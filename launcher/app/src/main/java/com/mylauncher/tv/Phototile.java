package com.mylauncher.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "live photo" tile: cross-fades through the photos in a folder on the TV.
 *
 * Light on purpose for a 1 GB TV: at most two tile-sized bitmaps (about 0.3 MB each) are alive, photos are
 * decoded on a background thread at tile size, nothing is fetched from the network, and the only redraws are
 * the short cross-fade itself.
 *
 * Folders read (jpg, jpeg, png, webp, sorted by file name):
 *   /sdcard/Android/data/com.mylauncher.tv/files/photos
 *   /sdcard/Pictures/TVHome            (needs the storage permission, see README)
 */
final class PhotoTile {
    private static final int FADE_MS = 700;
    private static final int MAX_FILES = 100;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final int w, h;
    private final float radius;
    private final Slide slide;

    private volatile List<File> files = new ArrayList<>();
    private int index = -1;            // photo currently shown (UI thread only)
    private boolean loading = false;   // UI thread only
    private ValueAnimator fade;
    private boolean dead = false;

    PhotoTile(Context appContext, int w, int h, float radius) {
        this.ctx = appContext;
        this.w = Math.max(16, w);
        this.h = Math.max(16, h);
        this.radius = radius;
        this.slide = new Slide(radius);
    }

    Drawable getDrawable() {
        return slide;
    }

    boolean hasPhotos() {
        return !files.isEmpty();
    }

    void shutdown() {
        dead = true;
        io.shutdownNow();
        if (fade != null) fade.cancel();
    }

    /** Re-read the photo folders (cheap) and make sure the first photo is showing. */
    void refresh() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<File> found = scan();
                files = found;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (dead) return;
                        if (found.isEmpty()) {
                            index = -1;
                            slide.setCurrent(null);
                        } else if (!slide.hasPhoto()) {
                            next();
                        }
                    }
                });
            }
        });
    }

    /** Fade to the next photo. Safe to call at any time; ignored while one is still loading. */
    void next() {
        if (dead || loading) return;
        final List<File> list = files;
        if (list.isEmpty()) return;
        loading = true;
        final int target = (index + 1) % list.size();
        final File f = list.get(target);
        io.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = decode(f);
                ui.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (dead) return;
                        index = target;      // a broken file is simply skipped next time
                        if (b != null) show(b);
                    }
                });
            }
        });
    }

    private void show(Bitmap b) {
        if (fade != null && fade.isRunning()) fade.end();
        if (!slide.hasPhoto()) {
            slide.setCurrent(b);
            return;
        }
        slide.setNext(b);
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(FADE_MS);
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                slide.setProgress((Float) a.getAnimatedValue());
            }
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                slide.finishFade();
            }
        });
        fade.start();
    }

    // ---------------------------------------------------------------- files and decoding

    private List<File> scan() {
        List<File> out = new ArrayList<>();
        File[] dirs = {
                ctx.getExternalFilesDir("photos"),
                new File(Environment.getExternalStorageDirectory(), "Pictures/TVHome")
        };
        for (File d : dirs) {
            if (d == null) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith(".") || !f.isFile()) continue;
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out);
        if (out.size() > MAX_FILES) out = new ArrayList<>(out.subList(0, MAX_FILES));
        return out;
    }

    /** Decodes at about tile size, then centre-crops into a rounded tile-sized bitmap. */
    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o2);
            if (src == null) return null;

            int rot = exifRotation(f.getPath());
            if (rot != 0) {
                Matrix rm = new Matrix();
                rm.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), rm, true);
                if (r != src) {
                    src.recycle();
                    src = r;
                }
            }

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(sh);
            new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
            src.recycle();
            return out;
        } catch (Throwable t) {
            return null;   // unreadable file or out of memory: skip this photo
        }
    }

    private static int exifRotation(String path) {
        try {
            int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignored) { }
        return 0;
    }

    // ---------------------------------------------------------------- the drawable

    /** Draws the current photo, the next one fading in on top, or a hint when there are no photos. */
    private static final class Slide extends Drawable {
        private Bitmap cur, next;
        private float progress;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;

        Slide(float radius) {
            this.radius = radius;
            text.setTextAlign(Paint.Align.CENTER);
        }

        boolean hasPhoto() { return cur != null; }

        void setCurrent(Bitmap b) {
            cur = b;
            next = null;
            progress = 0f;
            invalidateSelf();
        }

        void setNext(Bitmap b) {
            next = b;
            progress = 0f;
        }

        void setProgress(float p) {
            progress = p;
            invalidateSelf();
        }

        void finishFade() {
            if (next != null) cur = next;
            next = null;
            progress = 0f;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (cur == null) {
                rect.set(b);
                plate.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        0xFF2B3A55, 0xFF14161C, Shader.TileMode.CLAMP));
                c.drawRoundRect(rect, radius, radius, plate);
                text.setColor(0xCCFFFFFF);
                text.setTextSize(b.height() * 0.15f);
                c.drawText("Add photos", b.exactCenterX(), b.exactCenterY(), text);
                text.setColor(0x88FFFFFF);
                text.setTextSize(b.height() * 0.09f);
                c.drawText("see the README", b.exactCenterX(), b.exactCenterY() + b.height() * 0.17f, text);
                return;
            }
            paint.setAlpha(255);
            c.drawBitmap(cur, null, b, paint);
            if (next != null && progress > 0f) {
                paint.setAlpha((int) (255 * progress));
                c.drawBitmap(next, null, b, paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
