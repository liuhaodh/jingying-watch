package com.jingying.watch;

/**
 * 一个作品播完之后该去哪儿（纯 Java，可在桌面 JVM 单测）。
 *
 * <p>规则只有三条，但顺序不能反：
 * <ol>
 *   <li>用户刚滑过 / 正在滑：什么都不做。上一条刚好播完时如果立刻跳走，
 *       会把用户正在看的这一条顶掉，是以前真实出现过的毛病。</li>
 *   <li>开了自动连播、并且还有下一条：跳到下一条。</li>
 *   <li>其余情况（关了连播，或者已经是最后一条）：停在结尾等用户重播。</li>
 * </ol>
 */
public final class PlayNav {

    /** 刚滑过的宽限时间：抬手后这段时间内播完，不自动跳走。 */
    public static final long TOUCH_GRACE_MS = 900L;

    /** 刚滑过（或正在滑），保持现状。 */
    public static final int IGNORE = 0;
    /** 自动连播到下一个作品。 */
    public static final int NEXT = 1;
    /** 停在结尾，等用户点一下重播。 */
    public static final int REPLAY = 2;

    private PlayNav() {
    }

    /**
     * @param autoNext          设置里是否开启「播放结束自动连播」
     * @param hasNext           后面还有没有作品
     * @param sinceLastTouchMs  距离上次触摸过去了多少毫秒（时钟回退导致的负值按「刚滑过」处理）
     */
    public static int afterEnded(boolean autoNext, boolean hasNext, long sinceLastTouchMs) {
        if (sinceLastTouchMs < TOUCH_GRACE_MS) {
            return IGNORE;
        }
        return autoNext && hasNext ? NEXT : REPLAY;
    }
}
