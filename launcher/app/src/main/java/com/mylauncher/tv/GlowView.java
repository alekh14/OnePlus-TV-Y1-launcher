package com.mylauncher.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Soft radial ambient glow. Pure gradient - no blur, very cheap. */
final class GlowView extends View {
    private final Paint paint = new Paint();
    private float cx, cy, radius;
    private int color = ColorSampler.FALLBACK;

    GlowView(Context ctx) {
        super(ctx);
    }

    void setGeometry(float cx, float cy, float radius) {
        this.cx = cx;
        this.cy = cy;
        this.radius = radius;
        rebuild();
    }

    void setColor(int c) {
        color = c;
        rebuild();
        invalidate();
    }

    int getColor() {
        return color;
    }

    private void rebuild() {
        if (radius <= 0) return;
        int rgb = color & 0x00FFFFFF;
        paint.setShader(new RadialGradient(cx, cy, radius, 0x66000000 | rgb, rgb, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (paint.getShader() != null) canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
