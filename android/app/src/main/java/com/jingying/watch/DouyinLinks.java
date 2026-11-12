package com.jingying.watch;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音链接的拼装（纯 Java，方便单测）。
 *
 * 两个 ID 别混用：
 * - `sec_uid`（MS4wLjABAAAA…）只能放进网页主页链接 `douyin.com/user/<sec_uid>`；
 * - `snssdk1128://user/profile/` 后面要的是**数字 UID**，
 *   把 sec_uid 塞进去抖音能拉起但会停在空白主页。
 *
 * 所以 App 里优先用 profileWeb（抖音注册了 douyin.com 的 App Links，会直接拉起 App），
 * profileScheme 只在拿得到数字 UID 时当备选。
 */
final class DouyinLinks {

    private static final String SCHEME = "snssdk1128://";
    private static final String WEB = "https://www.douyin.com";
    /** 从一大段分享口令里抽出第一个链接：中文逗号/句号/引号都算结尾。 */
    private static final Pattern URL =
            Pattern.compile("https?://[^\\s，。；、！？\"'<>）】]+", Pattern.CASE_INSENSITIVE);

    private DouyinLinks() {
    }

    /** 只有 numericUid 是纯数字时才用，别把 sec_uid 传进来。 */
    static String profileScheme(String numericUid) {
        return SCHEME + "user/profile/" + encode(numericUid) + "?refer=web&type=need_follow";
    }

    /** 官方主页分享链接，sec_uid 专用；抖音通过 App Links 拉起 App。 */
    static String profileWeb(String secUid) {
        return WEB + "/user/" + encode(secUid);
    }

    static String searchScheme(String keyword) {
        return SCHEME + "search?keyword=" + encode(keyword);
    }

    static String searchWeb(String keyword) {
        return WEB + "/search/" + encode(keyword);
    }

    /**
     * 作品详情 deep link。抖音 App 认 `aweme/detail/<作品id>`，
     * 拉起后直接进作品页，不用经过浏览器（网页链接在未登录的浏览器里
     * 常常只显示「打开抖音观看」，用户就以为链接打不开了）。
     */
    static String workScheme(String awemeId) {
        String id = digits(awemeId);
        return id.isEmpty() ? "" : SCHEME + "aweme/detail/" + id;
    }

    /**
     * 给剪贴板 / 分享用的作品链接。
     *
     * 文案.txt 里的「作品链接：」有可能是分享口令（前后带中文），
     * 直接整段复制粘到抖音里识别不了，所以先只抽 URL。
     *
     * 优先级：
     * 1. `v.douyin.com` / `iesdouyin.com` 短链原样保留——这是抖音官方分享格式，
     *    App 内粘贴识别的成功率最高；
     * 2. 有作品 id 时拼标准作品页（视频 `/video/`、图集 `/note/`）；
     * 3. 都不行就返回抽出来的 URL（可能是旧格式），没有就空串。
     */
    static String workUrl(String awemeId, boolean album, String raw) {
        String url = clean(raw);
        if (isShareLink(url)) {
            return url;
        }
        String id = digits(awemeId);
        if (!id.isEmpty()) {
            // 原链接里已经写明是图文（/note/）就沿用，否则按作品类型猜：
            // 抖音对图文作品用 /note/，视频用 /video/
            boolean note = url.contains("/note/") || (url.isEmpty() && album);
            return WEB + (note ? "/note/" : "/video/") + id;
        }
        return url;
    }

    /** 抽第一个 http(s) 链接并去掉尾部标点。没有链接返回空串。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = URL.matcher(raw);
        if (!matcher.find()) {
            return "";
        }
        String url = matcher.group().trim();
        while (url.length() > 0 && ".,;:!?)]}，。；！？）】".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 抖音 App 自己的分享短链，原样保留最稳。 */
    static boolean isShareLink(String url) {
        if (url == null) {
            return false;
        }
        String value = url.toLowerCase(java.util.Locale.US);
        return value.startsWith("https://v.douyin.com/")
                || value.startsWith("http://v.douyin.com/")
                || value.contains("//v.douyin.com/")
                || value.contains("iesdouyin.com/share/");
    }

    /** 只保留 6~32 位纯数字的作品 id；别的（含空串）返回空。 */
    static String digits(String awemeId) {
        String value = awemeId == null ? "" : awemeId.trim();
        if (value.length() < 6 || value.length() > 32) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') {
                return "";
            }
        }
        return value;
    }

    private static String encode(String text) {
        try {
            String value = text == null ? "" : text.trim();
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception exc) {
            return "";
        }
    }
}
