package com.jingying.watch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音式上下翻页：三屏（上一屏 / 当前 / 下一屏）跟着手指走，
 * 松手按距离和速度吸附到上一屏/下一屏；单击暂停、双击收藏、长按 2 倍速、
 * 底部进度条可拖动，图集左右滑切换图片。
 */
public final class FeedPager extends ViewGroup {

    public interface Listener {
        void onPageChanged(int index, Work work);

        void onCompleted(FeedPage page);

        void onTap(FeedPage page, int target, float x, float y);

        void onDoubleTap(FeedPage page, float x, float y);

        void onLongPress(FeedPage page, boolean pressed);

        void onToken(FeedPage page, String token);
    }

    private final App app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OverScroller scroller;
    private final VelocityTracker velocityTracker;
    private final FeedPage[] pages = new FeedPage[3];
    private final List<Work> items = new ArrayList<>();
    private Listener listener;
    private int position;
    private float offset;
    private int settleTarget = -1;
    private boolean settling;
    private boolean tracking;
    private boolean dragging;
    private boolean scrubMode;
    private boolean albumSwipe;
    private boolean multiTouch;
    private boolean longPressFired;
    private boolean horizontalGesture;
    private boolean hostResumed = true;
    private float downX;
    private float downY;
    private long downTime;
    private volatile long lastTouchAt;
    private float scrubFraction;
    private final int touchSlop;
    /** 双击窗口：比系统 300ms 略短，单击暂停不至于明显发钝。 */
    private static final int DOUBLE_TAP_TIMEOUT_MS = 260;
    private final int doubleTapSlop;
    private final float density;
    private Runnable pendingSingleTap;
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;

    private final Runnable longPressRunnable;

