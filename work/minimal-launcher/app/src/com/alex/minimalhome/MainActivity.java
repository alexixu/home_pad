package com.alex.minimalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.Transliterator;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal home screen with optional, explicitly configured home controls. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFFF7EBD7;
    private static final int INK = 0xFF6C4E31;
    private static final int REQUEST_TERMUX_COMMAND = 1401;
    private static final String TERMUX_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private final ExecutorService indexWorker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> matches = new ArrayList<>();
    private final Collator collator = Collator.getInstance(Locale.getDefault());
    private FrameLayout root;
    private LinearLayout panel;
    private SearchField search;
    private ListView results;
    private TextView status;
    private View gap;
    private ResultAdapter adapter;
    private HomeControls homeControls;
    private HomeDevices homeDevices;
    private EnvironmentControls environmentControls;
    private WarmDashboard dashboard;
    private ControlCenter controlCenter;
    private VoiceControls voiceControls;
    private WakeWordControls wakeWords;
    private boolean resumed;
    private boolean windowFocused;
    private boolean launchingVoice;
    private int voiceLaunchGeneration;
    private final Runnable updateWake = this::syncWakeState;
    private AlertDialog overlayDialog;
    private boolean controlsOpen;
    private boolean searchOpen;
    private int imeBottom;
    private int safeTop;
    private int safeBottom;
    private int safeLeft;
    private int safeRight;
    private boolean indexLoading;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        root = new FrameLayout(this) {
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                post(MainActivity.this::updateLayout);
            }
        };
        root.setBackgroundColor(BACKGROUND);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setFocusableInTouchMode(true);
        root.setOnClickListener(v -> { if (searchOpen) resetSearch(); });
        homeControls = new HomeControls(this);
        homeDevices = new HomeDevices(this);
        environmentControls = new EnvironmentControls(this);
        dashboard = new WarmDashboard(this, this::openSearch, this::openVoice, this::openControlCenter);
        voiceControls = new VoiceControls(this, homeControls, environmentControls, homeDevices,
                this::scheduleWakeState);
        wakeWords = new WakeWordControls(this, this::openVoice, dashboard::setWakeStatus);
        root.addView(dashboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controlCenter = new ControlCenter(this, homeControls, homeDevices, environmentControls,
                this::resetSearch, this::showHomeSettings,
                () -> showEnvironmentDetails(false), () -> showEnvironmentDetails(true));
        root.addView(controlCenter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controlCenter.hide();
        homeDevices.setListener(() -> {
            if (controlCenter != null) controlCenter.refreshDevices();
            if (dashboard != null) dashboard.setDeviceCatalog(homeDevices.snapshot().toJson(""));
        });
        homeControls.setStateListener(controlState -> {
            dashboard.setState(controlState);
            environmentControls.setNetworkAvailable(controlState.networkAvailable);
            updateControlCenter();
            voiceControls.onStateChanged();
        });
        environmentControls.setStateListener(environmentState -> {
            dashboard.setEnvironmentState(environmentState);
            updateControlCenter();
            voiceControls.onStateChanged();
        });
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        root.addView(panel, new FrameLayout.LayoutParams(1, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        panel.setVisibility(View.GONE);

        search = new SearchField(this);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        search.setTextColor(INK);
        search.setHintTextColor(0xFFAA8D68);
        search.setHint("搜索应用");
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(22), 0, dp(22), 0);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setSelectAllOnFocus(false);
        search.setBackground(rounded(0xFFFFF9EE, 0xFFE4D3B6, 25));
        search.setElevation(dp(2));
        SearchIcon icon = new SearchIcon();
        icon.setBounds(0, 0, dp(23), dp(23));
        search.setCompoundDrawablesRelative(icon, null, null, null);
        search.setCompoundDrawablePadding(dp(14));
        panel.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        search.setOnFocusChangeListener((v, focused) -> updateLayout());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP && !matches.isEmpty()) {
                    launch(matches.get(0));
                }
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_SEARCH && event == null) {
                if (!matches.isEmpty()) launch(matches.get(0));
                return true;
            }
            return false;
        });

        gap = new View(this);
        gap.setVisibility(View.GONE);
        panel.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        results = new ListView(this);
        results.setDivider(null);
        results.setDividerHeight(0);
        results.setVerticalScrollBarEnabled(false);
        results.setBackground(rounded(0xFFFFF9EE, 0xFFE4D3B6, 22));
        results.setClipToOutline(true);
        results.setPadding(0, dp(7), 0, dp(7));
        results.setVisibility(View.GONE);
        adapter = new ResultAdapter();
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> launch(matches.get(position)));
        panel.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        status = new TextView(this);
        status.setTextColor(0xFF97744C);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        panel.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                safeTop = cutout.top;
                safeBottom = cutout.bottom;
                safeLeft = cutout.left;
                safeRight = cutout.right;
                imeBottom = insets.isVisible(WindowInsets.Type.ime())
                        ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
                updateLayout();
                return insets;
            });
        }
        root.requestFocus();
        hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // The runtime microphone permission sheet pauses/resumes this Activity.
        if (voiceControls == null || !voiceControls.isOpen()) resetSearch();
        refreshIndex();
        homeControls.start();
        homeDevices.start();
        environmentControls.start();
        dashboard.start();
        voiceControls.onHostResumed();
        scheduleWakeState();
    }

    @Override protected void onPause() {
        resumed = false;
        ++voiceLaunchGeneration;
        launchingVoice = false;
        if (wakeWords != null) wakeWords.stop();
        if (voiceControls != null) voiceControls.onHostPaused();
        if (controlCenter != null) controlCenter.hide();
        if (homeDevices != null) homeDevices.stop();
        super.onPause();
    }

    @Override protected void onStop() {
        dismissOverlays();
        environmentControls.stop();
        homeControls.stop();
        dashboard.stop();
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        resetSearch();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        windowFocused = focused;
        if (focused) hideSystemBars();
        scheduleWakeState();
    }

    @Override public void onBackPressed() { resetSearch(); }

    @Override protected void onDestroy() {
        destroyed = true;
        if (root != null) root.removeCallbacks(updateWake);
        if (wakeWords != null) wakeWords.destroy();
        indexWorker.shutdownNow();
        if (homeControls != null) homeControls.destroy();
        if (homeDevices != null) homeDevices.destroy();
        if (environmentControls != null) environmentControls.destroy();
        if (voiceControls != null) voiceControls.destroy();
        super.onDestroy();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void resetSearch() {
        if (search == null) return;
        ++voiceLaunchGeneration;
        launchingVoice = false;
        dismissOverlays();
        controlsOpen = false;
        if (controlCenter != null) controlCenter.hide();
        searchOpen = false;
        search.setText("");
        root.requestFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(search.getWindowToken(), 0);
        hideSystemBars();
        updateLayout();
    }

    private void openSearch() {
        resetSearch();
        searchOpen = true;
        panel.setVisibility(View.VISIBLE);
        dashboard.setVisibility(View.INVISIBLE);
        search.requestFocus();
        updateLayout();
        search.post(() -> {
            if (!searchOpen || destroyed) return;
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) input.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void openVoice() {
        resetSearch();
        final int ticket = ++voiceLaunchGeneration;
        launchingVoice = true;
        dashboard.setWakeStatus("正在进入语音…");
        // Release local wake capture before the ChatGPT WebRTC session takes the microphone.
        wakeWords.stopThen(released -> {
            if (destroyed || !resumed || voiceLaunchGeneration != ticket) return;
            launchingVoice = false;
            if (!released) dashboard.setWakeStatus("麦克风未能释放，请重新打开桌面后重试");
            else if (windowFocused) voiceControls.show();
            scheduleWakeState();
        });
    }

    private void scheduleWakeState() {
        if (root == null || destroyed) return;
        root.removeCallbacks(updateWake);
        root.post(updateWake);
    }

    private void syncWakeState() {
        if (wakeWords == null || destroyed) return;
        // stopThen owns this handoff. Repeated stop() would invalidate its generation token.
        if (launchingVoice) return;
        boolean homeVisible = resumed && windowFocused && !searchOpen && !controlsOpen
                && !launchingVoice && (voiceControls == null || !voiceControls.isOpen())
                && (overlayDialog == null || !overlayDialog.isShowing());
        if (homeVisible) wakeWords.start();
        else wakeWords.stop();
    }

    private void openControlCenter() {
        resetSearch();
        controlsOpen = true;
        updateControlCenter();
        controlCenter.show();
        updateLayout();
    }

    private void updateControlCenter() {
        if (controlCenter != null) {
            controlCenter.updateState(homeControls.getDashboardState(), environmentControls.getState());
        }
    }

    private void dismissOverlays() {
        if (wakeWords != null) wakeWords.stop();
        if (voiceControls != null) voiceControls.dismiss();
        if (overlayDialog != null) overlayDialog.dismiss();
        overlayDialog = null;
        if (homeControls != null) homeControls.dismissDialogs();
        if (environmentControls != null) environmentControls.dismissSettingsDialog();
    }

    private void showEnvironmentDetails(boolean outdoor) {
        if (!controlsOpen) return;
        dismissOverlays();
        overlayDialog = new AlertDialog.Builder(this)
                .setTitle(outdoor ? "室外天气来源" : "室内温湿度来源")
                .setMessage(dashboard.describeEnvironment(outdoor).replace("，", "\n")
                        + "\n\n记录时间来自 Home Assistant，不代表传感器实际采样时刻。")
                .setPositiveButton("关闭", null).create();
        overlayDialog.show();
        HomeStyle.dialog(overlayDialog);
    }

    private void showHomeSettings() {
        if (!controlsOpen) return;
        dismissOverlays();
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(20), dp(4), dp(20), dp(12));
        String[] names = {"家居连接", "温湿度与天气", "ChatGPT 语音", "默认桌面", "刷新全部状态", "本地服务"};
        String[] notes = {"管理 Home Assistant 连接", "选择室内传感器和室外天气来源", "配对语音服务，设置查询与控制权限", "选择平板按 Home 键后打开的应用", "更新设备与环境数据", "仅在本地服务未运行时手动启动"};
        for (int index = 0; index < names.length; index++) {
            final int selected = index;
            LinearLayout row = HomeStyle.settingsRow(this, names[index], notes[index], () -> {
                if (overlayDialog != null) overlayDialog.dismiss();
                overlayDialog = null;
                switch (selected) {
                    case 0: homeControls.showSettingsDialog(); return;
                    case 1: environmentControls.showSettingsDialog(); return;
                    case 2: voiceControls.showSettings(); return;
                    case 4: homeControls.refresh(); environmentControls.refresh(); homeDevices.refresh(ignored -> {}); return;
                    case 5: startTabletHomeService(); return;
                    default:
                        try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                        catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) params.topMargin = dp(8);
            menu.addView(row, params);
        }
        android.widget.ScrollView settingsScroll = new android.widget.ScrollView(this);
        settingsScroll.setVerticalScrollBarEnabled(false); settingsScroll.addView(menu);
        overlayDialog = new AlertDialog.Builder(this).setTitle("连接与桌面")
                .setView(settingsScroll).setNegativeButton("返回", null).create();
        overlayDialog.show();
        HomeStyle.dialog(overlayDialog);
    }

    private void startTabletHomeService() {
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "请先完成平板控制服务安装", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(TERMUX_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX_COMMAND_PERMISSION}, REQUEST_TERMUX_COMMAND);
            return;
        }
        Intent command = new Intent("com.termux.RUN_COMMAND")
                .setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"))
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/tablet-ha/start-tablet-ha")
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        try {
            // RunCommandService forwards the background command to Termux's foreground service.
            ComponentName started = startService(command);
            Toast.makeText(this, started == null
                    ? "请先完成平板控制服务安装" : "正在启动插座服务，稍后刷新", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "请先完成平板控制服务安装", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (voiceControls != null && voiceControls.onPermissionResult(requestCode, grantResults)) return;
        if (requestCode != REQUEST_TERMUX_COMMAND) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTabletHomeService();
        } else {
            Toast.makeText(this, "请在应用权限中允许极简桌面运行 Termux 命令", Toast.LENGTH_LONG).show();
        }
    }

    private void updateLayout() {
        scheduleWakeState();
        if (root == null || search == null || results == null || root.getWidth() == 0) return;
        int available = Math.max(dp(112), root.getHeight() - safeTop - Math.max(safeBottom, imeBottom));
        boolean active = searchOpen;
        panel.setVisibility(active ? View.VISIBLE : View.GONE);
        dashboard.setVisibility(active || controlsOpen ? View.INVISIBLE : View.VISIBLE);
        controlCenter.setVisibility(controlsOpen ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams dashboardFrame = (FrameLayout.LayoutParams) dashboard.getLayoutParams();
        if (dashboardFrame.leftMargin != safeLeft || dashboardFrame.topMargin != safeTop
                || dashboardFrame.rightMargin != safeRight || dashboardFrame.bottomMargin != safeBottom) {
            dashboardFrame.setMargins(safeLeft, safeTop, safeRight, safeBottom);
            dashboard.setLayoutParams(dashboardFrame);
        }
        FrameLayout.LayoutParams controlsFrame = (FrameLayout.LayoutParams) controlCenter.getLayoutParams();
        int controlsBottom = Math.max(safeBottom, imeBottom);
        if (controlsFrame.leftMargin != safeLeft || controlsFrame.topMargin != safeTop
                || controlsFrame.rightMargin != safeRight || controlsFrame.bottomMargin != controlsBottom) {
            controlsFrame.setMargins(safeLeft, safeTop, safeRight, controlsBottom);
            controlCenter.setLayoutParams(controlsFrame);
        }
        int top = active ? Math.max(dp(12), Math.min(dp(48), available / 12))
                : Math.max(dp(12), (int) ((available - dp(64)) * 0.42f));
        int width = Math.min(dp(640), Math.max(dp(160), root.getWidth() - safeLeft - safeRight - dp(48)));
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int absoluteTop = safeTop + top;
        if (frame.width != width || frame.topMargin != absoluteTop) {
            frame.width = width;
            frame.topMargin = absoluteTop;
            panel.setLayoutParams(frame);
        }
        int listHeight = Math.min(matches.size() * dp(58) + dp(14),
                Math.max(dp(48), available - top - dp(64) - dp(12) - dp(20)));
        ViewGroup.LayoutParams rows = results.getLayoutParams();
        if (rows.height != listHeight) {
            rows.height = listHeight;
            results.setLayoutParams(rows);
        }
    }

    private void refreshIndex() {
        if (indexLoading || destroyed) return;
        indexLoading = true;
        filterApps();
        indexWorker.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            PackageManager pm = getPackageManager();
            Map<String, AppEntry> indexed = new LinkedHashMap<>();
            Transliterator transliterator = null;
            try {
                transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII");
            } catch (RuntimeException ignored) {
                // Labels and package names remain searchable if the device lacks ICU data.
            }
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo resolved : pm.queryIntentActivities(query, 0)) {
                    if (Thread.currentThread().isInterrupted()) return;
                    ActivityInfo info = resolved.activityInfo;
                    if (info == null || info.applicationInfo == null || !info.enabled
                            || !info.applicationInfo.enabled || getPackageName().equals(info.packageName)
                            || indexed.containsKey(info.packageName)) continue;
                    try {
                        int enabled = pm.getApplicationEnabledSetting(info.packageName);
                        if (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) continue;
                        CharSequence loaded = resolved.loadLabel(pm);
                        String label = loaded == null ? info.packageName : loaded.toString().trim();
                        if (label.isEmpty()) label = info.packageName;
                        String romanized = transliterator == null ? label : transliterator.transliterate(label);
                        indexed.put(info.packageName, new AppEntry(label,
                                new ComponentName(info.packageName, info.name), romanized));
                    } catch (RuntimeException ignored) {
                        // An application may have been removed while its entry was being read.
                    }
                }
            } catch (RuntimeException ignored) {
                // Keep the previous usable index if PackageManager is temporarily unavailable.
            }
            List<AppEntry> loaded = new ArrayList<>(indexed.values());
            runOnUiThread(() -> {
                if (destroyed) return;
                indexLoading = false;
                if (!loaded.isEmpty() || apps.isEmpty()) {
                    apps.clear();
                    apps.addAll(loaded);
                }
                filterApps();
            });
        });
    }

    private void filterApps() {
        if (adapter == null) return;
        String query = fold(search.getText().toString()).trim();
        String compactQuery = compact(query);
        matches.clear();
        if (!query.isEmpty()) {
            for (AppEntry entry : apps) {
                if (entry.score(query, compactQuery) < Integer.MAX_VALUE) matches.add(entry);
            }
            Collections.sort(matches, (a, b) -> {
                int score = Integer.compare(a.score(query, compactQuery), b.score(query, compactQuery));
                return score != 0 ? score : collator.compare(a.label, b.label);
            });
        }
        adapter.notifyDataSetChanged();
        boolean searching = !query.isEmpty();
        gap.setVisibility(searching ? View.VISIBLE : View.GONE);
        results.setVisibility(searching && !matches.isEmpty() ? View.VISIBLE : View.GONE);
        status.setVisibility(searching && matches.isEmpty() ? View.VISIBLE : View.GONE);
        status.setText(indexLoading ? "正在读取应用…" : "没有找到应用，试试名称或拼音首字母");
        results.setSelection(0);
        updateLayout();
    }

    private void launch(AppEntry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            resetSearch();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "应用暂时无法打开", Toast.LENGTH_SHORT).show();
            refreshIndex();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static final class AppEntry {
        final String label;
        final ComponentName component;
        final String labelKey;
        final String packageKey;
        final String pinyin;
        final String initials;

        AppEntry(String label, ComponentName component, String romanized) {
            this.label = label;
            this.component = component;
            labelKey = fold(label);
            packageKey = component.getPackageName().toLowerCase(Locale.ROOT);
            String latin = fold(romanized);
            pinyin = compact(latin);
            StringBuilder letters = new StringBuilder();
            for (String word : latin.split("[^\\p{L}\\p{N}]+")) {
                if (!word.isEmpty()) letters.appendCodePoint(word.codePointAt(0));
            }
            initials = letters.toString();
        }

        int score(String query, String compactQuery) {
            if (labelKey.equals(query)) return 0;
            if (labelKey.startsWith(query)) return 10;
            if (!compactQuery.isEmpty()) {
                if (pinyin.equals(compactQuery)) return 12;
                if (initials.equals(compactQuery)) return 15;
            }
            if (labelKey.contains(query)) return 25;
            if (!compactQuery.isEmpty()) {
                if (pinyin.startsWith(compactQuery)) return 30;
                if (initials.startsWith(compactQuery)) return 35;
                if (pinyin.contains(compactQuery)) return 40;
            }
            if (packageKey.contains(query)) return 70;
            return Integer.MAX_VALUE;
        }
    }

    private final class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return matches.size(); }
        @Override public Object getItem(int position) { return matches.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView row;
            if (recycled instanceof TextView) {
                row = (TextView) recycled;
            } else {
                row = new TextView(MainActivity.this);
                row.setTextColor(INK);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(dp(24), 0, dp(24), 0);
                row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
                TypedValue selectable = new TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                    row.setBackgroundResource(selectable.resourceId);
                }
            }
            row.setText(matches.get(position).label);
            return row;
        }
    }

    private final class SearchField extends EditText {
        SearchField(Context context) { super(context); }
        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) resetSearch();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    private final class SearchIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SearchIcon() {
            paint.setColor(0xFFAA8D68);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.7f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            float width = getBounds().width();
            float height = getBounds().height();
            float x = getBounds().left;
            float y = getBounds().top;
            canvas.drawCircle(x + width * 0.43f, y + height * 0.43f, width * 0.28f, paint);
            canvas.drawLine(x + width * 0.64f, y + height * 0.64f,
                    x + width * 0.88f, y + height * 0.88f, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
