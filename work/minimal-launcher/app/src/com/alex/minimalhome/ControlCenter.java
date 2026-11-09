package com.alex.minimalhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
    private static final int OLIVE = 0xFF596447;
    private static final int OLIVE_PAPER = 0xFFE9EBDD;
    private static final int CHIP = 0xFFF1E9DB;
    private static final String ALL_ROOMS = "";
    private static final List<String> ROOM_ORDER = Arrays.asList("客厅", "主卧", "次卧", "书房", "厨房", "阳台");

    private final HomeControls homeControls;
    private final HomeDevices homeDevices;
    private final EnvironmentControls environmentControls;
    private final ScrollView scroll;
    private final LinearLayout devicePane;
    private final ScrollView roomRail;
    private final HorizontalScrollView roomStrip;
    private final LinearLayout roomRows;
    private final LinearLayout roomChips;
    private final TextView sectionTitle;
    private final LinearLayout environmentCard;
    private final TextView indoorReading;
    private final TextView indoorSource;
    private final TextView weatherReading;
    private final TextView weatherSource;
    private final Button environmentRefresh;
    private final LinearLayout allDevicesBody;
    private final TextView allDevicesSummary;
    private final TextView allDevicesFeedback;
    private final Button allDevicesRefresh;
    private AlertDialog actionDialog;
    private AtomicBoolean touchAuthorization;
    private boolean touchBusy;
    private boolean catalogRefreshing;
    private boolean wide;
    private int gridColumns = 1;
    // null is the favorites view; an empty key represents all rooms.
    private String selectedRoom;
    private HomeDevices.Snapshot renderedSnapshot;
    private JSONObject fullCatalog;
    private String renderedRoom;
    private boolean renderedWide;
    private int renderedColumns;
    private boolean renderedBlocked;
    private String navigationSignature = "";
    private String actionControlId = "";
    private String actionStatus = "";
    private String renderedActionControlId = "";
    private String renderedActionStatus = "";
    private final Runnable clearActionStatus = () -> {
        actionControlId = "";
        actionStatus = "";
        refreshDevices();
    };

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

        LinearLayout page = column();
        addView(page, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = row();
        header.setPadding(dp(24), dp(13), dp(24), dp(13));
        header.setBackgroundColor(SURFACE);
        LinearLayout heading = column();
        TextView eyebrow = text("我的家", 12, OLIVE, true);
        heading.addView(eyebrow, matchWrap());
        TextView title = text("控制中心", 27, INK, true);
        markHeading(title);
        heading.addView(title, matchWrap());
        header.addView(heading, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addHeaderButton(header, "环境", this::showEnvironment);
        addHeaderButton(header, "设置", openHomeSettings).setContentDescription("连接与桌面设置");
        addHeaderButton(header, "返回首页", () -> { hide(); close.run(); });
        page.addView(header, matchWrap());
        divider(page, 0);

        roomStrip = new HorizontalScrollView(activity);
        roomStrip.setHorizontalScrollBarEnabled(false);
        roomStrip.setClipToPadding(false);
        roomStrip.setPadding(dp(18), dp(10), dp(18), dp(10));
        roomChips = row();
        roomStrip.addView(roomChips);
        page.addView(roomStrip, matchWrap());

        LinearLayout workspace = row();
        workspace.setGravity(Gravity.TOP);
        page.addView(workspace, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        roomRail = new ScrollView(activity);
        roomRail.setFillViewport(true);
        roomRail.setVerticalScrollBarEnabled(false);
        roomRail.setBackgroundColor(0xFFF3EADD);
        roomRows = column();
        roomRows.setPadding(dp(16), dp(18), dp(16), dp(20));
        roomRail.addView(roomRows);
        workspace.addView(roomRail, new LinearLayout.LayoutParams(dp(184), LayoutParams.MATCH_PARENT));
        roomRail.setVisibility(GONE);

        devicePane = column();
        devicePane.setPadding(dp(20), dp(16), dp(20), 0);
        workspace.addView(devicePane, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1));

        environmentCard = card();
        TextView indoorLabel = text("室内温湿度", 15, INK, true);
        indoorLabel.setPadding(0, dp(17), 0, dp(8));
        environmentCard.addView(indoorLabel, matchWrap());
        indoorReading = text("— °C   /   — %", 26, INK, false);
        environmentCard.addView(indoorReading, matchWrap());
        indoorSource = text("等待传感器数据", 13, MUTED, false);
        indoorSource.setPadding(0, dp(7), 0, dp(10));
        environmentCard.addView(indoorSource, matchWrap());
        environmentCard.addView(button("温湿度来源详情", false, () -> { dismissActionDialog(); showIndoorDetails.run(); }), matchWrap());
        divider(environmentCard, 19);
        environmentCard.addView(text("室外天气", 15, INK, true), matchWrap());
        weatherReading = text("— °C", 26, INK, false);
        weatherReading.setPadding(0, dp(8), 0, 0);
        environmentCard.addView(weatherReading, matchWrap());
        weatherSource = text("等待天气数据", 13, MUTED, false);
        weatherSource.setPadding(0, dp(7), 0, dp(10));
        environmentCard.addView(weatherSource, matchWrap());
        environmentCard.addView(button("天气来源详情", false, () -> { dismissActionDialog(); showWeatherDetails.run(); }), matchWrap());
        divider(environmentCard, 19);
        environmentRefresh = button("刷新环境数据", false, environmentControls::refresh);
        environmentCard.addView(environmentRefresh, matchWrap());
        Button sourceSettings = button("温湿度与天气设置", false, () -> { dismissActionDialog(); environmentControls.showSettingsDialog(); });
        LinearLayout.LayoutParams sourceParams = matchWrap();
        sourceParams.topMargin = dp(8);
        environmentCard.addView(sourceSettings, sourceParams);

        LinearLayout catalogHeader = row();
        LinearLayout catalogHeading = column();
        sectionTitle = text("常用设备", 23, INK, true);
        markHeading(sectionTitle);
        catalogHeading.addView(sectionTitle, matchWrap());
        allDevicesSummary = text("等待读取设备", 13, MUTED, false);
        allDevicesSummary.setPadding(0, dp(3), 0, 0);
        catalogHeading.addView(allDevicesSummary, matchWrap());
        catalogHeader.addView(catalogHeading,
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        allDevicesRefresh = button("刷新", false, this::refreshCatalog);
        allDevicesRefresh.setContentDescription("刷新");
        catalogHeader.addView(allDevicesRefresh, new LinearLayout.LayoutParams(dp(76), dp(48)));
        devicePane.addView(catalogHeader, matchWrap());

        allDevicesFeedback = text("", 13, MUTED, false);
        allDevicesFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        allDevicesFeedback.setPadding(0, dp(8), 0, 0);
        allDevicesFeedback.setVisibility(GONE);
        devicePane.addView(allDevicesFeedback, matchWrap());

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        devicePane.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        allDevicesBody = column();
        allDevicesBody.setPadding(0, dp(14), 0, dp(20));
        scroll.addView(allDevicesBody, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        updateState(homeControls.getDashboardState(), environmentControls.getState());
    }

    public void show() {
        setVisibility(VISIBLE);
        requestFocus();
        updateState(homeControls.getDashboardState(), environmentControls.getState());
        scroll.scrollTo(0, 0);
    }

    private Button addHeaderButton(LinearLayout header, String label, Runnable action) {
        Button control = button(label, false, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48));
        params.leftMargin = dp(8);
        header.addView(control, params);
        return control;
    }

    private void showEnvironment() {
        dismissActionDialog();
        ViewGroup parent = (ViewGroup) environmentCard.getParent();
        if (parent != null) parent.removeView(environmentCard);
        ScrollView pane = new ScrollView(getContext());
        pane.addView(environmentCard);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle("环境")
                .setView(pane).setPositiveButton("关闭", null).create();
        showActionDialog(dialog);
    }

    public void hide() {
        cancelTouchAction();
        homeControls.dismissControlDialog();
        setVisibility(GONE);
    }

    /** Main-thread snapshots only; this view registers no observers and performs no polling. */
    public void updateState(HomeControls.DashboardState device, EnvironmentControls.State environment) {
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

    /** Rendering uses local snapshots. Refresh remains an explicit read-only action. */
    public void refreshDevices() {
        if (getVisibility() != VISIBLE) return;
        HomeDevices.Snapshot snapshot = homeDevices.snapshot();
        boolean blocked = controlsBlocked();
        enabled(allDevicesRefresh, !blocked);
        allDevicesRefresh.setText(catalogRefreshing ? "刷新中" : "刷新");
        if (snapshot == renderedSnapshot && Objects.equals(selectedRoom, renderedRoom) && wide == renderedWide
                && gridColumns == renderedColumns && blocked == renderedBlocked
                && actionStatus.equals(renderedActionStatus)
                && actionControlId.equals(renderedActionControlId)) return;
        if (snapshot != renderedSnapshot) fullCatalog = snapshot.controlCenterCatalog();
        renderedSnapshot = snapshot;
        renderedRoom = selectedRoom;
        renderedWide = wide;
        renderedColumns = gridColumns;
        renderedBlocked = blocked;
        renderedActionStatus = actionStatus;
        renderedActionControlId = actionControlId;

        List<JSONObject> all = objects(fullCatalog.optJSONArray("devices"));
        Map<String, Integer> rooms = new LinkedHashMap<>();
        int favorites = 0;
        for (JSONObject device : all) {
            String room = roomOf(device);
            rooms.put(room, rooms.getOrDefault(room, 0) + 1);
            if (DashboardDevices.favoriteIndex(device) >= 0) favorites++;
        }
        renderNavigation(rooms, favorites, all.size());
        allDevicesBody.removeAllViews();
        sectionTitle.setText(selectionLabel());
        if (!fullCatalog.optBoolean("ok", false)) {
            allDevicesSummary.setText("设备状态暂不可用");
            addCatalogNote(errorMessage(fullCatalog.optString("error", "not_ready")));
            return;
        }
        List<JSONObject> shown = new ArrayList<>();
        if (selectedRoom == null) {
            for (JSONObject device : all) if (DashboardDevices.favoriteIndex(device) >= 0) shown.add(device);
            shown.sort(Comparator.comparingInt(DashboardDevices::favoriteIndex));
        } else {
            for (JSONObject device : all) {
                if (ALL_ROOMS.equals(selectedRoom) || selectedRoom.equals(roomOf(device))) shown.add(device);
            }
            shown.sort(Comparator.comparingInt(device -> {
                int index = DashboardDevices.favoriteIndex(device);
                return index < 0 ? Integer.MAX_VALUE : index;
            }));
        }
        int available = 0;
        for (JSONObject device : shown) if (device.optBoolean("available")) available++;
        allDevicesSummary.setText(shown.size() + " 台设备 · " + available + " 台可用");
        if (shown.isEmpty()) {
            addCatalogNote(selectedRoom == null ? "常用设备还没有接入，点「全部设备」查看目录。" : "这个房间暂时没有设备。");
            return;
        }
        String revision = fullCatalog.optString("catalog_revision");
        if (ALL_ROOMS.equals(selectedRoom)) {
            for (String room : orderedRooms(rooms)) {
                List<JSONObject> group = new ArrayList<>();
                for (JSONObject device : shown) if (room.equals(roomOf(device))) group.add(device);
                TextView label = sectionLabel(room);
                label.setPadding(0, dp(8), 0, dp(9));
                allDevicesBody.addView(label, matchWrap());
                addGrid(group, revision, blocked);
            }
        } else {
            addGrid(shown, revision, blocked);
        }
    }

    private boolean controlsBlocked() {
        HomeControls.DashboardState home = homeControls.getDashboardState();
        return touchBusy || catalogRefreshing || (home != null && home.busy);
    }

    private String selectionLabel() {
        if (selectedRoom == null) return "常用设备";
        return ALL_ROOMS.equals(selectedRoom) ? "全部设备" : selectedRoom;
    }

    private void renderNavigation(Map<String, Integer> rooms, int favorites, int total) {
        String signature = rooms + "|" + favorites + "|" + total + "|" + selectedRoom + "|" + wide;
        if (signature.equals(navigationSignature)) return;
        navigationSignature = signature;
        LinearLayout nav = wide ? roomRows : roomChips;
        nav.removeAllViews();
        if (wide) addNavHeading(nav, "设备");
        addRoomButton(nav, null, "常用", favorites);
        addRoomButton(nav, ALL_ROOMS, "全部设备", total);
        if (wide) addNavHeading(nav, "房间");
        for (String room : orderedRooms(rooms)) {
            addRoomButton(nav, room, room, rooms.get(room));
        }
    }

    private static List<String> orderedRooms(Map<String, Integer> rooms) {
        List<String> ordered = new ArrayList<>();
        for (String room : ROOM_ORDER) if (rooms.containsKey(room)) ordered.add(room);
        for (String room : rooms.keySet()) if (!ordered.contains(room)) ordered.add(room);
        return ordered;
    }

    private void addNavHeading(LinearLayout nav, String label) {
        TextView heading = sectionLabel(label);
        heading.setPadding(dp(10), dp(7), 0, dp(8));
        nav.addView(heading, matchWrap());
    }

    private void addRoomButton(LinearLayout nav, String room, String label, int count) {
        boolean selected = Objects.equals(room, selectedRoom);
        Button item = button(label + "  " + count, false, () -> {
            selectedRoom = room;
            scroll.scrollTo(0, 0);
            if (!touchBusy && !catalogRefreshing) setFeedback("");
            refreshDevices();
        });
        item.setTextColor(selected ? OLIVE : INK);
        item.setSelected(selected);
        item.setContentDescription(label + "，" + count + " 台设备");
        item.setGravity(wide ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22646C4B),
                shape(selected ? OLIVE_PAPER : wide ? 0xFFF3EADD : SURFACE,
                        selected ? 0xFFD3D8BF : wide ? 0xFFF3EADD : LINE, 12), null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                wide ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT, dp(48));
        if (wide) params.bottomMargin = dp(3); else params.rightMargin = dp(8);
        nav.addView(item, params);
    }

    private void addGrid(List<JSONObject> devices, String revision, boolean blocked) {
        int columns = gridColumns;
        for (int index = 0; index < devices.size(); index += columns) {
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                int current = index + column;
                View cell = current < devices.size() ? deviceCard(devices.get(current), revision, blocked) : new View(getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
                if (column != 0) params.leftMargin = dp(12);
                line.addView(cell, params);
            }
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(12);
            allDevicesBody.addView(line, params);
        }
    }

    private void refreshCatalog() {
        if (controlsBlocked()) return;
        catalogRefreshing = true;
        setFeedback("正在读取设备状态…");
        refreshDevices();
        homeDevices.refresh(result -> {
            catalogRefreshing = false;
            setFeedback(result.optBoolean("ok", false) ? "设备状态已更新"
                    : errorMessage(result.optString("error", "request_failed")));
            refreshDevices();
        });
    }

    private void setFeedback(String value) {
        allDevicesFeedback.setText(value);
        allDevicesFeedback.setVisibility(value.isEmpty() ? GONE : VISIBLE);
    }

    private void addCatalogNote(String note) {
        LinearLayout empty = card();
        empty.addView(text(note, 15, MUTED, false), matchWrap());
        allDevicesBody.addView(empty, matchWrap());
    }

    private LinearLayout deviceCard(JSONObject device, String revision, boolean blocked) {
        LinearLayout box = column();
        box.setPadding(dp(15), dp(13), dp(15), dp(13));
        box.setMinimumHeight(dp(142));
        box.setBackground(shape(SURFACE, LINE, 17));
        String name = nonempty(device.optString("name"), "家居设备");
        List<JSONObject> entities = objects(device.optJSONArray("entities"));
        boolean oven = "烤箱插座".equals(name);
        JSONObject primary = oven ? mainPowerEntity(device) : primaryEntity(device, entities);
        String domain = primary == null ? "" : primary.optString("domain");
        boolean available = device.optBoolean("available") && (!oven || primary != null && entityAvailable(primary));
        boolean cover = "cover".equals(domain);
        boolean tmallCover = cover && device.optString("model").contains("天猫精灵桥接");
        boolean controllable = available && entities.stream().anyMatch(ControlCenter::hasActions);

        LinearLayout heading = row();
        heading.addView(deviceIcon(device, domain), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout titleBlock = column();
        titleBlock.setPadding(dp(11), 0, dp(8), 0);
        TextView title = text(name, 17, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleBlock.addView(title, matchWrap());
        titleBlock.addView(text(roomOf(device), 12, MUTED, false), matchWrap());
        heading.addView(titleBlock, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView badge = text(!available ? "暂不可用" : controllable ? "可操作" : "只读", 12, available ? OLIVE : MUTED, true);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setBackground(shape(available ? OLIVE_PAPER : CHIP, available ? OLIVE_PAPER : CHIP, 11));
        heading.addView(badge);
        box.addView(heading, matchWrap());

        String summary = summaryReading(device, primary, entities, available, tmallCover);
        TextView reading = text(summary, 15, available ? INK : MUTED, false);
        reading.setMaxLines(2);
        reading.setEllipsize(TextUtils.TruncateAt.END);
        reading.setPadding(0, dp(9), 0, dp(9));
        box.addView(reading, matchWrap());
        LinearLayout actions = row();
        boolean ready = available && !blocked && primary != null && entityAvailable(primary);
        if (cover || oven) {
            addQuickButton(actions, device, primary, revision, cover ? "open" : "power_on", cover ? "打开" : "开启", ready, true);
            addQuickButton(actions, device, primary, revision, cover ? "close" : "power_off", "关闭", ready, false);
            if (tmallCover && hasActions(primary)) {
                String controlId = primary.optString("ref") + ":more";
                Button more = button("操作…", false, () -> chooseAction(device, primary, revision, controlId));
                more.setContentDescription(targetLabel(device, primary) + "，选择其他操作");
                enabled(more, ready);
                bindActionStatus(more, controlId);
                addCardButton(actions, more, false);
            } else if (cover && !tmallCover) {
                addDetailsButton(actions, device, revision, blocked, false);
            }
        } else {
            // The full list remains behind Details for ambiguous or multi-function devices.
            if (primary != null && ("light".equals(domain) || "climate".equals(domain) || "switch".equals(domain))) {
                boolean on = catalogAction(primary, "power_on") != null;
                boolean off = catalogAction(primary, "power_off") != null;
                if (on) addQuickButton(actions, device, primary, revision, "power_on", "开启", ready, true);
                if (off) addQuickButton(actions, device, primary, revision, "power_off", "关闭", ready, !on);
            }
            addDetailsButton(actions, device, revision, blocked, false);
        }
        box.addView(actions, matchWrap());
        return box;
    }

    private ImageView deviceIcon(JSONObject device, String domain) {
        String name = device.optString("name");
        String asset;
        switch (domain) {
            case "cover": asset = name.contains("晾衣") ? "hanger" : "curtain"; break;
            case "light": asset = "bulb"; break;
            case "climate": asset = "air_conditioning"; break;
            case "switch": asset = "plug"; break;
            case "sensor": asset = "thermometer"; break;
            default: asset = name.contains("灯") ? "bulb" : "adjustments_horizontal";
        }
        ImageView icon = new ImageView(getContext());
        int resource = getResources().getIdentifier("home_" + asset, "drawable", getContext().getPackageName());
        icon.setImageResource(resource);
        icon.setColorFilter(ACCENT);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(shape(CHIP, CHIP, 11));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static JSONObject primaryEntity(JSONObject device, List<JSONObject> entities) {
        String name = device.optString("name");
        for (String domain : new String[]{"cover", "climate", "light", "switch", "binary_sensor", "sensor"}) {
            if ("switch".equals(domain) && (name.contains("网关") || name.contains("总线") || name.contains("控制器"))) continue;
            JSONObject unique = null;
            JSONObject named = null;
            int count = 0;
            int namedCount = 0;
            for (JSONObject entity : entities) {
                if (!normal(entity) || !domain.equals(entity.optString("domain"))) continue;
                unique = entity;
                count++;
                if (name.equals(entity.optString("name"))) {
                    named = entity;
                    namedCount++;
                }
            }
            if (namedCount == 1) return named;
            if (count == 1) return unique;
            if (count > 1 && !"sensor".equals(domain)) return null;
        }
        return null;
    }

    private static String summaryReading(JSONObject device, JSONObject primary, List<JSONObject> entities,
            boolean available, boolean tmallCover) {
        if (!available) return "等待设备状态";
        if (tmallCover) return "已接入";
        if (primary != null) return displayState(primary);
        if (device.optString("name").contains("温湿度")) {
            String temperature = "";
            String humidity = "";
            for (JSONObject entity : entities) {
                if (!normal(entity) || !entityAvailable(entity)) continue;
                String label = entity.optString("name");
                if (label.endsWith("温度")) temperature = displayState(entity);
                if (label.endsWith("湿度")) humidity = displayState(entity);
            }
            String reading = join(temperature, humidity);
            if (!reading.isEmpty()) return reading;
        }
        return entities.stream().anyMatch(ControlCenter::hasActions) ? "可查看设备功能" : "可查看设备状态";
    }

    private void addQuickButton(LinearLayout row, JSONObject device, JSONObject entity, String revision,
            String key, String label, boolean ready, boolean emphasized) {
        JSONObject action = entity == null ? null : catalogAction(entity, key);
        String controlId = entity == null ? "" : entity.optString("ref") + ":" + key;
        Button control = button(label, emphasized, () -> chooseDirectAction(device, entity, revision, action, controlId));
        enabled(control, ready && action != null);
        String target = entity == null ? join(roomOf(device), device.optString("name")) : targetLabel(device, entity);
        control.setContentDescription(target + "，" + label + "，点击执行");
        bindActionStatus(control, controlId);
        addCardButton(row, control, true);
    }

    private void addCardButton(LinearLayout row, Button button, boolean fill) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fill ? 0 : LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, fill ? 1 : 0);
        if (row.getChildCount() > 0) params.leftMargin = dp(7);
        row.addView(button, params);
    }

    private void addDetailsButton(LinearLayout row, JSONObject device, String revision, boolean blocked, boolean fill) {
        Button details = button("详情", false, () -> showDeviceDetails(device, revision));
        details.setContentDescription(device.optString("name") + "，设备详情");
        enabled(details, !blocked);
        bindActionStatus(details, device.optString("id") + ":details");
        addCardButton(row, details, fill);
    }

    private void showDeviceDetails(JSONObject device, String revision) {
        LinearLayout content = dialogForm();
        List<JSONObject> entries = objects(device.optJSONArray("entities"));
        entries.sort(Comparator.comparing(entity -> !hasActions(entity)));
        LinearLayout readings = column();
        LinearLayout parameters = column();
        for (JSONObject entity : entries) {
            LinearLayout target = normal(entity) ? readings : parameters;
            addDetailRow(target, device, entity, revision);
        }
        TextView room = text(roomOf(device), 13, MUTED, false);
        room.setPadding(0, 0, 0, dp(8));
        content.addView(room, matchWrap());
        if (readings.getChildCount() == 0) readings.addView(text("暂无可读状态", 14, MUTED, false), matchWrap());
        ScrollView body = new ScrollView(getContext());
        body.addView(readings);
        if (parameters.getChildCount() > 0) {
            LinearLayout tabs = row();
            Button stateTab = button("状态与操作", false, () -> {});
            Button parametersTab = button("参数与诊断", false, () -> {});
            addCardButton(tabs, stateTab, true);
            addCardButton(tabs, parametersTab, true);
            stateTab.setOnClickListener(v -> showDetailTab(body, readings, stateTab, parametersTab));
            parametersTab.setOnClickListener(v -> showDetailTab(body, parameters, parametersTab, stateTab));
            showDetailTab(body, readings, stateTab, parametersTab);
            LinearLayout.LayoutParams params = matchWrap();
            params.bottomMargin = dp(12);
            content.addView(tabs, params);
        }
        content.addView(body, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(nonempty(device.optString("name"), "设备详情")).setView(content)
                .setPositiveButton("关闭", null).create();
        showActionDialog(dialog);
    }

    private void showDetailTab(ScrollView body, View page, Button selected, Button other) {
        if (body.getChildAt(0) != page) {
            body.removeAllViews();
            body.addView(page);
        }
        body.scrollTo(0, 0);
        selected.setSelected(true);
        selected.setTextColor(OLIVE);
        selected.setBackground(shape(OLIVE_PAPER, 0xFFD3D8BF, 12));
        other.setSelected(false);
        other.setTextColor(INK);
        other.setBackground(shape(CHIP, LINE, 12));
    }

    private void addDetailRow(LinearLayout target, JSONObject device, JSONObject entity, String revision) {
        if (target.getChildCount() > 0) divider(target, 8);
        LinearLayout line = row();
        LinearLayout labels = column();
        labels.addView(text(nonempty(entity.optString("name"), "状态"), 14, INK, true), matchWrap());
        String state = "cover".equals(entity.optString("domain")) && device.optString("model").contains("天猫精灵桥接")
                ? entityAvailable(entity) ? "已接入" : "暂不可用" : displayState(entity);
        labels.addView(text(state, 13, MUTED, false), matchWrap());
        line.addView(labels, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        if (hasActions(entity)) {
            String controlId = device.optString("id") + ":details";
            Button operate = button("操作…", false, () -> chooseAction(device, entity, revision, controlId));
            operate.setContentDescription(targetLabel(device, entity) + "，选择操作");
            enabled(operate, entityAvailable(entity) && !controlsBlocked());
            addCardButton(line, operate, false);
        }
        target.addView(line, matchWrap());
    }

    private static boolean normal(JSONObject entity) {
        return "normal".equals(entity.optString("category"));
    }

    private static boolean hasActions(JSONObject entity) {
        JSONArray actions = entity.optJSONArray("actions");
        return actions != null && actions.length() > 0;
    }

    private static List<JSONObject> objects(JSONArray array) {
        List<JSONObject> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) result.add(value);
        }
        return result;
    }

    private static String roomOf(JSONObject device) {
        return nonempty(device.optString("room"), "未分配房间");
    }

    private static JSONObject catalogAction(JSONObject entity, String key) {
        JSONArray actions = entity.optJSONArray("actions");
        if (actions != null) for (int index = 0; index < actions.length(); index++) {
            JSONObject action = actions.optJSONObject(index);
            if (action != null && key.equals(action.optString("action", ""))
                    && "none".equals(action.optString("value_type", ""))) return action;
        }
        return null;
    }

    private void chooseDirectAction(JSONObject device, JSONObject entity, String revision, JSONObject action, String controlId) {
        if (action == null || touchBusy || getVisibility() != VISIBLE) return;
        cancelTouchAction();
        homeControls.dismissControlDialog();
        submitAction(entity, revision, action, null, controlId);
    }

    private static JSONObject mainPowerEntity(JSONObject device) {
        JSONArray entities = device.optJSONArray("entities");
        JSONObject selected = null;
        if (entities != null) for (int index = 0; index < entities.length(); index++) {
            JSONObject entity = entities.optJSONObject(index);
            if (entity == null || !normal(entity) || !"switch".equals(entity.optString("domain", ""))
                    || !device.optString("name", "").equals(entity.optString("name", ""))) continue;
            if (selected != null) return null;
            selected = entity;
        }
        return selected;
    }

    private void chooseAction(JSONObject device, JSONObject entity, String revision, String controlId) {
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
                    selectValue(entity, revision, choices.get(which), controlId);
                })
                .setNegativeButton("取消", null).create();
        showActionDialog(dialog);
    }

    private void selectValue(JSONObject entity, String revision, JSONObject action, String controlId) {
        String type = action.optString("value_type", "");
        String label = nonempty(action.optString("label"), "设备操作");
        if ("none".equals(type)) {
            submitAction(entity, revision, action, null, controlId);
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
                    .setView(form).setNegativeButton("取消", null).setPositiveButton("执行", null).create();
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
                String error = submitAction(entity, revision, action, selected, controlId);
                if (error != null) value.setError(error);
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
            setFeedback("这项操作暂未提供可选值，请刷新设备状态后重试。");
            dismissActionDialog();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(label)
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        submitAction(entity, revision, action, values.get(which), controlId))
                .setNegativeButton("取消", null).create();
        showActionDialog(dialog);
    }

    private String submitAction(JSONObject entity, String revision, JSONObject action, Object value, String controlId) {
        if (touchBusy || getVisibility() != VISIBLE) return "另一个操作正在处理中，请稍后再试。";
        final HomeDevices.PreparedAction prepared;
        try {
            prepared = homeDevices.prepare(entity.optString("ref", ""), revision,
                    action.optString("action", ""), value, "", false);
        } catch (HomeDevices.Failure error) {
            String message = errorMessage(error.code);
            showActionOutcome(controlId, "失败", message);
            return message;
        }
        dismissActionDialog();
        executeTouchAction(prepared, controlId);
        return null;
    }

    private void executeTouchAction(HomeDevices.PreparedAction prepared, String controlId) {
        if (touchBusy || getVisibility() != VISIBLE) return;
        AtomicBoolean authorization = new AtomicBoolean(true);
        touchAuthorization = authorization;
        touchBusy = true;
        removeCallbacks(clearActionStatus);
        actionControlId = controlId;
        actionStatus = "处理中…";
        setFeedback("正在处理设备操作…");
        updateState(homeControls.getDashboardState(), environmentControls.getState());
        homeDevices.execute(prepared, authorization, result -> {
            if (touchAuthorization != authorization) return;
            touchAuthorization = null;
            touchBusy = false;
            if (authorization.get()) {
                showActionOutcome(controlId, actionResultStatus(result), resultMessage(result));
            } else {
                showActionOutcome(controlId, "未确认", "操作页面已关闭。请刷新设备状态，确认当前结果。");
            }
            authorization.set(false);
            updateState(homeControls.getDashboardState(), environmentControls.getState());
        });
    }

    private void bindActionStatus(Button control, String controlId) {
        if (controlId.isEmpty() || !controlId.equals(actionControlId) || actionStatus.isEmpty()) return;
        control.setText(actionStatus);
        control.setContentDescription(control.getContentDescription() + "，" + actionStatus);
        control.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        enabled(control, false);
        control.setAlpha(1f);
    }

    private void showActionOutcome(String controlId, String status, String message) {
        actionControlId = controlId;
        actionStatus = status;
        setFeedback(message);
        refreshDevices();
        removeCallbacks(clearActionStatus);
        postDelayed(clearActionStatus, 4000);
    }

    private static String actionResultStatus(JSONObject result) {
        if ("result_unverified".equals(result.optString("operation"))) return "未确认";
        if (!result.optBoolean("ok")) return "失败";
        String operation = result.optString("operation");
        return "target_state_observed".equals(operation) || "already_in_target_state".equals(operation)
                ? "已完成" : "已发送";
    }

    private void showActionDialog(AlertDialog dialog) {
        dismissActionDialog();
        actionDialog = dialog;
        dialog.setOnDismissListener(d -> { if (actionDialog == dialog) actionDialog = null; });
        dialog.show();
        HomeStyle.dialog(dialog);
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
        return range + "，填写后点击执行。";
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
        boolean nextWide = w >= dp(860);
        int nextColumns = w >= dp(640) ? 2 : 1;
        if (nextWide == wide && nextColumns == gridColumns) return;
        wide = nextWide;
        gridColumns = nextColumns;
        roomRail.setVisibility(wide ? VISIBLE : GONE);
        roomStrip.setVisibility(wide ? GONE : VISIBLE);
        int gutter = dp(wide ? 24 : 18);
        devicePane.setPadding(gutter, dp(16), gutter, 0);
        post(this::refreshDevices);
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
