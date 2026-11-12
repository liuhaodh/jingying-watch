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

    private static String readFile(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            return new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8);
        }
    }
}
