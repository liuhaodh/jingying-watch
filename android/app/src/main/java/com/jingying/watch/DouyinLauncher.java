package com.jingying.watch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 拉起官方抖音打开作者主页。
 *
 * 关键：`snssdk1128://user/profile/` 要的是数字 UID，authors.json 里只有 sec_uid，
 * 把 sec_uid 塞进去会「抖音被拉起、主页一片空白」。所以主路径走官方网页链接
 * `https://www.douyin.com/user/<sec_uid>`（sec_uid 只有网页链接能正确解析）。
 *
 * https 链接不能裸发给系统，否则浏览器会抢先接走（很多 ROM 上抖音的 App Links
 * 没被设为默认处理器），表现就是「先跳浏览器再跳抖音」。所以先 `setPackage()` 点名
 * 让抖音接，失败才逐级回退到系统派发 / 搜索 / 网页版。
 */
public final class DouyinLauncher {

    private static final String DOUYIN = "com.ss.android.ugc.aweme";
    private static final String DOUYIN_LITE = "com.ss.android.ugc.aweme.lite";

    private DouyinLauncher() {
    }

    /**
     * @param authorName 作者昵称，只在既没有 sec_uid 也没有抖音号时用来搜
     * @param uniqueId   抖音号（纯数字时也可当 UID 试）
     * @param secUid     抖音主页 ID（authors.json 里的 sec_uid），可空
     */
    public static void openAuthor(Activity activity, String authorName,
                                  String uniqueId, String secUid) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String sec = trim(secUid);
        String uid = trim(uniqueId);
        if (!sec.isEmpty()) {
            String profile = DouyinLinks.profileWeb(sec);
            // 先点名让抖音自己接，避免浏览器抢先接走 https 链接
            if (openIn(activity, profile, DOUYIN)) {
                return;
            }
            if (openIn(activity, profile, DOUYIN_LITE)) {
                return;
            }
            // 抖音版本较老、没声明 https 路由时，用 scheme 再试一次
            if (isDigits(uid)) {
                String scheme = DouyinLinks.profileScheme(uid);
                if (openIn(activity, scheme, DOUYIN) || openIn(activity, scheme, DOUYIN_LITE)) {
                    return;
                }
            }
            // 最后才交给系统派发：可能是浏览器或应用选择器
            if (open(activity, profile)) {
                return;
            }
        }
        if (isDigits(uid) && open(activity, DouyinLinks.profileScheme(uid))) {
            return;
        }
        String keyword = uid.isEmpty() ? trim(authorName) : uid;
        if (!keyword.isEmpty()) {
            String search = DouyinLinks.searchScheme(keyword);
            if (openIn(activity, search, DOUYIN) || openIn(activity, search, DOUYIN_LITE)) {
                return;
            }
            if (open(activity, search)) {
                return;
            }
            if (open(activity, DouyinLinks.searchWeb(keyword))) {
                return;
            }
        }
        Ui.toast(activity, "没有找到这位作者的抖音主页");
    }

    /** 明确按抖音号/昵称搜索（主页打不开时的兜底）。 */
    public static void openSearch(Activity activity, String authorName, String uniqueId) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String keyword = trim(uniqueId);
        if (keyword.isEmpty()) {
            keyword = trim(authorName);
        }
        if (keyword.isEmpty()) {
            Ui.toast(activity, "这位作者没有抖音号，无法搜索");
            return;
        }
        if (openIn(activity, DouyinLinks.searchScheme(keyword), DOUYIN)
                || openIn(activity, DouyinLinks.searchScheme(keyword), DOUYIN_LITE)) {
            return;
        }
        if (open(activity, DouyinLinks.searchScheme(keyword))) {
            return;
        }
        if (open(activity, DouyinLinks.searchWeb(keyword))) {
            return;
        }
        Ui.toast(activity, "无法打开抖音搜索");
    }

    /**
     * 在抖音里打开这个作品。
     *
     * 先走 `snssdk1128://aweme/detail/<作品id>`：抖音 App 直接进作品页，
     * 不经过浏览器（网页链接在未登录浏览器里常常只显示「打开抖音观看」，
     * 看起来就是「链接打不开」）。取不到作品 id 或 scheme 没接住时，
     * 再点名抖音包名打开网页链接，最后才交给系统。
     */
    public static void openWork(Activity activity, String awemeId, String shareUrl,
                                boolean album) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String id = DouyinLinks.digits(awemeId);
        String url = DouyinLinks.workUrl(id, album, shareUrl);
        if (!id.isEmpty()) {
            String scheme = DouyinLinks.workScheme(id);
            if (openIn(activity, scheme, DOUYIN) || openIn(activity, scheme, DOUYIN_LITE)) {
                return;
            }
        }
        if (!url.isEmpty()) {
            if (openIn(activity, url, DOUYIN) || openIn(activity, url, DOUYIN_LITE)) {
                return;
            }
            if (open(activity, url)) {
                return;
            }
        }
        Ui.toast(activity, "这个作品没有可用的抖音链接");
    }

    /** 复制主页链接：sec_uid 无效时退成搜索链接，方便手动粘到抖音或浏览器。 */
    public static void copyLink(Activity activity, String authorName,
                                String uniqueId, String secUid) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String sec = trim(secUid);
        String keyword = trim(uniqueId);
        if (keyword.isEmpty()) {
            keyword = trim(authorName);
        }
        String url = sec.isEmpty()
                ? DouyinLinks.searchWeb(keyword)
                : DouyinLinks.profileWeb(sec);
        try {
            ClipboardManager manager = (ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("抖音主页", url));
                Ui.toast(activity, "链接已复制：" + url);
                return;
            }
        } catch (Exception ignored) {
        }
        Ui.toast(activity, "复制失败");
    }

    private static boolean open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (Exception exc) {
            return false;
        }
    }

    /** 显式指定包名：只让抖音接这个 Intent，浏览器就没机会抢先了。 */
    private static boolean openIn(Activity activity, String url, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(packageName);
            activity.startActivity(intent);
            return true;
        } catch (Exception exc) {
            return false;
        }
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
