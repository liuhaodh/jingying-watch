package com.jingying.watch;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 最小可用的 USTAR 写入器：把一组文件流式写进一个 .tar。
 *
 * <p>只用 java.io，桌面 JVM 也能编译运行（WatchTests 里用 TarReader 做读写往返验证）。
 * 一次只缓冲 256KB，大视频不会整段进内存；每个成员的长度必须先知道，
 * 读到的字节数对不上会直接报错，避免生成「看起来能用、其实坏了」的包。
 */
public final class TarWriter implements Closeable {

    public static final int BLOCK = 512;
    private static final int BUFFER = 256 * 1024;

    /** 每写完一批字节回调一次；返回 false 表示用户取消了打包。 */
    public interface Progress {
        boolean onBytes(long writtenInCurrentFile);
    }

    private final OutputStream out;
    private final byte[] header = new byte[BLOCK];
    private final byte[] buffer = new byte[BUFFER];
    private boolean finished;
    private boolean closed;

    public TarWriter(OutputStream out) {
        if (out == null) {
            throw new IllegalArgumentException("out 不能为空");
        }
        this.out = out;
    }

    /** 写一个普通文件成员；input 由调用方负责关闭。 */
    public void writeFile(String path, long size, InputStream input, Progress progress)
            throws IOException {
        if (finished || closed) {
            throw new IOException("tar 已经结束，不能再写文件");
        }
        if (input == null) {
            throw new IOException("没有输入流：" + path);
        }
        if (size < 0) {
            throw new IOException("文件长度未知：" + path);
        }
        out.write(buildHeader(path, size));

        long written = 0;
        while (written < size) {
            int want = (int) Math.min(buffer.length, size - written);
            int read = input.read(buffer, 0, want);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            out.write(buffer, 0, read);
            written += read;
            if (progress != null && !progress.onBytes(written)) {
                throw new IOException("打包已取消");
            }
        }
        if (written != size) {
            throw new IOException("文件没读完整（" + path + "）："
                    + written + " / " + size + " 字节");
        }
        int padding = (int) (BLOCK - (size % BLOCK)) % BLOCK;
        if (padding > 0) {
            out.write(new byte[padding]);
        }
    }

    /** 写两个全零块，标志 tar 结束。 */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        out.write(new byte[BLOCK * 2]);
        finished = true;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            if (!finished) {
                finish();
            }
        } finally {
            closed = true;
            out.close();
        }
    }

    // ---------- USTAR 头 ----------
    private byte[] buildHeader(String path, long size) throws IOException {
        Arrays.fill(header, (byte) 0);
        String[] parts = splitPath(path);
        putBytes(header, 0, 100, parts[0]);                    // name
        putOctal(header, 100, 8, 0644);                        // mode
        putOctal(header, 108, 8, 0);                           // uid
        putOctal(header, 116, 8, 0);                           // gid
        putOctal(header, 124, 12, size);                       // size（11 位八进制，最大 8GB）
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        for (int index = 148; index < 156; index++) {          // checksum 先按 8 个空格累加
            header[index] = (byte) ' ';
        }
        header[156] = '0';                                     // 普通文件
        byte[] magic = {'u', 's', 't', 'a', 'r', 0};
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';
        header[264] = '0';
        putBytes(header, 345, 155, parts[1]);                  // prefix

        long sum = 0;
        for (int index = 0; index < BLOCK; index++) {
            sum += header[index] & 0xff;
        }
        // 标准写法：6 位八进制 + NUL + 空格
        char[] digits = new char[6];
        long rest = sum;
        for (int index = 5; index >= 0; index--) {
            digits[index] = (char) ('0' + (int) (rest & 7));
            rest >>>= 3;
        }
        for (int index = 0; index < 6; index++) {
            header[148 + index] = (byte) digits[index];
        }
        header[154] = 0;
        header[155] = ' ';
        return header;
    }

    private static void putOctal(byte[] target, int offset, int length, long value)
            throws IOException {
        char[] digits = new char[length - 1];
        long rest = value;
        for (int index = length - 2; index >= 0; index--) {
            digits[index] = (char) ('0' + (int) (rest & 7));
            rest >>>= 3;
        }
        if (rest != 0) {
            throw new IOException("数值超出 tar 字段范围：" + value);
        }
        for (int index = 0; index < digits.length; index++) {
            target[offset + index] = (byte) digits[index];
        }
        target[offset + length - 1] = 0;
    }

    private static void putBytes(byte[] target, int offset, int limit, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int count = Math.min(bytes.length, limit);
        System.arraycopy(bytes, 0, target, offset, count);
    }

    /** 把路径拆成 USTAR 的 name / prefix；清洗掉 ..、控制字符，超长时按 UTF-8 边界截断。 */
    static String[] splitPath(String path) {
        String value = cleanPath(path);
        if (bytes(value) <= 100) {
            return new String[]{value, ""};
        }
        // 优先在 '/' 处拆：name 放最后一段，prefix 放前面的目录
        int slash = value.lastIndexOf('/');
        while (slash > 0) {
            String name = value.substring(slash + 1);
            String prefix = value.substring(0, slash);
            if (bytes(name) <= 100 && bytes(prefix) <= 155) {
                return new String[]{name, prefix};
            }
            slash = value.lastIndexOf('/', slash - 1);
        }
        // 单段名字本身就超过 100 字节：截到 80 字节再加 hash，避免同名互相覆盖
        int last = value.lastIndexOf('/');
        String base = value.substring(last + 1);
        String prefix = last > 0 ? value.substring(0, last) : "";
        String shortened = base.substring(0, charLimit(base, 80))
                + "-" + Integer.toHexString(value.hashCode());
        if (bytes(prefix) > 155 || bytes(prefix) + 1 + bytes(shortened) > 255) {
            prefix = "";
        }
        return new String[]{shortened, prefix};
    }

    private static String cleanPath(String path) {
        String value = path == null ? "" : path.replace('\\', '/');
        StringBuilder result = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            String cleaned = part.replaceAll("[\\x00-\\x1f]", "_").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(cleaned);
        }
        return result.length() == 0 ? "file" : result.toString();
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 返回不超过 maxBytes 的字符下标，保证不把一个 UTF-8 字符切成两半。 */
    private static int charLimit(String value, int maxBytes) {
        int used = 0;
        int index = 0;
        while (index < value.length()) {
            int code = value.codePointAt(index);
            int width = new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8).length;
            if (used + width > maxBytes) {
                break;
            }
            used += width;
            index += Character.charCount(code);
        }
        return index;
    }
}
