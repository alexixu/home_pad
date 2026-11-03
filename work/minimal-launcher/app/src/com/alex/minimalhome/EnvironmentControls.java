package com.alex.minimalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only environment data, independent from the user's real device control queue. */
public final class EnvironmentControls {
    private static final String PREFERENCES_NAME = "environment_sources";
    private static final String TEMPERATURE = "temperature_entity";
    private static final String HUMIDITY = "humidity_entity";
    private static final String WEATHER = "weather_entity";
    private static final long REFRESH_MS = 60000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final class State {
        public final Double temperatureC;
        public final Double humidity;
        public final Double outdoorTemperatureC;
        /** Home Assistant's original weather condition, or an empty string when unavailable. */
        public final String condition;
        public final String temperatureSource;
        public final String humiditySource;
        public final String weatherSource;
        public final String weatherAttribution;
        public final String indoorStatus;
        public final String weatherStatus;
        /** HA record timestamps, never presented as proof of a physical sensor sampling time. */
        public final String sensorReportedAt;
        public final String weatherReportedAt;
        public final boolean busy;

        State(Double temperatureC, Double humidity, Double outdoorTemperatureC, String condition,
                String temperatureSource, String humiditySource, String weatherSource,
                String weatherAttribution, String indoorStatus, String weatherStatus,
                String sensorReportedAt, String weatherReportedAt, boolean busy) {
            this.temperatureC = temperatureC;
            this.humidity = humidity;
            this.outdoorTemperatureC = outdoorTemperatureC;
            this.condition = condition;
            this.temperatureSource = temperatureSource;
            this.humiditySource = humiditySource;
            this.weatherSource = weatherSource;
            this.weatherAttribution = weatherAttribution;
            this.indoorStatus = indoorStatus;
            this.weatherStatus = weatherStatus;
            this.sensorReportedAt = sensorReportedAt;
            this.weatherReportedAt = weatherReportedAt;
            this.busy = busy;
        }
    }

    public interface StateListener { void onStateChanged(State state); }

    private final Activity activity;
    private final Context appContext;
    private final SharedPreferences preferences;
    private final SharedPreferences connectionPreferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable periodicRefresh = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener connectionChanged = (prefs, key) -> {
        if ("base_url".equals(key) || "entity_id".equals(key) || "token".equals(key)
                || "token_iv".equals(key) || key == null) credentialsChanged();
    };
    private StateListener listener;
    private State state;
    private ReadTask task;
    private Future<?> future;
    private AlertDialog settingsDialog;
    private boolean active;
    private boolean destroyed;
    private boolean networkAvailable;
    private int generation;

    public EnvironmentControls(Activity activity) {
        this.activity = activity;
        appContext = activity.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        connectionPreferences = appContext.getSharedPreferences(HomeControls.PREFERENCES_NAME, Context.MODE_PRIVATE);
        state = emptyState(Sources.read(preferences), "等待连接", false);
    }

    public State getState() { return state; }

    public void dismissSettingsDialog() {
        if (settingsDialog != null) settingsDialog.dismiss();
    }

    public void setStateListener(StateListener next) {
        listener = next;
        if (listener != null) listener.onStateChanged(state);
    }

    public void start() {
        if (active || destroyed) return;
        active = true;
        connectionPreferences.registerOnSharedPreferenceChangeListener(connectionChanged);
        networkAvailable = HomeControls.environmentNetworkAvailable(appContext);
        refresh();
    }

    public void stop() {
        active = false;
        connectionPreferences.unregisterOnSharedPreferenceChangeListener(connectionChanged);
        cancel();
        MAIN.removeCallbacks(periodicRefresh);
        if (settingsDialog != null) settingsDialog.dismiss();
        publish(emptyState(Sources.read(preferences), "等待连接", false));
    }

