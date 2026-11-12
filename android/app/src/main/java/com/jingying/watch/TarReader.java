package com.jingying.watch;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读「净影 · 电脑版」2026-11-08 起的作品包（`<作品id>.tar`）。
 *
 * 电脑版用 Python `tarfile.open(..., "w")` 打包，格式是 PAX（中文文件名会带
 * `@PaxHeader` 记录），tar 里保留一层 `作者_文案_时间/`。这里不依赖第三方库：
 * - `list()` 只按 512 字节头逐条往下走，数据块直接跳过（走 Range 时不需要下载视频本体）；
 * - `extract()` / `readText()` 通过 `Source` 从指定偏移读一段，支持「边下边解」。
 *
 * 只支持普通文件成员（忽略目录/链接/设备文件），成员名一律压成 basename，
 * 解包时不会因为 tar 里的相对路径写到缓存目录外面。
 */
public final class TarReader {

    private static final int BLOCK = 512;
    private static final int MAX_MEMBERS = 5000;
    private static final int PAX_LIMIT = 64 * 1024;

    private TarReader() {
    }

    /** tar 里的一个普通文件成员。 */
    public static final class Member {
        /** 只取文件名（不含上层目录）。 */
        public final String name;
        /** 完整路径（仅用于调试/落盘日志）。 */
        public final String path;
        public final long size;
        /** 数据在 tar 里的起始偏移。 */
        public final long offset;

        Member(String name, String path, long size, long offset) {
            this.name = name;
            this.path = path;
            this.size = Math.max(0L, size);
            this.offset = Math.max(0L, offset);
        }
    }

    /** 读取 tar 数据的来源：本地文件或 WebDAV（Range）。 */
    public interface Source extends Closeable {
        /** 文件总长度，未知时返回 -1。 */
        long length();

        /** 从 offset 读最多 length 字节，返回实际读到的内容。 */
        byte[] read(long offset, int length) throws IOException;

        /** 从 offset 开始的有界流；读满 length 或到 EOF 停止，close() 会关掉底层连接。 */
        InputStream open(long offset, long length) throws IOException;
    }

    /** 服务器不支持 Range 时抛这个，让上层退回「先整包下载再本地解包」。 */
    public static final class RangeUnsupportedException extends IOException {
        public RangeUnsupportedException(String message) {
            super(message);
        }
    }

