package com.mylauncher.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Draws an app banner (or its icon on a dark plate) clipped to a rounded rectangle. */
final class TileDrawable extends Drawable {
    private final Drawable src;
    private final boolean isBanner;
    private final float radius;
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    TileDrawable(Drawable src, boolean isBanner, float radius) {
        this.src = src;
        this.isBanner = isBanner;
        this.radius = radius;
        plate.setColor(0xFF2A2A2D);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        clip.reset();
        clip.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int save = c.save();
        c.clipPath(clip);
        if (isBanner) {
            src.setBounds(b);
            src.draw(c);
        } else {
            c.drawRect(b, plate);
            int s = (int) (b.height() * 0.62f);
            int cx = b.centerX();
            int cy = b.centerY();
            src.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            src.draw(c);
        }
        c.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