    public void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        listener = null;
        worker.shutdownNow();
    }

    /** Feed the existing HomeControls network observer; this does not control any device. */
    public void setNetworkAvailable(boolean available) {
        if (destroyed || networkAvailable == available) return;
        networkAvailable = available;
        cancel();
        MAIN.removeCallbacks(periodicRefresh);
        publish(emptyState(Sources.read(preferences), available ? "等待连接" : "网络未连接", false));
        if (active && available) refresh();
    }

    private void credentialsChanged() {
        if (!active || destroyed) return;
        cancel();
        MAIN.removeCallbacks(periodicRefresh);
        publish(emptyState(Sources.read(preferences), "连接配置已更新", false));
        // One preference commit may notify several keys. Coalesce those notifications on the main loop.
        MAIN.post(periodicRefresh);
    }

    public void refresh() {
        MAIN.removeCallbacks(periodicRefresh);
        if (!active || destroyed || task != null) return;
        Sources sources = Sources.read(preferences);
        if (!sources.anyConfigured()) {
            publish(emptyState(sources, "尚未接入", false));
            return;
        }
        networkAvailable = HomeControls.environmentNetworkAvailable(appContext);
        if (!networkAvailable) {
            publish(emptyState(sources, "网络未连接", false));
            MAIN.postDelayed(periodicRefresh, REFRESH_MS);
            return;
        }
        publish(emptyState(sources, "正在读取…", true));
        task = new ReadTask(this, appContext, sources, ++generation);
        future = worker.submit(task);
    }

    private void cancel() {
        generation++;
        if (task != null) task.cancel();
        if (future != null) future.cancel(true);
        task = null;
        future = null;
    }

    private void accept(int resultGeneration, State result) {
        if (destroyed || !active || generation != resultGeneration) return;
        boolean connectionUnchanged = task == null
                || HomeControls.environmentConnectionUnchanged(appContext, task.request);
        task = null;
        future = null;
        if (!connectionUnchanged) {
            publish(emptyState(Sources.read(preferences), "连接配置已更新", false));
            MAIN.post(periodicRefresh);
            return;
        }
        networkAvailable = HomeControls.environmentNetworkAvailable(appContext);
        publish(networkAvailable ? result : emptyState(Sources.read(preferences), "网络未连接", false));
        MAIN.removeCallbacks(periodicRefresh);
        MAIN.postDelayed(periodicRefresh, REFRESH_MS);
    }

    private void publish(State next) {
        state = next;
        if (listener != null) listener.onStateChanged(next);
    }

    private static State emptyState(Sources sources, String status, boolean busy) {
        return new State(null, null, null, "", "", "", "", "",
                sources.temperature.isEmpty() && sources.humidity.isEmpty() ? "温湿度待接入" : status,
                sources.weather.isEmpty() ? "天气待接入" : status, "", "", busy);
    }

    /** Explicit settings only. These source IDs are separate from encrypted connection credentials. */
    public void showSettingsDialog() {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()
                || settingsDialog != null && settingsDialog.isShowing()) return;
        Sources existing = Sources.read(preferences);
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), dp(12));
        EditText temperature = field(fields, "室内温度实体", "sensor.living_room_temperature", existing.temperature);
        EditText humidity = field(fields, "室内湿度实体", "sensor.living_room_humidity", existing.humidity);
        EditText weather = field(fields, "室外天气实体", "weather.home", existing.weather);
        TextView message = new TextView(activity);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        message.setTextColor(0xFF97744C);
        message.setPadding(0, dp(14), 0, 0);
        message.setText("只读取已连接的 Home Assistant 实体。温湿度请选择同一房间的环境传感器；留空移除对应来源。不会操作设备或修改家居授权。");
        fields.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("环境数据来源")
                .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        settingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (settingsDialog == dialog) settingsDialog = null; });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = temperature.getText().toString().trim();
            String h = humidity.getText().toString().trim();
            String w = weather.getText().toString().trim();
            if (!validEntity(t, "sensor") || !validEntity(h, "sensor") || !validEntity(w, "weather")) {
                message.setText("温度和湿度实体须以 sensor. 开头，天气须以 weather. 开头；仅支持小写字母、数字和下划线。");
                return;
            }
            cancel();
            MAIN.removeCallbacks(periodicRefresh);
            preferences.edit().putString(TEMPERATURE, t).putString(HUMIDITY, h).putString(WEATHER, w).apply();
            publish(emptyState(Sources.read(preferences), "等待连接", false));
            dialog.dismiss();
            refresh();
        }));
        dialog.show();
    }

    private EditText field(LinearLayout parent, String label, String hint, String value) {
        TextView caption = new TextView(activity);
        caption.setText(label);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        caption.setTextColor(0xFF6C4E31);
        caption.setPadding(0, dp(13), 0, dp(4));
        parent.addView(caption);
        EditText edit = new EditText(activity);
        edit.setSingleLine(true);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHint(hint);
        edit.setText(value);
        parent.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return edit;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private static boolean validEntity(String entity, String domain) {
        return entity.isEmpty() || entity.length() <= 255 && entity.matches(domain + "\\.[a-z0-9_]+");
    }

    private static final class Sources {
        final String temperature, humidity, weather;
        Sources(String temperature, String humidity, String weather) {
            this.temperature = temperature; this.humidity = humidity; this.weather = weather;
        }
        static Sources read(SharedPreferences preferences) {
            return new Sources(preferences.getString(TEMPERATURE, ""), preferences.getString(HUMIDITY, ""),
                    preferences.getString(WEATHER, ""));
        }
        boolean anyConfigured() { return !temperature.isEmpty() || !humidity.isEmpty() || !weather.isEmpty(); }
    }

    private static final class Reading {
        Double value;
        String source = "", reportedAt = "", condition = "", attribution = "", error = "";
    }

    private static final class ReadTask implements Runnable {
        final WeakReference<EnvironmentControls> owner;
        final Context application;
        final Sources sources;
        final int generation;
        final HomeControls.EnvironmentRead request = new HomeControls.EnvironmentRead();
        private volatile boolean cancelled;
        ReadTask(EnvironmentControls owner, Context application, Sources sources, int generation) {
            this.owner = new WeakReference<>(owner);
            this.application = application;
            this.sources = sources;
            this.generation = generation;
        }
        void cancel() { cancelled = true; request.cancel(); }
        @Override public void run() {
            Reading temperature = read(sources.temperature, 0);
            if (cancelled || Thread.currentThread().isInterrupted()) return;
            Reading humidity = read(sources.humidity, 1);
            if (cancelled || Thread.currentThread().isInterrupted()) return;
            Reading weather = read(sources.weather, 2);
            if (cancelled || Thread.currentThread().isInterrupted()) return;
            String sensorTime = oldest(temperature.reportedAt, humidity.reportedAt);
            String indoorStatus;
            if (temperature.value == null && humidity.value == null) {
                if (sources.temperature.isEmpty() && sources.humidity.isEmpty()) indoorStatus = "温湿度待接入";
                else indoorStatus = !temperature.error.isEmpty() ? temperature.error : humidity.error;
            } else if (temperature.value == null) indoorStatus = sources.temperature.isEmpty() ? "温度待接入" : "温度不可用";
            else if (humidity.value == null) indoorStatus = sources.humidity.isEmpty() ? "湿度待接入" : "湿度不可用";
            else indoorStatus = ageStatus(sensorTime, 24 * 60 * 60 * 1000L);
            String weatherStatus = sources.weather.isEmpty() ? "天气待接入"
                    : weather.value == null ? weather.error : ageStatus(weather.reportedAt, 3 * 60 * 60 * 1000L);
            State result = new State(temperature.value, humidity.value, weather.value, weather.condition,
                    temperature.source, humidity.source, weather.source, weather.attribution,
                    indoorStatus, weatherStatus, sensorTime, weather.reportedAt, false);
            WeakReference<EnvironmentControls> reference = owner;
            int requestedGeneration = generation;
            MAIN.post(() -> {
                EnvironmentControls controls = reference.get();
                if (controls != null) controls.accept(requestedGeneration, result);
            });
        }

        private Reading read(String entity, int kind) {
            Reading reading = new Reading();
            if (entity.isEmpty()) return reading;
            try {
                request.check();
                JSONObject json = HomeControls.readEnvironmentEntity(application, entity, request);
                JSONObject attrs = json.optJSONObject("attributes");
                if (attrs == null) throw new IllegalArgumentException();
                String rawState = json.optString("state", "");
                reading.source = clean(attrs.optString("friendly_name", kind == 2 ? "天气" : "温湿度计"), 48);
                if (rawState.isEmpty() || "unknown".equals(rawState) || "unavailable".equals(rawState)) {
                    reading.error = "unavailable".equals(rawState) ? "来源当前不可用" : "等待来源数据";
                    return reading;
                }
                if (kind == 2) {
                    reading.value = temperature(attrs.opt("temperature"), attrs.optString("temperature_unit", ""));
                    reading.condition = clean(rawState, 48);
                    reading.attribution = clean(attrs.optString("attribution", ""), 180);
                } else {
                    String expected = kind == 0 ? "temperature" : "humidity";
                    if (!expected.equals(attrs.optString("device_class", ""))) {
                        reading.error = "来源类型不匹配";
                        return reading;
                    }
                    String unit = attrs.optString("unit_of_measurement", "");
                    if (kind == 0) reading.value = temperature(rawState, unit);
                    else {
                        if (!"%".equals(unit)) throw new IllegalArgumentException();
                        reading.value = number(rawState);
                        if (reading.value < 0 || reading.value > 100) throw new IllegalArgumentException();
                    }
                }
                String reported = json.optString("last_reported", "");
                if (epoch(reported) == null) reported = json.optString("last_updated", "");
                reading.reportedAt = epoch(reported) == null ? "" : reported;
            } catch (HomeControls.EnvironmentReadException error) {
                reading.value = null; reading.condition = ""; reading.error = error.displayMessage;
            } catch (Exception ignored) {
                reading.value = null; reading.condition = ""; reading.error = "数值或单位无效";
            }
            return reading;
        }
    }

    private static Double number(Object raw) {
        if (raw == null || raw == JSONObject.NULL) throw new IllegalArgumentException();
        double number = Double.parseDouble(raw.toString());
        if (Double.isNaN(number) || Double.isInfinite(number)) throw new IllegalArgumentException();
        return number;
    }

    private static Double temperature(Object raw, String unit) {
        double value = number(raw);
        if ("°F".equals(unit)) value = (value - 32) * 5 / 9;
        else if (!"°C".equals(unit)) throw new IllegalArgumentException();
        if (value < -100 || value > 100) throw new IllegalArgumentException();
        return value;
    }

    private static String clean(String value, int maxLength) {
        String sanitized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private static Long epoch(String timestamp) {
        try { return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli(); }
        catch (DateTimeParseException | NullPointerException ignored) { return null; }
    }

    private static String oldest(String first, String second) {
        Long left = epoch(first), right = epoch(second);
        if (left == null) return right == null ? "" : second;
        if (right == null) return first;
        return left <= right ? first : second;
    }

    private static String ageStatus(String reportedAt, long threshold) {
        Long timestamp = epoch(reportedAt);
        if (timestamp == null) return "HA记录时间未知";
        long elapsed = System.currentTimeMillis() - timestamp;
        if (elapsed < -5 * 60 * 1000L) return "HA记录时间异常";
        if (elapsed > threshold) return "HA记录较早";
        return "已连接";
    }
}
