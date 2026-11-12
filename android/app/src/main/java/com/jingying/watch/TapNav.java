package com.jingying.watch;

/**
 * 点击判定的纯计算：双击窗口、单击延迟，以及「这一下是不是双击的第二下」。
 *
 * <p>只依赖 java.*，可以在桌面 JVM 上单测。FeedPager 不再自己散落一组时间常量，
 * 「延迟必须比窗口长」这条不变式也能用测试钉住。</p>
 */
public final class TapNav {

    /**
     * 双击窗口：从上一次抬手到这一次按下。系统 ViewConfiguration 的双击窗口是 300ms，
     * 这里取 260ms，单击暂停不至于明显发钝。注意判定基准是 DOWN，不是 UP：
     * 以前在 UP 判定，等于额外吃掉一个按压时长（~60~100ms），双击经常判不出来。
     */
    public static final int DOUBLE_TAP_WINDOW_MS = 260;

    /**
     * 单击延迟：必须**严格大于**双击窗口。
     * 只要第二下在窗口内按下，待执行的单击就一定还没到点，取消得掉；
     * 否则「第一下已经暂停、第二下才认出是双击」的竞态无法消除。
     */
    public static final int SINGLE_TAP_DELAY_MS = 280;

    private TapNav() {
    }

    /**
     * 是否把这次按下当成双击的第二下。
     *
     * @param pendingSingleTap 上一击的单击还在等双击窗口（已经执行过就不算）
     * @param now              本次 ACTION_DOWN 的 eventTime
     * @param lastTapTime      上一击 ACTION_UP 的 eventTime
     * @param slop             两次按下的最大位移（ViewConfiguration 的 doubleTapSlop）
     */
    public static boolean isDoubleTap(boolean pendingSingleTap,
                                      float x, float y, float lastX, float lastY,
                                      long now, long lastTapTime, int slop) {
        if (!pendingSingleTap) {
            return false;
        }
        if (Math.abs(x - lastX) > slop || Math.abs(y - lastY) > slop) {
            return false;
        }
        long delta = now - lastTapTime;
        return delta >= 0 && delta <= DOUBLE_TAP_WINDOW_MS;
    }
}
