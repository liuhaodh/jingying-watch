package com.jingying.watch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把当前作品的所有文件打成一个 tar，放到只供分享的 cache 目录。
 *
 * <p>三种来源按「本地优先」分流：已离线下载的文件直接读本地、作品包（tar）成员走
 * ArchiveStore 的解包缓存（服务器不支持 Range 时它会自动整包下载）、其余从 WebDAV
 * 流式读取。全程不把大视频读进内存，单文件缓冲 256KB。
 */
public final class WorkShare {

    public interface Progress {
        void onProgress(int percent, String stage);

        boolean isCancelled();
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("打包已取消");
        }
    }

    private static final long PRUNE_AFTER_MS = 24L * 60 * 60 * 1000;

    private WorkShare() {
    }

    /** 在后台线程调用；返回可直接交给 ShareProvider 的 tar 文件。 */
    public static File build(App app, Work work, Progress progress) throws Exception {
        List<RemoteFile> files = work.workFiles();
        if (files.isEmpty()) {
            throw new IOException("这个作品没有可分享的文件");
        }
        File root = ShareProvider.dir(app);
        pruneOld(root);
        File target = new File(root, fileName(work));
        File part = new File(root, target.getName() + ".part");

        long sum = 0;
        for (RemoteFile file : files) {
            sum += Math.max(0L, file.size);
        }
        final long total = sum;
        Map<String, Integer> usedNames = new HashMap<>();
        long done = 0;
        try (FileOutputStream output = new FileOutputStream(part);
             TarWriter tar = new TarWriter(output)) {
            for (int index = 0; index < files.size(); index++) {
                if (progress != null && progress.isCancelled()) {
                    throw new CancelledException();
                }
                RemoteFile file = files.get(index);
                String display = TarReader.safeName(file.name);
                if (display.isEmpty()) {
                    throw new IOException("文件名不合法：" + file.name);
                }
                String stage = "正在打包 " + (index + 1) + "/" + files.size() + "：" + display;
                if (progress != null) {
                    progress.onProgress(percent(done, total), stage);
                }
                Source source = open(app, work, file);
                final long base = done;
                final String currentStage = stage;
                try (InputStream input = source.stream) {
                    tar.writeFile(uniqueName(usedNames, entryName(work, display)),
                            source.size, input, written -> {
                                if (progress == null) {
                                    return true;
                                }
                                if (progress.isCancelled()) {
                                    return false;
                                }
                                progress.onProgress(percent(base + written, total), currentStage);
                                return true;
                            });
                }
                done += source.size;
            }
            tar.finish();
        } catch (Exception exc) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw exc;
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("打包完成，但文件改名失败");
        }
        return target;
    }

    /** 稳定文件名：同一作品反复分享会覆盖旧的，不会在 cache 里无限堆包。 */
    public static String fileName(Work work) {
        String hash = Util.sha1Hex(work.rel == null ? "" : work.rel);
        return safeTitle(work.displayTitle()) + "-"
                + hash.substring(0, Math.min(8, hash.length())) + ".tar";
    }

    // ---------- 每个文件的数据来源 ----------
    private static final class Source {
        final InputStream stream;
        final long size;

        Source(InputStream stream, long size) {
            this.stream = stream;
            this.size = size;
        }
    }

    private static Source open(App app, Work work, RemoteFile file) throws Exception {
        String base = TarReader.safeName(file.name);

        if (file.inArchive()) {
            // 成员的 rel 指向整个作品包，不能直接 GET；优先用已解好的缓存，
            // 否则让 ArchiveStore 按成员偏移解包（内含 Range 不可用时的整包兜底）。
            File local = app.archives.cached(file);
            if (local == null) {
                local = app.archives.fileSync(file);
            }
            return new Source(new FileInputStream(local), local.length());
        }

        // 散文件：已离线下载过就直接读本地，别把几十 MB 视频重新拉一遍
        File offline = new File(app.offline.dirFor(work), base);
        if (offline.isFile() && (file.size <= 0 || offline.length() == file.size)) {
            return new Source(new FileInputStream(offline), offline.length());
        }

        DavClient dav = app.dav();
        if (dav == null) {
            throw new IOException("还没配置 WebDAV，无法读取云端文件：" + base);
        }
        DavClient.RangeStream remote = dav.openStream(file.rel, 0);
        long size = remote.total > 0 ? remote.total : Math.max(0L, file.size);
        InputStream stream = new FilterInputStream(remote.stream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    remote.close();
                }
            }
        };
        return new Source(stream, size);
    }

    // ---------- 命名 / 清理 ----------
    private static String entryName(Work work, String base) {
        return safeTitle(work.displayTitle()) + "/" + base;
    }

    /** tar 里同名成员会互相覆盖，这里给重复的名字加序号。 */
    private static String uniqueName(Map<String, Integer> used, String path) {
        Integer count = used.get(path);
        if (count == null) {
            used.put(path, 1);
            return path;
        }
        used.put(path, count + 1);
        int dot = path.lastIndexOf('.');
        String suffix = "-" + count;
        return dot > 0 ? path.substring(0, dot) + suffix + path.substring(dot)
                : path + suffix;
    }

    private static String safeTitle(String title) {
        String value = title == null ? "" : title.trim();
        value = value.replaceAll("[/\\\\:*?\"<>|\\r\\n\\t]", "_");
        value = value.replaceAll("[\\x00-\\x1f]", "_");
        if (value.length() > 48) {
            value = value.substring(0, 48).trim();
        }
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            value = "作品";
        }
        return value;
    }

    private static int percent(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(99L, done * 100L / total));
    }

    /** 清掉一天前的旧包；刚生成的不能被删，接收方可能还在异步读。 */
    private static void pruneOld(File root) {
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}
