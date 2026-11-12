package com.jingying.watch;

/**
 * 图集切图/预取的纯计算：循环索引、相邻预取目标、横向手势阈值。
 *
 * 只依赖 java.*，可以在桌面 JVM 上直接单测；FeedPage / FeedPager 都不再各自
 * 内联一套阈值和取模，避免「改了判定却没改预取」这类不一致。
 */
public final class AlbumNav {

    /** 拖动超过 8% 屏宽就切一张（原来要 12%，划起来明显迟钝）。 */
    private static final float DRAG_FRACTION = 0.08f;
    /** 抬手兜底：位移超过 5% 屏宽且横向速度超过 300px/s 也算一次快扫。 */
    private static final float FLING_FRACTION = 0.05f;
    private static final float FLING_MIN_VELOCITY = 300f;

    private AlbumNav() {
    }

    /** 循环推进索引，负数（上一张）也能正确处理；size 非法时返回 0。 */
    public static int wrap(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        int value = index % size;
        return value < 0 ? value + size : value;
    }

    /**
     * 预取前一张和后一张。只有一张时不预取；两张时前后是同一张，只返回一次。
     */
    public static int[] neighbors(int index, int size) {
        if (size <= 1) {
            return new int[0];
        }
        int current = wrap(index, size);
        int previous = wrap(current - 1, size);
        int next = wrap(current + 1, size);
        if (previous == next) {
            return new int[]{previous};
        }
        return new int[]{previous, next};
    }

    /** 拖动中是否已经达到切图位移。 */
    public static boolean dragCrossed(float dx, float width) {
        return width > 0f && Math.abs(dx) > width * DRAG_FRACTION;
    }

    /**
     * 抬手时补一次「快扫」：系统把 MOVE 合并掉、只剩 DOWN/UP 时，
     * 位移可能没到拖动阈值，但手指确实快速横划过，不该被完全吞掉。
     */
    public static boolean flingCrossed(float dx, float durationMs, float width) {
        if (width <= 0f || Math.abs(dx) <= width * FLING_FRACTION) {
            return false;
        }
        float velocity = dx / Math.max(1f, durationMs) * 1000f;
        return Math.abs(velocity) > FLING_MIN_VELOCITY;
    }

    /** 横向手势要压过纵向：斜着划时得比纵向多 15% 才认成切图，不是翻作品。 */
    private static final float HORIZONTAL_RATIO = 1.15f;

    /**
     * 抬手时根据整段位移判断该不该切图（+1 下一张 / -1 上一张 / 0 不切）。
     *
     * <p>存在的理由：MOVE 事件被系统合并时，{@code FeedPager} 里那套「越过阈值才
     * 进入横滑模式」的判定根本没机会跑，只剩 DOWN/UP 两个事件。以前这种情况会被
     * 当成一次点击（顺手把图集暂停了），画面还停在第 1 张——用户看到的就是
     * 「划了一下没反应 / 还是第一张」。这里用抬手位移 + 方向补一次。
     *
     * @param dx       抬手时相对按下点的横向位移（右正左负）
     * @param dy       纵向位移
     * @param duration 手势时长（ms），用来算速度，判断是不是快速轻扫
     * @param width    控件宽度（屏宽）
     */
    public static int swipeDirection(float dx, float dy, float duration, float width) {
        if (width <= 0f) {
            return 0;
        }
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        if (absX <= absY * HORIZONTAL_RATIO) {
            return 0;
        }
        // 位移够远（正常拖动）或者够快（快速轻扫）都算；两个都不满足才忽略
        if (absX <= width * DRAG_FRACTION && !flingCrossed(dx, duration, width)) {
            return 0;
        }
        return dx > 0 ? -1 : 1;
    }

    /**
     * 图集是否还该继续自动轮播。
     *
     * <p>最关键的一条是 {@code manual}：只要用户自己划过一次，这个作品就交给手动浏览，
     * 不再被 3.2 秒定时器带着走（否则刚看清一张就被换掉，等于手动操作白做）。
     * 换到别的作品重新 bind 时会清掉这个状态。
     *
     * @param manual      这个作品是否已被用户手动切过图
     * @param paused      是否被用户点按暂停
     * @param imageCount  图片数量（只有一张时没什么可轮播的）
     * @param activePage  是否是当前正在显示的那一屏
     * @param hostResumed 宿主是否在前台
     */
    public static boolean shouldAutoAdvance(boolean manual, boolean paused, int imageCount,
                                            boolean activePage, boolean hostResumed) {
        return !manual && !paused && imageCount > 1 && activePage && hostResumed;
    }
}
