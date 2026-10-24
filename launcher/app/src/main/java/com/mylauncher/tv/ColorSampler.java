package com.mylauncher.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Picks a vivid representative colour from a banner/icon by drawing it into a 16x9 bitmap. */
final class ColorSampler {
    static final int FALLBACK = 0xFF4A6FA5;

    private ColorSampler() { }

    static int sample(Drawable d) {
        if (d == null) return FALLBACK;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, 16, 9);
            d.draw(c);
            d.setBounds(old);

            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, sum = 0;
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = bmp.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    double w = hsv[1] * hsv[2];   // favour saturated, bright pixels; ignore black/grey
                    w = w * w;
                    r += Color.red(p) * w;
                    g += Color.green(p) * w;
                    b += Color.blue(p) * w;
                    sum += w;
                }
            }
            if (sum < 0.3) return FALLBACK;
            int rgb = Color.rgb((int) (r / sum), (int) (g / sum), (int) (b / sum));
            Color.colorToHSV(rgb, hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return FALLBACK;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }
}
