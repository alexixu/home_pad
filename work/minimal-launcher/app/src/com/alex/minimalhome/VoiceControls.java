package com.alex.minimalhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** ChatGPT Voice over WebRTC. No Android ASR/TTS fallback and no OpenAI project key. */
public final class VoiceControls {
    private static final int REQUEST_AUDIO = 1402;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private final Activity activity;
    private final HomeControls home;
    private final EnvironmentControls environment;
    private final HomeDevices devices;
    private final Runnable onClosed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ExecutorService eventWorker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog, settingsDialog;
    private WebView browser;
    private VoiceServiceConfig sessionConfig;
    private VoiceServiceRequest handshake;
    private String requestId;
    private String serverSessionId;
    private volatile String pollingSession;
    private volatile VoiceServiceRequest eventRequest;
    private boolean resumed, destroyed, permissionPending, permissionDenied, pageLoaded, preparing, sessionReadOnly = true;
    private int generation, closingCount;
    private boolean launchPending;
    private PendingTool pending;
    private CatalogTool catalogPending;
    private final Set<String> toolCalls = new HashSet<>();
    private final Set<String> serverToolCalls = new HashSet<>();
    private final VoiceIntentEvidence speechEvidence = new VoiceIntentEvidence();
    private final Runnable toolTimeout = () -> {
        PendingTool task = pending;
        if (task == null) return;
        pending = null;
        reply(task.id, error(task.phase == 1 ? "result_unverified" : "state_read_timeout"));
    };
    private final Runnable catalogTimeout = () -> {
        CatalogTool task = catalogPending;
        if (task == null) return;
        task.authorization.set(false);
        catalogPending = null;
        cancelCatalogTransport();
        reply(task.id, error(task.control ? "result_unverified" : "state_read_timeout"));
    };

    public VoiceControls(Activity activity, HomeControls home, EnvironmentControls environment, HomeDevices devices,
            Runnable onClosed) {
        this.activity = activity; this.home = home; this.environment = environment; this.devices = devices; this.onClosed = onClosed;
    }

    private void cancelCatalogTransport() { devices.cancel(); }

    public boolean isOpen() { return launchPending || closingCount > 0 || dialog != null && dialog.isShowing(); }
    private boolean active() { return resumed && !destroyed && dialog != null && dialog.isShowing() && !activity.isFinishing(); }
    private boolean current(int ticket) { return active() && generation == ticket && browser != null; }
    private boolean current(String id, int ticket) { return current(ticket) && id != null && id.equals(requestId); }

    public void show() {
        if (destroyed || !resumed || activity.isFinishing()) return;
        dismiss();
        final int ticket = ++generation;
        launchPending = true;
        main.post(new Runnable() {
            @Override public void run() {
                if (generation != ticket || destroyed || !resumed) return;
                if (closingCount > 0) { main.postDelayed(this, 30); return; }
                launchPending = false;
                if (!activity.hasWindowFocus()) { onClosed.run(); return; }
                showDialog(ticket);
            }
        });
    }

