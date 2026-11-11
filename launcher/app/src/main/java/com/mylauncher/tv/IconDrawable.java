package com.mylauncher.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Small line icons drawn in code (no image files): Wi-Fi fan and the Bluetooth rune.
 * Drawn on a 24x24 grid and scaled to whatever bounds it is given.
 */
final class IconDrawable extends Drawable {
    static final int WIFI = 0, BLUETOOTH = 1;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int alpha = 255;

    IconDrawable(int kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float s = Math.min(b.width(), b.height()) / 24f;
        int save = c.save();
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f);
        c.scale(s, s);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(2.2f);

        if (kind == WIFI) {
            float cx = 12f, cy = 19.5f;
            float[] radii = {5.5f, 10f, 14.5f};
            paint.setStyle(Paint.Style.STROKE);
            for (float r : radii) {
                oval.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(oval, -135f, 90f, false, paint);   // 90 degrees centred on "up"
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 1.9f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            path.moveTo(7f, 7f);
            path.lineTo(17f, 17f);
            path.lineTo(12f, 22f);
            path.lineTo(12f, 2f);
            path.lineTo(17f, 7f);
            path.lineTo(7f, 17f);
            c.drawPath(path, paint);
        }
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
