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
    /**
     * 「后面 10 个作品」的预热单独一条线程：预热会排很多个 2MB 的任务，
     * 如果和播放共用一个池，拖动进度条后的续传要排在它们后面，画面就会干等。
     */
    private final ExecutorService prefetchPool = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-prefetch");
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
    /** 正在后台清缓存：这期间对外报 0，别让设置页显示「清完了还剩 4 GB」。 */
    private volatile boolean clearing;
    private ServerSocket server;
    private volatile long cacheLimit = 4L * 1024 * 1024 * 1024;
    /** cacheBytes() 会递归 stat 整个缓存目录：首页/设置页频繁问，缓存 15 秒。 */
    private final Object sizeLock = new Object();
    private long sizeCached;
    private long sizeCachedAt;
    /** 缓存过期时只让一个线程去递归 stat，避免多个线程同时扫几万个文件。 */
    private final java.util.concurrent.atomic.AtomicBoolean sizeComputing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long SIZE_TTL_MS = 15_000L;
    /** trim 要遍历整个缓存目录，别每下完一个文件就扫一遍。 */
    private volatile long lastTrimAt;

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
        // 上限变了要立刻按新上限清一次，不能等下一次播放结束
        trimAsync(true);
    }

    public long cacheBytes() {
        if (clearing) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        synchronized (sizeLock) {
            if (sizeCachedAt > 0 && now - sizeCachedAt < SIZE_TTL_MS) {
                return sizeCached;
            }
        }
        // 递归 stat 整个缓存目录可能要几万次，放到锁外做，别挡住下载线程的 cacheBytes()
        if (!sizeComputing.compareAndSet(false, true)) {
            synchronized (sizeLock) {
                return sizeCached;
            }
        }
        try {
            long size = sizeOf(root);
            synchronized (sizeLock) {
                sizeCached = size;
                sizeCachedAt = System.currentTimeMillis();
            }
            return size;
        } finally {
            sizeComputing.set(false);
        }
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
                // accept 连续失败（fd 耗尽/系统策略）时不能空转，否则 100% CPU 还不留日志
                android.util.Log.w("JYWatch", "proxy accept failed", exc);
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
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
            entry.prefetchLimit = 0;
            entry.pumpStart = firstGap(entry, 0);
            startPumpLocked(entry);
        }
    }

    /**
     * 只预取开头的一小段（给「后面 10 个作品」预热用）。
     *
     * 不把整段视频都拉下来：每个作品只补 `bytes` 字节，播放器切过去时再按需续传。
     * 已经预取过、正在下载、或者已经完整缓存的条目直接跳过。
     */
    public void prefetchPrefix(String rel, String name, long size, long bytes) {
        if (size <= 0 || bytes <= 0) {
            return;
        }
        Entry entry = register(rel, name, size);
        synchronized (entry.lock) {
            entry.lastUsed = System.currentTimeMillis();
            if (entry.complete || entry.downloading || entry.cancel || entry.prefetchedPrefix) {
                return;
            }
            long start = firstGap(entry, 0);
            long limit = Math.min(size, start + bytes);
            entry.prefetchedPrefix = true;
            if (start >= limit) {
                return;
            }
            entry.prefetchLimit = limit;
            entry.pumpStart = start;
            startPumpLocked(entry, true);
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
                String error;
                long sizeNow;
                synchronized (entry.lock) {
                    complete = entry.complete;
                    error = entry.error;
                    sizeNow = entry.size;
                }
                if (complete || (sizeNow > 0 && until >= sizeNow)) {
                    break;
                }
                if (error != null) {
                    throw new IOException(error);
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
        synchronized (sizeLock) {
            sizeCached = 0L;
            sizeCachedAt = System.currentTimeMillis();
        }
        // 最多 8GB 的目录递归删除不能占着主线程（设置页「清空视频缓存」就在主线程调）
        clearing = true;
        maintenancePool.execute(() -> {
            try {
                File[] children = root.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            } finally {
                clearing = false;
            }
        });
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
        invalidateSize();
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
        volatile String error;
        /** >0 表示这是「只预取到某个位置」的任务，播到该位置后再继续拉。 */
        volatile long prefetchLimit;
        boolean prefetchedPrefix;
        /** 上一次失败的时间：允许等待方在退避之后重启一次 pump，别让失败变成永久状态。 */
        long lastFailureAt;
        int restarts;
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
        startPumpLocked(entry, false);
    }

    /**
     * @param background 预热任务走单独的线程，播放器的续传不用排在它们后面。
     */
    private void startPumpLocked(Entry entry, boolean background) {
        if (entry.downloading || entry.complete || entry.cancel) {
            return;
        }
        entry.downloading = true;
        entry.error = null;
        (background ? prefetchPool : downloadPool).execute(() -> pump(entry));
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
                    entry.lastFailureAt = System.currentTimeMillis();
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
            boolean limited = false;
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
                        entry.restarts = 0;
                        entry.lock.notifyAll();
                    }
                    position += read;
                    if (entry.size > 0 && position >= entry.size) {
                        break;
                    }
                    if (entry.prefetchLimit > 0 && position >= entry.prefetchLimit) {
                        // 「只预热一段」到这里就够了，播到这儿时 awaitAvailable 会清掉上限续传
                        limited = true;
                        break;
                    }
                }
            } finally {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
            if (entry.size > 0 && position < entry.size && !limited) {
                throw new IOException("连接提前结束（" + position + "/" + entry.size + "）");
            }
        } finally {
            stream.close();
        }
    }

    /** 等到 position 之后有数据可读；返回当前连续可用到的位置。 */
    private long awaitAvailable(Entry entry, long position, long deadline) throws IOException {
        while (true) {
            synchronized (entry.lock) {
                if (entry.prefetchLimit > 0 && position >= entry.prefetchLimit) {
                    // 播放已经追上预热段：解除上限，pump 接着往后续传
                    entry.prefetchLimit = 0;
                }
                long until = coveredUntil(entry, position);
                if (until > position) {
                    return until;
                }
                if (entry.size > 0 && position >= entry.size) {
                    return entry.size;
                }
                if (entry.error != null && !entry.downloading) {
                    long now = System.currentTimeMillis();
                    if (now - entry.lastFailureAt < 3000L || entry.restarts >= 5) {
                        throw new IOException(entry.error);
                    }
                    // pump 已经放弃重试了：隔几秒给一次重新开始的机会。
                    // 以前 error 是粘滞的，一次瞬时断流就让这个文件在本次进程里永久放不了。
                    entry.error = null;
                    entry.restarts++;
                    entry.pumpStart = firstGap(entry, position);
                    startPumpLocked(entry);
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
        invalidateSize();
    }

    private void trimAsync() {
        trimAsync(false);
    }

    private void trimAsync(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastTrimAt < 15_000L) {
            // 连着下完几个文件时不用每次都遍历缓存目录
            return;
        }
        lastTrimAt = now;
        maintenancePool.execute(() -> {
            synchronized (trimLock) {
                trim();
            }
        });
    }

    private void trim() {
        // 递归 stat 放到锁外：trim 跑在维护线程上，别把下载路径的 cacheBytes() 一起堵住
        long total = sizeOf(root);
        synchronized (sizeLock) {
            sizeCached = total;
            sizeCachedAt = System.currentTimeMillis();
        }
        capEntries();
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
        synchronized (sizeLock) {
            sizeCached = Math.max(0L, total);
            sizeCachedAt = System.currentTimeMillis();
        }
    }

    private void invalidateSize() {
        synchronized (sizeLock) {
            sizeCachedAt = 0L;
        }
    }

    /**
     * entries 一个作品一条，长会话里会越积越多（每条还带一个 TreeMap）。
     * 超过 3000 条就把最久没用的空闲条目从内存里摘掉：只丢 Entry，不删缓存文件，
     * 下次再放会按 `.ok` 标记直接复用。
     */
    private void capEntries() {
        if (entries.size() <= 3000) {
            return;
        }
        List<Entry> idle = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 120_000L;
        for (Entry entry : entries.values()) {
            synchronized (entry.lock) {
                if (entry.readers == 0 && !entry.downloading && !entry.cancel
                        && entry.lastUsed < cutoff) {
                    idle.add(entry);
                }
            }
        }
        idle.sort(Comparator.comparingLong(entry -> entry.lastUsed));
        for (Entry entry : idle) {
            if (entries.size() <= 2000) {
                break;
            }
            synchronized (entry.lock) {
                if (entry.readers > 0 || entry.downloading) {
                    continue;
                }
                entry.cancel = true;
            }
            entries.remove(entry.key);
        }
    }

    private static long sizeOf(File root) {
        if (root == null || !root.exists()) {
            return 0L;
        }
        // 迭代遍历：缓存目录层级可能很深，递归版有栈溢出风险
        long total = 0;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File file = stack.pop();
            if (file.isFile()) {
                total += file.length();
            } else {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        stack.push(child);
                    }
                }
            }
        }
        return total;
    }

    // ---------- HTTP ----------
    private void handle(Socket socket) {
        try (Socket clientSocket = socket) {
            // 播放器每个 Range 请求两三百字节头，直接 read() 一字节一个 syscall 太浪费
            InputStream input = new java.io.BufferedInputStream(clientSocket.getInputStream(), 4096);
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
            long size;
            synchronized (entry.lock) {
                size = entry.size;
            }
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
                synchronized (entry.lock) {
                    if (entry.size <= 0) {
                        entry.size = size;
                        entry.lock.notifyAll();
                    } else {
                        // pump 已经按 GET 的实际字节数写过 size：以它为准。
                        // 以前无锁覆盖，HEAD 探测值偏小会把下完的文件标成「完整但截断」。
                        size = entry.size;
                    }
                }
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
        } catch (Exception exc) {
            // 客户端断开（播放器 seek/切条）很常见，但其它异常不能静默吞掉
            String message = exc.getMessage();
            if (!(exc instanceof java.io.IOException) || message == null
                    || !message.contains("Broken pipe")) {
                android.util.Log.w("JYWatch", "proxy request failed", exc);
            }
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
                if (entries.get(entry.key) != entry) {
                    // 缓存被清掉/条目被换掉了：别再往这条已经作废的流里写
                    throw new IOException("缓存已失效");
                }
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
                // 一轮拿到的数据可能只有几 KB，不 flush 就会被 64KB 缓冲攒住，
                // 播放器明明有数据可读却一直等着
                output.flush();
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
