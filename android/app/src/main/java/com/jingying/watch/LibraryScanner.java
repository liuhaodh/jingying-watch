package com.jingying.watch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * 远程片库扫描：
 * 1. 在 WebDAV 上定位 Videos / Music 目录（最多下钻 3 层）；
 * 2. 只扫「作者目录 → 作品目录」两层，得到作品骨架，边扫边显示；
 * 3. 作品里的文件、文案、音乐按需读取，读完写进本地索引。
 */
public final class LibraryScanner {

    public interface Listener {
        void onPhase(String message);

        void onProgress(int authorsDone, int authorsTotal, int worksFound);

        void onBatch(List<Work> works);

        void onDone(int works, String error);
    }

    public interface DetailsCallback {
        void onDetails(Work work, boolean ok, String error);
    }

    private final App app;
    private final ExecutorService scanPool = Executors.newFixedThreadPool(5, daemon("watch-scan"));
    private volatile boolean running;
    private volatile boolean cancel;

    public LibraryScanner(App app) {
        this.app = app;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancel = true;
    }

    public void scan(Listener listener) {
        if (running) {
            return;
        }
        running = true;
        cancel = false;
        app.io.execute(() -> doScan(listener));
    }

    // ---------- 主流程 ----------
    private void doScan(Listener listener) {
        String failure = null;
        int count = 0;
        try {
            DavClient client = app.dav();
            if (client == null) {
                failure = "还没有配置 WebDAV";
                return;
            }
            listener.onPhase("正在定位 WebDAV 上的片库…");
            String videoRoot = app.store.videoRoot();
            String musicRoot = app.store.musicRoot();
            if (videoRoot == null || videoRoot.isEmpty() || app.store.size() == 0) {
                String[] roots = findRoots(client);
                videoRoot = roots[0];
                musicRoot = roots[1];
                app.store.setRoots(videoRoot, musicRoot);
            }
            applyAuthorRoot(videoRoot);

            listener.onPhase("正在读取作者目录…");
            List<DavEntry> authors;
            try {
                authors = list(client, videoRoot, false);
            } catch (DavClient.DavException exc) {
                if (exc.status == 404) {
                    String[] roots = findRoots(client);
                    videoRoot = roots[0];
                    musicRoot = roots[1];
                    app.store.setRoots(videoRoot, musicRoot);
                    applyAuthorRoot(videoRoot);
                    authors = list(client, videoRoot, false);
                } else {
                    throw exc;
                }
            }
            List<DavEntry> authorDirs = new ArrayList<>();
            for (DavEntry entry : authors) {
                if (entry.dir && !isIgnored(entry.name)) {
                    authorDirs.add(entry);
                }
            }
            if (authorDirs.isEmpty()) {
                // 旧根目录可能只剩一个空壳（比如用户把作者目录移到了 Videos 下），
                // 重新定位一次根目录，直接读新层级。
                try {
                    String[] roots = findRoots(client);
                    if (!roots[0].equals(videoRoot)) {
                        videoRoot = roots[0];
                        musicRoot = roots[1];
                        app.store.setRoots(videoRoot, musicRoot);
                        applyAuthorRoot(videoRoot);
                        authors = list(client, videoRoot, false);
                        authorDirs.clear();
                        for (DavEntry entry : authors) {
                            if (entry.dir && !isIgnored(entry.name)) {
                                authorDirs.add(entry);
                            }
                        }
                    }
                } catch (DavClient.DavException ignored) {
                }
            }
            authorDirs.sort((left, right) -> Long.compare(right.mtime, left.mtime));
            int total = authorDirs.size();
            listener.onPhase(total == 0 ? "片库里还没有作者目录" : "共 " + total + " 位作者，正在读取作品…");
            listener.onProgress(0, total, 0);
            if (total == 0) {
                failure = "WebDAV 上还没有作品。先用「净影」把作品上传到 Videos/" + app.prefs.remoteRoot() + "。";
                return;
            }

            CompletionService<AuthorResult> completion =
                    new ExecutorCompletionService<>(scanPool);
            for (DavEntry author : authorDirs) {
                completion.submit(() -> scanAuthor(client, author));
            }
            List<Work> all = new ArrayList<>();
            int done = 0;
            Set<String> rels = new HashSet<>();
            for (int index = 0; index < total; index++) {
                if (cancel) {
                    failure = "已停止扫描";
                    break;
                }
                Future<AuthorResult> future = completion.take();
                AuthorResult result = future.get();
                done++;
                if (result.works != null && !result.works.isEmpty()) {
                    all.addAll(result.works);
                    for (Work work : result.works) {
                        rels.add(work.rel);
                    }
                    listener.onBatch(result.works);
                }
                listener.onProgress(done, total, all.size());
                listener.onPhase("已读取 " + done + "/" + total + " 位作者，共 "
                        + all.size() + " 个作品" + (result.error == null ? "" : "（部分失败）"));
            }
            if (!cancel) {
                if (!all.isEmpty() || app.store.size() == 0) {
                    app.store.retain(rels);
                    app.store.addBatch(all);
                }
                long now = Util.nowSeconds();
                app.store.setScannedAt(now);
                app.prefs.setScannedAt(now);
                app.store.saveNow();
                count = all.size();
            }
        } catch (DavClient.DavException exc) {
            failure = exc.getMessage();
            android.util.Log.e("JYWatch", "scan dav error", exc);
        } catch (Exception exc) {
            failure = exc.getMessage() == null ? "扫描失败" : exc.getMessage();
            android.util.Log.e("JYWatch", "scan error", exc);
        } finally {
            running = false;
            final String message = failure;
            final int works = count;
            app.runOnMain(() -> listener.onDone(works, message));
        }
    }

