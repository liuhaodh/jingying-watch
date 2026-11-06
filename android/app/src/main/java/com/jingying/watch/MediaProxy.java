package com.jingying.watch;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 边拉边播的本地代理：播放器访问 127.0.0.1 上的小 HTTP 服务，
 * 代理带着 WebDAV 鉴权去拉 Range，一边写缓存一边吐给播放器。
 *
 * 关键点：
 * - 拖动进度条时，目标位置没缓存就让后台从目标位置重新下载；已缓存区间保留，
 *   往回拖直接命中缓存；
 * - 「正在看」和「下一个」都会预取，滑到下一条不用等；
 * - 整段下完的文件会留到下次打开继续用，按容量上限做 LRU 清理。
 */
public final class MediaProxy {

    private static final int READ_BUFFER = 256 * 1024;
    private static final long WAIT_TIMEOUT_MS = 45000L;
    private static final long SEEK_SLACK = 4L * 1024 * 1024;

    private final Context context;
    private final File root;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "media-download");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService clientPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "media-http");
        thread.setDaemon(true);
        return thread;
    });
    /** 扫缓存、清缓存这类杂活单独一个线程，别和下载抢那 2 个线程。 */
    private final ExecutorService maintenancePool =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "media-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final Object trimLock = new Object();

    private volatile DavClient client;
    private volatile int port;
    private volatile boolean running;
    private ServerSocket server;
    private volatile long cacheLimit = 4L * 1024 * 1024 * 1024;

    public MediaProxy(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(this.context.getFilesDir(), "media");
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        // 扫缓存目录是纯磁盘 IO，App.onCreate 会同步走到这里，不能占用主线程
        maintenancePool.execute(this::sweepBrokenCache);
    }

    // ---------- 生命周期 ----------
    public void start() {
        if (running) {
            return;
        }
        try {
            server = new ServerSocket(0, 30, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            running = true;
            Thread thread = new Thread(this::acceptLoop, "media-accept");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException exc) {
            running = false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
    }

    public int port() {
        return port;
    }

    public void setClient(DavClient davClient) {
        this.client = davClient;
    }

    public void setCacheLimit(long bytes) {
        cacheLimit = Math.max(128L * 1024 * 1024, bytes);
        trimAsync();
    }

    public long cacheBytes() {
        return sizeOf(root);
    }

    public long cacheLimit() {
        return cacheLimit;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(30000);
                clientPool.execute(() -> handle(socket));
            } catch (IOException exc) {
                if (!running) {
                    return;
                }
            }
        }
    }

    // ---------- 对外 ----------
    /** 给播放器/图片用的本地地址。 */
    public String urlFor(String rel, String name, long size) {
        Entry entry = register(rel, name, size);
        return "http://127.0.0.1:" + port + "/m/" + entry.key + "/" + encode(entry.name);
    }

    /** 提前拉缓存（当前视频 / 下一个视频）。 */
    public void prefetch(String rel, String name, long size) {
        Entry entry = register(rel, name, size);
        synchronized (entry.lock) {
            entry.lastUsed = System.currentTimeMillis();
            if (entry.complete || entry.downloading || entry.cancel) {
                return;
            }
            entry.pumpStart = firstGap(entry, 0);
            startPumpLocked(entry);
        }
    }

    /** 整段读出来（文案 txt 这类小文件）。 */
    public byte[] readAll(String rel, String name, long size, long timeoutMs) throws IOException {
        long length = size;
        if (length <= 0) {
            try {
                length = client().sizeOf(rel);
            } catch (DavClient.DavException exc) {
                throw new IOException(exc.getMessage());
            }
        }
        if (length <= 0) {
            return new byte[0];
        }
        Entry entry = register(rel, name, length);
        synchronized (entry.lock) {
            entry.readers++;
        }
        try {
            long deadline = System.currentTimeMillis() + Math.max(5000L, timeoutMs);
            while (true) {
                long until = awaitAvailable(entry, 0L, deadline);
                boolean complete;
                synchronized (entry.lock) {
                    complete = entry.complete;
                }
                if (complete || entry.size > 0 && until >= entry.size) {
                    break;
                }
                if (entry.error != null) {
                    throw new IOException(entry.error);
                }
            }
            long actual;
            try (RandomAccessFile raf = new RandomAccessFile(entry.file, "rw")) {
                actual = raf.length();
                if (actual <= 0) {
                    actual = length;
                }
                if (actual > 8L * 1024 * 1024) {
                    actual = 8L * 1024 * 1024;
                }
                byte[] buffer = new byte[(int) actual];
                raf.seek(0);
                int read = raf.read(buffer);
                if (read <= 0) {
                    return new byte[0];
                }
                if (read == buffer.length) {
                    return buffer;
                }
                byte[] trimmed = new byte[read];
                System.arraycopy(buffer, 0, trimmed, 0, read);
                return trimmed;
            }
        } finally {
            synchronized (entry.lock) {
                entry.readers--;
                entry.lastUsed = System.currentTimeMillis();
                entry.lock.notifyAll();
            }
        }
    }

    public void clearCache() {
        for (Entry entry : new ArrayList<>(entries.values())) {
            synchronized (entry.lock) {
                entry.cancel = true;
                entry.lock.notifyAll();
            }
        }
        entries.clear();
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
    }

    /** 云端删除后，把这个作品（或整个作者目录）的播放缓存丢掉。 */
    public void forget(String rel) {
        String value = rel == null ? "" : rel.trim();
        if (value.isEmpty()) {
            return;
        }
        String prefix = value + "/";
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (!entry.rel.equals(value) && !entry.rel.startsWith(prefix)) {
                continue;
            }
            synchronized (entry.lock) {
                entry.cancel = true;
                entry.lock.notifyAll();
            }
            //noinspection ResultOfMethodCallIgnored
            entry.file.delete();
            //noinspection ResultOfMethodCallIgnored
            entry.marker.delete();
            entries.remove(entry.key);
        }
    }

    // ---------- 条目 ----------
    private Entry register(String rel, String name, long size) {
        String key = Util.sha1Hex(rel + "|" + Math.max(0L, size)).substring(0, 20);
        Entry exists = entries.get(key);
        if (exists != null) {
            exists.lastUsed = System.currentTimeMillis();
            return exists;
        }
        String fileName = name == null || name.isEmpty() ? RemoteFile.baseName(rel) : name;
        String ext = Mime.ext(fileName);
        if (ext.isEmpty()) {
            ext = ".bin";
        }
        File file = new File(root, key + ext);
        File marker = new File(root, key + ".ok");
        Entry entry = new Entry(key, rel, fileName, size, file, marker);
        long marked = readMarkerSize(marker);
        if (marked > 0 && marked == size && file.isFile() && file.length() >= size) {
            entry.ranges.put(0L, size);
            entry.complete = true;
        } else {
            if (marker.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            }
            if (file.isFile() && file.length() > 0) {
                // 没有完成标记，说明是半截文件，直接丢掉
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        Entry previous = entries.putIfAbsent(key, entry);
        return previous != null ? previous : entry;
    }

    private static final class Entry {
        final String key;
        final String rel;
        final String name;
        final File file;
        final File marker;
        final Object lock = new Object();
        final TreeMap<Long, Long> ranges = new TreeMap<>();
        volatile long lastUsed = System.currentTimeMillis();
        volatile long size;
        long pumpStart;
        long pumpPos;
        long seekRequest = -1;
        boolean downloading;
        boolean complete;
        boolean cancel;
        String error;
        int readers;

        Entry(String key, String rel, String name, long size, File file, File marker) {
            this.key = key;
            this.rel = rel;
            this.name = name;
            this.size = size;
            this.file = file;
            this.marker = marker;
        }
    }

    // ---------- 区间 ----------
    private static long coveredUntil(Entry entry, long position) {
        Long floor = entry.ranges.floorKey(position);
        if (floor == null) {
            return position;
        }
        Long end = entry.ranges.get(floor);
        return end == null ? position : Math.max(end, position);
    }

    private static long firstGap(Entry entry, long from) {
        long position = from;
        while (true) {
            long until = coveredUntil(entry, position);
            if (until <= position) {
                return position;
            }
            if (entry.size > 0 && until >= entry.size) {
                return entry.size;
            }
            position = until;
        }
    }

    private static void addRange(Entry entry, long start, long end) {
        if (end <= start) {
            return;
        }
        Long floor = entry.ranges.floorKey(start);
        if (floor != null && entry.ranges.get(floor) >= start) {
            start = floor;
            end = Math.max(end, entry.ranges.get(floor));
            entry.ranges.remove(floor);
        }
        Long next = entry.ranges.ceilingKey(start);
        while (next != null && next <= end) {
            end = Math.max(end, entry.ranges.get(next));
            entry.ranges.remove(next);
            next = entry.ranges.ceilingKey(start);
        }
        entry.ranges.put(start, end);
    }

    // ---------- 下载 ----------
    private void startPumpLocked(Entry entry) {
        if (entry.downloading || entry.complete || entry.cancel) {
            return;
        }
        entry.downloading = true;
        entry.error = null;
        downloadPool.execute(() -> pump(entry));
    }

    private void pump(Entry entry) {
        int attempts = 0;
        while (true) {
            long start;
            synchronized (entry.lock) {
                if (entry.cancel) {
                    entry.downloading = false;
                    entry.lock.notifyAll();
                    return;
                }
                if (entry.seekRequest >= 0) {
                    entry.pumpStart = entry.seekRequest;
                    entry.seekRequest = -1;
                } else if (entry.pumpStart < 0) {
                    entry.pumpStart = firstGap(entry, 0);
                }
                if (entry.size > 0 && entry.pumpStart >= entry.size) {
                    entry.complete = true;
                    entry.downloading = false;
                    entry.lock.notifyAll();
                    break;
                }
                if (entry.complete) {
                    entry.downloading = false;
                    entry.lock.notifyAll();
                    break;
                }
                start = entry.pumpStart;
                entry.pumpPos = start;
                entry.error = null;
                entry.downloading = true;
            }
            boolean failed = false;
            try {
                downloadOnce(entry, start);
            } catch (Exception exc) {
                failed = true;
                String message = exc.getMessage();
                android.util.Log.w("JYWatch", "proxy pump failed " + entry.rel + " " + message);
                synchronized (entry.lock) {
                    entry.error = message == null || message.isEmpty()
                            ? "下载失败" : message;
                }
            }
            synchronized (entry.lock) {
                entry.downloading = false;
                entry.complete = entry.size > 0 && coveredUntil(entry, 0) >= entry.size;
                if (entry.complete) {
                    writeMarker(entry);
                }
                entry.lock.notifyAll();
                if (entry.complete || entry.cancel) {
                    break;
                }
                if (failed) {
                    attempts++;
                    if (attempts > 3) {
                        break;
                    }
                    try {
                        Thread.sleep(700L * attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    entry.pumpStart = firstGap(entry, 0);
                    continue;
                }
                if (entry.seekRequest < 0) {
                    break;
                }
            }
        }
        trimAsync();
    }

    private void downloadOnce(Entry entry, long start) throws Exception {
        DavClient dav = client;
        if (dav == null) {
            throw new IOException("还没有配置 WebDAV");
        }
        DavClient.RangeStream stream = dav.openStream(entry.rel, start);
        long position = stream.start;
        if (start > 0 && !stream.partial) {
            stream.close();
            throw new IOException("服务器不支持 Range 断点续传");
        }
        if (stream.total > 0 && stream.total != entry.size) {
            // HEAD 的 Content-Length 有时会比 GET 实际字节数小（123pan CDN），
            // 以 GET 返回的总长度为准，避免把头像/文案截断。
            synchronized (entry.lock) {
                entry.size = stream.total;
                entry.lock.notifyAll();
            }
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(entry.file, "rw");
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = stream.stream.read(buffer)) > 0) {
                    synchronized (entry.lock) {
                        if (entry.cancel || entry.seekRequest >= 0) {
                            return;
                        }
                    }
                    raf.seek(position);
                    raf.write(buffer, 0, read);
                    synchronized (entry.lock) {
                        addRange(entry, position, position + read);
                        entry.pumpPos = position + read;
                        entry.lock.notifyAll();
                    }
                    position += read;
                    if (entry.size > 0 && position >= entry.size) {
                        break;
                    }
                }
            } finally {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        } finally {
            stream.close();
        }
        if (entry.size > 0 && position < entry.size) {
            throw new IOException("连接提前结束（" + position + "/" + entry.size + "）");
        }
    }

    /** 等到 position 之后有数据可读；返回当前连续可用到的位置。 */
    private long awaitAvailable(Entry entry, long position, long deadline) throws IOException {
        while (true) {
            synchronized (entry.lock) {
                long until = coveredUntil(entry, position);
                if (until > position) {
                    return until;
                }
                if (entry.size > 0 && position >= entry.size) {
                    return entry.size;
                }
                if (entry.error != null) {
                    throw new IOException(entry.error);
                }
                if (entry.cancel) {
                    throw new IOException("已取消");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("等待 WebDAV 数据超时");
                }
                if (!entry.downloading && !entry.complete) {
                    entry.pumpStart = firstGap(entry, position);
                    startPumpLocked(entry);
                } else if (entry.downloading) {
                    long delta = position - entry.pumpPos;
                    if (delta > SEEK_SLACK || delta < -SEEK_SLACK) {
                        entry.seekRequest = position;
                    }
                }
                try {
                    entry.lock.wait(200);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已取消");
                }
            }
        }
    }

    // ---------- 缓存标记 / 清理 ----------
    private void writeMarker(Entry entry) {
        try (FileOutputStream output = new FileOutputStream(entry.marker)) {
            output.write(String.valueOf(entry.size).getBytes(Charset.forName("UTF-8")));
        } catch (IOException ignored) {
        }
    }

    private static long readMarkerSize(File marker) {
        if (!marker.isFile()) {
            return -1;
        }
        try (InputStream input = new java.io.FileInputStream(marker)) {
            byte[] buffer = new byte[32];
            int read = input.read(buffer);
            if (read <= 0) {
                return -1;
            }
            return Long.parseLong(new String(buffer, 0, read, Charset.forName("UTF-8")).trim());
        } catch (Exception exc) {
            return -1;
        }
    }

    private void sweepBrokenCache() {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        // 这个扫描在后台线程跑，可能和刚起的下载撞上：最近 10 分钟动过的文件先留着
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        java.util.Set<String> markers = new java.util.HashSet<>();
        for (File child : children) {
            String name = child.getName();
            if (name.endsWith(".ok")) {
                markers.add(name.substring(0, name.length() - 3));
            }
        }
        for (File child : children) {
            String name = child.getName();
            if (name.endsWith(".ok")) {
                String base = name.substring(0, name.length() - 3);
                if (!new File(root, base).isFile() && child.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                }
                continue;
            }
            String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (!markers.contains(base) && child.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
    }

    private void trimAsync() {
        maintenancePool.execute(() -> {
            synchronized (trimLock) {
                trim();
            }
        });
    }

    private void trim() {
        long total = cacheBytes();
        if (total <= cacheLimit) {
            return;
        }
        List<Entry> candidates = new ArrayList<>();
        for (Entry entry : entries.values()) {
            synchronized (entry.lock) {
                if (entry.readers == 0 && !entry.cancel
                        && System.currentTimeMillis() - entry.lastUsed > 15000) {
                    candidates.add(entry);
                }
            }
        }
        candidates.sort(Comparator.comparingLong(entry -> entry.lastUsed));
        for (Entry entry : candidates) {
            if (total <= cacheLimit) {
                break;
            }
            long size = entry.file.length();
            synchronized (entry.lock) {
                entry.cancel = true;
                entry.lock.notifyAll();
            }
            //noinspection ResultOfMethodCallIgnored
            entry.file.delete();
            //noinspection ResultOfMethodCallIgnored
            entry.marker.delete();
            entries.remove(entry.key);
            total -= size;
        }
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    // ---------- HTTP ----------
    private void handle(Socket socket) {
        try (Socket clientSocket = socket) {
            InputStream input = clientSocket.getInputStream();
            OutputStream rawOutput = clientSocket.getOutputStream();
            BufferedOutputStream output = new BufferedOutputStream(rawOutput, 64 * 1024);
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String rangeHeader = null;
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    if ("range".equals(key)) {
                        rangeHeader = line.substring(colon + 1).trim();
                    }
                }
            }
            if ("OPTIONS".equals(method)) {
                output.write(("HTTP/1.1 200 OK\r\nAllow: GET, HEAD, OPTIONS\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                output.flush();
                return;
            }
            if (!target.startsWith("/m/")) {
                writeEmpty(output, 404, "Not Found");
                return;
            }
            String rest = target.substring(3);
            int slash = rest.indexOf('/');
            String key = slash < 0 ? rest : rest.substring(0, slash);
            Entry entry = entries.get(key);
            if (entry == null) {
                writeEmpty(output, 404, "Not Found");
                return;
            }
            long size = entry.size;
            if (size <= 0) {
                DavClient dav = client;
                if (dav == null) {
                    writeEmpty(output, 502, "Bad Gateway");
                    return;
                }
                try {
                    size = dav.sizeOf(entry.rel);
                } catch (Exception exc) {
                    writeEmpty(output, 502, "Bad Gateway");
                    return;
                }
                entry.size = size;
            }
            if (size <= 0) {
                writeEmpty(output, 404, "Not Found");
                return;
            }
            if ("HEAD".equals(method)) {
                output.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + Mime.of(entry.name) + "\r\n"
                        + "Content-Length: " + size + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                output.flush();
                return;
            }
            if (!"GET".equals(method)) {
                writeEmpty(output, 405, "Method Not Allowed");
                return;
            }
            long start = 0;
            long end = size - 1;
            boolean ranged = false;
            if (rangeHeader != null) {
                long[] parsed = parseRange(rangeHeader, size);
                if (parsed == null) {
                    output.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                            + "Content-Range: bytes */" + size + "\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                    output.flush();
                    return;
                }
                start = parsed[0];
                end = parsed[1];
                ranged = true;
            }
            long length = end - start + 1;
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 ")
                    .append(ranged ? "206 Partial Content" : "200 OK").append("\r\n");
            headers.append("Content-Type: ").append(Mime.of(entry.name)).append("\r\n");
            headers.append("Content-Length: ").append(length).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Cache-Control: no-store\r\n");
            if (ranged) {
                headers.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(size).append("\r\n");
            }
            headers.append("Connection: close\r\n\r\n");
            output.write(headers.toString().getBytes("UTF-8"));
            output.flush();

            synchronized (entry.lock) {
                entry.lastUsed = System.currentTimeMillis();
                entry.readers++;
            }
            try {
                streamRange(entry, start, end, output);
            } catch (Exception exc) {
                android.util.Log.w("JYWatch", "proxy stream " + entry.rel + " " + exc.getMessage());
            } finally {
                synchronized (entry.lock) {
                    entry.readers--;
                    entry.lastUsed = System.currentTimeMillis();
                    entry.lock.notifyAll();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private DavClient client() {
        DavClient current = client;
        if (current == null) {
            throw new IllegalStateException("WebDAV 未配置");
        }
        return current;
    }

    private void streamRange(Entry entry, long start, long end, OutputStream output)
            throws IOException {
        long position = start;
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        byte[] buffer = new byte[READ_BUFFER];
        RandomAccessFile raf = new RandomAccessFile(entry.file, "rw");
        try {
            while (position <= end) {
                long until = awaitAvailable(entry, position, deadline);
                long chunkEnd = Math.min(end + 1, until);
                if (chunkEnd <= position) {
                    throw new IOException("缓存读取失败");
                }
                while (position < chunkEnd) {
                    raf.seek(position);
                    int want = (int) Math.min(buffer.length, chunkEnd - position);
                    int read = raf.read(buffer, 0, want);
                    if (read <= 0) {
                        throw new IOException("缓存读取失败");
                    }
                    // 不要每块都 flush：BufferedOutputStream 自己会按 64KB 落盘，
                    // 大块写入本来就是直通 socket 的
                    output.write(buffer, 0, read);
                    position += read;
                }
                deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
            }
            output.flush();
        } finally {
            try {
                raf.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static long[] parseRange(String header, long size) {
        String value = header.trim();
        if (!value.startsWith("bytes=")) {
            return new long[]{0, size - 1};
        }
        value = value.substring(6).trim();
        if (value.contains(",")) {
            value = value.substring(0, value.indexOf(',')).trim();
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return new long[]{0, size - 1};
        }
        String first = value.substring(0, dash).trim();
        String last = value.substring(dash + 1).trim();
        long start;
        long end;
        try {
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(first);
                end = last.isEmpty() ? size - 1 : Long.parseLong(last);
            }
        } catch (NumberFormatException exc) {
            return null;
        }
        if (start < 0 || start >= size) {
            return null;
        }
        end = Math.min(end, size - 1);
        if (end < start) {
            return null;
        }
        return new long[]{start, end};
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return builder.toString().trim();
            }
            if (value != '\r') {
                builder.append((char) value);
            }
            if (builder.length() > 8192) {
                return null;
            }
        }
        return builder.length() == 0 ? null : builder.toString().trim();
    }

    private static void writeEmpty(OutputStream output, int code, String reason)
            throws IOException {
        output.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        output.flush();
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text == null ? "" : text, "UTF-8").replace("+", "%20");
        } catch (Exception exc) {
            return "file";
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
