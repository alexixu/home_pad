package com.alex.minimalhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Display-only favorites. An account-visible name never implies an imported device. */
final class DashboardDevices {
    static final class Item {
        final String name, room, icon, status;
        final boolean active;
        Item(String name, String room, String icon, String status, boolean active) {
            this.name = name; this.room = room; this.icon = icon;
            this.status = status; this.active = active;
        }
        String description() { return room + "，" + name + "，" + status; }
    }

    // Display slots from the previously observed household inventory, not control targets.
    private static final String[][] FAVORITES = {
        {"烤箱插座", "客厅", "plug", "烤箱插座"},
        {"主卧窗帘", "主卧", "curtain", "主卧窗帘"},
        {"主卧窗纱", "主卧", "curtain", "主卧窗纱"},
        {"客厅大灯", "客厅", "bulb", "客厅大灯"},
        {"客厅空调", "客厅", "air_conditioning", "客厅空调"},
        {"智能晾衣架", "阳台", "hanger", "卡瑞琪ProS1智能晾衣架", "智能晾衣架"}
    };

    static List<Item> summarize(JSONObject catalog, HomeControls.DashboardState plug) {
        List<Item> result = new ArrayList<>();
        JSONArray devices = catalog == null ? null : catalog.optJSONArray("devices");
        for (int slot = 0; slot < FAVORITES.length; slot++) {
            String[] favorite = FAVORITES[slot];
            JSONObject matched = null;
            if (devices != null) for (int i = 0; i < devices.length(); i++) {
                JSONObject candidate = devices.optJSONObject(i);
                if (candidate == null) continue;
                String room = candidate.optString("room", "");
                if (!room.isEmpty() && !room.equals(favorite[1])) continue;
                for (int alias = 3; alias < favorite.length; alias++) {
                    if (candidate.optString("name", "").equals(favorite[alias])) matched = candidate;
                }
            }
            String status = catalog == null ? "等待读取" : "待接入";
            boolean active = false;
            if (matched != null) {
                status = !catalog.optBoolean("ok", false) ? "状态未知"
                        : !matched.optBoolean("available", false) ? "暂不可用" : "可读取";
                if (catalog.optBoolean("ok", false) && matched.optBoolean("available", false)) {
                    JSONArray entities = matched.optJSONArray("entities");
                    if (entities != null) for (int i = 0; i < entities.length(); i++) {
                        JSONObject entity = entities.optJSONObject(i);
                        if (entity == null) continue;
                        String domain = entity.optString("domain", "");
                        boolean relevant = favorite[2].equals("bulb") ? domain.equals("light")
                                : favorite[2].equals("air_conditioning") ? domain.equals("climate")
                                : favorite[2].equals("hanger") || favorite[2].equals("curtain") ? domain.equals("cover")
                                : domain.equals("switch");
                        if (!relevant) continue;
                        String value = entity.optString("state", "unknown").toLowerCase(Locale.ROOT);
                        // Official curtain positions have not been physically verified.
                        // Show availability only; never infer open/closed from that report.
                        status = favorite[2].equals("curtain")
                                ? value.equals("unavailable") ? "暂不可用" : "已接入"
                                : stateLabel(value);
                        active = value.equals("on") || value.equals("cool") || value.equals("heat")
                                || value.equals("heat_cool") || value.equals("dry") || value.equals("fan_only")
                                || value.equals("opening") || value.equals("closing");
                        if (favorite[2].equals("curtain")) active = false;
                        break;
                    }
                }
            }
            // The existing single-plug reader also knows network / refresh failures.
            if (slot == 0 && plug != null && plug.configured) {
                status = !plug.networkAvailable ? "网络未连接" : !plug.known ? "状态未知"
                        : plug.on ? "已开启" : "已关闭";
                active = plug.networkAvailable && plug.known && plug.on;
            }
            result.add(new Item(favorite[0], favorite[1], favorite[2], status, active));
        }
        return result;
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
