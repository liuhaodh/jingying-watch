package com.jingying.watch;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * WebDAV 客户端：PROPFIND 列目录、HEAD 取大小、Range GET 拉字节、DELETE 删作品/作者。
 * 底层用自带的 HttpLite（Socket 直连），因为 Android 的 HttpURLConnection
 * 不允许 PROPFIND 这类自定义方法。
 */
public final class DavClient {

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:displayname/><d:resourcetype/><d:getcontentlength/>"
                    + "<d:getlastmodified/><d:getetag/>"
                    + "</d:prop></d:propfind>";

    public final String baseUrl;
    public final String remoteRoot;
    private final String scheme;
    private final String host;
    private final int port;
    private final String basePath;
    private final String user;
    private final String pass;
    private final boolean insecure;
    private final SSLContext trustAll;

    public DavClient(String url, String user, String pass, String remoteRoot, boolean insecure) {
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("WebDAV 地址为空");
        }
        if (raw.indexOf("://") < 0) {
            raw = "https://" + raw;
        }
        try {
            URI parsed = URI.create(raw);
            this.scheme = parsed.getScheme().toLowerCase(Locale.US);
            this.host = parsed.getHost();
            int parsedPort = parsed.getPort();
            this.port = parsedPort > 0 ? parsedPort : -1;
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            this.basePath = path;
        } catch (Exception exc) {
            throw new IllegalArgumentException("WebDAV 地址不合法：" + url);
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("WebDAV 地址不合法：" + url);
        }
        this.baseUrl = raw;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        String root = remoteRoot == null ? "" : remoteRoot.trim();
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.remoteRoot = root;
        this.insecure = insecure;
        this.trustAll = insecure ? buildTrustAll() : null;
    }

    public String seed() {
        return remoteRoot;
    }

    // ---------- 地址 ----------
    public String urlFor(String rel, boolean dir) {
        StringBuilder path = new StringBuilder(basePath == null ? "" : basePath);
        if (rel != null && !rel.isEmpty()) {
            for (String part : rel.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (path.length() == 0 || path.charAt(path.length() - 1) != '/') {
                    path.append('/');
                }
                path.append(encodeSegment(part));
            }
        }
        if (path.length() == 0) {
            path.append('/');
        }
        StringBuilder url = new StringBuilder(scheme).append("://").append(host);
        if (port > 0) {
            url.append(':').append(port);
        }
        url.append(path);
        if (dir && url.charAt(url.length() - 1) != '/') {
            url.append('/');
        }
        return url.toString();
    }

    private static String encodeSegment(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (Exception exc) {
            return text;
        }
    }

    private String relFromHref(String href) {
        if (href == null) {
            return "";
        }
        String value = href.trim();
        if (value.indexOf("://") >= 0) {
            try {
                value = new URL(value).getPath();
            } catch (Exception ignored) {
            }
        }
        try {
            value = URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        String base = basePath == null ? "" : basePath;
        if (!base.isEmpty() && !"/".equals(base)) {
            if (value.toLowerCase(Locale.US).startsWith((base + "/").toLowerCase(Locale.US))) {
                value = value.substring(base.length());
            } else if (value.equalsIgnoreCase(base)) {
                value = "/";
            }
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    // ---------- 请求 ----------
    private Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (!user.isEmpty()) {
            String token = android.util.Base64.encodeToString(
                    (user + ":" + pass).getBytes(java.nio.charset.Charset.forName("UTF-8")),
                    android.util.Base64.NO_WRAP);
            headers.put("Authorization", "Basic " + token);
        }
        return headers;
    }

    private HttpLite.Response send(String method, String rel, boolean dir,
                                   Map<String, String> extra, byte[] payload)
            throws IOException {
        URL url = new URL(urlFor(rel, dir));
        Map<String, String> headers = authHeaders();
        if (extra != null) {
            headers.putAll(extra);
        }
        return HttpLite.request(method, url, headers, payload, 15000, 60000,
                trustAll, insecure);
    }

    public List<DavEntry> propfind(String rel, int depth) throws DavException {
        Map<String, String> extra = new HashMap<>();
        extra.put("Depth", String.valueOf(depth));
        extra.put("Content-Type", "application/xml; charset=utf-8");
        byte[] payload;
        try {
            payload = PROPFIND_BODY.getBytes("UTF-8");
        } catch (Exception exc) {
            throw new DavException("编码失败");
        }
        HttpLite.Response response = null;
        try {
            response = send("PROPFIND", rel, true, extra, payload);
            int code = response.status;
            if (code == 207 || code == 200 || code == 201) {
                List<DavEntry> parsed = parseMultiStatus(response.body);
                String self = rel == null ? "" : rel.replaceAll("^/+|/+$", "");
                if (!self.isEmpty()) {
                    List<DavEntry> filtered = new ArrayList<>(parsed.size());
                    for (DavEntry entry : parsed) {
                        if (!entry.rel.equals(self)) {
                            filtered.add(entry);
                        }
                    }
                    parsed = filtered;
                }
                return parsed;
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("连接 WebDAV 失败：" + describe(exc), exc);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** 打开一个普通 GET/HEAD 连接。媒体下载走系统 TLS 栈（更稳），PROPFIND 走 HttpLite。 */
    private HttpURLConnection openConnection(String method, String rel, long start)
            throws IOException {
        URL url = new URL(urlFor(rel, false));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            if (trustAll != null) {
                https.setSSLSocketFactory(trustAll.getSocketFactory());
                https.setHostnameVerifier((hostname, session) -> true);
            }
        }
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "JingYingWatch/1.0 (Android; webdav)");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> entry : authHeaders().entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if (start > 0) {
            conn.setRequestProperty("Range", "bytes=" + start + "-");
        }
        return conn;
    }

    /** 先发 bytes=0-0 的 GET（会跟随 302，拿到 CDN 上真实的总长度），HEAD 只做兜底。 */
    public long sizeOf(String rel) throws DavException {
        HttpURLConnection probe = null;
        try {
            probe = openConnection("GET", rel, 0);
            probe.setRequestProperty("Range", "bytes=0-0");
            int code = probe.getResponseCode();
            if (code == 206) {
                String contentRange = probe.getHeaderField("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    String total = contentRange.substring(contentRange.indexOf('/') + 1).trim();
                    try {
                        long value = Long.parseLong(total);
                        if (value > 0) {
                            return value;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (code >= 200 && code < 300) {
                long length = parseLength(probe.getHeaderField("Content-Length"));
                if (length >= 0) {
                    return length;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (probe != null) {
                probe.disconnect();
            }
        }
        HttpURLConnection head = null;
        try {
            head = openConnection("HEAD", rel, 0);
            int code = head.getResponseCode();
            if (code >= 200 && code < 300) {
                long length = parseLength(head.getHeaderField("Content-Length"));
                if (length >= 0) {
                    return length;
                }
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("读取文件大小失败：" + describe(exc), exc);
        } finally {
            if (head != null) {
                head.disconnect();
            }
        }
    }
    public RangeStream openStream(String rel, long start) throws DavException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("GET", rel, start);
            int code = conn.getResponseCode();
            if (code == 200) {
                long total = parseLength(conn.getHeaderField("Content-Length"));
                if (start > 0) {
                    conn.disconnect();
                    throw new DavException("服务器不支持断点续传（Range）");
                }
                return new RangeStream(conn.getInputStream(), conn::disconnect, total, 0, false);
            }
            if (code == 206) {
                long total = -1;
                long offset = start;
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) {
                    String body = contentRange.trim();
                    int slash = body.indexOf('/');
                    if (slash > 0) {
                        total = parseLength(body.substring(slash + 1).trim());
                    }
                    int space = body.indexOf(' ');
                    int dash = body.indexOf('-');
                    if (space >= 0 && dash > space) {
                        offset = parseLength(body.substring(space + 1, dash).trim());
                    }
                }
                return new RangeStream(conn.getInputStream(), conn::disconnect, total, offset, true);
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            if (conn != null) {
                conn.disconnect();
            }
            throw exc;
        } catch (Exception exc) {
            if (conn != null) {
                conn.disconnect();
            }
            throw new DavException("读取 WebDAV 文件失败：" + describe(exc), exc);
        }
    }
    // ---------- 创建 / 写入 / 移动 ----------
    /** 建目录；405 表示已经存在，按成功处理。 */
    public void mkcol(String rel) throws DavException {
        if (rel == null || rel.trim().isEmpty()) {
            throw new DavException("目录路径为空");
        }
        HttpLite.Response response = null;
        try {
            response = send("MKCOL", rel, true, null, null);
            int code = response.status;
            if (code == 200 || code == 201 || code == 204 || code == 405) {
                return;
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("创建目录失败：" + describe(exc), exc);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** 逐层建目录（已存在的不报错）。 */
    public void mkcolPath(String rel) throws DavException {
        String value = rel == null ? "" : rel.trim();
        if (value.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(part);
            mkcol(current.toString());
        }
    }

    /** 写一个小文件（回收站的 meta.txt）。 */
    public void put(String rel, byte[] data, String contentType) throws DavException {
        Map<String, String> extra = new HashMap<>();
        extra.put("Content-Type", contentType == null
                ? "application/octet-stream" : contentType);
        HttpLite.Response response = null;
        try {
            response = send("PUT", rel, false, extra, data);
            int code = response.status;
            if (code == 200 || code == 201 || code == 204) {
                return;
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("写入文件失败：" + describe(exc), exc);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** 移动（重命名）文件或目录；回收站的移入/恢复都靠它。 */
    public void move(String srcRel, String destRel, boolean overwrite) throws DavException {
        if (srcRel == null || srcRel.trim().isEmpty()
                || destRel == null || destRel.trim().isEmpty()) {
            throw new DavException("移动路径为空");
        }
        Map<String, String> extra = new HashMap<>();
        extra.put("Destination", urlFor(destRel, false));
        extra.put("Overwrite", overwrite ? "T" : "F");
        HttpLite.Response response = null;
        try {
            response = send("MOVE", srcRel, true, extra, null);
            int code = response.status;
            if (code == 200 || code == 201 || code == 204) {
                return;
            }
            throw failure(code, srcRel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("移动失败：" + describe(exc), exc);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** 路径是否存在（PROPFIND Depth:0）。 */
    public boolean exists(String rel) throws DavException {
        if (rel == null || rel.trim().isEmpty()) {
            return false;
        }
        try {
            propfind(rel, 0);
            return true;
        } catch (DavException exc) {
            if (exc.status == 404) {
                return false;
            }
            throw exc;
        }
    }

    // ---------- 删除 ----------
    /** 删除一个文件或目录。空路径直接拒绝，避免把片库根目录删掉。 */
    public void delete(String rel) throws DavException {
        if (rel == null || rel.trim().isEmpty()) {
            throw new DavException("拒绝删除空路径");
        }
        HttpLite.Response response = null;
        try {
            Map<String, String> extra = new HashMap<>();
            // 删目录时显式声明递归，避免个别服务器只删空目录
            extra.put("Depth", "infinity");
            response = send("DELETE", rel, false, extra, null);
            int code = response.status;
            // 404 当作已经删掉了
            if (code == 200 || code == 202 || code == 204 || code == 404) {
                return;
            }
            throw failure(code, rel);
        } catch (DavException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new DavException("删除失败：" + describe(exc), exc);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 递归删除。先按 RFC 4918 一次删掉整个目录（服务器应递归处理），
     * 服务器不支持时（403/405/409/501…）再 PROPFIND 出子项逐个删。
     * 作者目录下可能有上千个作品，不能无条件一个个删。
     */
    public void deleteTree(String rel) throws DavException {
        try {
            delete(rel);
            return;
        } catch (DavException exc) {
            if (exc.status == 404) {
                return;
            }
            if (exc.status == 401) {
                throw exc;
            }
        }
        List<DavEntry> entries = propfind(rel, 1);
        for (DavEntry entry : entries) {
            if (entry.dir) {
                deleteTree(entry.rel);
            } else {
                delete(entry.rel);
            }
        }
        delete(rel);
    }

    public byte[] readAll(String rel, int maxBytes) throws DavException {
        RangeStream stream = openStream(rel, 0);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                if (output.size() > maxBytes) {
                    break;
                }
            }
            return output.toByteArray();
        } catch (IOException exc) {
            throw new DavException("读取文件失败：" + describe(exc), exc);
        } finally {
            stream.close();
        }
    }

    // ---------- 解析 ----------
    private List<DavEntry> parseMultiStatus(InputStream input) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(input, "UTF-8");
        List<DavEntry> entries = new ArrayList<>();
        String href = null;
        String display = null;
        long size = 0;
        long mtime = 0;
        boolean dir = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = localName(parser);
                switch (tag) {
                    case "response":
                        href = null;
                        display = null;
                        size = 0;
                        mtime = 0;
                        dir = false;
                        break;
                    case "collection":
                        dir = true;
                        break;
                    case "href":
                        href = parser.nextText().trim();
                        break;
                    case "displayname":
                        display = parser.nextText().trim();
                        break;
                    case "getcontentlength":
                        size = parseLength(parser.nextText().trim());
                        break;
                    case "getlastmodified":
                        mtime = parseHttpDate(parser.nextText().trim());
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = localName(parser);
                if ("response".equals(tag) && href != null) {
                    String rel = relFromHref(href);
                    String name = display == null || display.isEmpty()
                            ? lastSegment(rel) : display;
                    entries.add(new DavEntry(rel, name, dir, size, mtime));
                }
            }
            event = parser.next();
        }
        return entries;
    }

    private static String localName(XmlPullParser parser) {
        String name = parser.getName();
        return name == null ? "" : name;
    }

    private static String lastSegment(String rel) {
        int index = rel.lastIndexOf('/');
        return index < 0 ? rel : rel.substring(index + 1);
    }

    private static long parseLength(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exc) {
            return -1;
        }
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date date = format.parse(value);
                if (date != null) {
                    return date.getTime() / 1000L;
                }
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private DavException failure(int code, String rel) {
        String message;
        switch (code) {
            case 401:
            case 403:
                message = "WebDAV 鉴权失败，请检查账号和密码";
                break;
            case 404:
                message = "远程目录不存在：" + rel;
                break;
            case 405:
                message = "服务器不允许该请求：" + rel;
                break;
            case 423:
                message = "文件被锁定：" + rel;
                break;
            case 500:
            case 502:
            case 503:
            case 504:
                message = "网盘服务暂时不可用（" + code + "）";
                break;
            default:
                message = "WebDAV 请求失败（HTTP " + code + "）";
                break;
        }
        return new DavException(code, message, null);
    }

    private static String describe(Exception exc) {
        String message = exc.getMessage();
        return message == null || message.isEmpty() ? exc.getClass().getSimpleName() : message;
    }

    private static SSLContext buildTrustAll() {
        try {
            TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new java.security.SecureRandom());
            return context;
        } catch (Exception exc) {
            return null;
        }
    }

    /** 一次 Range GET 的响应。 */
    public static final class RangeStream implements Closeable {
        public final InputStream stream;
        public final long total;
        public final long start;
        public final boolean partial;
        private final Closeable source;

        RangeStream(InputStream stream, Closeable source, long total,
                    long start, boolean partial) {
            this.stream = stream;
            this.source = source;
            this.total = total;
            this.start = start;
            this.partial = partial;
        }

        @Override
        public void close() {
            if (source != null) {
                try {
                    source.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** WebDAV 请求失败。 */
    public static final class DavException extends Exception {
        public final int status;

        public DavException(String message) {
            super(message);
            this.status = 0;
        }

        public DavException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public DavException(String message, Throwable cause) {
            super(message, cause);
            this.status = 0;
        }
    }
}
