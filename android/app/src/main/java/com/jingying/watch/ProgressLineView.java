package com.jingying.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 抖音式细进度条：2dp 轨道 + 白色播放进度，可点击/拖动由外部手势处理。 */
public final class ProgressLineView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;
    private int trackColor = 0x55FFFFFF;
    private int barColor = 0xFFFFFFFF;

    public ProgressLineView(Context context) {
        this(context, null);
    }

    public ProgressLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setProgress(float value) {
        float next = Math.max(0f, Math.min(1f, value));
        if (Math.abs(next - progress) > 0.0005f) {
            progress = next;
            invalidate();
        }
    }

    public float getProgress() {
        return progress;
    }

    public void setColors(int track, int bar) {
        trackColor = track;
        barColor = bar;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float radius = height / 2f;
        paint.setColor(trackColor);
        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (progress > 0.001f) {
            paint.setColor(barColor);
            rect.set(0, 0, Math.max(height, width * progress), height);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
