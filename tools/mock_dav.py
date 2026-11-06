# -*- coding: utf-8 -*-
"""净影 · 观看已下载 —— 本地测试用 WebDAV 服务（只用标准库）。

用于在没有真实网盘时，把 `tools/fixtures/webdav` 目录当作 WebDAV 片库：

    python tools/mock_dav.py --port 8088

支持：OPTIONS / PROPFIND(Depth 0/1) / GET / HEAD + Range / DELETE / MKCOL / PUT / MOVE，
Basic 鉴权。默认账号 test / test123，访问地址 http://127.0.0.1:8088/webdav/
"""
import argparse
import base64
import email.utils
import html
import os
import re
import shutil
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROPFIND_BODY = (
    '<?xml version="1.0" encoding="utf-8"?>'
    '<D:multistatus xmlns:D="DAV:">%s</D:multistatus>'
)
RESPONSE_BODY = (
    "<D:response>"
    "<D:href>{href}</D:href>"
    "<D:propstat><D:prop>"
    "<D:displayname>{name}</D:displayname>"
    "<D:resourcetype>{resourcetype}</D:resourcetype>"
    "{length}{mtime}{etag}"
    "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
    "</D:response>"
)

TEXT_TYPES = {
    ".txt": "text/plain; charset=utf-8",
    ".json": "application/json",
    ".html": "text/html; charset=utf-8",
}
MEDIA_TYPES = {
    ".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm",
    ".mkv": "video/x-matroska", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".png": "image/png", ".webp": "image/webp", ".gif": "image/gif",
    ".mp3": "audio/mpeg", ".wav": "audio/wav", ".m4a": "audio/mp4",
    ".aac": "audio/aac", ".flac": "audio/flac",
}


class Store:
    def __init__(self, root):
        self.root = os.path.abspath(root)
        self.lock = threading.Lock()

    def resolve(self, rel):
        rel = (rel or "").replace("\\", "/").strip("/")
        path = os.path.abspath(os.path.join(self.root, *[p for p in rel.split("/") if p]))
        if path != self.root and not path.startswith(self.root + os.sep):
            raise PermissionError(path)
        return path

    def rel_of(self, path):
        rel = os.path.relpath(path, self.root).replace("\\", "/")
        return "" if rel == "." else rel


