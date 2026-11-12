package com.jingying.watch;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 抖音式竖屏播放页：滑一下换一个作品。 */
public final class FeedActivity extends android.app.Activity implements FeedPager.Listener {

    private FrameLayout root;
    private FeedPager pager;
    private App app;
    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int storeRevision;
    /** 回收站预览：作品不在片库里，下载/收藏/删除都禁用。 */
    private boolean preview;
    /** 分享打包：一次只跑一个，Activity 销毁时让后台任务自己收尾。 */
    private boolean sharing;
    private volatile boolean shareCancelled;
    private android.app.AlertDialog shareBusy;
    private AudioManager audioManager;
    private boolean focusHeld;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (pager == null) {
            return;
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pager.pauseForFocus();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            pager.resumeForFocus();
        }
    };
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && pager != null) {
                pager.pauseForFocus();
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                // 退到后台就停掉，别每 220ms 空转把自己唤醒
                return;
            }
            if (pager != null) {
                pager.updateProgress();
            }
            handler.postDelayed(this, 220L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        app = App.get();
        prefs = app.prefs;

        List<Work> items = FeedSession.items();
        if (items.isEmpty()) {
            Ui.toast(this, "片库里还没有作品");
            finish();
            return;
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pager = new FeedPager(this);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        Ui.enterImmersive(this);

        pager.setListener(this);
        app.offline.addListener(offlineListener);
        pager.setMuted(prefs.muted());
        pager.setItems(items, FeedSession.index());
        preview = FeedSession.isPreview();
        if (preview) {
            pager.setPreviewMode(true);
            Ui.toast(this, "回收站预览：恢复后才能下载、收藏");
        }
        storeRevision = app.store.revision();
        if (!preview) {
            // setItems 不一定触发 onPageChanged；这里补一次，打开就预热后面十个
            prefetchAhead(FeedSession.index());
        }
        handler.postDelayed(ticker, 300L);
    }

    @Override
    public void onPageChanged(int index, Work work) {
        if (preview) {
            return;
        }
        FeedSession.setIndex(index);
        if (work != null) {
            prefs.setLast(work.key(), index);
        }
        pager.setMuted(prefs.muted());
        prefetchAhead(index);
        maybeAutoDownload(work);
    }

    /** 播放时提前把后面 10 个作品的详情读出来，并给普通视频预热开头 2MB。 */
    private static final int PREFETCH_AHEAD = 10;
    private static final long PREFETCH_BYTES = 2L * 1024 * 1024;

    private void prefetchAhead(int index) {
        for (int offset = 1; offset <= PREFETCH_AHEAD; offset++) {
            Work work = pager.workAt(index + offset);
            if (work == null) {
                break;
            }
            if (work.detailsLoaded) {
                prefetchMedia(work);
            } else if (!work.empty) {
                app.scanner.loadDetails(work, (target, ok, error) -> {
                    if (ok) {
                        prefetchMedia(target);
                    }
                });
            }
        }
    }

    /**
     * 给后面作品的视频预热开头一小段（整段下载会吃掉带宽和存储）。
     * 作品包的视频要先解包才能播，预热只能到「详情」为止；切到该条时再解。
     */
    private void prefetchMedia(Work work) {
        if (work == null || work.video == null) {
            return;
        }
        if (work.video.inArchive()) {
            return;
        }
        app.proxy.prefetchPrefix(work.video.rel, work.video.name, work.video.size,
                PREFETCH_BYTES);
    }

    @Override
    public void onTap(FeedPage page, int target, float x, float y) {
        switch (target) {
            case FeedPage.HIT_BACK:
                finish();
                break;
            case FeedPage.HIT_MUTE:
                toggleMute();
                break;
            case FeedPage.HIT_DETAIL:
                showInfo(page);
                break;
            case FeedPage.HIT_SHARE:
                share(page);
                break;
            case FeedPage.HIT_FAVORITE:
                if (preview) {
                    Ui.toast(this, "回收站预览：恢复后才能收藏");
                } else {
                    toggleFavorite(page);
                }
                break;
            case FeedPage.HIT_DOWNLOAD:
                if (preview) {
                    Ui.toast(this, "回收站预览：恢复后才能下载");
                } else {
                    downloadOrDelete(page);
                }
                break;
            case FeedPage.HIT_AUTO_DOWNLOAD:
                if (preview) {
                    Ui.toast(this, "回收站预览：恢复后才能自动下载");
                } else {
                    toggleAutoDownload(page);
                }
                break;
            case FeedPage.HIT_AVATAR:
                openAuthorPage(page);
                break;
            case FeedPage.HIT_NONE:
            default:
                page.togglePlay();
                break;
        }
    }

    @Override
    public void onDoubleTap(FeedPage page, float x, float y) {
        if (preview) {
            Ui.toast(this, "回收站预览：恢复后才能收藏");
            return;
        }
        Work work = page == null ? null : page.work();
        if (work == null) {
            return;
        }
        // 双击是「点赞式收藏」：只加不减，再双击已收藏的作品不会误取消。
        if (app.favorites.isFavorite(work.rel)) {
            Ui.toast(this, "已在收藏夹");
        } else {
            app.favorites.toggle(work.rel);
            page.refreshFavoriteState();
            page.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            Ui.toast(this, "已收藏，可在首页「收藏」里看");
        }
        page.showFavoriteBurst(x, y);
    }

    @Override
    public void onToken(FeedPage page, String token) {
        if (preview || token == null || token.length() < 2 || pager == null) {
            return;
        }
        String value = token.substring(1).trim();
        List<Work> base = pager.items();
        List<Work> filtered = new ArrayList<>();
        if (token.startsWith("@")) {
            for (Work work : base) {
                if (work.displayAuthor().equalsIgnoreCase(value)) {
                    filtered.add(work);
                }
            }
        } else {
            String needle = token.toLowerCase(Locale.US);
            for (Work work : base) {
                // searchText() 是带缓存的规范化文本，和首页搜索同一套；
                // 以前每条都现拼 caption 再 toLowerCase，一万条就是几万个临时字符串
                if (work.searchText().contains(needle)) {
                    filtered.add(work);
                }
            }
        }
        if (filtered.isEmpty()) {
            Ui.toast(this, "没有找到 " + token + " 的作品");
            return;
        }
        if (filtered.size() == base.size()) {
            Ui.toast(this, "当前列表就包含 " + token);
            return;
        }
        pager.setItems(filtered, 0);
        FeedSession.update(filtered, 0);
        Ui.toast(this, "只看 " + token + " 的 " + filtered.size() + " 个作品");
    }

    @Override
    public void onLongPress(FeedPage page, boolean pressed) {
        if (pressed) {
            page.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    @Override
    public void onCompleted(FeedPage page) {
        if (pager == null) {
            page.seekTo(0);
            page.showEndedState();
            return;
        }
        int action = PlayNav.afterEnded(prefs.autoNext(),
                pager.position() + 1 < pager.size(),
                System.currentTimeMillis() - pager.lastTouchAt());
        if (action == PlayNav.IGNORE) {
            // 刚滑过/正在滑：不要因为上一条刚好播完再自动跳一条
            return;
        }
        if (action == PlayNav.NEXT) {
            pager.nextPage(true);
        } else {
            // 关了自动连播，或者已经是最后一条：停在结尾等用户重播
            page.seekTo(0);
            page.showEndedState();
        }
    }

    private final OfflineStore.Listener offlineListener = new OfflineStore.Listener() {
        @Override
        public void onOfflineChanged(Work work) {
            runOnUiThread(() -> {
                if (pager == null) {
                    return;
                }
                FeedPage page = pager.currentPage();
                if (page == null) {
                    return;
                }
                Work current = page.work();
                if (work != null && current != null && work.rel.equals(current.rel)) {
                    page.onOfflineReady();
                } else {
                    page.refreshDownloadState();
                }
            });
        }

        @Override
        public void onDownloadProgress(Work work, int percent) {
            onOfflineChanged(work);
        }
    };

    private void downloadOrDelete(FeedPage page) {
        final Work work = page.work();
        if (work == null) {
            return;
        }
        if (app.offline.isDownloading(work)) {
            Ui.toast(this, "正在下载…");
            return;
        }
        if (app.offline.has(work)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("本地下载")
                    .setMessage("这个作品已经下载到本地了。删除后需要重新联网下载，确定删除吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        app.offline.delete(work);
                        page.refreshDownloadState();
                        Ui.toast(this, "已删除本地文件");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!work.detailsLoaded) {
            Ui.toast(this, "正在读取作品信息…");
            app.scanner.loadDetails(work, (target, ok, error) -> {
                if (!ok) {
                    Ui.toast(this, error == null ? "作品信息读取失败" : error);
                    return;
                }
                app.offline.download(work);
                page.refreshDownloadState();
                Ui.toast(this, "已加入本地下载");
            });
            return;
        }
        app.offline.download(work);
        page.refreshDownloadState();
        Ui.toast(this, "已加入本地下载，完成后可离线观看");
    }

    /** 删除当前作品：先确认，再移入云端回收站，成功后把这一条从播放列表里去掉。 */
    private void confirmDeleteWork(FeedPage page) {
        final Work work = page == null ? null : page.work();
        if (work == null) {
            return;
        }
        Ui.confirm(this, "移入回收站",
                "将从片库移除：\n" + work.displayTitle() + "\n\n"
                        + "• 云端文件会移到「回收站」，可以随时恢复\n"
                        + "• 本地下载和播放缓存会清除\n"
                        + "• 回收站里可以彻底删除",
                "移入回收站", () -> moveToTrash(work));
    }

    private void moveToTrash(Work work) {
        final android.app.AlertDialog busy = Ui.busy(this, "正在移入回收站…");
        app.trash.moveWorkAsync(work, result -> {
            Ui.dismiss(busy);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result.ok) {
                Ui.toast(this, "已移入回收站");
                dropCurrentPage();
                return;
            }
            if (result.moveUnsupported) {
                confirmDeleteForever(work);
                return;
            }
            Ui.toast(this, result.error == null ? "操作失败" : result.error);
        });
    }

    /** 服务器不支持 MOVE 时的兜底：明确告知后彻底删除。 */
    private void confirmDeleteForever(Work work) {
        Ui.confirm(this, "网盘不支持回收站",
                "这个 WebDAV 服务器不支持移动文件（MOVE），没法把作品放进回收站。\n\n"
                        + "要直接彻底删除吗？删除后无法恢复。",
                "彻底删除", () -> {
                    final android.app.AlertDialog busy = Ui.busy(this, "正在删除…");
                    app.trash.deleteForeverAsync(work, result -> {
                        Ui.dismiss(busy);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!result.ok) {
                            Ui.toast(this, result.error == null ? "删除失败" : result.error);
                            return;
                        }
                        Ui.toast(this, "已删除");
                        dropCurrentPage();
                    });
                });
    }

    private void dropCurrentPage() {
        if (pager == null) {
            return;
        }
        pager.removeCurrent();
        if (pager.size() == 0) {
            finish();
        }
    }

    /** 收藏 / 取消收藏当前作品；收藏夹在首页右上角「收藏」里。 */
    private void toggleFavorite(FeedPage page) {
        Work work = page == null ? null : page.work();
        if (work == null) {
            return;
        }
        boolean favorite = app.favorites.toggle(work.rel);
        page.refreshFavoriteState();
        page.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        Ui.toast(this, favorite ? "已收藏，可在首页「收藏」里看" : "已取消收藏");
    }

    private void toggleMute() {
        boolean muted = !prefs.muted();
        prefs.setMuted(muted);
        pager.setMuted(muted);
        // 静音时就不该继续占着音频焦点，否则会把别的 App 正在放的音乐掐掉
        syncAudioFocus();
        Ui.toast(this, muted ? "已静音" : "已开启声音");
    }

    /** 只有「前台 + 没静音」才持有音频焦点；其余情况及时还回去。 */
    private void syncAudioFocus() {
        if (audioManager == null) {
            return;
        }
        boolean want = resumed && prefs != null && !prefs.muted();
        if (want && !focusHeld) {
            int result = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else if (!want && focusHeld) {
            audioManager.abandonAudioFocus(focusListener);
            focusHeld = false;
        }
    }

    private void toggleAutoDownload(FeedPage page) {
        boolean enabled = !prefs.autoDownload();
        prefs.setAutoDownload(enabled);
        if (pager != null) {
            pager.refreshAutoDownloadState();
        } else {
            page.refreshAutoDownloadState();
        }
        if (!enabled) {
            Ui.toast(this, "自动下载已关闭");
            return;
        }
        Ui.toast(this, "自动下载已开启：正在看的视频会存到本地");
        maybeAutoDownload(page.work());
    }

    /** 自动下载当前作品（视频或图集）；同一时间只跑一个下载，避免刷太快排一长队。 */
    private void maybeAutoDownload(Work work) {
        if (work == null || !prefs.autoDownload() || work.empty) {
            return;
        }
        if (app.offline.has(work) || app.offline.isDownloading(work)
                || app.offline.isBusy()) {
            return;
        }
        app.offline.download(work);
        if (pager != null) {
            pager.currentPage().refreshDownloadState();
        }
    }

    /** 分享：先把作品的全部文件打包成一个 tar，再用临时授权的 content:// 发出去。 */
    private void share(FeedPage page) {
        Work work = page.work();
        if (work == null) {
            return;
        }
        if (preview) {
            Ui.toast(this, "回收站预览：恢复后才能分享");
            return;
        }
        if (sharing) {
            Ui.toast(this, "正在打包作品文件…");
            return;
        }
        sharing = true;
        shareCancelled = false;
        final android.app.AlertDialog busy = Ui.busy(this, "正在打包作品文件…");
        shareBusy = busy;
        if (busy != null) {
            busy.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "取消",
                    (dialog, which) -> shareCancelled = true);
        }
        final Work target = work;
        app.io.execute(() -> {
            File tar = null;
            String error = null;
            try {
                tar = WorkShare.build(app, target, new WorkShare.Progress() {
                    @Override
                    public void onProgress(int percent, String stage) {
                        final String message = stage + "  " + percent + "%";
                        runOnUiThread(() -> {
                            if (busy != null && busy.isShowing()) {
                                busy.setMessage(message);
                            }
                        });
                    }

                    @Override
                    public boolean isCancelled() {
                        return shareCancelled;
                    }
                });
            } catch (Exception exc) {
                error = exc.getMessage() == null ? "打包失败" : exc.getMessage();
            }
            final File result = tar;
            final String failure = error;
            runOnUiThread(() -> {
                sharing = false;
                shareBusy = null;
                Ui.dismiss(busy);
                if (isFinishing() || isDestroyed() || shareCancelled) {
                    return;
                }
                if (result == null) {
                    Ui.toast(this, "打包失败：" + failure);
                    return;
                }
                sendTar(result, target);
            });
        });
    }

    private void sendTar(File tar, Work work) {
        Uri uri = ShareProvider.uriFor(tar.getName());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(ShareProvider.MIME);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, work.displayTitle());
        String text = work.captionLine();
        if (work.shareUrl != null && !work.shareUrl.isEmpty()) {
            text = text.isEmpty() ? work.shareUrl : text + "\n" + work.shareUrl;
        }
        if (!text.isEmpty()) {
            intent.putExtra(Intent.EXTRA_TEXT, text);
        }
        // EXTRA_STREAM 在部分系统上不自动授予读权限，clipData + flag 双保险
        intent.setClipData(ClipData.newUri(getContentResolver(), "作品文件", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, "分享作品文件");
        chooser.setClipData(ClipData.newUri(getContentResolver(), "作品文件", uri));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser);
        } catch (android.content.ActivityNotFoundException exc) {
            Ui.toast(this, "没有能接收 tar 文件的应用；文件已存到 " + tar.getAbsolutePath());
        }
    }

    private void openAuthorPage(FeedPage page) {
        Work work = page.work();
        if (work == null) {
            return;
        }
        Intent intent = new Intent(this, AuthorActivity.class);
        intent.putExtra(AuthorActivity.EXTRA_AUTHOR, work.displayAuthor());
        startActivity(intent);
    }

    private void showInfo(FeedPage page) {
        final Work work = page.work();
        if (work == null) {
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setDimAmount(0.6f);
        dialog.getWindow().setGravity(Gravity.BOTTOM);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(this, 0xFF17171C, 18));
        panel.setPadding(Ui.dp(this, 20), Ui.dp(this, 12),
                Ui.dp(this, 20), Ui.dp(this, 18));

        View handle = new View(this);
        handle.setBackground(Ui.round(this, 0x40FFFFFF, 3));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = Ui.dp(this, 14);
        panel.addView(handle, handleParams);

        panel.addView(Ui.text(this, work.displayTitle(), 16, Ui.TEXT, true));
        TextView meta = Ui.label(this, "作者：" + work.displayAuthor()
                + (work.uniqueId.isEmpty() ? "" : "（" + work.uniqueId + "）"), 12, Ui.DIM);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = Ui.dp(this, 6);
        panel.addView(meta, metaParams);

        String time = Util.humanTime(work.time);
        String size = Util.humanSize(work.totalBytes());
        String kind = work.isAlbum() ? "图集 · " + work.images.size() + " 张" : "视频";
        panel.addView(Ui.label(this, (time.isEmpty() ? "" : time + " · ") + kind
                + (size.isEmpty() ? "" : " · " + size), 12, Ui.DIM));

        // 用 captionLine() 而不是裸的 captionText：电脑版对没文案的作品会写
        // 「抖音作品 <id>」占位，那种不该在详情里当文案显示
        String captionBody = work.captionLine();
        if (!captionBody.isEmpty()) {
            TextView caption = Ui.label(this, captionBody, 13, 0xFFE6E6EC);
            caption.setMaxLines(6);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            captionParams.topMargin = Ui.dp(this, 12);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(caption);
            panel.addView(scroll, captionParams);
        }

        String path = work.rel;
        panel.addView(Ui.label(this, "片库路径：" + path, 11, Ui.FAINT));

        // 已下载到本地：把离线目录和里面的作品文件一起列出来，方便找文件 / 拷走。
        // meta.json 是下载完成标记（OfflineStore 成功后才原子写），下载中或下载失败
        // 被清空的目录不会出现这一段，也不会指向一个空壳路径。
        File localDir = app.offline.dirFor(work);
        if (LocalFiles.isDownloaded(localDir)) {
            TextView localTitle = Ui.label(this, "本地文件（已下载）· 点按复制目录",
                    11, Ui.DIM);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = Ui.dp(this, 12);
            panel.addView(localTitle, titleParams);

            final String localPath = localDir.getAbsolutePath();
            TextView pathView = Ui.label(this, localPath, 11, 0xFF7FD8B0);
            pathView.setTextIsSelectable(true);
            pathView.setOnClickListener(view -> {
                ClipboardManager manager =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                manager.setPrimaryClip(ClipData.newPlainText("本地目录", localPath));
                Ui.toast(this, "本地目录已复制");
            });
            panel.addView(pathView);

            String listing = LocalFiles.listing(localDir, 6);
            if (!listing.isEmpty()) {
                TextView fileList = Ui.label(this, listing, 11, Ui.FAINT);
                LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                listParams.topMargin = Ui.dp(this, 4);
                panel.addView(fileList, listParams);
            }
        }

        // 删除作品：危险操作，单独一行、红框，点了还要再确认一次
        // 回收站预览里不能删（否则会把回收站里的目录再搬一次）
        if (!preview) {
            TextView delete = Ui.text(this, "移入回收站", 13, 0xFFFF6B85, true);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(Ui.ripple(this,
                    Ui.roundStroke(this, 0x00000000, 0x55FF6B85, 20, 1), 20));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
            deleteParams.topMargin = Ui.dp(this, 14);
            panel.addView(delete, deleteParams);
            delete.setOnClickListener(view -> {
                dialog.dismiss();
                confirmDeleteWork(page);
            });
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = Ui.dp(this, 12);
        panel.addView(actions, actionsParams);

        // 「作品链接」：文案.txt 里那一行可能是整段分享口令，复制前先只抽 URL。
        // 复制出来的链接和「抖音打开」用的是同一个值，避免按钮说的和剪贴板不一致。
        final String workLink = DouyinLinks.workUrl(work.awemeId, work.isAlbum(), work.shareUrl);
        final boolean hasWorkLink = !workLink.isEmpty() || !work.awemeId.isEmpty();

        LinearLayout firstRow = actionRow();
        actions.addView(firstRow);
        TextView only = pillButton("作者主页", 0xFF2A2A32);
        only.setOnClickListener(view -> {
            dialog.dismiss();
            openAuthorPage(page);
        });
        firstRow.addView(only);

        if (hasWorkLink) {
            TextView open = pillButton("抖音打开", 0xFF2A2A32);
            open.setOnClickListener(view -> {
                dialog.dismiss();
                DouyinLauncher.openWork(this, work.awemeId, work.shareUrl, work.isAlbum());
            });
            firstRow.addView(open, leftMargin(this, 10));
        }

        LinearLayout secondRow = null;
        if (hasWorkLink) {
            secondRow = actionRow();
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            secondParams.topMargin = Ui.dp(this, 10);
            actions.addView(secondRow, secondParams);

            TextView copy = pillButton("复制链接", 0xFF2A2A32);
            copy.setOnClickListener(view -> {
                ClipboardManager manager =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                manager.setPrimaryClip(ClipData.newPlainText("作品链接", workLink));
                Ui.toast(this, "链接已复制，粘贴到抖音即可打开");
                dialog.dismiss();
            });
            secondRow.addView(copy);
        }
        TextView close = pillButton("关闭", 0xFF2A2A32);
        close.setOnClickListener(view -> dialog.dismiss());
        // 没有链接时只有两个按钮，和原来一样挤在一行
        (secondRow == null ? firstRow : secondRow).addView(close, leftMargin(this, 10));

        // 底部留一点边距
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        wrapper.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(wrapper);
        Window window = dialog.getWindow();
        window.setWindowAnimations(R.style.SheetAnimation);
        dialog.show();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView pillButton(String label, int background) {
        TextView view = Ui.text(this, label, 13, Ui.TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(Ui.round(this, background, 20));
        view.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        return view;
    }

    /** 详情弹层的按钮行：右对齐、自动换行到下一行（四个 pill 一行放不下）。 */
    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        return row;
    }

    private static LinearLayout.LayoutParams leftMargin(Context context, int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(context, dp);
        return params;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Ui.enterImmersive(this);
        syncAudioFocus();
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, noisyFilter);
        }
        if (pager != null) {
            pager.setHostResumed(true);
        }
        // 在作者主页删了作者再回来：把播放列表里已经不存在的作品去掉
        // 预览的作品本来就不在片库里，不能按这个规则过滤
        if (!preview && pager != null && app.store.isLoaded()
                && app.store.revision() != storeRevision) {
            storeRevision = app.store.revision();
            Set<String> rels = new HashSet<>();
            for (Work work : app.store.works()) {
                rels.add(work.rel);
            }
            pager.retainExisting(rels);
            if (pager.size() == 0) {
                finish();
                return;
            }
        }
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 300L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        if (audioManager != null && focusHeld) {
            audioManager.abandonAudioFocus(focusListener);
            focusHeld = false;
        }
        if (pager != null) {
            pager.setHostResumed(false);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Ui.enterImmersive(this);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        shareCancelled = true;
        Ui.dismiss(shareBusy);
        shareBusy = null;
        if (pager != null) {
            pager.destroy();
        }
        app.offline.removeListener(offlineListener);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
