package com.jingying.watch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 作者头像：读取 WebDAV 上「净影 · 电脑版」上传的
 * `作者资料/authors.json`，按 抖音号 / 昵称 找到头像文件，
 * 再通过本地代理下载、缓存。
 *
 * 依次尝试：authors.json 里的 avatar_local → avatar_history →
 * `作者资料/authors/<sec_uid>/` 目录里最新的一张图；都没有就回退到「首字」。
 * 首字用 code point 取，作者名以 emoji 开头也不会被截成半个字符。
 */
public final class AvatarStore {

    public interface Callback {
        void onAvatar(String author, String uid, Bitmap bitmap);
    }

    /** 只关心 sec_uid（抖音主页 ID）的回调。 */
    public interface SecUidCallback {
        void onSecUid(String secUid);
    }

    private final App app;
    private final File dir;
    private final Map<String, List<String>> byUid = new HashMap<>();
    private final Map<String, List<String>> byNick = new HashMap<>();
    private final Map<String, String> secByUid = new HashMap<>();
    private final Map<String, String> secByNick = new HashMap<>();
    /**
     * 头像内存缓存。以前是无上限的 ConcurrentHashMap：片库几千位作者全看过一遍
     * 就一直占着内存。头像只有几十 KB，给 8MB 的上限足够，LRU 自动回收。
     * android.util.LruCache 的方法本身是同步的，主线程取、下载线程写都安全。
     */
    private final android.util.LruCache<String, Bitmap> memory;
    /** 找不到头像的作者记一笔，短期内别再为同一个 key 重复联网。 */
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static final long FAIL_TTL_MS = 10 * 60 * 1000L;
    /** 同一张头像正在下载时，后来的请求挂在这里等结果，不能直接丢掉。 */
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private final Object pendingLock = new Object();
    private final Object lock = new Object();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "watch-avatars");
            thread.setDaemon(true);
            return thread;
        }
    });
    private volatile boolean indexReady;
    /** 有一个线程正在联网拉 authors.json（联网本身在锁外）。 */
    private volatile boolean indexLoading;
    /** 联网失败后的退避时间点。 */
    private volatile long nextIndexRetryAt;
    private volatile String root = "净影/作者资料";

    public AvatarStore(App app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "avatars");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        int maxKb = (int) Math.min(8 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 32);
        memory = new android.util.LruCache<String, Bitmap>(Math.max(1024, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void setRoot(String authorRoot) {
        if (authorRoot != null && !authorRoot.trim().isEmpty()) {
            root = authorRoot.trim();
        }
    }

    public String root() {
        return root;
    }

    /** 片库换位置后清空索引，下一次按需重新拉。 */
    public void reset() {
        synchronized (lock) {
            byUid.clear();
            byNick.clear();
            secByUid.clear();
            secByNick.clear();
            memory.evictAll();
            failedAt.clear();
            indexReady = false;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "authors.json").delete();
    }

    /** 用户点「重新扫描」时后台刷新一次作者资料（不阻塞界面）。 */
    public void refresh() {
        pool.execute(() -> {
            synchronized (lock) {
                byUid.clear();
                byNick.clear();
                secByUid.clear();
                secByNick.clear();
                failedAt.clear();
                indexReady = false;
                nextIndexRetryAt = 0L;
            }
            // 本地缓存必须一起删：ensureIndex 会先读 authors.json，
            // 只要读到旧文件就不会再联网，「重新扫描作者资料」等于什么都没做
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "authors.json").delete();
            ensureIndex();
        });
    }

    public void load(Work work, Callback callback) {
        if (work == null || callback == null) {
            return;
        }
        final String author = work.displayAuthor();
        final String uid = work.uniqueId == null ? "" : work.uniqueId;
        final String key = keyFor(author, uid);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            app.runOnMain(() -> callback.onAvatar(author, uid, cached));
            return;
        }
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < FAIL_TTL_MS) {
                // 刚试过、找不到：直接回落成首字，别再把唯一一条头像线程堵在这
                app.runOnMain(() -> callback.onAvatar(author, uid, null));
                return;
            }
            failedAt.remove(key);
        }
        if (!subscribe(key, callback)) {
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                File disk = new File(dir, key + ".jpg");
                if (disk.isFile()) {
                    bitmap = decodeFile(disk);
                }
                if (bitmap == null) {
                    ensureIndex();
                    List<String> candidates = relsFor(author, uid);
                    for (int index = 0; index < candidates.size() && bitmap == null; index++) {
                        bitmap = download(candidates.get(index));
                    }
                    if (bitmap == null) {
                        String secUid = secFor(author, uid);
                        String folderRel = folderAvatar(secUid);
                        if (folderRel != null) {
                            bitmap = download(folderRel);
                        }
                    }
                }
                if (bitmap != null) {
                    save(bitmap, new File(dir, key + ".jpg"));
                    failedAt.remove(key);
                } else if (indexReady) {
                    // 只有「确实查过作者资料」才记负缓存；索引都还没读到就别记，
                    // 否则网络慢一点就会把这位作者的头像封 10 分钟
                    if (failedAt.size() > 4096) {
                        failedAt.clear();
                    }
                    failedAt.put(key, System.currentTimeMillis());
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = bitmap;
            if (result != null) {
                memory.put(key, result);
            }
            app.runOnMain(() -> deliver(key, author, uid, result));
        });
    }

    /** true 表示这次请求负责真正去下载；false 表示已有人在下载，挂上去等结果即可。 */
    private boolean subscribe(String key, Callback callback) {
        synchronized (pendingLock) {
            List<Callback> list = pending.get(key);
            if (list != null) {
                list.add(callback);
                return false;
            }
            list = new ArrayList<>(2);
            list.add(callback);
            pending.put(key, list);
            return true;
        }
    }

    private void deliver(String key, String author, String uid, Bitmap bitmap) {
        List<Callback> list;
        synchronized (pendingLock) {
            list = pending.remove(key);
        }
        if (list == null) {
            return;
        }
        for (Callback callback : list) {
            try {
                callback.onAvatar(author, uid, bitmap);
            } catch (Exception ignored) {
            }
        }
    }

    public static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "净";
        }
        String value = name.trim();
        int codePoint = value.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    // ---------- 索引 ----------
    /**
     * 读作者资料：先用本地 authors.json，没有才联网。
     * 联网这一段刻意放在锁外——以前整段都攥着 lock，主线程上的 cachedSecUid
     * 会跟着等一次最长 40 秒的网络读，点「打开抖音主页」就卡成 ANR。
     */
    private void ensureIndex() {
        if (indexReady) {
            return;
        }
        synchronized (lock) {
            if (indexReady) {
                return;
            }
            if (System.currentTimeMillis() < nextIndexRetryAt) {
                // 上次联网失败，退避 60 秒再来，别每个头像请求都重打一次
                return;
            }
            if (byUid.isEmpty() && byNick.isEmpty()) {
                File cache = new File(dir, "authors.json");
                if (cache.isFile()) {
                    try {
                        parse(readText(cache));
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!byUid.isEmpty() || !byNick.isEmpty()) {
                indexReady = true;
                return;
            }
            if (indexLoading) {
                // 已经有线程在联网：不持锁干等，最多等 5 秒拿它的结果
                long deadline = System.currentTimeMillis() + 5000L;
                while (indexLoading && !indexReady && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(100L);
                    } catch (InterruptedException exc) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return;
            }
            indexLoading = true;
        }

        String text = null;
        try {
            String rel = root + "/authors.json";
            byte[] bytes = app.proxy.readAll(rel, "authors.json", 0, 40000);
            text = new String(bytes, Charset.forName("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            synchronized (lock) {
                if (text != null) {
                    byUid.clear();
                    byNick.clear();
                    secByUid.clear();
                    secByNick.clear();
                    try {
                        parse(text);
                    } catch (Exception ignored) {
                    }
                }
                indexLoading = false;
                if (text != null) {
                    indexReady = true;
                } else {
                    // 拉失败了：留个退避时间，下次请求过一会儿再试
                    nextIndexRetryAt = System.currentTimeMillis() + 60000L;
                }
                lock.notifyAll();
            }
        }
        if (text != null) {
            writeText(new File(dir, "authors.json"), text);
        }
    }

    private void parse(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        JSONArray array = new JSONArray(text);
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            List<String> rels = relsFromEntry(entry);
            String uid = entry.optString("unique_id", "").trim();
            String nick = entry.optString("nickname", "").trim();
            String sec = entry.optString("sec_uid", "").trim();
            // 没有头像文件、但有 sec_uid 的作者也要索引：
            // 「打开抖音主页」只依赖 sec_uid，不依赖头像
            if (rels.isEmpty() && sec.isEmpty()) {
                continue;
            }
            if (!uid.isEmpty()) {
                if (!rels.isEmpty()) {
                    byUid.put(uid, rels);
                }
                if (!sec.isEmpty()) {
                    secByUid.put(uid, sec);
                }
            }
            if (!nick.isEmpty()) {
                if (!rels.isEmpty()) {
                    byNick.put(nick, rels);
                    byNick.put(normalizeNick(nick), rels);
                }
                if (!sec.isEmpty()) {
                    secByNick.put(nick, sec);
                    secByNick.put(normalizeNick(nick), sec);
                }
            }
        }
    }

    private List<String> relsFromEntry(JSONObject entry) {
        List<String> paths = new ArrayList<>();
        String local = entry.optString("avatar_local", "").trim();
        if (!local.isEmpty()) {
            paths.add(local);
        }
        JSONArray history = entry.optJSONArray("avatar_history");
        if (history != null) {
            for (int index = history.length() - 1; index >= 0; index--) {
                String path = history.optString(index, "").trim();
                if (!path.isEmpty() && !paths.contains(path)) {
                    paths.add(path);
                }
            }
        }
        List<String> rels = new ArrayList<>();
        for (String path : paths) {
            String rel = relFromLocal(path);
            if (rel != null && !rels.contains(rel)) {
                rels.add(rel);
            }
            if (rels.size() >= 3) {
                break;
            }
        }
        return rels;
    }

    /** `C:\\...\\作者资料\\authors\\<uid>\\avatar.jpg` → `<root>/authors/<uid>/avatar.jpg` */
    private String relFromLocal(String path) {
        String value = path.replace('\\', '/');
        int marker = value.indexOf("作者资料");
        String suffix;
        if (marker >= 0) {
            suffix = value.substring(marker + "作者资料".length());
        } else {
            int authors = value.toLowerCase(Locale.US).indexOf("/authors/");
            if (authors < 0) {
                return null;
            }
            suffix = value.substring(authors);
        }
        while (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        }
        if (suffix.isEmpty()) {
            return null;
        }
        return root + "/" + suffix;
    }

    private List<String> relsFor(String author, String uid) {
        List<String> rels = null;
        if (uid != null && !uid.isEmpty()) {
            rels = byUid.get(uid);
        }
        if (rels == null && author != null) {
            rels = byNick.get(author);
            if (rels == null) {
                rels = byNick.get(normalizeNick(author));
            }
        }
        return rels == null ? new ArrayList<>() : rels;
    }

    private String secFor(String author, String uid) {
        String sec = null;
        if (uid != null && !uid.isEmpty()) {
            sec = secByUid.get(uid);
        }
        if (sec == null && author != null) {
            sec = secByNick.get(author);
            if (sec == null) {
                sec = secByNick.get(normalizeNick(author));
            }
        }
        return sec;
    }

    /**
     * 已经读到 authors.json 时直接返回 sec_uid（抖音主页 ID），没读到就返回空串。
     * 不联网、不阻塞，专门给「拉起抖音」用。
     */
    public String cachedSecUid(String author, String uid) {
        synchronized (lock) {
            if (!indexReady) {
                return "";
            }
            String sec = secFor(author, uid);
            return sec == null ? "" : sec;
        }
    }

    /** 后台把 authors.json 读出来（下次就能直接跳主页），完成后回调 sec_uid。 */
    public void loadSecUid(final String author, final String uid, final SecUidCallback callback) {
        pool.execute(() -> {
            String sec = "";
            try {
                ensureIndex();
                synchronized (lock) {
                    String value = secFor(author, uid);
                    sec = value == null ? "" : value;
                }
            } catch (Throwable ignored) {
            }
            final String result = sec;
            if (callback != null) {
                app.runOnMain(() -> callback.onSecUid(result));
            }
        });
    }

    /** authors.json 里的本地路径可能来自另一台机器；按 sec_uid 列一次目录兜底。 */
    private String folderAvatar(String secUid) {
        if (secUid == null || secUid.isEmpty()) {
            return null;
        }
        DavClient client = app.dav();
        if (client == null) {
            return null;
        }
        try {
            List<DavEntry> entries = client.propfind(root + "/authors/" + secUid, 1);
            DavEntry best = null;
            for (DavEntry entry : entries) {
                if (entry.dir || !isImage(entry.name)) {
                    continue;
                }
                if (best == null || entry.mtime > best.mtime) {
                    best = entry;
                }
            }
            return best == null ? null : best.rel;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isImage(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".webp") || low.endsWith(".gif");
    }

    private Bitmap download(String rel) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = app.proxy.readAll(rel, RemoteFile.baseName(rel), 0, 25000);
            return decodeBytes(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeNick(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[ .]+$", "").trim();
    }

    private static String keyFor(String author, String uid) {
        return Util.sha1Hex(author + "|" + uid).substring(0, 16);
    }

    // ---------- 文件 ----------
    private static Bitmap decodeFile(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 和 decodeBytes 用同一套取样规则：否则同一张头像「本次会话清晰、重启后糊一半」
        options.inSampleSize = avatarSample(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static int avatarSample(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= 160) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap decodeBytes(byte[] bytes) {
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
        options.inSampleSize = avatarSample(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void save(Bitmap bitmap, File target) {
        File temp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, buffer);
            output.write(buffer.toByteArray());
        } catch (Throwable exc) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(target);
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(Charset.forName("UTF-8")));
        } catch (Exception ignored) {
        }
    }
}
