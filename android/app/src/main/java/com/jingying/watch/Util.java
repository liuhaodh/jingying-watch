package com.jingying.watch;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 一些到处都要用的小工具。 */
public final class Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 1.6 万作品的片库会给每个作品算一次 key，MessageDigest 复用比每次 getInstance 快得多。
     * MessageDigest 不是线程安全的，所以按线程各持一份。
     */
    private static final ThreadLocal<MessageDigest> SHA1 = new ThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-1");
            } catch (Exception exc) {
                return null;
            }
        }
    };

    private static final ThreadLocal<SimpleDateFormat> DATE_TIME =
            formatter("yyyy-MM-dd HH:mm", Locale.CHINA);
    private static final ThreadLocal<SimpleDateFormat> DATE_ONLY =
            formatter("yyyy-MM-dd", Locale.CHINA);

    private Util() {
    }

    private static ThreadLocal<SimpleDateFormat> formatter(final String pattern, final Locale locale) {
        return new ThreadLocal<SimpleDateFormat>() {
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat(pattern, locale);
            }
        };
    }

    public static String sha1Hex(String text) {
        MessageDigest digest = SHA1.get();
        if (digest == null) {
            return Integer.toHexString(String.valueOf(text).hashCode());
        }
        try {
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes("UTF-8"));
            char[] out = new char[bytes.length * 2];
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xff;
                out[index * 2] = HEX[value >>> 4];
                out[index * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(out);
        } catch (Exception exc) {
            return Integer.toHexString(String.valueOf(text).hashCode());
        }
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 1234 → 1.2 GB；手机上下载体积这种写法最好读。 */
    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024.0;
            index++;
        }
        if (index == 0) {
            return String.format(Locale.CHINA, "%d %s", (long) value, units[index]);
        }
        return String.format(Locale.CHINA, "%.1f %s", value, units[index]);
    }

    public static String humanTime(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        return DATE_TIME.get().format(new Date(epochSeconds * 1000L));
    }

    public static String relativeTime(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "";
        }
        long delta = nowSeconds() - epochSeconds;
        if (delta < 60) {
            return "刚刚";
        }
        if (delta < 3600) {
            return (delta / 60) + " 分钟前";
        }
        if (delta < 86400) {
            return (delta / 3600) + " 小时前";
        }
        if (delta < 86400L * 7) {
            return (delta / 86400) + " 天前";
        }
        return DATE_ONLY.get().format(new Date(epochSeconds * 1000L));
    }

    public static String formatDuration(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        if (total >= 3600) {
            return String.format(Locale.CHINA, "%d:%02d:%02d",
                    total / 3600, (total % 3600) / 60, total % 60);
        }
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    /** 点赞/收藏数：1.2w、3.4k，抖音那种写法。 */
    public static String compactCount(int count) {
        if (count <= 0) {
            return "";
        }
        if (count < 10000) {
            return String.valueOf(count);
        }
        return String.format(Locale.CHINA, "%.1fw", count / 10000.0);
    }
}
