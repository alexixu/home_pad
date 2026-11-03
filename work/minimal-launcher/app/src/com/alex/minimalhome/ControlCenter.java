package com.alex.minimalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** The explicit destination for device actions; the home dashboard remains display-only. */
public final class ControlCenter extends FrameLayout {
    private static final int PAPER = 0xFFF7F0E5;
    private static final int SURFACE = 0xFFFFFBF3;
    private static final int INK = 0xFF51402F;
    private static final int MUTED = 0xFF88735C;
    private static final int LINE = 0xFFE8DDCD;
    private static final int ACCENT = 0xFF806343;

    private final HomeControls homeControls;
    private final HomeDevices homeDevices;
    private final EnvironmentControls environmentControls;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout columns;
    private final LinearLayout deviceCard;
    private final LinearLayout environmentCard;
    private final TextView deviceName;
    private final TextView deviceStatus;
    private final TextView indoorReading;
    private final TextView indoorSource;
    private final TextView weatherReading;
    private final TextView weatherSource;
    private final Button powerAction;
    private final Button deviceRefresh;
    private final Button environmentRefresh;
    private final LinearLayout allDevicesBody;
    private final TextView allDevicesSummary;
    private final TextView allDevicesFeedback;
    private final Button allDevicesRefresh;
    private final EditText deviceSearch;
    private AlertDialog actionDialog;
    private AtomicBoolean touchAuthorization;
    private boolean touchBusy;
    private boolean catalogRefreshing;
    private String renderedCatalog = "";
    private boolean wide;

