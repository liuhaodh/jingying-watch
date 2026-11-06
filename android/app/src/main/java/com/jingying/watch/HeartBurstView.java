package com.jingying.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 双击点赞时从手指位置冒出来的小心心。 */
public final class HeartBurstView extends View {

    private static final long LIFE_MS = 780L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final List<Burst> bursts = new ArrayList<>();
    private Runnable ticker;

    public HeartBurstView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.ACCENT);
        buildHeart();
        setClickable(false);
        setFocusable(false);
    }

    private void buildHeart() {
        heart.reset();
        heart.moveTo(12f, 21.2f);
        heart.cubicTo(3.2f, 15.2f, 0.4f, 11.2f, 0.4f, 7.6f);
        heart.cubicTo(0.4f, 3.6f, 3.4f, 1.4f, 6.8f, 2.9f);
        heart.cubicTo(9.2f, 3.9f, 11.2f, 6.4f, 12f, 8.4f);
        heart.cubicTo(12.8f, 6.4f, 14.8f, 3.9f, 17.2f, 2.9f);
        heart.cubicTo(20.6f, 1.4f, 23.6f, 3.6f, 23.6f, 7.6f);
        heart.cubicTo(23.6f, 11.2f, 20.8f, 15.2f, 12f, 21.2f);
        heart.close();
    }

    public void burst(float x, float y) {
        long now = System.currentTimeMillis();
        bursts.add(new Burst(x, y, now, 0f, 1f));
        bursts.add(new Burst(x - Ui.dp(getContext(), 10), y + Ui.dp(getContext(), 6),
                now + 60, -16f, 0.72f));
        bursts.add(new Burst(x + Ui.dp(getContext(), 11), y + Ui.dp(getContext(), 9),
                now + 120, 18f, 0.62f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        boolean active = false;
        float unit = Ui.dpf(getContext(), 22);
        for (Iterator<Burst> iterator = bursts.iterator(); iterator.hasNext(); ) {
            Burst burst = iterator.next();
            long life = now - burst.start;
            if (life < 0) {
                active = true;
                continue;
            }
            if (life > LIFE_MS) {
                iterator.remove();
                continue;
            }
            active = true;
            float t = life / (float) LIFE_MS;
            float scale = (0.45f + 0.85f * (float) Math.sin(Math.min(1f, t * 1.35f) * Math.PI / 2))
                    * burst.scale * (1f - 0.12f * t);
            float alpha = (1f - t) * (1f - t);
            paint.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.translate(burst.x, burst.y - Ui.dpf(getContext(), 26) * t);
            canvas.rotate(burst.rotation + 10f * (float) Math.sin(t * 8));
            float factor = unit / 24f * scale;
            canvas.scale(factor, factor);
            canvas.translate(-12f, -12f);
            canvas.drawPath(heart, paint);
            canvas.restore();
        }
        if (active) {
            postInvalidateOnAnimation();
        }
    }

    private static final class Burst {
        final float x;
        final float y;
        final long start;
        final float rotation;
        final float scale;

        Burst(float x, float y, long start, float rotation, float scale) {
            this.x = x;
            this.y = y;
            this.start = start;
            this.rotation = rotation;
            this.scale = scale;
        }
    }
}
