package com.alex.minimalhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native courtyard home. All environment and device regions are display-only. */
public final class WarmDashboard extends FrameLayout {
    private static final int INK = 0xFF654627, TEXT = 0xFF493724, MUTED = 0xFF92724D;
    private static final int GOLD = 0xFFC59750, OLIVE = 0xFF6B793D;
    private final Artwork artwork;
    private final View clockLabel, temperatureLabel, weatherLabel;
    private final View[] deviceLabels = new View[6];
    private final View searchButton, voiceButton, controlCenterButton;
    private HomeControls.DashboardState state;
    private EnvironmentControls.State environmentState;
    private JSONObject catalog;
    private List<DashboardDevices.Item> devices;
    private String wakeStatus = "点击开始语音";
    private float scale = 1, offsetX, offsetY, designWidth = 1600, designHeight = 1000;
    private final RectF environment = new RectF(), indoor = new RectF(), weather = new RectF(), clock = new RectF();
    private final RectF[] deviceRects = new RectF[6];
    private final RectF searchEntry = new RectF(), voiceEntry = new RectF(), controlEntry = new RectF();
    private boolean portrait, running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClockDescription(); artwork.invalidate();
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 40);
        }
    };

    public WarmDashboard(Context context, Runnable openSearch, Runnable openVoice, Runnable openControlCenter) {
        super(context);
        setClipChildren(false); setClickable(false); setLongClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        artwork = new Artwork(context);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(artwork, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        clockLabel = label("时间");
        temperatureLabel = label("室内温湿度，等待传感器数据");
        weatherLabel = label("室外天气，等待天气数据");
        for (int i = 0; i < deviceLabels.length; i++) {
            deviceLabels[i] = label("设备，等待读取"); deviceRects[i] = new RectF();
        }
        searchButton = action("搜索应用", openSearch);
        voiceButton = action("语音助手，点击进入语音模式。" + wakeStatus, openVoice);
        controlCenterButton = action("控制中心", openControlCenter);
        updateClockDescription(); updateDevices();
    }

    private View label(String description) {
        View v = new View(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.view.View");
                info.setClickable(false); info.setLongClickable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            }
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false;
                return super.performAccessibilityAction(action, arguments);
            }
            @Override public boolean onTouchEvent(MotionEvent event) { return true; }
        };
        v.setContentDescription(description); v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setFocusable(true); v.setClickable(false); v.setLongClickable(false);
        addView(v, new LayoutParams(1, 1)); return v;
    }

    private View action(String description, Runnable callback) {
        View v = new View(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Button");
            }
        };
        v.setContentDescription(description); v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setFocusable(true);
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(1000);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1AC59A5C), null, mask));
        v.setOnClickListener(ignored -> callback.run()); v.setLongClickable(false);
        addView(v, new LayoutParams(1, 1)); return v;
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
    public void setState(HomeControls.DashboardState next) { state = next; updateDevices(); artwork.invalidate(); }
    public void setDeviceCatalog(JSONObject next) {
        // Keep only a deep copy of the existing credential-free projection.
        try { catalog = next == null ? null : new JSONObject(next.toString()); }
        catch (Exception ignored) { catalog = null; }
        updateDevices(); artwork.invalidate();
    }
    private void updateDevices() {
        devices = DashboardDevices.summarize(catalog, state);
        for (int i = 0; i < deviceLabels.length; i++) deviceLabels[i].setContentDescription(devices.get(i).description());
    }
    public void setEnvironmentState(EnvironmentControls.State next) {
        environmentState = next;
        temperatureLabel.setContentDescription(describeEnvironment(false));
        weatherLabel.setContentDescription(describeEnvironment(true)); artwork.invalidate();
    }
    public void setWakeStatus(String status) {
        String value = safe(status); wakeStatus = value.isEmpty() ? "点击开始语音" : value;
        voiceButton.setContentDescription("语音助手，点击进入语音模式。" + wakeStatus); artwork.invalidate();
    }
    public String describeEnvironment(boolean outdoor) {
        EnvironmentControls.State data = environmentState;
        if (data == null) return outdoor ? "室外天气，等待天气数据" : "室内温湿度，等待传感器数据";
        String reading = outdoor ? "室外天气，" + temperatureText(data.outdoorTemperatureC) + "，" + conditionLabel(data.condition)
                : "室内温度，" + temperatureText(data.temperatureC) + "，湿度 " + humidityText(data.humidity);
        String source = outdoor ? safe(data.weatherSource) : indoorSource(data);
        String status = passiveStatus(outdoor ? data.weatherStatus : data.indoorStatus);
        String reported = safe(outdoor ? data.weatherReportedAt : data.sensorReportedAt);
        return reading + (source.isEmpty() ? "" : "，来源：" + source)
                + (outdoor && !safe(data.weatherAttribution).isEmpty() ? "，" + safe(data.weatherAttribution) : "")
                + (status.isEmpty() ? "" : "，" + status)
                + (reported.isEmpty() ? "" : "，数据记录时间：" + displayTime(reported))
                + (data.busy ? "，正在刷新" : "");
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String passiveStatus(String value) {
        return safe(value).replace(" · 点击刷新", "").replace("，请刷新", "")
                .replace("点击刷新", "等待更新");
    }

    private static boolean valid(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }

    private static String number(Double value) {
        if (!valid(value)) return "—";
        return String.format(Locale.CHINA, "%.1f", value).replaceAll("\\.0$", "");
    }

    private static String temperatureText(Double value) {
        return valid(value) ? number(value) + " 摄氏度" : "暂无读数";
    }

    private static String humidityText(Double value) {
        return valid(value) && value >= 0 && value <= 100 ? number(value) + "%" : "—";
    }

    private static String indoorSource(EnvironmentControls.State data) {
        String temperature = safe(data.temperatureSource), humidity = safe(data.humiditySource);
        if (temperature.isEmpty()) return humidity;
        if (humidity.isEmpty() || temperature.equals(humidity)) return temperature;
        String temperatureDevice = temperature.replaceFirst("(?i)[ ·_-]*(温度|temperature)$", "").trim();
        String humidityDevice = humidity.replaceFirst("(?i)[ ·_-]*(湿度|humidity)$", "").trim();
        if (!temperatureDevice.isEmpty() && temperatureDevice.equals(humidityDevice)) return temperatureDevice;
        return temperature + " / " + humidity;
    }

    private static String weatherCredit(String attribution) {
        String value = safe(attribution);
        if (value.toLowerCase(Locale.ROOT).contains("met.no")
                || value.toLowerCase(Locale.ROOT).contains("met norway")) return "MET Norway";
        return value;
    }

    private static String displayTime(String value) {
        try {
            return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
                    .withZone(ZoneId.systemDefault()).format(OffsetDateTime.parse(value).toInstant());
        } catch (RuntimeException ignored) { return value; }
    }

    static String conditionLabel(String value) {
        switch (safe(value)) {
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

    public void start() {
        running = true;
        removeCallbacks(tick);
        tick.run();
    }

    public void stop() { running = false; removeCallbacks(tick); }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    private void updateClockDescription() {
        clockLabel.setContentDescription(new SimpleDateFormat("M月d日 EEEE ah:mm", Locale.CHINA).format(new Date()));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
        portrait = h > w; designWidth = portrait ? 800 : 1600; designHeight = portrait ? 1280 : 1000;
        scale = Math.min(w / designWidth, h / designHeight);
        offsetX = (w - designWidth * scale) / 2; offsetY = (h - designHeight * scale) / 2;
        if (portrait) {
            clock.set(48, 24, 752, 264); environment.set(40, 290, 760, 526);
            indoor.set(58, 344, 506, 508); weather.set(506, 344, 742, 508);
            searchEntry.set(48, 1126, 258, 1198); voiceEntry.set(344, 1094, 456, 1206);
            controlEntry.set(542, 1126, 752, 1198);
        } else {
            clock.set(72, 20, 780, 220); environment.set(60, 244, 1060, 454);
            indoor.set(90, 300, 680, 440); weather.set(680, 300, 1040, 440);
            searchEntry.set(145, 834, 375, 900); voiceEntry.set(498, 796, 606, 904);
            controlEntry.set(716, 834, 972, 900);
        }
        float left = portrait ? 48 : 72, width = portrait ? 704 : 976;
        float gap = portrait ? 28 : 44, cell = (width - gap) / 2;
        float top = portrait ? 618 : 530, height = portrait ? 126 : 82, rowGap = portrait ? 20 : 0;
        for (int i = 0; i < deviceRects.length; i++) {
            int row = i / 2, col = i % 2;
            float x = left + col * (cell + gap), y = top + row * (height + rowGap);
            deviceRects[i].set(x, y, x + cell, y + height); position(deviceLabels[i], deviceRects[i]);
        }
        position(clockLabel, clock); position(temperatureLabel, indoor); position(weatherLabel, weather);
        positionAction(searchButton, searchEntry); positionAction(voiceButton, voiceEntry); positionAction(controlCenterButton, controlEntry);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
    private void position(View view, RectF r) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        params.width = Math.round(r.width() * scale); params.height = Math.round(r.height() * scale);
        params.leftMargin = Math.round(offsetX + r.left * scale); params.topMargin = Math.round(offsetY + r.top * scale);
        params.gravity = Gravity.TOP | Gravity.LEFT; view.setLayoutParams(params);
    }
    private void positionAction(View view, RectF r) {
        position(view, r); LayoutParams params = (LayoutParams) view.getLayoutParams();
        int minimum = (int) Math.ceil(48 * getResources().getDisplayMetrics().density);
        int width = Math.max(params.width, minimum), height = Math.max(params.height, minimum);
        params.leftMargin -= (width - params.width) / 2; params.topMargin -= (height - params.height) / 2;
        params.width = width; params.height = height; view.setLayoutParams(params);
    }

    private final class Artwork extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface body = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface serif = localFont("/system/fonts/MiSerifSC-Regular-01.ttf", Typeface.SERIF);
        private final Typeface numerals = displayFont();
        private final Bitmap courtyard;
        private final Map<String, Drawable> icons = new HashMap<>();
        Artwork(Context context) {
            super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            Bitmap image = null;
            try (InputStream stream = context.getAssets().open("dashboard/courtyard.png")) {
                image = BitmapFactory.decodeStream(stream);
            } catch (Exception ignored) { }
            courtyard = image;
        }
        private Typeface localFont(String path, Typeface fallback) {
            try { return Typeface.createFromFile(path); } catch (RuntimeException ignored) { return fallback; }
        }
        private Typeface displayFont() {
            try { return Typeface.createFromAsset(getContext().getAssets(), "dashboard/BodoniModa.ttf"); }
            catch (RuntimeException ignored) { return localFont("/system/fonts/NotoSerifDisplay-Regular.ttf", serif); }
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            fill(0xFFFBF3E7); c.drawRect(0, 0, getWidth(), getHeight(), p);
            if (courtyard != null) {
                float ratio = Math.max(getWidth() / (float)courtyard.getWidth(), getHeight() / (float)courtyard.getHeight());
                float bw = courtyard.getWidth() * ratio, bh = courtyard.getHeight() * ratio;
                float x = portrait ? getWidth() - bw : (getWidth() - bw) / 2, y = (getHeight() - bh) / 2;
                p.setFilterBitmap(true); c.drawBitmap(courtyard, null, new RectF(x, y, x + bw, y + bh), p);
                if (portrait) { fill(0xEAFCF5EB); c.drawRect(0, 0, getWidth(), getHeight(), p); }
            }
            c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale);
            drawClock(c); drawEnvironment(c); drawDevices(c);
            drawEntry(c, searchEntry, false); drawVoiceEntry(c); drawEntry(c, controlEntry, true);
            c.restore();
        }
        private void fill(int color) {
            p.reset(); p.setAntiAlias(true); p.setColor(color); p.setStyle(Paint.Style.FILL);
        }
        private void stroke(int color, float width) {
            fill(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        private void text(Canvas c, String value, float x, float baseline, float size, int color, Typeface font) {
            fill(color); p.setTypeface(font); p.setTextSize(size); c.drawText(value, x, baseline, p);
        }
        private float textWidth(String value, float size, Typeface font) {
            p.setTypeface(font); p.setTextSize(size); return p.measureText(value);
        }
        private String fitted(String value, float width, float size) {
            if (textWidth(value, size, body) <= width) return value;
            int end = value.length();
            while (end > 0 && textWidth(value.substring(0, end) + "…", size, body) > width) end--;
            return value.substring(0, end) + "…";
        }
        private void icon(Canvas c, String name, float x, float y, float size) {
            if (name.isEmpty()) return;
            if (!icons.containsKey(name)) {
                int resource = getResources().getIdentifier("home_" + name, "drawable", getContext().getPackageName());
                if (resource != 0) icons.put(name, getResources().getDrawable(resource, getContext().getTheme()));
            }
            Drawable drawable = icons.get(name);
            if (drawable == null) return;
            drawable.setBounds(Math.round(x - size / 2), Math.round(y - size / 2), Math.round(x + size / 2), Math.round(y + size / 2));
            drawable.draw(c);
        }
        private void drawClock(Canvas c) {
            float left = portrait ? 58 : 88;
            fill(OLIVE); c.drawRect(left - 17, 27, left - 13, 49, p);
            text(c, "我的家", left, 48, 24, TEXT, body);
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String greeting = hour < 6 ? "夜深了，家正安静" : hour < 12 ? "上午好，慢慢开启今天"
                    : hour < 18 ? "下午好，享受片刻闲暇" : "晚上好，欢迎回家";
            text(c, greeting, left, 92, 26, TEXT, body);
            Date now = new Date(); float baseline = portrait ? 245 : 212, size = portrait ? 124 : 142;
            String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(now);
            while (size > 80 && textWidth(time, size, numerals) > (portrait ? 365 : 370)) size--;
            text(c, time, left - 8, baseline, size, INK, numerals);
            float x = portrait ? 480 : 516, y = portrait ? 190 : 161;
            stroke(0x70BB956A, 1); c.drawLine(x - 30, y - 37, x - 30, y + 43, p);
            text(c, new SimpleDateFormat("M月d日", Locale.CHINA).format(now), x, y, portrait ? 28 : 34, TEXT, serif);
            text(c, new SimpleDateFormat("EEEE", Locale.CHINA).format(now), x, y + 40, 23, TEXT, body);
        }
        private String weatherIcon() {
            switch (environmentState == null ? "" : safe(environmentState.condition)) {
                case "sunny": return "sun";
                case "clear-night": return "moon";
                case "cloudy": case "partlycloudy": return "cloud";
                case "rainy": case "pouring": return "cloud_rain";
                case "snowy": case "snowy-rainy": case "hail": return "cloud_snow";
                case "fog": return "cloud_fog";
                case "windy": case "windy-variant": return "wind";
                case "lightning": case "lightning-rainy": return "cloud_storm";
                default: return "";
            }
        }
        private void drawEnvironment(Canvas c) {
            fill(0xA3F0E1CA); c.drawRoundRect(environment, 22, 22, p);
            float left = environment.left + 30, top = environment.top;
            text(c, "环境总览", left, top + 38, 27, TEXT, serif);
            float col = (environment.width() - 60) / 3;
            float nameY = top + (portrait ? 85 : 84), valueY = top + (portrait ? 149 : 141);
            String[] names = {"室内温度", "室内湿度", "室外天气"};
            String[] symbols = {"thermometer", "droplet", weatherIcon()};
            Double temperature = environmentState == null ? null : environmentState.temperatureC;
            Double humidity = environmentState == null ? null : environmentState.humidity;
            Double outdoor = environmentState == null ? null : environmentState.outdoorTemperatureC;
            String[] values = {number(temperature) + (valid(temperature) ? "°C" : ""), humidityText(humidity), number(outdoor) + (valid(outdoor) ? "°C" : "")};
            for (int i = 0; i < 3; i++) {
                float x = left + i * col, tx = x + (portrait ? 10 : 93);
                icon(c, symbols[i], x + (portrait ? col - 36 : 45), nameY + (portrait ? -16 : 18), portrait ? 30 : 52);
                if (portrait) tx = x + 9;
                text(c, names[i], x + (portrait ? 5 : 93), nameY - (portrait ? 8 : 0), portrait ? 20 : 22, TEXT, body);
                float valueX = portrait ? x + 5 : tx;
                float valueSize = portrait ? 40 : i == 2 ? 42 : 49;
                if (portrait) valueY = top + 150;
                text(c, values[i], valueX, valueY, valueSize, INK, numerals);
                if (i == 2) {
                    String condition = environmentState == null ? "等待天气" : conditionLabel(environmentState.condition);
                    if (portrait) text(c, fitted(condition, col - 20, 17), valueX, valueY + 28, 17, TEXT, body);
                    else {
                        float after = valueX + textWidth(values[i], valueSize, numerals) + 14;
                        text(c, fitted(condition, environment.right - 22 - after, 21), after, valueY - 2, 21, TEXT, body);
                    }
                }
                if (i > 0) { stroke(0x66B99365, 1); c.drawLine(x - 7, nameY - 19, x - 7, valueY + 5, p); }
            }
            float foot = environment.bottom - 18;
            stroke(0x40B99365, 1); c.drawLine(left, foot - 27, environment.right - 30, foot - 27, p);
            String source = environmentState == null ? "等待温湿度数据" : indoorSource(environmentState);
            String indoorStatus = environmentState == null ? "" : passiveStatus(environmentState.indoorStatus);
            if (!indoorStatus.isEmpty() && !indoorStatus.equals("已连接")) source += " · " + indoorStatus;
            String weatherSource = environmentState == null ? "等待天气数据" : safe(environmentState.weatherSource);
            String attribution = environmentState == null ? "" : weatherCredit(environmentState.weatherAttribution);
            if (!attribution.isEmpty()) weatherSource += " · " + attribution;
            text(c, fitted(source, portrait ? 320 : 540, 14), left, foot, 14, MUTED, body);
            float wx = portrait ? left + 340 : left + 610;
            text(c, fitted(weatherSource, environment.right - wx - 24, 14), wx, foot, 14, MUTED, body);
        }
        private void drawDevices(Canvas c) {
            float left = portrait ? 48 : 72, top = portrait ? 584 : 506;
            text(c, "常用设备", left, top, 27, TEXT, serif);
            text(c, "设备状态一览", left + 148, top - 1, 18, MUTED, body);
            for (int i = 0; i < deviceRects.length; i++) {
                RectF r = deviceRects[i]; DashboardDevices.Item item = devices.get(i);
                float y = r.top + (portrait ? 43 : 30), tx = r.left + (portrait ? 58 : 96);
                icon(c, item.icon, r.left + (portrait ? 23 : 42), r.top + (portrait ? 52 : 34), portrait ? 38 : 51);
                String status = item.status;
                float statusSize = portrait ? 17 : 20, statusWidth = textWidth(status, statusSize, body);
                if (portrait) {
                    text(c, fitted(item.name, r.width() - 64, 22), tx, y, 22, TEXT, body);
                    text(c, item.room, tx, y + 28, 17, MUTED, body);
                    fill(item.active ? OLIVE : 0xFFAC9D87); c.drawCircle(tx + 4, y + 54, 3.5f, p);
                    text(c, status, tx + 18, y + 60, statusSize, item.active ? OLIVE : MUTED, body);
                } else {
                    float sx = r.right - statusWidth - 13;
                    text(c, fitted(item.name, sx - tx - 28, 23), tx, y, 23, TEXT, body);
                    text(c, item.room, tx, y + 28, 17, MUTED, body);
                    fill(item.active ? OLIVE : 0xFFAC9D87); c.drawCircle(sx - 16, y + 2, 5, p);
                    text(c, status, sx, y + 8, statusSize, item.active ? OLIVE : MUTED, body);
                }
                stroke(0x40B99365, 1); c.drawLine(r.left, r.bottom - 2, r.right, r.bottom - 2, p);
                if (i % 2 == 0 && !portrait) { stroke(0x50B99365, 1); c.drawLine(r.right + 22, r.top + 4, r.right + 22, r.bottom - 13, p); }
            }
        }
        private void drawVoiceEntry(Canvas c) {
            float x = voiceEntry.centerX(), y = voiceEntry.centerY();
            float radius = voiceEntry.width() * .42f;
            // Keep the existing touch target; the airy rings are decorative only.
            for (int ring = 0; ring < 2; ring++) {
                float r = radius * (ring == 0 ? 1.28f : 1.46f);
                RectF orbit = new RectF(x - r, y - r, x + r, y + r);
                stroke(ring == 0 ? 0x66B19D7C : 0x40B19D7C, .65f);
                for (int dash = 0; dash < 80; dash++) {
                    c.drawArc(orbit, dash * 4.5f, 1.3f, false, p);
                }
            }
            c.save();
            c.translate(x + radius * .08f, y + radius * .69f);
            c.scale(1, .38f);
            fill(Color.WHITE);
            p.setShader(new RadialGradient(0, 0, radius * 1.3f,
                    new int[]{0x27645E51, 0x11645E51, 0x00645E51},
                    new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, radius * 1.3f, p);
            c.restore();
            fill(Color.WHITE);
            p.setShader(new RadialGradient(x - radius * .38f, y - radius * .48f, radius * 1.72f,
                    new int[]{0xFFFFFEFB, 0xFFFAF7F1, 0xFFF0ECE4, 0xFFDCD7CE},
                    new float[]{0, .42f, .72f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, radius, p);
            stroke(0x36FFFDF7, .65f);
            c.drawCircle(x, y, radius - .3f, p);
            fill(0xFF62625B);
            p.setShader(new LinearGradient(x, y - radius * .4f, x, y + radius * .16f,
                    0xFF72716A, 0xFF595A53, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(x - radius * .15f, y - radius * .4f,
                    x + radius * .15f, y + radius * .16f), radius * .15f, radius * .15f, p);
            stroke(0xFF5E5F57, radius * .044f);
            Path cradle = new Path(); cradle.moveTo(x - radius * .25f, y + radius * .03f);
            cradle.cubicTo(x - radius * .25f, y + radius * .41f,
                    x + radius * .25f, y + radius * .41f, x + radius * .25f, y + radius * .03f);
            c.drawPath(cradle, p);
            c.drawLine(x, y + radius * .315f, x, y + radius * .44f, p);
            c.drawLine(x - radius * .16f, y + radius * .44f,
                    x + radius * .16f, y + radius * .44f, p);
            String title = "你好，小爱";
            text(c, title, x - textWidth(title, 22, body) / 2, voiceEntry.bottom + 29, 22, TEXT, body);
            String caption = fitted(wakeStatus, portrait ? 340 : 420, 14);
            text(c, caption, x - textWidth(caption, 14, body) / 2, voiceEntry.bottom + 55, 14, MUTED, body);
        }

        private void drawEntry(Canvas c, RectF r, boolean controls) {
            fill(controls ? 0xFFE6CFAE : 0x50FFF9ED); c.drawRoundRect(r, 33, 33, p);
            stroke(controls ? 0x4AC5AB82 : 0x78CBAF85, 1); c.drawRoundRect(r, 33, 33, p);
            String title = controls ? "控制中心" : "搜索";
            float titleWidth = textWidth(title, 22, body), x = r.centerX() - (titleWidth + 55) / 2 + 15;
            icon(c, controls ? "adjustments_horizontal" : "search", x, r.centerY(), 35);
            text(c, title, x + 36, r.centerY() + 8, 22, TEXT, body);
        }
    }
}
