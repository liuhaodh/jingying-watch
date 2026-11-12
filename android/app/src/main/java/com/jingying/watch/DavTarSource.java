package com.jingying.watch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 从 WebDAV 上的 tar 里按偏移读数据。
 *
 * 每次 `read/open` 都是一次 Range GET（`DavClient.openStream`）；服务器回 200
 * 而不是 206 时抛 {@link TarReader.RangeUnsupportedException}，让 ArchiveStore
 * 退回「整包下到本地再解」。之所以单独一个类：`TarReader` 本身要能在桌面 JVM
 * 上做单元测试，不能依赖 Android 的 `DavClient`。
 */
public final class DavTarSource implements TarReader.Source {

    private final DavClient client;
    private final String rel;
    private final long size;

    public DavTarSource(DavClient client, String rel, long size) {
        this.client = client;
        this.rel = rel;
        this.size = size;
    }

    @Override
    public long length() {
        return size;
    }

    /** 列 tar 头是一串连续的 512B 小读；一次预读 64KB，别每个头都发一次 HTTPS Range 请求。 */
    private static final int PREFETCH = 64 * 1024;
    private byte[] cache;
    private long cacheStart = -1;

    @Override
    public synchronized byte[] read(long offset, int length) throws IOException {
        int want = Math.max(1, length);
        if (want > PREFETCH || offset < 0 || (size > 0 && offset >= size)) {
            return readDirect(offset, want);
        }
        boolean hit = cache != null && offset >= cacheStart
                && offset + want <= cacheStart + cache.length;
        // 上次已经读到包尾，缓存再怎么读也不会变长，别反复发请求
        boolean exhausted = cache != null && cacheStart == offset && cache.length < want;
        if (!hit && !exhausted) {
            int span = (int) Math.min(PREFETCH, size > 0 ? size - offset : PREFETCH);
            if (span <= 0) {
                return new byte[0];
            }
            cache = readDirect(offset, span);
            cacheStart = offset;
        }
        int from = (int) (offset - cacheStart);
        if (cache == null || from < 0 || from >= cache.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(cache, from, Math.min(cache.length, from + want));
    }

    private byte[] readDirect(long offset, int want) throws IOException {
        try (DavClient.RangeStream stream = openRange(offset)) {
            byte[] buffer = new byte[want];
            int read = 0;
            while (read < want) {
                int count = stream.stream.read(buffer, read, want - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return read == want ? buffer : Arrays.copyOf(buffer, read);
        }
    }

    @Override
    public InputStream open(long offset, long length) throws IOException {
        DavClient.RangeStream stream = openRange(offset);
        final InputStream input = stream.stream;
        final long limit = Math.max(0L, length);
        return new FilterInputStream(input) {
            private long remaining = limit;
            private boolean closed;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int value = super.read();
                if (value >= 0) {
                    remaining--;
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int want = (int) Math.min(len, remaining);
                int read = super.read(buffer, off, want);
                if (read > 0) {
                    remaining -= read;
                }
                return read;
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    input.close();
                } finally {
                    stream.close();
                }
            }
        };
    }

    private DavClient.RangeStream openRange(long offset) throws IOException {
        try {
            DavClient.RangeStream stream = client.openStream(rel, offset);
            if (offset > 0 && !stream.partial) {
                stream.close();
                throw new TarReader.RangeUnsupportedException("服务器不支持 Range");
            }
            return stream;
        } catch (DavClient.DavException exc) {
            String message = exc.getMessage();
            if (offset > 0 && message != null && message.contains("Range")) {
                throw new TarReader.RangeUnsupportedException(message);
            }
            throw new IOException(message == null ? "读取作品包失败" : message, exc);
        }
    }

    @Override
    public void close() {
    }
}