    private void showDialog(int ticket) {
        permissionDenied = false;
        sessionConfig = VoiceServiceConfig.load(activity);
        sessionReadOnly = sessionConfig == null || sessionConfig.readOnly;
        View content;
        if (sessionConfig == null) {
            TextView setup = new TextView(activity);
            setup.setText("连接 ChatGPT 语音后，就可以用自然语言查询和操作已接入的家居。\n\n请在语音设置中连接已配置的服务。");
            setup.setTextSize(18); setup.setTextColor(0xFF705537); setup.setPadding(dp(28), dp(24), dp(28), dp(24));
            content = setup;
        } else {
            browser = createBrowser(ticket);
            content = browser;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setView(content);
        if (browser == null) builder.setTitle("ChatGPT 语音")
                .setNegativeButton("返回首页", (ignored, which) -> dismiss())
                .setNeutralButton("语音设置", (ignored, which) -> { dismiss(); showSettings(); });
        AlertDialog next = builder.create();
        dialog = next;
        next.setOnDismissListener(ignored -> { if (dialog == next) dismiss(); });
        next.show();
        HomeStyle.dialog(next);
        GradientDrawable paper = new GradientDrawable(); paper.setColor(HomeStyle.SURFACE); paper.setCornerRadius(dp(24));
        if (next.getWindow() != null) {
            next.getWindow().setBackgroundDrawable(paper);
            if (browser != null) next.getWindow().setLayout(Math.min(dp(1000), activity.getResources().getDisplayMetrics().widthPixels - dp(48)),
                    Math.min(dp(760), activity.getResources().getDisplayMetrics().heightPixels - dp(48)));
        }
        if (browser != null) {
            browser.loadUrl(ORIGIN + "/voice/voice.html");
        }
    }

    private WebView createBrowser(int ticket) {
        WebView view = new WebView(activity);
        view.setBackgroundColor(HomeStyle.SURFACE);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        view.addJavascriptInterface(new Bridge(ticket), "HomeVoice");
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest permission) {
                main.post(() -> {
                    boolean audioOnly = permission.getResources().length == 1
                            && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(permission.getResources()[0]);
                    if (current(ticket) && audioOnly && ORIGIN.equals(permission.getOrigin().toString().replaceAll("/$", ""))
                            && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        permission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else permission.deny();
                });
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // Trusted bundled code only: no webpage may navigate to a remote origin with this bridge.
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                        && "GET".equals(request.getMethod()) && uri.getQuery() == null && uri.getPort() == -1
                        && "/voice/voice.html".equals(uri.getPath())) {
                    try { return new WebResourceResponse("text/html", "UTF-8", activity.getAssets().open("voice/voice.html")); }
                    catch (Exception ignored) {}
                }
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                        && "GET".equals(request.getMethod()) && uri.getQuery() == null && uri.getPort() == -1
                        && "/voice/voice.js".equals(uri.getPath())) {
                    try { return new WebResourceResponse("application/javascript", "UTF-8", activity.getAssets().open("voice/voice.js")); }
                    catch (Exception ignored) {}
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(),
                        new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (!current(ticket) || v != browser || !url.startsWith(ORIGIN + "/voice/")) return;
                pageLoaded = true; requestStart(ticket);
            }
        });
        return view;
    }

    private void requestStart(int ticket) {
        if (!current(ticket) || !pageLoaded || permissionPending || permissionDenied) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        browser.evaluateJavascript("window.homeVoice&&window.homeVoice.start()", null);
    }

    public boolean onPermissionResult(int code, int[] grants) {
        if (code != REQUEST_AUDIO) return false;
        boolean requested = permissionPending; permissionPending = false;
        if (!requested || !isOpen()) return true;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) requestStart(generation);
        } else {
            permissionDenied = true;
            showPageError("麦克风权限未开启，请在系统设置中允许后重新连接。", generation);
        }
        return true;
    }
    public void onHostResumed() {
        resumed = true;
        if (isOpen() && pageLoaded && requestId == null && !permissionPending) requestStart(generation);
    }
    public void onHostPaused() {
        resumed = false;
        if (!permissionPending) dismiss();
    }

    private final class Bridge {
        private final int ticket;
        Bridge(int ticket) { this.ticket = ticket; }
        @JavascriptInterface public void createSession(String offer, String id) {
            main.post(() -> {
                if (!current(ticket) || sessionConfig == null || id == null || !id.matches("[A-Za-z0-9_-]{8,96}")
                        || offer == null || !offer.startsWith("v=0") || offer.length() > 60000) return;
                if (requestId != null || handshake != null) return;
                requestId = id;
                if (!sessionConfig.sameConnection(VoiceServiceConfig.load(activity))) {
                    showPageError("语音连接设置已变化，请重新打开语音。", ticket); return;
                }
                toolCalls.clear(); speechEvidence.clear();
                VoiceServiceRequest task = new VoiceServiceRequest(); handshake = task;
                VoiceServiceConfig boundConfig = sessionConfig;
                worker.execute(() -> {
                    JSONObject result = null; String error = null;
                    try { result = task.create(boundConfig, offer, id).put("requestId", id); }
                    catch (VoiceServiceRequest.Failure e) { error = e.getMessage(); }
                    catch (javax.net.ssl.SSLException e) { error = "语音服务证书验证失败，请检查配对设置。"; }
                    catch (Exception e) { error = "无法连接语音服务，请检查 Wi-Fi 和服务是否在线。"; }
                    final JSONObject reply = result; final String failure = error;
                    main.post(() -> {
                        if (handshake == task) handshake = null;
                        if (!current(id, ticket)) {
                            if (reply != null && "codex".equals(reply.optString("backend")))
                                closeServerRemote(boundConfig, reply.optString("sessionId"));
                            return;
                        }
                        if (reply != null) {
                            sessionReadOnly = boundConfig.readOnly || reply.optBoolean("readOnly", true);
                            if ("codex".equals(reply.optString("backend"))) {
                                serverSessionId = reply.optString("sessionId");
                                startEvents(boundConfig, serverSessionId, id, ticket);
                            }
                            browser.evaluateJavascript("window.homeVoice.onSession(" + reply.toString() + ")", null);
                        } else showPageError(failure, ticket);
                    });
                });
            });
        }
        @JavascriptInterface public void closeSession(String id) {
            main.post(() -> {
                if (generation != ticket || (requestId != null && !requestId.equals(id))) return;
                endServerSession(); cancelPending(); if (handshake != null) handshake.cancel(); handshake = null; requestId = null;
            });
        }
        @JavascriptInterface public void settingsRequested() {
            main.post(() -> { if (current(ticket)) { dismiss(); showSettings(); } });
        }
        @JavascriptInterface public void closeRequested() { main.post(() -> { if (generation == ticket) dismiss(); }); }
        @JavascriptInterface public void notifyState(String state, String message, String id) {
            // The trusted page owns presentation; state messages cannot change device state.
        }
        @JavascriptInterface public void userTranscript(String text, String id) {
            // Live transcript fragments are display-only, never a power-operation trigger.
        }
        @JavascriptInterface public void assistantTranscript(String text, String id) {}
        @JavascriptInterface public void transcriptEvidence(String role, String delta, double startMs, double endMs, String id) {
            main.post(() -> {
                if (current(id, ticket)) {
                    speechEvidence.observe(role, delta, startMs, endMs);
                    if ("user".equals(role)) invalidateChangedCatalogIntent();
                }
            });
        }
        @JavascriptInterface public void invalidateEvidence(String id) {
            main.post(() -> {
                if (current(id, ticket)) { speechEvidence.invalidate(); invalidateChangedCatalogIntent(); }
            });
        }
        @JavascriptInterface public void runTool(String callId, String name, String args, String id) {
            main.post(() -> { if (current(id, ticket)) handleTool(callId, name, args); });
        }
    }

    private void startEvents(VoiceServiceConfig config, String session, String id, int ticket) {
        pollingSession = session;
        eventWorker.execute(() -> {
            while (session.equals(pollingSession)) {
                VoiceServiceRequest task = new VoiceServiceRequest(); eventRequest = task;
                try {
                    JSONObject response = task.poll(config, session);
                    JSONArray events = response.getJSONArray("events");
                    if (events.length() > 32) throw new IllegalArgumentException();
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject event = events.getJSONObject(i);
                        main.post(() -> {
                            if (current(id, ticket) && session.equals(serverSessionId)) handleServerEvent(event, session);
                        });
                    }
                } catch (Exception e) {
                    if (session.equals(pollingSession)) main.post(() -> {
                        if (current(id, ticket) && session.equals(serverSessionId)) {
                            cancelPending(); endServerSession();
                            showPageError("ChatGPT 语音连接已中断，请重新开始。", ticket);
                        }
                    });
                    break;
                } finally { if (eventRequest == task) eventRequest = null; }
            }
        });
    }

    private void handleServerEvent(JSONObject event, String session) {
        if (!session.equals(event.optString("sessionId"))) return;
        String type = event.optString("type");
        if ("tool.request".equals(type)) {
            String call = event.optString("callId");
            if (call.isEmpty() || call.length() > 200 || serverToolCalls.size() >= 128) return;
            JSONObject arguments = event.optJSONObject("arguments");
            if (arguments == null) return;
            serverToolCalls.add(call);
            handleTool(call, event.optString("tool"), arguments.toString());
        } else if ("error".equals(type) || "closed".equals(type)) {
            cancelPending(); endServerSession();
            showPageError("ChatGPT 语音会话已结束，请重新开始。", generation);
        }
    }

    private void endServerSession() {
        String previous = serverSessionId; serverSessionId = null; pollingSession = null;
        VoiceServiceRequest active = eventRequest; eventRequest = null;
        if (active != null) active.cancel();
        serverToolCalls.clear();
        if (previous != null && sessionConfig != null) closeServerRemote(sessionConfig, previous);
    }

    private void closeServerRemote(VoiceServiceConfig config, String session) {
        Runnable close = () -> { try { new VoiceServiceRequest().close(config, session); } catch (Exception ignored) {} };
        if (!worker.isShutdown()) {
            try { worker.execute(close); return; }
            catch (java.util.concurrent.RejectedExecutionException ignored) {}
        }
        // A handshake can complete after Activity destruction. Its owned server session
        // still needs an explicit close; this one bounded request never reopens the UI.
        Thread cleanup = new Thread(close, "voice-session-close");
        cleanup.setDaemon(true); cleanup.start();
    }

    private void handleTool(String id, String name, String arguments) {
        if (id == null || id.isEmpty() || id.length() > 200 || toolCalls.size() >= 128 || toolCalls.contains(id)) return;
        toolCalls.add(id);
        if (pending != null || catalogPending != null) { reply(id, error("busy")); return; }
        if (sessionConfig == null || !sessionConfig.sameConnection(VoiceServiceConfig.load(activity))) {
            reply(id, error("connection_changed")); return;
        }
        try {
            if (arguments == null || arguments.length() > 4096) throw new IllegalArgumentException();
            JSONObject args = new JSONObject(arguments);
            if ("get_home_devices".equals(name) || "control_home_device".equals(name)) {
                handleCatalogTool(id, name, args); return;
            }
            HomeControls.VoicePowerRequest power = null;
            VoiceIntentEvidence.Ticket evidence = null;
            Boolean target = null;
            if ("get_home_status".equals(name)) {
                if (args.length() != 0) throw new IllegalArgumentException();
            } else if ("set_device_power".equals(name)) {
                if (sessionReadOnly || sessionConfig.readOnly) { reply(id, error("read_only_session")); return; }
                if (args.length() != 3 || !"oven_plug".equals(args.getString("device"))) throw new IllegalArgumentException();
                String value = args.getString("power");
                if (!value.equals("on") && !value.equals("off")) throw new IllegalArgumentException();
                target = value.equals("on");
                VoiceCommand command = VoiceCommand.parse(args.getString("utterance"), home.getDashboardState().title);
                if (command.kind != VoiceCommand.Kind.SET_DEVICE || command.targetOn != target.booleanValue()) {
                    reply(id, error("explicit_single_command_required")); return;
                }
                evidence = speechEvidence.prepare(args.getString("utterance"), home.getDashboardState().title, target);
                if (evidence == null) { reply(id, error(speechEvidence.rejectionCode())); return; }
                power = home.prepareVoicePower(target);
                if (power == null) { reply(id, error("device_unavailable")); return; }
            } else { reply(id, error("unsupported_tool")); return; }
            pending = new PendingTool(id, power, target, evidence);
            preparing = true;
            home.refresh(); environment.refresh();
            preparing = false;
            main.postDelayed(toolTimeout, 25000);
            onStateChanged();
        } catch (Exception e) {
            preparing = false;
            if (pending != null && pending.id.equals(id)) cancelPending();
            if (catalogPending != null && catalogPending.id.equals(id)) cancelPending();
            reply(id, error("invalid_arguments"));
        }
    }

    private void handleCatalogTool(String id, String name, JSONObject args) throws Exception {
        if ("get_home_devices".equals(name)) {
            if (args.length() != 1 || !(args.opt("query") instanceof String)
                    || args.getString("query").length() > 64) throw new IllegalArgumentException();
            String query = args.getString("query");
            CatalogTool task = new CatalogTool(id, false, null, requestId, generation);
            catalogPending = task;
            main.postDelayed(catalogTimeout, 25000);
            devices.refresh(result -> {
                if (!catalogCurrent(task)) return;
                finishCatalogTool(task, result.optBoolean("ok", false) ? devices.snapshot().toJson(query) : result);
            });
            return;
        }
        if (sessionReadOnly || sessionConfig == null || sessionConfig.readOnly) { reply(id, error("read_only")); return; }
        if (args.length() != 5 || !(args.opt("target_ref") instanceof String)
                || !(args.opt("catalog_revision") instanceof String) || !(args.opt("action") instanceof String)
                || !(args.opt("utterance") instanceof String) || !args.has("value")) throw new IllegalArgumentException();
        HomeDevices.PreparedAction action;
        try {
            action = devices.prepare(args.getString("target_ref"), args.getString("catalog_revision"),
                    args.getString("action"), args.get("value"), args.getString("utterance"), true);
        } catch (HomeDevices.Failure failure) { reply(id, error(failure.code)); return; }
        VoiceIntentEvidence.Ticket evidence = speechEvidence.prepareValidated(action.utterance);
        if (evidence == null) { reply(id, error(speechEvidence.rejectionCode())); return; }
        CatalogTool task = new CatalogTool(id, true, evidence, requestId, generation);
        catalogPending = task;
        main.postDelayed(catalogTimeout, 25000);
        devices.execute(action, task.authorization, evidence.expiresAtNanos, result -> {
            if (catalogCurrent(task)) finishCatalogTool(task, result);
        });
    }

    private boolean catalogCurrent(CatalogTool task) {
        return catalogPending == task && current(task.requestId, task.generation);
    }
    private void invalidateChangedCatalogIntent() {
        CatalogTool task = catalogPending;
        // Keep the request tracked until transport finishes. A late correction can
        // prevent an unsent command, but cannot retract a command already sent.
        if (task != null && task.control && !speechEvidence.stillMatches(task.evidence))
            task.authorization.set(false);
    }
    private void finishCatalogTool(CatalogTool task, JSONObject result) {
        if (!catalogCurrent(task)) return;
        task.authorization.set(false);
        catalogPending = null;
        main.removeCallbacks(catalogTimeout);
        reply(task.id, result);
    }

    public void onStateChanged() {
        if (!active() || pending == null || preparing) return;
        HomeControls.DashboardState device = home.getDashboardState();
        EnvironmentControls.State environmentState = environment.getState();
        if (device.busy || (pending.phase == 0 && environmentState.busy)) return;
        PendingTool task = pending;
        if (task.power == null) { finishTool(task, snapshot()); return; }
        if (task.phase == 0) {
            if (sessionReadOnly || sessionConfig == null || !sessionConfig.sameConnection(VoiceServiceConfig.load(activity))) {
                finishTool(task, error("connection_changed")); return;
            }
            if (!speechEvidence.stillMatches(task.evidence)) { finishTool(task, error("spoken_intent_changed")); return; }
            task.phase = 1;
            preparing = true;
            HomeControls.VoicePowerResult result = home.executeVoicePower(task.power);
            preparing = false;
            if (result == HomeControls.VoicePowerResult.REJECTED) { finishTool(task, error("device_request_rejected")); return; }
            if (result == HomeControls.VoicePowerResult.ALREADY_IN_STATE) {
                JSONObject state = snapshot(); put(state, "operation", "already_in_target_state"); finishTool(task, state); return;
            }
            if (home.getDashboardState().busy) return;
        }
        JSONObject state = snapshot();
        put(state, "operation", device.known && device.on == task.target ? "target_state_observed" : "result_unverified");
        finishTool(task, state);
    }

    private JSONObject snapshot() {
        HomeControls.DashboardState d = home.getDashboardState(); EnvironmentControls.State e = environment.getState();
        JSONObject result = new JSONObject();
        put(result, "ok", true); put(result, "observed_at_ms", System.currentTimeMillis());
        JSONObject plug = new JSONObject(); put(plug, "name", d.title); put(plug, "state", d.known ? d.on ? "on" : "off" : "unknown");
        put(plug, "status", d.status); put(plug, "network_available", d.networkAvailable); put(result, "oven_plug", plug);
        JSONObject indoor = new JSONObject(); put(indoor, "temperature_c", e.temperatureC); put(indoor, "humidity_percent", e.humidity);
        put(indoor, "status", e.indoorStatus); put(indoor, "recorded_at", e.sensorReportedAt); put(result, "indoor", indoor);
        JSONObject weather = new JSONObject(); put(weather, "temperature_c", e.outdoorTemperatureC);
        put(weather, "condition", WarmDashboard.conditionLabel(e.condition)); put(weather, "location", e.weatherSource);
        put(weather, "attribution", e.weatherAttribution); put(weather, "status", e.weatherStatus);
        put(weather, "recorded_at", e.weatherReportedAt); put(result, "weather", weather);
        put(result, "evidence", "Home Assistant cached reports; use get_home_devices for the current device catalog, not a physical power measurement.");
        return result;
    }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) {} }
    private static JSONObject error(String code) { JSONObject result = new JSONObject(); put(result, "ok", false); put(result, "error", code); return result; }
    private void finishTool(PendingTool task, JSONObject result) { if (pending != task) return; pending = null; main.removeCallbacks(toolTimeout); reply(task.id, result); }
    private void reply(String call, JSONObject result) {
        if (active() && serverSessionId != null && serverToolCalls.remove(call)) {
            String session = serverSessionId; String id = requestId; int ticket = generation;
            VoiceServiceConfig config = sessionConfig;
            worker.execute(() -> {
                if (!session.equals(pollingSession)) return;
                try { new VoiceServiceRequest().toolResult(config, session, call, result); }
                catch (Exception e) { main.post(() -> {
                    if (current(id, ticket) && session.equals(serverSessionId)) {
                        cancelPending(); endServerSession();
                        showPageError("家居查询结果未能送达，请重新连接语音。", ticket);
                    }
                }); }
            });
            return;
        }
        if (active() && browser != null && requestId != null)
            browser.evaluateJavascript("window.homeVoice.onToolResult(" + JSONObject.quote(call) + "," + result.toString()
                    + "," + JSONObject.quote(requestId) + ")", null);
    }
    private void cancelPending() {
        pending = null; main.removeCallbacks(toolTimeout); preparing = false;
        CatalogTool task = catalogPending; catalogPending = null;
        if (task != null) { task.authorization.set(false); devices.cancel(); }
        main.removeCallbacks(catalogTimeout);
    }
    private static final class CatalogTool {
        final String id, requestId; final int generation; final boolean control;
        final VoiceIntentEvidence.Ticket evidence;
        final AtomicBoolean authorization = new AtomicBoolean(true);
        CatalogTool(String id, boolean control, VoiceIntentEvidence.Ticket evidence, String requestId, int generation) {
            this.id = id; this.control = control; this.evidence = evidence;
            this.requestId = requestId; this.generation = generation;
        }
    }
    private static final class PendingTool {
        final String id; final HomeControls.VoicePowerRequest power; final Boolean target;
        final VoiceIntentEvidence.Ticket evidence; int phase;
        PendingTool(String id, HomeControls.VoicePowerRequest power, Boolean target, VoiceIntentEvidence.Ticket evidence) {
            this.id = id; this.power = power; this.target = target; this.evidence = evidence;
        }
    }

    private void showPageError(String message, int ticket) {
        if (generation != ticket || browser == null) return;
        if (requestId == null) {
            browser.evaluateJavascript("window.homeVoice&&window.homeVoice.showSetupError(" + JSONObject.quote(message) + ")", null);
            return;
        }
        browser.evaluateJavascript("window.homeVoice&&window.homeVoice.onError(" + JSONObject.quote(message) + ","
                + (requestId == null ? "null" : JSONObject.quote(requestId)) + ")", null);
    }

    public void showSettings() {
        if (destroyed || activity.isFinishing()) return;
        if (settingsDialog != null) settingsDialog.dismiss();
        VoiceServiceConfig saved = VoiceServiceConfig.load(activity);
        LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView explanation = new TextView(activity); explanation.setText("连接 ChatGPT 语音服务。OpenAI 凭据保留在服务端，平板只保存配对信息。"); fields.addView(explanation);
        EditText endpoint = input(fields, "服务地址 https://…", saved == null ? "" : saved.endpoint, false);
        EditText pin = input(fields, "服务证书 SHA256", saved == null ? "" : saved.certificateSha256, false);
        EditText token = input(fields, saved == null ? "配对码" : "配对码（留空保留）", "", true);
        CheckBox readOnly = new CheckBox(activity); readOnly.setText("仅查询状态，禁用语音设备控制"); readOnly.setChecked(saved == null || saved.readOnly); fields.addView(readOnly);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        AlertDialog next = new AlertDialog.Builder(activity).setTitle("ChatGPT 语音连接").setView(scroll)
                .setPositiveButton("保存", null).setNeutralButton("导入配对文件", null).setNegativeButton("取消", null).create();
        settingsDialog = next; next.setOnDismissListener(ignored -> { if (settingsDialog == next) settingsDialog = null; });
        next.show();
        HomeStyle.dialog(next);
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String value = token.getText().toString();
                if (value.isEmpty() && saved != null) value = saved.token;
                new VoiceServiceConfig(endpoint.getText().toString(), pin.getText().toString(), value, readOnly.isChecked()).save(activity);
                next.dismiss(); Toast.makeText(activity, "语音连接已保存", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(activity, "地址、证书或配对码无效，未保存。", Toast.LENGTH_LONG).show(); }
        });
        next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            try {
                boolean imported = VoiceServiceConfig.importPairing(activity);
                Toast.makeText(activity, imported ? "语音配对已导入" : "没有找到待导入的配对文件", Toast.LENGTH_LONG).show();
                if (imported) next.dismiss();
            } catch (Exception e) { Toast.makeText(activity, "配对文件无效，未导入。", Toast.LENGTH_LONG).show(); }
        });
    }
    private EditText input(LinearLayout parent, String hint, String value, boolean password) {
        TextView label = new TextView(activity); label.setText(hint); label.setTextColor(HomeStyle.MUTED);
        label.setTextSize(13); label.setPadding(0, dp(14), 0, dp(4)); parent.addView(label);
        EditText edit = new EditText(activity); edit.setSingleLine(true); edit.setHint(password ? "输入后保存" : hint); edit.setText(value);
        edit.setTextSize(15); edit.setTextColor(HomeStyle.INK); edit.setHintTextColor(HomeStyle.MUTED);
        edit.setPadding(dp(12), dp(12), dp(12), dp(12)); edit.setBackground(HomeStyle.surface(activity, HomeStyle.PAPER, 10));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        parent.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return edit;
    }

    public void dismiss() {
        ++generation; endServerSession(); launchPending = false; permissionPending = false; pageLoaded = false; requestId = null; cancelPending(); toolCalls.clear(); speechEvidence.clear();
        if (handshake != null) handshake.cancel(); handshake = null;
        WebView previousBrowser = browser; browser = null;
        if (previousBrowser != null) {
            ++closingCount;
            try { previousBrowser.evaluateJavascript("window.homeVoice&&window.homeVoice.stop()", null); }
            catch (RuntimeException ignored) { /* Always run bounded native disposal below. */ }
            // Give the trusted page a bounded chance to send session.close; never resume it.
            main.postDelayed(() -> {
                try { previousBrowser.removeJavascriptInterface("HomeVoice"); previousBrowser.stopLoading(); previousBrowser.destroy(); }
                finally { --closingCount; if (closingCount == 0) onClosed.run(); }
            }, 500);
        }
        AlertDialog previous = dialog; dialog = null;
        if (previous != null) { previous.dismiss(); if (closingCount == 0) onClosed.run(); }
        if (settingsDialog != null) { AlertDialog settings = settingsDialog; settingsDialog = null; settings.dismiss(); }
    }
    public void destroy() { destroyed = true; dismiss(); eventWorker.shutdownNow(); worker.shutdown(); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
