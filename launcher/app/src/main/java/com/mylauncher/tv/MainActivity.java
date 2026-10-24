package com.mylauncher.tv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple-TV-style home screen. All D-pad navigation is handled here with an explicit
 * (zone, index) model, so Up/Down always move exactly one row and nothing falls through.
 *
 * Layout is expressed in "units" where the screen height is 540 units, so it looks the same on
 * 720p and 1080p panels whatever the density bucket.
 */
public class MainActivity extends Activity {

    private static final String SECURE_DOCK_KEY = "tvl_dock";
    private static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar.dplus.tv"
    };
    private static final int COLS = 4;
    private static final int MAX_DOCK = 4;

    private static final int Z_TOP = 0, Z_DOCK = 1, Z_GRID = 2, Z_MENU = 3;

    // ---- geometry ----
    private float u;
    private int W, H;
    private int margin, cellW, tileW, tileH, bandTop, bandH, shiftPx;

    // ---- views ----
    private FrameLayout root, page;
    private GlowView glow;
    private GlassView glass;
    private TextView greeting, heroName;
    private final TextView[] pills = new TextView[3];
    private LinearLayout topBar;
    private View dockBand;
    private final List<ImageView> dockTiles = new ArrayList<>();
    private RecyclerView rv;
    private GridLayoutManager gridLm;
    private AppAdapter adapter;
    private LinearLayout menuPanel;
    private final List<TextView> menuRows = new ArrayList<>();
    private final List<Intent> menuIntents = new ArrayList<>();

    // ---- state ----
    private int zone = Z_DOCK;
    private int topIdx = 0, dockIdx = 0, gridIdx = 0, menuIdx = 0;
    private List<AppEntry> dock = new ArrayList<>();
    private List<AppEntry> grid = new ArrayList<>();
    private boolean appsLoaded = false;
    private String pendingGridPkg;       // restored from prefs until apps are loaded
    private int pendingZone = -1;

    private SharedPreferences prefs;
    private Typeface fThin, fLight, fRegular;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator decel = new DecelerateInterpolator(1.6f);
    private ValueAnimator glowAnim;

    private final Runnable reloadApps = new Runnable() {
        @Override public void run() { loadApps(); }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(reloadApps);
            ui.postDelayed(reloadApps, 700);
        }
    };
    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateWifiPill(); }
    };
    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); }
    };

    // =====================================================================================
    // lifecycle
    // =====================================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        u = H / 540f;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        loadTypefaces();
        computeGeometry();
        buildUi();
        readSavedState();
        setContentView(root);
        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateGreeting();
        updateWifiPill();
        if (appsLoaded) loadApps();   // pick up changes made while we were away (e.g. dock setting)
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(netReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(tickReceiver); } catch (Exception ignored) { }
        saveState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** Home button pressed while we are already running: go back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (zone == Z_MENU) closeMenuQuiet();
        zone = dock.isEmpty() ? (grid.isEmpty() ? Z_TOP : Z_GRID) : Z_DOCK;
        if (zone == Z_DOCK && gridLm != null) gridLm.scrollToPositionWithOffset(0, 0);
        applyFocus(false);
    }

    @Override
    public void onBackPressed() {
        if (zone == Z_MENU) { closeMenu(); return; }
        if (zone == Z_GRID) { zone = Z_DOCK; applyFocus(true); saveState(); return; }
        if (zone == Z_TOP) { zone = Z_DOCK; applyFocus(true); return; }
        // On the dock a launcher never exits.
    }

    // =====================================================================================
    // setup
    // =====================================================================================

    private float px(float units) { return units * u; }

    private void loadTypefaces() {
        fThin = font("fonts/Outfit-Thin.ttf", "sans-serif-thin");
        fLight = font("fonts/Outfit-Light.ttf", "sans-serif-light");
        fRegular = font("fonts/Outfit-Regular.ttf", "sans-serif");
    }

    private Typeface font(String asset, String fallbackFamily) {
        try {
            return Typeface.createFromAsset(getAssets(), asset);
        } catch (Throwable t) {
            return Typeface.create(fallbackFamily, Typeface.NORMAL);
        }
    }

    private void computeGeometry() {
        margin = (int) px(48);
        cellW = (W - 2 * margin) / COLS;
        tileW = cellW - (int) px(24);
        tileH = tileW * 9 / 16;
        bandH = tileH + (int) px(28);
        bandTop = H - bandH - (int) px(28);
        shiftPx = bandTop - (int) px(36);
    }

    private TextView text(float sizeUnits, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeUnits));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setFocusable(false);
        return t;
    }

    private FrameLayout.LayoutParams lp(int w, int h, int left, int top) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        root.setFocusable(false);

        // optional wallpaper: put wallpaper.jpg in app/src/main/assets and rebuild
        Bitmap wp = loadWallpaper();
        if (wp != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(wp);
            root.addView(iv, new FrameLayout.LayoutParams(W, H));
            View dim = new View(this);
            dim.setBackgroundColor(0xAA000000);
            root.addView(dim, new FrameLayout.LayoutParams(W, H));
        }

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(W, H));

        glow = new GlowView(this);
        glow.setGeometry(W * 0.32f, bandTop - px(40), W * 0.55f);
        page.addView(glow, new FrameLayout.LayoutParams(W, H));

        // ---- top left: greeting, clock, date ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        greeting = text(17, 0xB3FFFFFF, fRegular);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(78));
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(fThin);
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        date.setTextColor(0xB3FFFFFF);
        date.setTypeface(fLight);
        date.setIncludeFontPadding(false);
        head.addView(greeting);
        head.addView(clock);
        head.addView(date);
        page.addView(head, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                margin, (int) px(26)));

        // ---- top right: pills ----
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            TextView p = text(15, Color.WHITE, fRegular);
            p.setText(names[i]);
            p.setSingleLine(true);
            p.setEllipsize(TextUtils.TruncateAt.END);
            p.setGravity(Gravity.CENTER);
            p.setPadding((int) px(18), 0, (int) px(18), 0);
            p.setAlpha(0.85f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x2EFFFFFF);          // frosted look without any blur
            bg.setStroke(Math.max(1, (int) px(1)), 0x33FFFFFF);
            bg.setCornerRadius(px(18));
            p.setBackground(bg);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) px(36));
            plp.leftMargin = (int) px(12);
            topBar.addView(p, plp);
            pills[i] = p;
        }
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.topMargin = (int) px(32);
        tlp.rightMargin = margin;
        page.addView(topBar, tlp);

        // ---- hero name above the dock ----
        heroName = text(46, Color.WHITE, fLight);
        heroName.setSingleLine(true);
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        page.addView(heroName, lp(W - 2 * margin, ViewGroup.LayoutParams.WRAP_CONTENT,
                margin + (int) px(8), bandTop - (int) px(24) - (int) px(54)));

        // ---- dock band ----
        dockBand = new View(this);
        GradientDrawable band = new GradientDrawable();
        band.setColor(0xFF161618);
        band.setCornerRadius(px(30));
        dockBand.setBackground(band);
        page.addView(dockBand, lp(W - 2 * margin, bandH, margin, bandTop));

        // ---- grid ----
        int gridTop = bandTop + bandH + (int) px(20);
        int cellH = tileH + (int) px(44);
        int onScreenTop = gridTop - shiftPx;
        int gridH = H - onScreenTop - (int) px(6);
        adapter = new AppAdapter(cellW, cellH, tileW, tileH, (int) px(8), (int) px(8) + tileH + (int) px(10),
                px(15), fRegular);
        rv = new RecyclerView(this);
        gridLm = new GridLayoutManager(this, COLS);
        rv.setLayoutManager(gridLm);
        rv.setAdapter(adapter);
        rv.setItemAnimator(null);
        rv.setHasFixedSize(true);
        rv.setFocusable(false);
        rv.setClipChildren(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setAlpha(0f);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView r, int dx, int dy) {
                if (zone == Z_GRID) moveGlass(false);   // keep the highlight glued to the tile while scrolling
            }
        });
        page.addView(rv, lp(W - 2 * margin, gridH, margin, gridTop));

        // ---- inputs dropdown (hidden until opened) ----
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setPadding((int) px(8), (int) px(8), (int) px(8), (int) px(8));
        GradientDrawable mbg = new GradientDrawable();
        mbg.setColor(0xF01E1E21);
        mbg.setStroke(Math.max(1, (int) px(1)), 0x33FFFFFF);
        mbg.setCornerRadius(px(16));
        menuPanel.setBackground(mbg);
        menuPanel.setVisibility(View.GONE);
        page.addView(menuPanel, lp((int) px(230), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        // ---- the one glass highlight, always on top ----
        glass = new GlassView(this, Math.max(1.5f, px(1.2f)));
        page.addView(glass, new FrameLayout.LayoutParams(W, H));
    }

    private Bitmap loadWallpaper() {
        try {
            InputStream in = getAssets().open("wallpaper.jpg");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int s = 1;
            while (o.outWidth / (s * 2) >= W) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory
            in = getAssets().open("wallpaper.jpg");
            Bitmap bm = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return bm;
        } catch (Throwable t) {
            return null;   // no wallpaper file: pitch black
        }
    }

    // =====================================================================================
    // apps
    // =====================================================================================

    private void loadApps() {
        final String keepPkg = (zone == Z_GRID && adapter.get(gridIdx) != null) ? adapter.get(gridIdx).pkg : null;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    PackageManager pm = getPackageManager();
                    List<AppEntry> list = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    collect(pm, list, seen, Intent.CATEGORY_LEANBACK_LAUNCHER);
                    collect(pm, list, seen, Intent.CATEGORY_LAUNCHER);
                    Collections.sort(list, new Comparator<AppEntry>() {
                        @Override public int compare(AppEntry a, AppEntry b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                    String setting = Settings.Secure.getString(getContentResolver(), SECURE_DOCK_KEY);
                    final String[] wanted = (setting != null && setting.trim().length() > 0)
                            ? setting.split(",") : DEFAULT_DOCK;
                    final List<AppEntry> dockList = new ArrayList<>();
                    for (String w : wanted) {
                        String pkg = w.trim();
                        for (AppEntry e : list) {
                            if (e.pkg.equals(pkg)) { dockList.add(e); break; }
                        }
                        if (dockList.size() >= MAX_DOCK) break;
                    }
                    final List<AppEntry> gridList = new ArrayList<>(list);
                    gridList.removeAll(dockList);
                    ui.post(new Runnable() {
                        @Override public void run() { onAppsLoaded(dockList, gridList, keepPkg); }
                    });
                } catch (Throwable t) {
                    // leave the UI as it is
                }
            }
        });
    }

    private void collect(PackageManager pm, List<AppEntry> out, Set<String> seen, String category) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.component = new ComponentName(pkg, ri.activityInfo.name);
            CharSequence lbl = ri.loadLabel(pm);
            e.label = lbl != null ? lbl.toString() : pkg;

            Drawable banner = null;
            try { banner = ri.activityInfo.loadBanner(pm); } catch (Throwable ignored) { }
            if (banner == null) {
                try {
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    banner = pm.getApplicationBanner(ai);
                } catch (Throwable ignored) { }
            }
            Drawable src;
            boolean isBanner;
            if (banner != null) {
                src = banner;
                isBanner = true;
            } else {
                src = ri.loadIcon(pm);
                isBanner = false;
            }
            e.tile = new TileDrawable(src, isBanner, px(14));
            e.glow = ColorSampler.sample(src);
            out.add(e);
        }
    }

    private void onAppsLoaded(List<AppEntry> dockList, List<AppEntry> gridList, String keepPkg) {
        dock = dockList;
        grid = gridList;
        adapter.setItems(grid);
        buildDockTiles();

        if (!appsLoaded) {
            // first load: restore what was focused last time
            appsLoaded = true;
            if (pendingGridPkg != null) {
                int gi = adapter.indexOfPackage(pendingGridPkg);
                if (gi >= 0) gridIdx = gi;
            }
            zone = (pendingZone == Z_GRID && !grid.isEmpty()) ? Z_GRID : Z_DOCK;
        } else if (keepPkg != null) {
            int gi = adapter.indexOfPackage(keepPkg);
            if (gi >= 0) gridIdx = gi;
        }
        dockIdx = Math.max(0, Math.min(dockIdx, dock.size() - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, grid.size() - 1));
        if (dock.isEmpty() && zone == Z_DOCK) zone = grid.isEmpty() ? Z_TOP : Z_GRID;
        if (grid.isEmpty() && zone == Z_GRID) zone = dock.isEmpty() ? Z_TOP : Z_DOCK;
        if (zone == Z_GRID) gridLm.scrollToPositionWithOffset(gridIdx, 0);

        page.post(new Runnable() {
            @Override public void run() { applyFocus(false); }
        });
    }

    private void buildDockTiles() {
        for (ImageView v : dockTiles) page.removeView(v);
        dockTiles.clear();
        int top = bandTop + (bandH - tileH) / 2;
        int glassIndex = page.indexOfChild(glass);
        for (int i = 0; i < dock.size(); i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(dock.get(i).tile);
            iv.setFocusable(false);
            int left = margin + i * cellW + (cellW - tileW) / 2;
            page.addView(iv, glassIndex + i, lp(tileW, tileH, left, top));
            dockTiles.add(iv);
        }
    }

    private void launch(AppEntry e) {
        if (e == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            i.setComponent(e.component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            toast("Cannot open " + e.label);
        }
    }

    // =====================================================================================
    // key handling
    // =====================================================================================

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:    move(0, -1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN:  move(0, 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT:  move(-1, 0); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
                default: select(); break;
            }
        }
        return true;   // we own every navigation key; Android's focus search never runs
    }

    private void move(int dx, int dy) {
        switch (zone) {
            case Z_TOP:
                if (dx != 0) {
                    topIdx = clamp(topIdx + dx, 0, pills.length - 1);   // ends stay put
                } else if (dy > 0) {
                    if (!dock.isEmpty()) zone = Z_DOCK;
                    else if (!grid.isEmpty()) zone = Z_GRID;
                }
                break;
            case Z_DOCK:
                if (dx != 0) {
                    dockIdx = clamp(dockIdx + dx, 0, Math.max(0, dock.size() - 1));
                } else if (dy < 0) {
                    zone = Z_TOP;
                } else if (dy > 0 && !grid.isEmpty()) {
                    zone = Z_GRID;
                    gridIdx = Math.min(dockIdx, grid.size() - 1);   // straight down, first row
                }
                break;
            case Z_GRID:
                if (dx != 0) {
                    int col = gridIdx % COLS;
                    int nc = col + dx;
                    int ni = gridIdx + dx;
                    if (nc >= 0 && nc < COLS && ni >= 0 && ni < grid.size()) gridIdx = ni;
                } else if (dy < 0) {
                    if (gridIdx < COLS) {
                        zone = dock.isEmpty() ? Z_TOP : Z_DOCK;
                        if (!dock.isEmpty()) dockIdx = Math.min(gridIdx % COLS, dock.size() - 1);
                    } else {
                        gridIdx -= COLS;
                    }
                } else {
                    int n = gridIdx + COLS;
                    if (n < grid.size()) {
                        gridIdx = n;
                    } else {
                        int lastRowStart = ((grid.size() - 1) / COLS) * COLS;
                        if (gridIdx < lastRowStart) gridIdx = grid.size() - 1;   // short last row
                    }
                }
                if (zone == Z_GRID) scrollGridTo(gridIdx);
                break;
            case Z_MENU:
                if (dy != 0) menuIdx = clamp(menuIdx + dy, 0, Math.max(0, menuRows.size() - 1));
                break;
            default:
                break;
        }
        applyFocus(true);
        saveState();
    }

    private void select() {
        switch (zone) {
            case Z_TOP:
                if (topIdx == 0) openMenu();
                else if (topIdx == 1) openSettings(Settings.ACTION_WIFI_SETTINGS);
                else openSettings(Settings.ACTION_SETTINGS);
                break;
            case Z_DOCK:
                if (dockIdx < dock.size()) launch(dock.get(dockIdx));
                break;
            case Z_GRID:
                launch(adapter.get(gridIdx));
                break;
            case Z_MENU:
                if (menuIdx >= 0 && menuIdx < menuIntents.size()) {
                    Intent i = menuIntents.get(menuIdx);
                    closeMenu();
                    try {
                        startActivity(i);
                    } catch (Throwable t) {
                        toast("Nothing on this TV can open that input");
                    }
                }
                break;
            default:
                break;
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t2) {
                toast("Settings not available");
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void scrollGridTo(int pos) {
        LinearSmoothScroller s = new LinearSmoothScroller(this) {
            @Override protected int getVerticalSnapPreference() { return SNAP_TO_ANY; }
            @Override protected float calculateSpeedPerPixel(DisplayMetrics dm) { return 60f / dm.densityDpi; }
        };
        s.setTargetPosition(pos);
        gridLm.startSmoothScroll(s);
    }

    // =====================================================================================
    // focus visuals
    // =====================================================================================

    private void applyFocus(boolean animate) {
        boolean gridMode = zone == Z_GRID;
        float ty = gridMode ? -shiftPx : 0f;
        if (animate) {
            page.animate().translationY(ty).setDuration(300).setInterpolator(decel).start();
            rv.animate().alpha(gridMode ? 1f : 0f).setDuration(260).start();
        } else {
            page.animate().cancel();
            rv.animate().cancel();
            page.setTranslationY(ty);
            rv.setAlpha(gridMode ? 1f : 0f);
        }

        if (zone != Z_MENU && menuPanel.getVisibility() == View.VISIBLE) closeMenuQuiet();

        for (int i = 0; i < pills.length; i++) {
            boolean on = zone == Z_TOP && i == topIdx;
            scale(pills[i], on ? 1.06f : 1f, animate);
            pills[i].setAlpha(on || (zone == Z_MENU && i == 0) ? 1f : 0.85f);
        }
        for (int i = 0; i < dockTiles.size(); i++) {
            scale(dockTiles.get(i), (zone == Z_DOCK && i == dockIdx) ? 1.08f : 1f, animate);
        }
        adapter.setFocused(rv, gridMode ? gridIdx : -1);
        for (int i = 0; i < menuRows.size(); i++) {
            menuRows.get(i).setAlpha(zone == Z_MENU && i == menuIdx ? 1f : 0.7f);
        }
        updateHero();
        moveGlass(animate);
    }

    private void scale(View v, float s, boolean animate) {
        if (animate) {
            v.animate().scaleX(s).scaleY(s).setDuration(170).start();
        } else {
            v.animate().cancel();
            v.setScaleX(s);
            v.setScaleY(s);
        }
    }

    private void updateHero() {
        if (dock.isEmpty()) {
            heroName.setText("");
            return;
        }
        AppEntry e = dock.get(clamp(dockIdx, 0, dock.size() - 1));
        if (!e.label.contentEquals(heroName.getText())) heroName.setText(e.label);
        animateGlow(e.glow);
    }

    private void animateGlow(int target) {
        int from = glow.getColor();
        if (from == target) return;
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        glowAnim.setDuration(380);
        glowAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { glow.setColor((Integer) a.getAnimatedValue()); }
        });
        glowAnim.start();
    }

    private final RectF tmp = new RectF();

    /** Position of v relative to the page (ignores the page's own translation). */
    private void rectInPage(View v, RectF out) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != page) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
        }
        out.set(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private void moveGlass(boolean animate) {
        View target = null;
        float s = 1f, pad = 0f, radius = px(12);
        switch (zone) {
            case Z_TOP:
                target = pills[topIdx];
                s = 1.06f; pad = px(2);
                break;
            case Z_DOCK:
                if (dockIdx < dockTiles.size()) target = dockTiles.get(dockIdx);
                s = 1.08f; pad = px(6); radius = px(18);
                break;
            case Z_GRID: {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(gridIdx);
                if (vh instanceof AppAdapter.VH) target = ((AppAdapter.VH) vh).tile;
                s = AppAdapter.FOCUS_SCALE; pad = px(6); radius = px(18);
                break;
            }
            case Z_MENU:
                if (menuIdx < menuRows.size()) target = menuRows.get(menuIdx);
                radius = px(10);
                break;
            default:
                break;
        }
        if (target == null) { glass.setVisibility(View.INVISIBLE); return; }
        if (target.getWidth() == 0) {
            // not laid out yet: try again next frame
            final boolean a = animate;
            page.post(new Runnable() { @Override public void run() { moveGlass(a); } });
            return;
        }
        glass.setVisibility(View.VISIBLE);
        rectInPage(target, tmp);
        float cx = tmp.centerX(), cy = tmp.centerY();
        float hw = tmp.width() * s / 2f + pad, hh = tmp.height() * s / 2f + pad;
        if (zone == Z_TOP) radius = hh;
        glass.moveTo(cx - hw, cy - hh, cx + hw, cy + hh, radius, animate);
    }

    // =====================================================================================
    // inputs dropdown
    // =====================================================================================

    private void openMenu() {
        menuPanel.removeAllViews();
        menuRows.clear();
        menuIntents.clear();

        List<String> labels = new ArrayList<>();
        List<Intent> intents = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = tim != null ? tim.getTvInputList() : new ArrayList<TvInputInfo>();
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;   // HDMI, AV/composite, component...
                CharSequence l = info.loadCustomLabel(this);
                if (l == null || l.length() == 0) l = info.loadLabel(this);
                labels.add(l != null ? l.toString() : info.getId());
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intents.add(i);
            }
        } catch (Throwable t) {
            // fall through to the Live TV row only
        }
        // sort HDMI 1, HDMI 2, ... AV by label
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) order.add(i);
        final List<String> lbl = labels;
        Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return lbl.get(a).compareToIgnoreCase(lbl.get(b)); }
        });
        List<String> sl = new ArrayList<>();
        List<Intent> si = new ArrayList<>();
        for (int idx : order) { sl.add(labels.get(idx)); si.add(intents.get(idx)); }
        sl.add("Live TV");
        Intent live = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
        live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        si.add(live);

        for (int i = 0; i < sl.size(); i++) {
            TextView row = text(15, Color.WHITE, fRegular);
            row.setText(sl.get(i));
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) px(14), 0, (int) px(14), 0);
            menuPanel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) px(38)));
            menuRows.add(row);
            menuIntents.add(si.get(i));
        }

        rectInPage(pills[0], tmp);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
        p.leftMargin = (int) tmp.left;
        p.topMargin = (int) (tmp.bottom + px(8));
        menuPanel.setLayoutParams(p);
        menuPanel.setVisibility(View.VISIBLE);
        zone = Z_MENU;
        menuIdx = 0;
        // the rows have no size until the next layout pass
        menuPanel.post(new Runnable() { @Override public void run() { applyFocus(true); } });
    }

    private void closeMenu() {
        closeMenuQuiet();
        zone = Z_TOP;
        applyFocus(true);
    }

    private void closeMenuQuiet() {
        menuPanel.setVisibility(View.GONE);
        menuRows.clear();
        menuIntents.clear();
    }

    // =====================================================================================
    // header texts
    // =====================================================================================

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (h >= 5 && h < 12) g = "Good morning";
        else if (h >= 12 && h < 17) g = "Good afternoon";
        else if (h >= 17 && h < 22) g = "Good evening";
        else g = "Good night";
        greeting.setText(g);
    }

    private void updateWifiPill() {
        String t = networkLabel();
        pills[1].setText(t);
        // the pill changed width, so the highlight may need to follow
        page.post(new Runnable() { @Override public void run() { if (zone == Z_TOP) moveGlass(false); } });
    }

    private String networkLabel() {
        try {
            Context app = getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Wi-Fi · Not connected";
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet";
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            String ssid = wi != null ? wi.getSSID() : null;
            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("0x") && ssid.length() > 0) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (ssid.length() > 22) ssid = ssid.substring(0, 21) + "…";
                return "Wi-Fi · " + ssid;
            }
            return "Wi-Fi · Connected";
        } catch (Throwable t) {
            return "Wi-Fi";
        }
    }

    // =====================================================================================
    // persistence
    // =====================================================================================

    private void readSavedState() {
        dockIdx = prefs.getInt("dockIdx", 0);
        topIdx = clamp(prefs.getInt("topIdx", 0), 0, 2);
        pendingGridPkg = prefs.getString("gridPkg", null);
        pendingZone = prefs.getInt("zone", Z_DOCK);
    }

    private void saveState() {
        if (!appsLoaded) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt("zone", zone == Z_GRID ? Z_GRID : Z_DOCK);
        ed.putInt("dockIdx", dockIdx);
        ed.putInt("topIdx", topIdx);
        AppEntry g = adapter.get(gridIdx);
        if (g != null) ed.putString("gridPkg", g.pkg);
        ed.apply();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
