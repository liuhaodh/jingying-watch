package com.jingying.watch;

/**
 * 长按 2× 倍速的策略：怎么设、没生效怎么发现、还不生效怎么兜底。
 *
 * <p>只做决策不碰 MediaPlayer，所以能在桌面 JVM 上单测。</p>
 *
 * <p>两条路：</p>
 * <ol>
 *   <li><b>参数变速</b>：{@code MediaPlayer.setPlaybackParams(speed=2)}。真正的时间伸缩，
 *       音画都变，最理想。但它会抛异常，而抛异常并不等于「这台机器不支持倍速」——
 *       没 Surface、正在 prepare/缓冲、pitch 不被接受、视频没音轨都会抛，
 *       所以按「换一种写法 + 逐步拉长间隔」重试。</li>
 *   <li><b>跳帧兜底</b>：有些机型参数不抛异常、{@code getPlaybackParams()} 也老实回报 2.0，
 *       可视频轨根本没变速（用户看到的就是「提示 2×，画面还是原速」）。光看返回值发现不了，
 *       只能设完等 700ms 拿播放位置自己算推进比；确认没加速就改成「定时往前 seek」，
 *       播放器本身还在 1× 走，每隔一段补一段差值，叠加起来就是 2×。笨，但任何设备都有效。</li>
 * </ol>
 */
public final class SpeedNav {

    /** 长按时的目标倍速。 */
    public static final float BOOST = 2f;
    /** 连续失败这么多次才认定「不支持」。配合下面的间隔，一共约 6 秒。 */
    public static final int MAX_FAILURES = 6;
    /** 参数写法一共三种，见 {@link #variant()}。 */
    public static final int VARIANT_COUNT = 3;

    /** 还没定：正在试 {@code setPlaybackParams}。 */
    public static final int MODE_PARAMS = 1;
    /** 引擎不认变速参数，改用「定时 seek 往前跳」实现倍速。 */
    public static final int MODE_SEEK = 2;

    /**
     * 参数只被「接受」却没真执行时，试两次就转跳帧——不能像抛异常那样等满 6 次，
     * 否则用户长按 6 秒还是原速，等于没修。
     */
    public static final int SEEK_AFTER_FAILURES = 2;

    /** 设完参数等这么久再看进度条走得多快。太短会把刚起播的抖动误判成「没加速」。 */
    public static final long PROBE_DELAY_MS = 700L;
    /**
     * 实测推进比低于这个值就认定参数被忽略。
     * 2× 正常应接近 2.0；取 1.4 留出解码抖动和 getCurrentPosition 取整的余量，
     * 又足以和「1× 正常播放」区分开。
     */
    public static final float PROBE_MIN_RATIO = 1.4f;

    /**
     * 跳帧的节流间隔。进度条是 220ms 刷一次，这里取 300 而不是贴着 220：
     * 跳一次就要重新定位关键帧、重新填缓冲，太密必然黑闪；300ms 已经能看出加速。
     */
    public static final long SEEK_INTERVAL_MS = 300L;
    /** 目标时间轴只领先当前不到这么多就不值得 seek（seek 本身也要几十毫秒）。 */
    public static final long SEEK_MIN_LEAD_MS = 200L;
    /** 留出尾部余量，别把自己 seek 到「播完」上去，否则长按中会跳下一条。 */
    public static final long SEEK_TAIL_MARGIN_MS = 500L;
    /**
     * seek 落点比目标靠前多少才算「没落到」。
     *
     * <p>默认的 {@code seekTo(int)} 是「跳到前一个关键帧」，抖音短视频的 GOP 常有 1～4 秒，
     * 所以每次跳都可能退回好几秒、再往前播——表现就是画面反复倒退、一顿一顿。
     * 靠这个容差发现落点不对，重新以实际位置为基准，避免越追越远。</p>
     */
    public static final long LANDING_TOLERANCE_MS = 400L;

    private int variant;
    private int failures;
    private boolean applied;
    private long lastAttemptAt = Long.MIN_VALUE / 2;

