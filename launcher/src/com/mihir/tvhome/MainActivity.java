package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Glass-style home screen: a rail of icon buttons on the left, the date/clock/prayer header on top,
 * a tab pill (Favorit / Live / Aplikasi) and one glass panel holding either the favourite apps plus
 * the recently opened ones, or the grid of every other app. Also shows the city, weather (Open-Meteo,
 * refreshed every 30 min) and today's prayer times (computed on the device from config.xml).
 * Plain framework views only (no AndroidX).
 *
 * Memory: the glass is a tiny blurred copy of the wallpaper drawn behind each panel (no real-time
 * blur); the wallpaper is decoded no larger than the screen; app art is loaded only for tiles that
 * are on or near the screen; and while another app is in front, the wallpaper and every tile image
 * are released.
 */
public class MainActivity extends Activity {

    static final int PAGE_FAV = 0, PAGE_LIVE = 1, PAGE_APPS = 2;
    static final int MODE_DOCK = 0, MODE_RECENT = 1, MODE_GRID = 2;
    static final int MAX_RECENT = 8;

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        boolean isBanner;
        int color; // 0 until the art has been sampled once; then remembered across restarts
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final Map<String, App> allApps = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> recentCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> railItems = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private SharedPreferences colorPrefs;

    private ScrollView appsScroll;
    private LinearLayout favPage, dockRow, recentRow, grid;
    private HorizontalScrollView recentScroll;
    private TextView recentLabel;
    private View railView, headerView, tabsView, panelView;
    private View railInputs, railWifi;
    private int page = PAGE_FAV;
    private boolean recentDirty;

    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private final Glass glass = new Glass();
    private int sw, sh; // screen size in pixels
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private Bitmap curBm, fadeOld;
    private boolean stopped, wallCleared;
    private int wallGen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView clock, date, title;

    // Location, weather and prayer times; settings live in res/values/config.xml.
    private String locationName;
    private double lat, lng;
    private int fajrAngle, ishaAngle, ihtiyat;
    private TextView weather;
    private final TextView[] prayerName = new TextView[5];
    private final TextView[] prayerTime = new TextView[5];
    private long weatherAt;
    private boolean weatherBusy;
    static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000;
    private final Runnable refreshWeather = this::fetchWeather;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        locationName = getString(R.string.location_name);
        try {
            lat = Double.parseDouble(getString(R.string.location_lat).trim());
            lng = Double.parseDouble(getString(R.string.location_lng).trim());
        } catch (NumberFormatException e) { // a typo in config.xml must not break the home screen
            lat = -6.2383;
            lng = 106.9756;
        }
        fajrAngle = getResources().getInteger(R.integer.prayer_fajr_angle);
        ishaAngle = getResources().getInteger(R.integer.prayer_isha_angle);
        ihtiyat = getResources().getInteger(R.integer.prayer_ihtiyat_min);
        sw = getResources().getDisplayMetrics().widthPixels;
        sh = getResources().getDisplayMetrics().heightPixels;
        colorPrefs = getSharedPreferences("colors", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!stopped) return;
        stopped = false;
        for (View c : dockCards) bindArt(c, true);
        for (View c : recentCards) bindArt(c, true);
        if (page == PAGE_APPS) appsScroll.post(this::updateGridArt);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        long sinceWeather = SystemClock.elapsedRealtime() - weatherAt;
        if (weatherAt == 0 || sinceWeather >= WEATHER_INTERVAL_MS) fetchWeather();
        else scheduleWeather(WEATHER_INTERVAL_MS - sinceWeather);
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        else if (recentDirty) { buildRecent(); wireFocus(); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null && last.isShown()) last.requestFocus();
        else focusDefault();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        long since = SystemClock.elapsedRealtime() - wallChangedAt;
        if (wallIndex < 0 || since >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else {
            if (wallCleared) loadWallpaper(wallIndex);
            scheduleWallpaper(WALLPAPER_INTERVAL_MS - since);
        }
        wallCleared = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(refreshWeather);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    /** Another app is in front: give back the wallpaper and every tile image. */
    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        wallGen++;
        wallFront.animate().cancel();
        wallBack.animate().cancel();
        wallFront.setImageDrawable(null);
        wallBack.setImageDrawable(null);
        recycle(fadeOld);
        recycle(curBm);
        fadeOld = null;
        curBm = null;
        glass.clear();
        wallCleared = true;
        for (View c : dockCards) bindArt(c, false);
        for (View c : recentCards) bindArt(c, false);
        for (View c : gridCards) bindArt(c, false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the favourites.
        if (inputsPopup != null) inputsPopup.dismiss();
        showPage(PAGE_FAV);
        focusDefault();
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back returns to the favourites.
        showPage(PAGE_FAV);
        focusDefault();
    }

    private void focusDefault() {
        if (page == PAGE_APPS && !gridCards.isEmpty()) gridCards.get(0).requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        else if (!tabViews.isEmpty()) tabViews.get(0).requestFocus();
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top and bottom so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        railView = buildRail();
        root.addView(railView, lp(dp(60), -1, Gravity.START, dp(28), dp(28), 0, dp(28)));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setClipChildren(false);
        main.setClipToPadding(false);
        headerView = buildHeader();
        main.addView(headerView, llp(-1, -2, 0, 0, 0, 0));
        tabsView = buildTabs();
        main.addView(tabsView, llp(-2, dp(36), 0, dp(14), 0, 0));
        panelView = buildPanel();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1f);
        pp.setMargins(0, dp(14), 0, 0);
        main.addView(panelView, pp);
        root.addView(main, lp(-1, -1, Gravity.START | Gravity.TOP, dp(114), dp(30), dp(40), dp(30)));
        return root;
    }

