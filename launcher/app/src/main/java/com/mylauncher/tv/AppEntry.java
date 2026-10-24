package com.mylauncher.tv;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One launchable app. Built on a background thread, read on the UI thread. */
final class AppEntry {
    String label;
    String pkg;
    ComponentName component;
    Drawable tile;   // rounded 16:9 tile (banner, or icon on a dark plate)
    int glow;        // colour sampled from the banner/icon, used for the ambient glow
}
