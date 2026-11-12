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
                if (entry.dir && !isIgnored(entry.name) && !isSystemDir(entry.name)) {
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
                            if (entry.dir && !isIgnored(entry.name) && !isSystemDir(entry.name)) {
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
            boolean allOk = true;
            Set<String> rels = new HashSet<>();
            for (int index = 0; index < total; index++) {
                if (cancel) {
                    failure = "已停止扫描";
                    break;
                }
                Future<AuthorResult> future = completion.take();
                AuthorResult result = future.get();
                done++;
                if (result.error != null) {
                    allOk = false;
                }
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
                // 只有「每个作者都读成功」的一轮扫描才敢按 rels 裁剪本地索引：
                // 某个作者这次返回空（服务端同步中/限流/网络抖动）时，裁掉就等于把
                // 这位作者的作品从本地片库永久删掉。
                if (allOk && done == total && (!all.isEmpty() || app.store.size() == 0)) {
                    app.store.retain(rels);
                }
                app.store.addBatch(all);
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
                if (child.dir) {
                    works.add(Work.skeleton(child.rel, entry.rel, author[0], author[1],
                            child.name, child.mtime));
                } else if (TarReader.looksLikeTar(child.name)) {
                    // 2026-11-08 起电脑版一个作品一个 `<作品id>.tar`
                    works.add(Work.archiveSkeleton(child.rel, entry.rel, author[0],
                            author[1], child.name, child.size, child.mtime));
                }
            }
            if (works.isEmpty()) {
                // 这个作者以前有作品、这次却读成空目录：多半是服务端同步/限流中，
                // 不能当成「作品全被删了」——返回缓存并标记失败，本轮就不做裁剪。
                List<Work> cached = app.store.worksForAuthor(entry.rel);
                if (!cached.isEmpty()) {
                    return new AuthorResult(cached, "读取结果为空，沿用本地缓存");
                }
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
        // 旧版电脑端可能把作品上传到「净影/净影/<作者>」，Videos 只有探测文件。
        // 这种情况下回退到基目录下的「净影」，避免扫出 0 个作者。
        if (countDirs(client, videoRoot) == 0) {
            // 新版电脑版把作者目录直接拍平到「净影/<作者>」，Videos 只剩探测文件。
            if (!seed.isEmpty() && countAuthors(client, seed) > 0) {
                videoRoot = seed;
            } else {
                String legacy = joinRel(seed, "净影");
                if (countAuthors(client, legacy) > 0) {
                    videoRoot = legacy;
                } else {
                    String legacyVideos = joinRel(legacy, "Videos");
                    if (countAuthors(client, legacyVideos) > 0) {
                        videoRoot = legacyVideos;
                    }
                }
            }
        }
        String musicRoot = found.containsKey("music")
                ? descend(client, found.get("music")) : "";
        return new String[]{videoRoot, musicRoot};
    }

    private int countDirs(DavClient client, String rel) {
        try {
            List<DavEntry> entries = list(client, rel, false);
            int count = 0;
            for (DavEntry entry : entries) {
                if (entry.dir && !isIgnored(entry.name)) {
                    count++;
                }
            }
            return count;
        } catch (Exception exc) {
            return 0;
        }
    }

    /** 数作者目录：排掉 Videos/Music/作者资料/回收站 这些系统目录。 */
    private int countAuthors(DavClient client, String rel) {
        try {
            List<DavEntry> entries = list(client, rel, false);
            int count = 0;
            for (DavEntry entry : entries) {
                if (entry.dir && !isIgnored(entry.name) && !isSystemDir(entry.name)) {
                    count++;
                }
            }
            return count;
        } catch (Exception exc) {
            return 0;
        }
    }

    private static boolean isSystemDir(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        return "videos".equals(low) || "music".equals(low)
                || "作者资料".equals(name) || "回收站".equals(name) || "净影".equals(name);
    }

    private static String joinRel(String head, String tail) {
        if (head == null || head.isEmpty()) {
            return tail == null ? "" : tail;
        }
        if (tail == null || tail.isEmpty()) {
            return head;
        }
        return head + "/" + tail;
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
    /** 同一个作品正在读详情时，后来的请求挂在这里等同一份结果，避免重复 PROPFIND。 */
    private final Map<String, List<DetailsCallback>> detailWaiters = new HashMap<>();
    /**
     * 旧版本把「音乐目录查询失败」也写成了 musicChecked=true 并落盘，
     * 升级后这些作品会永远跳过音乐。每个进程给它们一次复查机会（查完就记下，不反复打网络）。
     */
    private final Set<String> musicRechecked = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public void loadDetails(Work work, DetailsCallback callback) {
        if (work == null) {
            return;
        }
        if (work.detailsLoaded) {
            app.runOnMain(() -> callback.onDetails(work, !work.empty, null));
            return;
        }
        synchronized (detailWaiters) {
            List<DetailsCallback> pending = detailWaiters.get(work.rel);
            if (pending != null) {
                pending.add(callback);
                return;
            }
            pending = new ArrayList<>(2);
            pending.add(callback);
            detailWaiters.put(work.rel, pending);
        }
        try {
            app.details.execute(() -> {
                boolean ok = false;
                String error = null;
                try {
                    DavClient client = app.dav();
                    if (client == null) {
                        throw new DavClient.DavException("还没有配置 WebDAV");
                    }
                    if (work.archive != null) {
                        loadArchiveDetails(work);
                        ok = true;
                    } else {
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
                    }
                } catch (Exception exc) {
                    error = exc.getMessage() == null ? "读取作品失败" : exc.getMessage();
                }
                List<DetailsCallback> done;
                synchronized (detailWaiters) {
                    done = detailWaiters.remove(work.rel);
                }
                final boolean success = ok;
                final String message = error;
                final List<DetailsCallback> targets = done == null
                        ? java.util.Collections.singletonList(callback) : done;
                app.runOnMain(() -> {
                    for (DetailsCallback target : targets) {
                        try {
                            target.onDetails(work, success, message);
                        } catch (Exception ignored) {
                        }
                    }
                });
            });
        } catch (Exception exc) {
            // 线程池满了/已关闭：别把等待者永远挂着
            List<DetailsCallback> done;
            synchronized (detailWaiters) {
                done = detailWaiters.remove(work.rel);
            }
            final List<DetailsCallback> targets = done == null
                    ? java.util.Collections.singletonList(callback) : done;
            app.runOnMain(() -> {
                for (DetailsCallback target : targets) {
                    try {
                        target.onDetails(work, false, "读取作品失败");
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    /**
     * 读作品包详情：只按 tar 头列成员（Range 读 512 字节头，不下载视频本体），
     * 再读包里的小 `文案.txt` 补作者/时间/完整文案。
     */
    private void loadArchiveDetails(Work work) throws Exception {
        if (work.archive == null) {
            throw new DavClient.DavException("不是作品包");
        }
        if (work.archive.size <= 0) {
            // 恢复/重建出来的骨架可能不知道包大小；PROPFIND 的 size 或 HEAD 都能补上
            try {
                DavClient client = app.dav();
                long size = client == null ? 0L : client.sizeOf(work.archive.rel);
                if (size > 0) {
                    work.archive = new RemoteFile(work.archive.rel, work.archive.name, size);
                }
            } catch (Exception ignored) {
            }
        }
        List<TarReader.Member> members = app.archives.list(work);
        Work detail = Work.fromArchive(work, members);
        if (detail == null) {
            work.empty = true;
            work.detailsLoaded = true;
            work.kind = "unknown";
            app.store.update(work);
            throw new DavClient.DavException("作品包里没有视频或图片");
        }
        work.mergeArchiveDetails(detail);
        TarReader.Member captionMember = null;
        for (TarReader.Member member : members) {
            if ("文案.txt".equals(member.name)) {
                captionMember = member;
                break;
            }
        }
        if (captionMember != null && work.captionText.isEmpty()) {
            try {
                String text = app.archives.readText(work, captionMember, 20000);
                if (text != null && !text.isEmpty()) {
                    Work.parseCaptionText(work, text);
                }
            } catch (Exception ignored) {
                // 文案读不到不影响播放
            }
        }
        work.musicChecked = true;
        work.detailsLoaded = true;
        app.store.update(work);
    }

    private void loadCaptionSync(Work work) {
        if (work.archive != null) {
            try {
                if (work.caption == null) {
                    return;
                }
                String text = app.archives.readMemberText(work.caption, 20000);
                if (text != null && !text.isEmpty()) {
                    Work.parseCaptionText(work, text);
                }
            } catch (Exception ignored) {
            }
            return;
        }
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
        if (work.archive != null) {
            // 包里的成员清单已经带上了音乐；没有就是真没有，不用再去 Music/ 目录找
            app.runOnMain(done);
            return;
        }
        if (work.music != null || (work.musicChecked && musicRechecked.contains(work.rel))) {
            app.runOnMain(done);
            return;
        }
        // 没音乐又「查过了」：可能只是旧版本留下的假标记，只复查这一次
        if (musicRechecked.size() > 20_000) {
            musicRechecked.clear();
        }
        musicRechecked.add(work.rel);
        // 用独立的 music 线程：否则会排在 prefetchAhead 的 10 个详情任务后面
        app.music.execute(() -> {
            DavClient client = app.dav();
            if (client != null) {
                if (!loadMusicSync(client, work)) {
                    // 刚才那次是网络/服务端失败，不是「确认没有音乐」：隔一下再试一次，
                    // 随机观看时 10 个作品并发读详情，PROPFIND 偶发失败很常见
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    loadMusicSync(client, work);
                }
                app.store.update(work);
            }
            app.runOnMain(done);
        });
    }

    /** @return 是否真的读到了 Music 目录（失败不算，别当成「确认没有音乐」）。 */
    public boolean loadMusicSync(DavClient client, Work work) {
        String musicRel = musicRelFor(work);
        if (musicRel == null || musicRel.isEmpty()) {
            work.musicChecked = true;
            return true;
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
            // 只有真的读到目录（哪怕里面没有音频）才算「确认过」。
            work.musicChecked = true;
            return true;
        } catch (Exception exc) {
            // 网络抖动 / 服务端限流时不给 musicChecked 置位：以前一次失败就写成
            // 「这个作品没有音乐」并落盘，之后连重试都不会有，随机刷到就一直没声音。
            android.util.Log.w("JYWatch", "music lookup failed " + musicRel, exc);
            work.music = null;
            return false;
        }
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
        if (work != null && work.archive != null) {
            // 音乐在作品包里，不再单独去 Music/ 找
            return "";
        }
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
