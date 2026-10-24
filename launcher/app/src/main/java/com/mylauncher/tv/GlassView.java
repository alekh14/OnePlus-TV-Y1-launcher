package com.mylauncher.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The single translucent "glass" highlight. It is one view that springs from target to target;
 * tiles have no borders of their own.
 */
final class GlassView extends View {
    private float l, t, r, b, radius;
    private boolean placed;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SpringAnimation sl, st, sr, sb;

    private static final float STIFFNESS = 520f;
    private static final float DAMPING = 0.72f;   // < 1 gives the little spring overshoot

    private static final FloatPropertyCompat<GlassView> L = new FloatPropertyCompat<GlassView>("l") {
        @Override public float getValue(GlassView o) { return o.l; }
        @Override public void setValue(GlassView o, float v) { o.l = v; o.invalidate(); }
    };
    private static final FloatPropertyCompat<GlassView> T = new FloatPropertyCompat<GlassView>("t") {
        @Override public float getValue(GlassView o) { return o.t; }
        @Override public void setValue(GlassView o, float v) { o.t = v; o.invalidate(); }
    };
    private static final FloatPropertyCompat<GlassView> R = new FloatPropertyCompat<GlassView>("r") {
        @Override public float getValue(GlassView o) { return o.r; }
        @Override public void setValue(GlassView o, float v) { o.r = v; o.invalidate(); }
    };
    private static final FloatPropertyCompat<GlassView> B = new FloatPropertyCompat<GlassView>("b") {
        @Override public float getValue(GlassView o) { return o.b; }
        @Override public void setValue(GlassView o, float v) { o.b = v; o.invalidate(); }
    };

    GlassView(Context ctx, float strokeWidth) {
        super(ctx);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x33FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(0x88FFFFFF);
        sl = spring(L);
        st = spring(T);
        sr = spring(R);
        sb = spring(B);
    }

    private SpringAnimation spring(FloatPropertyCompat<GlassView> prop) {
        SpringAnimation a = new SpringAnimation(this, prop);
        a.setSpring(new SpringForce(0f).setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        a.setMinimumVisibleChange(0.5f);
        return a;
    }

    boolean isPlaced() {
        return placed;
    }

    /** Move to the given rectangle (page coordinates). */
    void moveTo(float nl, float nt, float nr, float nb, float rad, boolean animate) {
        radius = rad;
        if (!placed || !animate) {
            cancelAll();
            l = nl; t = nt; r = nr; b = nb;
            placed = true;
            invalidate();
            return;
        }
        sl.animateToFinalPosition(nl);
        st.animateToFinalPosition(nt);
        sr.animateToFinalPosition(nr);
        sb.animateToFinalPosition(nb);
    }

    private void cancelAll() {
        if (sl.isRunning()) sl.cancel();
        if (st.isRunning()) st.cancel();
        if (sr.isRunning()) sr.cancel();
        if (sb.isRunning()) sb.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!placed) return;
        c.drawRoundRect(l, t, r, b, radius, radius, fill);
        c.drawRoundRect(l, t, r, b, radius, radius, stroke);
    }
}
