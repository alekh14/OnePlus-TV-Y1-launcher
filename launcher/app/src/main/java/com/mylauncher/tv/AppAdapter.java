package com.mylauncher.tv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The 4-column app grid. Focus is driven from MainActivity, never by Android's focus system. */
final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
    static final float FOCUS_SCALE = 1.07f;

    private final List<AppEntry> items = new ArrayList<>();
    private final int cellW, cellH, tileW, tileH, tileTop, labelTop;
    private final float labelSizePx;
    private final Typeface font;
    private int focused = -1;

    AppAdapter(int cellW, int cellH, int tileW, int tileH, int tileTop, int labelTop,
               float labelSizePx, Typeface font) {
        this.cellW = cellW;
        this.cellH = cellH;
        this.tileW = tileW;
        this.tileH = tileH;
        this.tileTop = tileTop;
        this.labelTop = labelTop;
        this.labelSizePx = labelSizePx;
        this.font = font;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView tile;
        final TextView label;

        VH(FrameLayout cell, ImageView tile, TextView label) {
            super(cell);
            this.tile = tile;
            this.label = label;
        }
    }

    void setItems(List<AppEntry> list) {
        items.clear();
        items.addAll(list);
        focused = -1;
        notifyDataSetChanged();
    }

    AppEntry get(int pos) {
        return (pos >= 0 && pos < items.size()) ? items.get(pos) : null;
    }

    int indexOfPackage(String pkg) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        cell.setLayoutParams(new RecyclerView.LayoutParams(cellW, cellH));
        cell.setClipChildren(false);
        cell.setFocusable(false);

        ImageView tile = new ImageView(parent.getContext());
        tile.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(tileW, tileH,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = tileTop;
        cell.addView(tile, tlp);

        TextView label = new TextView(parent.getContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSizePx);
        label.setTextColor(Color.WHITE);
        label.setTypeface(font);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(tileW,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        llp.topMargin = labelTop;
        cell.addView(label, llp);

        return new VH(cell, tile, label);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppEntry e = items.get(position);
        h.tile.setImageDrawable(e.tile);
        h.label.setText(e.label);
        applyState(h, position == focused, false);
    }

    /** Update which tile looks focused (-1 for none). */
    void setFocused(RecyclerView rv, int pos) {
        int old = focused;
        focused = pos;
        if (old == pos) return;
        update(rv, old);
        update(rv, pos);
    }

    private void update(RecyclerView rv, int pos) {
        if (pos < 0) return;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh instanceof VH) applyState((VH) vh, pos == focused, true);
    }

    private void applyState(VH h, boolean on, boolean animate) {
        float s = on ? FOCUS_SCALE : 1f;
        float a = on ? 1f : 0f;
        if (animate) {
            h.tile.animate().scaleX(s).scaleY(s).setDuration(170).start();
            h.label.animate().alpha(a).setDuration(170).start();
        } else {
            h.tile.animate().cancel();
            h.label.animate().cancel();
            h.tile.setScaleX(s);
            h.tile.setScaleY(s);
            h.label.setAlpha(a);
        }
    }
}
