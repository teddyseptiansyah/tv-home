package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
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
 * Apple TV style home screen: the focused app's name top-left, a clock pill and icon buttons
 * top-right, a dock of pinned apps with the next row peeking in under it, and every other app in a
 * grid that scrolls up on Down. Also shows the city, weather (Open-Meteo, refreshed every 30 min)
 * and today's prayer times (computed on the device from the coordinates in config.xml).
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    static final int PEEK_DP = 22;     // how much of the first grid row shows under the dock
    static final int DOCK_GAP_DP = 22; // space between the dock band and that peeking row
    private TextView clock, date, title;

    // Location, weather and prayer times; settings live in res/values/config.xml.
    private String locationName;
    private double lat, lng;
    private int fajrAngle, ishaAngle, ihtiyat;
    private TextView weather;
    private final TextView[] prayerName = new TextView[5];
    private final TextView[] prayerTime = new TextView[5];
    private TextView prayerCountdown;
    private TextView locationView;
    private LinearLayout calendarCard;
    private long weatherAt;
    private boolean weatherBusy;
    static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000;
    private final Runnable refreshWeather = this::fetchWeather;
    private View wifiPill;
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
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(refreshWeather);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
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
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
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

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: one screen minus a thin strip, so the first grid row peeks in under the dock.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH - dp(PEEK_DP)));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(DOCK_GAP_DP)));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(24));
        band.setColor(0x4D000000);
        band.setStroke(dp(1), 0x1AFFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
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

        // Top-right: location + prayer countdown, clock, weather, icon buttons
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);
        rightCol.setClipChildren(false);

        LinearLayout locRow = new LinearLayout(this);
        locRow.setGravity(Gravity.CENTER_VERTICAL);
        locationView = text(10, "sans-serif-medium", 0xCCFFFFFF);
        locationView.setLetterSpacing(0.18f);
        locationView.setText(locationName.toUpperCase(Locale.getDefault()));
        locRow.addView(locationView);
        prayerCountdown = text(10, "sans-serif-medium", 0xCCFFFFFF);
        prayerCountdown.setLetterSpacing(0.12f);
        prayerCountdown.setPadding(dp(10), 0, 0, 0);
        locRow.addView(prayerCountdown);
        rightCol.addView(locRow);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);

        // Calendar card
        calendarCard = new LinearLayout(this);
        calendarCard.setOrientation(LinearLayout.VERTICAL);
        calendarCard.setGravity(Gravity.CENTER);
        calendarCard.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable calBg = new GradientDrawable();
        calBg.setCornerRadius(dp(12));
        calBg.setColor(0x66000000);
        calBg.setStroke(dp(1), 0x33FFFFFF);
        calendarCard.setBackground(calBg);
        TextView calDay = text(16, "sans-serif-medium", Color.WHITE);
        calDay.setText(String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)));
        TextView calMon = text(8, "sans-serif-medium", 0xB3FFFFFF);
        calMon.setLetterSpacing(0.18f);
        calMon.setText(new SimpleDateFormat("MMM", Locale.getDefault()).format(Calendar.getInstance().getTime()).toUpperCase(Locale.getDefault()));
        TextView calHijri = text(7, "sans-serif-medium", 0x99FFFFFF);
        calendarCard.addView(calDay);
        calendarCard.addView(calMon);
        calendarCard.addView(calHijri);
        right.addView(calendarCard, llp(-2, dp(46), 0, dp(8), dp(10), 0));

        LinearLayout weatherPill = new LinearLayout(this);
        weatherPill.setGravity(Gravity.CENTER);
        weatherPill.setPadding(dp(14), 0, dp(14), 0);
        weatherPill.setBackground(pillBackground());
        weather = text(12, "sans-serif-medium", Color.WHITE);
        weather.setIncludeFontPadding(false);
        weather.setText("--\u00B0");
        weatherPill.addView(weather);
        right.addView(weatherPill, llp(-2, dp(30), 0, dp(8), 0, 0));

        LinearLayout timePill = new LinearLayout(this);
        timePill.setGravity(Gravity.CENTER);
        timePill.setPadding(dp(14), 0, dp(14), 0);
        timePill.setBackground(pillBackground());
        clock = text(12, "sans-serif-medium", Color.WHITE);
        clock.setIncludeFontPadding(false);
        timePill.addView(clock);
        right.addView(timePill, llp(-2, dp(30), dp(10), dp(8), 0, 0));

        View inputs = pill("Inputs", R.drawable.ic_inputs);
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", R.drawable.ic_wifi);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settings = pill("Settings", R.drawable.ic_settings);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (View p : new View[]{inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), dp(8), 0, 0));
            pills.add(p);
        }
        rightCol.addView(right);
        header.addView(rightCol, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private GradientDrawable pillBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        return bg;
    }

    /** A round icon button; it widens to show its label while focused. */
    private View pill(String label, int iconRes) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(6), 0, dp(6), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xE6FFFFFF);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        p.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView text = text(11, "sans-serif-medium", 0xE6FFFFFF);
        text.setText(label);
        text.setSingleLine(true);
        text.setPadding(dp(8), 0, dp(6), 0);
        text.setVisibility(View.GONE);
        p.addView(text);

        GradientDrawable bg = pillBackground();
        p.setBackground(bg);

        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            text.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            text.setVisibility(has ? View.VISIBLE : View.GONE);
            icon.setColorFilter(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        p.setTag(text);
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }
        // Fallback: also query CATEGORY_LAUNCHER
        Intent q2 = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q2, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) {
            if (byPkg.containsKey(p)) {
                dock.add(byPkg.remove(p));
            } else {
                // Try to construct entry directly for dock apps
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    Intent li = pm.getLeanbackLaunchIntentForPackage(p);
                    if (li == null) li = pm.getLaunchIntentForPackage(p);
                    if (li != null && li.getComponent() != null) {
                        App a = new App();
                        a.pkg = p;
                        a.label = String.valueOf(pm.getApplicationLabel(ai));
                        a.component = li.getComponent();
                        Drawable banner = ai.loadBanner(pm);
                        if (banner == null) {
                            try {
                                ActivityInfo act = pm.getActivityInfo(li.getComponent(), 0);
                                banner = act.loadBanner(pm);
                            } catch (Exception ignored) {}
                        }
                        a.isBanner = banner != null;
                        a.art = banner != null ? banner : ai.loadIcon(pm);
                        a.color = sampleColor(p, a.art);
                        dock.add(a);
                        continue;
                    }
                } catch (Exception ignored) {}
            }
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        int dcount = dock.size();
        int dW = tileWidth();
        for (int i = 0; i < dcount; i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) tile.getLayoutParams();
            if (p == null) {
                p = new LinearLayout.LayoutParams(dW, -2);
            } else {
                p.width = dW;
                p.height = -2;
            }
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dcount - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }
        dockRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        if (inDock) {
            box.setLayoutParams(new LinearLayout.LayoutParams(tileWidth(), -2));
        }

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
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
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
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.14f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        if (inputsPopup != null) {
            inputsPopup.dismiss();
            inputsPopup = null;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(10), dp(10), dp(10));
        list.setClipChildren(false);
        list.setClipToPadding(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xCC1C1C1E);
        bg.setStroke(dp(1), 0x40FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(248), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo t : tim.getTvInputList()) {
                if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
            }
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
            r.setOnClickListener(v -> { pw.dismiss(); Object tag = v.getTag(); if (tag instanceof Runnable) ((Runnable) tag).run(); });
            LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(-1, dp(44));
            lpRow.setMargins(0, dp(2), 0, dp(2));
            list.addView(r, lpRow);
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(i == 0 ? rows.get(rows.size() - 1).getId() : rows.get(i - 1).getId());
            r.setNextFocusDownId(i == rows.size() - 1 ? rows.get(0).getId() : rows.get(i + 1).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setElevation(dp(28));
        pw.setClippingEnabled(false);
        pw.setOnDismissListener(() -> {
            inputsPopup = null;
            if (anchor != null) anchor.requestFocus();
        });
        inputsPopup = pw;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() - dp(248);
        int y = loc[1] + anchor.getHeight() + dp(10);
        pw.showAtLocation(getWindow().getDecorView(), Gravity.NO_GRAVITY, x, y);
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(14, "sans-serif-medium", 0xF2FFFFFF);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), 0, dp(18), 0);
        r.setFocusable(true);
        r.setSingleLine(true);
        r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            if (has) {
                bg.setColor(0xE6FFFFFF);
                r.setTextColor(0xFF000000);
                v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(140).start();
            } else {
                bg.setColor(Color.TRANSPARENT);
                r.setTextColor(0xF2FFFFFF);
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
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
        if (locationView != null) {
            locationView.setText(locationName.toUpperCase(Locale.getDefault()));
        }
        updatePrayer();
    }

    private void updateCalendarCard() {
        if (calendarCard == null) return;
        Calendar now = Calendar.getInstance();
        TextView day = null;
        TextView month = null;
        TextView hijri = null;
        // try to find children
        if (calendarCard.getChildCount() >= 3) {
            if (calendarCard.getChildAt(0) instanceof TextView) day = (TextView) calendarCard.getChildAt(0);
            if (calendarCard.getChildAt(1) instanceof TextView) month = (TextView) calendarCard.getChildAt(1);
            if (calendarCard.getChildAt(2) instanceof TextView) hijri = (TextView) calendarCard.getChildAt(2);
        }
        if (day != null) day.setText(String.valueOf(now.get(Calendar.DAY_OF_MONTH)));
        if (month != null) month.setText(new SimpleDateFormat("MMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        // simple approx hijri not available offline easily; show placeholder or leave
        if (hijri != null) hijri.setText("");
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                String s = info.getSSID();
                if (s != null) {
                    s = s.replace("\"", "");
                    if (!s.equals("<unknown ssid>") && !s.isEmpty()) {
                        name = s;
                        if (wifiPill != null && wifiPill.getTag() instanceof TextView) {
                            ((TextView) wifiPill.getTag()).setText(name);
                        }
                        return;
                    }
                }
            }
        } catch (SecurityException ignored) { }
        if (wifiPill != null && wifiPill.getTag() instanceof TextView) {
            ((TextView) wifiPill.getTag()).setText(name);
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
        int next = -1;
        for (int i = 0; i < 5; i++) {
            if (t[i] >= cur) { next = i; break; }
        }
        if (next == -1) {
            // after Isya, next is tomorrow's Subuh
            next = 0;
            // compute tomorrow Subuh offset roughly? keep simple: show Subuh next day
            int subuh = t[0];
            if (subuh < 0) {
                if (prayerCountdown != null) prayerCountdown.setText("");
            } else {
                int diff = (24 * 60 - cur) + subuh;
                if (prayerCountdown != null) {
                    int h2 = diff / 60;
                    int m2 = diff % 60;
                    prayerCountdown.setText(String.format(Locale.US, "SUBUH %02d:%02d", h2, m2));
                }
            }
        } else {
            int diff = t[next] - cur;
            if (prayerCountdown != null && diff >= 0) {
                int h2 = diff / 60;
                int m2 = diff % 60;
                String[] names = {"SUBUH", "DZUHUR", "ASHAR", "MAGHRIB", "ISYA"};
                prayerCountdown.setText(String.format(Locale.US, "%s -%02d:%02d", names[next], h2, m2));
            } else if (prayerCountdown != null) {
                prayerCountdown.setText("");
            }
        }
        for (int i = 0; i < 5; i++) {
            prayerTime[i].setText(t[i] < 0 ? "--:--"
                    : String.format(Locale.US, "%02d:%02d", t[i] / 60 % 24, t[i] % 60));
            boolean on = (next == i) || (next == 0 && i == 0 && cur > t[4] && t[4] >= 0); // approximate
            // better: if next == i and not wrapped case? simplified
            if (next >= 0 && !(next == 0 && cur > t[4] && t[4] >= 0 && i != 0)) {
                on = (i == next);
            } else if (next == 0 && cur > (t[4] >= 0 ? t[4] : 0) && t[4] >= 0) {
                on = (i == 0); // next is subuh tomorrow
            }
            prayerName[i].setTextColor(on ? Color.WHITE : 0x99FFFFFF);
            prayerTime[i].setTextColor(on ? Color.WHITE : 0xB3FFFFFF);
            prayerTime[i].setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        }
        updateCalendarCard();
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
        g.setGradientCenter(0.5f, 0.78f);
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

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

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
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
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

    /** Dock and grid tiles share one width, so the dock lines up with the grid columns. */
    private int tileWidth() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return (w - 2 * dp(68) - (cols - 1) * dp(20)) / cols;
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

    private boolean handleShortcut(KeyEvent e) {
        if (e == null) return false;
        int k = e.getKeyCode();
        // Menu key opens the Inputs dropdown
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!pills.isEmpty()) { showInputs(pills.get(0)); return true; }
        }
        // Settings shortcut button
        if (k == KeyEvent.KEYCODE_SETTINGS) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {}
        }
        // Channel / Guide / TV Input shortcuts -> open Inputs/source picker
        if (k == KeyEvent.KEYCODE_GUIDE || k == KeyEvent.KEYCODE_TV_INPUT
                || k == KeyEvent.KEYCODE_TV || k == KeyEvent.KEYCODE_CHANNEL_UP
                || k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (!pills.isEmpty()) { showInputs(pills.get(0)); return true; }
        }
        // Some TCL remotes use INFO or PROG keys for sources/settings
        if (k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_PROG_RED
                || k == KeyEvent.KEYCODE_PROG_GREEN || k == KeyEvent.KEYCODE_PROG_YELLOW
                || k == KeyEvent.KEYCODE_PROG_BLUE) {
            if (!pills.isEmpty()) { showInputs(pills.get(0)); return true; }
        }
        // Source/Input variants
        if (k == KeyEvent.KEYCODE_WINDOW) {
            if (!pills.isEmpty()) { showInputs(pills.get(0)); return true; }
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e != null && (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP)) {
            if (handleShortcut(e)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleShortcut(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (handleShortcut(event)) return true;
        return super.onKeyUp(keyCode, event);
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
