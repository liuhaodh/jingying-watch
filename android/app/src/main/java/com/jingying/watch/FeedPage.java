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
 * 底部作者/文案/音乐/进度，右侧点赞收藏分享，布局对齐抖音。
 */
public final class FeedPage extends FrameLayout {

    public static final int HIT_NONE = 0;
    public static final int HIT_BACK = 1;
    public static final int HIT_MUTE = 2;
    public static final int HIT_COMMENT = 4;
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
    /** 长按倍速。 */
    private static final float BOOST_SPEED = 2f;
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
    private ImageView commentIcon;
    private ImageView shareIcon;
    private ImageView favoriteIcon;
    private TextView favoriteLabel;
    private ImageView playIcon;
    private ImageView musicIcon;
    private LoadingDotsView spinner;
    private ProgressLineView progressLine;

    private LinearLayout rail;
    private LinearLayout bottomBlock;
    private LinearLayout avatarBox;

    private MediaPlayer player;
    private MediaPlayer musicPlayer;
    private boolean musicPrepared;
    private boolean visualReady;
    private boolean albumPaused;
    private final Runnable visualFallback = () -> {
        visualReady = true;
        startMusicIfReady();
    };
    private Work work;
    private int index;
    private int role;
    private int albumIndex;
    private String counterSuffix = "";
    private boolean prepared;
    private boolean playing;
    private boolean userPaused;
    private boolean muted;
    private boolean detailsRequested;
    private boolean settingUp;
    private boolean coverHidden;
    private boolean speedBoost;
    private int speedFailures;
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
                applyVideoTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                applyVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (player != null) {
                    try {
                        player.setSurface(null);
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
        commentIcon = railIcon(R.drawable.ic_comment, "评论");
        rail.addView((View) commentIcon.getParent());
        rail.addView(railSpacer(14));
        shareIcon = railIcon(R.drawable.ic_share, "分享");
        rail.addView((View) shareIcon.getParent());
        rail.addView(railSpacer(14));
        favoriteIcon = railIcon(R.drawable.ic_star, "收藏");
        favoriteLabel = (TextView) ((ViewGroup) favoriteIcon.getParent()).getChildAt(1);
        rail.addView((View) favoriteIcon.getParent());
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
        this.index = index;
        counterSuffix = " / " + total;
        prepared = false;
        playing = false;
        userPaused = false;
        detailsRequested = false;
        playAttempts = 0;
        speedBoost = false;
        speedFailures = 0;
        musicPrepared = false;
        visualReady = false;
        albumPaused = false;
        handler.removeCallbacks(visualFallback);
        coverHidden = false;
        albumIndex = 0;
        videoWidth = 0;
        videoHeight = 0;
        errorText.setVisibility(GONE);
        spinner.setVisibility(GONE);
        playGlyph.setVisibility(GONE);
        progressLine.setProgress(0f);
        if (progressTime != null) {
            progressTime.setText("");
            progressTime.setVisibility(GONE);
        }
        albumBadge.setVisibility(GONE);
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
                handler.removeCallbacks(visualFallback);
                errorText.setVisibility(GONE);
                showLoading(true);
                if (role == 0) {
                    prepareVideo();
                }
            }
        } else if (work.isAlbum()) {
            showAlbumImage(albumIndex, false);
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
    public boolean handleCaptionTap(float x, float y) {
        if (captionText == null || captionText.getVisibility() != VISIBLE
                || captionText.getLayout() == null || captionRanges.isEmpty()) {
            return false;
        }
        int[] pageLocation = new int[2];
        int[] captionLocation = new int[2];
        getLocationOnScreen(pageLocation);
        captionText.getLocationOnScreen(captionLocation);
        float localX = pageLocation[0] + x - captionLocation[0] - captionText.getPaddingLeft();
        float localY = pageLocation[1] + y - captionLocation[1] - captionText.getPaddingTop();
        if (localX < 0 || localY < 0 || localX > captionText.getWidth()
                || localY > captionText.getHeight()) {
            return false;
        }
        int line = captionText.getLayout().getLineForVertical((int) localY);
        if (line < 0 || line >= captionText.getLineCount()) {
            return false;
        }
        int offset = captionText.getLayout().getOffsetForHorizontal(line, localX);
        for (int index = 0; index < captionRanges.size(); index++) {
            int[] range = captionRanges.get(index);
            if (offset >= range[0] - 1 && offset <= range[1]) {
                if (listener != null) {
                    listener.onToken(this, captionTokens.get(index));
                }
                return true;
            }
        }
        return false;
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
            if (work.captionText != null && !work.captionText.isEmpty()) {
                setCaption(work.captionText);
            }
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
        if (prepared || settingUp || player != null || work == null || work.video == null) {
            return;
        }
        settingUp = true;
        try {
            java.io.File localVideo = app.offline.localVideo(work);
            String url = localVideo != null ? null
                    : app.proxy.urlFor(work.video.rel, work.video.name, work.video.size);
            MediaPlayer mp = new MediaPlayer();
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
                handler.removeCallbacks(visualFallback);
                handler.postDelayed(visualFallback, 1500L);
                videoWidth = mediaPlayer.getVideoWidth();
                videoHeight = mediaPlayer.getVideoHeight();
                applyVideoTransform();
                if (role == 0 && !userPaused && hostResumed) {
                    // 先设倍速再 start：部分机型只有在 Prepared 状态设置才真正生效
                    if (speedBoost) {
                        applySpeed(BOOST_SPEED);
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
            player = mp;
            mp.prepareAsync();
            if (localVideo == null && app.prefs.autoNext()) {
                // 预取下一屏的数据，滑过去就能直接播
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
        showAlbumImage(0, false);
        scheduleAlbumTicker();
        if (work.music == null && !work.musicChecked) {
            app.scanner.loadMusic(work, () -> {
                if (work == FeedPage.this.work) {
                    prepareMusic();
                }
            });
        } else {
            prepareMusic();
        }
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
        if (work == null || work.music == null || musicPlayer != null) {
            musicIcon.setVisibility(GONE);
            return;
        }
        try {
            java.io.File localMusic = app.offline.localMusic(work);
            String url = localMusic != null ? null
                    : app.proxy.urlFor(work.music.rel, work.music.name, work.music.size);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setLooping(true);
            mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            if (localMusic != null) {
                mp.setDataSource(localMusic.getAbsolutePath());
            } else {
                mp.setDataSource(getContext(), Uri.parse(url));
            }
            mp.prepareAsync();
            mp.setOnPreparedListener(mediaPlayer -> {
                musicPrepared = true;
                startMusicIfReady();
            });
            musicPlayer = mp;
            musicIcon.setVisibility(VISIBLE);
            musicText.setText("背景音乐");
        } catch (Exception exc) {
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
        RemoteFile file = work.images.get(index);
        final int target = index;
        final Work boundWork = work;
        ImageLoader.Callback albumCallback = (key, bitmap) -> {
            if (bitmap == null || work == null || work != boundWork || albumIndex != target) {
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
            startMusicIfReady();
        };
        java.io.File localImage = app.offline.localImage(work, index);
        if (localImage != null) {
            images.loadLocalImage(localImage, Ui.screenWidth(getContext()), albumCallback);
        } else {
            images.loadImage(file, Ui.screenWidth(getContext()), albumCallback);
        }
        albumBadge.setVisibility(work.isAlbum() ? VISIBLE : GONE);
        albumBadge.setText("图集 " + (index + 1) + "/" + work.images.size());
        progressLine.setProgress((index + 1f) / work.images.size());
        updateProgressTime();
    }

    private void scheduleAlbumTicker() {
        handler.removeCallbacks(albumTicker);
        if (work == null || !work.isAlbum() || albumPaused
                || work.images.size() <= 1 || role != 0 || !hostResumed) {
            return;
        }
        handler.postDelayed(albumTicker, ALBUM_INTERVAL);
    }

    private final Runnable albumTicker = new Runnable() {
        @Override
        public void run() {
            if (work == null || !work.isAlbum() || albumPaused
                    || role != 0 || !hostResumed) {
                return;
            }
            showAlbumImage((albumIndex + 1) % work.images.size(), true);
            handler.postDelayed(this, ALBUM_INTERVAL);
        }
    };

    public void advanceAlbum() {
        if (work != null && work.isAlbum() && !work.images.isEmpty()) {
            showAlbumImage((albumIndex + 1) % work.images.size(), true);
        }
    }

    public void previousAlbum() {
        if (work != null && work.isAlbum() && !work.images.isEmpty()) {
            showAlbumImage((albumIndex - 1 + work.images.size()) % work.images.size(), true);
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
                applySpeed(2f);
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
        speedBoost = boost;
        if (boost) {
            speedFailures = 0;
        }
        applySpeed(boost ? BOOST_SPEED : 1f);
    }

    /**
     * 设置播放倍速。
     *
     * 几个坑：
     * - 必须在 start() 之后设置，Prepared 状态下设置会被当成「开始播放」；
     * - 缓冲、seek、切后台回来都可能把倍速打回 1×，所以要配合 {@link #keepSpeed()} 补；
     * - 别复用 getPlaybackParams() 返回的对象，新建一个更稳（避免继承到设备不认的 fallback 模式）。
     */
    private void applySpeed(float speed) {
        if (player == null || !prepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PlaybackParams params = new PlaybackParams()
                    .setSpeed(speed)
                    .setPitch(1f);
            player.setPlaybackParams(params);
            speedFailures = 0;
            android.util.Log.i("JYWatch", "倍速已设置为 " + speed + "×");
        } catch (Exception exc) {
            speedFailures++;
            android.util.Log.w("JYWatch", "setPlaybackParams(" + speed + ") 失败："
                    + exc + " playing=" + playing + " prepared=" + prepared);
        }
    }

    /** 正在 2× 时，每帧检查一下倍速有没有被打回 1×，被打回就补上。 */
    private void keepSpeed() {
        if (!speedBoost || speedFailures > 3 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || player == null || !prepared) {
            return;
        }
        try {
            if (Math.abs(player.getPlaybackParams().getSpeed() - BOOST_SPEED) > 0.01f) {
                applySpeed(BOOST_SPEED);
            }
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
            progressTime.setVisibility(GONE);
            return;
        }
        if (work.isAlbum()) {
            int total = work.images.size();
            if (total <= 0) {
                progressTime.setVisibility(GONE);
                return;
            }
            int current = Math.min(total, albumIndex + 1);
            progressTime.setText("第 " + current + " 张 / 共 " + total + " 张");
            progressTime.setVisibility(VISIBLE);
            return;
        }
        long duration = durationMs();
        if (duration <= 0) {
            progressTime.setVisibility(GONE);
            return;
        }
        progressTime.setText(Util.formatDuration(positionMs()) + " / "
                + Util.formatDuration(duration));
        progressTime.setVisibility(VISIBLE);
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
        if (isInside(muteIcon, x, y, 26)) {
            return HIT_MUTE;
        }
        if (isInside(downloadIcon, x, y, 26)) {
            return HIT_DOWNLOAD;
        }
        if (isInside(commentIcon, x, y, 26)) {
            return HIT_COMMENT;
        }
        if (isInside(shareIcon, x, y, 26)) {
            return HIT_SHARE;
        }
        if (isInside(favoriteIcon, x, y, 26)) {
            return HIT_FAVORITE;
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
        return progressLine.getVisibility() == VISIBLE
                && y > progressLine.getTop() - Ui.dp(getContext(), 34)
                && y < progressLine.getTop() + Ui.dp(getContext(), 12);
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
        prepared = false;
        playing = false;
        settingUp = false;
        musicPrepared = false;
        visualReady = false;
        albumPaused = false;
        speedBoost = false;
        speedFailures = 0;
        handler.removeCallbacks(albumTicker);
        handler.removeCallbacks(visualFallback);
    }

    public void destroy() {
        releasePlayers();
        handler.removeCallbacksAndMessages(null);
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setSpeedLabel(boolean show) {
        // 设置倍速抛异常时别再显示「2× 播放中」骗人，直接说明不支持
        timePill.setText(show && speedFailures > 0 ? "倍速不可用" : "2× 播放中");
        timePill.setVisibility(show ? VISIBLE : GONE);
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
