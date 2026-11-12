package com.jingying.watch;

/**
 * 长按 2× 倍速的重试策略。
 *
 * <p>只负责「什么时候还能再试、下一次换哪种写法、什么时候才算真的不支持」，
 * 不碰 MediaPlayer，所以能在桌面 JVM 上单测。</p>
 *
 * <p>为什么需要它：{@code MediaPlayer.setPlaybackParams()} 抛异常并不等于
 * 「这台机器不支持倍速」。下面这些情况都会抛，但都是暂时的、或者换个写法就能过：</p>
 * <ul>
 *   <li>视频轨还没有 Surface（页面刚进来 / TextureView 被回收）——
 *       渲染器为空时 NuPlayer 直接返回 INVALID_OPERATION；</li>
 *   <li>正在 prepare、正在缓冲、刚 seek 完；</li>
 *   <li>参数里带了设备不认的 pitch / fallback 模式；</li>
 *   <li>视频本身没有音轨（时间是靠音频轨做伸缩的）。</li>
 * </ul>
 *
 * <p>所以这里按「换一种参数写法 + 逐步拉长间隔」重试，连续失败到上限才下结论。</p>
 */
public final class SpeedNav {

    /** 长按时的目标倍速。 */
    public static final float BOOST = 2f;
    /** 连续失败这么多次才认定「不支持」。配合下面的间隔，一共约 6 秒。 */
    public static final int MAX_FAILURES = 6;
    /** 参数写法一共三种，见 {@link #variant()}。 */
    public static final int VARIANT_COUNT = 3;

    private int variant;
    private int failures;
    private boolean applied;
    private long lastAttemptAt = Long.MIN_VALUE / 2;

    /** 新的一次长按 / 换了播放器：全部清零，从头再试。 */
    public void reset() {
        variant = 0;
        failures = 0;
        applied = false;
        lastAttemptAt = Long.MIN_VALUE / 2;
    }

    /**
     * 播放状态变了（缓冲结束、Surface 就绪、重新起播）：清掉失败计数重新给机会，
     * 但保留已经试出来的参数写法——新播放器通常还是同一个引擎，没必要从最差的那种写法重来。
     */
    public void restart() {
        failures = 0;
        applied = false;
        lastAttemptAt = Long.MIN_VALUE / 2;
    }

    /** 这一次该用第几种参数写法：0 带 pitch / 1 只给 speed / 2 沿用播放器当前参数。 */
    public int variant() {
        return variant;
    }

    public int failures() {
        return failures;
    }

    public boolean applied() {
        return applied;
    }

    /** 真的试到上限了才叫不支持。 */
    public boolean unsupported() {
        return !applied && failures >= MAX_FAILURES;
    }

    /** 现在能不能再试：已经放弃、或者距上次尝试还不够一个退避间隔时都不试。 */
    public boolean shouldAttempt(long nowMs) {
        if (unsupported()) {
            return false;
        }
        return nowMs - lastAttemptAt >= retryDelayMs();
    }

    public void markAttempt(long nowMs) {
        lastAttemptAt = nowMs;
    }

    /** 第 n 次失败之后等多久再试：300 / 600 / 900 … 最多 1800ms。 */
    public long retryDelayMs() {
        return Math.min(1800L, 300L + 300L * failures);
    }

    public void onSuccess() {
        applied = true;
        failures = 0;
    }

    /** 每次失败换一种写法，别拿同一套参数反复撞墙。 */
    public void onFailure() {
        applied = false;
        failures++;
        if (variant < VARIANT_COUNT - 1) {
            variant++;
        }
    }

    /** API 太低 / 明确拿不到变速能力：直接判不支持，不用再试。 */
    public void giveUp() {
        applied = false;
        failures = MAX_FAILURES;
    }

    /** 长按提示里的文案。只有真试到上限才说不支持。 */
    public String label() {
        return unsupported() ? "本机不支持倍速" : "2× 播放中";
    }
}