    public ControlCenter(Activity activity, HomeControls homeControls, HomeDevices homeDevices,
            EnvironmentControls environmentControls, Runnable close,
            Runnable openHomeSettings, Runnable showIndoorDetails, Runnable showWeatherDetails) {
        super(activity);
        this.homeControls = homeControls;
        this.homeDevices = homeDevices;
        this.environmentControls = environmentControls;
        setBackgroundColor(PAPER);
        setClickable(true); // Consume empty-space touches instead of forwarding them to the home screen.
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        setVisibility(GONE);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        content = column();
        content.setPadding(dp(28), dp(28), dp(28), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout header = row();
        LinearLayout heading = column();
        heading.addView(text("HOME", 11, MUTED, true));
        TextView title = text("控制中心", 30, INK, true);
        title.setPadding(0, dp(5), 0, 0);
        markHeading(title);
        heading.addView(title);
        header.addView(heading, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button back = button("返回首页", false, () -> { hide(); close.run(); });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(112), LayoutParams.WRAP_CONTENT);
        backParams.leftMargin = dp(12);
        header.addView(back, backParams);
        content.addView(header, matchWrap());
        TextView intro = text("查看详情，或在这里操作设备。", 14, MUTED, false);
        intro.setPadding(0, dp(13), 0, dp(26));
        content.addView(intro, matchWrap());

        columns = column();
        content.addView(columns, matchWrap());

        deviceCard = card();
        deviceCard.addView(sectionLabel("常用设备"), matchWrap());
        deviceName = text("插座", 25, INK, true);
        deviceName.setPadding(0, dp(14), 0, dp(8));
        deviceCard.addView(deviceName, matchWrap());
        deviceStatus = text("等待连接", 17, MUTED, false);
        deviceCard.addView(deviceStatus, matchWrap());
        TextView stateNote = text("状态由 Home Assistant 报告", 12, MUTED, false);
        stateNote.setPadding(0, dp(7), 0, dp(22));
        deviceCard.addView(stateNote, matchWrap());
        powerAction = button("电源操作…", true, () -> {
            cancelTouchAction();
            homeControls.showControlDialog();
        });
        deviceCard.addView(powerAction, matchWrap());
        TextView confirmNote = text("操作前需再次确认。", 12, MUTED, false);
        confirmNote.setPadding(0, dp(8), 0, dp(14));
        deviceCard.addView(confirmNote, matchWrap());
        deviceRefresh = button("刷新设备状态", false, homeControls::refresh);
        deviceCard.addView(deviceRefresh, matchWrap());

        environmentCard = card();
        environmentCard.addView(sectionLabel("环境"), matchWrap());
        TextView indoorLabel = text("室内温湿度", 15, INK, true);
        indoorLabel.setPadding(0, dp(17), 0, dp(8));
        environmentCard.addView(indoorLabel, matchWrap());
        indoorReading = text("— °C   /   — %", 26, INK, false);
        environmentCard.addView(indoorReading, matchWrap());
        indoorSource = text("等待传感器数据", 13, MUTED, false);
        indoorSource.setPadding(0, dp(7), 0, dp(10));
        environmentCard.addView(indoorSource, matchWrap());
        environmentCard.addView(button("温湿度来源详情", false, showIndoorDetails), matchWrap());
        divider(environmentCard, 19);
        environmentCard.addView(text("室外天气", 15, INK, true), matchWrap());
        weatherReading = text("— °C", 26, INK, false);
        weatherReading.setPadding(0, dp(8), 0, 0);
        environmentCard.addView(weatherReading, matchWrap());
        weatherSource = text("等待天气数据", 13, MUTED, false);
        weatherSource.setPadding(0, dp(7), 0, dp(10));
        environmentCard.addView(weatherSource, matchWrap());
        environmentCard.addView(button("天气来源详情", false, showWeatherDetails), matchWrap());
        divider(environmentCard, 19);
        environmentRefresh = button("刷新环境数据", false, environmentControls::refresh);
        environmentCard.addView(environmentRefresh, matchWrap());
        Button sourceSettings = button("温湿度与天气设置", false, environmentControls::showSettingsDialog);
        LinearLayout.LayoutParams sourceParams = matchWrap();
        sourceParams.topMargin = dp(8);
        environmentCard.addView(sourceSettings, sourceParams);

        columns.addView(deviceCard, matchWrap());
        LinearLayout.LayoutParams envParams = matchWrap();
        envParams.topMargin = dp(16);
        columns.addView(environmentCard, envParams);

        LinearLayout allDevices = card();
        LinearLayout catalogHeader = row();
        LinearLayout catalogHeading = column();
        catalogHeading.addView(sectionLabel("全部设备"), matchWrap());
        allDevicesSummary = text("等待设备目录", 14, MUTED, false);
        allDevicesSummary.setPadding(0, dp(8), 0, 0);
        catalogHeading.addView(allDevicesSummary, matchWrap());
        catalogHeader.addView(catalogHeading,
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        allDevicesRefresh = button("刷新", false, this::refreshCatalog);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(88), LayoutParams.WRAP_CONTENT);
        refreshParams.leftMargin = dp(12);
        catalogHeader.addView(allDevicesRefresh, refreshParams);
        allDevices.addView(catalogHeader, matchWrap());
        deviceSearch = new EditText(activity);
        deviceSearch.setSingleLine(true);
        deviceSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        deviceSearch.setTextColor(INK);
        deviceSearch.setHintTextColor(MUTED);
        deviceSearch.setHint("查找设备、房间或功能");
        deviceSearch.setContentDescription("查找设备、房间或功能");
        deviceSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        deviceSearch.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        deviceSearch.setPadding(dp(14), dp(12), dp(14), dp(12));
        deviceSearch.setBackground(shape(PAPER, LINE, 12));
        LinearLayout.LayoutParams searchParams = matchWrap();
        searchParams.topMargin = dp(18);
        allDevices.addView(deviceSearch, searchParams);
        allDevicesFeedback = text("选择设备的“操作”后，确认才会执行。", 12, MUTED, false);
        allDevicesFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        allDevicesFeedback.setPadding(0, dp(12), 0, dp(6));
        allDevices.addView(allDevicesFeedback, matchWrap());
        allDevicesBody = column();
        allDevices.addView(allDevicesBody, matchWrap());
        LinearLayout.LayoutParams catalogParams = matchWrap();
        catalogParams.topMargin = dp(16);
        content.addView(allDevices, catalogParams);
        deviceSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshDevices();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        LinearLayout management = card();
        management.addView(sectionLabel("设置"), matchWrap());
        TextView settingsNote = text("家居连接、桌面与本地服务", 14, MUTED, false);
        settingsNote.setPadding(0, dp(10), 0, dp(15));
        management.addView(settingsNote, matchWrap());
        management.addView(button("打开设置", false, openHomeSettings), matchWrap());
        LinearLayout.LayoutParams managementParams = matchWrap();
        managementParams.topMargin = dp(16);
        content.addView(management, managementParams);

        updateState(homeControls.getDashboardState(), environmentControls.getState());
    }

    /** Showing this surface never refreshes or controls a device by itself. */
    public void show() {
        updateState(homeControls.getDashboardState(), environmentControls.getState());
        setVisibility(VISIBLE);
        deviceSearch.clearFocus();
        requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        scroll.scrollTo(0, 0);
        // A layout pass can restore the old descendant position. Only correct the
        // entry position while this container itself still owns initial focus;
        // an intentional search-field tap must keep its normal focus behavior.
        scroll.post(() -> {
            if (getVisibility() == VISIBLE && isFocused()) scroll.scrollTo(0, 0);
        });
    }

    public void hide() {
        cancelTouchAction();
        homeControls.dismissControlDialog();
        setVisibility(GONE);
    }

    /** Main-thread snapshots only; this view registers no observers and performs no polling. */
    public void updateState(HomeControls.DashboardState device, EnvironmentControls.State environment) {
        deviceName.setText(device == null ? "插座" : nonempty(device.title, "插座"));
        deviceStatus.setText(device == null ? "等待连接" : nonempty(device.status, "状态未知"));
        boolean canControl = device != null && device.configured && device.known
                && device.canControl && device.networkAvailable && !device.busy && !touchBusy;
        enabled(powerAction, canControl);
        powerAction.setContentDescription(deviceName.getText() + "，电源操作，需再次确认");
        enabled(deviceRefresh, device != null && !device.busy);
        refreshDevices();
        if (environment == null) {
            indoorReading.setText("— °C   /   — %");
            indoorSource.setText("等待传感器数据");
            weatherReading.setText("— °C");
            weatherSource.setText("等待天气数据");
            enabled(environmentRefresh, true);
            return;
        }
        indoorReading.setText(number(environment.temperatureC) + " °C   /   "
                + humidity(environment.humidity) + " %");
        indoorSource.setText(join(indoorSource(environment), environment.indoorStatus));
        weatherReading.setText(number(environment.outdoorTemperatureC) + " °C  ·  "
                + condition(environment.condition));
        String credit = nonempty(environment.weatherAttribution, "");
        if (credit.toLowerCase(Locale.ROOT).contains("met.no")
                || credit.toLowerCase(Locale.ROOT).contains("met norway")) credit = "MET Norway";
        weatherSource.setText(join(join(environment.weatherSource, environment.weatherStatus), credit));
        enabled(environmentRefresh, !environment.busy);
    }

    /** Called by the activity's shared listener; rendering performs no network requests. */
    public void refreshDevices() {
        JSONObject catalog = homeDevices.snapshot().toJson(deviceSearch.getText().toString().trim());
        HomeControls.DashboardState home = homeControls.getDashboardState();
        boolean blocked = touchBusy || catalogRefreshing || (home != null && home.busy);
        enabled(allDevicesRefresh, !catalogRefreshing && !blocked);
        allDevicesRefresh.setText(catalogRefreshing ? "刷新中" : "刷新");
        String signature = catalog.toString() + "|" + blocked;
        if (signature.equals(renderedCatalog)) return;
        renderedCatalog = signature;
        allDevicesBody.removeAllViews();
        if (!catalog.optBoolean("ok", false)) {
            allDevicesSummary.setText("设备目录暂不可用");
            addCatalogNote(errorMessage(catalog.optString("error", "not_ready")));
            return;
        }
        JSONArray devices = catalog.optJSONArray("devices");
        int displayed = devices == null ? 0 : devices.length();
        int total = catalog.optInt("total_devices", displayed);
        int matched = catalog.optInt("matched_devices", displayed);
        boolean filtered = !deviceSearch.getText().toString().trim().isEmpty();
        String count = filtered ? "找到 " + matched + " 台 · 共 " + total + " 台设备"
                : "已接入 " + total + " 台设备";
        boolean truncated = catalog.optBoolean("truncated", false);
        if (truncated && displayed < matched) count += " · 当前显示 " + displayed + " 台";
        allDevicesSummary.setText(count);
        if (truncated) addCatalogNote("部分功能已省略，请搜索设备或功能。");
        if (displayed == 0) {
            addCatalogNote(filtered ? "没有找到匹配的设备或房间。" : "设备目录中暂时没有设备。");
            return;
        }
        Map<String, ArrayList<JSONObject>> rooms = new LinkedHashMap<>();
        for (int i = 0; i < displayed; i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null) continue;
            String room = nonempty(device.optString("room"), "未分配房间");
            ArrayList<JSONObject> group = rooms.get(room);
            if (group == null) {
                group = new ArrayList<>();
                rooms.put(room, group);
            }
            group.add(device);
        }
        String revision = catalog.optString("catalog_revision", "");
        for (Map.Entry<String, ArrayList<JSONObject>> room : rooms.entrySet()) {
            TextView roomTitle = text(room.getKey() + "  ·  " + room.getValue().size(), 15, INK, true);
            roomTitle.setPadding(0, dp(21), 0, dp(10));
            markHeading(roomTitle);
            allDevicesBody.addView(roomTitle, matchWrap());
            for (JSONObject device : room.getValue()) addDevice(device, revision, blocked);
        }
    }

