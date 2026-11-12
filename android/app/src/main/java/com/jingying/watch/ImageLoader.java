package com.jingying.watch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 封面/图集加载：视频封面用 MediaMetadataRetriever 从本地代理取第一帧，
 * 图集直接读第一张图；结果存内存 + 磁盘，滚动时不再重复拉。
 */
public final class ImageLoader {

    public interface Callback {
        void onImage(String key, Bitmap bitmap);
    }

    private final App app;
    private final File dir;
    /** 封面 / 视频首帧缓存。 */
    private final LruCache<String, Bitmap> memory;
    /**
     * 图集大图单独一份缓存：一张 1080×1920 的 RGB_565 约 4MB，和封面挤在 48MB 里
     * 来回划几张就会被淘汰、反复全量解码，这正是图集滑动掉帧的主因之一。
     */
    private final LruCache<String, Bitmap> albumMemory;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "watch-covers");
            thread.setDaemon(true);
            return thread;
        }
    });
    /** 预取专用：单线程、最低优先级，绝不和正在显示的那张图抢线程池。 */
    private final ExecutorService prefetchPool = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "watch-album-prefetch");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });
    /**
     * 正在预取的 key。刻意不占用 pending：否则用户正好切到这张时，正式请求会
     * 被挂到低优先级单线程后面排队，比不做预取还慢。
     */
    private final java.util.Set<String> prefetching = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /**
     * 同一个 key 正在加载时，后来的请求挂在这里一起等结果。
     * 之前是直接 return，回调永远不来 —— 快速滚动时格子会一直空着。
     */
    private final Map<String, List<Callback>> pending = new HashMap<>();
    /** 只画了占位图（没取到真封面）的 key：换连接/重扫后重试一次。 */
    private final java.util.Set<String> failed = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** key → 占位图生成时间；过一会儿再重试，避免每绑一次就重打一次网络。 */
    private final Map<String, Long> failedAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 正在等详情的 key：详情回来后再跑一次封面，不再占着封面线程干等。 */
    private final java.util.Set<String> waitingDetails = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final long RETRY_MS = 20_000L;
    /** 清空封面缓存时 +1：清完之后，正在跑的任务别再把旧文件写回来。 */
    private volatile int diskGeneration;
    /** 封面磁盘上限：超过 8000 张按 lastModified 清到 6000 张。 */
    private static final int COVER_CAP = 8000;
    private static final int COVER_KEEP = 6000;
    private final java.util.concurrent.atomic.AtomicInteger savesSincePrune =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean pruning;

    public ImageLoader(App app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "covers");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        int maxKb = (int) Math.min(48 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        int albumKb = (int) Math.max(1024L,
                Math.min(64 * 1024L, Runtime.getRuntime().maxMemory() / 1024 / 4));
        albumMemory = new LruCache<String, Bitmap>(albumKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public Bitmap peek(String key) {
        if (failed.contains(key)) {
            // 内存里可能是上次失败留下的占位图；别让首页直接拿它，
            // 交给 loadCover() 判断是「还在退避」还是「该重试了」。
            return null;
        }
        return memory.get("cover:" + key);
    }

    /** 首页/播放页封面。 */
    public void loadCover(Work work, Callback callback) {
        if (work == null || callback == null) {
            return;
        }
        final String key = work.key();
        Bitmap cached = memory.get("cover:" + key);
        if (cached != null) {
            Long at = failedAt.get(key);
            if (at == null) {
                callback.onImage(key, cached);
                return;
            }
            if (System.currentTimeMillis() - at < RETRY_MS) {
                // 刚失败过：先把占位图给界面，等退避结束再重试
                callback.onImage(key, cached);
                return;
            }
            memory.remove("cover:" + key);
            failed.remove(key);
            failedAt.remove(key);
            cached = null;
        }
        if (cached != null) {
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe("cover:" + key, callback)) {
            return;
        }
        runCoverTask(work, key, false);
    }

    /**
     * 生成并交付一张封面。
     *
     * 需要详情（tar 成员清单、视频文件在哪）时不在这里同步等：挂到详情回调上，
     * 详情回来后再跑一次。之前用 25 秒的 CountDownLatch 占住 3 个封面线程，
     * 首页快速滚动时一个 tar 作品就能把后面的封面全堵住。
     */
    private void runCoverTask(final Work target, final String key, final boolean detailsRetried) {
        final int generation = diskGeneration;
        pool.execute(() -> {
            Bitmap bitmap = null;
            boolean defer = false;
            File disk = new File(dir, key + ".jpg");
            try {
                if (disk.isFile()) {
                    bitmap = decodeFile(disk, 640);
                    if (bitmap == null) {
                        // 旧版本可能存过损坏/占位图：删掉，下次从头再来
                        //noinspection ResultOfMethodCallIgnored
                        disk.delete();
                    } else {
                        failed.remove(key);
                        failedAt.remove(key);
                    }
                }
                if (bitmap == null) {
                    if (!target.detailsLoaded && !target.empty && !detailsRetried) {
                        defer = true;
                    } else {
                        bitmap = generate(target);
                        if (bitmap != null) {
                            // 只把真实封面写盘。占位图（网络/解码失败时画出来的渐变图）
                            // 一旦落盘，下次会直接命中，片库就永远停在那张假封面上。
                            if (generation == diskGeneration) {
                                save(bitmap, disk);
                            }
                            failed.remove(key);
                            failedAt.remove(key);
                        } else {
                            bitmap = placeholder(target);
                            markFailed(key);
                        }
                    }
                }
            } catch (Throwable exc) {
                // 异常（含解码失败）也要交付占位图并进退避，否则这个格子会一直空着，
                // 而且回调拿到的 null 没有任何重试标记。
                if (!defer && bitmap == null) {
                    try {
                        bitmap = placeholder(target);
                    } catch (Throwable ignored) {
                    }
                    markFailed(key);
                }
            }
            if (defer) {
                ensureDetailsThenRetry(target, key);
                return;
            }
            final Bitmap result = bitmap;
            app.runOnMain(() -> {
                if (result != null) {
                    memory.put("cover:" + key, result);
                }
                deliver("cover:" + key, key, result);
            });
        });
    }

    /** 记住这个 key 失败了（占位图不落盘）；表太大就整体清空，别让离线片库把它撑爆。 */
    private void markFailed(String key) {
        if (failedAt.size() > 20_000) {
            failed.clear();
            failedAt.clear();
        }
        failed.add(key);
        failedAt.put(key, System.currentTimeMillis());
    }

    /** 详情就绪后重跑封面；详情失败就按失败处理，别让这次请求永远挂着。 */
    private void ensureDetailsThenRetry(final Work work, final String key) {
        if (!waitingDetails.add(key)) {
            return;
        }
        app.scanner.loadDetails(work, (target, ok, error) -> {
            waitingDetails.remove(key);
            runCoverTask(target, key, true);
        });
    }

    // ---------- 图集大图 ----------
    private static String imageKey(RemoteFile file, int maxWidth) {
        return "img:" + file.rel + ":" + file.offset + ":" + maxWidth;
    }

    private static String localImageKey(File file, int maxWidth) {
        return "file:" + file.getAbsolutePath() + ":" + maxWidth;
    }

    /** 图集里的大图（按屏幕宽度解码）。命中缓存时同步回调，切图当帧就能换画面。 */
    public void loadImage(RemoteFile file, int maxWidth, Callback callback) {
        if (file == null || callback == null) {
            return;
        }
        final String key = imageKey(file, maxWidth);
        Bitmap cached = albumMemory.get(key);
        if (cached != null) {
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe(key, callback)) {
            return;
        }
        runImageTask(key, file, null, maxWidth, pool, false);
    }

    /**
     * 提前把相邻图解码进图集缓存：走独立的低优先级线程池，不占封面 / 切图线程，
     * 也不回调界面。正式加载完全不感知预取，永远走 pool，绝不会被预取队列拖慢；
     * 预取先完成时若正好有正式请求在等，就顺手把结果一起交付。
     */
    public void prefetchImage(RemoteFile file, File local, int maxWidth) {
        if (file == null && local == null) {
            return;
        }
        final String key = local != null ? localImageKey(local, maxWidth)
                : imageKey(file, maxWidth);
        if (albumMemory.get(key) != null || !prefetching.add(key)) {
            return;
        }
        synchronized (pending) {
            if (pending.containsKey(key)) {
                prefetching.remove(key);
                return;
            }
        }
        runImageTask(key, file, local, maxWidth, prefetchPool, true);
    }

    /** 解码一张图，回主线程写缓存并通知所有等待者（正式请求 / 预取共用）。 */
    private void runImageTask(final String key, final RemoteFile file, final File local,
                              final int maxWidth, ExecutorService executor,
                              final boolean prefetch) {
        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (local != null) {
                    bitmap = decodeFile(local, maxWidth);
                } else if (file.inArchive()) {
                    // 作品包成员：按 offset 解出这一张图再解码
                    File unpacked = app.archives.fileSync(file);
                    bitmap = decodeFile(unpacked, maxWidth);
                    if (bitmap != null && file.name.startsWith("图片_01")) {
                        cacheArchiveCover(file.archiveRel, bitmap);
                    }
                } else {
                    byte[] bytes = app.proxy.readAll(file.rel, file.name, file.size, 45000);
                    bitmap = decodeBytes(bytes, maxWidth);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = bitmap;
            app.runOnMain(() -> {
                if (result != null) {
                    albumMemory.put(key, result);
                }
                if (prefetch) {
                    prefetching.remove(key);
                    if (result == null) {
                        // 预取失败别把正式请求的等待队列消费掉：它还在高优先级线程上跑，
                        // 由它自己交付成功结果（或失败），否则画面会永远停在旧图。
                        return;
                    }
                }
                deliver(key, key, result);
            });
        });
    }

    /**
     * 作品播起来以后用解出来的视频/首图生成封面：首页格子下次绑定就能看到真封面。
     * 之前这里已经有占位图，必须覆盖掉。
     */
    public void cacheVideoCover(final Work work, final File video) {
        if (work == null || video == null || !video.isFile()) {
            return;
        }
        final String key = work.key();
        pool.execute(() -> {
            Bitmap bitmap = frame(video);
            if (bitmap == null) {
                return;
            }
            save(bitmap, new File(dir, key + ".jpg"));
            app.runOnMain(() -> {
                memory.remove("cover:" + key);
                memory.put("cover:" + key, bitmap);
                failed.remove(key);
                failedAt.remove(key);
            });
        });
    }

    private void cacheArchiveCover(String archiveRel, Bitmap bitmap) {
        if (archiveRel == null || bitmap == null) {
            return;
        }
        final String key = "w" + Util.sha1Hex(archiveRel).substring(0, 16);
        save(bitmap, new File(dir, key + ".jpg"));
        app.runOnMain(() -> {
            memory.remove("cover:" + key);
            memory.put("cover:" + key, bitmap);
            failed.remove(key);
            failedAt.remove(key);
        });
    }

    /** 下载完成后清掉旧的封面（可能是网络失败时的占位图），让下一帧从本地文件重新生成。 */
    public void invalidateCover(String key) {
        if (key == null) {
            return;
        }
        memory.remove("cover:" + key);
        failed.remove(key);
        failedAt.remove(key);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, key + ".jpg").delete();
    }

    public void clearDisk() {
        // 一万多张封面在主线程删会把界面卡死；放到 io 线程，顺带让在途任务别回写
        diskGeneration++;
        failed.clear();
        failedAt.clear();
        memory.evictAll();
        albumMemory.evictAll();
        prefetching.clear();
        // 清空前把在途请求逐个回一次空结果：直接 pending.clear() 会让这批格子的
        // 回调永远收不到通知，界面停在空白/转圈上。
        final Map<String, List<Callback>> abandoned = new HashMap<>();
        synchronized (pending) {
            abandoned.putAll(pending);
            pending.clear();
        }
        app.runOnMain(() -> {
            for (Map.Entry<String, List<Callback>> entry : abandoned.entrySet()) {
                String task = entry.getKey();
                String key = task.startsWith("cover:") ? task.substring(6) : task;
                for (Callback callback : entry.getValue()) {
                    try {
                        callback.onImage(key, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        app.io.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        });
    }

    /**
     * 换了 WebDAV 连接 / 网络恢复后调用：把当初只画了占位图的封面忘掉，
     * 下一帧就会重新去取真实首帧（占位图不落盘，所以这里只要清内存）。
     */
    public void retryFailedCovers() {
        if (failed.isEmpty()) {
            return;
        }
        for (String key : failed) {
            memory.remove("cover:" + key);
        }
        failed.clear();
        failedAt.clear();
    }

    // ---------- 已下载到本地的图集图片 ----------
    /** 已下载到本地的图片；和远程图共用图集缓存与预取去重。 */
    public void loadLocalImage(File file, int maxWidth, Callback callback) {
        if (callback == null) {
            return;
        }
        if (file == null || !file.isFile()) {
            callback.onImage("file:null", null);
            return;
        }
        final String key = localImageKey(file, maxWidth);
        Bitmap cached = albumMemory.get(key);
        if (cached != null) {
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe(key, callback)) {
            return;
        }
        runImageTask(key, null, file, maxWidth, pool, false);
    }

    // ---------- 等待队列 ----------
    /** true 表示这次请求负责真正去加载；false 表示已经有人在加载，挂上去等结果即可。 */
    private boolean subscribe(String task, Callback callback) {
        synchronized (pending) {
            List<Callback> list = pending.get(task);
            if (list != null) {
                list.add(callback);
                return false;
            }
            list = new ArrayList<>(2);
            list.add(callback);
            pending.put(task, list);
            return true;
        }
    }

    private void deliver(String task, String key, Bitmap bitmap) {
        List<Callback> list;
        synchronized (pending) {
            list = pending.remove(task);
        }
        if (list == null) {
            return;
        }
        for (Callback callback : list) {
            try {
                callback.onImage(key, bitmap);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 生成 ----------
    private Bitmap generate(Work work) {
        // 调用方保证详情已经读过（没读过的会先挂到详情回调上，不在这里阻塞）
        if (!work.detailsLoaded || work.empty) {
            return null;
        }
        if (work.archive != null) {
            if (work.isAlbum()) {
                RemoteFile cover = work.coverFile();
                if (cover == null) {
                    return null;
                }
                try {
                    File local = app.archives.fileSync(cover);
                    return decodeFile(local, 640);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            if (work.isVideo() && work.video != null) {
                return tarFrame(work.video);
            }
            return null;
        }
        if (!work.detailsLoaded && !work.empty) {
            return null;
        }
        Bitmap bitmap = null;
        if (app.offline.has(work)) {
            File video = app.offline.localVideo(work);
            if (video != null) {
                bitmap = frame(video);
            }
            if (bitmap == null) {
                File image = app.offline.localImage(work, 0);
                if (image != null) {
                    bitmap = decodeFile(image, 640);
                }
            }
        }
        if (bitmap != null) {
            return bitmap;
        }
        if (work.isAlbum()) {
            RemoteFile image = work.coverFile();
            if (image != null) {
                try {
                    byte[] bytes = app.proxy.readAll(image.rel, image.name, image.size, 45000);
                    bitmap = decodeBytes(bytes, 640);
                } catch (Throwable ignored) {
                }
            }
        } else if (work.video != null) {
            bitmap = frame(work.video);
        }
        // 失败时返回 null：占位图由调用方生成且不落盘（否则假封面会被永久缓存）
        return bitmap;
    }

    private Bitmap frame(File local) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(local.getAbsolutePath());
            Bitmap bitmap = retriever.getFrameAtTime(300000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0L);
            }
            return scaleDown(bitmap, 640);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private Bitmap frame(RemoteFile video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            String url = app.proxy.urlFor(video.rel, video.name, video.size);
            retriever.setDataSource(url);
            Bitmap bitmap = retriever.getFrameAtTime(300000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0L);
            }
            return scaleDown(bitmap, 640);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * tar 视频成员：小/中视频直接把「视频.mp4」解到本地再取首帧（和播放共用缓存，
     * 点开时不用再解一次）；大视频先用 MediaDataSource 按 Range 取帧，避免为了
     * 一张封面下载整段大文件，取不到再退回解包。
     */
    private Bitmap tarFrame(RemoteFile member) {
        if (member == null || member.size <= 0) {
            return null;
        }
        boolean large = member.size > 80L * 1024 * 1024;
        if (large) {
            Bitmap viaRange = tarFrameViaDataSource(member);
            if (viaRange != null) {
                return viaRange;
            }
        }
        try {
            File local = app.archives.fileSync(member);
            Bitmap bitmap = frame(local);
            if (bitmap != null) {
                return bitmap;
            }
        } catch (Throwable ignored) {
        }
        return large ? null : tarFrameViaDataSource(member);
    }

    private Bitmap tarFrameViaDataSource(RemoteFile member) {
        TarMediaDataSource source = new TarMediaDataSource(app, member);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source);
            Bitmap bitmap = retriever.getFrameAtTime(300000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0L);
            }
            return scaleDown(bitmap, 640);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
            source.close();
        }
    }

    private Bitmap placeholder(Work work) {
        // 首页格子实际就 300 多像素宽，占位图没必要用 540×720 的 ARGB（一张 1.5MB）
        int width = 360;
        int height = 480;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        int seed = Math.abs(work.key().hashCode());
        int top = 0xFF1A1720 + (seed % 0x00101010);
        int bottom = 0xFF0B0B0F;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        String initial = AvatarStore.initialOf(work.displayAuthor());
        paint.setColor(0x33FFFFFF);
        paint.setTextSize(160f);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(initial, width / 2f, height * 0.52f, paint);

        String title = work.displayTitle();
        if (title.length() > 14) {
            title = title.substring(0, 14) + "…";
        }
        paint.setTextSize(20f);
        paint.setFakeBoldText(false);
        paint.setColor(0xCCFFFFFF);
        canvas.drawText(title, width / 2f, height * 0.82f, paint);
        return bitmap;
    }

    // ---------- 解码 ----------
    private static Bitmap decodeFile(File file, int maxWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Bitmap decodeBytes(byte[] bytes, int maxWidth) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static int sampleSize(int width, int height, int maxWidth) {
        int sample = 1;
        int longest = Math.max(width, height);
        int target = Math.max(1, maxWidth);
        while (longest / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxWidth) {
        if (bitmap == null) {
            return null;
        }
        if (bitmap.getWidth() <= maxWidth) {
            return bitmap;
        }
        float ratio = maxWidth / (float) bitmap.getWidth();
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, maxWidth,
                Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    private void save(Bitmap bitmap, File target) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            // 直接压到文件流：以前先压进 ByteArrayOutputStream 再 toByteArray，
            // 一张封面要多分配一次几十 KB 的数组，滚动时会累积成 GC 压力
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output);
        } catch (Throwable exc) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(target);
        if (savesSincePrune.incrementAndGet() >= 200) {
            savesSincePrune.set(0);
            pruneIfNeeded();
        }
    }

    /** 封面目录没有天然上限：一万多个作品全看一遍就是几百 MB，超过 8000 张按最久没用的清到 6000。 */
    private void pruneIfNeeded() {
        if (pruning) {
            return;
        }
        pruning = true;
        app.io.execute(() -> {
            try {
                File[] files = dir.listFiles();
                if (files == null || files.length <= COVER_CAP) {
                    return;
                }
                java.util.Arrays.sort(files,
                        (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
                int remove = files.length - COVER_KEEP;
                for (int index = 0; index < remove; index++) {
                    //noinspection ResultOfMethodCallIgnored
                    files[index].delete();
                }
            } catch (Throwable ignored) {
            } finally {
                pruning = false;
            }
        });
    }
}
