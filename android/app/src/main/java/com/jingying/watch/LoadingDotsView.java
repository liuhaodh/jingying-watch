package com.jingying.watch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 抖音式的三点加载动画（参考 Lottie 小动画的效果，纯 Canvas 实现，不引依赖）。
 */
public final class LoadingDotsView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float[] phases = {0f, 0.18f, 0.36f};
    private ValueAnimator animator;
    private float progress;

    public LoadingDotsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(0xE6FFFFFF);
        setAlpha(0.9f);
    }

    public void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(900L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = 3.2f * density;
        float gap = 10.5f * density;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        for (int index = 0; index < 3; index++) {
            float phase = (progress - phases[index] + 1f) % 1f;
            // 0～1～0 的呼吸曲线，三个点错开
            float wave = (float) Math.sin(phase * Math.PI);
            float scale = 0.55f + 0.75f * wave;
            paint.setAlpha((int) (150 + 105 * wave));
            canvas.drawCircle(centerX + (index - 1) * gap, centerY, radius * scale, paint);
        }
    }
}
