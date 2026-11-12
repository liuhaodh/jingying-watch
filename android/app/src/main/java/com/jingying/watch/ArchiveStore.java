package com.jingying.watch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 作品包（tar）的解包缓存。
 *
 * 播放/看图时只把**需要的那一个成员**按 Range 从 WebDAV 解到本地：
 * tar 头只有 512 字节，成员数据直接跳到 offset 读，不会为了一个封面把整包拉下来。
 * 解出来的文件放在 `files/archive/<包路径哈希>/<文件名>`，同一个包重复播放直接复用。
 *
 * 服务器不支持 Range（返回 200 而不是 206）时才退回「整包下到 cache 再本地解」。
 */
public final class ArchiveStore {

    public interface FileCallback {
        void onFile(File file, String error);
    }

    /** 解包缓存上限；超过后按目录最后使用时间清掉最旧的。 */
    private static final long CACHE_LIMIT = 2L * 1024 * 1024 * 1024;
    private static final int MAX_READ = 64 * 1024;

    private final App app;
    private final File root;
    private final File fullRoot;
    private final Map<String, Object> locks = new HashMap<>();
    private final Object lockGuard = new Object();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "watch-archive");
        thread.setDaemon(true);
        return thread;
    });

    public ArchiveStore(App app) {
        this.app = app;
        this.root = new File(app.getFilesDir(), "archive");
        this.fullRoot = new File(root, "full");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        fullRoot.mkdirs();
    }

    // ---------- 对外 ----------
    /** 已经解好的本地文件；没有或大小对不上返回 null。 */
    public File cached(RemoteFile member) {
        File target = cacheFile(member);
        if (target == null || !target.isFile()) {
            return null;
        }
        if (member.size > 0 && target.length() != member.size) {
            return null;
        }
        // 记录一次访问时间，给 LRU 清理用
        //noinspection ResultOfMethodCallIgnored
        target.setLastModified(System.currentTimeMillis());
        return target;
    }

    /** 异步解包一个成员（回调在主线程）。 */
    public void file(final RemoteFile member, final FileCallback callback) {
        File ready = cached(member);
        if (ready != null) {
            if (callback != null) {
                app.runOnMain(() -> callback.onFile(ready, null));
            }
            return;
        }
        pool.execute(() -> {
            File result = null;
            String error = null;
            try {
                result = fileSync(member);
            } catch (Exception exc) {
                error = exc.getMessage() == null ? "解包失败" : exc.getMessage();
            }
            final File file = result;
            final String message = error;
            if (callback != null) {
                app.runOnMain(() -> callback.onFile(file, message));
            }
        });
    }

    /** 同步解包（只能在后台线程调用）。 */
    public File fileSync(RemoteFile member) throws Exception {
        File target = cacheFile(member);
        if (target == null) {
            throw new IOException("作品包成员路径不合法");
        }
        File ready = cached(member);
        if (ready != null) {
            return ready;
        }
        synchronized (lockFor(target.getAbsolutePath())) {
            ready = cached(member);
            if (ready != null) {
                return ready;
            }
            try {
                TarReader.Source source = sourceFor(member);
                try {
                    TarReader.Member item = new TarReader.Member(
                            member.name, member.name, member.size, member.offset);
                    TarReader.extract(source, item, target);
                } finally {
                    source.close();
                }
            } catch (TarReader.RangeUnsupportedException exc) {
                // 服务器不支持 Range：整包下到本地，再从本地 tar 解
                File tar = downloadFull(member.archiveRel);
                TarReader.Source local = new TarReader.FileSource(tar);
                try {
                    TarReader.Member item = new TarReader.Member(
                            member.name, member.name, member.size, member.offset);
                    TarReader.extract(local, item, target);
                } finally {
                    local.close();
                }
            }
            if (!target.isFile()) {
                throw new IOException("解包后文件不存在");
            }
            trimIfNeeded();
            return target;
        }
    }

    /** 列出包内成员（后台线程调用）；不支持 Range 时整包下到缓存再列。 */
    public List<TarReader.Member> list(Work work) throws Exception {
        try {
            TarReader.Source source = source(work);
            try {
                return TarReader.list(source);
            } finally {
                source.close();
            }
        } catch (TarReader.RangeUnsupportedException exc) {
            File tar = downloadFull(work.archive.rel);
            TarReader.Source local = new TarReader.FileSource(tar);
            try {
                return TarReader.list(local);
            } finally {
                local.close();
            }
        }
    }

    /** 从包里读 文案.txt 这类小文件（后台线程调用）；Range 不可用时退回整包。 */
    public String readText(Work work, TarReader.Member member, int maxBytes) throws Exception {
        try {
            TarReader.Source source = source(work);
            try {
                return TarReader.readText(source, member, maxBytes);
            } finally {
                source.close();
            }
        } catch (TarReader.RangeUnsupportedException exc) {
            File tar = downloadFull(work.archive.rel);
            TarReader.Source local = new TarReader.FileSource(tar);
            try {
                return TarReader.readText(local, member, maxBytes);
            } finally {
                local.close();
            }
        }
    }

    /** 读一个成员的前若干字节（例如只取 tar 里第一层目录名，给骨架补标题）。 */
    public String readMemberText(RemoteFile member, int maxBytes) throws Exception {
        TarReader.Member item = new TarReader.Member(
                member.name, member.name, member.size, member.offset);
        try {
            TarReader.Source source = sourceFor(member);
            try {
                return TarReader.readText(source, item, maxBytes);
            } finally {
                source.close();
            }
        } catch (TarReader.RangeUnsupportedException exc) {
            File tar = downloadFull(member.archiveRel);
            TarReader.Source local = new TarReader.FileSource(tar);
            try {
                return TarReader.readText(local, item, maxBytes);
            } finally {
                local.close();
            }
        }
    }

    public void clear(Work work) {
        if (work == null || work.archive == null) {
            return;
        }
        deleteRecursive(cacheDir(work.archive.rel));
        File full = fullTar(work.archive.rel);
        //noinspection ResultOfMethodCallIgnored
        full.delete();
    }

    public void clearAll() {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().equals("full")) {
                File[] tars = child.listFiles();
                if (tars != null) {
                    for (File tar : tars) {
                        //noinspection ResultOfMethodCallIgnored
                        tar.delete();
                    }
                }
                continue;
            }
            deleteRecursive(child);
        }
    }

    public long cacheBytes() {
        return sizeOf(root);
    }

    public long cacheLimit() {
        return CACHE_LIMIT;
    }

    // ---------- 数据源 ----------
    private TarReader.Source source(Work work) throws Exception {
        if (work == null || work.archive == null) {
            throw new IOException("不是作品包");
        }
        File local = app.offline.savedTar(work);
        if (local != null && local.isFile()) {
            return new TarReader.FileSource(local);
        }
        File full = fullTar(work.archive.rel);
        if (full.isFile() && (work.archive.size <= 0 || full.length() == work.archive.size)) {
            return new TarReader.FileSource(full);
        }
        DavClient dav = app.dav();
        if (dav == null) {
            throw new IOException("还没有配置 WebDAV");
        }
        return new DavTarSource(dav, work.archive.rel, work.archive.size);
    }

    private TarReader.Source sourceFor(RemoteFile member) throws Exception {
        Work work = app.store == null ? null : app.store.find(member.archiveRel);
        if (work != null && work.archive != null) {
            return source(work);
        }
        File local = app.offline.savedTarFor(member.archiveRel);
        if (local != null && local.isFile()) {
            return new TarReader.FileSource(local);
        }
        File full = fullTar(member.archiveRel);
        if (full.isFile()) {
            return new TarReader.FileSource(full);
        }
        DavClient dav = app.dav();
        if (dav == null) {
            throw new IOException("还没有配置 WebDAV");
        }
        return new DavTarSource(dav, member.archiveRel, 0L);
    }

    /** 整包下载（只在服务器不支持 Range 时用），返回本地 tar。 */
    private File downloadFull(String archiveRel) throws Exception {
        File target = fullTar(archiveRel);
        if (target.isFile() && target.length() > 0) {
            return target;
        }
        DavClient dav = app.dav();
        if (dav == null) {
            throw new IOException("还没有配置 WebDAV");
        }
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (DavClient.RangeStream stream = dav.openStream(archiveRel, 0);
             FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = stream.stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } catch (Exception exc) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw exc;
        }
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("作品包缓存写入失败");
        }
        return target;
    }

    // ---------- 缓存路径 ----------
    private File cacheDir(String archiveRel) {
        return new File(root, Util.sha1Hex(archiveRel).substring(0, 16));
    }

    private File cacheFile(RemoteFile member) {
        if (member == null || !member.inArchive()) {
            return null;
        }
        String name = TarReader.safeName(member.name);
        if (name.isEmpty()) {
            return null;
        }
        return new File(cacheDir(member.archiveRel), name);
    }

    private File fullTar(String archiveRel) {
        return new File(fullRoot, Util.sha1Hex(archiveRel).substring(0, 16) + ".tar");
    }

    private Object lockFor(String key) {
        synchronized (lockGuard) {
            Object lock = locks.get(key);
            if (lock == null) {
                lock = new Object();
                locks.put(key, lock);
            }
            return lock;
        }
    }

    // ---------- LRU ----------
    private void trimIfNeeded() {
        if (sizeOf(root) <= CACHE_LIMIT) {
            return;
        }
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return;
        }
        List<File> candidates = new ArrayList<>();
        long fullBytes = 0;
        for (File dir : dirs) {
            if (dir.getName().equals("full")) {
                File[] tars = dir.listFiles();
                if (tars != null) {
                    fullBytes += sizeOf(dir);
                }
                continue;
            }
            if (dir.isDirectory()) {
                candidates.add(dir);
            }
        }
        candidates.sort(Comparator.comparingLong(File::lastModified));
        long total = sizeOf(root);
        for (File dir : candidates) {
            if (total <= CACHE_LIMIT) {
                break;
            }
            long bytes = sizeOf(dir);
            deleteRecursive(dir);
            total -= bytes;
        }
        if (total > CACHE_LIMIT && fullBytes > 0) {
            // 整包缓存只在 Range 不可用时才有；实在超限就一起清
            File[] tars = fullRoot.listFiles();
            if (tars != null) {
                Arrays.sort(tars, Comparator.comparingLong(File::lastModified));
                for (File tar : tars) {
                    if (sizeOf(root) <= CACHE_LIMIT) {
                        break;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tar.delete();
                }
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
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

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }
}
