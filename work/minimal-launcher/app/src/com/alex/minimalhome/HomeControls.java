package com.alex.minimalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.security.NetworkSecurityPolicy;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * One real Home Assistant switch/light, with no placeholder device and no optimistic state.
 * All public instance methods must be called on the main thread.
 * Host: add getView(), call start()/stop() with the visible Activity lifecycle, destroy() once,
 * and expose showSettingsDialog() only in the user's settings menu.
 * The host must supply INTERNET, ACCESS_NETWORK_STATE and its scoped network-security configuration.
 */
public final class HomeControls {
    public static final String PREFERENCES_NAME = "home_controls";

    /** Display-only snapshot. Contains no server address, entity ID or authorization data. */
    public static final class DashboardState {
        public final String title;
        public final String status;
        public final boolean configured;
        public final boolean canControl;
        public final boolean on;
        public final boolean known;
        public final boolean busy;
        public final boolean networkAvailable;

        private DashboardState(String title, String status, boolean configured,
                boolean canControl, boolean on, boolean known, boolean busy,
                boolean networkAvailable) {
            this.title = title;
            this.status = status;
            this.configured = configured;
            this.canControl = canControl;
            this.on = on;
            this.known = known;
            this.busy = busy;
            this.networkAvailable = networkAvailable;
        }
    }

    public interface StateListener {
        void onStateChanged(DashboardState state);
    }

    /** Opaque, single-use voice intent bound to the connection selected when it was parsed. */
    public static final class VoicePowerRequest {
        private final Object owner;
        private final Config snapshot;
        private final boolean targetOn;
        private boolean consumed;