    private static final class AuthorResult {
        final List<Work> works;
        final String error;

        AuthorResult(List<Work> works, String error) {
            this.works = works;
            this.error = error;
        }
    }

    private AuthorResult scanAuthor(DavClient client, DavEntry entry) {
        try {
            Long cachedMtime = app.store.authorMtime(entry.rel);
            if (cachedMtime != null && Math.abs(cachedMtime - entry.mtime) <= 1.5) {
                List<Work> cached = app.store.worksForAuthor(entry.rel);
                if (!cached.isEmpty()) {
                    return new AuthorResult(cached, null);
                }
            }
            String[] author = Work.splitAuthor(entry.name);
            List<DavEntry> entries = list(client, entry.rel, true);
            List<Work> works = new ArrayList<>();
            for (DavEntry child : entries) {
                if (!child.dir) {
                    continue;
                }
                works.add(Work.skeleton(child.rel, entry.rel, author[0], author[1],
                        child.name, child.mtime));
            }
            app.store.setAuthorMtime(entry.rel, entry.mtime);
            return new AuthorResult(works, null);
        } catch (Exception exc) {
            List<Work> cached = app.store.worksForAuthor(entry.rel);
            String message = exc.getMessage() == null ? "读取失败" : exc.getMessage();
            return new AuthorResult(cached, message);
        }
    }

