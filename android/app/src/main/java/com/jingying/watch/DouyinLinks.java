package com.jingying.watch;

import java.net.URLEncoder;

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

    private static String encode(String text) {
        try {
            String value = text == null ? "" : text.trim();
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception exc) {
            return "";
        }
    }
}
