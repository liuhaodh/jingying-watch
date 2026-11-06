package com.jingying.watch;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 云端回收站。
 *
 * 删除作品/作者时不再直接删掉，而是把目录 MOVE 到
 * `<片库根>/回收站/<原名>__<时间>/data`，对应的音乐目录放到 `music`，
 * 再写一个 `meta.txt` 记录原始路径，恢复时按原路径 MOVE 回去。
 *
 * 这样即使换手机、清 App 数据，回收站里的东西仍然认得回哪儿去。
 */
public final class TrashStore {

    public static final class Item {
        public String trashRel = "";
        public String type = "unknown";
        public String name = "";
        public long deletedAt;
        public final List<String> rels = new ArrayList<>();
        public final List<String> musicRels = new ArrayList<>();

        public boolean isAuthor() {
            return "author".equals(type);
        }

        public boolean isWork() {
            return "work".equals(type);
        }

        public String originalRel() {
            return rels.isEmpty() ? "" : rels.get(0);
        }

        public String typeLabel() {
            if (isAuthor()) {
                return "作者";
            }
            return isWork() ? "作品" : "未知";
        }

        /** 回收站里显示的名字：作品去掉时间戳，作者只留昵称。 */
        public String displayName() {
            if (isAuthor()) {
                String nickname = Work.splitAuthor(name)[0];
                return nickname.isEmpty() ? name : nickname;
            }
            if (isWork()) {
                String title = Work.parseWorkFolder(name, "")[0];
                return title.isEmpty() ? name : title;
            }
            return name;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        /** 服务器不支持 MOVE（405/501），回收站用不了，只能彻底删除。 */
        public final boolean moveUnsupported;

        Result(boolean ok, String error, boolean moveUnsupported) {
            this.ok = ok;
            this.error = error;
            this.moveUnsupported = moveUnsupported;
        }
    }

    public interface Callback {
        void onDone(Result result);
    }

    public interface ListCallback {
        void onDone(List<Item> items, Result result);
    }

    /** 预览用：把回收站条目还原成一个临时 Work。 */
    public interface WorkCallback {
        void onDone(Work work, Result result);
    }

    private static final int META_LIMIT = 16 * 1024;