    // ---------- 目录定位 ----------
    private String[] findRoots(DavClient client) throws DavClient.DavException {
        String seed = client.seed();
        Map<String, String> found = new HashMap<>();
        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[]{seed, "0"});
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty() && found.size() < 2) {
            String[] item = queue.poll();
            String rel = item[0];
            int depth = parseInt(item[1]);
            if (!seen.add(rel)) {
                continue;
            }
            List<DavEntry> entries = list(client, rel, false);
            for (DavEntry entry : entries) {
                if (!entry.dir) {
                    continue;
                }
                String low = entry.name.toLowerCase(Locale.US);
                if (("videos".equals(low) || "music".equals(low)) && !found.containsKey(low)) {
                    found.put(low, entry.rel);
                } else if (depth < 3 && (rel.equals(seed) || "净影".equals(entry.name)
                        || "videos".equals(low) || "music".equals(low))) {
                    queue.add(new String[]{entry.rel, String.valueOf(depth + 1)});
                }
            }
        }
        if (!found.containsKey("videos")) {
            throw new DavClient.DavException(
                    "没有在 WebDAV 上找到 Videos 目录。请确认已经用「净影」上传过作品，"
                            + "或把远程目录填成存放 Videos 的那一层。");
        }
        String videoRoot = descend(client, found.get("videos"));
        String musicRoot = found.containsKey("music")
                ? descend(client, found.get("music")) : "";
        return new String[]{videoRoot, musicRoot};
    }

    /**
     * 兼容两种目录层级：
     *   Videos/净影/<作者>/<作品>   （旧）
     *   Videos/<作者>/<作品>        （新）
     * 只有 Videos/Music 下唯一子目录叫「净影」、且里面确实还有作者目录时才下钻；
     * 直接是作者目录（或只剩一个空的「净影」壳）时返回当前层。
     */
    private String descend(DavClient client, String rel) {
        try {
            List<DavEntry> entries = list(client, rel, false);
            DavEntry onlyDir = null;
            int dirCount = 0;
            for (DavEntry entry : entries) {
                if (entry.dir && !isIgnored(entry.name)) {
                    dirCount++;
                    onlyDir = entry;
                }
            }
            if (dirCount == 1 && onlyDir != null && "净影".equals(onlyDir.name)) {
                List<DavEntry> inner = list(client, onlyDir.rel, false);
                for (DavEntry entry : inner) {
                    if (entry.dir && !isIgnored(entry.name)) {
                        return onlyDir.rel;
                    }
                }
            }
        } catch (DavClient.DavException ignored) {
        }
        return rel;
    }

    private void applyAuthorRoot(String videoRoot) {
        String derived = deriveAuthorRoot(videoRoot);
        if (derived.isEmpty()) {
            return;
        }
        if (!derived.equals(app.store.authorRoot())) {
            app.store.setAuthorRoot(derived);
            app.avatars.reset();
        }
        app.avatars.setRoot(derived);
    }

    /** 净影/Videos/净影 → 净影/作者资料；没有 净影 层时用 作者资料。 */
    private String deriveAuthorRoot(String videoRoot) {
        if (videoRoot == null || videoRoot.isEmpty()) {
            return "";
        }
        String low = videoRoot.toLowerCase(Locale.US);
        int index = low.indexOf("/videos");
        if (index == 0) {
            return "作者资料";
        }
        String base = index > 0 ? videoRoot.substring(0, index) : "";
        if (base.isEmpty()) {
            String remote = app.prefs.remoteRoot();
            base = remote == null ? "" : remote.trim();
        }
        while (base.startsWith("/")) {
            base = base.substring(1);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isEmpty() ? "作者资料" : base + "/作者资料";
    }

    private static boolean isIgnored(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        return low.equals(".ds_store") || low.equals("thumbs.db") || low.equals("desktop.ini")
                || low.equals("thumbs") || low.equals("thumbnails")
                || low.startsWith(".");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exc) {
            return 0;
        }
    }

    // ---------- 列表 ----------
    private List<DavEntry> list(DavClient client, String rel, boolean allowMissing)
            throws DavClient.DavException {
        DavClient.DavException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                List<DavEntry> entries = client.propfind(rel, 1);
                if (!entries.isEmpty() || attempt == 1) {
                    return entries;
                }
                Thread.sleep(350);
            } catch (DavClient.DavException exc) {
                last = exc;
                if (allowMissing && exc.status == 404) {
                    return new ArrayList<>();
                }
                try {
                    Thread.sleep(350);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last != null ? last : new DavClient.DavException("读取目录失败：" + rel);
    }

    // ---------- 详情 / 文案 / 音乐 ----------
    public void loadDetails(Work work, DetailsCallback callback) {
        if (work == null) {
            return;
        }
        if (work.detailsLoaded) {
            app.runOnMain(() -> callback.onDetails(work, !work.empty, null));
            return;
        }
        app.details.execute(() -> {
            boolean ok = false;
            String error = null;
            try {
                DavClient client = app.dav();
                if (client == null) {
                    throw new DavClient.DavException("还没有配置 WebDAV");
                }
                List<DavEntry> entries = list(client, work.rel, true);
                List<RemoteFile> files = new ArrayList<>();
                for (DavEntry entry : entries) {
                    if (!entry.dir) {
                        files.add(new RemoteFile(entry.rel, entry.name, entry.size));
                    }
                }
                Work detail = Work.fromFiles(work.rel, work.authorRel, work.author,
                        work.uniqueId, work.folder, work.mtime, files,
                        work.musicChecked, work.music);
                if (detail == null) {
                    work.empty = true;
                    work.detailsLoaded = true;
                    work.kind = "unknown";
                    error = "这个目录里没有视频或图片";
                } else {
                    work.mergeDetails(detail);
                    ok = true;
                }
                if (ok && work.caption != null && work.captionText.isEmpty()) {
                    loadCaptionSync(work);
                }
                if (ok && work.isAlbum() && !work.musicChecked) {
                    loadMusicSync(client, work);
                }
                app.store.update(work);
            } catch (Exception exc) {
                error = exc.getMessage() == null ? "读取作品失败" : exc.getMessage();
            }
            final boolean success = ok;
            final String message = error;
            app.runOnMain(() -> callback.onDetails(work, success, message));
        });
    }

    private void loadCaptionSync(Work work) {
        try {
            byte[] bytes = app.proxy.readAll(work.caption.rel, work.caption.name,
                    work.caption.size, 20000);
            Work.parseCaptionText(work, new String(bytes, "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    public void loadCaption(Work work, Runnable done) {
        if (work == null || work.caption == null || !work.captionText.isEmpty()) {
            app.runOnMain(done);
            return;
        }
        app.details.execute(() -> {
            loadCaptionSync(work);
            app.store.update(work);
            app.runOnMain(done);
        });
    }

    public void loadMusic(Work work, Runnable done) {
        if (work == null) {
            app.runOnMain(done);
            return;
        }
        if (work.music != null || work.musicChecked) {
            app.runOnMain(done);
            return;
        }
        app.details.execute(() -> {
            DavClient client = app.dav();
            if (client != null) {
                loadMusicSync(client, work);
                app.store.update(work);
            }
            app.runOnMain(done);
        });
    }

    public void loadMusicSync(DavClient client, Work work) {
        String musicRel = musicRelFor(work);
        if (musicRel == null || musicRel.isEmpty()) {
            work.musicChecked = true;
            return;
        }
        try {
            List<DavEntry> entries = list(client, musicRel, true);
            List<RemoteFile> audios = new ArrayList<>();
            for (DavEntry entry : entries) {
                if (!entry.dir && Work.AUDIO_EXTS.contains(entry.ext())) {
                    audios.add(new RemoteFile(entry.rel, entry.name, entry.size));
                }
            }
            audios.sort((left, right) -> {
                boolean leftMain = left.name.startsWith("音乐");
                boolean rightMain = right.name.startsWith("音乐");
                if (leftMain != rightMain) {
                    return leftMain ? -1 : 1;
                }
                return left.name.compareTo(right.name);
            });
            work.music = audios.isEmpty() ? null : audios.get(0);
        } catch (Exception ignored) {
            work.music = null;
        }
        work.musicChecked = true;
    }

    /**
     * 从回收站恢复作者后，只重扫这一位作者。
     * 比整库扫描快得多（一次 PROPFIND），恢复的作品能立刻回到片库。
     */
    public void refreshAuthor(String authorRel) {
        final String rel = authorRel == null ? "" : authorRel.trim();
        if (rel.isEmpty()) {
            return;
        }
        app.io.execute(() -> {
            DavClient client = app.dav();
            if (client == null) {
                return;
            }
            try {
                DavEntry entry = new DavEntry(rel, RemoteFile.baseName(rel), true, 0L, 0L);
                AuthorResult result = scanAuthor(client, entry);
                if (result != null && result.works != null && !result.works.isEmpty()) {
                    app.store.addBatch(result.works);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public String musicRelFor(Work work) {
        String videoRoot = app.store.videoRoot();
        String musicRoot = app.store.musicRoot();
        if (videoRoot == null || videoRoot.isEmpty()
                || musicRoot == null || musicRoot.isEmpty()) {
            return "";
        }
        String prefix = videoRoot.endsWith("/") ? videoRoot : videoRoot + "/";
        if (!work.rel.startsWith(prefix)) {
            return "";
        }
        return musicRoot + "/" + work.rel.substring(prefix.length());
    }
}
