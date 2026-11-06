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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "watch-covers");
            thread.setDaemon(true);
            return thread;
        }
    });
    /**
     * 同一个 key 正在加载时，后来的请求挂在这里一起等结果。
     * 之前是直接 return，回调永远不来 —— 快速滚动时格子会一直空着。
     */
    private final Map<String, List<Callback>> pending = new HashMap<>();

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
    }

    public Bitmap peek(String key) {
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
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe("cover:" + key, callback)) {
            return;
        }
        final Work target = work;
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                File disk = new File(dir, key + ".jpg");
                if (disk.isFile()) {
                    bitmap = decodeFile(disk, 640);
                }
                if (bitmap == null) {
                    bitmap = generate(target);
                    if (bitmap != null) {
                        save(bitmap, disk);
                    }
                }
            } catch (Throwable ignored) {
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

    /** 图集里的大图（按屏幕宽度解码）。 */
    public void loadImage(RemoteFile file, int maxWidth, Callback callback) {
        if (file == null || callback == null) {
            return;
        }
        final String key = "img:" + file.rel + ":" + maxWidth;
        Bitmap cached = memory.get(key);
        if (cached != null) {
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe(key, callback)) {
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = app.proxy.readAll(file.rel, file.name, file.size, 45000);
                bitmap = decodeBytes(bytes, maxWidth);
            } catch (Throwable ignored) {
            }
            final Bitmap result = bitmap;
            app.runOnMain(() -> {
                if (result != null) {
                    memory.put(key, result);
                }
                deliver(key, key, result);
            });
        });
    }

    /** 下载完成后清掉旧的封面（可能是网络失败时的占位图），让下一帧从本地文件重新生成。 */
    public void invalidateCover(String key) {
        if (key == null) {
            return;
        }
        memory.remove("cover:" + key);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, key + ".jpg").delete();
    }

    public void clearDisk() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        memory.evictAll();
    }

    // ---------- 本地下载 ----------
    /** 已下载到本地的图片/视频首帧。 */
    public void loadLocalImage(File file, int maxWidth, Callback callback) {
        if (callback == null) {
            return;
        }
        if (file == null || !file.isFile()) {
            callback.onImage("file:null", null);
            return;
        }
        final String key = "file:" + file.getAbsolutePath() + ":" + maxWidth;
        Bitmap cached = memory.get(key);
        if (cached != null) {
            callback.onImage(key, cached);
            return;
        }
        if (!subscribe(key, callback)) {
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeFile(file, maxWidth);
            } catch (Throwable ignored) {
            }
            final Bitmap result = bitmap;
            app.runOnMain(() -> {
                if (result != null) {
                    memory.put(key, result);
                }
                deliver(key, key, result);
            });
        });
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
        if (!work.detailsLoaded && !work.empty) {
            ensureDetails(work);
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
        if (bitmap == null) {
            bitmap = placeholder(work);
        }
        return bitmap;
    }

    private void ensureDetails(Work work) {
        final CountDownLatch latch = new CountDownLatch(1);
        app.scanner.loadDetails(work, (target, ok, error) -> latch.countDown());
        try {
            latch.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
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
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, buffer);
            output.write(buffer.toByteArray());
        } catch (Throwable exc) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(target);
    }
}
