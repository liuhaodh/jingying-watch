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
}
