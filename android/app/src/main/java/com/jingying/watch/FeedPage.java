package com.jingying.watch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * 一屏作品：竖屏视频（TextureView + MediaPlayer）或图集，
 * 底部作者/文案/音乐/进度，右侧收藏分享详情，布局对齐抖音。
 */
public final class FeedPage extends FrameLayout {

    public static final int HIT_NONE = 0;
    public static final int HIT_BACK = 1;
    public static final int HIT_MUTE = 2;
    public static final int HIT_DETAIL = 4;
    public static final int HIT_SHARE = 6;
    public static final int HIT_AVATAR = 7;
    public static final int HIT_PROGRESS = 8;
    public static final int HIT_DOWNLOAD = 9;
    public static final int HIT_AUTO_DOWNLOAD = 10;
    public static final int HIT_FAVORITE = 11;

    public interface Listener {
        void onCompleted(FeedPage page);

        void onHit(FeedPage page, int target, float x, float y);

        /** 点击文案里的 #话题 / @用户。 */
        void onToken(FeedPage page, String token);
    }

    private static final long ALBUM_INTERVAL = 3200L;
    /** 图集大图读失败后的重试次数（退避 400/800/1200ms）。 */
    private static final int ALBUM_LOAD_RETRIES = 3;
    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("[#@][\\p{L}\\p{N}_\\u4e00-\\u9fa5]+");

    private final App app;
    private final ImageLoader images;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextureView texture;
    private ImageView backdrop;
    private ImageView cover;
    private Surface surface;
    private SurfaceTexture surfaceTexture;

    private TextView authorText;
    private TextView avatarText;
    private ImageView avatarImage;
    private TextView captionText;
    private TextView publishText;
    private final java.util.List<String> captionTokens = new java.util.ArrayList<>();
    private final java.util.List<int[]> captionRanges = new java.util.ArrayList<>();
    private TextView musicText;
    private TextView counterText;
    private TextView errorText;
    private TextView playGlyph;
    private TextView timePill;
    private TextView progressTime;
    private TextView autoLabel;
    private TextView albumBadge;

    private ImageView backIcon;
    private ImageView muteIcon;
    private ImageView downloadIcon;
    private TextView downloadPercent;
    private ImageView autoIcon;
    private ImageView detailIcon;
    private ImageView shareIcon;
    private ImageView favoriteIcon;
    private TextView favoriteLabel;
    private HeartBurstView heartBurst;
    private ImageView playIcon;
    private ImageView musicIcon;
    private LoadingDotsView spinner;
    private ProgressLineView progressLine;

    private LinearLayout rail;
    private LinearLayout bottomBlock;
    private LinearLayout avatarBox;

    private MediaPlayer player;
    private MediaPlayer musicPlayer;
    /**
     * 图集里「实况 / 动图」用的播放器：电脑版把动图存成 `动图_01.mp4`，
     * 和 `图片_01.jpg` 配对。复用同一个 TextureView，画面出来后再把静态图淡出。
     * 单独一个播放器（不复用 player）是为了不搅乱视频作品的 prepared / playing / 进度状态。
     */
    private MediaPlayer livePlayer;
    private boolean livePrepared;
    /** 动图首帧已经盖住静态图（此时 cover 是 GONE）。 */
    private boolean liveShown;
    private int liveWidth;
    private int liveHeight;
    private int liveRevealAttempts;
    /**
     * 首帧不一定有 MEDIA_INFO_VIDEO_RENDERING_START 回调（部分 ROM 就不发），
     * 所以轮询播放位置：真的开始出画面了再把静态图淡出，避免揭开一层黑屏。
     */
    private final Runnable liveReveal = new Runnable() {
        @Override
        public void run() {
            if (liveShown || livePlayer == null || pageDestroyed) {
                return;
            }
            long position = -1L;
            boolean running = false;
            try {
                position = livePlayer.getCurrentPosition();
                running = livePlayer.isPlaying();
            } catch (Exception ignored) {
            }
            if (position > 0 && running) {
                showAlbumLiveFrame();
                return;
            }
            if (liveRevealAttempts < 8) {
                liveRevealAttempts++;
                handler.postDelayed(this, 250L);
            }
        }
    };
    /** 作品包里的成员解到本地后的路径（同一个作品复用，切走不删缓存）。 */
    private java.io.File archiveVideo;
    private java.io.File archiveMusic;
    private boolean archiveVideoLoading;
    private boolean archiveMusicLoading;
    /** 作品包解包偶发失败时给音乐两次重试机会，别让一次抖动整条没声音。 */
    private int archiveMusicRetries;
    private final Runnable musicRetry = this::prepareMusic;
    /** 一个作品在一屏里只主动查一次独立音乐目录，避免轮播/回调重复 PROPFIND。 */
    private boolean musicLookupDone;
    /** 页面已经 destroy() 后，解包回调用不着再起播放器。 */
    private boolean pageDestroyed;
    private boolean musicPrepared;
    private boolean visualReady;
    private boolean albumPaused;
    /**
     * 用户已经手动切过图：这个作品从此交给手动浏览，不再自动轮播。
     * 换作品重新 bind 时清掉，所以只影响「被手动接管的那一个作品」。
     */
    private boolean albumManual;
    private final Runnable visualFallback = () -> {
        visualReady = true;
        startMusicIfReady();
    };
    private Work work;
    private int index;
    private int role;
    private int albumIndex;
    /** 每次切图 +1：旧的加载 / 重试回调靠它对不上号就丢弃，不会把上一张画回来。 */
    private int albumLoadToken;
    private String counterSuffix = "";
    private boolean prepared;
    private boolean playing;
    private boolean userPaused;
    private boolean muted;
    private boolean detailsRequested;
    private boolean settingUp;
    private boolean coverHidden;
    private boolean speedBoost;
    /** 倍速的重试策略（写法降级 + 退避 + 什么时候才叫不支持）。 */
    private final SpeedNav speedNav = new SpeedNav();
    /** 为倍速重建播放器后要回到的位置，-1 表示不用回。 */
    private long resumeAtMs = -1L;
    private boolean previewMode;
    private boolean hostResumed = true;
    private int videoWidth;
    private int videoHeight;
    private int playAttempts;

    private Listener listener;

