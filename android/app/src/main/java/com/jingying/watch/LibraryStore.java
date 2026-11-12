package com.jingying.watch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 片库索引：骨架作品立即返回、文件清单按需补全，全部落盘到 library.json。
 * 下次打开 App 不用等 WebDAV，先看到上次的片库。
 *
 * 内存里维护 rel / 作者两张索引：首页和扫描都要按 rel、按作者取作品，
 * 1.6 万条的线性扫描会直接卡住主线程。
 */
public final class LibraryStore {

    private static final int VERSION = 3;
    /** 读详情、读文案、读音乐连着触发落盘，攒一下再写，避免每次重序列化几 MB JSON。 */
    private static final long SAVE_DEBOUNCE_MS = 500L;

    private final File file;
    private final List<Work> works = new ArrayList<>();
    private final Map<String, Work> byRel = new HashMap<>();
    private final Map<String, List<Work>> byAuthor = new HashMap<>();
    private final Map<String, Long> authorMtimes = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "library-writer");
        thread.setDaemon(true);
        return thread;
    });
    /** 后台落盘和扫描结束时的 saveNow 可能同时发生，temp 文件是同一个，必须串起来。 */
    private final Object writeLock = new Object();
    private String videoRoot = "";
    private String musicRoot = "";
    private String authorRoot = "";
    private long scannedAt;
    private boolean dirty;
    private boolean saving;
    private volatile boolean loaded;
    /** 作品集合每变一次（扫描合并/删除/清空）就 +1，界面用它判断要不要重新过滤列表。 */
    private volatile int revision;

    public LibraryStore(Context context) {
        File dir = new File(context.getFilesDir(), "library");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        file = new File(dir, "library.json");
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ---------- 读取 ----------
    public synchronized void load() {
        if (!file.isFile()) {
            loaded = true;
            return;
        }
        try {
            String text = readText(file);
            JSONObject json = new JSONObject(text);
            if (json.optInt("version", 0) < 1) {
                return;
            }
            videoRoot = json.optString("videoRoot", "");
            musicRoot = json.optString("musicRoot", "");
            authorRoot = json.optString("authorRoot", "");
            scannedAt = json.optLong("scannedAt", 0L);
            works.clear();
            JSONArray array = json.optJSONArray("works");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    Work work = Work.fromJson(array.optJSONObject(index));
                    if (work != null) {
                        works.add(work);
                    }
                }
            }
            Library.sortByTime(works);
            reindex();
            revision++;
            authorMtimes.clear();
            JSONObject authors = json.optJSONObject("authors");
            if (authors != null) {
                java.util.Iterator<String> keys = authors.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    authorMtimes.put(key, authors.optLong(key, 0L));
                }
            }
        } catch (Exception ignored) {
        } finally {
            loaded = true;
        }
    }

    private static String readText(File file) throws Exception {
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    // ---------- 查询 ----------
    public synchronized List<Work> works() {
        return new ArrayList<>(works);
    }

    public synchronized int size() {
        return works.size();
    }

    public int revision() {
        return revision;
    }

    public synchronized Work find(String rel) {
        return rel == null ? null : byRel.get(rel);
    }

    public synchronized String videoRoot() {
        return videoRoot;
    }

    public synchronized String musicRoot() {
        return musicRoot;
    }

    public synchronized String authorRoot() {
        return authorRoot;
    }

    public synchronized void setAuthorRoot(String root) {
        authorRoot = root == null ? "" : root;
        saveAsync();
    }

    public synchronized void setRoots(String videos, String music) {
        videoRoot = videos == null ? "" : videos;
        musicRoot = music == null ? "" : music;
        saveAsync();
    }

    public synchronized long scannedAt() {
        return scannedAt;
    }

    public synchronized void setScannedAt(long value) {
        scannedAt = Math.max(0L, value);
    }

    public synchronized Long authorMtime(String rel) {
        return authorMtimes.get(rel);
    }

    public synchronized List<Work> worksForAuthor(String authorRel) {
        List<Work> list = byAuthor.get(authorRel);
        return list == null ? new ArrayList<Work>() : new ArrayList<>(list);
    }

    public synchronized void setAuthorMtime(String rel, long mtime) {
        authorMtimes.put(rel, mtime);
    }

    // ---------- 写入 ----------
    public synchronized void replaceAll(Collection<Work> items) {
        works.clear();
        works.addAll(items);
        Library.sortByTime(works);
        reindex();
        revision++;
        saveAsync();
    }

    public synchronized void addBatch(Collection<Work> items) {
        Map<String, Work> merged = new HashMap<>(Math.max(16, byRel.size() + items.size()));
        merged.putAll(byRel);
        for (Work work : items) {
            if (work == null || work.rel == null || work.rel.isEmpty()) {
                continue;
            }
            Work existing = byRel.get(work.rel);
            if (existing != null && existing != work && existing.detailsLoaded
                    && !existing.empty && !work.detailsLoaded) {
                // 扫描拿到的只是骨架：并进已有对象，别把详情/文案/音乐/封面一起冲掉。
                // 用同一个对象还能让播放页、收藏页手里那份列表跟着更新。
                existing.mergeSkeleton(work);
                merged.put(work.rel, existing);
                continue;
            }
            merged.put(work.rel, work);
        }
        works.clear();
        works.addAll(merged.values());
        Library.sortByTime(works);
        reindex();
        revision++;
        saveAsync();
    }

    public synchronized void update(Work work) {
        if (work == null) {
            return;
        }
        Work existing = byRel.get(work.rel);
        if (existing == work) {
            // 详情是原地合并进同一个对象的，列表里已经是它
            saveAsync();
            return;
        }
        if (existing != null) {
            int index = works.indexOf(existing);
            if (index >= 0) {
                works.set(index, work);
            } else {
                works.add(work);
                Library.sortByTime(works);
            }
        } else {
            works.add(work);
            Library.sortByTime(works);
        }
        byRel.put(work.rel, work);
        indexAuthor(work, existing);
        saveAsync();
    }

    public synchronized void retain(Set<String> rels) {
        Set<String> keep = new HashSet<>(rels);
        List<Work> next = new ArrayList<>();
        for (Work work : works) {
            if (keep.contains(work.rel)) {
                next.add(work);
            }
        }
        if (next.size() != works.size()) {
            works.clear();
            works.addAll(next);
            reindex();
            revision++;
            saveAsync();
        }
    }

    /** 云端删除成功后从本地索引里摘掉一个作品。 */
    public synchronized Work remove(String rel) {
        if (rel == null) {
            return null;
        }
        Work removed = byRel.remove(rel);
        if (removed == null) {
            return null;
        }
        works.remove(removed);
        List<Work> list = byAuthor.get(removed.authorRel);
        if (list != null) {
            list.remove(removed);
            if (list.isEmpty()) {
                byAuthor.remove(removed.authorRel);
            }
        }
        revision++;
        saveAsync();
        return removed;
    }

    /** 云端删除成功后从本地索引里摘掉一位作者的全部作品；调用方负责清本地下载。 */
    public synchronized List<Work> removeAuthor(String authorRel) {
        List<Work> removed = new ArrayList<>();
        if (authorRel == null) {
            return removed;
        }
        List<Work> next = new ArrayList<>(works.size());
        for (Work work : works) {
            if (authorRel.equals(work.authorRel)) {
                byRel.remove(work.rel);
                removed.add(work);
            } else {
                next.add(work);
            }
        }
        if (!removed.isEmpty()) {
            works.clear();
            works.addAll(next);
            byAuthor.remove(authorRel);
            revision++;
            saveAsync();
        }
        return removed;
    }

    public synchronized void clear() {
        works.clear();
        byRel.clear();
        byAuthor.clear();
        authorMtimes.clear();
        videoRoot = "";
        musicRoot = "";
        authorRoot = "";
        scannedAt = 0L;
        revision++;
        saveAsync();
    }

    // ---------- 索引 ----------
    private void reindex() {
        byRel.clear();
        byAuthor.clear();
        for (Work work : works) {
            byRel.put(work.rel, work);
            List<Work> list = byAuthor.get(work.authorRel);
            if (list == null) {
                list = new ArrayList<>();
                byAuthor.put(work.authorRel, list);
            }
            list.add(work);
        }
    }

    private void indexAuthor(Work work, Work previous) {
        if (previous != null && previous.authorRel.equals(work.authorRel)) {
            List<Work> list = byAuthor.get(work.authorRel);
            if (list != null) {
                int index = list.indexOf(previous);
                if (index >= 0) {
                    list.set(index, work);
                } else {
                    list.add(work);
                }
                return;
            }
        }
        if (previous != null) {
            List<Work> old = byAuthor.get(previous.authorRel);
            if (old != null) {
                old.remove(previous);
            }
        }
        List<Work> list = byAuthor.get(work.authorRel);
        if (list == null) {
            list = new ArrayList<>();
            byAuthor.put(work.authorRel, list);
        }
        list.add(work);
    }

    // ---------- 落盘 ----------
    public void saveAsync() {
        boolean start;
        synchronized (this) {
            dirty = true;
            start = !saving;
            saving = true;
        }
        if (start) {
            writer.execute(this::drainSaves);
        }
    }

    /** 后台线程循环落盘：先在锁里拷贝快照，再在锁外序列化，主线程不会被 8MB JSON 卡住。 */
    private void drainSaves() {
        int writeFailures = 0;
        try {
            while (true) {
                synchronized (this) {
                    if (!dirty) {
                        saving = false;
                        return;
                    }
                }
                // 连续几次 update（读详情 → 读文案 → 读音乐）合并成一次写盘
                try {
                    Thread.sleep(SAVE_DEBOUNCE_MS);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                }
                Snapshot snapshot;
                synchronized (this) {
                    if (!dirty) {
                        saving = false;
                        return;
                    }
                    dirty = false;
                    snapshot = new Snapshot(new ArrayList<>(works), videoRoot, musicRoot,
                            authorRoot, scannedAt, new HashMap<>(authorMtimes));
                }
                if (!writeAtomically(toJson(snapshot).toString())) {
                    // 写失败（磁盘满/权限）：恢复 dirty 并退避重试，别静默丢掉这次更新
                    synchronized (this) {
                        dirty = true;
                    }
                    if (++writeFailures >= 5) {
                        synchronized (this) {
                            saving = false;
                        }
                        return;
                    }
                    Thread.sleep(SAVE_DEBOUNCE_MS * (long) Math.min(writeFailures, 4));
                } else {
                    writeFailures = 0;
                }
            }
        } catch (Throwable ignored) {
            // 序列化失败（比如内存不足）也要把写线程放出来，否则后面再也不落盘
            boolean retry;
            synchronized (this) {
                saving = false;
                retry = dirty;
            }
            if (retry) {
                saveAsync();
            }
        }
    }

    public void saveNow() {
        Snapshot snapshot;
        synchronized (this) {
            dirty = false;
            snapshot = new Snapshot(new ArrayList<>(works), videoRoot, musicRoot,
                    authorRoot, scannedAt, new HashMap<>(authorMtimes));
        }
        if (!writeAtomically(toJson(snapshot).toString())) {
            // 同步保存失败也要留着 dirty，等下一次 saveAsync 重试
            synchronized (this) {
                dirty = true;
            }
        }
    }

    private static final class Snapshot {
        final List<Work> works;
        final String videoRoot;
        final String musicRoot;
        final String authorRoot;
        final long scannedAt;
        final Map<String, Long> authors;

        Snapshot(List<Work> works, String videoRoot, String musicRoot,
                 String authorRoot, long scannedAt, Map<String, Long> authors) {
            this.works = works;
            this.videoRoot = videoRoot;
            this.musicRoot = musicRoot;
            this.authorRoot = authorRoot;
            this.scannedAt = scannedAt;
            this.authors = authors;
        }
    }

    private static JSONObject toJson(Snapshot snapshot) {
        JSONObject json = new JSONObject();
        try {
            json.put("version", VERSION);
            json.put("videoRoot", snapshot.videoRoot);
            json.put("musicRoot", snapshot.musicRoot);
            json.put("authorRoot", snapshot.authorRoot);
            json.put("scannedAt", snapshot.scannedAt);
            JSONArray array = new JSONArray();
            for (Work work : snapshot.works) {
                array.put(work.toJson());
            }
            json.put("works", array);
            JSONObject authors = new JSONObject();
            for (Map.Entry<String, Long> entry : snapshot.authors.entrySet()) {
                authors.put(entry.getKey(), entry.getValue());
            }
            json.put("authors", authors);
        } catch (Exception ignored) {
        }
        return json;
    }

    /** 返回 true 才算写成功；失败要告诉调用方恢复 dirty，否则这次索引更新会永久丢。 */
    private boolean writeAtomically(String text) {
        synchronized (writeLock) {
            File temp = new File(file.getParentFile(), "library.json.tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                OutputStreamWriter writer = new OutputStreamWriter(output, Charset.forName("UTF-8"));
                writer.write(text);
                writer.flush();
            } catch (Exception exc) {
                android.util.Log.w("JYWatch", "写片库索引失败", exc);
                return false;
            }
            if (!temp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                android.util.Log.w("JYWatch", "片库索引重命名失败");
                return false;
            }
            return true;
        }
    }
}