        private VoicePowerRequest(Object owner, Config snapshot, boolean targetOn) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.targetOn = targetOn;
        }
    }

    public enum VoicePowerResult { STARTED, ALREADY_IN_STATE, REJECTED }

    private static final String KEY_ALIAS = "com.alex.minimalhome.ha.token.v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REFRESH_MS = 30000L;
    private static final int TIMEOUT_MS = 7000;
    private final Activity activity;
    private final Context appContext;
    private final SharedPreferences preferences;
    private final ConnectivityManager connectivity;
    private final Map<Network, NetworkCapabilities> networks = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout view;
    private final TextView title;
    private final TextView stateLabel;
    private final Button control;
    private final Button refreshButton;
    private final Runnable periodicRefresh = this::refresh;
    private final Object voiceRequestOwner = new Object();
    private Config config;
    private Result lastResult;
    private RequestTask currentTask;
    private Future<?> currentFuture;
    private Future<?> saveFuture;
    private AlertDialog settingsDialog;
    private AlertDialog controlDialog;
    private Config controlDialogConfig;
    private Boolean controlDialogExpectedOn;
    private TextView settingsMessage;
    private EditText settingsToken;
    private int generation;
    private boolean active;
    private boolean destroyed;
    private boolean saving;
    private boolean networkAvailable;
    private ConnectivityManager.NetworkCallback networkCallback;
    private StateListener stateListener;
    private DashboardState dashboardState = new DashboardState(
            "插座", "尚未连接设备", false, false, false, false, false, false);

    public HomeControls(Activity activity) {
        this.activity = activity;
        appContext = activity.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        connectivity = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(22), dp(18), dp(22), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0xFFE5E6E5);
        view.setBackground(background);
        view.setVisibility(View.GONE);

        title = text(18, 0xFF242526);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.addView(title, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT));
        stateLabel = text(14, 0xFF777C80);
        stateLabel.setPadding(0, dp(6), 0, dp(12));
        view.addView(stateLabel, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        control = new Button(activity);
        control.setAllCaps(false);
        control.setText("等待状态");
        control.setEnabled(false);
        control.setOnClickListener(v -> showControlDialog());
        actions.addView(control, new LinearLayout.LayoutParams(0, dp(48), 1));
        refreshButton = new Button(activity);
        refreshButton.setAllCaps(false);
        refreshButton.setText("刷新");
        refreshButton.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams refreshLayout = new LinearLayout.LayoutParams(dp(90), dp(48));
        refreshLayout.leftMargin = dp(12);
        actions.addView(refreshButton, refreshLayout);
        view.addView(actions, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public View getView() { return view; }

    public boolean isConfigured() { return Config.read(preferences).isConfigured(); }

    /** Main-thread only. A newly attached observer immediately receives the current snapshot. */
    public void setStateListener(StateListener listener) {
        stateListener = listener;
        if (listener != null) listener.onStateChanged(dashboardState);
    }

    public DashboardState getDashboardState() { return dashboardState; }

    /** Bind an explicit parsed voice target before refreshing; never exposes or decrypts a token. */
    public VoicePowerRequest prepareVoicePower(boolean targetOn) {
        if (Looper.myLooper() != Looper.getMainLooper() || destroyed || !active || saving
                || activity.isFinishing() || activity.isDestroyed() || config == null) return null;
        Config snapshot = Config.read(preferences);
        if (!snapshot.isConfigured() || !sameEnvironmentConnection(config, snapshot)) return null;
        return new VoicePowerRequest(voiceRequestOwner, snapshot, targetOn);
    }

    /**
     * Main-thread only, after the voice flow has refreshed state. Voice commands do not ask
     * for touch confirmation, but remain fixed-target, single-use and bound to their original
     * connection. The worker performs its own GET before at most one non-retried service call.
     */
    public VoicePowerResult executeVoicePower(VoicePowerRequest request) {
        if (Looper.myLooper() != Looper.getMainLooper() || request == null
                || request.owner != voiceRequestOwner || request.consumed) return VoicePowerResult.REJECTED;
        request.consumed = true;
        if (destroyed || !active || saving || activity.isFinishing() || activity.isDestroyed()
                || !networkAvailable || !hasUsableNetwork(connectivity) || currentTask != null
                || config == null || !config.isConfigured()
                || !sameEnvironmentConnection(request.snapshot, config)
                || !sameEnvironmentConnection(request.snapshot, Config.read(preferences))
                || lastResult == null || !lastResult.known || !lastResult.canControl) {
            return VoicePowerResult.REJECTED;
        }
        if (lastResult.on == request.targetOn) return VoicePowerResult.ALREADY_IN_STATE;
        dismissControlDialog();
        beginRequest(request.targetOn);
        return currentTask != null && Boolean.valueOf(request.targetOn).equals(currentTask.targetOn)
                ? VoicePowerResult.STARTED : VoicePowerResult.REJECTED;
    }

    /** Displaying a control dialog never sends a power command. */
    public void showControlDialog() {
        showControlDialog((Boolean) null);
    }

    /** A fixed touch-initiated intent retains the user's on-screen confirmation. */
    public void showControlDialog(boolean targetOn) {
        showControlDialog(Boolean.valueOf(targetOn));
    }

    private void showControlDialog(Boolean requestedTargetOn) {
        if (destroyed || !active || activity.isFinishing() || activity.isDestroyed()) return;
        if (controlDialog != null && controlDialog.isShowing()) return;
        DashboardState displayed = createDashboardState();
        Config expectedConfig = config;
        boolean ready = displayed.canControl && displayed.known && expectedConfig != null
                && hasUsableNetwork(connectivity)
                && sameEnvironmentConnection(expectedConfig, Config.read(preferences));
        if (!ready) {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(displayed.title)
                    .setMessage("当前状态：" + displayed.status + "\n状态确认后才能切换电源。")
                    .setPositiveButton("刷新", (ignored, which) -> refresh())
                    .setNegativeButton("取消", null).create();
            attachControlDialog(dialog, null, null);
            dialog.show();
            return;
        }
        final boolean expectedOn = displayed.on;
        final boolean targetOn = requestedTargetOn != null ? requestedTargetOn : !expectedOn;
        if (targetOn == expectedOn) {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(displayed.title)
                    .setMessage("当前状态：" + (expectedOn ? "已开启" : "已关闭")
                            + "\n设备已经处于所需状态，无需切换电源。")
                    .setPositiveButton("知道了", null).create();
            attachControlDialog(dialog, null, null);
            dialog.show();
            return;
        }
        String action = targetOn ? "开启" : "关闭";
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("确认" + action + "“" + displayed.title + "”？")
                .setMessage("当前状态：" + (expectedOn ? "已开启" : "已关闭")
                        + "\n确认后将" + action + "设备电源。")
                .setPositiveButton("确认" + action, null)
                .setNegativeButton("取消", null).create();
        attachControlDialog(dialog, expectedConfig, expectedOn);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                // Keep the action the user reviewed. Never invert a later state on confirmation.
                if (controlDialog != dialog || !canConfirmControl(expectedConfig, expectedOn)) {
                    dismissControlDialog();
                    Toast.makeText(activity, "设备状态或连接已变化，请刷新后重新确认", Toast.LENGTH_LONG).show();
                    refresh();
                    return;
                }
                dismissControlDialog();
                beginRequest(targetOn);
            });
            updateControlDialog();
        });
        dialog.show();
    }

    private void attachControlDialog(AlertDialog dialog, Config expectedConfig, Boolean expectedOn) {
        controlDialog = dialog;
        controlDialogConfig = expectedConfig;
        controlDialogExpectedOn = expectedOn;
        dialog.setOnDismissListener(ignored -> {
            if (controlDialog == dialog) {
                controlDialog = null;
                controlDialogConfig = null;
                controlDialogExpectedOn = null;
            }
        });
    }

    /** Called when leaving this screen or returning Home; no confirmation survives navigation. */
    public void dismissControlDialog() {
        AlertDialog previous = controlDialog;
        controlDialog = null;
        controlDialogConfig = null;
        controlDialogExpectedOn = null;
        if (previous != null) previous.dismiss();
    }

    /** No device or connection dialog should survive navigation back to the display-only home. */
    public void dismissDialogs() {
        dismissControlDialog();
        if (settingsDialog != null) settingsDialog.dismiss();
    }

    private boolean sameControlState(Config expectedConfig, boolean expectedOn) {
        return !destroyed && active && !saving && networkAvailable && hasUsableNetwork(connectivity)
                && expectedConfig != null && config != null
                && sameEnvironmentConnection(expectedConfig, config)
                && sameEnvironmentConnection(expectedConfig, Config.read(preferences))
                && lastResult != null && lastResult.known && lastResult.canControl
                && lastResult.on == expectedOn;
    }

    private boolean canConfirmControl(Config expectedConfig, boolean expectedOn) {
        return currentTask == null && sameControlState(expectedConfig, expectedOn);
    }

    private void updateControlDialog() {
        AlertDialog dialog = controlDialog;
        if (dialog == null || !dialog.isShowing() || controlDialogExpectedOn == null) return;
        if (!sameControlState(controlDialogConfig, controlDialogExpectedOn)) {
            dismissControlDialog();
            if (active && !destroyed) {
                Toast.makeText(activity, "设备状态或连接已变化，请重新确认", Toast.LENGTH_LONG).show();
            }
            return;
        }
        // A scheduled GET may briefly disable confirmation, without closing a valid dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(currentTask == null);
    }

    public void start() {
        if (destroyed || active) return;
        active = true;
        watchNetworks();
        config = Config.read(preferences);
        lastResult = null;
        render();
        refresh();
    }

    public void stop() {
        dismissControlDialog();
        active = false;
        unwatchNetworks();
        cancelRequest();
        MAIN.removeCallbacks(periodicRefresh);
        if (settingsDialog != null) settingsDialog.dismiss();
        if (saveFuture != null) saveFuture.cancel(true);
        saveFuture = null;
        saving = false;
        lastResult = null;
        render();
    }

    public void destroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        stateListener = null;
        worker.shutdownNow();
    }

    private void watchNetworks() {
        networks.clear();
        if (connectivity == null) {
            networkAvailable = false;
            return;
        }
        try {
            networkAvailable = hasUsableNetwork(connectivity);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                private boolean current() {
                    return active && !destroyed && networkCallback == this;
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (!current()) return;
                    networks.put(network, caps);
                    networkChanged();
                }
                @Override public void onLost(Network network) {
                    if (!current()) return;
                    networks.remove(network);
                    networkChanged();
                }
            };
            // Observe LAN-only Wi-Fi as well as the default Internet route. VALIDATED would
            // incorrectly reject a usable local network when the Internet is unavailable.
            NetworkRequest request = new NetworkRequest.Builder().clearCapabilities()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build();
            connectivity.registerNetworkCallback(request, networkCallback, MAIN);
        } catch (RuntimeException ignored) {
            networkCallback = null;
            networkAvailable = hasUsableNetwork(connectivity);
        }
    }

    private void unwatchNetworks() {
        ConnectivityManager.NetworkCallback previous = networkCallback;
        networkCallback = null;
        if (previous != null && connectivity != null) {
            try { connectivity.unregisterNetworkCallback(previous); }
            catch (RuntimeException ignored) { }
        }
        networks.clear();
        networkAvailable = false;
    }

    private void networkChanged() {
        boolean available = false;
        for (NetworkCapabilities caps : networks.values()) {
            if (usableNetwork(caps)) { available = true; break; }
        }
        if (networkAvailable == available) return;
        networkAvailable = available;
        // Invalidate a response from the previous connection. In particular, localhost HA
        // may still return the plug's old state after the tablet loses Wi-Fi.
        if (currentTask != null) cancelRequest();
        lastResult = null;
        MAIN.removeCallbacks(periodicRefresh);
        render();
        if (available && !saving) refresh(); // GET only; never replay an interrupted command.
    }

    private static boolean usableNetwork(NetworkCapabilities caps) {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    private static boolean hasUsableNetwork(ConnectivityManager connectivity) {
        if (connectivity == null) return false;
        try {
            for (Network network : connectivity.getAllNetworks()) {
                if (usableNetwork(connectivity.getNetworkCapabilities(network))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** Package-local, read-only bridge. Keeps the original switch identity in the token AAD. */
    static JSONObject readEnvironmentEntity(Context context, String entity) throws EnvironmentReadException {
        return readEnvironmentEntity(context, entity, new EnvironmentRead());
    }

    static boolean environmentNetworkAvailable(Context context) {
        return hasUsableNetwork((ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE));
    }

    static final class EnvironmentReadException extends IOException {
        final String displayMessage;
        EnvironmentReadException(String message) { super(message); displayMessage = message; }
    }

    /** Owned by an environment worker, so cancellation never touches the switch request. */
    static final class EnvironmentRead {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private volatile Config original;
        void cancel() {
            cancelled = true;
            HttpURLConnection previous = connection;
            if (previous != null) previous.disconnect();
        }
        void check() throws EnvironmentReadException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new EnvironmentReadException("读取已取消");
            }
        }
        Config snapshot(SharedPreferences preferences) throws EnvironmentReadException {
            check();
            Config current = Config.read(preferences);
            if (original == null) original = current;
            if (!sameEnvironmentConnection(original, current)) {
                throw new EnvironmentReadException("连接配置已更新，请刷新");
            }
            return original;
        }
    }

    private static boolean sameEnvironmentConnection(Config original, Config current) {
        return original.baseUrl.equals(current.baseUrl) && original.entity.equals(current.entity)
                && original.encryptedToken.equals(current.encryptedToken) && original.iv.equals(current.iv);
    }

    static boolean environmentConnectionUnchanged(Context context, EnvironmentRead pending) {
        Config original = pending.original;
        return original == null || sameEnvironmentConnection(original, Config.read(context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)));
    }

    static JSONObject readEnvironmentEntity(Context context, String entity, EnvironmentRead pending)
            throws EnvironmentReadException {
        if (entity == null || entity.length() > 255 || !entity.matches("(?:sensor|weather)\\.[a-z0-9_]+")) {
            throw new EnvironmentReadException("来源实体无效");
        }
        Context application = context.getApplicationContext();
        SharedPreferences prefs = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        Config original = pending.snapshot(prefs);
        if (!original.isConfigured()) throw new EnvironmentReadException("请先配置家居连接");
        pending.check();
        if (!environmentNetworkAvailable(application)) throw new EnvironmentReadException("网络未连接");
        HttpURLConnection request = null;
        try {
            String base = validateBaseUrl(original.baseUrl);
            // Do not replace original.entity with the environment entity: it authenticates the ciphertext.
            String token;
            try { token = decryptToken(original); validateToken(token); }
            catch (Exception ignored) { throw new EnvironmentReadException("授权无法读取，请检查家居连接"); }
            request = (HttpURLConnection) new URL(base + "/api/states/" + entity).openConnection();
            pending.connection = request;
            pending.check();
            request.setConnectTimeout(TIMEOUT_MS);
            request.setReadTimeout(TIMEOUT_MS);
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setRequestMethod("GET");
            request.setRequestProperty("Authorization", "Bearer " + token);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("Cache-Control", "no-cache");
            request.setRequestProperty("Connection", "close");
            int status = request.getResponseCode();
            pending.check();
            if (status == 401 || status == 403) throw new EnvironmentReadException("授权失效，请检查家居连接");
            if (status == 404) throw new EnvironmentReadException("未找到来源实体");
            if (status >= 300 && status < 400) throw new EnvironmentReadException("连接地址发生重定向");
            if (status < 200 || status >= 300) throw new EnvironmentReadException("环境数据服务异常");
            JSONObject result;
            try (InputStream input = request.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    pending.check();
                    if (output.size() + count > 65536) throw new EnvironmentReadException("环境数据响应过大");
                    output.write(buffer, 0, count);
                }
                result = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
            if (!entity.equals(result.optString("entity_id", ""))) {
                throw new EnvironmentReadException("环境数据响应不匹配");
            }
            pending.check();
            if (!environmentNetworkAvailable(application)) throw new EnvironmentReadException("网络未连接");
            if (!sameEnvironmentConnection(original, Config.read(prefs))) {
                throw new EnvironmentReadException("连接配置已更新，请刷新");
            }
            return result;
        } catch (EnvironmentReadException error) {
            throw error;
        } catch (Exception ignored) {
            // Raw network / JSON / crypto errors can contain private values. Do not expose them.
            throw new EnvironmentReadException("无法读取环境数据");
        } finally {
            if (request != null) request.disconnect();
            if (pending.connection == request) pending.connection = null;
        }
    }

    /** Opaque saved authorization identity. The original entity is retained for AES-GCM AAD. */
    static final class DeviceConnection {
        private final Config original;
        private DeviceConnection(Config original) { this.original = original; }
    }

    static final class DeviceRequest {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        long expiresAtNanos = Long.MAX_VALUE;
        volatile boolean submissionStarted;
        void cancel() {
            cancelled = true;
            HttpURLConnection previous = connection;
            if (previous != null) previous.disconnect();
        }
        void check() throws HomeDevices.Failure {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new HomeDevices.Failure("authorization_expired");
            if (System.nanoTime() >= expiresAtNanos) throw new HomeDevices.Failure("spoken_intent_expired");
        }
    }

    static DeviceConnection captureDeviceConnection(Context context) throws HomeDevices.Failure {
        Config original = Config.read(context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
        if (!original.isConfigured()) throw new HomeDevices.Failure("not_ready");
        return new DeviceConnection(original);
    }

    /** Private-storage binding only; neither this marker nor any input is exposed in the catalog. */
    static String deviceCatalogBinding(Context context) throws HomeDevices.Failure {
        Config original = captureDeviceConnection(context).original;
        return HomeDevicePolicy.hash(original.baseUrl + "\n" + original.entity + "\n" + original.encryptedToken + "\n" + original.iv);
    }

    static boolean deviceConnectionUnchanged(Context context, DeviceConnection connection) {
        return connection != null && sameEnvironmentConnection(connection.original, Config.read(context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)));
    }

    static JSONArray readDeviceStates(Context context, DeviceConnection connection, DeviceRequest pending) throws HomeDevices.Failure {
        try { return new JSONArray(deviceApi(context, connection, pending, "/api/states", null, null, 4 * 1024 * 1024)); }
        catch (HomeDevices.Failure error) { throw error; }
        catch (Exception ignored) { throw new HomeDevices.Failure("request_failed"); }
    }

    static JSONArray readDeviceServices(Context context, DeviceConnection connection, DeviceRequest pending) throws HomeDevices.Failure {
        try { return new JSONArray(deviceApi(context, connection, pending, "/api/services", null, null, 4 * 1024 * 1024)); }
        catch (HomeDevices.Failure error) { throw error; }
        catch (Exception ignored) { throw new HomeDevices.Failure("request_failed"); }
    }

    /** Only the global temperature unit leaves this fixed read; all other HA config stays here. */
    static String readDeviceTemperatureUnit(Context context, DeviceConnection connection, DeviceRequest pending) throws HomeDevices.Failure {
        try {
            JSONObject config = new JSONObject(deviceApi(context, connection, pending, "/api/config", null, null, 262144));
            JSONObject units = config.optJSONObject("unit_system");
            String unit = units == null ? "" : units.optString("temperature", "");
            if (!unit.equals("°C") && !unit.equals("°F")) throw new HomeDevices.Failure("unit_unavailable");
            return unit;
        } catch (HomeDevices.Failure error) { throw error; }
        catch (Exception ignored) { throw new HomeDevices.Failure("request_failed"); }
    }

    static JSONObject readDeviceEntity(Context context, DeviceConnection connection, DeviceRequest pending, String entity) throws HomeDevices.Failure {
        if (!HomeDevicePolicy.validEntity(entity)) throw new HomeDevices.Failure("target_unavailable");
        try {
            JSONObject state = new JSONObject(deviceApi(context, connection, pending, "/api/states/" + entity, null, null, 65536));
            if (!entity.equals(state.optString("entity_id", ""))) throw new HomeDevices.Failure("target_unavailable");
            return state;
        } catch (HomeDevices.Failure error) { throw error; }
        catch (Exception ignored) { throw new HomeDevices.Failure("request_failed"); }
    }

    /** The package policy creates the only accepted service/payload pair; no URL or token getter exists. */
    static void submitDeviceAction(Context context, DeviceConnection connection, DeviceRequest pending,
            HomeDevicePolicy.Entity entity, HomeDevicePolicy.Capability capability, Object value,
            AtomicBoolean authorization) throws HomeDevices.Failure {
        if (!entity.controllable() || !HomeDevicePolicy.validEntity(entity.entityId)
                || !capability.service.matches("[a-z_]{1,48}")) throw new HomeDevices.Failure("unsupported_action");
        Object validated = capability.validate(value);
        deviceApi(context, connection, pending, "/api/services/" + entity.domain + "/" + capability.service,
                capability.payload(entity.entityId, validated), authorization, 1024 * 1024);
    }

    private static String deviceApi(Context context, DeviceConnection connection, DeviceRequest pending,
            String path, JSONObject body, AtomicBoolean authorization, int responseLimit) throws HomeDevices.Failure {
        Context application = context.getApplicationContext();
        HttpURLConnection request = null;
        try {
            pending.check();
            if (!deviceConnectionUnchanged(application, connection)) throw new HomeDevices.Failure("connection_changed");
            if (!environmentNetworkAvailable(application)) throw new HomeDevices.Failure("not_ready");
            String base = validateBaseUrl(connection.original.baseUrl);
            // A tablet-local catalog is never sent to the old Mac HA or a different host.
            URI uri = new URI(base);
            if (!("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())))
                throw new HomeDevices.Failure("local_connection_required");
            String token;
            try { token = decryptToken(connection.original); validateToken(token); }
            catch (Exception ignored) { throw new HomeDevices.Failure("not_ready"); }
            request = (HttpURLConnection) new URL(base + path).openConnection();
            pending.connection = request;
            request.setConnectTimeout(TIMEOUT_MS); request.setReadTimeout(TIMEOUT_MS);
            request.setInstanceFollowRedirects(false); request.setUseCaches(false);
            request.setRequestMethod(body == null ? "GET" : "POST");
            request.setRequestProperty("Authorization", "Bearer " + token);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("Cache-Control", "no-cache");
            request.setRequestProperty("Connection", "close");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                request.setDoOutput(true); request.setFixedLengthStreamingMode(bytes.length);
                request.setRequestProperty("Content-Type", "application/json");
                pending.check();
                if (authorization == null || !authorization.get()) throw new HomeDevices.Failure("authorization_expired");
                if (!deviceConnectionUnchanged(application, connection)) throw new HomeDevices.Failure("connection_changed");
                try (OutputStream output = request.getOutputStream()) {
                    pending.check();
                    if (!authorization.get()) throw new HomeDevices.Failure("authorization_expired");
                    if (!deviceConnectionUnchanged(application, connection)) throw new HomeDevices.Failure("connection_changed");
                    pending.submissionStarted = true;
                    output.write(bytes); output.flush();
                }
            }
            int status = request.getResponseCode(); pending.check();
            if (status < 200 || status >= 300) throw new HomeDevices.Failure(status == 404 ? "target_unavailable" : "request_failed");
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    pending.check();
                    if (output.size() + count > responseLimit) throw new HomeDevices.Failure("response_too_large");
                    output.write(buffer, 0, count);
                }
                pending.check();
                if (!deviceConnectionUnchanged(application, connection)) throw new HomeDevices.Failure("connection_changed");
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (HomeDevices.Failure error) { throw error; }
        catch (Exception ignored) { throw new HomeDevices.Failure("request_failed"); }
        finally {
            if (request != null) request.disconnect();
            if (pending.connection == request) pending.connection = null;
        }
    }

    /** Read-only, including the scheduled foreground refresh. Never retries a service call. */
    public void refresh() {
        MAIN.removeCallbacks(periodicRefresh);
        if (!active || destroyed || saving || currentTask != null) return;
        if (networkCallback == null) networkAvailable = hasUsableNetwork(connectivity);
        config = Config.read(preferences);
        if (!config.isConfigured()) {
            lastResult = null;
            render();
            return;
        }
        beginRequest(null);
    }

    private void beginRequest(Boolean targetOn) {
        if (!active || destroyed || saving || currentTask != null || config == null
                || !config.isConfigured()) return;
        MAIN.removeCallbacks(periodicRefresh);
        if (!networkAvailable || !hasUsableNetwork(connectivity)) {
            networkAvailable = false;
            lastResult = null;
            render();
            MAIN.postDelayed(periodicRefresh, REFRESH_MS);
            return;
        }
        currentTask = new RequestTask(this, config, ++generation, targetOn, connectivity);
        currentFuture = worker.submit(currentTask);
        render();
    }

    private void cancelRequest() {
        generation++;
        if (currentTask != null) currentTask.cancel();
        if (currentFuture != null) currentFuture.cancel(true);
        currentTask = null;
        currentFuture = null;
    }

    private void acceptResult(int requestGeneration, Result result) {
        if (destroyed || !active || requestGeneration != generation) return;
        if (!hasUsableNetwork(connectivity)) networkAvailable = false;
        String friendlyName = result.friendlyName.trim();
        if (config != null && !friendlyName.isEmpty()) {
            String identity = config.baseUrl + "\n" + config.entity;
            if (!identity.equals(preferences.getString("cached_name_identity", ""))
                    || !friendlyName.equals(preferences.getString("cached_name", ""))) {
                // Cache only device identity/display metadata, never its power state.
                preferences.edit().putString("cached_name_identity", identity)
                        .putString("cached_name", friendlyName).apply();
            }
        }
        currentTask = null;
        currentFuture = null;
        lastResult = networkAvailable ? result : null;
        render();
        MAIN.removeCallbacks(periodicRefresh);
        MAIN.postDelayed(periodicRefresh, REFRESH_MS);
    }

    private void render() {
        dashboardState = createDashboardState();
        updateControlDialog();
        if (config == null || !config.isConfigured()) {
            view.setVisibility(View.GONE);
            control.setEnabled(false);
            refreshButton.setEnabled(false);
            notifyStateListener();
            return;
        }
        view.setVisibility(View.VISIBLE);
        title.setText(dashboardState.title);
        stateLabel.setText(dashboardState.status);
        control.setEnabled(dashboardState.canControl);
        control.setText(dashboardState.known ? "查看并确认操作" : "等待状态");
        refreshButton.setEnabled(active && !saving && currentTask == null);
        notifyStateListener();
    }

    private DashboardState createDashboardState() {
        if (config == null || !config.isConfigured()) {
            return new DashboardState("插座", "尚未连接设备", false, false,
                    false, false, saving, networkAvailable);
        }
        String name = config.label;
        if (name.isEmpty() && (config.baseUrl + "\n" + config.entity)
                .equals(preferences.getString("cached_name_identity", ""))) {
            name = preferences.getString("cached_name", "");
        }
        if (name.isEmpty()) name = "插座";
        boolean busy = currentTask != null;
        String message;
        if (active && !networkAvailable) {
            message = "状态未知 · 平板网络未连接";
        } else if (saving) {
            message = "正在保存连接…";
        } else if (busy) {
            message = currentTask.targetOn == null ? "正在读取状态…" : "正在确认设备状态…";
        } else if (lastResult != null) {
            message = lastResult.message;
        } else {
            message = "状态未知";
        }
        boolean enabled = active && networkAvailable && !saving && !busy
                && lastResult != null && lastResult.canControl;
        boolean known = networkAvailable && lastResult != null && lastResult.known;
        return new DashboardState(name, message, true, enabled,
                known && lastResult.on, known, busy || saving, networkAvailable);
    }

    private void notifyStateListener() {
        if (stateListener != null) stateListener.onStateChanged(dashboardState);
    }

    /** Only opened by an explicit user action. The saved token is never revealed in the editor. */
    public void showSettingsDialog() {
        if (destroyed || activity.isFinishing() || activity.isDestroyed()) return;
        dismissControlDialog();
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        if (currentTask != null && currentTask.targetOn != null) {
            Toast.makeText(activity, "请等待本次操作确认", Toast.LENGTH_SHORT).show();
            return;
        }
        Config existing = Config.read(preferences);
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText url = field(fields, "Home Assistant 地址", "http://127.0.0.1:8123",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existing.baseUrl);
        EditText token = field(fields, "长期访问令牌", existing.hasToken() ? "已保存；留空保持原令牌" : "输入令牌",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        token.setSaveEnabled(false);
        token.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        settingsToken = token;
        EditText entity = field(fields, "设备实体 ID", "switch.your_plug", InputType.TYPE_CLASS_TEXT);
        entity.setText(existing.entity);
        EditText label = field(fields, "显示名称（可选）", "插座", InputType.TYPE_CLASS_TEXT);
        label.setText(existing.label);
        settingsMessage = text(13, 0xFF777C80);
        settingsMessage.setText("仅支持实际存在的 switch 或 light 设备。保存后只读取状态。通过平板本地服务控制设备，请保持平板连接 Wi-Fi；其他服务器请使用 HTTPS。");
        settingsMessage.setPadding(0, dp(12), 0, dp(8));
        fields.addView(settingsMessage, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(fields);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("连接家居设备")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (existing.isConfigured()) builder.setNeutralButton("移除控件", (dialog, which) -> clearConfiguration());
        AlertDialog dialog = builder.create();
        settingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            token.getText().clear();
            if (settingsDialog == dialog) {
                settingsDialog = null;
                settingsToken = null;
                settingsMessage = null;
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (saving) return;
            try {
                String base = validateBaseUrl(url.getText().toString());
                String id = validateEntity(entity.getText().toString());
                String secret = token.getText().toString().trim();
                if (secret.isEmpty() && !existing.hasToken()) throw new IllegalArgumentException("请输入访问令牌");
                if (!secret.isEmpty()) validateToken(secret);
                Config candidate = new Config(base, id, label.getText().toString().trim(), "", "");
                cancelRequest();
                MAIN.removeCallbacks(periodicRefresh);
                saving = true;
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                }
                settingsMessage.setText("正在加密保存…");
                token.getText().clear();
                render();
                int saveGeneration = ++generation;
                saveFuture = worker.submit(new SaveTask(this, appContext, candidate, existing, secret, saveGeneration));
            } catch (IllegalArgumentException e) {
                settingsMessage.setText(e.getMessage());
            }
        }));
        dialog.show();
    }

    private void acceptSave(int requestGeneration, boolean success) {
        if (destroyed || requestGeneration != generation) return;
        saving = false;
        saveFuture = null;
        if (success) {
            if (settingsDialog != null) settingsDialog.dismiss();
            config = Config.read(preferences);
            lastResult = null;
            render();
            refresh();
        } else {
            if (settingsDialog != null) {
                settingsDialog.setCancelable(true);
                settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                settingsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if (settingsDialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                    settingsDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                }
                if (settingsMessage != null) settingsMessage.setText("无法安全保存令牌，请重新输入后重试");
            }
            render();
        }
    }

    private void clearConfiguration() {
        cancelRequest();
        MAIN.removeCallbacks(periodicRefresh);
        preferences.edit().clear().apply();
        config = Config.read(preferences);
        lastResult = null;
        render();
    }

    private EditText field(LinearLayout parent, String caption, String hint, int inputType) {
        TextView heading = text(13, 0xFF777C80);
        heading.setText(caption);
        heading.setPadding(0, dp(14), 0, dp(4));
        parent.addView(heading, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setInputType(inputType);
        field.setHint(hint);
        parent.addView(field, fullWidth(dp(48)));
        return field;
    }

    private TextView text(int size, int color) {
        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private static String validateEntity(String value) {
        String entity = value.trim();
        if (!entity.matches("(?:switch|light)\\.[a-z0-9_]+")) {
            throw new IllegalArgumentException("实体 ID 必须以 switch. 或 light. 开头，且只能包含小写字母、数字和下划线");
        }
        return entity;
    }

    private static void validateToken(String token) {
        if (token.length() < 20 || token.length() > 16384 || !token.matches("[\\x21-\\x7E]+")) {
            throw new IllegalArgumentException("访问令牌格式不正确");
        }
    }

    private static String validateBaseUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("https".equals(scheme) || "http".equals(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IllegalArgumentException("请输入完整的 HTTP 或 HTTPS 地址，不要包含用户名、查询参数或片段");
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("请输入 Home Assistant 的根地址，不要包含 /api 等路径");
            }
            if ("http".equals(scheme) && !isExplicitLocalHost(host)) {
                throw new IllegalArgumentException("公网地址必须使用 HTTPS；HTTP 请使用局域网 IP 或回环地址");
            }
            if ("http".equals(scheme)
                    && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
                throw new IllegalArgumentException("当前版本 HTTP 仅支持 127.0.0.1 或 localhost；其他地址请使用 HTTPS");
            }
            return scheme + "://" + uri.getRawAuthority();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("服务器地址格式不正确");
        }
    }

    private static boolean isExplicitLocalHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value)) return true;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            String[] pieces = value.split("\\.");
            int[] octets = new int[4];
            try {
                for (int i = 0; i < 4; i++) {
                    if (pieces[i].length() > 1 && pieces[i].startsWith("0")) return false;
                    octets[i] = Integer.parseInt(pieces[i]);
                    if (octets[i] < 0 || octets[i] > 255) return false;
                }
            } catch (NumberFormatException e) { return false; }
            return octets[0] == 127 || octets[0] == 10
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168);
        }
        if (value.contains(":") && value.matches("[0-9a-f:]+")) {
            try {
                InetAddress address = InetAddress.getByName(value); // Numeric IPv6 only; no DNS lookup.
                byte[] bytes = address.getAddress();
                return address.isLoopbackAddress() || (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC);
            } catch (IOException ignored) { return false; }
        }
        return false;
    }

    private static final class Config {
        final String baseUrl;
        final String entity;
        final String label;
        final String encryptedToken;
        final String iv;
        Config(String baseUrl, String entity, String label, String encryptedToken, String iv) {
            this.baseUrl = baseUrl;
            this.entity = entity;
            this.label = label;
            this.encryptedToken = encryptedToken;
            this.iv = iv;
        }
        static Config read(SharedPreferences p) {
            return new Config(p.getString("base_url", ""), p.getString("entity_id", ""),
                    p.getString("label", ""), p.getString("token", ""), p.getString("token_iv", ""));
        }
        boolean hasToken() { return !encryptedToken.isEmpty() && !iv.isEmpty(); }
        boolean isConfigured() { return !baseUrl.isEmpty() && !entity.isEmpty() && hasToken(); }
        byte[] associatedData() { return ("home-controls-v1\n" + baseUrl + "\n" + entity).getBytes(StandardCharsets.UTF_8); }
    }

    private static final class Result {
        final boolean known;
        final boolean on;
        final boolean canControl;
        final String message;
        final String friendlyName;
        Result(boolean known, boolean on, boolean canControl, String message, String friendlyName) {
            this.known = known;
            this.on = on;
            this.canControl = canControl;
            this.message = message;
            this.friendlyName = friendlyName;
        }
        static Result error(String message) { return new Result(false, false, false, message, ""); }
        Result unconfirmed(String explanation) {
            return new Result(known, on, false,
                    known ? (on ? "已开启" : "已关闭") + " · " + explanation : explanation, friendlyName);
        }
    }

    /** Static workers hold no Activity/View references, and callbacks only retain a weak owner. */
    private static final class RequestTask implements Runnable {
        final WeakReference<HomeControls> owner;
        final Config config;
        final int generation;
        final Boolean targetOn;
        final ConnectivityManager connectivity;
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        RequestTask(HomeControls owner, Config config, int generation, Boolean targetOn,
                ConnectivityManager connectivity) {
            this.owner = new WeakReference<>(owner);
            this.config = config;
            this.generation = generation;
            this.targetOn = targetOn;
            this.connectivity = connectivity;
        }
        void cancel() {
            cancelled = true;
            HttpURLConnection activeConnection = connection;
            if (activeConnection != null) activeConnection.disconnect();
        }
        private void checkCancelled() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        }
        @Override public void run() {
            Result result;
            try {
                checkCancelled();
                validateBaseUrl(config.baseUrl);
                validateEntity(config.entity);
                String token;
                try {
                    token = decryptToken(config);
                    validateToken(token);
                } catch (Exception e) {
                    publish(Result.error("令牌无法读取，请重新设置"));
                    return;
                }
                Result before = readState(token);
                if (targetOn == null || !before.canControl || before.on == targetOn.booleanValue()) {
                    result = before;
                } else {
                    checkCancelled();
                    boolean serviceAccepted = false;
                    String serviceError = "无法连接控制接口，请刷新";
                    try {
                        String domain = config.entity.substring(0, config.entity.indexOf('.'));
                        JSONObject payload = new JSONObject().put("entity_id", config.entity);
                        request(token, "/api/services/" + domain + (targetOn ? "/turn_on" : "/turn_off"),
                                payload.toString());
                        serviceAccepted = true;
                    } catch (HttpFailure e) {
                        serviceError = e.userMessage();
                    } catch (Exception ignored) {
                        // The command may already have reached the device. Never retry it.
                    }
                    checkCancelled();
                    // A successful HTTP service response is not evidence of the device's state.
                    Result after = readState(token);
                    if (!after.canControl) result = after;
                    else if (!serviceAccepted) result = after.unconfirmed(serviceError);
                    else if (after.on != targetOn.booleanValue()) result = after.unconfirmed("尚未确认目标状态，请刷新");
                    else result = after;
                }
            } catch (HttpFailure e) {
                result = Result.error(e.userMessage());
            } catch (IllegalArgumentException e) {
                result = Result.error("配置无效，请重新设置");
            } catch (Exception e) {
                result = Result.error("无法连接 · 点击刷新");
            }
            publish(result);
        }
        private void publish(Result result) {
            if (cancelled) return;
            WeakReference<HomeControls> reference = owner;
            int requestGeneration = generation;
            MAIN.post(() -> {
                HomeControls controls = reference.get();
                if (controls != null) controls.acceptResult(requestGeneration, result);
            });
        }
        private Result readState(String token) throws Exception {
            JSONObject data = new JSONObject(request(token, "/api/states/" + config.entity, null));
            if (!config.entity.equals(data.optString("entity_id", ""))) {
                return Result.error("状态未知 · 设备响应不匹配");
            }
            JSONObject attributes = data.optJSONObject("attributes");
            String friendly = attributes == null ? "" : attributes.optString("friendly_name", "");
            String state = data.optString("state", "unknown");
            if ("on".equals(state) || "off".equals(state)) {
                boolean on = "on".equals(state);
                return new Result(true, on, true, on ? "已开启" : "已关闭", friendly);
            }
            return new Result(false, false, false,
                    "unavailable".equals(state) ? "状态未知 · 设备不可用" : "状态未知 · 点击刷新", friendly);
        }
        private String request(String token, String path, String body) throws Exception {
            checkCancelled();
            if (!hasUsableNetwork(connectivity)) throw new IOException("network unavailable");
            URL url = new URL(config.baseUrl + path);
            if ("http".equals(url.getProtocol())
                    && (!isExplicitLocalHost(url.getHost())
                    || !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.getHost()))) {
                throw new IOException("cleartext unavailable");
            }
            HttpURLConnection request = (HttpURLConnection) url.openConnection();
            connection = request;
            try {
                checkCancelled();
                request.setConnectTimeout(TIMEOUT_MS);
                request.setReadTimeout(TIMEOUT_MS);
                request.setInstanceFollowRedirects(false);
                request.setUseCaches(false);
                request.setRequestProperty("Authorization", "Bearer " + token);
                request.setRequestProperty("Accept", "application/json");
                request.setRequestProperty("Cache-Control", "no-cache");
                request.setRequestProperty("Connection", "close");
                if (body == null) {
                    request.setRequestMethod("GET");
                } else {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    request.setRequestMethod("POST");
                    request.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    request.setDoOutput(true);
                    request.setFixedLengthStreamingMode(bytes.length);
                    checkCancelled();
                    try (OutputStream output = request.getOutputStream()) { output.write(bytes); }
                }
                checkCancelled();
                int status = request.getResponseCode();
                if (status < 200 || status >= 300) throw new HttpFailure(status);
                // Ignore a service response body; the separate GET is the state authority.
                if (body != null) return "";
                try (InputStream input = request.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        if (output.size() + count > 65536) throw new IOException("response too large");
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                request.disconnect();
                if (connection == request) connection = null;
            }
        }
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
        String userMessage() {
            if (status == 401 || status == 403) return "认证失败 · 请更新访问令牌";
            if (status == 404) return "未找到设备 · 请检查实体 ID";
            if (status >= 300 && status < 400) return "地址发生重定向 · 请填写服务器的最终地址";
            return "无法连接 · 服务器返回 " + status;
        }
    }

    private static final class SaveTask implements Runnable {
        final WeakReference<HomeControls> owner;
        final Context appContext;
        final Config candidate;
        final Config existing;
        final String providedToken;
        final int generation;
        SaveTask(HomeControls owner, Context appContext, Config candidate, Config existing,
                 String providedToken, int generation) {
            this.owner = new WeakReference<>(owner);
            this.appContext = appContext;
            this.candidate = candidate;
            this.existing = existing;
            this.providedToken = providedToken;
            this.generation = generation;
        }
        @Override public void run() {
            boolean saved = false;
            try {
                if (Thread.currentThread().isInterrupted()) return;
                String token = providedToken.isEmpty() ? decryptToken(existing) : providedToken;
                validateToken(token);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
                cipher.updateAAD(candidate.associatedData());
                byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
                byte[] encrypted;
                try { encrypted = cipher.doFinal(plaintext); }
                finally { Arrays.fill(plaintext, (byte) 0); }
                if (Thread.currentThread().isInterrupted()) return;
                saved = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                        .putString("base_url", candidate.baseUrl)
                        .putString("entity_id", candidate.entity)
                        .putString("label", candidate.label)
                        .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .putString("token_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                        .commit();
            } catch (Exception ignored) {
                // Never log credentials, response bodies, or cryptographic exception details.
            }
            boolean success = saved;
            WeakReference<HomeControls> reference = owner;
            int saveGeneration = generation;
            MAIN.post(() -> {
                HomeControls controls = reference.get();
                if (controls != null) controls.acceptSave(saveGeneration, success);
            });
        }
    }

    private static SecretKey getKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        if (!create) throw new java.security.KeyStoreException("missing key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String decryptToken(Config config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(config.iv, Base64.NO_WRAP);
        if (iv.length != 12) throw new java.security.GeneralSecurityException("invalid IV");
        cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(config.associatedData());
        byte[] plaintext = cipher.doFinal(Base64.decode(config.encryptedToken, Base64.NO_WRAP));
        try { return new String(plaintext, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }
}