    /** Left rail: Home and Inputs at the top, Wi-Fi and Settings at the bottom. */
    private View buildRail() {
        FrameLayout rail = new FrameLayout(this);
        rail.addView(new GlassView(this, glass, dp(30), sw, sh), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(16), 0, dp(16));
        rail.addView(col, new FrameLayout.LayoutParams(-1, -1));

        View home = railItem("Beranda", R.drawable.ic_home, true);
        home.setOnClickListener(v -> { showPage(PAGE_FAV); focusDefault(); });
        railInputs = railItem("Inputs", R.drawable.ic_inputs, false);
        railInputs.setOnClickListener(v -> showInputs(v, true));
        railWifi = railItem("Wi-Fi", R.drawable.ic_wifi, false);
        railWifi.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settings = railItem("Settings", R.drawable.ic_settings, false);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));

        col.addView(home, llp(dp(44), dp(44), 0, 0, 0, 0));
        col.addView(railInputs, llp(dp(44), dp(44), 0, dp(14), 0, 0));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(railWifi, llp(dp(44), dp(44), 0, 0, 0, 0));
        col.addView(settings, llp(dp(44), dp(44), 0, dp(14), 0, 0));
        for (View v : new View[]{home, railInputs, railWifi, settings}) railItems.add(v);
        return rail;
    }

    /** A round icon button; the title at the top shows its label while it is focused. */
    private View railItem(String label, int iconRes, boolean current) {
        FrameLayout item = new FrameLayout(this);
        item.setFocusable(true);
        item.setId(View.generateViewId());
        item.setTag(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(current ? 0x40FFFFFF : Color.TRANSPARENT);
        item.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xE6FFFFFF);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        item.addView(icon, ip);

        item.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : current ? 0x40FFFFFF : Color.TRANSPARENT);
            icon.setColorFilter(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) {
                lastFocusedId = v.getId();
                title.setText(String.valueOf(v.getTag()));
                title.animate().alpha(1f).setDuration(200).start();
                fadeGlow(1f);
            }
        });
        return item;
    }

    /** Tab pill: Favorit and Aplikasi switch the panel on focus; Live opens the Inputs list. */
    private View buildTabs() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(new GlassView(this, glass, dp(18), sw, sh), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
        wrap.addView(row, new FrameLayout.LayoutParams(-2, -1));
        String[] names = {"Favorit", "Live", "Aplikasi"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(12, "sans-serif-medium", Color.WHITE);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(20), 0, dp(20), 0);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(15));
            t.setBackground(bg);
            t.setOnClickListener(v -> { if (idx == PAGE_LIVE) showInputs(v, false); });
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    lastFocusedId = v.getId();
                    title.setText(names[idx]);
                    title.animate().alpha(1f).setDuration(200).start();
                    fadeGlow(1f);
                    if (idx != PAGE_LIVE) showPage(idx);
                }
                updateTabStyles();
            });
            row.addView(t, llp(-2, -1, i == 0 ? 0 : dp(2), 0, 0, 0));
            tabViews.add(t);
        }
        updateTabStyles();
        return wrap;
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean selected = i == page;
            boolean focused = t.hasFocus();
            ((GradientDrawable) t.getBackground()).setColor(
                    selected ? 0xF2FFFFFF : focused ? 0x66FFFFFF : Color.TRANSPARENT);
            t.setTextColor(selected ? 0xFF111111 : Color.WHITE);
        }
    }

    /** One glass panel; the favourites page and the app grid share it. */
    private View buildPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setClipChildren(false);
        panel.addView(new GlassView(this, glass, dp(26), sw, sh), new FrameLayout.LayoutParams(-1, -1));

        favPage = new LinearLayout(this);
        favPage.setOrientation(LinearLayout.VERTICAL);
        favPage.setClipChildren(false);
        favPage.setClipToPadding(false);
        favPage.setPadding(dp(22), dp(14), dp(22), dp(14));

        dockRow = new LinearLayout(this);
        favPage.addView(hscroll(dockRow), new LinearLayout.LayoutParams(-1, -2));

        recentLabel = text(11, "sans-serif-medium", 0xCCFFFFFF);
        recentLabel.setText("TERAKHIR DIPAKAI");
        recentLabel.setLetterSpacing(0.15f);
        recentLabel.setPadding(dp(12), 0, 0, 0);
        favPage.addView(recentLabel, llp(-2, -2, 0, dp(10), 0, 0));
        recentRow = new LinearLayout(this);
        recentScroll = hscroll(recentRow);
        favPage.addView(recentScroll, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(favPage, new FrameLayout.LayoutParams(-1, -1));

        appsScroll = new HomeScrollView(this);
        appsScroll.setVerticalScrollBarEnabled(false);
        appsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appsScroll.setClipChildren(false);
        appsScroll.setClipToPadding(true);
        appsScroll.setPadding(dp(34), dp(22), dp(34), 0);
        appsScroll.setVisibility(View.GONE);
        appsScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateGridArt());
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(60));
        appsScroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(appsScroll, new FrameLayout.LayoutParams(-1, -1));
        return panel;
    }

    /** A horizontal strip that may hold more tiles than fit; it scrolls to the focused one. */
    private HorizontalScrollView hscroll(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(dp(12), dp(14), dp(12), dp(14));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        h.addView(row, new FrameLayout.LayoutParams(-2, -2));
        return h;
    }

    private void showPage(int p) {
        if (p == page) return;
        page = p;
        favPage.setVisibility(p == PAGE_FAV ? View.VISIBLE : View.GONE);
        appsScroll.setVisibility(p == PAGE_APPS ? View.VISIBLE : View.GONE);
        if (p == PAGE_APPS) {
            appsScroll.scrollTo(0, 0);
            appsScroll.post(this::updateGridArt);
        } else {
            for (View c : gridCards) bindArt(c, false);
        }
        updateTabStyles();
        updateNav();
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        // Top-left: the focused app's name, big, with the date as a small caption above it.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        date.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        left.addView(date);

        title = text(38, "sans-serif-condensed", Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setAllCaps(true);
        title.setLetterSpacing(0.03f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, 0, dp(16), 0);
        title.setShadowLayer(dp(14), 0, dp(2), 0x99000000);
        left.addView(title, llp(-2, -2, 0, dp(4), 0, 0));

        // Today's five prayer times; the next one is highlighted.
        LinearLayout prayers = new LinearLayout(this);
        prayers.setOrientation(LinearLayout.HORIZONTAL);
        prayers.setPadding(dp(14), dp(8), dp(14), dp(8));
        prayers.setBackground(pillBackground());
        String[] names = {"SUBUH", "DZUHUR", "ASHAR", "MAGHRIB", "ISYA"};
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            prayerName[i] = text(8, "sans-serif-medium", 0x99FFFFFF);
            prayerName[i].setText(names[i]);
            prayerName[i].setLetterSpacing(0.15f);
            prayerTime[i] = text(13, "sans-serif-medium", 0xB3FFFFFF);
            prayerTime[i].setText("--:--");
            cell.addView(prayerName[i]);
            cell.addView(prayerTime[i]);
            prayers.addView(cell, llp(-2, -2, i == 0 ? 0 : dp(18), 0, 0, 0));
        }
        left.addView(prayers, llp(-2, -2, 0, dp(12), 0, 0));
        header.addView(left, new FrameLayout.LayoutParams(dp(440), -2, Gravity.START | Gravity.TOP));

        // Top-right: a small clock pill, then icon buttons (the label shows while one is focused).
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);

        LinearLayout weatherPill = new LinearLayout(this);
        weatherPill.setGravity(Gravity.CENTER);
        weatherPill.setPadding(dp(14), 0, dp(14), 0);
        weatherPill.setBackground(pillBackground());
        weather = text(12, "sans-serif-medium", Color.WHITE);
        weather.setIncludeFontPadding(false);
        weather.setText("--\u00B0");
        weatherPill.addView(weather);
        right.addView(weatherPill, llp(-2, dp(30), 0, 0, 0, 0));

        LinearLayout timePill = new LinearLayout(this);
        timePill.setGravity(Gravity.CENTER);
        timePill.setPadding(dp(14), 0, dp(14), 0);
        timePill.setBackground(pillBackground());
        clock = text(12, "sans-serif-medium", Color.WHITE);
        clock.setIncludeFontPadding(false);
        timePill.addView(clock);
        right.addView(timePill, llp(-2, dp(30), dp(10), 0, 0, 0));
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private GradientDrawable pillBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        return bg;
    }


    // ---------------------------------------------------------------- apps

    /** Tile width: fits `cols` per row inside the panel (shared by favourites, recent and grid). */
    private int tileWidth() {
        int inner = sw - dp(114) - dp(40) - 2 * dp(34);
        return (inner - (cols - 1) * dp(14)) / cols;
    }

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        allApps.clear();
        // Only names and ids here; the art of each app is loaded later, for the tiles that need it.
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || allApps.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            a.isBanner = ai.banner != 0 || ai.applicationInfo.banner != 0;
            a.color = colorPrefs.getInt(a.pkg, 0);
            allApps.put(a.pkg, a);
        }

        Map<String, App> left = new HashMap<>(allApps);
        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (left.containsKey(p)) dock.add(left.remove(p));
        List<App> rest = new ArrayList<>(left.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        int w = tileWidth();
        int gap = dp(14);
        for (int i = 0; i < dock.size(); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, -2);
            p.setMargins(i == 0 ? 0 : gap, 0, 0, 0);
            dockRow.addView(tile(dock.get(i), MODE_DOCK), p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(22)));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, -2);
            p.setMargins(i % cols == 0 ? 0 : gap, 0, 0, 0);
            row.addView(tile(rest.get(i), MODE_GRID), p);
        }
        buildRecent();
        wireFocus();

        if (!dock.isEmpty()) {
            App first = dock.get(0);
            title.setText(first.label);
            if (first.color != 0) setGlow(first.color);
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null && last.isShown()) last.requestFocus();
        else focusDefault();
        if (page == PAGE_APPS) appsScroll.post(this::updateGridArt);
    }

    // The apps opened from this launcher, newest first (stored as package names).
    private List<String> recentPkgs() {
        String s = getPreferences(MODE_PRIVATE).getString("recent", "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    private void recordRecent(String pkg) {
        List<String> l = recentPkgs();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String p : l) sb.append(sb.length() == 0 ? "" : ",").append(p);
        getPreferences(MODE_PRIVATE).edit().putString("recent", sb.toString()).apply();
        recentDirty = true;
    }

    private void buildRecent() {
        recentRow.removeAllViews();
        recentCards.clear();
        int w = tileWidth();
        int n = 0;
        for (String p : recentPkgs()) {
            App a = allApps.get(p);
            if (a == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.setMargins(n == 0 ? 0 : dp(14), 0, 0, 0);
            recentRow.addView(tile(a, MODE_RECENT), lp);
            n++;
        }
        int vis = n > 0 ? View.VISIBLE : View.GONE;
        recentLabel.setVisibility(vis);
        recentScroll.setVisibility(vis);
        recentDirty = false;
    }

    /** The app's banner (or icon). Read on demand so only tiles in use hold a bitmap. */
    private Drawable loadArt(App a) {
        PackageManager pm = getPackageManager();
        try {
            ActivityInfo ai = pm.getActivityInfo(a.component, 0);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            return banner != null ? banner : ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Load or release one tile's image. The image is always the card's first child. */
    private void bindArt(View card, boolean on) {
        ImageView img = (ImageView) ((ViewGroup) card).getChildAt(0);
        if (!on) {
            if (img.getDrawable() != null) img.setImageDrawable(null);
            return;
        }
        if (img.getDrawable() != null || stopped) return;
        App a = (App) card.getTag();
        Drawable d = loadArt(a);
        if (d == null) return;
        if (a.color == 0) { // sample before the ImageView takes the drawable (it resets the bounds)
            a.color = sampleColor(a.pkg, d);
            colorPrefs.edit().putInt(a.pkg, a.color).apply();
        }
        img.setImageDrawable(d);
    }

    /** Keep art only for grid rows within about a screen of the visible area. */
    private void updateGridArt() {
        if (page != PAGE_APPS || stopped) return;
        if (grid.getHeight() == 0) { // not laid out yet: wait, or every row would count as visible
            appsScroll.postDelayed(this::updateGridArt, 50);
            return;
        }
        int h = appsScroll.getHeight() > 0 ? appsScroll.getHeight() : sh;
        int lo = appsScroll.getScrollY() - h / 2, hi = appsScroll.getScrollY() + h + h / 2;
        for (int r = 0; r < grid.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) grid.getChildAt(r);
            boolean visible = row.getBottom() + grid.getTop() > lo && row.getTop() + grid.getTop() < hi;
            for (int c = 0; c < row.getChildCount(); c++) {
                bindArt(((ViewGroup) row.getChildAt(c)).getChildAt(0), visible);
            }
        }
    }

    /** A 16:9 card with the app's banner; in the grid the name shows under it while focused. */
    private View tile(App app, int mode) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color != 0 ? app.color : 0xFF3A3A3A, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this); // must stay the first child, see bindArt
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (mode == MODE_GRID) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> {
            recordRecent(app.pkg);
            launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (mode == MODE_GRID ? 1.1f : 1.12f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (mode == MODE_GRID) {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            } else {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                if (app.color != 0) animateGlow(app.color);
                fadeGlow(1f);
            }
        });

        (mode == MODE_DOCK ? dockCards : mode == MODE_RECENT ? recentCards : gridCards).add(card);
        if (mode != MODE_GRID) bindArt(card, true); // grid art is loaded by updateGridArt
        return box;
    }

    /** Explicit D-pad targets: ends of rows don't wrap, and the rail and tabs link to the panel. */
    private void wireFocus() {
        int d = dockCards.size(), rc = recentCards.size(), g = gridCards.size();
        int home = railItems.get(0).getId();
        int tabFav = tabViews.get(PAGE_FAV).getId();
        int tabApps = tabViews.get(PAGE_APPS).getId();

        for (int i = 0; i < railItems.size(); i++) {
            View v = railItems.get(i);
            v.setNextFocusUpId(i == 0 ? v.getId() : railItems.get(i - 1).getId());
            v.setNextFocusDownId(i == railItems.size() - 1 ? v.getId() : railItems.get(i + 1).getId());
            v.setNextFocusLeftId(v.getId());
        }
        for (int i = 0; i < tabViews.size(); i++) {
            View v = tabViews.get(i);
            v.setNextFocusLeftId(i == 0 ? home : tabViews.get(i - 1).getId());
            v.setNextFocusRightId(i == tabViews.size() - 1 ? v.getId() : tabViews.get(i + 1).getId());
            v.setNextFocusUpId(v.getId());
        }
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(tabFav);
            c.setNextFocusLeftId(i == 0 ? home : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(rc > 0 ? recentCards.get(Math.min(i, rc - 1)).getId() : c.getId());
        }
        for (int i = 0; i < rc; i++) {
            View c = recentCards.get(i);
            c.setNextFocusUpId(d > 0 ? dockCards.get(Math.min(i, d - 1)).getId() : tabFav);
            c.setNextFocusLeftId(i == 0 ? home : recentCards.get(i - 1).getId());
            c.setNextFocusRightId(i == rc - 1 ? c.getId() : recentCards.get(i + 1).getId());
            c.setNextFocusDownId(c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0 ? tabApps : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? home : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
        updateNav();
    }

    /** Links that depend on the open page: rail to the current tab, tabs down to the first tile. */
    private void updateNav() {
        if (tabViews.isEmpty() || railItems.isEmpty()) return;
        int tab = tabViews.get(page == PAGE_LIVE ? PAGE_FAV : page).getId();
        for (View v : railItems) v.setNextFocusRightId(tab);
        View first = page == PAGE_APPS
                ? (gridCards.isEmpty() ? null : gridCards.get(0))
                : (dockCards.isEmpty() ? null : dockCards.get(0));
        for (TextView t : tabViews) t.setNextFocusDownId(first != null ? first.getId() : t.getId());
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int target = grid.getTop() + row.getTop() - (int) (appsScroll.getHeight() * 0.25f);
        int max = Math.max(0, grid.getTop() + grid.getHeight() - appsScroll.getHeight());
        appsScroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor, boolean side) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        if (side) pw.showAsDropDown(anchor, anchor.getWidth() + dp(12), -anchor.getHeight(), Gravity.START);
        else pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        String t = new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        clock.setText(is24 ? t : t + (h < 12 ? " AM" : " PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault())
                + "  \u00B7  " + locationName.toUpperCase(Locale.getDefault()));
        updatePrayer();
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
            }
        } catch (SecurityException ignored) { }
        if (railWifi != null) {
            railWifi.setTag(name);
            if (railWifi.hasFocus()) title.setText(name);
        }
    }

    // ---------------------------------------------------------------- weather

    private void scheduleWeather(long delay) {
        handler.removeCallbacks(refreshWeather);
        handler.postDelayed(refreshWeather, Math.max(1000, delay));
    }

    /** Current temperature and sky from Open-Meteo (no key), fetched off the main thread. */
    private void fetchWeather() {
        handler.removeCallbacks(refreshWeather);
        if (weatherBusy) return;
        weatherBusy = true;
        new Thread(() -> {
            String text = null;
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lng
                        + "&current=temperature_2m,weather_code&timezone=auto");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                    text = Math.round(cur.getDouble("temperature_2m")) + "\u00B0  "
                            + weatherLabel(cur.getInt("weather_code"));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            String result = text;
            runOnUiThread(() -> {
                weatherBusy = false;
                if (isDestroyed()) return;
                if (result != null) {
                    weather.setText(result.trim());
                    weatherAt = SystemClock.elapsedRealtime();
                    scheduleWeather(WEATHER_INTERVAL_MS);
                } else {
                    scheduleWeather(5 * 60 * 1000); // offline: try again in 5 minutes
                }
            });
        }).start();
    }

    private static String weatherLabel(int code) {
        if (code == 0) return "Cerah";
        if (code <= 2) return "Cerah berawan";
        if (code == 3) return "Berawan";
        if (code == 45 || code == 48) return "Berkabut";
        if (code >= 51 && code <= 57) return "Gerimis";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Hujan";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Salju";
        if (code >= 95) return "Badai petir";
        return "";
    }

    // ---------------------------------------------------------------- prayer times

    private void updatePrayer() {
        Calendar now = Calendar.getInstance();
        double tz = TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 3600000.0;
        int[] t = prayerMinutes(now, tz);
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int next = 0; // after Isya the next one is tomorrow's Subuh
        for (int i = 0; i < 5; i++) if (t[i] > cur) { next = i; break; }
        for (int i = 0; i < 5; i++) {
            prayerTime[i].setText(t[i] < 0 ? "--:--"
                    : String.format(Locale.US, "%02d:%02d", t[i] / 60 % 24, t[i] % 60));
            boolean on = i == next;
            prayerName[i].setTextColor(on ? Color.WHITE : 0x99FFFFFF);
            prayerTime[i].setTextColor(on ? Color.WHITE : 0xB3FFFFFF);
            prayerTime[i].setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    /**
     * Subuh, Dzuhur, Ashar, Maghrib, Isya as minutes after midnight (-1 if the sun never reaches
     * the angle). Standard solar-position method; Ashar uses the Shafi'i shadow factor.
     */
    private int[] prayerMinutes(Calendar day, double tz) {
        double jd = julian(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
                - lng / 360;
        double[] h = {
                midDay(jd, 5 / 24.0) - angleTime(jd, fajrAngle, 5 / 24.0),
                midDay(jd, 12 / 24.0),
                midDay(jd, 13 / 24.0) + asrTime(jd, 1, 13 / 24.0),
                midDay(jd, 18 / 24.0) + angleTime(jd, 0.833, 18 / 24.0),
                midDay(jd, 18 / 24.0) + angleTime(jd, ishaAngle, 18 / 24.0)};
        int[] m = new int[5];
        for (int i = 0; i < 5; i++) {
            double v = h[i] + tz - lng / 15;
            m[i] = Double.isNaN(v) ? -1 : (int) Math.round(fix(v, 24) * 60) + ihtiyat;
        }
        return m;
    }

    private static double fix(double a, double b) {
        a = a - b * Math.floor(a / b);
        return a < 0 ? a + b : a;
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at a Julian date. */
    private static double[] sunPosition(double jd) {
        double dd = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * dd, 360);
        double q = fix(280.459 + 0.98564736 * dd, 360);
        double l = fix(q + 1.915 * Math.sin(Math.toRadians(g)) + 0.020 * Math.sin(Math.toRadians(2 * g)), 360);
        double e = 23.439 - 0.00000036 * dd;
        double ra = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(e)) * Math.sin(Math.toRadians(l)),
                Math.cos(Math.toRadians(l)))) / 15;
        double decl = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(e)) * Math.sin(Math.toRadians(l))));
        return new double[]{decl, q / 15 - fix(ra, 24)};
    }

    private static double midDay(double jd, double t) {
        return fix(12 - sunPosition(jd + t)[1], 24);
    }

    /** Hours between solar noon and the sun reaching {@code angle} degrees below the horizon. */
    private double angleTime(double jd, double angle, double t) {
        double decl = Math.toRadians(sunPosition(jd + t)[0]);
        double la = Math.toRadians(lat);
        double x = (-Math.sin(Math.toRadians(angle)) - Math.sin(decl) * Math.sin(la)) / (Math.cos(decl) * Math.cos(la));
        return Math.toDegrees(Math.acos(x)) / 15;
    }

    private double asrTime(double jd, double factor, double t) {
        double decl = sunPosition(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (factor + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, angle, t);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.55f, 0.45f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Pick the next photo, then decode it off the main thread and crossfade it in. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        loadWallpaper(wallIndex);
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    /** Decode photo `index` no larger than the screen, plus a tiny blurred copy for the glass. */
    private void loadWallpaper(int index) {
        if (wallpapers.length == 0 || index < 0 || index >= wallpapers.length) return;
        final String name = "wallpapers/" + wallpapers[index];
        final int gen = ++wallGen;
        new Thread(() -> {
            Bitmap photo = decodeSampled(name, sw, sh);
            Bitmap blur = makeBlur(name);
            runOnUiThread(() -> showWallpaper(photo, blur, gen));
        }).start();
    }

    /** Largest power-of-two downsample that still keeps the image at least screen-sized. */
    private Bitmap decodeSampled(String name, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= reqW && o.outHeight / (s * 2) >= reqH) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            try (java.io.InputStream in = getAssets().open(name)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** About 100 px wide and box-blurred: the glass panels stretch it over the screen. */
    private Bitmap makeBlur(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= 96) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap small;
            try (java.io.InputStream in = getAssets().open(name)) {
                small = BitmapFactory.decodeStream(in, null, d);
            }
            if (small == null) return null;
            int w = small.getWidth(), h = small.getHeight();
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            small.recycle();
            for (int pass = 0; pass < 2; pass++) boxBlur(px, w, h, 3);
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        blurPass(px, tmp, w, h, r, true);
        blurPass(tmp, px, w, h, r, false);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = i + k;
                    if (j < 0 || j >= len) continue;
                    int p = horizontal ? src[l * w + j] : src[j * w + l];
                    rs += (p >> 16) & 255;
                    gs += (p >> 8) & 255;
                    bs += p & 255;
                    n++;
                }
                int o = horizontal ? l * w + i : i * w + l;
                dst[o] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    /** End a crossfade now: drop the old photo from the back layer and free its bitmap. */
    private void finishFade() {
        wallFront.animate().cancel();
        wallFront.setAlpha(1f);
        wallBack.setImageDrawable(null);
        recycle(fadeOld);
        fadeOld = null;
    }

    private void showWallpaper(Bitmap bm, Bitmap blur, int gen) {
        if (isDestroyed() || stopped || gen != wallGen || bm == null) { // stale or not visible
            recycle(bm);
            return;
        }
        finishFade();
        boolean first = curBm == null;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        fadeOld = curBm;
        curBm = bm;
        glass.set(blur, !first);
        wallFront.animate().alpha(1f).setDuration(first ? 600 : 2000).withEndAction(this::finishFade).start();
    }

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View[] parts = {railView, headerView, tabsView, panelView};
        for (int i = 0; i < parts.length; i++) {
            View v = parts[i];
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(80 + i * 70L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }


    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote opens the Inputs list.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && railInputs != null) {
            showInputs(railInputs, true);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** The blurred wallpaper the glass panels draw. A few dozen KB, swapped with the photo. */
    static final class Glass {
        Bitmap prev, cur;
        float mix = 1f;
        private ValueAnimator anim;
        final List<GlassView> views = new ArrayList<>();

        void set(Bitmap blur, boolean animate) {
            if (anim != null) anim.cancel();
            prev = cur;
            cur = blur;
            if (!animate || prev == null) {
                prev = null;
                mix = 1f;
                invalidateAll();
                return;
            }
            mix = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2000);
            anim.addUpdateListener(a -> {
                mix = (float) a.getAnimatedValue();
                if (mix >= 1f) prev = null;
                invalidateAll();
            });
            anim.start();
        }

        void clear() {
            if (anim != null) anim.cancel();
            prev = null;
            cur = null;
            mix = 1f;
            invalidateAll();
        }

        void invalidateAll() {
            for (GlassView v : views) v.invalidate();
        }
    }

    /**
     * A rounded "glass" surface: the blurred wallpaper stretched over the whole screen and cut out
     * at this view's place, plus a light tint and a thin edge. Cheap: no real-time blur, no layer.
     */
    static final class GlassView extends View {
        private final Glass glass;
        private final float radius;
        private final int sw, sh;
        private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final int[] loc = new int[2];

        GlassView(Context c, Glass glass, float radiusPx, int sw, int sh) {
            super(c);
            this.glass = glass;
            this.radius = radiusPx;
            this.sw = sw;
            this.sh = sh;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, radiusPx / 30f));
            edge.setColor(0x4DFFFFFF);
            glass.views.add(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationInWindow(loc);
            c.save();
            c.clipPath(clip);
            fill.setColor(0xFF1A1A1C); // shows while there is no photo yet
            c.drawRect(rect, fill);
            drawPhoto(c, glass.prev, 255);
            drawPhoto(c, glass.cur, glass.prev == null ? 255 : Math.round(255 * glass.mix));
            fill.setColor(0x40000000); // darken a little so white text stays readable
            c.drawRect(rect, fill);
            fill.setColor(0x24FFFFFF); // then the frosted lightening
            c.drawRect(rect, fill);
            c.restore();
            float inset = edge.getStrokeWidth() / 2f;
            c.drawRoundRect(inset, inset, rect.right - inset, rect.bottom - inset, radius, radius, edge);
        }

        private void drawPhoto(Canvas c, Bitmap bm, int alpha) {
            if (bm == null || alpha <= 0) return;
            float s = Math.max((float) sw / bm.getWidth(), (float) sh / bm.getHeight());
            float dx = (sw - bm.getWidth() * s) / 2f, dy = (sh - bm.getHeight() * s) / 2f;
            photo.setAlpha(alpha);
            c.save();
            c.translate(-loc[0] + dx, -loc[1] + dy);
            c.scale(s, s);
            c.drawBitmap(bm, 0, 0, photo);
            c.restore();
        }
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}