    public FeedPage(Context context) {
        super(context);
        app = (App) context.getApplicationContext();
        images = app.images;
        prefs = app.prefs;
        muted = prefs.muted();
        build();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---------- 视图 ----------
    private void build() {
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);

        texture = new TextureView(getContext());
        texture.setOpaque(true);
        addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surfaceTexture = st;
                surface = new Surface(st);
                if (player != null) {
                    player.setSurface(surface);
                }
                if (livePlayer != null) {
                    livePlayer.setSurface(surface);
                }
                if (player != null && speedBoost) {
                    // 没有 Surface 时视频轨没有渲染器，设倍速必定失败，之前都是直接跳过；
                    // Surface 一就绪立刻补上，别让长按白按
                    speedNav.restart();
                    applySpeed(SpeedNav.BOOST);
                }
                applyVideoTransform();
                applyLiveTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                applyVideoTransform();
                applyLiveTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (player != null) {
                    try {
                        player.setSurface(null);
                    } catch (Exception ignored) {
                    }
                }
                if (livePlayer != null) {
                    try {
                        livePlayer.setSurface(null);
                    } catch (Exception ignored) {
                    }
                }
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                surfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        backdrop = new ImageView(getContext());
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setVisibility(GONE);
        addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cover = new ImageView(getContext());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.BLACK);
        addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View topScrim = new View(getContext());
        topScrim.setBackground(Ui.verticalGradient(getContext(), 0xB3000000, 0x00000000));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 150));
        topParams.gravity = Gravity.TOP;
        addView(topScrim, topParams);

        View bottomScrim = new View(getContext());
        bottomScrim.setBackground(Ui.verticalGradient(getContext(), 0x00000000, 0xD9000000));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 340));
        bottomParams.gravity = Gravity.BOTTOM;
        addView(bottomScrim, bottomParams);

        // 顶部：返回 + 标题 + 静音
        backIcon = Ui.icon(getContext(), R.drawable.ic_back, 24, Color.WHITE);
        FrameLayout backBox = new FrameLayout(getContext());
        backBox.addView(backIcon, new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 24), Ui.dp(getContext(), 24), Gravity.CENTER));
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 44), Ui.dp(getContext(), 44));
        backParams.gravity = Gravity.TOP | Gravity.START;
        backParams.setMargins(Ui.dp(getContext(), 10),
                Ui.dp(getContext(), 26), 0, 0);
        addView(backBox, backParams);

        counterText = Ui.text(getContext(), "", 13, 0xE6FFFFFF, true);
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        counterParams.gravity = Gravity.TOP | Gravity.START;
        counterParams.setMargins(Ui.dp(getContext(), 60), Ui.dp(getContext(), 36), 0, 0);
        addView(counterText, counterParams);

        muteIcon = Ui.icon(getContext(), R.drawable.ic_volume, 22, Color.WHITE);
        FrameLayout muteBox = new FrameLayout(getContext());
        muteBox.addView(muteIcon, new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 22), Ui.dp(getContext(), 22), Gravity.CENTER));
        FrameLayout.LayoutParams muteParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 44), Ui.dp(getContext(), 44));
        muteParams.gravity = Gravity.TOP | Gravity.END;
        muteParams.setMargins(0, Ui.dp(getContext(), 26), Ui.dp(getContext(), 10), 0);
        addView(muteBox, muteParams);

        downloadIcon = Ui.icon(getContext(), R.drawable.ic_download, 22, Color.WHITE);
        FrameLayout downloadBox = new FrameLayout(getContext());
        downloadBox.addView(downloadIcon, new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 22), Ui.dp(getContext(), 22), Gravity.CENTER));
        FrameLayout.LayoutParams downloadParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 44), Ui.dp(getContext(), 44));
        downloadParams.gravity = Gravity.TOP | Gravity.END;
        downloadParams.setMargins(0, Ui.dp(getContext(), 26), Ui.dp(getContext(), 52), 0);
        addView(downloadBox, downloadParams);

        downloadPercent = Ui.text(getContext(), "", 10, 0xE6FFFFFF, true);
        downloadPercent.setVisibility(GONE);
        FrameLayout.LayoutParams percentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        percentParams.gravity = Gravity.TOP | Gravity.END;
        percentParams.setMargins(0, Ui.dp(getContext(), 74), Ui.dp(getContext(), 62), 0);
        addView(downloadPercent, percentParams);

        // 底部文案
        bottomBlock = new LinearLayout(getContext());
        bottomBlock.setOrientation(LinearLayout.VERTICAL);
        bottomBlock.setPadding(Ui.dp(getContext(), 16), 0, 0, 0);
        FrameLayout.LayoutParams blockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.gravity = Gravity.BOTTOM | Gravity.START;
        blockParams.setMargins(0, 0, Ui.dp(getContext(), 86), Ui.dp(getContext(), 64));
        addView(bottomBlock, blockParams);

        authorText = Ui.text(getContext(), "", 16, Color.WHITE, true);
        authorText.setShadowLayer(4f, 0f, 1f, 0x99000000);
        bottomBlock.addView(authorText);

        captionText = Ui.label(getContext(), "", 14, 0xF2FFFFFF);
        captionText.setMaxLines(3);
        captionText.setEllipsize(TextUtils.TruncateAt.END);
        captionText.setShadowLayer(4f, 0f, 1f, 0x99000000);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = Ui.dp(getContext(), 8);
        bottomBlock.addView(captionText, captionParams);

        publishText = Ui.text(getContext(), "", 10, 0x99FFFFFF, false);
        publishText.setShadowLayer(4f, 0f, 1f, 0x99000000);
        publishText.setVisibility(GONE);
        LinearLayout.LayoutParams publishParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        publishParams.topMargin = Ui.dp(getContext(), 6);
        bottomBlock.addView(publishText, publishParams);

        LinearLayout musicRow = new LinearLayout(getContext());
        musicRow.setOrientation(LinearLayout.HORIZONTAL);
        musicRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams musicParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        musicParams.topMargin = Ui.dp(getContext(), 10);
        bottomBlock.addView(musicRow, musicParams);
        musicIcon = Ui.icon(getContext(), R.drawable.ic_music, 14, 0xE6FFFFFF);
        musicRow.addView(musicIcon);
        musicText = Ui.text(getContext(), "", 12, 0xE6FFFFFF, false);
        LinearLayout.LayoutParams musicTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        musicTextParams.leftMargin = Ui.dp(getContext(), 6);
        musicRow.addView(musicText, musicTextParams);

        // 进度条
        progressLine = new ProgressLineView(getContext());
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 3));
        progressParams.gravity = Gravity.BOTTOM;
        // 抬高到系统手势区上方，避免从底部边缘拖动时被系统返回/Home 手势抢走
        progressParams.setMargins(0, 0, 0, Ui.dp(getContext(), 46));
        addView(progressLine, progressParams);

        progressTime = Ui.text(getContext(), "", 11, 0xCCFFFFFF, false);
        progressTime.setShadowLayer(3f, 0f, 1f, 0x99000000);
        progressTime.setVisibility(GONE);
        FrameLayout.LayoutParams progressTimeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressTimeParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        progressTimeParams.bottomMargin = Ui.dp(getContext(), 24);
        addView(progressTime, progressTimeParams);

        timePill = Ui.text(getContext(), "", 12, Color.WHITE, true);
        timePill.setBackground(Ui.round(getContext(), 0xCC000000, 12));
        timePill.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 5),
                Ui.dp(getContext(), 10), Ui.dp(getContext(), 5));
        timePill.setVisibility(GONE);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pillParams.bottomMargin = Ui.dp(getContext(), 18);
        addView(timePill, pillParams);

        // 右侧操作栏
        rail = new LinearLayout(getContext());
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 76), ViewGroup.LayoutParams.WRAP_CONTENT);
        railParams.gravity = Gravity.BOTTOM | Gravity.END;
        railParams.setMargins(0, 0, Ui.dp(getContext(), 4), Ui.dp(getContext(), 76));
        addView(rail, railParams);

        avatarBox = new LinearLayout(getContext());
        avatarBox.setOrientation(LinearLayout.VERTICAL);
        avatarBox.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.addView(avatarBox, new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 52), Ui.dp(getContext(), 52)));

        FrameLayout avatarFrame = new FrameLayout(getContext());
        avatarFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatarFrame.setClipToOutline(true);
        avatarBox.addView(avatarFrame, new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 46), Ui.dp(getContext(), 46)));

        avatarText = new TextView(getContext());
        avatarText.setText("净");
        avatarText.setTextColor(Color.WHITE);
        avatarText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        avatarText.setTypeface(Ui.BOLD);
        avatarText.setIncludeFontPadding(false);
        avatarText.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A3A44, 0xFF1B1B22});
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarText.setBackground(avatarBg);
        avatarFrame.addView(avatarText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        avatarImage = new ImageView(getContext());
        avatarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarImage.setVisibility(GONE);
        avatarFrame.addView(avatarImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View avatarRing = new View(getContext());
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(0x00000000);
        ringBg.setStroke(Ui.dp(getContext(), 1.4f), 0x99FFFFFF);
        avatarRing.setBackground(ringBg);
        avatarFrame.addView(avatarRing, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail.addView(railSpacer(18));
        // 侧栏顺序：收藏 → 分享 → 详情 → 自动下载。收藏与原来的「评论」对调；
        // 原「评论」点开的其实是详情弹层，现在图标和文案也一并对上。
        favoriteIcon = railIcon(R.drawable.ic_star, "收藏");
        favoriteLabel = (TextView) ((ViewGroup) favoriteIcon.getParent()).getChildAt(1);
        rail.addView((View) favoriteIcon.getParent());
        rail.addView(railSpacer(14));
        shareIcon = railIcon(R.drawable.ic_share, "分享");
        rail.addView((View) shareIcon.getParent());
        rail.addView(railSpacer(14));
        detailIcon = railIcon(R.drawable.ic_info, "详情");
        rail.addView((View) detailIcon.getParent());
        rail.addView(railSpacer(14));
        autoIcon = railIcon(R.drawable.ic_download, "自动下载");
        autoLabel = (TextView) ((ViewGroup) autoIcon.getParent()).getChildAt(1);
        rail.addView((View) autoIcon.getParent());
        refreshAutoDownloadState();
        avatarBox.setVisibility(VISIBLE);

        // 加载 / 错误 / 播放图标
        spinner = new LoadingDotsView(getContext());
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 54), Ui.dp(getContext(), 24));
        spinnerParams.gravity = Gravity.CENTER;
        addView(spinner, spinnerParams);

        playGlyph = Ui.text(getContext(), "▶", 40, 0xF2FFFFFF, true);
        playGlyph.setGravity(Gravity.CENTER);
        GradientDrawable glyphBg = new GradientDrawable();
        glyphBg.setShape(GradientDrawable.OVAL);
        glyphBg.setColor(0x66000000);
        playGlyph.setBackground(glyphBg);
        playGlyph.setVisibility(GONE);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 78), Ui.dp(getContext(), 78));
        glyphParams.gravity = Gravity.CENTER;
        addView(playGlyph, glyphParams);

        errorText = Ui.text(getContext(), "", 14, 0xE6FFFFFF, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                Ui.dp(getContext(), 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.gravity = Gravity.CENTER;
        addView(errorText, errorParams);

        albumBadge = Ui.text(getContext(), "", 12, Color.WHITE, true);
        albumBadge.setBackground(Ui.round(getContext(), 0x66000000, 11));
        albumBadge.setPadding(Ui.dp(getContext(), 8), Ui.dp(getContext(), 3),
                Ui.dp(getContext(), 8), Ui.dp(getContext(), 3));
        albumBadge.setVisibility(GONE);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.setMargins(0, Ui.dp(getContext(), 70), Ui.dp(getContext(), 16), 0);
        addView(albumBadge, badgeParams);

        playIcon = Ui.bigIcon(getContext(), R.drawable.ic_play, 26, 0xF2FFFFFF);
        playIcon.setVisibility(GONE);
        playIcon.setBackgroundColor(0x00000000);
        // 双击收藏的心心动画：铺满整屏、不拦截触摸，叠在最后一层
        heartBurst = new HeartBurstView(getContext());
        addView(heartBurst, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateMuteIcon();
    }

    private View railSpacer(int heightDp) {
        View view = new View(getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(getContext(), heightDp)));
        return view;
    }

    private ImageView railIcon(int res, String label) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(res);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(
                Ui.dp(getContext(), 32), Ui.dp(getContext(), 32)));
        TextView text = Ui.text(getContext(), label, 11, 0xF2FFFFFF, false);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = Ui.dp(getContext(), 3);
        box.addView(text, textParams);
        // 让 icon 的 parent 能反查到 label
        icon.setTag(text);
        return icon;
    }

    // ---------- 绑定 ----------
    public Work work() {
        return work;
    }

    public int index() {
        return index;
    }

    public int role() {
        return role;
    }

    public void bind(Work work, int index, int total) {
        pageDestroyed = false;
        if (this.work == work) {
            this.index = index;
            counterSuffix = " / " + total;
            updateCounter();
            // 页面复用：作品没变，但收藏状态可能在别处改过，顺手对一下
            refreshFavoriteState();
            return;
        }
        releasePlayers();
        this.work = work;
        pageDestroyed = false;
        archiveVideo = null;
        archiveMusic = null;
        archiveVideoLoading = false;
        archiveMusicLoading = false;
        archiveMusicRetries = 0;
        musicLookupDone = false;
        handler.removeCallbacks(musicRetry);
        // 换作品：上一轮图集轮播的待执行任务先撤掉，等 prepareAlbum 重新排
        handler.removeCallbacks(albumTicker);
        this.index = index;
        counterSuffix = " / " + total;
        prepared = false;
        playing = false;
        userPaused = false;
        detailsRequested = false;
        playAttempts = 0;
        speedBoost = false;
        resumeAtMs = -1L;
        speedNav.reset();
        musicPrepared = false;
        visualReady = false;
        albumPaused = false;
        albumManual = false;
        handler.removeCallbacks(visualFallback);
        coverHidden = false;
        albumIndex = 0;
        videoWidth = 0;
        videoHeight = 0;
        liveWidth = 0;
        liveHeight = 0;
        liveShown = false;
        livePrepared = false;
        errorText.setVisibility(GONE);
        spinner.stop();
        spinner.setVisibility(GONE);
        playGlyph.setVisibility(GONE);
        progressLine.setProgress(0f);
        if (progressTime != null) {
            progressTime.setText("");
            progressTime.setVisibility(GONE);
        }
        albumBadge.setVisibility(GONE);
        // 上一页的「封面淡出」动画可能还在跑：不复位的话它的 endAction 会把
        // 刚绑上来的这一页封面设成 GONE，首帧出来前是一片黑
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(VISIBLE);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageDrawable(null);
        if (backdrop != null) {
            backdrop.setVisibility(GONE);
            backdrop.setImageDrawable(null);
        }
        if (work == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        updateCounter();
        authorText.setText("@" + work.displayAuthor());
        if (avatarText != null) {
            avatarText.setText(AvatarStore.initialOf(work.displayAuthor()));
            avatarText.setVisibility(VISIBLE);
        }
        if (avatarImage != null) {
            avatarImage.setVisibility(GONE);
            avatarImage.setImageDrawable(null);
        }
        app.avatars.load(work, (author, uid, bitmap) -> {
            if (bitmap != null && work == FeedPage.this.work && avatarImage != null) {
                avatarImage.setImageBitmap(bitmap);
                avatarImage.setVisibility(VISIBLE);
                if (avatarText != null) {
                    avatarText.setVisibility(GONE);
                }
            }
        });
        setCaption(work.captionLine());
        updatePublishTime();
        musicText.setText("");
        musicIcon.setVisibility(work.isAlbum() ? VISIBLE : GONE);

        Bitmap peek = images.peek(work.key());
        if (peek != null) {
            cover.setImageBitmap(peek);
        } else {
            images.loadCover(work, (key, bitmap) -> {
                if (bitmap != null && work == FeedPage.this.work && !coverHidden
                        && cover.getDrawable() == null) {
                    cover.setImageBitmap(bitmap);
                }
            });
        }
        requestDetailsIfNeeded();
        refreshDownloadState();
        refreshAutoDownloadState();
        refreshFavoriteState();
    }

    /**
     * 当前作品刚下载完：如果画面还在转圈/报错/没准备好，立刻改用本地文件重新加载，
     * 不用等用户退出重进。正在正常播放的就不打断。
     */
    public void onOfflineReady() {
        if (work == null || !app.offline.has(work)) {
            refreshDownloadState();
            return;
        }
        if (work.isVideo()) {
            boolean shouldReload = errorText.getVisibility() == VISIBLE
                    || !prepared
                    || spinner.getVisibility() == VISIBLE
                    || (!coverHidden && !playing);
            if (shouldReload) {
                if (player != null) {
                    try {
                        player.release();
                    } catch (Exception ignored) {
                    }
                    player = null;
                }
                settingUp = false;
                prepared = false;
                playing = false;
                visualReady = false;
                playAttempts = 0;
                armVisualFallback();
                errorText.setVisibility(GONE);
                showLoading(true);
                if (role == 0) {
                    prepareVideo();
                }
            }
        } else if (work.isAlbum()) {
            showAlbumImage(albumIndex, false);
            armVisualFallback();
            if (musicPlayer == null) {
                prepareMusic();
            }
        }
        if (!coverHidden) {
            images.loadCover(work, (key, bitmap) -> {
                if (bitmap != null && work == FeedPage.this.work
                        && !coverHidden && cover.getVisibility() == VISIBLE) {
                    cover.setImageBitmap(bitmap);
                }
            });
        }
        refreshDownloadState();
    }

    /** 回收站预览：下载 / 收藏 / 自动下载都不可用（变暗，点了提示先恢复）。 */
    public void setPreviewMode(boolean preview) {
        previewMode = preview;
        float alpha = preview ? 0.35f : 1f;
        if (downloadIcon != null) {
            downloadIcon.setAlpha(alpha);
        }
        if (downloadPercent != null && preview) {
            downloadPercent.setVisibility(GONE);
        }
        if (favoriteIcon != null) {
            ((View) favoriteIcon.getParent()).setAlpha(alpha);
        }
        if (autoIcon != null) {
            ((View) autoIcon.getParent()).setAlpha(alpha);
        }
    }

    public boolean isPreviewMode() {
        return previewMode;
    }

    /** 右侧「收藏」按钮：已收藏时星星变金色。 */
    public void refreshFavoriteState() {
        if (favoriteIcon == null) {
            return;
        }
        boolean favorite = work != null && app.favorites.isFavorite(work.rel);
        favoriteIcon.setColorFilter(favorite ? 0xFFFFC53D : Color.WHITE);
        if (favoriteLabel != null) {
            favoriteLabel.setText(favorite ? "已收藏" : "收藏");
            favoriteLabel.setTextColor(favorite ? 0xFFFFC53D : 0xF2FFFFFF);
        }
    }

    /** 双击收藏时，在手指位置冒出心心；pager 传来的坐标就是整屏坐标，可直接用。 */
    public void showFavoriteBurst(float x, float y) {
        if (heartBurst != null) {
            heartBurst.burst(x, y);
        }
    }

    /** 右上角下载按钮：未下载/下载中/已下载三种状态。 */
    public void refreshDownloadState() {
        if (work == null || downloadIcon == null) {
            return;
        }
        boolean has = app.offline.has(work);
        boolean ing = app.offline.isDownloading(work);
        int progress = app.offline.progressOf(work);
        downloadIcon.setImageResource(has ? R.drawable.ic_done : R.drawable.ic_download);
        downloadIcon.setColorFilter(has ? 0xFF25F4EE : Color.WHITE);
        if (ing) {
            downloadPercent.setVisibility(VISIBLE);
            downloadPercent.setText(Math.max(0, progress) + "%");
        } else if (progress == -2) {
            downloadPercent.setVisibility(VISIBLE);
            downloadPercent.setText("失败");
        } else {
            downloadPercent.setVisibility(GONE);
        }
    }

    private void updateCounter() {
        counterText.setText((index + 1) + counterSuffix);
    }

    /** 右侧「自动下载」开关：开启时每看完一个视频就自动存到本地。 */
    public void refreshAutoDownloadState() {
        if (autoIcon == null) {
            return;
        }
        boolean on = prefs.autoDownload();
        autoIcon.setColorFilter(on ? 0xFF25F4EE : Color.WHITE);
        if (autoLabel != null) {
            autoLabel.setText(on ? "自动下载·开" : "自动下载");
            autoLabel.setTextColor(on ? 0xFF25F4EE : 0xCCFFFFFF);
        }
    }

    /**
     * 文案下面显示作品的发布时间 + 这个作品所有文件加起来的大小
     * （来自文案.txt，读不到时用目录名里的时间；大小要等详情读完才有）。
     */
    private void updatePublishTime() {
        if (publishText == null) {
            return;
        }
        if (work == null) {
            publishText.setVisibility(GONE);
            return;
        }
        String time = Util.humanTime(work.time);
        // 详情还没读出来时 totalBytes() 是 0，不能显示成「0 B」，等 onDetailsReady 再补
        long bytes = work.totalBytes();
        String size = bytes > 0 ? Util.humanSize(bytes) : "";
        if (time.isEmpty() && size.isEmpty()) {
            publishText.setText("");
            publishText.setVisibility(GONE);
        } else {
            StringBuilder text = new StringBuilder();
            if (!time.isEmpty()) {
                text.append("发布时间 ").append(time);
            }
            if (!size.isEmpty()) {
                if (text.length() > 0) {
                    text.append(" · ");
                }
                text.append(size);
            }
            publishText.setText(text);
            publishText.setVisibility(VISIBLE);
        }
    }

    /** 文案里的 #话题 / @用户 高亮并可点击，和抖音一致。 */
    private void setCaption(String text) {
        if (text == null) {
            text = "";
        }
        captionTokens.clear();
        captionRanges.clear();
        if (text.isEmpty()) {
            // 没文案就整行藏掉。以前这里会显示 `<作品id>.tar` 去掉扩展名后的一串数字，
            // 那只是文件名，不是作品文案。
            captionText.setText("");
            captionText.setVisibility(GONE);
            return;
        }
        captionText.setVisibility(VISIBLE);
        SpannableString spannable = new SpannableString(text);
        java.util.regex.Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            captionTokens.add(token);
            captionRanges.add(new int[]{start, end});
            spannable.setSpan(new ForegroundColorSpan(0xFF25F4EE), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        captionText.setText(spannable);
    }

    /** 文案不是可点击控件（避免吞掉上下滑手势），由 pager 在单击时按坐标命中 #话题 / @用户。 */
    /** 命中的话题序号；没有返回 -1。只做判定、无副作用，手势层也能安全调用。 */
    private int captionTokenAt(float x, float y) {
        if (captionText == null || captionText.getVisibility() != VISIBLE
                || captionText.getLayout() == null || captionRanges.isEmpty()) {
            return -1;
        }
        int[] pageLocation = new int[2];
        int[] captionLocation = new int[2];
        getLocationOnScreen(pageLocation);
        captionText.getLocationOnScreen(captionLocation);
        float localX = pageLocation[0] + x - captionLocation[0] - captionText.getPaddingLeft();
        float localY = pageLocation[1] + y - captionLocation[1] - captionText.getPaddingTop();
        if (localX < 0 || localY < 0 || localX > captionText.getWidth()
                || localY > captionText.getHeight()) {
            return -1;
        }
        int line = captionText.getLayout().getLineForVertical((int) localY);
        if (line < 0 || line >= captionText.getLineCount()) {
            return -1;
        }
        int offset = captionText.getLayout().getOffsetForHorizontal(line, localX);
        for (int index = 0; index < captionRanges.size(); index++) {
            int[] range = captionRanges.get(index);
            if (offset >= range[0] - 1 && offset <= range[1]) {
                return index;
            }
        }
        return -1;
    }

    /** 双击判定用：这个位置是不是落在可点的话题文字上（不触发跳转）。 */
    public boolean hasCaptionTokenAt(float x, float y) {
        return captionTokenAt(x, y) >= 0;
    }

    public boolean handleCaptionTap(float x, float y) {
        int index = captionTokenAt(x, y);
        if (index < 0) {
            return false;
        }
        if (listener != null) {
            listener.onToken(this, captionTokens.get(index));
        }
        return true;
    }

    private void requestDetailsIfNeeded() {
        if (work == null) {
            return;
        }
        if (work.detailsLoaded) {
            onDetailsReady();
            return;
        }
        if (detailsRequested) {
            return;
        }
        detailsRequested = true;
        showLoading(true);
        app.scanner.loadDetails(work, (target, ok, message) -> {
            if (work != target) {
                return;
            }
            if (!ok) {
                detailsRequested = false;
                showLoading(false);
                showError(message == null ? "作品读取失败" : message);
                return;
            }
            // 详情读完后统一刷新：有文案就显示，确实没有就保持隐藏
            setCaption(work.captionLine());
            onDetailsReady();
        });
    }

    private void onDetailsReady() {
        updatePublishTime();
        showLoading(false);
        if (work.empty) {
            showError("这个作品里没有可播放的内容");
            return;
        }
        if (work.isVideo()) {
            prepareVideo();
        } else {
            prepareAlbum();
        }
    }

    private void prepareVideo() {
        if (pageDestroyed) {
            return;
        }
        if (prepared || settingUp || player != null || work == null || work.video == null) {
            return;
        }
        if (work.video.inArchive() && archiveVideo == null) {
            // 作品包里只有 视频.mp4 这一个成员，按 Range 解出来再交给 MediaPlayer
            if (!archiveVideoLoading) {
                archiveVideoLoading = true;
                showLoading(true);
                final Work bound = work;
                app.archives.file(work.video, (file, error) -> {
                    archiveVideoLoading = false;
                    if (pageDestroyed || work != bound) {
                        return;
                    }
                    if (file == null) {
                        showLoading(false);
                        showError(error == null ? "作品包解包失败" : error);
                        return;
                    }
                    archiveVideo = file;
                    if (app.images != null) {
                        app.images.cacheVideoCover(work, file);
                    }
                    prepareVideo();
                });
            }
            return;
        }
        settingUp = true;
        try {
            java.io.File localVideo = archiveVideo != null ? archiveVideo
                    : app.offline.localVideo(work);
            String url = localVideo != null ? null
                    : app.proxy.urlFor(work.video.rel, work.video.name, work.video.size);
            MediaPlayer mp = new MediaPlayer();
            // 先挂到字段：setDataSource / setSurface 抛异常时 catch 才释放得到这个播放器，
            // 否则每次加载失败都会漏掉一个含 native 资源的 MediaPlayer。
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setLooping(false);
            mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            mp.setOnPreparedListener(mediaPlayer -> {
                if (mediaPlayer != player) {
                    return;
                }
                prepared = true;
                playAttempts = 0;
                settingUp = false;
                visualReady = false;
                armVisualFallback();
                videoWidth = mediaPlayer.getVideoWidth();
                videoHeight = mediaPlayer.getVideoHeight();
                applyVideoTransform();
                final boolean resumed = resumeAtMs > 0;
                if (resumed) {
                    // 为倍速重建过播放器：先回到原来的位置，别让画面跳回开头
                    long duration = mediaPlayer.getDuration();
                    long at = duration > 0 ? Math.min(resumeAtMs, Math.max(0L, duration - 500)) : resumeAtMs;
                    try {
                        mediaPlayer.seekTo((int) Math.max(0L, at));
                    } catch (Exception ignored) {
                    }
                    resumeAtMs = -1L;
                }
                if (role == 0 && !userPaused && hostResumed) {
                    // 先设倍速再 start：部分机型只有在 Prepared 状态设置才真正生效。
                    // 新播放器 = 新引擎，把上一次的失败计数清掉重新给机会
                    if (speedBoost) {
                        speedNav.restart();
                        if (resumed) {
                            // seek 是异步的，等落点稳定再设：否则实测基准会被「位置从 0
                            // 跳到原处」这一步污染，明明没加速也会算成加速
                            handler.postDelayed(() -> {
                                if (!pageDestroyed && player == mediaPlayer) {
                                    applySpeed(SpeedNav.BOOST);
                                }
                            }, 300L);
                        } else {
                            applySpeed(SpeedNav.BOOST);
                        }
                    }
                    mediaPlayer.start();
                    playing = true;
                }
                showLoading(false);
                updatePlayGlyph();
            });
            mp.setOnVideoSizeChangedListener((mediaPlayer, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                applyVideoTransform();
            });
            mp.setOnInfoListener((mediaPlayer, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    hideCover();
                    if (speedBoost) {
                        // 缓冲期间设倍速很容易被拒，这会儿才算「状态变了」，清掉失败计数再补一次
                        speedNav.restart();
                        applySpeed(SpeedNav.BOOST);
                    }
                }
                return false;
            });
            mp.setOnCompletionListener(mediaPlayer -> {
                playing = false;
                if (listener != null) {
                    listener.onCompleted(FeedPage.this);
                }
            });
            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                android.util.Log.w("JYWatch", "media error what=" + what + " extra=" + extra
                        + " rel=" + work.rel);
                settingUp = false;
                prepared = false;
                if (musicPlayer != null) {
                    try {
                        musicPlayer.pause();
                    } catch (Exception ignored) {
                    }
                }
                if (player != null) {
                    try {
                        player.release();
                    } catch (Exception ignored) {
                    }
                    player = null;
                }
                if (playAttempts < 2 && role == 0) {
                    // 123pan 的 CDN 偶发 TLS 断流：自动重连一次，比直接报错友好
                    playAttempts++;
                    showLoading(true);
                    handler.postDelayed(FeedPage.this::prepareVideo, 1200L * playAttempts);
                } else {
                    showLoading(false);
                    showError("视频暂时无法播放，轻点重试");
                }
                return true;
            });
            if (localVideo != null) {
                mp.setDataSource(localVideo.getAbsolutePath());
            } else {
                mp.setDataSource(getContext(), Uri.parse(url));
            }
            if (surface != null) {
                mp.setSurface(surface);
            }
            mp.prepareAsync();
            if (localVideo == null && !work.video.inArchive()) {
                // 边播边把这段视频补完，回滑 / 重播都不必重新拉（作品包按成员解，不需要代理预取）。
                // 只和「这段视频有没有本地文件」有关，与自动连播开关无关：
                // 关掉连播不代表当前这集不需要缓存。
                app.proxy.prefetch(work.video.rel, work.video.name, work.video.size);
            }
        } catch (Exception exc) {
            android.util.Log.w("JYWatch", "prepare failed " + work.rel, exc);
            settingUp = false;
            prepared = false;
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            showError("视频加载失败，轻点重试");
        }
    }

    private void prepareAlbum() {
        if (work == null || work.images.isEmpty()) {
            showError("图集里没有图片");
            return;
        }
        prepared = true;
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackgroundColor(Color.BLACK);
        // 用当前索引而不是写死 0：换作品时 bind() 已经把它清成 0；但「上次释放过播放器
        // 又滑回来」会重新 prepare，这时写死 0 会把用户刚切到的第 N 张打回第 1 张
        showAlbumImage(albumIndex, false);
        scheduleAlbumTicker();
        // 图集首图读不出来（解包失败 / 网络抖动 / 解码失败）时，visualReady 永远不会置位，
        // 已经 prepare 好的背景音乐就被一起卡死。随机观看的作品全是冷数据，
        // 首图失败概率明显更高，这就是「随机看相册没声音」的主因。
        armVisualFallback();
        ensureMusic();
    }

    /** 画面迟迟不来时放行音乐：宁可先响，也不能让人一直等到退出重进。 */
    private void armVisualFallback() {
        if (visualReady) {
            return;
        }
        handler.removeCallbacks(visualFallback);
        handler.postDelayed(visualFallback, 1500L);
    }

    /** 绑定一次只查一次独立音乐目录；作品包里的音乐来自成员清单，不用再查。 */
    private void ensureMusic() {
        if (work == null || musicLookupDone) {
            return;
        }
        musicLookupDone = true;
        final Work bound = work;
        if (work.music != null) {
            prepareMusic();
            return;
        }
        app.scanner.loadMusic(work, () -> {
            if (work == bound && !pageDestroyed) {
                prepareMusic();
            }
        });
    }

    /** 音乐必须等画面（视频首帧 / 图集第一张）出来后再响，避免"音乐先响、画面还黑着"。 */
    private void startMusicIfReady() {
        if (musicPlayer == null || !musicPrepared || !visualReady) {
            return;
        }
        if (role != 0 || !hostResumed || userPaused) {
            return;
        }
        try {
            if (!musicPlayer.isPlaying()) {
                musicPlayer.start();
            }
            musicIcon.setVisibility(VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void prepareMusic() {
        if (pageDestroyed) {
            return;
        }
        if (work == null || work.music == null) {
            musicIcon.setVisibility(GONE);
            return;
        }
        if (musicPlayer != null) {
            // 播放器已经在跑（比如下载完成回调又调了一次）：别把音乐图标藏了
            return;
        }
        final Work bound = work;
        if (work.music.inArchive() && archiveMusic == null) {
            if (!archiveMusicLoading) {
                archiveMusicLoading = true;
                app.archives.file(work.music, (file, error) -> {
                    archiveMusicLoading = false;
                    // 页面已经被复用到别的作品：这次解包结果作废，别把上一条的音乐
                    // 挂到新作品上（以前写的是 work != FeedPage.this.work，两边是同一个
                    // 字段，永远为 false，等于没有校验）。
                    if (pageDestroyed || work != bound) {
                        return;
                    }
                    if (file == null) {
                        musicIcon.setVisibility(GONE);
                        if (archiveMusicRetries < 2) {
                            archiveMusicRetries++;
                            handler.postDelayed(musicRetry, 1200L);
                        }
                        return;
                    }
                    archiveMusic = file;
                    prepareMusic();
                });
            }
            return;
        }
        try {
            java.io.File localMusic = archiveMusic != null ? archiveMusic
                    : app.offline.localMusic(work);
            String url = localMusic != null ? null
                    : app.proxy.urlFor(work.music.rel, work.music.name, work.music.size);
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setLooping(true);
            mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            // 监听器要先挂上再 prepareAsync：否则失败时没人收错误，
            // musicPlayer 一直非空、musicPrepared 一直 false，这条永远没声音也不会重试
            mp.setOnPreparedListener(mediaPlayer -> {
                if (mediaPlayer != musicPlayer) {
                    return;
                }
                musicPrepared = true;
                startMusicIfReady();
            });
            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (mediaPlayer != musicPlayer) {
                    return true;
                }
                releaseMusicPlayer();
                if (!pageDestroyed && work != null && work.music != null
                        && archiveMusicRetries < 2) {
                    archiveMusicRetries++;
                    handler.postDelayed(musicRetry, 1200L);
                }
                return true;
            });
            // 先挂到字段上：prepareAsync 的回调可能比方法返回还早
            musicPlayer = mp;
            if (localMusic != null) {
                mp.setDataSource(localMusic.getAbsolutePath());
            } else {
                mp.setDataSource(getContext(), Uri.parse(url));
            }
            mp.prepareAsync();
            musicIcon.setVisibility(VISIBLE);
            musicText.setText("背景音乐");
        } catch (Exception exc) {
            releaseMusicPlayer();
            musicIcon.setVisibility(GONE);
        }
    }

    private void showAlbumImage(int index, boolean animate) {
        if (work == null || work.images.isEmpty()) {
            return;
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= work.images.size()) {
            index = 0;
        }
        albumIndex = index;
        final int token = ++albumLoadToken;
        // 新的一张没有配对的动图时，先把上一张的动图收掉：否则它会继续在同一块
        // TextureView 上循环，静态图却还是上一张，看起来就像「切了没反应」。
        if (work.liveFor(index) == null) {
            stopAlbumLive();
        }
        loadAlbumImage(work, index, animate, token, 0);
        albumBadge.setVisibility(work.isAlbum() ? VISIBLE : GONE);
        albumBadge.setText("图集 " + (index + 1) + "/" + work.images.size());
        progressLine.setProgress((index + 1f) / work.images.size());
        updateProgressTime();
        prefetchAlbumNeighbors(index);
    }

    /**
     * 加载第 index 张图，失败就退避重试。
     *
     * <p>以前加载失败时回调直接 return：画面停在上一次（通常就是第 1 张），角标却
     * 已经跳到「图集 2/3」，既不提示也不会自己恢复。用户看到的就是「划了一下，
     * 还是第一张」。WebDAV 偶发超时 / 一次性链接失效都算这一类。
     */
    private void loadAlbumImage(final Work boundWork, final int index, final boolean animate,
                                final int token, final int attempt) {
        if (work != boundWork || token != albumLoadToken) {
            return;
        }
        final ImageLoader.Callback albumCallback = (key, bitmap) -> {
            if (work != boundWork || token != albumLoadToken) {
                return;
            }
            if (bitmap == null) {
                if (attempt < ALBUM_LOAD_RETRIES) {
                    handler.postDelayed(() -> loadAlbumImage(boundWork, index, animate,
                            token, attempt + 1), 400L * (attempt + 1));
                } else if (role == 0) {
                    // 真读不出来就说一句，别让人以为横滑根本没生效
                    Ui.toast(getContext(), "这一张图读取失败，已自动重试");
                }
                return;
            }
            cover.setImageBitmap(bitmap);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 不做「从全黑淡入」：图集切换时那一瞬间容易被误认为没加载出来
            cover.setAlpha(1f);
            if (animate) {
                cover.animate().scaleX(1.015f).scaleY(1.015f).setDuration(180)
                        .withEndAction(() -> cover.animate().scaleX(1f).scaleY(1f)
                                .setDuration(180).start()).start();
            }
            visualReady = true;
            handler.removeCallbacks(visualFallback);
            startMusicIfReady();
            // 静态图先顶上，再让这一张的实况动图叠上来；没有动图就保持静态图
            prepareAlbumLive(index);
        };
        java.io.File localImage = app.offline.localImage(boundWork, index);
        if (localImage != null) {
            images.loadLocalImage(localImage, Ui.screenWidth(getContext()), albumCallback);
        } else {
            images.loadImage(boundWork.images.get(index), Ui.screenWidth(getContext()),
                    albumCallback);
        }
    }

    /**
     * 切完立刻把前后相邻图预解码进图集缓存：下一次划动就能同步命中、不必等
     * WebDAV 整图下载 + 解码。只给当前屏预取，三屏里另两屏不浪费带宽。
     */
    private void prefetchAlbumNeighbors(int index) {
        if (work == null || !work.isAlbum() || role != 0 || work.images.size() <= 1) {
            return;
        }
        int width = Ui.screenWidth(getContext());
        for (int neighbor : AlbumNav.neighbors(index, work.images.size())) {
            RemoteFile file = work.images.get(neighbor);
            java.io.File local = app.offline.localImage(work, neighbor);
            images.prefetchImage(file, local, width);
        }
    }

    // ---------- 图集里的实况 / 动图 ----------

    /**
     * 这一张有没有配对的动图：有就开始播，没有就把播放器收掉、露出静态图。
     *
     * 电脑版导出图集时，每张图可能带一段短视频（`动图_01.mp4` ↔ `图片_01.jpg`），
     * 以前 App 只读 `images`、把 lives 整个忽略，所以实况图永远只有一张静态照片。
     */
    private void prepareAlbumLive(int index) {
        if (work == null || !work.hasLives() || work.liveFor(index) == null) {
            stopAlbumLive();
            return;
        }
        // 换到新的一张：先把静态图露出来，等动图首帧到了再淡出，避免中间黑一下
        liveShown = false;
        handler.removeCallbacks(liveReveal);
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setScaleX(1f);
        cover.setScaleY(1f);
        if (cover.getVisibility() != VISIBLE) {
            cover.setVisibility(VISIBLE);
        }
        playAlbumLive(index);
    }

    private void playAlbumLive(int index) {
        if (pageDestroyed || work == null) {
            return;
        }
        final Work bound = work;
        final int target = index;
        RemoteFile live = work.liveFor(index);
        if (live == null) {
            stopAlbumLive();
            return;
        }
        if (live.inArchive()) {
            // ArchiveStore 自己按目标文件加锁并缓存，切来切去重复调用也不会重复解包
            app.archives.file(live, (file, error) -> {
                if (pageDestroyed || work != bound || albumIndex != target) {
                    return;
                }
                if (file == null) {
                    // 解包失败就停在静态图，不影响看图
                    android.util.Log.w("JYWatch", "动图解包失败 " + live.name
                            + (error == null ? "" : "：" + error));
                    return;
                }
                startAlbumLive(file, target);
            });
            return;
        }
        startAlbumLive(app.offline.localLive(bound, index), target);
    }

    private void startAlbumLive(java.io.File local, int index) {
        if (pageDestroyed || work == null) {
            return;
        }
        RemoteFile live = work.liveFor(index);
        if (live == null) {
            return;
        }
        releaseAlbumLive();
        try {
            java.io.File localFile = local;
            String url = localFile != null ? null
                    : app.proxy.urlFor(live.rel, live.name, live.size);
            MediaPlayer mp = new MediaPlayer();
            // 先挂字段：setDataSource / prepareAsync 抛异常时 catch 才释放得到
            livePlayer = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // 循环播放：3.2 秒的自动轮播可能比动图本身短，不循环就会切到一半停住
            mp.setLooping(true);
            // 动图静音：背景音乐在 musicPlayer 上，两条一起响会糊成一片
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(player -> {
                if (player != livePlayer) {
                    return;
                }
                livePrepared = true;
                applyLiveTransform();
                if (albumLiveShouldPlay()) {
                    try {
                        player.start();
                    } catch (Exception ignored) {
                    }
                }
                // 有的机型不回调 MEDIA_INFO_VIDEO_RENDERING_START，轮询兜底
                liveRevealAttempts = 0;
                handler.removeCallbacks(liveReveal);
                handler.postDelayed(liveReveal, 250L);
            });
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                liveWidth = width;
                liveHeight = height;
                applyLiveTransform();
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    showAlbumLiveFrame();
                }
                return false;
            });
            mp.setOnErrorListener((player, what, extra) -> {
                android.util.Log.w("JYWatch", "动图播放失败 " + live.name
                        + " what=" + what + " extra=" + extra);
                releaseAlbumLive();
                return true;
            });
            if (localFile != null) {
                mp.setDataSource(localFile.getAbsolutePath());
            } else {
                mp.setDataSource(getContext(), Uri.parse(url));
            }
            if (surface != null) {
                mp.setSurface(surface);
            }
            mp.prepareAsync();
            if (localFile == null && !live.inArchive()) {
                app.proxy.prefetch(live.rel, live.name, live.size);
            }
        } catch (Exception exc) {
            android.util.Log.w("JYWatch", "动图准备失败 " + live.name, exc);
            releaseAlbumLive();
        }
    }

    /** 动图首帧已上屏：把静态图淡出，让 TextureView 的画面露出来。 */
    private void showAlbumLiveFrame() {
        if (liveShown || livePlayer == null || pageDestroyed) {
            return;
        }
        liveShown = true;
        handler.removeCallbacks(liveReveal);
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (liveShown) {
                cover.setVisibility(GONE);
                // 切图时那个 1.015 倍「呼吸」动画可能被取消在半路，
                // 这里还原，下次静态图重新出现时不会莫名其妙变大
                cover.setScaleX(1f);
                cover.setScaleY(1f);
            }
        }).start();
        visualReady = true;
        handler.removeCallbacks(visualFallback);
        startMusicIfReady();
    }

    /** 收起动图、把静态图放回来（这一张没有动图 / 播放失败 / 换作品）。 */
    private void stopAlbumLive() {
        // 本来就没有动图时什么都别做：这里的 cancel / setAlpha 会打断
        // showAlbumImage 正在跑的 1.015 倍缩放动画，把图卡在放大状态
        boolean hadLive = liveShown || livePlayer != null;
        releaseAlbumLive();
        liveShown = false;
        handler.removeCallbacks(liveReveal);
        if (!hadLive) {
            return;
        }
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setScaleX(1f);
        cover.setScaleY(1f);
        if (cover.getVisibility() != VISIBLE) {
            cover.setVisibility(VISIBLE);
        }
    }

    private void releaseAlbumLive() {
        livePrepared = false;
        handler.removeCallbacks(liveReveal);
        if (livePlayer != null) {
            try {
                livePlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                livePlayer.release();
            } catch (Exception ignored) {
            }
            livePlayer = null;
        }
    }

    private boolean albumLiveShouldPlay() {
        return role == 0 && hostResumed && !albumPaused && !userPaused;
    }

    public void pauseAlbumLive() {
        if (livePlayer == null) {
            return;
        }
        try {
            if (livePlayer.isPlaying()) {
                livePlayer.pause();
            }
        } catch (Exception ignored) {
        }
    }

    public void resumeAlbumLive() {
        if (livePlayer == null || !livePrepared || !albumLiveShouldPlay()) {
            return;
        }
        try {
            livePlayer.start();
        } catch (Exception ignored) {
        }
        if (!liveShown) {
            // 预加载屏（role != 0）里 prepare 但不 start，轮询早就放弃了；
            // 真正滑到这一屏时要重新盯着，别让静态图一直盖着动图
            liveRevealAttempts = 0;
            handler.removeCallbacks(liveReveal);
            handler.postDelayed(liveReveal, 250L);
        }
    }

    /**
     * 动图的画面变换：图集静态图是 FIT_CENTER（完整显示），动图也必须完整显示，
     * 不然切换瞬间会从「不裁切」跳到「铺满裁切」。所以这里固定 contain。
     */
    private void applyLiveTransform() {
        if (liveWidth <= 0 || liveHeight <= 0 || texture == null
                || texture.getWidth() <= 0 || texture.getHeight() <= 0) {
            return;
        }
        float viewWidth = texture.getWidth();
        float viewHeight = texture.getHeight();
        float liveAspect = liveWidth / (float) liveHeight;
        float screenAspect = viewWidth / viewHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (liveAspect >= screenAspect) {
            scaleY = screenAspect / liveAspect;
        } else {
            scaleX = liveAspect / screenAspect;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        texture.setTransform(matrix);
    }

    /** 这个作品还要不要自动轮播。判定统一放 AlbumNav，别让两处条件各写一套。 */
    private boolean albumShouldAdvance() {
        int count = work != null && work.isAlbum() ? work.images.size() : 0;
        return AlbumNav.shouldAutoAdvance(albumManual, albumPaused, count, role == 0, hostResumed);
    }

    private void scheduleAlbumTicker() {
        handler.removeCallbacks(albumTicker);
        if (!albumShouldAdvance()) {
            return;
        }
        handler.postDelayed(albumTicker, ALBUM_INTERVAL);
    }

    private final Runnable albumTicker = new Runnable() {
        @Override
        public void run() {
            if (!albumShouldAdvance()) {
                return;
            }
            showAlbumImage(AlbumNav.wrap(albumIndex + 1, work.images.size()), true);
            handler.postDelayed(this, ALBUM_INTERVAL);
        }
    };

    /**
     * 用户手动切过一张：这个作品从此不自动轮播。
     *
     * 以前手动切完 3.2 秒定时器照旧把画面带走，等于白划一次；现在第一次手动切
     * 就撤掉定时器，本作品内不再自动切（换作品 bind 时重置）。
     */
    private void markAlbumManual() {
        albumManual = true;
        handler.removeCallbacks(albumTicker);
    }

    public void advanceAlbum() {
        if (work != null && work.isAlbum() && !work.images.isEmpty()) {
            markAlbumManual();
            showAlbumImage(AlbumNav.wrap(albumIndex + 1, work.images.size()), true);
        }
    }

    public void previousAlbum() {
        if (work != null && work.isAlbum() && !work.images.isEmpty()) {
            markAlbumManual();
            showAlbumImage(AlbumNav.wrap(albumIndex - 1, work.images.size()), true);
        }
    }

    /** 图集点一下：暂停自动轮播和背景音乐；再点继续。 */
    private void pauseAlbum() {
        if (work == null || !work.isAlbum()) {
            return;
        }
        albumPaused = true;
        userPaused = true;
        handler.removeCallbacks(albumTicker);
        pauseAlbumLive();
        if (musicPlayer != null) {
            try {
                musicPlayer.pause();
            } catch (Exception ignored) {
            }
        }
        updatePlayGlyph();
    }

    private void resumeAlbum() {
        if (work == null || !work.isAlbum()) {
            return;
        }
        albumPaused = false;
        userPaused = false;
        resumeAlbumLive();
        startMusicIfReady();
        scheduleAlbumTicker();
        updatePlayGlyph();
    }

    /** 手指按在图集上时先停轮播，松开/点按后再根据暂停状态决定是否继续。 */
    public void onAlbumTouchDown() {
        if (work != null && work.isAlbum()) {
            handler.removeCallbacks(albumTicker);
        }
    }

    public void onAlbumTouchUp() {
        if (work != null && work.isAlbum()) {
            scheduleAlbumTicker();
        }
    }

    private void applyVideoTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture == null
                || texture.getWidth() <= 0 || texture.getHeight() <= 0) {
            return;
        }
        float viewWidth = texture.getWidth();
        float viewHeight = texture.getHeight();
        float videoAspect = videoWidth / (float) videoHeight;
        float screenAspect = viewWidth / viewHeight;
        // TextureView 默认把画面拉满整屏；用非等比缩放把画面还原成正确比例。
        float scaleX = 1f;
        float scaleY = 1f;
        boolean cover = videoAspect < 0.95f;  // 竖屏铺满，横屏完整显示
        if (cover) {
            if (videoAspect >= screenAspect) {
                scaleX = videoAspect / screenAspect;
            } else {
                scaleY = screenAspect / videoAspect;
            }
        } else {
            if (videoAspect >= screenAspect) {
                scaleY = screenAspect / videoAspect;
            } else {
                scaleX = videoAspect / screenAspect;
            }
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        texture.setTransform(matrix);
    }

    private void hideCover() {
        if (coverHidden) {
            return;
        }
        coverHidden = true;
        visualReady = true;
        handler.removeCallbacks(visualFallback);
        startMusicIfReady();
        cover.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> cover.setVisibility(GONE)).start();
    }

    private void showLoading(boolean show) {
        boolean visible = show && errorText.getVisibility() != VISIBLE;
        spinner.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            spinner.start();
        } else {
            spinner.stop();
        }
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(VISIBLE);
        spinner.setVisibility(GONE);
        spinner.stop();
        playGlyph.setVisibility(GONE);
    }

    private void updatePlayGlyph() {
        if (work != null && work.isAlbum()) {
            playGlyph.setVisibility(albumPaused ? VISIBLE : GONE);
            playGlyph.animate().alpha(albumPaused ? 1f : 0f).setDuration(120).start();
            return;
        }
        boolean paused = !playing && prepared && errorText.getVisibility() != VISIBLE;
        playGlyph.setVisibility(paused ? VISIBLE : GONE);
        playGlyph.animate().alpha(paused ? 1f : 0f).setDuration(120).start();
    }

    // ---------- 播放控制 ----------
    public void setRole(int role) {
        this.role = role;
        if (role == 0) {
            // 滑回这一屏时对一下收藏状态
            refreshFavoriteState();
            if (work != null && work.isAlbum()) {
                scheduleAlbumTicker();
                if (!albumPaused) {
                    resumeAlbumLive();
                    startMusicIfReady();
                }
            } else if (prepared && !userPaused && hostResumed) {
                startPlayback();
            } else if (!prepared && work != null && work.detailsLoaded && !work.empty) {
                if (work.isVideo()) {
                    prepareVideo();
                } else {
                    prepareAlbum();
                }
            }
        } else {
            pauseInternal();
            pauseAlbumLive();
            handler.removeCallbacks(albumTicker);
            if (musicPlayer != null) {
                try {
                    musicPlayer.pause();
                } catch (Exception ignored) {
                }
            }
            if (role == 1 && !prepared && work != null && work.detailsLoaded
                    && work.isVideo() && !work.empty) {
                prepareVideo();
            }
        }
        updatePlayGlyph();
    }

    private void startPlayback() {
        if (player == null || !prepared) {
            return;
        }
        try {
            player.start();
            playing = true;
            userPaused = false;
            playGlyph.setVisibility(GONE);
            if (speedBoost) {
                applySpeed(SpeedNav.BOOST);
            }
        } catch (Exception ignored) {
        }
        startMusicIfReady();
    }

    private void pauseInternal() {
        if (player != null && playing) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
            playing = false;
        }
        // 长按着直接切后台时松手事件可能收不到，别把跳帧那阵的静音留在页面上
        endSeekBoost();
        // 图集页没有 player，停的是动图播放器
        pauseAlbumLive();
        if (musicPlayer != null) {
            try {
                musicPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    /** 来电/耳机拔出等音频焦点变化：暂停但不记住「用户暂停」。 */
    public void pauseForFocus() {
        if (role != 0) {
            return;
        }
        pauseInternal();
        if (musicPlayer != null) {
            try {
                musicPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    public void resumeForFocus() {
        if (role != 0 || userPaused || !hostResumed) {
            return;
        }
        if (prepared && !playing) {
            startPlayback();
        }
        startMusicIfReady();
    }

    public void togglePlay() {
        if (work == null) {
            return;
        }
        if (errorText.getVisibility() == VISIBLE) {
            errorText.setVisibility(GONE);
            detailsRequested = false;
            showLoading(true);
            requestDetailsIfNeeded();
            return;
        }
        if (work.isAlbum()) {
            if (albumPaused) {
                resumeAlbum();
            } else {
                pauseAlbum();
            }
            return;
        }
        if (playing) {
            pauseInternal();
            userPaused = true;
            updatePlayGlyph();
        } else {
            startPlayback();
        }
    }

    public void pause() {
        handler.removeCallbacks(albumTicker);
        pauseInternal();
        if (musicPlayer != null) {
            try {
                musicPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    public void resume() {
        if (role == 0 && hostResumed) {
            if (work != null && work.isAlbum()) {
                resumeAlbumLive();
                startMusicIfReady();
                scheduleAlbumTicker();
            } else if (prepared && !userPaused) {
                startPlayback();
            }
        }
    }

    public void setHostResumed(boolean resumed) {
        hostResumed = resumed;
        if (!resumed) {
            pause();
        } else if (role == 0) {
            resume();
        }
    }

    public void setMuted(boolean value) {
        muted = value;
        float volume = muted ? 0f : 1f;
        if (player != null) {
            try {
                player.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
        if (musicPlayer != null) {
            try {
                musicPlayer.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
        updateMuteIcon();
    }

    private void updateMuteIcon() {
        if (muteIcon == null) {
            return;
        }
        muteIcon.setImageResource(muted ? R.drawable.ic_mute : R.drawable.ic_volume);
    }

    public void setSpeedBoost(boolean boost) {
        if (!boost) {
            // 松手：跳帧期间音频是静音的，先把音量还回去
            endSeekBoost();
        }
        speedBoost = boost;
        // 每次长按都从头试：上一次长按留下的「不支持」结论不能带到这一次
        speedNav.reset();
        applySpeed(boost ? SpeedNav.BOOST : 1f);
    }

    /**
     * 设置播放倍速。
     *
     * <p>几个坑：</p>
     * <ul>
     *   <li>必须在 start() 之后设置，Prepared 状态下设置会被当成「开始播放」；</li>
     *   <li>缓冲、seek、切后台回来都可能把倍速打回 1×，所以要配合 {@link #keepSpeed()} 补；</li>
     *   <li>别复用 getPlaybackParams() 返回的对象，新建一个更稳（避免继承到设备不认的 fallback 模式），
     *       但「沿用当前参数只改速度」可以作为最后一种兜底写法；</li>
     *   <li>抛异常 ≠ 这台机器不支持：没 Surface、正在 prepare / 缓冲、视频没音轨、pitch 不被接受
     *       都会抛。所以失败要交给 {@link SpeedNav} 换写法重试，试到上限才下结论。</li>
     * </ul>
     */
    private void applySpeed(float speed) {
        if (speed <= 1.001f) {
            restoreSpeed();
            return;
        }
        if (player == null || !prepared) {
            // 还没准备好：speedBoost 已经记下，onPrepared 里会补设。
            // 这里不能算失败，否则「还没准备好」会被误报成「不支持倍速」。
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // 老系统没有 PlaybackParams，但「定时往前 seek」照样能加速，别直接判死刑
            if (!speedNav.inSeek()) {
                startSeekBoost(SystemClock.uptimeMillis());
            } else {
                stepSeekBoost();
            }
            return;
        }
        if (speedNav.inSeek()) {
            // 已经确认参数写法不管用，后面全走跳帧
            stepSeekBoost();
            return;
        }
        if (surface == null) {
            // 视频轨没有渲染器时设置倍速必定抛 INVALID_OPERATION（不是不支持），
            // 等 Surface 就绪会重新补；这种失败一次都不该记
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!speedNav.shouldAttempt(now)) {
            return;
        }
        speedNav.markAttempt(now);
        int variant = speedNav.variant();
        try {
            player.setPlaybackParams(buildSpeedParams(variant));
            // 不抛异常 ≠ 真的加速了：记下这一刻的位置，700ms 后拿进度自己验证
            speedNav.onParamsApplied(now, positionMs());
            android.util.Log.i("JYWatch", "倍速参数被接受 " + speed + "×（写法 " + variant + "），待实测");
        } catch (Exception exc) {
            speedNav.onFailure();
            android.util.Log.w("JYWatch", "setPlaybackParams 失败（写法 " + variant + "，第 "
                    + speedNav.failures() + " 次）：" + exc
                    + " playing=" + playing + " prepared=" + prepared + " surface=" + (surface != null));
            if (speedNav.wantsSeek()) {
                if (speedNav.canRebuild()) {
                    // 和实测失败一样，先给一次重建播放器的机会，别直接掉到有损的跳帧
                    speedNav.markRebuilt();
                    rebuildForSpeed(positionMs());
                } else {
                    android.util.Log.i("JYWatch", "参数写法连续失败 " + speedNav.failures()
                            + " 次且已重建过，改走跳帧倍速");
                    startSeekBoost(now);
                }
            }
        }
        refreshSpeedLabel();
    }

    /**
     * 三种写法依次降级。
     *
     * <p>顺序很关键：<b>先沿用播放器当前的参数对象只改速度</b>。
     * 官方文档和 AOSP 示例都是 `mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(x))`，
     * 因为这个对象里带着引擎已初始化的内部模式（audio fallback / sync engine）；
     * 而 `new PlaybackParams()` 是个干净对象，部分机型会**接受但不执行**——
     * 不抛异常、`getPlaybackParams()` 也回报 2.0，画面却还是原速。
     * 之前把这个写法排在最后，等于永远试不到它，这就是「提示 2× 画面原速」的根因之一。</p>
     *
     * <p>0 沿用当前参数 + 保持音调 / 1 新建带 pitch / 2 新建只给 speed。</p>
     */
    private PlaybackParams buildSpeedParams(int variant) {
        if (variant <= 0) {
            try {
                PlaybackParams current = player.getPlaybackParams();
                if (current != null) {
                    return current.setSpeed(SpeedNav.BOOST).setPitch(1f);
                }
            } catch (Exception ignored) {
            }
        }
        if (variant <= 1) {
            return new PlaybackParams().setSpeed(SpeedNav.BOOST).setPitch(1f);
        }
        return new PlaybackParams().setSpeed(SpeedNav.BOOST);
    }

    /** 松手恢复 1×。恢复失败无所谓，也不该影响「支持 / 不支持」的判定。 */
    private void restoreSpeed() {
        if (player == null || !prepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(1f).setPitch(1f));
            return;
        } catch (Exception ignored) {
        }
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(1f));
        } catch (Exception ignored) {
        }
    }

    /** 正在 2× 时，每次进度刷新检查一下倍速有没有被打回 1×，被打回就补上。 */
    private void keepSpeed() {
        if (!speedBoost || speedNav.unsupported() || player == null || !prepared) {
            return;
        }
        if (speedNav.inSeek()) {
            // 跳帧模式：进度每刷一次就往前补一段
            stepSeekBoost();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long pos = positionMs();
        if (speedNav.shouldProbe(now) && speedNav.probeIgnored(now, pos)) {
            // 参数被接受、getPlaybackParams 也回报 2×，但画面还是原速——
            // 这就是「提示说 2× 却看不出变化」的真正来源。
            // 不过 getCurrentPosition() 不是每台设备都实时，先给第二次机会再下结论，
            // 免得把「位置还没刷新」误判成「没加速」，白白把平滑的 2× 换成跳帧。
            if (!speedNav.suspect(now, pos)) {
                android.util.Log.i("JYWatch", "倍速实测可疑（700ms 只走了 " + pos + "ms），再测一轮");
                return;
            }
            android.util.Log.w("JYWatch", "两轮实测都没加速（位置 " + pos + "ms）");
            if (speedNav.canRebuild()) {
                // 有些机型第一次设倍速就是不生效，重建播放器再设才行（比重写引擎便宜，
                // 也比跳帧平滑），所以排在跳帧前面
                speedNav.markRebuilt();
                rebuildForSpeed(pos);
                return;
            }
            android.util.Log.w("JYWatch", "重建后仍不生效，改走跳帧");
            startSeekBoost(now);
            return;
        }
        if (surface == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            if (Math.abs(player.getPlaybackParams().getSpeed() - SpeedNav.BOOST) < 0.01f) {
                return;
            }
        } catch (Exception ignored) {
        }
        applySpeed(SpeedNav.BOOST);
    }

    /**
     * 为倍速重建播放器：释放当前实例，从原来的位置重新 prepare。
     *
     * <p>华为等机型的已知行为：第一次 {@code setPlaybackParams} 不生效——不抛异常、
     * {@code getPlaybackParams()} 也回报 2.0，画面却是原速；把 MediaPlayer 释放重建、
     * 重新 prepare 之后再设就正常了。代价是短暂重新加载，换回来的是真正平滑的 2×，
     * 比跳帧好得多，所以排在跳帧前面。</p>
     */
    private void rebuildForSpeed(long positionMs) {
        if (pageDestroyed || work == null) {
            return;
        }
        android.util.Log.i("JYWatch", "为倍速重建播放器（回到 " + positionMs + "ms）");
        resumeAtMs = Math.max(0L, positionMs);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
        playing = false;
        settingUp = false;
        coverHidden = false;
        showLoading(true);
        prepareVideo();
    }

    /**
     * 参数写法确认无效：改走「定时往前 seek」的跳帧倍速。
     *
     * <p>播放器本身还在 1× 往前走，每 {@link SpeedNav#SEEK_INTERVAL_MS} 补一段差值，
     * 叠加起来就是 2×。比 {@code setPlaybackParams} 笨，但任何设备都一定有效。</p>
     */
    private void startSeekBoost(long now) {
        speedNav.beginSeek(now, positionMs());
        // 跳帧时音频会一顿一顿，长按这点时间里静音更自然；松手由 endSeekBoost 还原
        try {
            player.setVolume(0f, 0f);
        } catch (Exception ignored) {
        }
        refreshSpeedLabel();
        stepSeekBoost();
    }

    private void stepSeekBoost() {
        if (player == null || !prepared) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long pos = positionMs();
        if (!speedNav.hasSeekBase() || speedNav.landedShort(pos)) {
            // 缓冲/起播会把基准作废；上一步 seek 落到更前面的关键帧也会让基准失真。
            // 两种情况下都重取基准，否则会越追越偏、画面来回倒退
            speedNav.beginSeek(now, pos);
        }
        long target = speedNav.seekTarget(now, pos, durationMs());
        if (target < 0) {
            return;
        }
        speedNav.markSeek(now, target);
        try {
            seekClosest((int) target);
        } catch (Exception exc) {
            android.util.Log.w("JYWatch", "跳帧倍速 seek 失败：" + exc);
            speedNav.giveUp();
            refreshSpeedLabel();
        }
    }

    /**
     * 精确跳转。默认的 {@code seekTo(int)} 语义是「跳到前一个关键帧」，
     * 短视频 GOP 常有 1～4 秒，于是每跳一次都先退回好几秒再往前播——
     * 画面就成了反复倒退 + 黑闪，这正是跳帧倍速看起来「卡顿」的主因。
     */
    private void seekClosest(int targetMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo(targetMs);
        }
    }

    /** 松手 / 切页：退出跳帧，音量按当前静音设置还原。 */
    private void endSeekBoost() {
        if (player == null) {
            return;
        }
        if (speedNav.inSeek()) {
            android.util.Log.i("JYWatch", "退出跳帧倍速，位置 " + positionMs() + "ms");
        }
        try {
            player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        } catch (Exception ignored) {
        }
    }

    // ---------- 进度 ----------
    public long durationMs() {
        if (work == null) {
            return 0;
        }
        if (work.isAlbum()) {
            return work.images.size() * ALBUM_INTERVAL;
        }
        if (player != null && prepared) {
            try {
                return Math.max(0L, player.getDuration());
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    public long positionMs() {
        if (work == null) {
            return 0;
        }
        if (work.isAlbum()) {
            return albumIndex * ALBUM_INTERVAL;
        }
        if (player != null && prepared) {
            try {
                return Math.max(0L, player.getCurrentPosition());
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    public void updateProgress() {
        if (work == null) {
            return;
        }
        if (work.isAlbum()) {
            if (work.images.size() > 0) {
                progressLine.setProgress((albumIndex + 1f) / work.images.size());
            }
            updateProgressTime();
            return;
        }
        long duration = durationMs();
        if (duration > 0) {
            progressLine.setProgress(positionMs() / (float) duration);
        }
        keepSpeed();
        updateProgressTime();
    }

    /** 进度条下面常显「当前 / 总时长」；拖动时让气泡代替它。 */
    private void updateProgressTime() {
        if (progressTime == null) {
            return;
        }
        if (work == null || timePill.getVisibility() == VISIBLE) {
            setProgressTimeVisible(false);
            return;
        }
        String text;
        if (work.isAlbum()) {
            int total = work.images.size();
            if (total <= 0) {
                setProgressTimeVisible(false);
                return;
            }
            int current = Math.min(total, albumIndex + 1);
            text = "第 " + current + " 张 / 共 " + total + " 张";
        } else {
            long duration = durationMs();
            if (duration <= 0) {
                setProgressTimeVisible(false);
                return;
            }
            text = Util.formatDuration(positionMs()) + " / " + Util.formatDuration(duration);
        }
        // ticker 每 220ms 调一次：文字没变就别 setText，否则每次都 requestLayout，
        // 三块全屏页面跟着重新测量，图集页更是白忙。
        if (!text.contentEquals(progressTime.getText())) {
            progressTime.setText(text);
        }
        setProgressTimeVisible(true);
    }

    private void setProgressTimeVisible(boolean visible) {
        if (progressTime == null) {
            return;
        }
        int target = visible ? VISIBLE : GONE;
        if (progressTime.getVisibility() != target) {
            progressTime.setVisibility(target);
        }
    }

    public void seekTo(long millis) {
        if (work != null && work.isVideo() && player != null && prepared) {
            try {
                player.seekTo((int) Math.max(0L, millis));
                hideCover();
            } catch (Exception ignored) {
            }
        }
    }

    public void showScrub(long position, long duration) {
        if (duration <= 0) {
            return;
        }
        progressLine.setProgress(position / (float) duration);
        timePill.setText(Util.formatDuration(position) + " / " + Util.formatDuration(duration));
        timePill.setVisibility(VISIBLE);
        if (progressTime != null) {
            progressTime.setVisibility(GONE);
        }
    }

    public void hideScrub() {
        timePill.setVisibility(GONE);
        updateProgressTime();
    }

    public boolean isScrubbable() {
        return work != null && work.isVideo() && prepared;
    }

    // ---------- 命中测试 ----------
    public int hitTest(float x, float y) {
        if (isInProgressTouchZone(y)) {
            return HIT_PROGRESS;
        }
        if (isInside(backIcon, x, y, 26)) {
            return HIT_BACK;
        }
        // 静音和下载两个图标中心只隔 42dp，命中区外扩超过 10dp 就会互相抢点击
        if (isInside(muteIcon, x, y, 10)) {
            return HIT_MUTE;
        }
        if (isInside(downloadIcon, x, y, 10)) {
            return HIT_DOWNLOAD;
        }
        // 命中区外扩 26dp 后相邻按钮会重叠，判定顺序必须和侧栏自上而下的顺序一致，
        // 否则点在两个按钮中间会落到下面那个按钮上
        if (isInside(favoriteIcon, x, y, 26)) {
            return HIT_FAVORITE;
        }
        if (isInside(shareIcon, x, y, 26)) {
            return HIT_SHARE;
        }
        if (isInside(detailIcon, x, y, 26)) {
            return HIT_DETAIL;
        }
        if (isInside(autoIcon, x, y, 26)) {
            return HIT_AUTO_DOWNLOAD;
        }
        if (isInside(avatarBox, x, y, 8)) {
            return HIT_AVATAR;
        }
        return HIT_NONE;
    }

    /** 进度条的可触摸范围（已整体上移，避开系统底部手势）。 */
    public boolean isInProgressTouchZone(float y) {
        // 只留 26dp 窄带：原来往上一路吃到 34dp，会把「背景音乐」那一行也框进来，
        // 点音乐行下半截会莫名其妙跳进度。
        return progressLine.getVisibility() == VISIBLE
                && y > progressLine.getTop() - Ui.dp(getContext(), 16)
                && y < progressLine.getTop() + Ui.dp(getContext(), 10);
    }

    private boolean isInside(View view, float x, float y, int paddingDp) {
        if (view == null || view.getVisibility() != VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationInWindow(location);
        int padding = Ui.dp(getContext(), paddingDp);
        float left = location[0] - padding;
        float top = location[1] - padding;
        float right = location[0] + view.getWidth() + padding;
        float bottom = location[1] + view.getHeight() + padding;
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    public float progressFractionAt(float x) {
        float width = Math.max(1, progressLine.getWidth());
        return Math.max(0f, Math.min(1f, x / width));
    }

    // ---------- 释放 ----------
    public void releasePlayers() {
        handler.removeCallbacks(albumTicker);
        handler.removeCallbacks(musicRetry);
        releaseAlbumLive();
        liveShown = false;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (musicPlayer != null) {
            releaseMusicPlayer();
        }
        prepared = false;
        playing = false;
        settingUp = false;
        musicPrepared = false;
        visualReady = false;
        albumPaused = false;
        speedBoost = false;
        resumeAtMs = -1L;
        speedNav.reset();
        handler.removeCallbacks(albumTicker);
        handler.removeCallbacks(visualFallback);
    }

    public void destroy() {
        pageDestroyed = true;
        releasePlayers();
        handler.removeCallbacksAndMessages(null);
    }

    /** 只释放背景音乐（视频播放器不动）：音乐出错时释放掉，下次 bind 还能重新试。 */
    private void releaseMusicPlayer() {
        if (musicPlayer != null) {
            try {
                musicPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                musicPlayer.release();
            } catch (Exception ignored) {
            }
            musicPlayer = null;
        }
        musicPrepared = false;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setSpeedLabel(boolean show) {
        if (!show) {
            timePill.setVisibility(GONE);
            return;
        }
        timePill.setVisibility(VISIBLE);
        // 文案按真实状态算：以前只在按下的那一瞬间写一次死文本，第一次设置失败就
        // 一直显示「倍速不可用」，哪怕 300ms 后重试其实成功了。
        refreshSpeedLabel();
    }

    /** 倍速提示只反映真实状态：还在重试 / 已生效都写「2× 播放中」，真试到上限才说不支持。 */
    private void refreshSpeedLabel() {
        if (timePill == null || timePill.getVisibility() != VISIBLE) {
            return;
        }
        timePill.setText(speedNav.label());
    }

    /** 播放到最后一个作品时停在当前屏，露出播放按钮，方便重看。 */
    public void showEndedState() {
        playing = false;
        userPaused = true;
        playGlyph.setVisibility(VISIBLE);
        playGlyph.setAlpha(1f);
        progressLine.setProgress(0f);
    }
}