class Handler(BaseHTTPRequestHandler):
    server_version = "MockDAV/1.0"
    protocol_version = "HTTP/1.1"

    # ---------- 基础设施 ----------
    @property
    def store(self):
        return self.server.store

    @property
    def config(self):
        return self.server.config

    def log_message(self, fmt, *args):
        if self.config.get("quiet"):
            return
        sys.stderr.write("[dav] %s - %s\n" % (self.address_string(), fmt % args))
        sys.stderr.flush()

    def _unauthorized(self):
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="mockdav"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _authorized(self):
        user = self.config.get("user") or ""
        if not user:
            return True
        header = self.headers.get("Authorization", "")
        if not header.lower().startswith("basic "):
            return False
        try:
            raw = base64.b64decode(header.split(None, 1)[1]).decode("utf-8")
        except Exception:  # noqa: BLE001
            return False
        got_user, _, got_pass = raw.partition(":")
        return got_user == user and got_pass == (self.config.get("password") or "")

    def _split_path(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        prefix = self.config.get("prefix") or ""
        if prefix and path.startswith(prefix):
            path = path[len(prefix):]
        if not path.startswith("/"):
            path = "/" + path
        return path

    def _send_bytes(self, body, status=200, content_type="text/plain; charset=utf-8",
                    extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # ---------- 方法 ----------
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1, 2")
        self.send_header("Allow", "OPTIONS, GET, HEAD, PROPFIND, DELETE, MKCOL, PUT, MOVE")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_MKCOL(self):
        if not self._authorized():
            return self._unauthorized()
        rel = self._split_path().strip("/")
        if not rel:
            return self._send_bytes(b"forbidden", 403)
        try:
            path = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        if os.path.exists(path):
            return self._send_bytes(b"exists", 405)
        if not os.path.isdir(os.path.dirname(path)):
            return self._send_bytes(b"conflict", 409)
        try:
            with self.store.lock:
                os.makedirs(path)
        except OSError as exc:
            return self._send_bytes(str(exc).encode("utf-8"), 500)
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PUT(self):
        if not self._authorized():
            return self._unauthorized()
        rel = self._split_path().strip("/")
        if not rel:
            return self._send_bytes(b"forbidden", 403)
        try:
            path = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else b""
        existed = os.path.exists(path)
        try:
            with self.store.lock:
                parent = os.path.dirname(path)
                if parent and not os.path.isdir(parent):
                    os.makedirs(parent)
                with open(path, "wb") as handle:
                    handle.write(data)
        except OSError as exc:
            return self._send_bytes(str(exc).encode("utf-8"), 500)
        self.send_response(204 if existed else 201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _dest_path(self):
        destination = self.headers.get("Destination") or ""
        if not destination:
            return None
        parsed = urllib.parse.urlsplit(destination)
        path = urllib.parse.unquote(parsed.path or "")
        prefix = self.config.get("prefix") or ""
        if prefix and path.startswith(prefix):
            path = path[len(prefix):]
        try:
            return self.store.resolve(path.strip("/"))
        except PermissionError:
            return None

    def do_MOVE(self):
        if not self._authorized():
            return self._unauthorized()
        rel = self._split_path().strip("/")
        if not rel:
            return self._send_bytes(b"forbidden", 403)
        try:
            src = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        if not os.path.exists(src):
            return self._send_bytes(b"not found", 404)
        dest = self._dest_path()
        if dest is None:
            return self._send_bytes(b"bad destination", 400)
        overwrite = (self.headers.get("Overwrite") or "T").strip().upper() != "F"
        if os.path.exists(dest):
            if not overwrite:
                return self._send_bytes(b"precondition failed", 412)
            try:
                with self.store.lock:
                    if os.path.isdir(dest):
                        shutil.rmtree(dest)
                    else:
                        os.remove(dest)
            except OSError as exc:
                return self._send_bytes(str(exc).encode("utf-8"), 500)
        if not os.path.isdir(os.path.dirname(dest)):
            return self._send_bytes(b"conflict", 409)
        try:
            with self.store.lock:
                os.replace(src, dest)
        except OSError:
            try:
                with self.store.lock:
                    shutil.move(src, dest)
            except OSError as exc:
                return self._send_bytes(str(exc).encode("utf-8"), 500)
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_DELETE(self):
        if not self._authorized():
            return self._unauthorized()
        rel = self._split_path().strip("/")
        if not rel:
            return self._send_bytes(b"forbidden", 403)
        try:
            path = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        if not os.path.exists(path):
            return self._send_bytes(b"not found", 404)
        try:
            with self.store.lock:
                if os.path.isdir(path):
                    shutil.rmtree(path)
                else:
                    os.remove(path)
        except OSError as exc:
            return self._send_bytes(str(exc).encode("utf-8"), 500)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        if not self._authorized():
            return self._unauthorized()
        if self.command == "PROPFIND":
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
        rel = self._split_path().strip("/")
        try:
            path = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        if not os.path.exists(path):
            return self._send_bytes(b"not found", 404)
        depth = (self.headers.get("Depth") or "1").strip()
        try:
            depth = int(depth)
        except ValueError:
            depth = 1
        entries = [path]
        if depth >= 1 and os.path.isdir(path):
            with self.store.lock:
                names = sorted(os.listdir(path))
            entries += [os.path.join(path, name) for name in names]
        chunks = []
        for item in entries:
            rel_item = self.store.rel_of(item)
            href = self._href_for(rel_item, os.path.isdir(item))
            is_dir = os.path.isdir(item)
            try:
                stat = os.stat(item)
            except OSError:
                continue
            length = "" if is_dir else (
                "<D:getcontentlength>%d</D:getcontentlength>" % stat.st_size)
            mtime = ("<D:getlastmodified>%s</D:getlastmodified>"
                     % email.utils.formatdate(stat.st_mtime, usegmt=True))
            etag = '<D:getetag>"%x-%x"</D:getetag>' % (int(stat.st_mtime), stat.st_size)
            chunks.append(RESPONSE_BODY.format(
                href=html.escape(href),
                name=html.escape(os.path.basename(item) or "/"),
                resourcetype="<D:collection/>" if is_dir else "",
                length=length, mtime=mtime, etag=etag,
            ))
        body = (PROPFIND_BODY % "".join(chunks)).encode("utf-8")
        self._send_bytes(body, 207, "application/xml; charset=utf-8")

    def _href_for(self, rel, is_dir):
        prefix = self.config.get("prefix") or ""
        encoded = "/".join(urllib.parse.quote(p, safe="") for p in rel.split("/") if p)
        href = f"{prefix}/{encoded}" if encoded else f"{prefix}/"
        if is_dir and not href.endswith("/"):
            href += "/"
        return href

    def do_HEAD(self):
        self._serve_file(head=True)

    def do_GET(self):
        self._serve_file(head=False)

    def _serve_file(self, head=False):
        if not self._authorized():
            return self._unauthorized()
        rel = self._split_path().strip("/")
        try:
            path = self.store.resolve(rel)
        except PermissionError:
            return self._send_bytes(b"forbidden", 403)
        if not os.path.isfile(path):
            return self._send_bytes(b"not found", 404)
        size = os.path.getsize(path)
        ext = os.path.splitext(path)[1].lower()
        content_type = MEDIA_TYPES.get(ext) or TEXT_TYPES.get(ext) or "application/octet-stream"
        start, end = 0, size - 1
        status = 200
        range_header = self.headers.get("Range") or ""
        matched = re.match(r"bytes=(\d*)-(\d*)$", range_header.strip())
        if matched:
            first, last = matched.group(1), matched.group(2)
            if first:
                start = int(first)
                end = int(last) if last else size - 1
            elif last:
                start = max(0, size - int(last))
                end = size - 1
            if start >= size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, size - 1)
            status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Last-Modified", email.utils.formatdate(
            os.path.getmtime(path), usegmt=True))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        with open(path, "rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, store, config):
        super().__init__(addr, Handler)
        self.store = store
        self.config = config


def main(argv=None):
    parser = argparse.ArgumentParser(description="Mock WebDAV for JingYing Watch")
    parser.add_argument("--root", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "fixtures", "webdav"))
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--prefix", default="/webdav")
    parser.add_argument("--user", default="test")
    parser.add_argument("--password", default="test123")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        raise SystemExit(f"片库目录不存在：{root}")
    config = {"prefix": args.prefix.rstrip("/"), "user": args.user,
              "password": args.password, "quiet": args.quiet}
    server = Server((args.host, args.port), Store(root), config)
    print(f"Mock WebDAV: http://127.0.0.1:{args.port}{args.prefix}/  root={root}")
    print(f"账号：{args.user} / {args.password}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