    private void refreshCatalog() {
        if (catalogRefreshing || touchBusy) return;
        catalogRefreshing = true;
        allDevicesFeedback.setText("正在读取设备状态…");
        refreshDevices();
        homeDevices.refresh(result -> {
            catalogRefreshing = false;
            allDevicesFeedback.setText(result.optBoolean("ok", false)
                    ? "设备状态已刷新。选择操作后，确认才会执行。"
                    : errorMessage(result.optString("error", "request_failed")));
            refreshDevices();
        });
    }

    private void addCatalogNote(String note) {
        TextView view = text(note, 14, MUTED, false);
        view.setPadding(0, dp(18), 0, dp(4));
        allDevicesBody.addView(view, matchWrap());
    }

    private void addDevice(JSONObject device, String revision, boolean blocked) {
        LinearLayout box = column();
        box.setPadding(dp(17), dp(16), dp(17), dp(16));
        box.setBackground(shape(PAPER, LINE, 16));
        String name = nonempty(device.optString("name"), "家居设备");
        JSONArray entities = device.optJSONArray("entities");
        boolean available = device.optBoolean("available", false);
        boolean controllable = false;
        if (entities != null) {
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entity = entities.optJSONObject(i);
                if (entity == null) continue;
                JSONArray actions = entity.optJSONArray("actions");
                controllable |= entityAvailable(entity) && actions != null && actions.length() > 0;
            }
        }
        LinearLayout titleRow = row();
        TextView title = text(name, 18, INK, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView availability = text(!available ? "暂不可用" : controllable ? "可操作" : "只读", 12, MUTED, false);
        availability.setPadding(dp(12), 0, 0, 0);
        titleRow.addView(availability, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        box.addView(titleRow, matchWrap());
        if (entities == null || entities.length() == 0) {
            TextView note = text(available ? "此设备暂未提供可查看的状态。" : "暂时无法获取此设备的状态。", 13, MUTED, false);
            note.setPadding(0, dp(10), 0, 0);
            box.addView(note, matchWrap());
        } else {
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entity = entities.optJSONObject(i);
                if (entity == null) continue;
                LinearLayout entityRow = row();
                entityRow.setPadding(0, dp(14), 0, 0);
                LinearLayout reading = column();
                String entityName = nonempty(entity.optString("name"), name);
                reading.addView(text(entityName, 14, INK, false), matchWrap());
                TextView state = text(displayState(entity), 14, MUTED, false);
                state.setPadding(0, dp(3), 0, 0);
                reading.addView(state, matchWrap());
                entityRow.addView(reading, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                JSONArray actions = entity.optJSONArray("actions");
                if (actions != null && actions.length() > 0) {
                    Button operate = button("操作…", false, () -> chooseAction(device, entity, revision));
                    enabled(operate, available && entityAvailable(entity) && !blocked);
                    operate.setContentDescription(join(name, entityName) + "，选择操作，确认后才执行");
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(94), LayoutParams.WRAP_CONTENT);
                    params.leftMargin = dp(12);
                    entityRow.addView(operate, params);
                } else {
                    TextView readonly = text(entityAvailable(entity) ? "只读" : "不可用", 12, MUTED, false);
                    readonly.setPadding(dp(12), 0, 0, 0);
                    entityRow.addView(readonly, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                }
                box.addView(entityRow, matchWrap());
            }
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        allDevicesBody.addView(box, params);
    }

    private void chooseAction(JSONObject device, JSONObject entity, String revision) {
        if (touchBusy || getVisibility() != VISIBLE) return;
        cancelTouchAction();
        homeControls.dismissControlDialog();
        JSONArray actions = entity.optJSONArray("actions");
        if (actions == null || actions.length() == 0) return;
        ArrayList<JSONObject> choices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            choices.add(action);
            labels.add(nonempty(action.optString("label"), "设备操作"));
        }
        if (choices.isEmpty()) return;
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(targetLabel(device, entity))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    dismissActionDialog();
                    selectValue(device, entity, revision, choices.get(which));
                })
                .setNegativeButton("取消", null).create();
        showActionDialog(dialog);
    }

    private void selectValue(JSONObject device, JSONObject entity, String revision, JSONObject action) {
        String type = action.optString("value_type", "");
        String label = nonempty(action.optString("label"), "设备操作");
        if ("none".equals(type)) {
            confirmAction(device, entity, revision, action, null, "");
            return;
        }
        if ("number".equals(type)) {
            LinearLayout form = dialogForm();
            TextView explanation = text(numericHint(action), 14, MUTED, false);
            explanation.setPadding(0, 0, 0, dp(10));
            form.addView(explanation, matchWrap());
            EditText value = new EditText(getContext());
            value.setTextColor(INK);
            value.setHint("输入目标数值");
            value.setSingleLine(true);
            value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
            form.addView(value, matchWrap());
            AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(label)
                    .setView(form).setNegativeButton("取消", null).setPositiveButton("下一步", null).create();
            showActionDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                double selected;
                try {
                    selected = Double.parseDouble(value.getText().toString().trim());
                    if (Double.isNaN(selected) || Double.isInfinite(selected)) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    value.setError("请输入有效数值");
                    return;
                }
                if ((action.has("min") && selected < action.optDouble("min"))
                        || (action.has("max") && selected > action.optDouble("max"))) {
                    value.setError("请填写范围内的数值");
                    return;
                }
                // The native policy validates capability-specific steps and ranges again.
                confirmAction(device, entity, revision, action, selected,
                        preciseNumber(selected) + unitSuffix(action.optString("unit", "")));
            });
            return;
        }
        ArrayList<Object> values = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        if ("boolean".equals(type)) {
            values.add(Boolean.TRUE);
            values.add(Boolean.FALSE);
            String key = action.optString("action", "");
            labels.add("set_muted".equals(key) ? "静音" : "set_oscillating".equals(key) ? "开启摆风" : "开启");
            labels.add("set_muted".equals(key) ? "取消静音" : "set_oscillating".equals(key) ? "关闭摆风" : "关闭");
        } else if ("string".equals(type)) {
            JSONArray options = action.optJSONArray("options");
            if (options != null) {
                for (int i = 0; i < options.length(); i++) {
                    JSONObject option = options.optJSONObject(i);
                    if (option == null || !option.has("value")) continue;
                    values.add(option.optString("value"));
                    labels.add(nonempty(option.optString("label"), "选项 " + (i + 1)));
                }
            }
        }
        if (values.isEmpty()) {
            allDevicesFeedback.setText("这项操作暂未提供可选值，请刷新设备状态后重试。");
            dismissActionDialog();
            return;
        }
        int[] selected = {-1};
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(label)
                .setSingleChoiceItems(labels.toArray(new String[0]), -1, (d, which) -> {
                    selected[0] = which;
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                }).setNegativeButton("取消", null).setPositiveButton("下一步", null).create();
        showActionDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (selected[0] >= 0) confirmAction(device, entity, revision, action,
                    values.get(selected[0]), labels.get(selected[0]));
        });
    }

    private void confirmAction(JSONObject device, JSONObject entity, String revision,
            JSONObject action, Object value, String valueLabel) {
        if (touchBusy || getVisibility() != VISIBLE) return;
        final HomeDevices.PreparedAction prepared;
        try {
            prepared = homeDevices.prepare(entity.optString("ref", ""), revision,
                    action.optString("action", ""), value, "", false);
        } catch (HomeDevices.Failure error) {
            allDevicesFeedback.setText(errorMessage(error.code));
            dismissActionDialog();
            return;
        }
        dismissActionDialog();
        String description = nonempty(action.optString("label"), "设备操作");
        if (!valueLabel.isEmpty()) description += "：" + valueLabel;
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle("确认操作")
                .setMessage(targetLabel(device, entity) + "\n\n" + description)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认执行", (d, which) -> executeTouchAction(prepared)).create();
        showActionDialog(dialog);
    }

    private void executeTouchAction(HomeDevices.PreparedAction prepared) {
        if (touchBusy || getVisibility() != VISIBLE) return;
        AtomicBoolean authorization = new AtomicBoolean(true);
        touchAuthorization = authorization;
        touchBusy = true;
        allDevicesFeedback.setText("正在提交已确认的操作…");
        updateState(homeControls.getDashboardState(), environmentControls.getState());
        homeDevices.execute(prepared, authorization, result -> {
            if (touchAuthorization == authorization) touchAuthorization = null;
            touchBusy = false;
            if (authorization.get()) {
                allDevicesFeedback.setText(resultMessage(result));
            } else {
                allDevicesFeedback.setText("操作页面已关闭。请刷新设备状态，确认当前结果。");
            }
            authorization.set(false);
            updateState(homeControls.getDashboardState(), environmentControls.getState());
        });
    }

    private void showActionDialog(AlertDialog dialog) {
        dismissActionDialog();
        actionDialog = dialog;
        dialog.setOnDismissListener(d -> { if (actionDialog == dialog) actionDialog = null; });
        dialog.show();
    }

    private void dismissActionDialog() {
        AlertDialog previous = actionDialog;
        actionDialog = null;
        if (previous != null) previous.dismiss();
    }

    private void cancelTouchAction() {
        if (touchAuthorization != null) touchAuthorization.set(false);
        dismissActionDialog();
    }

    private LinearLayout dialogForm() {
        LinearLayout form = column();
        form.setPadding(dp(24), dp(12), dp(24), dp(8));
        return form;
    }

    private static String targetLabel(JSONObject device, JSONObject entity) {
        String name = nonempty(device.optString("name"), "家居设备");
        String entityName = nonempty(entity.optString("name"), name);
        String target = name.equals(entityName) ? name : join(name, entityName);
        return join(device.optString("room", ""), target);
    }

    private static boolean entityAvailable(JSONObject entity) {
        String state = entity.optString("state", "");
        return !state.isEmpty() && !"unavailable".equals(state) && !"unknown".equals(state);
    }

    private static String numericHint(JSONObject action) {
        String unit = unitSuffix(action.optString("unit", ""));
        String range;
        if (action.has("min") && action.has("max")) {
            range = "范围 " + preciseNumber(action.optDouble("min")) + " — " + preciseNumber(action.optDouble("max")) + unit;
        } else if (action.has("min")) {
            range = "最小值 " + preciseNumber(action.optDouble("min")) + unit;
        } else if (action.has("max")) {
            range = "最大值 " + preciseNumber(action.optDouble("max")) + unit;
        } else {
            range = "填写目标数值" + unit;
        }
        if (action.has("step") && action.optDouble("step") > 0) {
            range += "，每次增减 " + preciseNumber(action.optDouble("step")) + unit;
        }
        return range + "。下一步确认后才会执行。";
    }

    private static String unitSuffix(String unit) {
        return nonempty(unit, "").isEmpty() ? "" : " " + unit.trim();
    }

    private static String preciseNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—";
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String displayState(JSONObject entity) {
        String state = entity.optString("state", "");
        switch (state) {
            case "on": return "已开启";
            case "off": return "已关闭";
            case "unavailable": return "暂不可用";
            case "unknown": case "": return "状态未知";
            case "open": return "已打开";
            case "closed": return "已关闭";
            case "opening": return "正在打开";
            case "closing": return "正在关闭";
            case "cool": return "制冷";
            case "heat": return "制热";
            case "heat_cool": return "冷暖自动";
            case "dry": return "除湿";
            case "fan_only": return "送风";
            case "auto": return "自动";
            case "idle": return "待机";
            case "playing": return "播放中";
            case "paused": return "已暂停";
            case "cleaning": return "清扫中";
            case "docked": return "已回充";
            case "returning": return "返回中";
            case "unlocked": return "未锁定";
            case "locked": return "已锁定";
            case "detected": return "已检测到";
            case "not_detected": return "未检测到";
            default: return state + unitSuffix(entity.optString("unit", ""));
        }
    }

    private static String resultMessage(JSONObject result) {
        if ("result_unverified".equals(result.optString("operation", ""))) {
            return "操作结果尚未确认，请先刷新状态，避免重复操作。";
        }
        if (!result.optBoolean("ok", false)) return errorMessage(result.optString("error", "request_failed"));
        switch (result.optString("operation", "")) {
            case "target_state_observed": return "已观察到目标状态。";
            case "already_in_target_state": return "设备已处于目标状态，无需重复操作。";
            default: return "请求已提交，请刷新设备状态确认结果。";
        }
    }

    private static String errorMessage(String code) {
        switch (code) {
            case "not_ready": case "catalog_missing": case "not_imported":
                return "设备目录尚未就绪，完成接入后即可查看全部设备。";
            case "catalog_stale": return "设备信息已变化，请刷新后重新选择操作。";
            case "target_unavailable": return "设备暂不可用，请稍后刷新状态。";
            case "unsupported_action": return "设备暂不支持这项操作，请刷新后重新选择。";
            case "invalid_value": return "设置值不符合设备要求，请检查范围和调整步长。";
            case "ambiguous_target": return "无法确定操作目标，请重新选择具体设备。";
            case "authorization_expired": return "本次操作已取消或过期，请重新选择。";
            case "connection_changed": return "家居连接已变化，请刷新后重新选择操作。";
            case "intent_not_verified": return "无法确认这项操作，请重新选择设备和参数。";
            default: return "暂时无法确认设备结果，请刷新状态后查看。";
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int horizontal = Math.max(dp(18), (w - dp(1040)) / 2);
        content.setPadding(horizontal, dp(28), horizontal, dp(36));
        boolean nextWide = w >= dp(800);
        if (nextWide == wide) return;
        wide = nextWide;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams deviceParams = wide
                ? new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1)
                : matchWrap();
        LinearLayout.LayoutParams environmentParams = wide
                ? new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1)
                : matchWrap();
        environmentParams.leftMargin = wide ? dp(16) : 0;
        environmentParams.topMargin = wide ? 0 : dp(16);
        deviceCard.setLayoutParams(deviceParams);
        environmentCard.setLayoutParams(environmentParams);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(24), dp(23), dp(24), dp(24));
        view.setBackground(shape(SURFACE, LINE, 24));
        return view;
    }

    private TextView sectionLabel(String label) {
        TextView view = text(label, 13, MUTED, true);
        view.setLetterSpacing(.08f);
        markHeading(view);
        return view;
    }

    private TextView text(String label, int size, int color, boolean medium) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(getContext());
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setMinHeight(dp(50));
        button.setMinimumHeight(dp(50));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setElevation(0);
        button.setStateListAnimator(null);
        GradientDrawable background = shape(primary ? ACCENT : 0xFFF4ECDF, primary ? ACCENT : LINE, 14);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x22FFFFFF : 0x16765A37),
                background, shape(Color.WHITE, Color.WHITE, 14)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), border);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void divider(LinearLayout parent, int spacing) {
        View line = new View(getContext());
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(spacing);
        params.bottomMargin = dp(spacing);
        parent.addView(line, params);
    }

    private static void markHeading(TextView view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private static void enabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .45f);
    }

    private static String nonempty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String join(String first, String second) {
        String a = nonempty(first, ""), b = nonempty(second, "");
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    private static String indoorSource(EnvironmentControls.State state) {
        String a = nonempty(state.temperatureSource, "");
        String b = nonempty(state.humiditySource, "");
        if (a.equals(b)) return a;
        String temperatureDevice = a.replaceFirst("(?i)[ ·_-]*(温度|temperature)$", "").trim();
        String humidityDevice = b.replaceFirst("(?i)[ ·_-]*(湿度|humidity)$", "").trim();
        if (!temperatureDevice.isEmpty() && temperatureDevice.equals(humidityDevice)) return temperatureDevice;
        return join(a, b);
    }

    private static String number(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return "—";
        return String.format(Locale.CHINA, "%.1f", value).replaceFirst("\\.0$", "");
    }

    private static String humidity(Double value) {
        return value == null || value < 0 || value > 100 ? "—" : number(value);
    }

    private static String condition(String value) {
        switch (nonempty(value, "")) {
            case "sunny": return "晴";
            case "clear-night": return "晴朗夜晚";
            case "partlycloudy": return "多云";
            case "cloudy": return "阴";
            case "rainy": return "雨";
            case "pouring": return "大雨";
            case "lightning": return "雷暴";
            case "lightning-rainy": return "雷阵雨";
            case "snowy": return "雪";
            case "snowy-rainy": return "雨夹雪";
            case "hail": return "冰雹";
            case "fog": return "雾";
            case "windy": return "有风";
            case "windy-variant": return "风大多云";
            case "exceptional": return "异常天气";
            default: return "天气状态未知";
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
