package com.jingying.watch;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 纯 Java 单元测试：不依赖 Android，直接 javac + java 跑。
 *
 * 覆盖面（都是「改了容易错、错了不容易看出来」的地方）：
 *   - 目录名解析：作者 / 文案 / 时间戳，定长判断的边界；
 *   - 文件分类：视频 / 图集 / 实况 / 文案 / 音乐，编号排序；
 *   - 详情落地：toJson / fromJson 往返，重扫时骨架不能盖掉详情；
 *   - 筛选排序：搜索、作者、类型、时间倒序；
 *   - 随机观看的 Fisher–Yates、抖音链接拼装、体积/时长格式化。
 *
 * 运行：tools\run-tests.ps1（build.ps1 -Test 也会跑一遍）
 */
public final class WatchTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("目录名解析", WatchTests::parseWorkFolder);
        run("作者目录拆分", WatchTests::splitAuthor);
        run("作品文件分类", WatchTests::applyFiles);
        run("详情落盘往返", WatchTests::jsonRoundTrip);
        run("骨架合并保留详情", WatchTests::mergeSkeleton);
        run("片库筛选排序", WatchTests::libraryFilter);
        run("片库统计", WatchTests::libraryStats);
        run("随机打乱", WatchTests::shuffles);
        run("抖音链接", WatchTests::douyinLinks);
        run("格式化工具", WatchTests::formatting);
        run("tar 作品包", WatchTests::tarReader);
        run("tar 作品模型", WatchTests::archiveWorkModel);
        run("分享打包往返", WatchTests::tarWriter);
        run("重定向凭据安全", WatchTests::httpRedirects);
        run("空文案不显示作品 ID", WatchTests::emptyCaption);
        run("图集切图与预取", WatchTests::albumNavigation);
        run("图集动图配对", WatchTests::albumLives);
        run("详情里的本地文件目录", WatchTests::localDirListing);
        run("双击判定与单击延迟", WatchTests::doubleTapTiming);
        run("播完去向与自动连播", WatchTests::playNav);
        run("长按倍速重试", WatchTests::speedBoost);
        run("搜索框输入节奏", WatchTests::searchInput);

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("PASS " + passed + " / FAIL 0");
            return;
        }
        System.out.println("FAILED " + failures.size() + " / PASSED " + passed);
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    // ---------- 跑测试 ----------
    private static void run(String group, Runnable body) {
        int failedBefore = failures.size();
        int passedBefore = passed;
        try {
            body.run();
        } catch (Throwable exc) {
            failures.add(group + " 抛出异常：" + exc);
        }
        boolean ok = failures.size() == failedBefore;
        System.out.println((ok ? "ok   " : "FAIL ") + group
                + "（" + (passed - passedBefore) + " 项通过）");
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            passed++;
        } else {
            failures.add(name + "：期望 <" + expected + ">，实际 <" + actual + ">");
        }
    }

    // ---------- 目录名解析 ----------
    private static void parseWorkFolder() {
        String[] parsed = Work.parseWorkFolder("小鹿同学_周末露营vlog_20261029_120540", "小鹿同学");
        eq("标题去作者前缀", "周末露营vlog", parsed[0]);
        eq("完整时间戳", String.valueOf(stamp("20261029120540")), parsed[1]);

        parsed = Work.parseWorkFolder("阿星_街拍合集_20261025", "阿星");
        eq("只有日期的时间戳", "街拍合集", parsed[0]);
        eq("日期补零点", String.valueOf(stamp("20261025000000")), parsed[1]);

        parsed = Work.parseWorkFolder("没有时间戳的文案", "阿星");
        eq("没有时间戳时保留整段", "没有时间戳的文案", parsed[0]);
        eq("没有时间戳时时间为 0", "0", parsed[1]);

        parsed = Work.parseWorkFolder("阿星__文案__20261026_220018", "阿星");
        eq("两头下划线会被去掉", "文案", parsed[0]);

        parsed = Work.parseWorkFolder("小鹿同学._文案_20261029_120540", "小鹿同学.");
        eq("作者名带句点时也能去掉", "文案", parsed[0]);

        parsed = Work.parseWorkFolder("作者_文案_2026102_120540", "作者");
        eq("时间戳位数不对时不解析", "文案_2026102_120540", parsed[0]);
        eq("时间戳位数不对时时间为 0", "0", parsed[1]);

        // 16 个字符结尾但不是时间戳：不能当时间解析，也不能截掉正文
        parsed = Work.parseWorkFolder("作者_2026年终总结_2026102", "作者");
        eq("假时间戳不截断正文", "2026年终总结_2026102", parsed[0]);
        eq("假时间戳时间为 0", "0", parsed[1]);
    }

    private static long stamp(String value) {
        try {
            return new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(value).getTime() / 1000L;
        } catch (Exception exc) {
            return -1L;
        }
    }

    private static void splitAuthor() {
        String[] pair = Work.splitAuthor("小鹿同学_lush0918");
        eq("拆出昵称", "小鹿同学", pair[0]);
        eq("拆出抖音号", "lush0918", pair[1]);

        pair = Work.splitAuthor("阿星_94704804628");
        eq("纯数字抖音号", "94704804628", pair[1]);

        pair = Work.splitAuthor("净影");
        eq("没有抖音号的昵称", "净影", pair[0]);
        eq("没有抖音号时为空", "", pair[1]);

        pair = Work.splitAuthor("");
        eq("空目录名用占位作者", "未知作者", pair[0]);

        pair = Work.splitAuthor("a_b_c");
        eq("按最后一个下划线拆", "a_b", pair[0]);
        eq("最后一段当抖音号", "c", pair[1]);

        pair = Work.splitAuthor("昵称_中文名");
        eq("中文尾巴不当抖音号", "昵称_中文名", pair[0]);
        eq("中文尾巴抖音号为空", "", pair[1]);
    }

    // ---------- 文件分类 ----------
    private static void applyFiles() {
        Work work = Work.fromFiles("净影/Videos/作者_1/作者_作品_20260101_120000",
                "净影/Videos/作者_1", "作者", "1",
                "作者_作品_20260101_120000", 100L, files(), true, null);
        check("可播放作品不为 null", work != null);
        if (work == null) {
            return;
        }
        check("有视频时算视频", work.isVideo());
        eq("主视频优先", "视频.mp4", work.video.name);
        eq("图片两张", 2, work.images.size());
        eq("图片按编号排序", "图片_01.webp", work.images.get(0).name);
        eq("图片按编号排序（第二张）", "图片_02.webp", work.images.get(1).name);
        eq("实况图单独归类", 1, work.lives.size());
        eq("文案被识别", "文案.txt", work.caption.name);
        eq("音乐兜底", "音乐.mp3", work.music != null ? work.music.name : null);
        eq("总大小不含文案", 2000L, work.totalBytes());
        check("详情已标记", work.detailsLoaded && !work.empty);

        // 顺序反过来也要选中「视频.mp4」
        List<RemoteFile> swapped = new ArrayList<>();
        swapped.add(new RemoteFile("d/extra.mp4", "extra.mp4", 1));
        swapped.add(new RemoteFile("d/视频.mp4", "视频.mp4", 1));
        Work preferred = Work.fromFiles("d", "a", "作者", "", "作者_作品_20260101_120000",
                0L, swapped, true, null);
        eq("视频.mp4 优先于其它 mp4", "视频.mp4",
                preferred != null ? preferred.video.name : null);

        // 只有图片 = 图集
        List<RemoteFile> images = new ArrayList<>();
        images.add(new RemoteFile("d/图片_01.jpg", "图片_01.jpg", 10));
        Work album = Work.fromFiles("d", "a", "作者", "", "作者_作品_20260101_120000",
                0L, images, true, null);
        check("只有图片算图集", album != null && album.isAlbum());
        eq("图集没有视频", null, album == null ? null : album.video);

        // 没有可播放内容
        List<RemoteFile> textOnly = new ArrayList<>();
        textOnly.add(new RemoteFile("d/文案.txt", "文案.txt", 10));
        Work nothing = Work.skeleton("d", "a", "作者", "", "作者_作品_20260101_120000", 0L);
        boolean playable = nothing.applyFiles(textOnly);
        check("没有视频/图片时不可播放", !playable);
        check("空作品被标记", nothing.empty && nothing.detailsLoaded);
    }

    private static List<RemoteFile> files() {
        List<RemoteFile> files = new ArrayList<>();
        files.add(new RemoteFile("d/视频.mp4", "视频.mp4", 1000));
        files.add(new RemoteFile("d/图片_02.webp", "图片_02.webp", 200));
        files.add(new RemoteFile("d/图片_01.webp", "图片_01.webp", 100));
        files.add(new RemoteFile("d/动图_01.mp4", "动图_01.mp4", 300));
        files.add(new RemoteFile("d/文案.txt", "文案.txt", 10));
        files.add(new RemoteFile("d/音乐.mp3", "音乐.mp3", 400));
        return files;
    }

    // ---------- 落盘 / 合并 ----------
    private static void jsonRoundTrip() {
        Work work = Work.fromFiles("净影/Videos/作者_1/作者_作品_20260101_120000",
                "净影/Videos/作者_1", "作者", "1",
                "作者_作品_20260101_120000", 100L, files(), true, null);
        work.captionText = "今天去露营，风很大但很舒服\n#露营 @小鹿";
        work.shareUrl = "https://www.douyin.com/video/7525082444551310602";
        work.awemeId = "7525082444551310602";

        JSONObject json = work.toJson();
        Work copy = Work.fromJson(json);
        check("反序列化不为 null", copy != null);
        if (copy == null) {
            return;
        }
        eq("rel 一致", work.rel, copy.rel);
        eq("key 一致", work.key(), copy.key());
        eq("类型一致", work.kind, copy.kind);
        eq("详情标记一致", true, copy.detailsLoaded);
        eq("图片数量一致", work.images.size(), copy.images.size());
        eq("文案一致", work.captionText, copy.captionText);
        eq("分享链接一致", work.shareUrl, copy.shareUrl);
        eq("作品 id 一致", work.awemeId, copy.awemeId);
        eq("音乐一致", work.music == null ? null : work.music.rel,
                copy.music == null ? null : copy.music.rel);
        eq("音乐文件名一致", work.music == null ? null : work.music.name,
                copy.music == null ? null : copy.music.name);
        eq("音乐体积一致", work.music == null ? 0L : work.music.size,
                copy.music == null ? 0L : copy.music.size);
        eq("音乐已确认标记一致", work.musicChecked, copy.musicChecked);
        eq("总大小一致", work.totalBytes(), copy.totalBytes());
        eq("展示标题一致", work.displayTitle(), copy.displayTitle());

        // 骨架（没读过详情）不带 files 字段，反序列化后仍是骨架
        Work skeleton = Work.skeleton("d", "a", "作者", "1", "作者_作品_20260101_120000", 5L);
        Work skeletonCopy = Work.fromJson(skeleton.toJson());
        check("骨架反序列化后仍无详情",
                skeletonCopy != null && !skeletonCopy.detailsLoaded);
    }

    private static void mergeSkeleton() {
        Work rich = Work.fromFiles("净影/Videos/作者_1/作者_作品_20260101_120000",
                "净影/Videos/作者_1", "作者", "1",
                "作者_作品_20260101_120000", 100L, files(), true, null);
        rich.captionText = "文案";
        long oldTime = rich.time;
        String oldKey = rich.key();

        Work skeleton = Work.skeleton(rich.rel, rich.authorRel, rich.author, rich.uniqueId,
                rich.folder, 777L);
        skeleton.time = oldTime + 3600L;
        boolean changed = rich.mergeSkeleton(skeleton);

        check("骨架合并报告有变化", changed);
        check("合并后仍有详情", rich.detailsLoaded && !rich.empty);
        check("合并后仍是视频", rich.isVideo());
        eq("合并后图片还在", 2, rich.images.size());
        check("合并后音乐还在", rich.music != null);
        eq("合并后文案还在", "文案", rich.captionText);
        eq("mtime 更新", 777L, rich.mtime);
        eq("有文案时发布时间不被骨架覆盖", oldTime, rich.time);
        eq("合并后 key 不变", oldKey, rich.key());

        Work plain = Work.fromFiles("净影/Videos/作者_1/作者_作品_20260101_120000",
                "净影/Videos/作者_1", "作者", "1",
                "作者_作品_20260101_120000", 100L, files(), true, null);
        plain.captionText = "";
        plain.time = 0L;
        Work plainSkeleton = Work.skeleton(plain.rel, plain.authorRel, plain.author,
                plain.uniqueId, plain.folder, 888L);
        plainSkeleton.time = 1_000L;
        plain.mergeSkeleton(plainSkeleton);
        eq("没有文案时用骨架时间", 1_000L, plain.time);

        Work empty = Work.skeleton(rich.rel, "", "", "", "", 0L);
        check("空骨架不覆盖作者", !rich.mergeSkeleton(empty));
        eq("作者仍在", "作者", rich.author);
    }

    // ---------- 筛选 / 统计 ----------
    private static List<Work> sampleLibrary() {
        List<Work> works = new ArrayList<>();
        works.add(work("作者A_露营vlog_20260101_120000", "作者A", "111", "v", 300L));
        works.add(work("作者B_城市夜景_20260102_120000", "作者B", "222", "a", 200L));
        works.add(work("作者A_海边日落_20260103_120000", "作者A", "111", "v", 400L));
        Library.sortByTime(works);
        return works;
    }

    private static Work work(String folder, String author, String uid, String kind, long time) {
        List<RemoteFile> files = new ArrayList<>();
        if ("a".equals(kind)) {
            files.add(new RemoteFile("d/" + folder + "/图片_01.jpg", "图片_01.jpg", 10));
        } else {
            files.add(new RemoteFile("d/" + folder + "/视频.mp4", "视频.mp4", 10));
        }
        Work work = Work.fromFiles("d/" + folder, "d/" + author, author, uid, folder,
                time, files, true, null);
        work.time = time;
        return work;
    }

    private static void libraryFilter() {
        List<Work> works = sampleLibrary();

        List<Work> byQuery = Library.filter(works, null, "露营", "all");
        eq("按文案搜索命中一条", 1, byQuery.size());
        eq("命中的是露营", "露营vlog", byQuery.isEmpty() ? null : byQuery.get(0).folderTitle);

        eq("搜索大小写无关", 1, Library.filter(works, null, "VLOG", "all").size());
        eq("按作者名搜索", 2, Library.filter(works, null, "作者a", "all").size());
        eq("按抖音号搜索", 1, Library.filter(works, null, "222", "all").size());
        eq("空搜索词不过滤", 3, Library.filter(works, null, "  ", "all").size());

        eq("作者筛选", 2, Library.filter(works, "作者A", "", "all").size());
        eq("视频筛选", 2, Library.filter(works, null, "", "video").size());
        eq("图集筛选", 1, Library.filter(works, null, "", "album").size());
        eq("筛选后保持时间倒序", "海边日落", Library.filter(works, "作者A", "", "all").get(0).folderTitle);

        // filter 不能改动传入列表
        List<String> before = new ArrayList<>();
        for (Work work : works) {
            before.add(work.rel);
        }
        Library.filter(works, null, "露营", "all");
        List<String> after = new ArrayList<>();
        for (Work work : works) {
            after.add(work.rel);
        }
        eq("筛选不改原列表顺序", before, after);

        // 已经按时间排好的列表，筛选结果也该是排好的
        List<Work> ordered = Library.filter(works, null, "", "all");
        check("结果按时间倒序", ordered.get(0).time >= ordered.get(1).time
                && ordered.get(1).time >= ordered.get(2).time);

        // 首页搜索传 sort=false：输入有序时只挑子集，顺序原样保留（省掉一次全量排序）
        List<Work> presorted = new ArrayList<>(works);
        Library.sortByTime(presorted);
        List<Work> noSort = Library.filter(presorted, null, "", "all", false);
        List<String> presortedRels = new ArrayList<>();
        for (Work work : presorted) {
            presortedRels.add(work.rel);
        }
        List<String> noSortRels = new ArrayList<>();
        for (Work work : noSort) {
            noSortRels.add(work.rel);
        }
        eq("sort=false 保持输入顺序", presortedRels, noSortRels);

        // 扫描过程中列表是乱的，这时传 sort=true 仍要排好
        List<Work> reversed = new ArrayList<>(presorted);
        Collections.reverse(reversed);
        List<Work> sorted = Library.filter(reversed, null, "", "all", true);
        check("sort=true 会给乱序输入排序", sorted.get(0).time >= sorted.get(1).time
                && sorted.get(1).time >= sorted.get(2).time);
    }

    private static void libraryStats() {
        Library.Stats stats = Library.stats(sampleLibrary());
        eq("作品数", 3, stats.count);
        eq("视频数", 2, stats.videos);
        eq("图集数", 1, stats.albums);
        eq("作者数", 2, stats.authors);
        eq("已读详情数", 3, stats.details);
        eq("总大小", 30L, stats.bytes);

        List<Library.AuthorInfo> authors = Library.authors(sampleLibrary());
        eq("作者列表两行", 2, authors.size());
        eq("作品多的作者在前", "作者A", authors.get(0).name);
        eq("作者A 作品数", 2, authors.get(0).count);
    }

    // ---------- 纯工具 ----------
    private static void shuffles() {
        List<Integer> list = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            list.add(index);
        }
        List<Integer> original = new ArrayList<>(list);
        Shuffles.fisherYates(list, new Random(7L));
        eq("打乱后元素一个不少", new HashSet<>(original), new HashSet<>(list));
        eq("打乱后长度不变", 50, list.size());
        check("打乱后顺序变了", !original.equals(list));

        List<Integer> again = new ArrayList<>(original);
        Shuffles.fisherYates(again, new Random(7L));
        eq("同一个种子结果一致", list, again);

        Shuffles.fisherYates(new ArrayList<Integer>(), new Random(1L));
        List<Integer> single = new ArrayList<>(Collections.singletonList(42));
        Shuffles.fisherYates(single, new Random(1L));
        eq("单元素不出错", 42, single.get(0));
    }

    private static void douyinLinks() {
        eq("sec_uid 拼网页主页", "https://www.douyin.com/user/MS4wLjABAAAA-demo",
                DouyinLinks.profileWeb("MS4wLjABAAAA-demo"));
        eq("网页链接去掉空格", "https://www.douyin.com/user/a%20b",
                DouyinLinks.profileWeb(" a b "));
        check("数字 uid 走 scheme",
                DouyinLinks.profileScheme("123456").startsWith("snssdk1128://user/profile/123456"));
        check("scheme 带 refer 参数",
                DouyinLinks.profileScheme("123456").contains("refer=web"));
        eq("搜索链接", "snssdk1128://search?keyword=%E9%9C%B2%E8%90%A5",
                DouyinLinks.searchScheme("露营"));
        eq("网页搜索链接", "https://www.douyin.com/search/%E9%9C%B2%E8%90%A5",
                DouyinLinks.searchWeb("露营"));

        // 文案.txt 里的「作品链接：」常常是整段分享口令，不能整段塞进剪贴板
        eq("从分享口令里抽链接",
                "https://v.douyin.com/iRabc123/",
                DouyinLinks.clean("7.89 复制打开抖音，看看【小鹿的作品】"
                        + "https://v.douyin.com/iRabc123/ 复制此链接，打开抖音搜索"));
        eq("链接尾部的中文标点去掉",
                "https://www.douyin.com/video/7525082444551310602",
                DouyinLinks.clean("https://www.douyin.com/video/7525082444551310602，"));
        eq("没有链接时为空", "", DouyinLinks.clean("这段文案里没有网址"));
        eq("空文本为空", "", DouyinLinks.clean(null));

        eq("短链原样保留", "https://v.douyin.com/iRabc123/",
                DouyinLinks.workUrl("7525082444551310602", false,
                        "https://v.douyin.com/iRabc123/"));
        eq("作品 id 拼视频链接", "https://www.douyin.com/video/7525082444551310602",
                DouyinLinks.workUrl("7525082444551310602", false, ""));
        eq("图集 id 拼图文链接", "https://www.douyin.com/note/7525082444551310602",
                DouyinLinks.workUrl("7525082444551310602", true, ""));
        eq("原链接是图文时沿用 /note/", "https://www.douyin.com/note/7525082444551310602",
                DouyinLinks.workUrl("7525082444551310602", false,
                        "https://www.douyin.com/note/7525082444551310602"));
        eq("没有 id 时退回原链接", "https://www.douyin.com/video/123",
                DouyinLinks.workUrl("", false, "https://www.douyin.com/video/123"));
        eq("什么都没有时为空", "", DouyinLinks.workUrl("", false, ""));

        eq("作品 deep link", "snssdk1128://aweme/detail/7525082444551310602",
                DouyinLinks.workScheme("7525082444551310602"));
        eq("id 不是纯数字时没有 deep link", "", DouyinLinks.workScheme("abc"));
        eq("id 太短不认", "", DouyinLinks.workScheme("123"));
    }

    private static void formatting() {
        eq("0 体积", "0 B", Util.humanSize(0));
        eq("字节数", "512 B", Util.humanSize(512));
        eq("KB", "1.5 KB", Util.humanSize(1536));
        eq("MB", "1.0 MB", Util.humanSize(1024L * 1024L));
        eq("GB", "1.2 GB", Util.humanSize((long) (1.24 * 1024 * 1024 * 1024)));

        eq("0 时长", "00:00", Util.formatDuration(0));
        eq("负时长当 0", "00:00", Util.formatDuration(-5));
        eq("分秒", "01:05", Util.formatDuration(65000));
        eq("带小时", "1:01:01", Util.formatDuration(3661000));

        eq("小数字不缩写", "9999", Util.compactCount(9999));
        eq("万", "1.2w", Util.compactCount(12345));
        eq("0 不显示", "", Util.compactCount(0));
        eq("负数不显示", "", Util.compactCount(-3));

        eq("sha1 稳定", "a9993e364706816aba3e25717850c26c9cd0d89d", Util.sha1Hex("abc"));
        check("sha1 十六进制长度", Util.sha1Hex("露营").length() == 40);

        long now = Util.nowSeconds();
        eq("刚刚", "刚刚", Util.relativeTime(now));
        eq("分钟前", "5 分钟前", Util.relativeTime(now - 5 * 60));
        eq("小时前", "2 小时前", Util.relativeTime(now - 2 * 3600));
        eq("天前", "3 天前", Util.relativeTime(now - 3 * 86400));
    }

    private WatchTests() {
    }

    // ---------- tar 作品包 ----------
    private static void tarReader() {
        try {
            // 测试临时目录放在项目 build 下：某些环境的 java.io.tmpdir 不可写
            File dir = new File("android/build/tar-test-" + System.nanoTime());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File tar = new File(dir, "work.tar");
            writeTar(tar);

            List<TarReader.Member> members = TarReader.list(new TarReader.FileSource(tar));
            eq("tar 成员数", 3, members.size());
            eq("PAX 视频名", "视频.mp4", members.get(0).name);
            eq("视频大小", 5L, members.get(0).size);
            eq("PAX 文案名", "文案.txt", members.get(1).name);
            eq("普通成员名", "plain.txt", members.get(2).name);

            File out = new File(dir, "out");
            //noinspection ResultOfMethodCallIgnored
            out.mkdirs();
            TarReader.Source source = new TarReader.FileSource(tar);
            TarReader.extract(source, members.get(0), new File(out, members.get(0).name));
            eq("解包视频内容", "VIDEO", readFile(new File(out, "视频.mp4")));
            eq("读包内文案", "文案：测试",
                    TarReader.readText(source, members.get(1), 100));
            source.close();

            eq("解包路径不越界", "evil.txt", TarReader.safeName("../evil.txt"));
            eq("空成员名", "", TarReader.safeName(".."));
            check("识别 .tar 后缀", TarReader.looksLikeTar("7525082444551310602.TAR"));

            File bad = new File(dir, "bad.tar");
            try (FileOutputStream output = new FileOutputStream(bad)) {
                output.write("not a tar".getBytes(StandardCharsets.UTF_8));
            }
            boolean failed = false;
            try {
                TarReader.list(new TarReader.FileSource(bad));
            } catch (Exception exc) {
                failed = true;
            }
            check("坏包会报错", failed);
        } catch (Exception exc) {
            failures.add("tar 作品包抛出异常：" + exc);
        }
    }

    private static void writeTar(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            // 模拟电脑版 Python tarfile 的 PAX 格式：中文路径放在 @PaxHeader 里
            writePax(output, "作者_文案_20261108_101010/视频.mp4");
            writeHeader(output, header("视频.mp4", 5L, '0'));
            writeData(output, "VIDEO".getBytes(StandardCharsets.UTF_8));

            writePax(output, "作者_文案_20261108_101010/文案.txt");
            byte[] caption = "文案：测试".getBytes(StandardCharsets.UTF_8);
            writeHeader(output, header("文案.txt", caption.length, '0'));
            writeData(output, caption);

            writeHeader(output, header("plain.txt", 3L, '0'));
            writeData(output, "abc".getBytes(StandardCharsets.UTF_8));

            output.write(new byte[1024]);
        }
    }

    private static void archiveWorkModel() {
        try {
            File dir = new File("android/build/tar-model-" + System.nanoTime());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File tar = new File(dir, "7525082444551310602.tar");
            try (FileOutputStream output = new FileOutputStream(tar)) {
                writePax(output, "作者_文案_20261108_101010/视频.mp4");
                writeHeader(output, header("视频.mp4", 100L, '0'));
                writeData(output, new byte[100]);
                writePax(output, "作者_文案_20261108_101010/图片_01.jpg");
                writeHeader(output, header("图片_01.jpg", 10L, '0'));
                writeData(output, new byte[10]);
                writePax(output, "作者_文案_20261108_101010/图片_02.jpg");
                writeHeader(output, header("图片_02.jpg", 11L, '0'));
                writeData(output, new byte[11]);
                writePax(output, "作者_文案_20261108_101010/音乐.mp3");
                writeHeader(output, header("音乐.mp3", 20L, '0'));
                writeData(output, new byte[20]);
                writePax(output, "作者_文案_20261108_101010/文案.txt");
                writeHeader(output, header("文案.txt", 4L, '0'));
                writeData(output, "文案".getBytes(StandardCharsets.UTF_8));
                output.write(new byte[1024]);
            }
            List<TarReader.Member> members = TarReader.list(new TarReader.FileSource(tar));
            Work base = Work.archiveSkeleton("净影/Videos/作者_1/7525.tar", "净影/Videos/作者_1",
                    "作者", "1", "7525082444551310602.tar", tar.length(), 1_700_000_000L);
            check("骨架带 archive", base.archive != null && base.isArchive());
            eq("骨架标题去掉 .tar", "7525082444551310602", base.folderTitle);
            Work detail = Work.fromArchive(base, members);
            check("详情不为 null", detail != null);
            if (detail == null) {
                return;
            }
            check("有视频", detail.isVideo());
            check("视频在包里", detail.video.inArchive());
            eq("视频偏移非 0", true, detail.video.offset > 0);
            eq("图集两张", 2, detail.images.size());
            eq("音乐在包里", true, detail.music != null && detail.music.inArchive());
            eq("文案在包里", true, detail.caption != null && detail.caption.inArchive());

            base.mergeArchiveDetails(detail);
            check("合并后仍有 archive", base.archive != null);
            check("合并后是视频", base.isVideo());
            eq("合并后图片数", 2, base.images.size());
            check("合并后音乐仍在包里", base.music != null && base.music.inArchive());

            Work copy = Work.fromJson(base.toJson());
            check("落盘往返 archive 还在", copy != null && copy.archive != null);
            if (copy != null && copy.archive != null) {
                eq("往返后图片数", 2, copy.images.size());
                check("往返后视频仍在包里", copy.video != null && copy.video.inArchive());
                eq("往返后视频偏移", base.video.offset, copy.video.offset);
                check("往返后音乐仍在包里", copy.music != null && copy.music.inArchive());
                eq("往返后音乐偏移", base.music.offset, copy.music.offset);
                eq("往返后音乐体积", base.music.size, copy.music.size);
            }
        } catch (Exception exc) {
            failures.add("tar 作品模型抛出异常：" + exc);
        }
    }

    private static void writePax(FileOutputStream output, String path) throws IOException {
        byte[] data = paxRecord(path).getBytes(StandardCharsets.UTF_8);
        writeHeader(output, header("@PaxHeader", data.length, 'x'));
        writeData(output, data);
    }

    private static String paxRecord(String path) {
        String body = "path=" + path + "\n";
        int bodyLength = body.getBytes(StandardCharsets.UTF_8).length;
        int digits = 1;
        while (true) {
            int total = digits + 1 + bodyLength;
            if (String.valueOf(total).length() == digits) {
                return total + " " + body;
            }
            digits++;
        }
    }

    private static byte[] header(String name, long size, char type) {
        byte[] h = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, h, 0, Math.min(100, nameBytes.length));
        putOctal(h, 100, 8, 0644);
        putOctal(h, 108, 8, 0);
        putOctal(h, 116, 8, 0);
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, 0);
        h[156] = (byte) type;
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, 5);
        h[262] = 0;
        h[263] = '0';
        h[264] = '0';
        for (int index = 148; index < 156; index++) {
            h[index] = ' ';
        }
        long sum = 0;
        for (byte value : h) {
            sum += value & 0xff;
        }
        String checksum = String.format(Locale.US, "%06o", sum) + "\u0000 ";
        byte[] checksumBytes = checksum.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, h, 148, Math.min(8, checksumBytes.length));
        return h;
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String text = String.format(Locale.US, "%0" + (length - 1) + "o", value);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(length - 1, bytes.length));
        header[offset + length - 1] = 0;
    }

    private static void writeHeader(FileOutputStream output, byte[] header) throws IOException {
        output.write(header);
    }

    private static void writeData(FileOutputStream output, byte[] data) throws IOException {
        output.write(data);
        int pad = (512 - (data.length % 512)) % 512;
        if (pad > 0) {
            output.write(new byte[pad]);
        }
    }

    // ---------- WebDAV 重定向：凭据不能跟着跳到别的主机 ----------
    private static void httpRedirects() {
        final String auth = "Basic dGVzdDp0ZXN0";
        MiniServer same = null;
        MiniServer cdn = null;
        MiniServer origin = null;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", auth);
            headers.put("Destination", "https://dav.example/private/");

            // 只换路径的同源重定向，凭据必须继续带着
            same = new MiniServer(302, "/next");
            HttpLite.Response response = HttpLite.request("GET",
                    new URL("http://127.0.0.1:" + same.port + "/start"),
                    headers, null, 3000, 3000, null, false);
            eq("同源重定向最终成功", 200, response.status);
            response.close();
            eq("同源重定向发了两次请求", 2, same.seen.size());
            eq("同源重定向保留凭据", auth, same.seen.get(1).get("authorization"));
            eq("同源重定向保留 Destination", "https://dav.example/private/",
                    same.seen.get(1).get("destination"));

            // 跨端口（等价于跨源）重定向，Authorization / Destination 都必须剥掉
            cdn = new MiniServer(0, null);
            origin = new MiniServer(302,
                    "http://127.0.0.1:" + cdn.port + "/cdn/file.mp4");
            HttpLite.Response cdnResponse = HttpLite.request("GET",
                    new URL("http://127.0.0.1:" + origin.port + "/dav/file.mp4"),
                    headers, null, 3000, 3000, null, false);
            eq("跨源重定向最终成功", 200, cdnResponse.status);
            cdnResponse.close();
            check("跨源请求确实到了第二个服务", !cdn.seen.isEmpty());
            if (!cdn.seen.isEmpty()) {
                check("跨源重定向不带 Authorization",
                        cdn.seen.get(0).get("authorization") == null);
                check("跨源重定向不带 Destination",
                        cdn.seen.get(0).get("destination") == null);
            }
        } catch (Exception exc) {
            failures.add("重定向测试异常：" + exc);
        } finally {
            if (same != null) same.close();
            if (cdn != null) cdn.close();
            if (origin != null) origin.close();
        }
    }

    /** 只为重定向测试服务的最小 HTTP 服务器：第一个请求可选返回 302。 */
    private static final class MiniServer implements AutoCloseable {
        final ServerSocket server;
        final int port;
        final List<Map<String, String>> seen =
                Collections.synchronizedList(new ArrayList<Map<String, String>>());
        final Thread thread;
        private final int redirectCode;
        private final String location;

        MiniServer(int redirectCode, String location) throws IOException {
            this.redirectCode = redirectCode;
            this.location = location;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            thread = new Thread(this::serve, "watch-test-http");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            int count = 0;
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = input.readLine();
                    if (requestLine == null) {
                        continue;
                    }
                    Map<String, String> headers = new HashMap<>();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                                    line.substring(colon + 1).trim());
                        }
                    }
                    seen.add(headers);
                    count++;
                    OutputStream output = socket.getOutputStream();
                    if (count == 1 && redirectCode != 0) {
                        String head = "HTTP/1.1 " + redirectCode + " Found\r\n"
                                + "Location: " + location + "\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n";
                        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    } else {
                        byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
                        String head = "HTTP/1.1 200 OK\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
                        output.write(body);
                    }
                    output.flush();
                } catch (Exception ignored) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ---------- 分享打包：TarWriter 写、TarReader 读回来验证 ----------
    private static void tarWriter() {
        try {
            File dir = new File("android/build/tar-writer-" + System.nanoTime());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File tar = new File(dir, "share.tar");

            byte[] video = new byte[700];               // 故意不是 512 的倍数，验证对齐
            new Random(20261112L).nextBytes(video);
            byte[] caption = "文案：打包分享测试".getBytes(StandardCharsets.UTF_8);
            // 总长 >100 字节但目录段 <=155 字节，应当走 USTAR 的 prefix 字段
            String longPath = "很长的作品标题" + repeat("中文", 20) + "/封面图片.jpg";
            byte[] image = "IMG".getBytes(StandardCharsets.US_ASCII);

            try (FileOutputStream output = new FileOutputStream(tar);
                 TarWriter writer = new TarWriter(output)) {
                writer.writeFile("我的作品/视频.mp4", video.length,
                        new ByteArrayInputStream(video), null);
                writer.writeFile("我的作品/文案.txt", caption.length,
                        new ByteArrayInputStream(caption), null);
                writer.writeFile("我的作品/空文件.txt", 0,
                        new ByteArrayInputStream(new byte[0]), null);
                writer.writeFile(longPath, image.length,
                        new ByteArrayInputStream(image), null);
            }

            TarReader.Source source = new TarReader.FileSource(tar);
            // list() 会校验每个头的 checksum，能列全就说明头写对了
            List<TarReader.Member> members = TarReader.list(source);
            eq("打包成员数", 4, members.size());
            eq("视频名", "视频.mp4", members.get(0).name);
            eq("视频大小", (long) video.length, members.get(0).size);
            eq("文案名", "文案.txt", members.get(1).name);
            eq("空文件大小", 0L, members.get(2).size);
            eq("长路径只留 basename", "封面图片.jpg", members.get(3).name);
            check("长路径走 prefix 且能拼回", longPath.equals(members.get(3).path));
            check("包长度按 512 对齐", tar.length() % TarWriter.BLOCK == 0);

            File out = new File(dir, "out");
            //noinspection ResultOfMethodCallIgnored
            out.mkdirs();
            long copied = TarReader.extract(source, members.get(0),
                    new File(out, "video.bin"));
            eq("解出视频字节数", (long) video.length, copied);
            check("视频内容一致",
                    Arrays.equals(video, readBytes(new File(out, "video.bin"))));
            long copiedCaption = TarReader.extract(source, members.get(1),
                    new File(out, "caption.txt"));
            eq("解出文案字节数", (long) caption.length, copiedCaption);
            check("文案内容一致",
                    Arrays.equals(caption, readBytes(new File(out, "caption.txt"))));
            source.close();

            boolean shortReadFailed = false;
            try (FileOutputStream output = new FileOutputStream(new File(dir, "broken.tar"));
                 TarWriter writer = new TarWriter(output)) {
                writer.writeFile("short.bin", 100,
                        new ByteArrayInputStream(new byte[10]), null);
            } catch (IOException exc) {
                shortReadFailed = true;
            }
            check("数据没读够会报错（不写坏包）", shortReadFailed);

            boolean cancelled = false;
            try (FileOutputStream output = new FileOutputStream(new File(dir, "cancel.tar"));
                 TarWriter writer = new TarWriter(output)) {
                writer.writeFile("v.bin", video.length,
                        new ByteArrayInputStream(video), value -> false);
            } catch (IOException exc) {
                cancelled = true;
            }
            check("可以中途取消", cancelled);

            eq("路径清洗丢掉 ..", "evil/视频.mp4",
                    TarWriter.splitPath("../evil/视频.mp4")[0]);
            String[] longParts = TarWriter.splitPath(longPath);
            check("拆分后 name 不超 100 字节",
                    longParts[0].getBytes(StandardCharsets.UTF_8).length <= 100);
            check("长路径拆出了 prefix", !longParts[1].isEmpty());
        } catch (Exception exc) {
            failures.add("分享打包抛出异常：" + exc);
        }
    }

    // ---------- 图集切图 / 预取 ----------
    private static void albumNavigation() {
        eq("wrap 正数取模", 2, AlbumNav.wrap(5, 3));
        eq("wrap 负数回绕", 2, AlbumNav.wrap(-1, 3));
        eq("wrap 正好到末尾", 0, AlbumNav.wrap(3, 3));
        eq("wrap 空图集", 0, AlbumNav.wrap(7, 0));
        eq("wrap 负 size", 0, AlbumNav.wrap(7, -2));
        eq("轮播下一张", 1, AlbumNav.wrap(0 + 1, 3));
        eq("上一张回绕", 2, AlbumNav.wrap(0 - 1, 3));

        check("三张图预取两张", java.util.Arrays.equals(new int[]{2, 1},
                AlbumNav.neighbors(0, 3)));
        check("中间位置预取", java.util.Arrays.equals(new int[]{0, 2},
                AlbumNav.neighbors(1, 3)));
        check("末位回绕预取", java.util.Arrays.equals(new int[]{1, 3},
                AlbumNav.neighbors(2, 5)));
        check("越界索引先回绕", java.util.Arrays.equals(new int[]{2, 1},
                AlbumNav.neighbors(9, 3)));
        check("两张图只预取一次", java.util.Arrays.equals(new int[]{1},
                AlbumNav.neighbors(0, 2)));
        eq("单张图不预取", 0, AlbumNav.neighbors(0, 1).length);
        eq("空图集不预取", 0, AlbumNav.neighbors(0, 0).length);

        check("划过 8% 屏宽就切图", AlbumNav.dragCrossed(-100f, 1000f));
        check("反向划也切图", AlbumNav.dragCrossed(100f, 1000f));
        check("只划 5% 不切图", !AlbumNav.dragCrossed(-50f, 1000f));
        check("宽度为 0 不切图", !AlbumNav.dragCrossed(-500f, 0f));

        check("快扫补切一张", AlbumNav.flingCrossed(-60f, 120f, 1000f));
        check("反向快扫也补切", AlbumNav.flingCrossed(60f, 120f, 1000f));
        check("慢推不补切", !AlbumNav.flingCrossed(-60f, 800f, 1000f));
        check("位移不足不补切", !AlbumNav.flingCrossed(-30f, 60f, 1000f));
        check("时长为 0 不崩", AlbumNav.flingCrossed(-60f, 0f, 1000f));

        // ---------- 抬手补切：MOVE 被合并时只剩 DOWN/UP ----------
        eq("左划补下一张", 1, AlbumNav.swipeDirection(-120f, 8f, 160f, 1000f));
        eq("右划补上一张", -1, AlbumNav.swipeDirection(120f, 8f, 160f, 1000f));
        eq("纵向为主不切图", 0, AlbumNav.swipeDirection(-40f, 260f, 300f, 1000f));
        eq("斜 45 度不切图", 0, AlbumNav.swipeDirection(-200f, 200f, 200f, 1000f));
        eq("位移不足又慢不切图", 0, AlbumNav.swipeDirection(-20f, 4f, 900f, 1000f));
        eq("快速小位移也补切", 1, AlbumNav.swipeDirection(-60f, 5f, 120f, 1000f));
        eq("宽度为 0 不切图", 0, AlbumNav.swipeDirection(-500f, 0f, 100f, 0f));

        // ---------- 手动切过图之后不再自动轮播 ----------
        check("默认会轮播", AlbumNav.shouldAutoAdvance(false, false, 3, true, true));
        check("手动切过就不轮播", !AlbumNav.shouldAutoAdvance(true, false, 3, true, true));
        check("手动切过 + 暂停也不轮播", !AlbumNav.shouldAutoAdvance(true, true, 3, true, true));
        check("手动切过 + 只有一张也不轮播", !AlbumNav.shouldAutoAdvance(true, false, 1, true, true));
        check("暂停时不轮播", !AlbumNav.shouldAutoAdvance(false, true, 3, true, true));
        check("单张图不轮播", !AlbumNav.shouldAutoAdvance(false, false, 1, true, true));
        check("空图集不轮播", !AlbumNav.shouldAutoAdvance(false, false, 0, true, true));
        check("非当前屏不轮播", !AlbumNav.shouldAutoAdvance(false, false, 3, false, true));
        check("宿主在后台不轮播", !AlbumNav.shouldAutoAdvance(false, false, 3, true, false));
        // 换作品 bind 时 manual 会被清回 false，新的图集重新开始轮播
        check("换作品重置后恢复轮播", AlbumNav.shouldAutoAdvance(false, false, 3, true, true));
    }

    // ---------- 空文案不该显示作品 ID ----------
    private static void emptyCaption() {
        final String id = "7525082444551310602";

        check("19 位纯数字是作品 ID", Work.looksLikeWorkId(id));
        check("6 位纯数字是作品 ID", Work.looksLikeWorkId("123456"));
        check("短数字不算作品 ID", !Work.looksLikeWorkId("12345"));
        check("中文标题不算作品 ID", !Work.looksLikeWorkId("周末露营vlog"));
        check("带下划线不算作品 ID", !Work.looksLikeWorkId(id + "_20260101"));
        check("空串不算作品 ID", !Work.looksLikeWorkId(""));
        check("null 不算作品 ID", !Work.looksLikeWorkId(null));

        // ---------- 电脑版对没文案的作品会写「抖音作品 <id>」占位 ----------
        check("抖音作品占位是 ID", Work.looksLikeWorkId("抖音作品 " + id));
        check("抖音作品占位无空格也是 ID", Work.looksLikeWorkId("抖音作品" + id));
        check("作品ID：前缀是 ID", Work.looksLikeWorkId("作品ID：" + id));
        check("作品id: 小写也是 ID", Work.looksLikeWorkId("作品id: " + id));
        check("带换行尾巴的占位是 ID", Work.looksLikeWorkId("抖音作品 " + id + "\n"));
        check("占位后面跟真文案就不是占位", !Work.looksLikeWorkId("抖音作品 " + id + "\n#露营"));
        check("带正文的文案不是 ID", !Work.looksLikeWorkId("第 3 天露营 2026 年"));
        check("文案里带数字不是 ID", !Work.looksLikeWorkId(id + " 的旅行日记"));
        check("短数字加前缀算占位", Work.looksLikeWorkId("抖音作品 123"));

        Work placeholder = Work.skeleton("d", "a", "小鹿", "", "小鹿_20260101_120000", 0L);
        placeholder.captionText = "抖音作品 " + id;
        placeholder.title = "抖音作品 " + id;
        check("占位文案不算文案", !placeholder.hasCaption());
        eq("占位文案不显示", "", placeholder.captionLine());
        check("占位的展示标题不含 ID", !placeholder.displayTitle().contains(id));

        Work parsed = Work.skeleton("d", "a", "小鹿", "", "小鹿_20260101_120000", 0L);
        Work.parseCaptionText(parsed, "作者：小鹿\n作品链接：https://www.douyin.com/video/"
                + id + "\n\n文案：\n抖音作品 " + id + "\n");
        check("解析占位文案后仍算没有文案", !parsed.hasCaption());
        eq("解析占位文案后不显示", "", parsed.captionLine());
        check("解析占位后标题不含 ID", !parsed.displayTitle().contains(id));

        Work legacy = Work.skeleton("d", "a", "小鹿", "", "小鹿_20260101_120000", 0L);
        Work.parseCaptionText(legacy, "作者：小鹿\n文案：\n" + id + "\n");
        check("旧包文案就是纯 ID 时也算没有文案", !legacy.hasCaption());
        eq("旧包纯 ID 文案不显示", "", legacy.captionLine());

        Work archive = Work.archiveSkeleton("净影/Videos/小鹿_1/" + id + ".tar",
                "净影/Videos/小鹿_1", "小鹿", "1", id + ".tar", 1024L, 1_700_000_000L);
        eq("骨架 title 字段仍保留 ID", id, archive.title);
        check("骨架没有文案", !archive.hasCaption());
        eq("空文案不再回退目录名", "", archive.captionLine());
        check("展示标题不含作品 ID", !archive.displayTitle().contains(id));
        eq("展示标题回退到作者", "@小鹿 的作品", archive.displayTitle());

        Work copy = Work.fromJson(archive.toJson());
        check("落盘往返对象在", copy != null);
        if (copy != null) {
            eq("落盘往返后文案仍为空", "", copy.captionLine());
            check("落盘往返后标题不含 ID", !copy.displayTitle().contains(id));
        }

        Work.parseCaptionText(archive, "作者：小鹿\n作品链接：https://www.douyin.com/video/" + id
                + "\n文案：周末去露营啦 #露营\n第二行");
        String caption = "周末去露营啦 #露营\n第二行";
        check("解析后有文案", archive.hasCaption());
        eq("解析后文案正常显示", caption, archive.captionLine());
        eq("解析后标题用文案", caption, archive.displayTitle());
        eq("解析后 awemeId", id, archive.awemeId);

        Work blank = Work.skeleton("d", "a", "作者", "", "作者_作品_20260101_120000", 0L);
        blank.captionText = "   \n  \t ";
        blank.title = id;
        check("纯空白当成没有文案", !blank.hasCaption());
        eq("纯空白文案输出空串", "", blank.captionLine());
        check("纯空白也不显示 ID", !blank.displayTitle().contains(id));

        Work plain = Work.skeleton("d", "a", "小鹿", "", "小鹿_周末露营_20260101_120000", 0L);
        eq("中文目录名也不冒充文案", "", plain.captionLine());
        eq("中文目录名仍可作标题", "周末露营", plain.displayTitle());

        Work written = Work.skeleton("d", "a", "小鹿", "", "小鹿_周末露营_20260101_120000", 0L);
        written.captionText = "今天很开心  ";
        eq("有文案时正常显示", "今天很开心", written.captionLine());
        eq("有文案时标题用文案", "今天很开心", written.displayTitle());
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < times; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static byte[] readBytes(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int count = input.read(bytes, read, bytes.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return read == bytes.length ? bytes : Arrays.copyOf(bytes, read);
        }
    }

    // ---------- 详情里的本地文件目录 ----------
    // ---------- 图集动图配对 ----------
    private static void albumLives() {
        Work work = album("图集_动图", new String[][]{
                {"图片_01.jpg", "100"}, {"图片_02.jpg", "100"}, {"图片_03.jpg", "100"},
                {"动图_01.mp4", "300"}, {"动图_03.mp4", "300"}});
        check("只有图片时算图集", work != null && work.isAlbum());
        if (work == null) {
            return;
        }
        check("识别到动图", work.hasLives());
        eq("动图数量", 2, work.lives.size());
        eq("第一张配对动图_01", "动图_01.mp4",
                work.liveFor(0) == null ? null : work.liveFor(0).name);
        eq("第二张没有动图", null, work.liveFor(1));
        eq("第三张配对动图_03", "动图_03.mp4",
                work.liveFor(2) == null ? null : work.liveFor(2).name);
        eq("越界返回 null", null, work.liveFor(9));
        eq("负数返回 null", null, work.liveFor(-1));
        eq("动图计入作品体积", 900L, work.totalBytes());

        Work plain = album("图集_无动图", new String[][]{
                {"图片_01.jpg", "100"}, {"图片_02.jpg", "100"}});
        check("没有动图的图集", plain != null && !plain.hasLives());
        eq("没有动图时返回 null", null, plain == null ? null : plain.liveFor(0));

        // 旧包文件名里没有序号：数量一致就按下标配，不然没法知道哪张对哪张
        Work noIndex = album("图集_无序号", new String[][]{
                {"封面.jpg", "100"}, {"细节.jpg", "100"},
                {"动图A.mp4", "300"}, {"动图B.mp4", "300"}});
        check("无序号也能配到动图", noIndex != null && noIndex.liveFor(0) != null);
        check("无序号第二张也配到", noIndex != null && noIndex.liveFor(1) != null);

        // 数量对不上又没序号：不能瞎配，宁可不播（退回静态图）
        Work mismatch = album("图集_数量不符", new String[][]{
                {"封面.jpg", "100"}, {"细节.jpg", "100"}, {"花絮.jpg", "100"},
                {"动图A.mp4", "300"}, {"动图B.mp4", "300"}});
        eq("数量不符时不硬配", null, mismatch == null ? null : mismatch.liveFor(0));
    }

    /** 构造一个图集作品：pairs 是 {文件名, 体积}。 */
    private static Work album(String folder, String[][] pairs) {
        List<RemoteFile> files = new ArrayList<>();
        for (String[] pair : pairs) {
            files.add(new RemoteFile("d/" + pair[0], pair[0], Long.parseLong(pair[1])));
        }
        return Work.fromFiles("d", "a", "作者", "1", folder, 0L, files, true, null);
    }

    private static void localDirListing() {
        try {
            File base = new File("android/build/local-files-" + System.nanoTime());
            File dir = new File(base, "w0123456789abcdef");
            check("创建离线目录", dir.mkdirs());

            check("null 不算已下载", !LocalFiles.isDownloaded(null));
            check("不存在的目录不算已下载",
                    !LocalFiles.isDownloaded(new File(base, "missing")));
            check("空目录不算已下载", !LocalFiles.isDownloaded(dir));
            eq("空目录清单为空", "", LocalFiles.listing(dir, 6));

            // 下载中：只有 .part，没有完成标记 meta.json
            sizedFile(new File(dir, "视频.mp4.part"), 1234);
            check("下载中不算已下载", !LocalFiles.isDownloaded(dir));
            eq("下载中的临时文件不进清单", "", LocalFiles.listing(dir, 6));
            check("清掉临时文件", new File(dir, "视频.mp4.part").delete());

            // 只有完成标记、作品文件被清空（下载失败清理过）也不算
            sizedFile(new File(dir, "meta.json"), 20);
            check("只有 meta.json 不算已下载", !LocalFiles.isDownloaded(dir));
            eq("只剩标记时清单为空", "", LocalFiles.listing(dir, 6));

            // 下载完成：媒体 + 文案 + 一堆应当被忽略的杂物
            sizedFile(new File(dir, "视频.mp4"), 2 * 1024 * 1024);
            sizedFile(new File(dir, "图片_01.jpg"), 1024);
            sizedFile(new File(dir, "文案.txt"), 48);
            sizedFile(new File(dir, ".nomedia"), 0);
            sizedFile(new File(dir, "音乐.mp3.tmp"), 999);
            sizedFile(new File(dir, "音乐.mp3.part"), 999);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "子目录").mkdirs();
            check("完成标记 + 作品文件 = 已下载", LocalFiles.isDownloaded(dir));

            List<File> listed = LocalFiles.files(dir);
            eq("杂物被过滤，只剩 3 个作品文件", 3, listed.size());
            eq("文件名排序 1", "图片_01.jpg", listed.get(0).getName());
            eq("文件名排序 2", "文案.txt", listed.get(1).getName());
            eq("文件名排序 3", "视频.mp4", listed.get(2).getName());

            String all = LocalFiles.listing(dir, 6);
            eq("清单行数", 3, all.split("\n", -1).length);
            check("清单含视频名", all.contains("视频.mp4"));
            check("清单含人类可读体积", all.contains(Util.humanSize(2 * 1024 * 1024)));
            check("没超出上限就不折叠", !all.contains("还有"));

            String folded = LocalFiles.listing(dir, 2);
            eq("折叠后行数", 3, folded.split("\n", -1).length);
            check("折叠提示写明剩余数量", folded.contains("还有 1 个文件"));

            String minimum = LocalFiles.listing(dir, 0);
            eq("maxLines=0 至少列 1 个 + 折叠行", 2,
                    minimum.split("\n", -1).length);

            // 中文名 + 0 字节文件也要能正常显示
            File emptyDir = new File(base, "empty");
            //noinspection ResultOfMethodCallIgnored
            emptyDir.mkdirs();
            sizedFile(new File(emptyDir, "meta.json"), 8);
            sizedFile(new File(emptyDir, "空文案.txt"), 0);
            check("中文名空文件也算已下载", LocalFiles.isDownloaded(emptyDir));
            eq("空文件体积", "空文案.txt  ·  0 B", LocalFiles.listing(emptyDir, 6));
        } catch (Throwable exc) {
            failures.add("详情里的本地文件目录 抛出异常：" + exc);
        }
    }

    private static void sizedFile(File file, int length) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] chunk = new byte[Math.min(length, 1024)];
            int left = length;
            while (left > 0) {
                int step = Math.min(left, chunk.length);
                output.write(chunk, 0, step);
                left -= step;
            }
        }
    }

    // ---------- 双击判定与单击延迟 ----------
    private static void doubleTapTiming() {
        int window = TapNav.DOUBLE_TAP_WINDOW_MS;
        int slop = 24;
        long lastUp = 1000L;
        check("单击延迟必须严格大于双击窗口",
                TapNav.SINGLE_TAP_DELAY_MS > window);
        check("没有待判定的单击就不是双击",
                !TapNav.isDoubleTap(false, 100, 200, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("同位置短间隔 = 双击",
                TapNav.isDoubleTap(true, 100, 200, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("立即再按（间隔 0）也算双击",
                TapNav.isDoubleTap(true, 100, 200, 100, 200, lastUp, lastUp, slop));
        check("刚好等于窗口仍算双击",
                TapNav.isDoubleTap(true, 100, 200, 100, 200,
                        lastUp + window, lastUp, slop));
        check("超出窗口 1ms 就不算",
                !TapNav.isDoubleTap(true, 100, 200, 100, 200,
                        lastUp + window + 1, lastUp, slop));
        check("慢双击（300ms 后）不算",
                !TapNav.isDoubleTap(true, 100, 200, 100, 200,
                        lastUp + 300, lastUp, slop));
        check("时间倒流不算",
                !TapNav.isDoubleTap(true, 100, 200, 100, 200,
                        lastUp - 1, lastUp, slop));
        check("横向位移刚好等于 slop 仍算",
                TapNav.isDoubleTap(true, 100 + slop, 200, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("横向位移超 slop 不算",
                !TapNav.isDoubleTap(true, 100 + slop + 1, 200, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("纵向位移超 slop 不算",
                !TapNav.isDoubleTap(true, 100, 200 + slop + 1, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("两下离得远不算",
                !TapNav.isDoubleTap(true, 400, 600, 100, 200,
                        lastUp + 120, lastUp, slop));
        check("slop 为 0 时稍偏就不算",
                !TapNav.isDoubleTap(true, 101, 200, 100, 200,
                        lastUp + 120, lastUp, 0));
        // 核心不变式：判定成立时，第一下的单击一定还没到执行时间
        long firedAt = lastUp + TapNav.SINGLE_TAP_DELAY_MS;
        long latestSecondDown = lastUp + window;
        check("判定成立时待执行的单击还没触发", latestSecondDown < firedAt);
        check("窗口不比系统 300ms 更长，单击不发钝", window <= 300);
    }

    // ---------- 播完去向：自动连播开关 ----------

    private static void playNav() {
        long grace = PlayNav.TOUCH_GRACE_MS;

        // 四种开关 / 是否有下一条的组合
        eq("连播开 + 有下一条 → 下一个", PlayNav.NEXT,
                PlayNav.afterEnded(true, true, grace));
        eq("连播关 + 有下一条 → 停住重播", PlayNav.REPLAY,
                PlayNav.afterEnded(false, true, grace));
        eq("连播开 + 最后一条 → 停住重播", PlayNav.REPLAY,
                PlayNav.afterEnded(true, false, grace));
        eq("连播关 + 最后一条 → 停住重播", PlayNav.REPLAY,
                PlayNav.afterEnded(false, false, grace));

        // 刚滑过 / 正在滑的宽限：上一条刚好播完不能把用户顶走
        eq("宽限内差 1ms → 不动", PlayNav.IGNORE,
                PlayNav.afterEnded(true, true, grace - 1));
        eq("宽限边界整好 → 按设置走", PlayNav.NEXT,
                PlayNav.afterEnded(true, true, grace));
        eq("刚抬手 0ms → 不动", PlayNav.IGNORE,
                PlayNav.afterEnded(true, true, 0));
        eq("时钟回退（负值）→ 不动", PlayNav.IGNORE,
                PlayNav.afterEnded(true, true, -5000));
        eq("宽限内即使关了连播也别重播", PlayNav.IGNORE,
                PlayNav.afterEnded(false, true, 100));

        // 常量自身的不变式
        check("宽限时间为正数", grace > 0);
        check("三种去向互不相同", PlayNav.IGNORE != PlayNav.NEXT
                && PlayNav.NEXT != PlayNav.REPLAY
                && PlayNav.IGNORE != PlayNav.REPLAY);
    }

    private static void speedBoost() {
        SpeedNav nav = new SpeedNav();
        eq("目标倍速 2×", 2f, SpeedNav.BOOST);
        eq("第一次沿用播放器当前参数（官方推荐写法）", 0, nav.variant());
        check("新一次长按立刻就能试", nav.shouldAttempt(0));
        check("还没试过不算失败", nav.failures() == 0 && !nav.unsupported());
        eq("长按提示", "2× 播放中", nav.label());

        nav.markAttempt(1000);
        check("退避内不重复设置倍速", !nav.shouldAttempt(1200));
        check("退避到点可以再试", nav.shouldAttempt(1300));

        nav.onFailure();
        eq("失败后换第二种写法", 1, nav.variant());
        eq("失败一次还不算不支持", 1, nav.failures());
        eq("失败后间隔拉长到 600ms", 600L, nav.retryDelayMs());
        nav.markAttempt(2000);
        check("差 300ms 还没到 600ms", !nav.shouldAttempt(2300));

        nav.onFailure();
        eq("再失败换第三种写法", 2, nav.variant());
        nav.onFailure();
        eq("写法封顶不越界", 2, nav.variant());
        eq("第三次失败后间隔 1200ms", 1200L, nav.retryDelayMs());

        // 一旦成功就清零，别把重试计数带回去
        nav.onSuccess();
        eq("成功后失败计数清零", 0, nav.failures());
        check("成功标记已生效", nav.applied());
        eq("清零后间隔回到 300ms", 300L, nav.retryDelayMs());

        // 试到上限才说不支持
        SpeedNav doomed = new SpeedNav();
        for (int i = 0; i < SpeedNav.MAX_FAILURES - 1; i++) {
            doomed.onFailure();
        }
        check("差一次还不算不支持", !doomed.unsupported());
        eq("差一次时提示仍是 2×", "2× 播放中", doomed.label());
        doomed.onFailure();
        check("试满上限才判不支持", doomed.unsupported());
        eq("不支持的提示", "本机不支持倍速", doomed.label());
        check("判不支持后不再尝试", !doomed.shouldAttempt(Long.MAX_VALUE / 2));

        // 累计退避要够长：页面刚进来缓冲那几百毫秒的失败不能就把结论定死
        long total = 0;
        for (int failures = 0; failures < SpeedNav.MAX_FAILURES; failures++) {
            SpeedNav probe = new SpeedNav();
            for (int i = 0; i < failures; i++) {
                probe.onFailure();
            }
            total += probe.retryDelayMs();
        }
        check("试到上限要花 5 秒以上（共 " + total + "ms）", total >= 5000L);

        // 播放状态变了：清失败计数、但保留已经试出来的写法
        SpeedNav again = new SpeedNav();
        again.onFailure();
        again.onFailure();
        again.restart();
        eq("restart 清失败计数", 0, again.failures());
        eq("restart 保留写法", 2, again.variant());
        again.reset();
        eq("reset 回到第一种写法", 0, again.variant());

        SpeedNav apiLow = new SpeedNav();
        apiLow.giveUp();
        check("低版本直接判不支持", apiLow.unsupported());
        eq("低版本提示", "本机不支持倍速", apiLow.label());

        // ---- 实测：参数被接受 ≠ 真的加速 ----
        SpeedNav probe = new SpeedNav();
        probe.onParamsApplied(0, 1_000);
        eq("接受参数后进入参数模式", SpeedNav.MODE_PARAMS, probe.mode());
        check("刚设完不急着下结论", !probe.shouldProbe(500));
        check("到点才实测", probe.shouldProbe(700));
        check("700ms 走了 1400ms 算真加速", !probe.probeIgnored(700, 2_400));
        check("700ms 只走了 700ms 算被忽略", probe.probeIgnored(700, 1_700));
        check("位置回退过（用户拖了进度条）不判", !probe.probeIgnored(700, 900));

        // 单次采样不算数：位置刷新滞后的设备会被误判，误判就把平滑 2× 换成跳帧
        check("第一次可疑只是记下，不马上跳帧", !probe.suspect(700, 1_700));
        check("重设基准后要再等一轮才测", !probe.shouldProbe(1_000));
        check("等满 700ms 再测", probe.shouldProbe(1_400));
        check("两轮都对不上才下结论", probe.suspect(1_400, 2_300));
        check("确认后标记可疑", probe.suspected());

        // 确认没加速也别直接跳帧：先给一次重建播放器的机会（华为等机型首次设置不生效）
        check("还能重建播放器", probe.canRebuild());
        probe.markRebuilt();
        check("重建过就不再重复重建", !probe.canRebuild());
        probe.reset();
        check("新一次长按又能重建", probe.canRebuild());

        // ---- 跳帧兜底 ----
        SpeedNav seek = new SpeedNav();
        seek.onFailure();
        check("失败一次先换写法，不急着跳帧", !seek.wantsSeek());
        seek.onFailure();
        check("连续失败两次改走跳帧", seek.wantsSeek());
        seek.beginSeek(10_000, 5_000);
        check("已进入跳帧模式", seek.inSeek());
        eq("跳帧时提示改为快进（便于区分走的哪条路）", "2× 快进", seek.label());
        check("跳帧模式不再试变速参数", !seek.shouldAttempt(999_999));
        eq("目标 = 起点 + 已过时间 × 2", 5_600L, seek.seekTarget(10_300, 5_300, 60_000));
        seek.markSeek(10_300);
        check("间隔内不重复 seek", seek.seekTarget(10_400, 5_700, 60_000) < 0);
        eq("到下个间隔继续追", 6_200L, seek.seekTarget(10_600, 5_800, 60_000));

        SpeedNav tail = new SpeedNav();
        tail.beginSeek(0, 9_000);
        eq("接近结尾夹到留白处，别跳到播完", 9_500L, tail.seekTarget(10_000, 9_000, 10_000));

        SpeedNav noDur = new SpeedNav();
        noDur.beginSeek(0, 0);
        check("时长未知不跳（怕一跳就播完）", noDur.seekTarget(10_000, 1_000, 0) < 0);

        SpeedNav closeLead = new SpeedNav();
        closeLead.beginSeek(0, 0);
        check("离目标太近不值得 seek", closeLead.seekTarget(300, 550, 60_000) < 0);

        // 落点偏前（seek 落到了更前面的关键帧）要能发现，否则越追越偏、画面倒退
        SpeedNav landing = new SpeedNav();
        landing.beginSeek(0, 5_000);
        landing.markSeek(300, 5_600);
        check("落点比目标差 500ms 算没落到", landing.landedShort(5_100));
        check("落点在容差内不算", !landing.landedShort(5_400));
        check("没记过目标时不下判断", !new SpeedNav().landedShort(0));

        // 1.2 秒内按 300ms 一档追，累计应该正好到 2.4 秒的位置
        SpeedNav rate = new SpeedNav();
        rate.beginSeek(0, 0);
        long cursor = 0;
        for (long t = 300; t <= 1_200; t += 300) {
            long target = rate.seekTarget(t, cursor, 60_000);
            check("每档都要往前补（t=" + t + "）", target > 0);
            rate.markSeek(t, target);
            cursor = target;
        }
        eq("1.2 秒追到 2.4 秒处", 2_400L, cursor);

        SpeedNav keepMode = new SpeedNav();
        keepMode.beginSeek(0, 0);
        keepMode.restart();
        check("缓冲结束后仍留在跳帧模式", keepMode.inSeek());
        check("跳帧期间缓冲不清基准（否则跳幅忽大忽小）", keepMode.hasSeekBase());
        keepMode.reset();
        check("reset 退出跳帧", !keepMode.inSeek());
    }

    private static void searchInput() {
        eq("null 归一成空串", "", SearchNav.normalize(null));
        eq("去掉首尾空格", "露营", SearchNav.normalize("  露营  "));
        eq("全空格等于空", "", SearchNav.normalize("   "));

        // 防抖：普通输入要等，删空立刻生效
        eq("普通输入走 260ms 防抖", SearchNav.DEBOUNCE_MS,
                SearchNav.delayMs("露营", false));
        eq("删空立刻生效", 0L, SearchNav.delayMs("", false));
        eq("只剩空格也算删空", 0L, SearchNav.delayMs("   ", false));
        eq("null 当删空", 0L, SearchNav.delayMs(null, false));
        eq("拼音还没上屏先等 120ms", SearchNav.COMPOSING_MS,
                SearchNav.delayMs("luying", true));
        eq("组合输入时即使框里是空的也先等", SearchNav.COMPOSING_MS,
                SearchNav.delayMs("", true));

        // 组合输入的等待上限：输入法一直不提交也不能永远不筛
        check("组合输入第一次等", SearchNav.waitForComposing(true, 0));
        check("等满上限就不再等", !SearchNav.waitForComposing(true,
                SearchNav.MAX_COMPOSING_WAITS));
        check("不在组合输入就不等", !SearchNav.waitForComposing(false, 0));
        check("等待上限是正数", SearchNav.MAX_COMPOSING_WAITS > 0);

        // 关键词没变就别重建 1.6 万条的列表
        check("换关键词要重建", SearchNav.needsRebuild("露营", "露营 湖"));
        check("删字要重建", SearchNav.needsRebuild("露营", "露"));
        check("只是首尾空格变化不重建", !SearchNav.needsRebuild("露营", " 露营 "));
        check("删空要重建", SearchNav.needsRebuild("露营", ""));
        check("本来就是空又删一次不重建", !SearchNav.needsRebuild("", "  "));
        check("null 和空串等价", !SearchNav.needsRebuild(null, ""));
    }

    private static String readFile(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            return new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8);
        }
    }
}
