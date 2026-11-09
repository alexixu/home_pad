package com.alex.minimalhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Display-only favorites. An account-visible name never implies an imported device. */
final class DashboardDevices {
    static final class Item {
        final String name, room, icon, status, subtitle;
        final boolean active;
        Item(String name, String room, String icon, String status, String subtitle, boolean active) {
            this.name = name; this.room = room; this.icon = icon;
            this.status = status; this.subtitle = subtitle; this.active = active;
        }
        String description() { return room + "，" + name + "，" + status
                + (subtitle.equals(room) ? "" : "，" + subtitle); }
    }

    // Shared favorite order. Curtain controls stay in Control Center; home shows readable states.
    private static final String[][] FAVORITES = {
        {"烤箱插座", "客厅", "plug", "烤箱插座"},
        {"灯具总开关", "客厅", "plug", "灯具总线"},
        {"主卧窗帘", "主卧", "curtain", "主卧窗帘"},
        {"主卧窗纱", "主卧", "curtain", "主卧窗纱"},
        {"客厅空调", "客厅", "air_conditioning", "客厅空调"},
        {"主卧空调", "主卧", "air_conditioning", "主卧空调"},
        {"次卧空调", "次卧", "air_conditioning", "次卧空调"},
        {"书房空调", "书房", "air_conditioning", "书房空调"},
        {"客厅大灯", "客厅", "bulb", "客厅大灯"},
        {"智能晾衣架", "阳台", "hanger", "卡瑞琪ProS1智能晾衣架", "智能晾衣架"}
    };
    static final int MAX_ITEMS = FAVORITES.length;

    static int favoriteIndex(JSONObject device) {
        for (int index = 0; index < FAVORITES.length; index++) {
            if (matches(device, FAVORITES[index])) return index;
        }
        return -1;
    }

    private static boolean matches(JSONObject device, String[] favorite) {
        if (device == null) return false;
        String room = device.optString("room", "");
        if (!room.isEmpty() && !room.equals(favorite[1])) return false;
        String name = device.optString("name", "");
        for (int alias = 3; alias < favorite.length; alias++) {
            if (name.equals(favorite[alias])) return true;
        }
        return false;
    }

    static List<Item> summarize(JSONObject catalog, HomeControls.DashboardState plug) {
        List<Item> result = new ArrayList<>();
        JSONArray devices = catalog == null ? null : catalog.optJSONArray("devices");
        for (int slot = 0; slot < FAVORITES.length; slot++) {
            String[] favorite = FAVORITES[slot];
            if (favorite[2].equals("curtain")) continue;
            JSONObject matched = null;
            if (devices != null) for (int i = 0; i < devices.length(); i++) {
                JSONObject candidate = devices.optJSONObject(i);
                if (matches(candidate, favorite)) matched = candidate;
            }
            String status = "";
            String subtitle = favorite[1];
            boolean active = false;
            boolean online = false;
            if (matched != null && catalog.optBoolean("ok", false) && matched.optBoolean("available", false)) {
                JSONArray entities = matched.optJSONArray("entities");
                if (entities != null) for (int i = 0; i < entities.length(); i++) {
                    JSONObject entity = entities.optJSONObject(i);
                    if (entity == null) continue;
                    String domain = entity.optString("domain", "");
                    boolean relevant = favorite[2].equals("bulb") ? domain.equals("light")
                            : favorite[2].equals("air_conditioning") ? domain.equals("climate")
                            : favorite[2].equals("hanger") ? domain.equals("cover")
                            : domain.equals("switch");
                    if (favorite[0].equals("灯具总开关")) {
                        relevant = domain.equals("switch") && entity.optString("name").replaceAll("\\s+", "")
                                .equals("灯具总线开关开关");
                    }
                    if (!relevant) continue;
                    String value = entity.optString("state", "unknown").toLowerCase(Locale.ROOT);
                    if (value.isEmpty() || value.equals("unknown") || value.equals("unavailable")) continue;
                    online = true;
                    status = stateLabel(value);
                    if (domain.equals("climate")) {
                        String current = temperature(entity, "current_temperature");
                        String target = temperature(entity, "target_temperature");
                        if (!current.isEmpty()) status = "室温 " + current;
                        else if (!target.isEmpty()) status = "设定 " + target;
                        subtitle = stateLabel(value);
                        if (!current.isEmpty() && !target.isEmpty()) subtitle += " · 设定 " + target;
                    }
                    active = value.equals("on") || value.equals("cool") || value.equals("heat")
                            || value.equals("heat_cool") || value.equals("dry") || value.equals("fan_only")
                            || value.equals("opening") || value.equals("closing");
                    break;
                }
            }
            // The existing single-plug reader also knows network / refresh failures.
            if (slot == 0 && plug != null && plug.configured) {
                online = plug.networkAvailable && plug.known;
                status = plug.on ? "已开启" : "已关闭";
                active = online && plug.on;
            }
            if (online) result.add(new Item(favorite[0], favorite[1], favorite[2], status, subtitle, active));
        }
        return result;
    }

    private static String temperature(JSONObject entity, String key) {
        Object reading = entity.opt(key);
        String unit = entity.optString("temperature_unit");
        if (!(reading instanceof Number) || !(unit.equals("°C") || unit.equals("°F"))) return "";
        double number = ((Number) reading).doubleValue();
        return Double.isFinite(number) && Math.abs(number) <= 1e12
                ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() + unit : "";
    }

    private static String stateLabel(String value) {
        switch (value) {
            case "on": return "已开启";
            case "off": return "已关闭";
            case "cool": return "制冷中";
            case "heat": return "制热中";
            case "heat_cool": return "自动模式";
            case "dry": return "除湿中";
            case "fan_only": return "送风中";
            case "open": return "已展开";
            case "closed": return "已收起";
            case "opening": return "展开中";
            case "closing": return "收起中";
            case "unavailable": return "暂不可用";
            case "unknown": case "": return "状态未知";
            default: return "可读取";
        }
    }
}
