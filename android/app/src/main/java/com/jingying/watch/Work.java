package com.jingying.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个作品（视频 / 图集）。
 *
 * 目录命名规则和「净影」系列完全一致：
 *   Videos/净影/昵称_抖音号/昵称_文案_20260101_120000/视频.mp4
 */
public final class Work {

    public static final Set<String> VIDEO_EXTS = new HashSet<>();
    public static final Set<String> IMAGE_EXTS = new HashSet<>();
    public static final Set<String> AUDIO_EXTS = new HashSet<>();
    public static final Set<String> TEXT_EXTS = new HashSet<>();

    static {
        Collections.addAll(VIDEO_EXTS, ".mp4", ".mov", ".webm", ".mkv", ".m4v", ".avi", ".flv", ".ts");
        Collections.addAll(IMAGE_EXTS, ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".heic");
        Collections.addAll(AUDIO_EXTS, ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".amr");
        Collections.addAll(TEXT_EXTS, ".txt", ".md");
    }

    private static final String[] LIVE_PREFIXES = {"动图", "实况", "live"};
    private static final String[] MAIN_VIDEO_PREFIXES = {"视频", "video"};
    private static final Pattern INDEX = Pattern.compile("(\\d+)");
    private static final Pattern AWEME = Pattern.compile("/(?:video|note)/(\\d{6,})");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._\\-]{1,32}$");

    /**
     * 解析目录名和文案时每个作品都要格式化/解析一次时间。
     * SimpleDateFormat 创建成本不低（1.6 万作品就是 1.6 万次），按线程复用一份。
     */
    private static final ThreadLocal<SimpleDateFormat> FOLDER_STAMP =
            stampFormat("yyyyMMddHHmmss");
    private static final ThreadLocal<SimpleDateFormat> CAPTION_STAMP =
            stampFormat("yyyy-MM-dd HH:mm:ss");

    private static ThreadLocal<SimpleDateFormat> stampFormat(final String pattern) {
        return new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat(pattern, Locale.US);
            }
        };
    }

    public String rel = "";
    public String authorRel = "";
    public String author = "";
    public String uniqueId = "";
    public String folder = "";
    public String folderTitle = "";
    public String title = "";
    public long time;
    public long mtime;
    public String kind = "unknown";
    public RemoteFile video;
    public RemoteFile music;
    public RemoteFile caption;
    public final List<RemoteFile> images = new ArrayList<>();
    public final List<RemoteFile> lives = new ArrayList<>();
    public boolean detailsLoaded;
    public boolean empty;
    public boolean musicChecked;
    public String captionText = "";
    public String awemeId = "";
    public String shareUrl = "";

    private volatile String cachedKey;
    private volatile String cachedAuthor;
    private volatile String cachedTitle;
    private volatile String cachedSearch;

    // ---------- 构造 / 解析 ----------
    public static Work skeleton(String rel, String authorRel, String author,
                                String uniqueId, String folder, long mtime) {
        Work work = new Work();
        work.rel = rel == null ? "" : rel;
        work.authorRel = authorRel == null ? "" : authorRel;
        work.author = author == null ? "" : author;
        work.uniqueId = uniqueId == null ? "" : uniqueId;
        work.folder = folder == null ? "" : folder;
        work.mtime = mtime;
        String[] parsed = parseWorkFolder(folder, work.author);
        work.folderTitle = parsed[0];
        work.title = parsed[0];
        work.time = parseLong(parsed[1]);
        return work;
    }

    /** 从作品目录里的文件清单构建作品；没有可播放内容返回 null。 */
    public static Work fromFiles(String rel, String authorRel, String author, String uniqueId,
                                 String folder, long mtime, List<RemoteFile> files,
                                 boolean musicChecked, RemoteFile music) {
        Work work = skeleton(rel, authorRel, author, uniqueId, folder, mtime);
        work.musicChecked = musicChecked;
        work.music = music;
        boolean has = work.applyFiles(files);
        return has ? work : null;
    }

    /** 把文件分类成 视频 / 图片 / 实况 / 文案 / 兜底音乐。返回是否可播放。 */
    public boolean applyFiles(List<RemoteFile> files) {
        images.clear();
        lives.clear();
        video = null;
        caption = null;
        RemoteFile fallbackMusic = null;
        for (RemoteFile file : files) {
            if (file == null) {
                continue;
            }
            String ext = file.ext();
            if (TEXT_EXTS.contains(ext)) {
                if (caption == null) {
                    caption = file;
                }
            } else if (IMAGE_EXTS.contains(ext)) {
                images.add(file);
            } else if (VIDEO_EXTS.contains(ext)) {
                if (startsWith(file.name, LIVE_PREFIXES)) {
                    lives.add(file);
                } else if (video == null) {
                    video = file;
                } else if (startsWith(file.name, MAIN_VIDEO_PREFIXES)
                        && !startsWith(video.name, MAIN_VIDEO_PREFIXES)) {
                    video = file;
                }
            } else if (AUDIO_EXTS.contains(ext) && fallbackMusic == null) {
                fallbackMusic = file;
            }
        }
        sortByIndex(images);
        sortByIndex(lives);
        if (music == null) {
            music = fallbackMusic;
        }
        detailsLoaded = true;
        if (video == null && images.isEmpty()) {
            empty = true;
            kind = "unknown";
            return false;
        }
        empty = false;
        kind = video != null ? "video" : "album";
        return true;
    }

    /** 用详情结果原地更新（列表里持有同一个对象）。 */
    public boolean mergeDetails(Work other) {
        if (other == null) {
            empty = true;
            detailsLoaded = true;
            return false;
        }
        kind = other.kind;
        video = other.video;
        images.clear();
        images.addAll(other.images);
        lives.clear();
        lives.addAll(other.lives);
        caption = other.caption;
        if (other.mtime > 0) {
            mtime = other.mtime;
        }
        if (other.music != null) {
            music = other.music;
            musicChecked = true;
        }
        if (other.folderTitle != null && !other.folderTitle.isEmpty()) {
            folderTitle = other.folderTitle;
            title = other.folderTitle;
        }
        if (other.time > 0) {
            time = other.time;
        }
        if (other.captionText != null && !other.captionText.isEmpty()) {
            captionText = other.captionText;
        }
        invalidateDisplay();
        detailsLoaded = true;
        empty = other.empty;
        return !empty;
    }

    // ---------- 展示 ----------
    public String key() {
        if (cachedKey == null) {
            cachedKey = "w" + Util.sha1Hex(rel).substring(0, 16);
        }
        return cachedKey;
    }

    public boolean isVideo() {
        return "video".equals(kind);
    }

    public boolean isAlbum() {
        return "album".equals(kind);
    }

    public String displayTitle() {
        String cached = cachedTitle;
        if (cached != null) {
            return cached;
        }
        String value = captionText != null && !captionText.trim().isEmpty() ? captionText : title;
        if (value == null || value.trim().isEmpty()) {
            value = folderTitle;
        }
        if (value == null || value.trim().isEmpty()) {
            value = "抖音作品";
        }
        cached = value.trim();
        cachedTitle = cached;
        return cached;
    }

    public String displayAuthor() {
        String cached = cachedAuthor;
        if (cached != null) {
            return cached;
        }
        String value = author == null ? "" : author.trim();
        cached = value.isEmpty() ? "抖音创作者" : value;
        cachedAuthor = cached;
        return cached;
    }

    /** 搜索用的规范化文本：只有字段变了才重新拼，避免每敲一个字都为全库重建字符串。 */
    public String searchText() {
        String cached = cachedSearch;
        if (cached != null) {
            return cached;
        }
        cached = (displayTitle() + " " + (title == null ? "" : title) + " "
                + (folderTitle == null ? "" : folderTitle) + " "
                + displayAuthor() + " " + (uniqueId == null ? "" : uniqueId))
                .toLowerCase(Locale.CHINA);
        cachedSearch = cached;
        return cached;
    }

    /** 文案/作者/标题变了以后清掉展示缓存。 */
    private void invalidateDisplay() {
        cachedAuthor = null;
        cachedTitle = null;
        cachedSearch = null;
    }

    public String captionLine() {
        String value = captionText == null ? "" : captionText.trim();
        return value.isEmpty() ? title : value;
    }

    public RemoteFile coverFile() {
        return images.isEmpty() ? null : images.get(0);
    }

    public String fileNameForPlay() {
        if (video != null) {
            return video.name;
        }
        if (!images.isEmpty()) {
            return images.get(0).name;
        }
        return "视频.mp4";
    }

    public long totalBytes() {
        long total = 0;
        if (video != null) {
            total += video.size;
        }
        for (RemoteFile file : images) {
            total += file.size;
        }
        for (RemoteFile file : lives) {
            total += file.size;
        }
        if (music != null) {
            total += music.size;
        }
        return total;
    }

    public List<RemoteFile> workFiles() {
        List<RemoteFile> files = new ArrayList<>();
        if (video != null) {
            files.add(video);
        }
        files.addAll(lives);
        files.addAll(images);
        if (caption != null) {
            files.add(caption);
        }
        if (music != null) {
            files.add(music);
        }
        return files;
    }

    // ---------- 持久化 ----------
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("rel", rel);
            json.put("authorRel", authorRel);
            json.put("author", author);
            json.put("uid", uniqueId);
            json.put("folder", folder);
            json.put("folderTitle", folderTitle);
            json.put("time", time);
            json.put("mtime", mtime);
            json.put("kind", kind);
            json.put("details", detailsLoaded);
            json.put("empty", empty);
            json.put("captionText", captionText);
            json.put("aweme", awemeId);
            json.put("share", shareUrl);
            if (detailsLoaded) {
                JSONArray array = new JSONArray();
                for (RemoteFile file : workFiles()) {
                    if (file != null) {
                        array.put(file.toJson());
                    }
                }
                json.put("files", array);
                if (music != null) {
                    json.put("music", music.toJson());
                }
                json.put("musicChecked", musicChecked);
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    public static Work fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        String rel = json.optString("rel", "");
        if (rel.isEmpty()) {
            return null;
        }
        Work work = new Work();
        work.rel = rel;
        work.authorRel = json.optString("authorRel", "");
        work.author = json.optString("author", "");
        work.uniqueId = json.optString("uid", "");
        work.folder = json.optString("folder", RemoteFile.baseName(rel));
        work.folderTitle = json.optString("folderTitle", work.folder);
        work.time = json.optLong("time", 0L);
        work.mtime = json.optLong("mtime", 0L);
        work.kind = json.optString("kind", "unknown");
        work.detailsLoaded = json.optBoolean("details", false);
        work.empty = json.optBoolean("empty", false);
        work.captionText = json.optString("captionText", "");
        work.awemeId = json.optString("aweme", "");
        work.shareUrl = json.optString("share", "");
        if (work.detailsLoaded) {
            List<RemoteFile> files = new ArrayList<>();
            JSONArray array = json.optJSONArray("files");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    RemoteFile file = RemoteFile.fromJson(array.optJSONObject(index), rel);
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
            work.musicChecked = json.optBoolean("musicChecked", false);
            JSONObject musicJson = json.optJSONObject("music");
            RemoteFile music = RemoteFile.fromJson(musicJson, "");
            work.applyFiles(files);
            if (music != null) {
                work.music = music;
            }
        }
        return work;
    }

    // ---------- 目录名解析 ----------
    /** 「昵称_抖音号」→ [昵称, 抖音号]。 */
    public static String[] splitAuthor(String folder) {
        String name = folder == null ? "" : folder.trim();
        if (name.isEmpty()) {
            return new String[]{"未知作者", ""};
        }
        int index = name.lastIndexOf('_');
        if (index > 0) {
            String head = name.substring(0, index);
            String tail = name.substring(index + 1);
            if (!head.isEmpty() && looksLikeId(tail)) {
                return new String[]{head.trim(), tail};
            }
        }
        return new String[]{name, ""};
    }

    /** 「作者_文案_20260101_120000」→ [文案, 发布时间秒]。 */
    public static String[] parseWorkFolder(String folder, String author) {
        String text = folder == null ? "" : folder.trim();
        String body = text;
        long stamp = 0L;
        String datePart = "";
        String clockPart = "";
        // 时间戳固定是结尾的 "_yyyyMMdd_HHmmss"（16 个字符）或 "_yyyyMMdd"（9 个字符）。
        // 用定长判断代替 "(.*)_(\\d{8})_(\\d{6})$" 这种带回溯的正则：
        // 1.6 万作品扫下来，正则比直接看字符慢好几倍。
        int length = text.length();
        if (length >= 16 && isStampTail(text, length, 16, 15, true)) {
            body = text.substring(0, length - 16);
            datePart = text.substring(length - 15, length - 7);
            clockPart = text.substring(length - 6);
        } else if (length >= 9 && isStampTail(text, length, 9, 8, false)) {
            body = text.substring(0, length - 9);
            datePart = text.substring(length - 8);
            clockPart = "000000";
        }
        if (!datePart.isEmpty()) {
            try {
                Date date = FOLDER_STAMP.get().parse(datePart + clockPart);
                if (date != null) {
                    stamp = date.getTime() / 1000L;
                }
            } catch (ParseException ignored) {
            }
        }
        String resultTitle = body.trim();
        String name = author == null ? "" : author.trim();
        if (!name.isEmpty()) {
            if (resultTitle.startsWith(name + "_")) {
                resultTitle = resultTitle.substring(name.length() + 1);
            } else {
                String trimmed = name.replaceAll("[ .]+$", "");
                if (!trimmed.isEmpty() && resultTitle.startsWith(trimmed)
                        && resultTitle.length() > trimmed.length()
                        && "_. -".indexOf(resultTitle.charAt(trimmed.length())) >= 0) {
                    resultTitle = resultTitle.substring(trimmed.length() + 1);
                }
            }
        }
        resultTitle = trimEdge(resultTitle);
        if (resultTitle.isEmpty()) {
            resultTitle = body.trim();
        }
        return new String[]{resultTitle, String.valueOf(stamp)};
    }

    public static void parseCaptionText(Work work, String text) {
        if (work == null || text == null) {
            return;
        }
        StringBuilder captionLines = new StringBuilder();
        boolean inCaption = false;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.trim();
            if (!inCaption) {
                if (line.startsWith("作者：") || line.startsWith("作者:")) {
                    String value = line.substring(3).trim();
                    if (!value.isEmpty()) {
                        work.author = value;
                    }
                } else if (line.startsWith("抖音号：") || line.startsWith("抖音号:")) {
                    String value = line.substring(4).trim();
                    work.uniqueId = isUnknown(value) ? "" : value;
                } else if (line.startsWith("发布时间：") || line.startsWith("发布时间:")) {
                    String value = line.substring(5).trim();
                    try {
                        Date date = CAPTION_STAMP.get().parse(value);
                        if (date != null) {
                            work.time = date.getTime() / 1000L;
                        }
                    } catch (ParseException ignored) {
                    }
                } else if (line.startsWith("作品链接：") || line.startsWith("作品链接:")) {
                    String value = line.substring(5).trim();
                    work.shareUrl = value;
                    Matcher matched = AWEME.matcher(value);
                    if (matched.find()) {
                        work.awemeId = matched.group(1);
                    }
                } else if (line.startsWith("文案：") || line.startsWith("文案:")) {
                    inCaption = true;
                    String value = line.substring(3).trim();
                    if (!value.isEmpty()) {
                        captionLines.append(value);
                    }
                }
            } else {
                captionLines.append('\n').append(raw);
            }
        }
        String caption = captionLines.toString().trim();
        if (!caption.isEmpty()) {
            work.captionText = caption;
            work.title = firstLine(caption);
        }
        work.invalidateDisplay();
    }

    // ---------- 细节 ----------
    private static boolean isUnknown(String value) {
        return value.isEmpty() || "未知".equals(value) || "-".equals(value) || "无".equals(value);
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }

    private static boolean looksLikeId(String text) {
        if (text == null || !ID.matcher(text).matches()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetterOrDigit(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(String name, String[] prefixes) {
        String low = name == null ? "" : name.toLowerCase(Locale.US);
        for (String prefix : prefixes) {
            if (low.startsWith(prefix.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static void sortByIndex(List<RemoteFile> files) {
        if (files.size() < 2) {
            return;
        }
        // 比较器里不要重复跑正则：先把每个文件的序号算好，排序时直接查表
        final java.util.Map<String, Integer> order = new java.util.HashMap<>(files.size() * 2);
        for (RemoteFile file : files) {
            order.put(file.name, indexOf(file.name));
        }
        Collections.sort(files, new Comparator<RemoteFile>() {
            @Override
            public int compare(RemoteFile left, RemoteFile right) {
                int leftIndex = order.get(left.name);
                int rightIndex = order.get(right.name);
                return leftIndex != rightIndex
                        ? Integer.compare(leftIndex, rightIndex)
                        : left.name.compareTo(right.name);
            }
        });
    }

    private static boolean isEdgeChar(char value) {
        return value == ' ' || value == '_' || value == '.' || value == '-';
    }

    /**
     * 判断结尾是不是 "_yyyyMMdd"（tailLen=9）或 "_yyyyMMdd_HHmmss"（tailLen=16）。
     * dateOffset 是日期第一位从末尾数的偏移（15 或 8）。
     */
    private static boolean isStampTail(String text, int length, int tailLen,
                                       int dateOffset, boolean withClock) {
        if (text.charAt(length - tailLen) != '_'
                || !isDigits(text, length - dateOffset, length - dateOffset + 8)) {
            return false;
        }
        return !withClock
                || (text.charAt(length - 7) == '_' && isDigits(text, length - 6, length));
    }

    private static boolean isDigits(String text, int from, int to) {
        for (int index = from; index < to; index++) {
            char value = text.charAt(index);
            if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    /** 等价于 replaceAll("^[ _.-]+|[ _.-]+$", "")，但不用编译正则。 */
    private static String trimEdge(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdgeChar(value.charAt(start))) {
            start++;
        }
        while (end > start && isEdgeChar(value.charAt(end - 1))) {
            end--;
        }
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static int indexOf(String name) {
        Matcher matched = INDEX.matcher(name == null ? "" : name);
        return matched.find() ? Integer.parseInt(matched.group(1)) : 1000000;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exc) {
            return 0L;
        }
    }
}