    public static boolean looksLikeTar(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".tar");
    }

    // ---------- 列表 ----------
    public static List<Member> list(Source source) throws IOException {
        List<Member> members = new ArrayList<>();
        long length = source.length();
        long pos = 0;
        int guard = 0;
        String pendingName = null;
        long pendingSize = -1;
        while (guard++ < MAX_MEMBERS) {
            if (length > 0 && pos + BLOCK > length) {
                break;
            }
            byte[] header = source.read(pos, BLOCK);
            if (header == null || header.length < BLOCK) {
                break;
            }
            if (isZeroBlock(header)) {
                break;
            }
            if (!checksumOk(header)) {
                throw new IOException("作品包格式不对（tar 头校验失败）");
            }
            char type = (char) (header[156] & 0xff);
            long headerSize = parseNumber(header, 124, 12);
            long dataOffset = pos + BLOCK;

            if (type == 'x' || type == 'g') {
                // PAX 扩展头：path/size 覆盖下一条成员
                if (type == 'x') {
                    byte[] data = source.read(dataOffset,
                            (int) Math.min(headerSize, PAX_LIMIT));
                    Map<String, String> pax = parsePax(data);
                    String path = pax.get("path");
                    if (path != null && !path.isEmpty()) {
                        pendingName = path;
                    }
                    String sizeValue = pax.get("size");
                    if (sizeValue != null) {
                        try {
                            pendingSize = Long.parseLong(sizeValue.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                pos = dataOffset + blocks(headerSize);
                continue;
            }
            if (type == 'L' || type == 'K') {
                byte[] data = source.read(dataOffset,
                        (int) Math.min(headerSize, 4096));
                String value = new String(data, StandardCharsets.UTF_8).trim();
                if (type == 'L' && !value.isEmpty()) {
                    pendingName = value;
                }
                pos = dataOffset + blocks(headerSize);
                continue;
            }

            String path = pendingName != null ? pendingName : headerName(header);
            long size = pendingSize >= 0 ? pendingSize : headerSize;
            boolean regular = type == '0' || type == 0 || type == '7';
            if (regular) {
                String base = safeName(path);
                if (!base.isEmpty()) {
                    members.add(new Member(base, path, size, dataOffset));
                }
            }
            pendingName = null;
            pendingSize = -1;
            pos = dataOffset + blocks(size);
        }
        if (members.isEmpty() && length > 0) {
            throw new IOException("作品包里没有可用的文件");
        }
        return members;
    }

    // ---------- 读取 / 解包 ----------
    public static String readText(Source source, Member member, int maxBytes)
            throws IOException {
        if (member == null) {
            return "";
        }
        int limit = (int) Math.min(Math.max(0L, member.size), Math.max(1, maxBytes));
        if (limit <= 0) {
            return "";
        }
        byte[] bytes;
        try (InputStream input = source.open(member.offset, limit)) {
            bytes = new byte[limit];
            int read = 0;
            while (read < limit) {
                int count = input.read(bytes, read, limit - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read <= 0) {
                return "";
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** 把成员解到 target（先写 .part 再改名）；返回实际写入字节数。 */
    public static long extract(Source source, Member member, File target)
            throws IOException {
        if (member == null || target == null) {
            return 0L;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("解包目录创建失败：" + parent.getAbsolutePath());
        }
        File part = new File(target.getParentFile(), target.getName() + ".part");
        long written = 0;
        try {
            try (InputStream input = source.open(member.offset, member.size);
                 FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[256 * 1024];
                while (written < member.size) {
                    int want = (int) Math.min(buffer.length, member.size - written);
                    int read = input.read(buffer, 0, want);
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    written += read;
                }
            }
            if (member.size > 0 && written < member.size) {
                throw new IOException("作品包数据不完整（" + written + "/" + member.size + "）");
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!part.renameTo(target)) {
                throw new IOException("解包文件改名失败：" + target.getName());
            }
            return written;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    // ---------- Source 实现 ----------
    /** 本地 tar 文件。 */
    public static final class FileSource implements Source {
        private final File file;

        public FileSource(File file) {
            this.file = file;
        }

        @Override
        public long length() {
            return file.isFile() ? file.length() : -1L;
        }

        @Override
        public byte[] read(long offset, int length) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long available = raf.length() - offset;
                if (available <= 0) {
                    return new byte[0];
                }
                int want = (int) Math.min(Math.max(0, length), available);
                byte[] buffer = new byte[want];
                raf.seek(offset);
                int read = 0;
                while (read < want) {
                    int count = raf.read(buffer, read, want - read);
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
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(Math.max(0L, offset));
            return new InputStream() {
                private long remaining = Math.max(0L, length);
                private boolean closed;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) {
                        return -1;
                    }
                    int value = raf.read();
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
                    int read = raf.read(buffer, off, want);
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
                    raf.close();
                }
            };
        }

        @Override
        public void close() {
        }
    }

    // ---------- tar 头解析 ----------
    private static boolean isZeroBlock(byte[] header) {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean checksumOk(byte[] header) {
        long expected = parseNumber(header, 148, 8);
        if (expected <= 0) {
            // 少数写入器用空格表示「不校验」
            return true;
        }
        long sum = 0;
        for (int index = 0; index < BLOCK; index++) {
            boolean checksumField = index >= 148 && index < 156;
            sum += checksumField ? (byte) ' ' : (header[index] & 0xff);
        }
        return sum == expected;
    }

    private static String headerName(byte[] header) {
        String name = string(header, 0, 100);
        String prefix = string(header, 345, 155);
        if (!prefix.isEmpty() && header[257] != 0) {
            return prefix + "/" + name;
        }
        return name;
    }

    private static String string(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(data.length, offset + length);
        while (end < limit && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseNumber(byte[] data, int offset, int length) {
        if (offset >= data.length) {
            return 0L;
        }
        // GNU base-256（超大文件的 size 用二进制）
        if ((data[offset] & 0x80) != 0) {
            long value = data[offset] & 0x7f;
            for (int index = 1; index < length && offset + index < data.length; index++) {
                value = (value << 8) | (data[offset + index] & 0xff);
            }
            return value;
        }
        String text = string(data, offset, length).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        long value = 0;
        boolean seen = false;
        for (int index = 0; index < text.length(); index++) {
            char valueChar = text.charAt(index);
            if (valueChar < '0' || valueChar > '7') {
                if (seen) {
                    break;
                }
                continue;
            }
            seen = true;
            value = value * 8 + (valueChar - '0');
        }
        return seen ? value : 0L;
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || data.length == 0) {
            return result;
        }
        // PAX 记录长度是「字节数」，不能用 String 的下标切（中文是多字节）。
        int pos = 0;
        while (pos < data.length) {
            int space = -1;
            int limit = Math.min(data.length, pos + 20);
            for (int index = pos; index < limit; index++) {
                if (data[index] == ' ') {
                    space = index;
                    break;
                }
            }
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos,
                        StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException exc) {
                break;
            }
            if (length <= 0 || pos + length > data.length) {
                break;
            }
            int equals = -1;
            for (int index = space + 1; index < pos + length; index++) {
                if (data[index] == '=') {
                    equals = index;
                    break;
                }
            }
            if (equals > space) {
                String key = new String(data, space + 1, equals - space - 1,
                        StandardCharsets.UTF_8).trim();
                int valueEnd = pos + length;
                while (valueEnd > equals + 1
                        && (data[valueEnd - 1] == '\n' || data[valueEnd - 1] == '\r')) {
                    valueEnd--;
                }
                String value = new String(data, equals + 1, valueEnd - equals - 1,
                        StandardCharsets.UTF_8);
                if (!key.isEmpty()) {
                    result.put(key, value);
                }
            }
            pos += length;
        }
        return result;
    }

    private static long blocks(long size) {
        return ((Math.max(0L, size) + BLOCK - 1) / BLOCK) * BLOCK;
    }

    /** 只保留文件名，拒绝空名/`.`/`..`/带路径分隔符的怪名字。 */
    public static String safeName(String path) {
        String value = path == null ? "" : path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.trim();
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return "";
        }
        return value;
    }
}
