package com.jingying.watch;

/**
 * 首页搜索框的输入节奏（纯 Java，可单测）。
 *
 * <p>三个坑：</p>
 * <ul>
 *   <li><b>拼音还没上屏时不能筛</b>：中文输入法在选词前把拼音字母写进输入框
 *       （composing 状态），这时文本是 {@code "luying"} 这种字母串，
 *       拿它去筛列表结果一定不对；删中文字时也会先退成拼音，
 *       列表跟着一会儿「没有匹配」一会儿又跳回来，看起来就是「删字不正常」。</li>
 *   <li><b>删空要立刻生效</b>：删空还走 260ms 防抖，用户会觉得列表没恢复。</li>
 *   <li><b>关键词没变不用重建</b>：只敲了空格又删掉、拼音来回改，都不该重排 1.6 万条。</li>
 * </ul>
 */
public final class SearchNav {

    /** 正常输入的防抖。 */
    public static final long DEBOUNCE_MS = 260L;
    /** 还在组合输入时的再等时间。 */
    public static final long COMPOSING_MS = 120L;
    /** 最多为组合输入等这么多次（约 0.7 秒），免得输入法一直不提交就永远不筛。 */
    public static final int MAX_COMPOSING_WAITS = 6;

    /** 去掉首尾空格；null 当空串。 */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 还在组合输入、且没超过等待上限：这次先不筛，稍后再来。 */
    public static boolean waitForComposing(boolean composing, int waits) {
        return composing && waits < MAX_COMPOSING_WAITS;
    }

    /** 这次文本变化该等多久再筛；0 表示立刻（删空 / 点清除按钮）。 */
    public static long delayMs(String text, boolean waitMore) {
        if (waitMore) {
            return COMPOSING_MS;
        }
        return normalize(text).isEmpty() ? 0L : DEBOUNCE_MS;
    }

    /** 关键词真的变了才需要重建列表（只差首尾空格不算）。 */
    public static boolean needsRebuild(String applied, String next) {
        return !normalize(applied).equals(normalize(next));
    }
}
