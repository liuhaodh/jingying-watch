package com.jingying.watch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 本地下载：把 WebDAV 上的作品整段拉到手机里，之后不联网也能看。
 *
 * 存放位置是 App 私有目录（优先 `Android/data/<包名>/files/offline`，
 * 拿不到外部目录时退回内部 `files/offline`），再放一个 `.nomedia`，
 * 系统相册/媒体库不会扫描到这些文件。
 */
public final class OfflineStore {

    private static final long LOAD_RETRY_MS = 3000L;

    public interface Listener {
        /** work 为 null 表示整体变化（清空/重新加载）。 */
        void onOfflineChanged(Work work);

        void onDownloadProgress(Work work, int percent);
    }

    private final App app;
    private final File root;
    private final Map<String, Work> saved = new ConcurrentHashMap<>();
    private final Map<String, Integer> progress = new ConcurrentHashMap<>();
    private final Set<String> downloading = Collections.synchronizedSet(new HashSet<String>());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean loaded;
    /** 正在扫描/等待扫描的标记，避免 has() 每次都往 io 线程丢任务。 */
    private volatile boolean loadQueued;
    /** 下载途中被用户删掉的作品：别在下载线程收尾时又把它写回索引。 */
    private final Set<String> cancelled = Collections.synchronizedSet(new HashSet<String>());
    /** 读不到目录时（比如外置存储还没挂载）最多每 3 秒重试一次。 */
    private long lastLoadAt;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "watch-offline");
            thread.setDaemon(true);
            return thread;
        }
    });

    public OfflineStore(App app) {
        this.app = app;
        File base = app.getExternalFilesDir(null);
        if (base == null) {
            base = app.getFilesDir();
        }
        this.root = new File(base, "offline");
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        writeNoMedia(base);
        writeNoMedia(root);
    }

    private static void writeNoMedia(File dir) {
        try {
            File marker = new File(dir, ".nomedia");
            if (!marker.exists()) {
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            }
        } catch (Exception ignored) {
        }
    }

    public String rootPath() {
        return root.getAbsolutePath();
    }

    public int count() {
        ensureLoaded();
        return saved.size();
    }

    public List<Work> works() {
        ensureLoaded();
        return new ArrayList<>(saved.values());
    }

    public long totalBytes() {
        return sizeOf(root);
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---------- 索引 ----------
    public synchronized void load() {
        if (loaded) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < LOAD_RETRY_MS) {
            // 刚试过（可能外置存储还没挂载），别让每次 has() 都重扫一遍目录
            return;
        }
        lastLoadAt = now;
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            if (isDownloadingDir(dir.getName())) {
                // 正在下载的目录还没写 meta.json，别当成垃圾删掉
                continue;
            }
            File meta = new File(dir, "meta.json");
            if (!meta.isFile()) {
                deleteRecursive(dir);
                continue;
            }
            try {
                JSONObject json = new JSONObject(readText(meta));
                Work work = Work.fromJson(json.optJSONObject("work"));
                if (work == null || !hasMainFile(work)) {
                    deleteRecursive(dir);
                    continue;
                }
                saved.put(work.rel, work);
            } catch (Exception ignored) {
                deleteRecursive(dir);
            }
        }
        loaded = true;
    }

    private boolean isDownloadingDir(String dirName) {
        if (downloading.isEmpty() || dirName == null) {
            return false;
        }
        synchronized (downloading) {
            for (String rel : downloading) {
                if (rel != null && ("w" + Util.sha1Hex(rel).substring(0, 16)).equals(dirName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 冷启动时片库和首页可能先于后台 load 就绪，这里保证离线判断是准的。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        // 整理目录 + 解析 meta.json 可能要几百毫秒，绝不能放在主线程（has() 就在 bind 里调用）。
        // 先返回「没有下载」，索引建好后 notifyChanged(null) 会让界面重新绑定。
        loadAsync();
    }

    private void loadAsync() {
        if (loaded || loadQueued) {
            return;
        }
        loadQueued = true;
        app.io.execute(() -> {
            try {
                load();
            } finally {
                loadQueued = false;
            }
            if (loaded) {
                notifyChanged(null);
            }
        });
    }

    private boolean hasMainFile(Work work) {
        File dir = dirFor(work);
        if (work.archive != null) {
            // 一个作品一个 tar：本地只要这个包在且大小对得上就算已下载
            return fileOk(new File(dir, work.archive.name), work.archive.size);
        }
        if (work.video != null) {
            return fileOk(new File(dir, work.video.name), work.video.size);
        }
        if (!work.images.isEmpty()) {
            RemoteFile image = work.images.get(0);
            return fileOk(new File(dir, image.name), image.size);
        }
        return false;
    }

    private static boolean fileOk(File file, long expected) {
        return file.isFile() && file.length() > 0
                && (expected <= 0 || file.length() >= expected);
    }

    /**
     * 是否已下载。load() 时已经逐个校验过主文件，这里只查内存索引 ——
     * 首页统计要对全库（1.6 万条）调用一次，每次都做文件系统 stat 会把主线程卡住。
     */
    public boolean has(Work work) {
        ensureLoaded();
        return work != null && saved.containsKey(work.rel);
    }

    public boolean isDownloading(Work work) {
        return work != null && downloading.contains(work.rel);
    }

    /** 是否还有下载任务在跑（自动下载用来避免一次排很多条）。 */
    public boolean isBusy() {
        return !downloading.isEmpty();
    }

    public int progressOf(Work work) {
        if (work == null) {
            return -1;
        }
        Integer value = progress.get(work.rel);
        return value == null ? -1 : value;
    }

    public File dirFor(Work work) {
        return new File(root, work.key());
    }

    public File localVideo(Work work) {
        ensureLoaded();
        Work stored = saved.get(work.rel);
        if (stored == null || stored.archive != null || stored.video == null) {
            return null;
        }
        File file = new File(dirFor(stored), stored.video.name);
        return file.isFile() ? file : null;
    }

    public File localImage(Work work, int index) {
        ensureLoaded();
        Work stored = saved.get(work.rel);
        if (stored == null || stored.archive != null
                || index < 0 || index >= stored.images.size()) {
            return null;
        }
        File file = new File(dirFor(stored), stored.images.get(index).name);
        return file.isFile() ? file : null;
    }

    public File localMusic(Work work) {
        ensureLoaded();
        Work stored = saved.get(work.rel);
        if (stored == null || stored.archive != null || stored.music == null) {
            return null;
        }
        File file = new File(dirFor(stored), stored.music.name);
        return file.isFile() ? file : null;
    }

    /** 已下载的作品包（tar）；没有或大小不对返回 null。 */
    public File savedTar(Work work) {
        ensureLoaded();
        if (work == null || work.archive == null) {
            return null;
        }
        Work stored = saved.get(work.rel);
        if (stored == null || stored.archive == null) {
            return null;
        }
        File file = new File(dirFor(stored), stored.archive.name);
        if (!file.isFile()) {
            return null;
        }
        if (stored.archive.size > 0 && file.length() < stored.archive.size) {
            return null;
        }
        return file;
    }

    public File savedTarFor(String archiveRel) {
        ensureLoaded();
        if (archiveRel == null || archiveRel.isEmpty()) {
            return null;
        }
        return savedTar(saved.get(archiveRel));
    }

    public Work stored(Work work) {
        return work == null ? null : saved.get(work.rel);
    }

    // ---------- 下载 / 删除 ----------
    public void download(Work work) {
        if (work == null || !downloading.add(work.rel)) {
            return;
        }
        progress.put(work.rel, 0);
        pool.execute(() -> doDownload(work));
    }

    public void delete(Work work) {
        if (work == null) {
            return;
        }
        if (downloading.remove(work.rel)) {
            // 下载还在跑：标记一下，别让它在写 meta 的时候又把记录塞回索引
            cancelled.add(work.rel);
        }
        deleteRecursive(dirFor(work));
        saved.remove(work.rel);
        progress.remove(work.rel);
        if (app.archives != null) {
            app.archives.clear(work);
        }
        if (app.images != null) {
            app.images.invalidateCover(work.key());
        }
        notifyChanged(work);
    }

    public void clearAll() {
        synchronized (downloading) {
            cancelled.addAll(downloading);
        }
        if (app.archives != null) {
            app.archives.clearAll();
        }
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory()) {
                    deleteRecursive(dir);
                }
            }
        }
        saved.clear();
        progress.clear();
        notifyChanged(null);
    }

    /** 只统计真正会下载的文件（实况图 lives 不下载，不能算进进度分母）。 */
    private static long downloadBytes(Work work) {
        if (work.archive != null) {
            return Math.max(0L, work.archive.size);
        }
        long total = 0;
        if (work.video != null) {
            total += work.video.size;
        }
        for (RemoteFile image : work.images) {
            total += image.size;
        }
        if (work.music != null) {
            total += work.music.size;
        }
        return total;
    }

    private void doDownload(Work work) {
        File dir = dirFor(work);
        try {
            // 下载线程上可以等索引读完：否则 has() 可能说「没下过」，把整条重新下一遍
            load();
            if (!work.detailsLoaded && !ensureDetails(work)) {
                throw new IllegalStateException("作品信息读取失败");
            }
            if (work.empty) {
                throw new IllegalStateException("作品里没有可下载的内容");
            }
            if (has(work)) {
                progress.put(work.rel, 100);
                notifyChanged(work);
                return;
            }
            if (work.archive != null) {
                downloadArchive(work, dir);
                return;
            }
            if (work.isAlbum() && !work.musicChecked) {
                app.scanner.loadMusicSync(app.dav(), work);
            }
            deleteChildren(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            long total = downloadBytes(work);
            if (total > 0) {
                long free = root.getUsableSpace();
                if (free > 0 && free < (long) (total * 1.15)) {
                    throw new IllegalStateException("手机剩余空间不足：需要约 "
                            + Util.humanSize(total) + "，" + "当前只剩 "
                            + Util.humanSize(free));
                }
            }
            if (total <= 0) {
                total = 1;
            }
            long done = 0;
            byte[] captionBytes = null;
            if (work.caption != null) {
                try {
                    captionBytes = app.proxy.readAll(work.caption.rel, work.caption.name,
                            work.caption.size, 20000);
                    if (work.captionText == null || work.captionText.isEmpty()) {
                        Work.parseCaptionText(work,
                                new String(captionBytes, Charset.forName("UTF-8")));
                    }
                } catch (Exception ignored) {
                }
            }
            if (work.video != null) {
                done = downloadFile(work, work.video, new File(dir, work.video.name),
                        done, total);
            }
            for (RemoteFile image : work.images) {
                done = downloadFile(work, image, new File(dir, image.name), done, total);
            }
            if (work.music != null) {
                done = downloadFile(work, work.music, new File(dir, work.music.name),
                        done, total);
            }
            // 文案保留 WebDAV 上的原始文件（含作者、发布时间、作品链接），不要再只写正文
            if (captionBytes != null && captionBytes.length > 0) {
                String name = work.caption != null && !work.caption.name.isEmpty()
                        ? work.caption.name : "文案.txt";
                writeBytes(new File(dir, name), captionBytes);
            } else if (work.captionText != null && !work.captionText.isEmpty()) {
                writeText(new File(dir, "文案.txt"), work.captionText);
            }
            if (cancelled.remove(work.rel)) {
                // 下载途中被用户删了：文件已经清掉，别再写 meta、也别写回索引
                deleteChildren(dir);
                return;
            }
            JSONObject meta = new JSONObject();
            meta.put("savedAt", System.currentTimeMillis() / 1000L);
            meta.put("work", work.toJson());
            // 原子写：meta.json 只写了一半就掉电，下次启动会被当成坏目录整个删掉
            writeTextAtomic(new File(dir, "meta.json"), meta.toString());
            saved.put(work.rel, work);
            progress.put(work.rel, 100);
            if (app.images != null) {
                app.images.invalidateCover(work.key());
            }
            notifyChanged(work);
        } catch (Exception exc) {
            deleteChildren(dir);
            android.util.Log.w("JYWatch", "offline download failed " + work.rel, exc);
            progress.put(work.rel, -2);
            notifyChanged(work);
        } finally {
            cancelled.remove(work.rel);
            downloading.remove(work.rel);
            android.util.Log.i("JYWatch", "offline done " + work.rel
                    + " size=" + dirSize(dir));
        }
    }

    /** 作品包：只把 `<作品id>.tar` 整个下到本地，播放时再由 ArchiveStore 解包。 */
    private void downloadArchive(Work work, File dir) throws Exception {
        long total = Math.max(1L, work.archive.size);
        long free = root.getUsableSpace();
        if (free > 0 && free < (long) (total * 1.15)) {
            throw new IllegalStateException("手机剩余空间不足：需要约 "
                    + Util.humanSize(total) + "，" + "当前只剩 "
                    + Util.humanSize(free));
        }
        deleteChildren(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File target = new File(dir, work.archive.name);
        downloadFile(work, work.archive, target, 0L, total);
        if (cancelled.remove(work.rel)) {
            deleteChildren(dir);
            return;
        }
        JSONObject meta = new JSONObject();
        meta.put("savedAt", System.currentTimeMillis() / 1000L);
        meta.put("work", work.toJson());
        writeTextAtomic(new File(dir, "meta.json"), meta.toString());
        saved.put(work.rel, work);
        progress.put(work.rel, 100);
        if (app.images != null) {
            app.images.invalidateCover(work.key());
        }
        notifyChanged(work);
    }

    private boolean ensureDetails(Work work) {
        final CountDownLatch latch = new CountDownLatch(1);
        app.scanner.loadDetails(work, (target, ok, error) -> latch.countDown());
        try {
            return latch.await(35, TimeUnit.SECONDS) && work.detailsLoaded && !work.empty;
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long downloadFile(Work work, RemoteFile file, File target,
                              long done, long total) throws Exception {
        // 先取一次：没配 WebDAV 就直接给明确错误，别在重试循环里白等 3 次 NPE
        final DavClient dav = app.dav();
        if (dav == null) {
            throw new IllegalStateException("还没有配置 WebDAV，无法下载");
        }
        if (file.size > 0 && target.isFile() && target.length() >= file.size) {
            return done + file.size;
        }
        File part = new File(target.getParentFile(), target.getName() + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        int attempts = 0;
        while (true) {
            attempts++;
            DavClient.RangeStream stream = null;
            try {
                stream = dav.openStream(file.rel, 0);
                long written = 0;
                try (FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[128 * 1024];
                    long lastNotify = 0;
                    int read;
                    while ((read = stream.stream.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                        written += read;
                        long now = System.currentTimeMillis();
                        if (now - lastNotify > 400) {
                            lastNotify = now;
                            int percent = (int) Math.min(99,
                                    (done + written) * 100 / Math.max(1, total));
                            progress.put(work.rel, percent);
                            notifyProgress(work, percent);
                        }
                    }
                    output.flush();
                }
                long expected = file.size;
                if (expected > 0 && part.length() < expected) {
                    throw new IllegalStateException("下载不完整（" + part.length()
                            + "/" + expected + "）");
                }
                if (!part.renameTo(target)) {
                    throw new IllegalStateException("保存文件失败");
                }
                return done + Math.max(written, file.size);
            } catch (Exception exc) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                if (attempts >= 3) {
                    throw exc;
                }
                Thread.sleep(800L * attempts);
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }

    private void notifyChanged(Work work) {
        app.runOnMain(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onOfflineChanged(work);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void notifyProgress(Work work, int percent) {
        app.runOnMain(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onDownloadProgress(work, percent);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------- 文件工具 ----------
    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursive(child);
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

    private static long dirSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            total += file.isDirectory() ? dirSize(file) : file.length();
        }
        return total;
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0;
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

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                if (output.size() > 4 * 1024 * 1024) {
                    break;
                }
            }
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    private static void writeBytes(File file, byte[] bytes) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (Exception ignored) {
        }
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(Charset.forName("UTF-8")));
        } catch (Exception ignored) {
        }
    }

    /** 先写 .tmp 再改名：掉电/进程被杀时不会留下半截 JSON（那种目录下次启动会被当垃圾删掉）。 */
    private static void writeTextAtomic(File target, String text) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(text.getBytes(Charset.forName("UTF-8")));
        } catch (Exception exc) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw exc;
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IllegalStateException("写入失败：" + target.getName());
        }
    }
}