    /** 0 未定 / {@link #MODE_PARAMS} / {@link #MODE_SEEK}。 */
    private int mode;
    private long probeAt = Long.MIN_VALUE / 2;
    private long probePos;
    /** 第一次实测觉得没加速，等第二轮确认。 */
    private boolean suspected;
    private long seekBaseAt = Long.MIN_VALUE / 2;
    private long seekBasePos;
    private long lastSeekAt = Long.MIN_VALUE / 2;
    /** 上一次 seek 的目标位置，用来判断落点有没有跑到目标前面去。 */
    private long lastTargetMs = -1L;

    /** 新的一次长按 / 换了播放器：全部清零，从头再试。 */
    public void reset() {
        variant = 0;
        failures = 0;
        applied = false;
        lastAttemptAt = Long.MIN_VALUE / 2;
        mode = 0;
        probeAt = Long.MIN_VALUE / 2;
        probePos = 0;
        suspected = false;
        seekBaseAt = Long.MIN_VALUE / 2;
        seekBasePos = 0;
        lastSeekAt = Long.MIN_VALUE / 2;
        lastTargetMs = -1L;
    }

    /**
     * 播放状态变了（缓冲结束、Surface 就绪、重新起播）：清掉失败计数重新给机会，
     * 但保留已经试出来的参数写法——新播放器通常还是同一个引擎，没必要从最差的那种写法重来。
     */
    public void restart() {
        failures = 0;
        applied = false;
        lastAttemptAt = Long.MIN_VALUE / 2;
        probeAt = Long.MIN_VALUE / 2;
        suspected = false;
        // 已经确认要走跳帧的，别因为缓冲结束又退回参数写法白试一轮；
        // 而且跳帧期间的 BUFFERING_END 多半是我们自己 seek 触发的，
        // 这时候清基准会让每一跳都从当前位置重新起算，跳幅忽大忽小。
        if (mode != MODE_SEEK) {
            lastSeekAt = Long.MIN_VALUE / 2;
            seekBaseAt = Long.MIN_VALUE / 2;
            lastTargetMs = -1L;
        }
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

    /** 现在能不能再试：已经放弃、已经改走跳帧、或者距上次尝试还不够一个退避间隔时都不试。 */
    public boolean shouldAttempt(long nowMs) {
        if (unsupported() || mode == MODE_SEEK) {
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
        mode = MODE_PARAMS;
    }

    /**
     * 参数被接受：记下这一刻的播放位置，等 {@link #PROBE_DELAY_MS} 后实测到底有没有加速。
     *
     * <p>这是「提示说 2× 但画面还是原速」的关键——很多机型 {@code setPlaybackParams}
     * 不抛异常、{@code getPlaybackParams()} 回报 2.0，但视频轨根本没变速。
     * 光看返回值是发现不了的，只能拿播放位置自己算。</p>
     */
    public void onParamsApplied(long nowMs, long positionMs) {
        onSuccess();
        probeAt = nowMs;
        probePos = positionMs;
        suspected = false;
    }

    /** 每次失败换一种写法，别拿同一套参数反复撞墙。 */
    public void onFailure() {
        applied = false;
        failures++;
        if (variant < VARIANT_COUNT - 1) {
            variant++;
        }
    }

    // ---------- 实测与跳帧 ----------

    public int mode() {
        return mode;
    }

    public boolean inSeek() {
        return mode == MODE_SEEK;
    }

    /** 参数写法连续失败到阈值：该换跳帧了。 */
    public boolean wantsSeek() {
        return mode != MODE_SEEK && failures >= SEEK_AFTER_FAILURES;
    }

    /** 到点该实测了。 */
    public boolean shouldProbe(long nowMs) {
        return mode == MODE_PARAMS && applied && nowMs - probeAt >= PROBE_DELAY_MS;
    }

    /** 实测结论：这么久只走了这么点，说明参数被引擎忽略了（只判一次，调用方随即转跳帧）。 */
    public boolean probeIgnored(long nowMs, long positionMs) {
        long elapsed = nowMs - probeAt;
        if (elapsed < PROBE_DELAY_MS) {
            return false;
        }
        long advanced = positionMs - probePos;
        if (advanced < 0) {
            // 期间用户自己拖过进度条，基准作废，别误判
            return false;
        }
        return advanced < (long) (elapsed * PROBE_MIN_RATIO);
    }

    /**
     * 第一次疑似没加速：重设采样基准，隔 {@link #PROBE_DELAY_MS} 再测一轮。
     * 返回 true 表示已经两轮都对不上，可以下结论去跳帧了。
     *
     * <p>为什么不一次定性：{@code getCurrentPosition()} 不是每台设备都实时
     * （有的几百毫秒才更新一次），单次采样很容易把「位置还没来得及刷新」当成「没加速」。
     * 误判代价很大——明明能平滑 2× 的设备会被切成跳帧，画面立刻变卡。</p>
     */
    public boolean suspect(long nowMs, long positionMs) {
        if (!suspected) {
            suspected = true;
            probeAt = nowMs;
            probePos = positionMs;
            return false;
        }
        return true;
    }

    public boolean suspected() {
        return suspected;
    }

    /**
     * 开始跳帧：以当前时刻 / 位置为基准，之后按目标时间轴追赶。
     */
    public void beginSeek(long nowMs, long positionMs) {
        mode = MODE_SEEK;
        applied = false;
        seekBaseAt = nowMs;
        seekBasePos = Math.max(0L, positionMs);
        lastSeekAt = Long.MIN_VALUE / 2;
        lastTargetMs = -1L;
        probeAt = Long.MIN_VALUE / 2;
        suspected = false;
    }

    /** {@link #restart()} 之后基准会作废，调用方要先重新 {@link #beginSeek}。 */
    public boolean hasSeekBase() {
        return seekBaseAt != Long.MIN_VALUE / 2;
    }

    /**
     * 这一次要不要 seek、跳到哪：
     * 目标 = 长按起点 + 已经过去的时间 × 2，追不上就返回 -1（不用 seek）。
     *
     * <p>播放器自己还在 1× 往前走，所以每隔一段时间补一段差值，叠加起来就是 2×。</p>
     */
    public long seekTarget(long nowMs, long positionMs, long durationMs) {
        if (mode != MODE_SEEK || seekBaseAt == Long.MIN_VALUE / 2 || durationMs <= 0) {
            // 时长未知时不敢跳：一跳过头就直接播完跳下一条了
            return -1;
        }
        if (nowMs - lastSeekAt < SEEK_INTERVAL_MS) {
            return -1;
        }
        long target = seekBasePos + (long) ((nowMs - seekBaseAt) * BOOST);
        long limit = durationMs - SEEK_TAIL_MARGIN_MS;
        if (limit <= seekBasePos) {
            return -1;
        }
        if (target > limit) {
            target = limit;
        }
        if (target - positionMs < SEEK_MIN_LEAD_MS) {
            return -1;
        }
        return target;
    }

    /** seek 真的发出去了才记时间，失败的下一轮还能再试。 */
    public void markSeek(long nowMs) {
        lastSeekAt = nowMs;
    }

    /** 同上，顺便记下目标位置，用来判断落点。 */
    public void markSeek(long nowMs, long targetMs) {
        lastSeekAt = nowMs;
        lastTargetMs = targetMs;
    }

    /**
     * 上一次 seek 是不是没落到目标附近（落到了更前面的关键帧）。
     * 是的话说明再按原来的基准追只会越追越偏，得重新取基准。
     */
    public boolean landedShort(long positionMs) {
        return lastTargetMs >= 0 && positionMs < lastTargetMs - LANDING_TOLERANCE_MS;
    }

    public long lastTarget() {
        return lastTargetMs;
    }

    /** API 太低 / 明确拿不到变速能力：直接判不支持，不用再试。 */
    public void giveUp() {
        applied = false;
        failures = MAX_FAILURES;
    }

    /**
     * 长按提示里的文案。只有两条路都不行才说不支持；
     * 跳帧和参数变速分开写，方便一眼看出这次到底走的哪条路。
     */
    public String label() {
        if (unsupported()) {
            return "本机不支持倍速";
        }
        return mode == MODE_SEEK ? "2× 快进" : "2× 播放中";
    }
}