    public FeedPager(Context context) {
        super(context);
        app = (App) context.getApplicationContext();
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        doubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        scroller = new OverScroller(context);
        velocityTracker = VelocityTracker.obtain();
        setBackgroundColor(0xFF000000);
        setClipChildren(true);
        for (int index = 0; index < 3; index++) {
            FeedPage page = new FeedPage(context);
            pages[index] = page;
            addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            page.setListener(new FeedPage.Listener() {
                @Override
                public void onCompleted(FeedPage completed) {
                    if (listener != null && completed == currentPage()
                            && !dragging && !settling && !longPressFired) {
                        listener.onCompleted(completed);
                    }
                }

                @Override
                public void onHit(FeedPage target, int hit, float x, float y) {
                }

                @Override
                public void onToken(FeedPage target, String token) {
                    if (listener != null) {
                        listener.onToken(target, token);
                    }
                }
            });
        }
        longPressRunnable = () -> {
            FeedPage page = currentPage();
            // 详情还没读到的作品 kind 还是 unknown，这时也允许长按：
            // 倍速会先记下来，等 prepare 完成后再补上
            if (page != null && page.work() != null && !page.work().isAlbum()) {
                longPressFired = true;
                page.setSpeedBoost(true);
                page.setSpeedLabel(true);
                if (listener != null) {
                    listener.onLongPress(page, true);
                }
            }
        };
        // 让内容区不拦截触摸：所有手势由 pager 统一处理
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public List<Work> items() {
        return new ArrayList<>(items);
    }

    public int size() {
        return items.size();
    }

    public Work workAt(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    public int position() {
        return position;
    }

    public FeedPage currentPage() {
        return pages[1];
    }

    public void setItems(List<Work> works, int start) {
        items.clear();
        items.addAll(works);
        position = Math.max(0, Math.min(start, Math.max(0, items.size() - 1)));
        offset = 0;
        settleTarget = -1;
        settling = false;
        bindSlots();
        applyOffset();
        if (listener != null) {
            listener.onPageChanged(position, currentPage().work());
        }
    }

    // ---------- 布局 ----------
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        for (FeedPage page : pages) {
            page.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        for (FeedPage page : pages) {
            page.layout(0, 0, width, height);
        }
        applyOffset();
    }

    private void applyOffset() {
        int height = getHeight();
        for (int index = 0; index < 3; index++) {
            pages[index].setTranslationY(offset + (index - 1) * height);
        }
    }

    private void bindSlots() {
        // 翻页 / 换列表时，上一次待判定的单击不能落到新作品上
        cancelPendingSingleTap();
        for (int index = 0; index < 3; index++) {
            int itemIndex = position + index - 1;
            FeedPage page = pages[index];
            if (itemIndex < 0 || itemIndex >= items.size()) {
                page.releasePlayers();
                page.setVisibility(INVISIBLE);
                continue;
            }
            page.setVisibility(VISIBLE);
            page.bind(items.get(itemIndex), itemIndex, items.size());
            page.setRole(index - 1);
        }
    }

    // ---------- 翻页 ----------
    public void nextPage(boolean auto) {
        if (items.isEmpty()) {
            return;
        }
        if (position + 1 >= items.size()) {
            if (!auto) {
                Ui.toast(getContext(), "已经是最后一个了");
            }
            return;
        }
        animateTo(position + 1);
    }

    public void previousPage() {
        if (position - 1 >= 0) {
            animateTo(position - 1);
        }
    }

    /** 云端删除后，把播放列表里已经不存在的作品去掉，尽量停在原来那一条上。 */
    public void retainExisting(java.util.Set<String> rels) {
        if (items.isEmpty() || rels == null) {
            return;
        }
        Work current = currentPage() == null ? null : currentPage().work();
        String currentRel = current == null ? "" : current.rel;
        List<Work> kept = new ArrayList<>(items.size());
        int index = -1;
        for (Work work : items) {
            if (!rels.contains(work.rel)) {
                continue;
            }
            if (work.rel.equals(currentRel)) {
                index = kept.size();
            }
            kept.add(work);
        }
        if (kept.size() == items.size()) {
            return;
        }
        items.clear();
        items.addAll(kept);
        settleTarget = -1;
        settling = false;
        offset = 0;
        if (items.isEmpty()) {
            position = 0;
            bindSlots();
            applyOffset();
            if (listener != null) {
                listener.onPageChanged(0, null);
            }
            return;
        }
        position = index >= 0 ? index : Math.min(position, items.size() - 1);
        bindSlots();
        applyOffset();
        if (listener != null) {
            listener.onPageChanged(position, currentPage().work());
        }
    }

    /** 删除当前作品后，把这一条从播放列表里去掉并重新绑定三屏。 */
    public void removeCurrent() {
        if (items.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(position, items.size() - 1));
        items.remove(index);
        settleTarget = -1;
        settling = false;
        offset = 0;
        if (items.isEmpty()) {
            position = 0;
            bindSlots();
            applyOffset();
            if (listener != null) {
                listener.onPageChanged(0, null);
            }
            return;
        }
        position = Math.min(index, items.size() - 1);
        bindSlots();
        applyOffset();
        if (listener != null) {
            listener.onPageChanged(position, currentPage().work());
        }
    }

    private void animateTo(int targetIndex) {
        int height = getHeight();
        if (height <= 0) {
            return;
        }
        if (settling) {
            scroller.abortAnimation();
        }
        settling = true;
        settleTarget = targetIndex;
        int startOffset = Math.round(offset);
        int targetOffset = (targetIndex - position) * -height;
        int duration = 240 + Math.min(160,
                (int) (Math.abs(targetOffset - startOffset) / (float) height * 160));
        scroller.startScroll(0, startOffset, 0, targetOffset - startOffset, duration);
        postInvalidateOnAnimation();
    }

    /**
     * 手指按下时给还没走完的吸附动画收尾。
     * 动画走了一半以上就落定到目标页，否则弹回当前页；两种情况都把 offset 归零，
     * 免得 computeScroll 在手指按着的时候又跳一次页。
     */
    private void finishSettleOnTouchDown() {
        scroller.abortAnimation();
        int height = getHeight();
        boolean commit = settleTarget >= 0 && settleTarget != position
                && height > 0 && Math.abs(offset) > height * 0.5f;
        if (commit) {
            int delta = settleTarget - position;
            position = settleTarget;
            if (delta > 0) {
                rotate(true);
            } else {
                rotate(false);
            }
            bindSlots();
            if (listener != null) {
                listener.onPageChanged(position, currentPage().work());
            }
        }
        settleTarget = -1;
        settling = false;
        offset = 0;
        applyOffset();
    }

    private void settle(float velocityY) {
        int height = getHeight();
        if (height <= 0) {
            return;
        }
        settleTarget = position;
        float targetOffset = 0;
        float threshold = height * 0.11f;
        boolean next = position + 1 < items.size();
        boolean prev = position - 1 >= 0;
        if ((offset < -threshold || velocityY < -500) && next) {
            settleTarget = position + 1;
            targetOffset = -height;
        } else if ((offset > threshold || velocityY > 500) && prev) {
            settleTarget = position - 1;
            targetOffset = height;
        } else if (!next && offset < -threshold) {
            targetOffset = -height * 0.08f;
        } else if (!prev && offset > threshold) {
            targetOffset = height * 0.08f;
        }
        settling = true;
        int startOffset = Math.round(offset);
        int duration = 220 + Math.min(180,
                (int) (Math.abs(targetOffset - startOffset) / (float) height * 180));
        scroller.startScroll(0, startOffset, 0, Math.round(targetOffset) - startOffset, duration);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.getCurrY();
            applyOffset();
            postInvalidateOnAnimation();
            return;
        }
        if (!settling) {
            return;
        }
        if (settleTarget != position) {
            int delta = settleTarget - position;
            position = settleTarget;
            if (delta > 0) {
                rotate(true);
            } else {
                rotate(false);
            }
            offset = 0;
            settling = false;
            bindSlots();
            applyOffset();
            if (listener != null) {
                listener.onPageChanged(position, currentPage().work());
            }
        } else if (Math.abs(offset) > 1f) {
            // 到头了回弹
            int start = Math.round(offset);
            scroller.startScroll(0, start, 0, -start, 180);
            postInvalidateOnAnimation();
        } else {
            offset = 0;
            applyOffset();
            settling = false;
        }
    }

    private void rotate(boolean forward) {
        FeedPage recycled = forward ? pages[0] : pages[2];
        if (forward) {
            pages[0] = pages[1];
            pages[1] = pages[2];
            pages[2] = recycled;
        } else {
            pages[2] = pages[1];
            pages[1] = pages[0];
            pages[0] = recycled;
        }
    }

    // ---------- 触摸 ----------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.isEmpty()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                velocityTracker.clear();
                velocityTracker.addMovement(event);
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                lastTouchAt = System.currentTimeMillis();
                tracking = true;
                dragging = false;
                scrubMode = false;
                albumSwipe = false;
                horizontalGesture = false;
                multiTouch = false;
                // 上一次吸附动画还没走完就又按下：先把这次翻页收尾再确认「当前页」，
                // 否则计算下一帧时 computeScroll 会在手指下面直接跳到目标页。
                if (settling) {
                    finishSettleOnTouchDown();
                }
                FeedPage downPage = currentPage();
                if (downPage != null) {
                    downPage.onAlbumTouchDown();
                }
                longPressFired = false;
                // 上一次图集左右滑留下的「别当点击」标记必须清掉，
                // 否则滑完图集后的第一次点击会被吞掉
                suppressTapUntilUp = false;
                // 进度条上按住是在拖动进度，不能同时触发 2× 长按
                boolean onProgress = downPage != null && downPage.isScrubbable()
                        && downPage.isInProgressTouchZone(downY);
                if (!onProgress) {
                    handler.postDelayed(longPressRunnable, 420);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                cancelLongPressCallback();
                cancelPendingSingleTap();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // 多指抬起后不要再把这次手势当成点击/翻页
                multiTouch = true;
                cancelLongPressCallback();
                cancelPendingSingleTap();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    return true;
                }
                velocityTracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && !scrubMode && !horizontalGesture) {
                    float absX = Math.abs(dx);
                    float absY = Math.abs(dy);
                    if (absX > touchSlop || absY > touchSlop) {
                        FeedPage page = currentPage();
                        boolean album = page != null && page.work() != null
                                && page.work().isAlbum();
                        // 图集左右滑要更明确一点，斜着划时不要被当成上下翻作品
                        float ratio = album ? 1.15f : 1.0f;
                        if (absX > touchSlop && absX >= absY * ratio) {
                            horizontalGesture = true;
                            if (page.isScrubbable() && page.isInProgressTouchZone(downY)) {
                                scrubMode = true;
                                cancelLongPressCallback();
                                beginScrub(event.getX());
                            } else if (album) {
                                albumSwipe = true;
                                cancelLongPressCallback();
                            }
                        } else if (absY > touchSlop && absY >= absX * ratio) {
                            dragging = true;
                            cancelLongPressCallback();
                        }
                    }
                }
                if (scrubMode) {
                    updateScrub(event.getX());
                    return true;
                }
                if (albumSwipe) {
                    FeedPage page = currentPage();
                    if (page != null && AlbumNav.dragCrossed(dx, getWidth())) {
                        if (dx > 0) {
                            page.previousAlbum();
                        } else {
                            page.advanceAlbum();
                        }
                        albumSwipe = false;
                        suppressTapUntilUp = true;
                    }
                    return true;
                }
                if (dragging) {
                    float next = dy;
                    float limit = getHeight() * 1.15f;
                    if (!(position + 1 < items.size()) && next < 0) {
                        next = Math.max(next, -limit * 0.12f);
                    }
                    if (!(position - 1 >= 0) && next > 0) {
                        next = Math.min(next, limit * 0.12f);
                    }
                    offset = Math.max(-limit, Math.min(limit, next));
                    applyOffset();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                lastTouchAt = System.currentTimeMillis();
                velocityTracker.addMovement(event);
                float velocityY = velocityTracker.getYVelocity();
                long gestureMs = Math.max(1L, event.getEventTime() - downTime);
                float rawUpDy = event.getY() - downY;
                // 事件被合并时 VelocityTracker 可能算不出速度，甚至方向相反；
                // 位移/时长是最可靠的兜底。
                float syntheticVelocity = rawUpDy / (float) gestureMs * 1000f;
                if (Math.abs(velocityY) < 800f
                        || (velocityY > 0) != (rawUpDy > 0)) {
                    velocityY = syntheticVelocity;
                }
                cancelLongPressCallback();
                // 这次手势不是「干净的一下点击」时（滑动 / 长按 / 多指 / 图集横滑），
                // 撤掉上一击待判定的单击，别在翻页之后突然暂停
                if (dragging || scrubMode || longPressFired || albumSwipe
                        || horizontalGesture || multiTouch || suppressTapUntilUp) {
                    cancelPendingSingleTap();
                }
                FeedPage upPage = currentPage();
                if (upPage != null) {
                    upPage.onAlbumTouchUp();
                }
                if (multiTouch) {
                    // 双指缩放/误触的抬手不该翻页，也不该 seek
                    multiTouch = false;
                    dragging = false;
                    albumSwipe = false;
                    horizontalGesture = false;
                    if (scrubMode) {
                        if (upPage != null) {
                            upPage.hideScrub();
                        }
                        scrubMode = false;
                    }
                    return true;
                }
                if (scrubMode) {
                    finishScrub();
                    return true;
                }
                if (longPressFired) {
                    FeedPage page = currentPage();
                    page.setSpeedBoost(false);
                    page.setSpeedLabel(false);
                    longPressFired = false;
                    if (listener != null) {
                        listener.onLongPress(page, false);
                    }
                    return true;
                }
                if (albumSwipe) {
                    // MOVE 被系统合并、位移没到拖动阈值时的快速横扫：按抬手速度补切一张，
                    // 对齐竖直方向那段兜底，别让用户觉得「划了没反应」。
                    FeedPage page = currentPage();
                    float upDx = event.getX() - downX;
                    if (page != null
                            && AlbumNav.flingCrossed(upDx, (float) gestureMs, getWidth())) {
                        if (upDx > 0) {
                            page.previousAlbum();
                        } else {
                            page.advanceAlbum();
                        }
                    }
                    albumSwipe = false;
                    horizontalGesture = false;
                    return true;
                }
                if (horizontalGesture) {
                    // 图集/视频的横向手势不参与上下翻页
                    horizontalGesture = false;
                    return true;
                }
                // 快速轻扫时系统可能把 MOVE 事件合并掉，只剩 DOWN/UP：
                // 这时按抬手位移和手势时长补一次翻页，避免被当成点击。
                if (!dragging && Math.abs(rawUpDy) > touchSlop * 2f) {
                    float limitUp = getHeight() * 1.15f;
                    float finalUp = rawUpDy;
                    if (!(position + 1 < items.size()) && finalUp < 0) {
                        finalUp = Math.max(finalUp, -limitUp * 0.12f);
                    }
                    if (!(position - 1 >= 0) && finalUp > 0) {
                        finalUp = Math.min(finalUp, limitUp * 0.12f);
                    }
                    offset = Math.max(-limitUp, Math.min(limitUp, finalUp));
                    applyOffset();
                    settle(velocityY);
                    return true;
                }
                if (dragging) {
                    // 事件可能被合并，抬手位置才是最终位移
                    float finalDy = event.getY() - downY;
                    float limitUp = getHeight() * 1.15f;
                    if (!(position + 1 < items.size()) && finalDy < 0) {
                        finalDy = Math.max(finalDy, -limitUp * 0.12f);
                    }
                    if (!(position - 1 >= 0) && finalDy > 0) {
                        finalDy = Math.min(finalDy, limitUp * 0.12f);
                    }
                    offset = Math.max(-limitUp, Math.min(limitUp, finalDy));
                    applyOffset();
                    dragging = false;
                    settle(velocityY);
                    return true;
                }
                if (suppressTapUntilUp) {
                    suppressTapUntilUp = false;
                    return true;
                }
                handleTap(event);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLongPressCallback();
                cancelPendingSingleTap();
                if (longPressFired) {
                    currentPage().setSpeedBoost(false);
                    currentPage().setSpeedLabel(false);
                    longPressFired = false;
                }
                if (scrubMode) {
                    currentPage().hideScrub();
                    scrubMode = false;
                }
                if (dragging) {
                    dragging = false;
                    settle(0f);
                }
                FeedPage cancelPage = currentPage();
                if (cancelPage != null) {
                    cancelPage.onAlbumTouchUp();
                }
                horizontalGesture = false;
                albumSwipe = false;
                return true;
            default:
                return true;
        }
    }

    private boolean suppressTapUntilUp;

    private void cancelLongPressCallback() {
        handler.removeCallbacks(longPressRunnable);
    }

    private void handleTap(MotionEvent event) {
        FeedPage page = currentPage();
        if (page == null) {
            return;
        }
        float x = event.getX();
        float y = event.getY();
        int target = page.hitTest(x, y);
        if (target == FeedPage.HIT_PROGRESS) {
            firePendingSingleTap();
            seekToFraction(page.progressFractionAt(x));
            return;
        }
        if (target != FeedPage.HIT_NONE) {
            // 侧栏 / 顶部图标立即生效，不参与双击判定；上一击的补发不能丢
            firePendingSingleTap();
            if (listener != null) {
                listener.onTap(page, target, x, y);
            }
            return;
        }
        if (page.handleCaptionTap(x, y)) {
            firePendingSingleTap();
            return;
        }
        // 内容区：单击暂停/继续，双击收藏。第一下先等一个双击窗口，
        // 否则两下会变成「暂停 + 继续」，画面白闪一次。
        long now = event.getEventTime();
        if (pendingSingleTap != null) {
            boolean close = Math.abs(x - lastTapX) <= doubleTapSlop
                    && Math.abs(y - lastTapY) <= doubleTapSlop;
            cancelPendingSingleTap();
            if (close && now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS) {
                if (listener != null) {
                    listener.onDoubleTap(page, x, y);
                }
                return;
            }
            // 两下离得太远：先补上第一下的单击，这一下再按新的单击处理
            if (listener != null) {
                listener.onTap(page, FeedPage.HIT_NONE, lastTapX, lastTapY);
            }
        }
        lastTapTime = now;
        lastTapX = x;
        lastTapY = y;
        final FeedPage tapPage = page;
        pendingSingleTap = () -> {
            pendingSingleTap = null;
            if (tapPage == currentPage() && listener != null) {
                listener.onTap(tapPage, FeedPage.HIT_NONE, x, y);
            }
        };
        handler.postDelayed(pendingSingleTap, DOUBLE_TAP_TIMEOUT_MS);
    }

    /** 立刻把待判定的单击执行掉（点到按钮 / 话题时，不能吞掉上一击）。 */
    private void firePendingSingleTap() {
        Runnable pending = pendingSingleTap;
        if (pending != null) {
            handler.removeCallbacks(pending);
            pendingSingleTap = null;
            pending.run();
        }
    }

    private void cancelPendingSingleTap() {
        if (pendingSingleTap != null) {
            handler.removeCallbacks(pendingSingleTap);
            pendingSingleTap = null;
        }
    }

    // ---------- 进度条拖动 ----------
    private void beginScrub(float x) {
        FeedPage page = currentPage();
        long duration = page.durationMs();
        scrubFraction = page.progressFractionAt(x);
        page.showScrub((long) (duration * scrubFraction), duration);
    }

    private void updateScrub(float x) {
        FeedPage page = currentPage();
        long duration = page.durationMs();
        scrubFraction = page.progressFractionAt(x);
        page.showScrub((long) (duration * scrubFraction), duration);
    }

    private void finishScrub() {
        FeedPage page = currentPage();
        long duration = page.durationMs();
        page.seekTo((long) (duration * scrubFraction));
        page.hideScrub();
        scrubMode = false;
    }

    private void seekToFraction(float fraction) {
        FeedPage page = currentPage();
        long duration = page.durationMs();
        page.seekTo((long) (duration * fraction));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    // ---------- 外部控制 ----------
    public void pauseAll() {
        for (FeedPage page : pages) {
            page.pause();
        }
    }

    public void resumeCurrent() {
        hostResumed = true;
        currentPage().setHostResumed(true);
    }

    public void setHostResumed(boolean resumed) {
        hostResumed = resumed;
        for (FeedPage page : pages) {
            page.setHostResumed(resumed);
        }
    }

    public void setMuted(boolean muted) {
        for (FeedPage page : pages) {
            page.setMuted(muted);
        }
    }

    /** 回收站预览模式：三屏都把下载/收藏/自动下载置灰。 */
    public void setPreviewMode(boolean preview) {
        for (FeedPage page : pages) {
            if (page != null) {
                page.setPreviewMode(preview);
            }
        }
    }

    public void refreshAutoDownloadState() {
        for (FeedPage page : pages) {
            if (page != null) {
                page.refreshAutoDownloadState();
            }
        }
    }

    public void pauseForFocus() {
        FeedPage page = currentPage();
        if (page != null) {
            page.pauseForFocus();
        }
    }

    public void resumeForFocus() {
        FeedPage page = currentPage();
        if (page != null) {
            page.resumeForFocus();
        }
    }

    public void updateProgress() {
        currentPage().updateProgress();
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        pendingSingleTap = null;
        for (FeedPage page : pages) {
            page.destroy();
        }
        velocityTracker.recycle();
    }

    public boolean isSettling() {
        return settling;
    }

    public long lastTouchAt() {
        return lastTouchAt;
    }

    public boolean isDragging() {
        return dragging;
    }
}
