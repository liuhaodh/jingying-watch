package com.jingying.watch;

import android.media.MediaDataSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * 把 tar 里的一个视频成员伪装成普通媒体文件，交给 MediaMetadataRetriever 取封面帧。
 *
 * 只读 MediaMetadataRetriever 真正要的那几段（moov/首帧），不会为了封面把整段视频
 * 解出来；有本地离线 tar 时直接读本地文件，否则按 Range 读 WebDAV。
 */
public final class TarMediaDataSource extends MediaDataSource {

    private static final int CHUNK = 256 * 1024;

    private final App app;
    private final String archiveRel;
    private final long memberOffset;
    private final long size;
    private final Object lock = new Object();

    private RandomAccessFile local;
    private boolean localChecked;
    private byte[] chunk;
    private long chunkStart;
    private int chunkLength;
    private boolean closed;

    public TarMediaDataSource(App app, RemoteFile member) {
        this.app = app;
        this.archiveRel = member == null ? "" : member.archiveRel;
        this.memberOffset = member == null ? 0L : member.offset;
        this.size = member == null ? 0L : member.size;
    }

    @Override
    public int readAt(long position, byte[] buffer, int bufferOffset, int bufferSize)
            throws IOException {
        synchronized (lock) {
            if (closed || size <= 0 || position < 0 || position >= size) {
                return -1;
            }
            int want = (int) Math.min(bufferSize, size - position);
            if (want <= 0) {
                return -1;
            }
            if (chunk != null && position >= chunkStart
                    && position + want <= chunkStart + chunkLength) {
                System.arraycopy(chunk, (int) (position - chunkStart), buffer,
                        bufferOffset, want);
                return want;
            }
            int fetch = (int) Math.min(Math.max(want, CHUNK), size - position);
            byte[] data = readBytes(position, fetch);
            if (data == null || data.length == 0) {
                return -1;
            }
            chunk = data;
            chunkStart = position;
            chunkLength = data.length;
            int copy = Math.min(want, data.length);
            System.arraycopy(data, 0, buffer, bufferOffset, copy);
            return copy;
        }
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            chunk = null;
            if (local != null) {
                try {
                    local.close();
                } catch (IOException ignored) {
                }
                local = null;
            }
        }
    }

    private byte[] readBytes(long position, int length) throws IOException {
        File tar = app.offline.savedTarFor(archiveRel);
        if (tar != null && tar.isFile()) {
            return readLocal(tar, position, length);
        }
        DavClient client = app.dav();
        if (client == null) {
            throw new IOException("还没有配置 WebDAV");
        }
        try (DavClient.RangeStream stream = client.openStream(archiveRel,
                memberOffset + position)) {
            if (memberOffset + position > 0 && !stream.partial) {
                throw new IOException("服务器不支持 Range，无法取封面帧");
            }
            byte[] buffer = new byte[length];
            int read = 0;
            while (read < length) {
                int count = stream.stream.read(buffer, read, length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read == 0) {
                return null;
            }
            if (read == length) {
                return buffer;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(buffer, 0, trimmed, 0, read);
            return trimmed;
        } catch (DavClient.DavException exc) {
            throw new IOException(exc.getMessage() == null ? "读取作品包失败" : exc.getMessage());
        }
    }

    private byte[] readLocal(File tar, long position, int length) throws IOException {
        if (!localChecked) {
            localChecked = true;
            local = new RandomAccessFile(tar, "r");
        }
        if (local == null) {
            return null;
        }
        long start = memberOffset + position;
        if (start < 0 || start >= local.length()) {
            return null;
        }
        int want = (int) Math.min(length, local.length() - start);
        byte[] buffer = new byte[want];
        local.seek(start);
        int read = 0;
        while (read < want) {
            int count = local.read(buffer, read, want - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        if (read == 0) {
            return null;
        }
        if (read == want) {
            return buffer;
        }
        byte[] trimmed = new byte[read];
        System.arraycopy(buffer, 0, trimmed, 0, read);
        return trimmed;
    }
}