    private final App app;
    private final ThreadLocal<SimpleDateFormat> stampFormat =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                }
            };

    public TrashStore(App app) {
        this.app = app;
    }

    // ---------- 路径 ----------
    /** 回收站目录：`净影/回收站`（片库根没有「净影」这一层时就是 `回收站`）。 */
    public String root() {
        String videoRoot = app.store.videoRoot();
        String base = "";
        if (videoRoot != null && !videoRoot.isEmpty()) {
            String low = videoRoot.toLowerCase(Locale.US);
            int index = low.lastIndexOf("/videos");
            if (index > 0) {
                base = videoRoot.substring(0, index);
            }
        }
        return base.isEmpty() ? "回收站" : base + "/回收站";
    }

    private static String parentOf(String rel) {
        int index = rel == null ? -1 : rel.lastIndexOf('/');
        return index <= 0 ? "" : rel.substring(0, index);
    }

    private static String slot(String prefix, int number) {
        return number == 1 ? prefix : prefix + number;
    }

    /**
     * 只允许操作片库下面的子项：空路径、片库根目录、没有层级关系的裸名字一律拒绝。
     */
    private boolean safe(String rel) {
        String value = rel == null ? "" : rel.trim();
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/")) {
            return false;
        }
        if (!value.contains("/")) {
            return false;
        }
        if (app.store != null) {
            if (value.equals(app.store.videoRoot()) || value.equals(app.store.musicRoot())) {
                return false;
            }
        }
        return true;
    }

    private DavClient requireClient() throws DavClient.DavException {
        DavClient dav = app.dav();
        if (dav == null) {
            throw new DavClient.DavException("还没有配置 WebDAV");
        }
        return dav;
    }

    private static String sanitize(String name) {
        String value = name == null ? "" : name.trim();
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length() && builder.length() < 40; index++) {
            char letter = value.charAt(index);
            if (letter < 32 || "/\\:*?\"<>|".indexOf(letter) >= 0) {
                builder.append('_');
            } else {
                builder.append(letter);
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? "未命名" : result;
    }

    private String uniqueItemRel(DavClient dav, String trashRoot, String name)
            throws DavClient.DavException {
        String base = sanitize(name);
        String stamp = stampFormat.get().format(new Date());
        String candidate = trashRoot + "/" + base + "__" + stamp;
        int attempt = 1;
        while (dav.exists(candidate) && attempt < 50) {
            attempt++;
            candidate = trashRoot + "/" + base + "__" + stamp + "_" + attempt;
        }
        return candidate;
    }

    // ---------- 移入回收站 ----------
    public Item moveWork(Work work) throws DavClient.DavException {
        DavClient dav = requireClient();
        String rel = work == null ? "" : work.rel;
        if (!safe(rel)) {
            throw new DavClient.DavException("路径不安全，已取消删除");
        }
        String trashRoot = root();
        dav.mkcolPath(trashRoot);
        String itemRel = uniqueItemRel(dav, trashRoot, RemoteFile.baseName(rel));
        dav.mkcol(itemRel);

        Item item = new Item();
        item.trashRel = itemRel;
        item.type = "work";
        item.name = RemoteFile.baseName(rel);
        item.deletedAt = Util.nowSeconds();
        item.rels.add(rel);
        String music = app.scanner.musicRelFor(work);
        boolean hasMusic = music != null && !music.isEmpty() && !music.equals(rel)
                && dav.exists(music);
        item.musicRels.add(hasMusic ? music : "");

        try {
            writeMeta(dav, item);
            dav.move(rel, itemRel + "/data", false);
        } catch (DavClient.DavException exc) {
            try {
                dav.deleteTree(itemRel);
            } catch (Exception ignored) {
            }
            throw exc;
        }
        if (hasMusic) {
            try {
                dav.move(music, itemRel + "/music", false);
            } catch (DavClient.DavException exc) {
                // 音乐没搬过去不影响作品本身，把 meta 里的记录去掉
                item.musicRels.set(0, "");
                try {
                    writeMeta(dav, item);
                } catch (Exception ignored) {
                }
            }
        }
        forgetLocal(work);
        return item;
    }

    public Item moveAuthor(List<String> authorRels) throws DavClient.DavException {
        DavClient dav = requireClient();
        final List<String> targets = new ArrayList<>();
        if (authorRels != null) {
            for (String rel : authorRels) {
                if (rel != null && !rel.trim().isEmpty() && safe(rel) && !targets.contains(rel)) {
                    targets.add(rel);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new DavClient.DavException("作者目录不存在");
        }
        String trashRoot = root();
        dav.mkcolPath(trashRoot);
        String first = targets.get(0);
        String itemRel = uniqueItemRel(dav, trashRoot, RemoteFile.baseName(first));
        dav.mkcol(itemRel);

        Item item = new Item();
        item.trashRel = itemRel;
        item.type = "author";
        item.name = RemoteFile.baseName(first);
        item.deletedAt = Util.nowSeconds();
        for (String rel : targets) {
            item.rels.add(rel);
            String music = musicDirFor(rel);
            item.musicRels.add(music.isEmpty() || !dav.exists(music) ? "" : music);
        }

        try {
            writeMeta(dav, item);
            for (int index = 0; index < targets.size(); index++) {
                dav.move(targets.get(index),
                        itemRel + "/" + slot("data", index + 1), false);
            }
        } catch (DavClient.DavException exc) {
            // 已经搬进去的部分要搬回原位，不能把人家的目录留在回收站里
            for (int index = 0; index < targets.size(); index++) {
                try {
                    String moved = itemRel + "/" + slot("data", index + 1);
                    if (dav.exists(moved)) {
                        dav.move(moved, targets.get(index), false);
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                dav.deleteTree(itemRel);
            } catch (Exception ignored) {
            }
            throw exc;
        }

        boolean changed = false;
        for (int index = 0; index < targets.size(); index++) {
            String music = index < item.musicRels.size() ? item.musicRels.get(index) : "";
            if (music.isEmpty()) {
                continue;
            }
            try {
                dav.move(music, itemRel + "/" + slot("music", index + 1), false);
            } catch (DavClient.DavException exc) {
                item.musicRels.set(index, "");
                changed = true;
            }
        }
        if (changed) {
            try {
                writeMeta(dav, item);
            } catch (Exception ignored) {
            }
        }
        forgetLocalAuthor(targets);
        return item;
    }

    // ---------- 回收站内容 ----------
    public List<Item> list() throws DavClient.DavException {
        DavClient dav = requireClient();
        String trashRoot = root();
        List<Item> items = new ArrayList<>();
        if (!dav.exists(trashRoot)) {
            return items;
        }
        List<DavEntry> entries = dav.propfind(trashRoot, 1);
        for (DavEntry entry : entries) {
            if (!entry.dir) {
                continue;
            }
            Item item = null;
            try {
                byte[] bytes = dav.readAll(entry.rel + "/meta.txt", META_LIMIT);
                item = parseMeta(new String(bytes, Charset.forName("UTF-8")),
                        entry.rel, entry.name, entry.mtime);
            } catch (Exception ignored) {
            }
            if (item == null) {
                item = new Item();
                item.trashRel = entry.rel;
                item.type = "unknown";
                item.name = entry.name;
                item.deletedAt = entry.mtime;
            }
            items.add(item);
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item left, Item right) {
                return Long.compare(right.deletedAt, left.deletedAt);
            }
        });
        return items;
    }

    /**
     * 把回收站条目拼成一个临时 Work 用来预览。
     *
     * 注意：这个 Work **不会写进片库索引**，`rel` 指向 `回收站/<条目>/data`，
     * 播放/看图都走本地代理，所以能直接看，但下载/收藏/删除都要先恢复。
     */
    public Work loadWork(Item item) throws DavClient.DavException {
        DavClient dav = requireClient();
        if (item == null || item.trashRel.isEmpty() || item.rels.isEmpty()) {
            throw new DavClient.DavException("这条记录缺少原始路径，无法预览");
        }
        String dataRel = item.trashRel + "/data";
        String workName = item.name;
        String authorRel = parentOf(item.originalRel());
        List<RemoteFile> files = new ArrayList<>();
        List<DavEntry> entries = dav.propfind(dataRel, 1);
        for (DavEntry entry : entries) {
            if (!entry.dir) {
                files.add(new RemoteFile(entry.rel, entry.name, entry.size));
            }
        }
        if (files.isEmpty()) {
            // 删的是整个作者：data 下面是一堆作品目录，取第一个来预览
            for (DavEntry entry : entries) {
                if (!entry.dir) {
                    continue;
                }
                dataRel = entry.rel;
                workName = entry.name;
                authorRel = item.originalRel();
                files.clear();
                for (DavEntry child : dav.propfind(dataRel, 1)) {
                    if (!child.dir) {
                        files.add(new RemoteFile(child.rel, child.name, child.size));
                    }
                }
                break;
            }
        }
        if (files.isEmpty()) {
            throw new DavClient.DavException("回收站里找不到可播放的文件");
        }
        RemoteFile music = null;
        try {
            for (DavEntry entry : dav.propfind(item.trashRel + "/music", 1)) {
                if (!entry.dir && Mime.of(entry.name).startsWith("audio/")) {
                    music = new RemoteFile(entry.rel, entry.name, entry.size);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        String[] author = Work.splitAuthor(RemoteFile.baseName(authorRel));
        Work work = Work.fromFiles(dataRel, authorRel, author[0], author[1],
                workName, 0L, files, true, music);
        if (work == null) {
            throw new DavClient.DavException("这个作品没有可播放的内容");
        }
        if (work.caption != null) {
            try {
                byte[] bytes = dav.readAll(work.caption.rel, 40000);
                Work.parseCaptionText(work, new String(bytes, Charset.forName("UTF-8")));
            } catch (Exception ignored) {
            }
        }
        // 详情已经拼好了，别再让播放页去 PROPFIND（那会把回收站的路径写进片库索引）
        work.detailsLoaded = true;
        return work;
    }

    public void loadWorkAsync(Item item, WorkCallback callback) {
        app.io.execute(() -> {
            Work work = null;
            String error = null;
            try {
                work = loadWork(item);
            } catch (Exception exc) {
                error = describe(exc);
            }
            final Work result = work;
            final String message = error;
            app.runOnMain(() -> {
                if (callback != null) {
                    callback.onDone(result,
                            new Result(message == null && result != null, message, false));
                }
            });
        });
    }

    /** 恢复到原路径；目标已存在同名目录时拒绝，避免覆盖。 */
    public void restore(Item item) throws DavClient.DavException {
        DavClient dav = requireClient();
        if (item == null || item.trashRel == null || item.trashRel.isEmpty()) {
            throw new DavClient.DavException("回收站记录不完整");
        }
        if (item.rels.isEmpty()) {
            throw new DavClient.DavException("这条记录缺少原始路径，只能彻底删除");
        }
        for (String rel : item.rels) {
            if (dav.exists(rel)) {
                throw new DavClient.DavException("原位置已有同名目录：" + rel);
            }
        }
        for (int index = 0; index < item.rels.size(); index++) {
            int number = index + 1;
            String rel = item.rels.get(index);
            String dataSlot = item.trashRel + "/" + slot("data", number);
            if (!dav.exists(dataSlot)) {
                continue;
            }
            dav.mkcolPath(parentOf(rel));
            dav.move(dataSlot, rel, false);
            String music = index < item.musicRels.size() ? item.musicRels.get(index) : "";
            if (music != null && !music.isEmpty()) {
                String musicSlot = item.trashRel + "/" + slot("music", number);
                if (dav.exists(musicSlot) && !dav.exists(music)) {
                    dav.mkcolPath(parentOf(music));
                    dav.move(musicSlot, music, false);
                }
            }
        }
        try {
            dav.deleteTree(item.trashRel);
        } catch (Exception ignored) {
        }
        // 恢复后这些缓存路径已经不存在了
        app.proxy.forget(item.trashRel);
    }

    public void deleteItem(Item item) throws DavClient.DavException {
        if (item == null || item.trashRel == null || item.trashRel.isEmpty()) {
            throw new DavClient.DavException("回收站记录不完整");
        }
        requireClient().deleteTree(item.trashRel);
        // 预览过的播放缓存一起清掉
        app.proxy.forget(item.trashRel);
    }

    /** 清空回收站，返回删掉的条目数。 */
    public int clearAll() throws DavClient.DavException {
        DavClient dav = requireClient();
        String trashRoot = root();
        if (!dav.exists(trashRoot)) {
            return 0;
        }
        List<DavEntry> entries = dav.propfind(trashRoot, 1);
        int count = 0;
        for (DavEntry entry : entries) {
            dav.deleteTree(entry.rel);
            app.proxy.forget(entry.rel);
            count++;
        }
        return count;
    }

    // ---------- 彻底删除（MOVE 不支持时的兜底） ----------
    public void deleteForever(Work work) throws DavClient.DavException {
        DavClient dav = requireClient();
        String rel = work == null ? "" : work.rel;
        if (!safe(rel)) {
            throw new DavClient.DavException("路径不安全，已取消删除");
        }
        dav.deleteTree(rel);
        String music = app.scanner.musicRelFor(work);
        if (music != null && !music.isEmpty() && !music.equals(rel)) {
            try {
                dav.deleteTree(music);
            } catch (Exception ignored) {
            }
        }
        forgetLocal(work);
    }

    public void deleteForeverAuthor(List<String> authorRels) throws DavClient.DavException {
        DavClient dav = requireClient();
        List<String> targets = new ArrayList<>();
        if (authorRels != null) {
            for (String rel : authorRels) {
                if (rel != null && !rel.trim().isEmpty() && safe(rel) && !targets.contains(rel)) {
                    targets.add(rel);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new DavClient.DavException("作者目录不存在");
        }
        for (String rel : targets) {
            dav.deleteTree(rel);
            String music = musicDirFor(rel);
            if (!music.isEmpty()) {
                try {
                    dav.deleteTree(music);
                } catch (Exception ignored) {
                }
            }
        }
        forgetLocalAuthor(targets);
    }

    // ---------- meta.txt ----------
    private void writeMeta(DavClient dav, Item item) throws DavClient.DavException {
        String text = TrashMeta.encode(item.type, item.name, item.deletedAt,
                item.rels, item.musicRels);
        dav.put(item.trashRel + "/meta.txt",
                text.getBytes(Charset.forName("UTF-8")),
                "text/plain; charset=utf-8");
    }

    private static Item parseMeta(String text, String trashRel, String fallbackName,
                                  long fallbackTime) {
        TrashMeta.Parsed parsed = TrashMeta.decode(text);
        Item item = new Item();
        item.trashRel = trashRel;
        item.type = parsed.type;
        item.name = parsed.name.isEmpty() ? fallbackName : parsed.name;
        item.deletedAt = parsed.deletedAt > 0 ? parsed.deletedAt : fallbackTime;
        item.rels.addAll(parsed.rels);
        item.musicRels.addAll(parsed.musicRels);
        return item;
    }

    // ---------- 本地清理 ----------
    private void forgetLocal(Work work) {
        if (work == null) {
            return;
        }
        try {
            app.offline.delete(work);
        } catch (Exception ignored) {
        }
        try {
            app.images.invalidateCover(work.key());
        } catch (Exception ignored) {
        }
        app.proxy.forget(work.rel);
        app.store.remove(work.rel);
    }

    private void forgetLocalAuthor(List<String> rels) {
        for (String rel : rels) {
            List<Work> removed = app.store.removeAuthor(rel);
            for (Work work : removed) {
                try {
                    app.offline.delete(work);
                    app.images.invalidateCover(work.key());
                } catch (Exception ignored) {
                }
            }
            app.proxy.forget(rel);
        }
    }

    private String musicDirFor(String authorRel) {
        String musicRoot = app.store.musicRoot();
        String folder = RemoteFile.baseName(authorRel);
        if (musicRoot == null || musicRoot.isEmpty() || folder.isEmpty()) {
            return "";
        }
        return musicRoot + "/" + folder;
    }

    // ---------- 异步包装 ----------
    private interface Action {
        void run() throws Exception;
    }

    public void moveWorkAsync(Work work, Callback callback) {
        run(callback, () -> moveWork(work));
    }

    public void moveAuthorAsync(List<String> rels, Callback callback) {
        run(callback, () -> moveAuthor(rels));
    }

    public void restoreAsync(Item item, Callback callback) {
        run(callback, () -> restore(item));
    }

    public void deleteItemAsync(Item item, Callback callback) {
        run(callback, () -> deleteItem(item));
    }

    public void clearAllAsync(Callback callback) {
        run(callback, this::clearAll);
    }

    public void deleteForeverAsync(Work work, Callback callback) {
        run(callback, () -> deleteForever(work));
    }

    public void deleteForeverAuthorAsync(List<String> rels, Callback callback) {
        run(callback, () -> deleteForeverAuthor(rels));
    }

    private void run(Callback callback, Action action) {
        app.io.execute(() -> {
            String error = null;
            boolean unsupported = false;
            try {
                action.run();
            } catch (DavClient.DavException exc) {
                error = describe(exc);
                unsupported = exc.status == 405 || exc.status == 501;
            } catch (Exception exc) {
                error = describe(exc);
            }
            final String message = error;
            final boolean flag = unsupported;
            app.runOnMain(() -> {
                if (callback != null) {
                    callback.onDone(new Result(message == null, message, flag));
                }
            });
        });
    }

    public void listAsync(ListCallback callback) {
        app.io.execute(() -> {
            List<Item> items = new ArrayList<>();
            String error = null;
            try {
                items.addAll(list());
            } catch (Exception exc) {
                error = describe(exc);
            }
            final List<Item> result = items;
            final String message = error;
            app.runOnMain(() -> {
                if (callback != null) {
                    callback.onDone(result, new Result(message == null, message, false));
                }
            });
        });
    }

    private static String describe(Exception exc) {
        String message = exc == null ? "" : exc.getMessage();
        return message == null || message.isEmpty() ? "操作失败" : message;
    }
}
