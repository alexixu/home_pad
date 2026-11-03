package com.alex.minimalhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** A resolution-independent, native home surface. Decorative values are never device readings. */
public final class WarmDashboard extends FrameLayout {
    private static final int INK = 0xFF6C4E31;
    private static final int TEXT = 0xFF493724;
    private static final int MUTED = 0xFF97744C;
    private static final int GOLD = 0xFFC59750;
    private final Artwork artwork;
    private final View clockLabel;
    private final View temperatureLabel;
    private final View weatherLabel;
    private final View lightLabel;
    private final View plugCard;
    private final View searchButton;
    private final View voiceButton;
    private final View controlCenterButton;
    private HomeControls.DashboardState state;
    private EnvironmentControls.State environmentState;
    private String wakeStatus = "点击开始语音";
    private String lightDetail = "灯光设备尚未接入", lightStatus = "待接入";
    private boolean lightsOn;
    private float scale = 1, offsetX, offsetY, designWidth = 1168, designHeight = 784;
    private final RectF indoor = new RectF(), weather = new RectF(), lights = new RectF();
    private final RectF plug = new RectF(), clock = new RectF();
    private final RectF searchEntry = new RectF(), voiceEntry = new RectF(), controlEntry = new RectF();
    private boolean portrait;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClockDescription();
            artwork.invalidate();
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 40);
        }
    };

    public WarmDashboard(Context context, Runnable openSearch, Runnable openVoice, Runnable openControlCenter) {
        super(context);
        setClipChildren(false);
        setClickable(false);
        setLongClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        artwork = new Artwork(context);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(artwork, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        clockLabel = label("时间");
        temperatureLabel = label("室内温湿度，等待传感器数据");
        weatherLabel = label("室外天气，等待天气数据");
        plugCard = label("插座，等待状态");
        lightLabel = label("全屋灯光，待接入");
        searchButton = action("搜索应用", openSearch);
        voiceButton = action("语音助手，点击进入语音模式。" + wakeStatus, openVoice);
        controlCenterButton = action("控制中心", openControlCenter);
        updateClockDescription();
    }

    private View label(String description) {
        View v = new View(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.view.View");
                info.setClickable(false);
                info.setLongClickable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            }

            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK
                        || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false;
                return super.performAccessibilityAction(action, arguments);
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                // A display-only region owns its gestures without scheduling a click or long press.
                return true;
            }
        };
        v.setContentDescription(description);
        v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setFocusable(true);
        v.setClickable(false);
        v.setLongClickable(false);
        addView(v, new LayoutParams(1, 1));
        return v;
    }

    private View action(String description, Runnable callback) {
        View v = new View(getContext()) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        v.setContentDescription(description);
        v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setFocusable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(1000);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1AC59A5C), null, mask));
        v.setOnClickListener(ignored -> callback.run());
        v.setLongClickable(false);
        addView(v, new LayoutParams(1, 1));
        return v;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // Empty space must not pass a long press through to the launcher's parent surface.
        return true;
    }

    public void setState(HomeControls.DashboardState next) {
        state = next;
        plugCard.setContentDescription(next == null ? "插座，等待状态" : next.title + "，" + passiveStatus(next.status));
        artwork.invalidate();
    }

    public void setEnvironmentState(EnvironmentControls.State next) {
        environmentState = next;
        temperatureLabel.setContentDescription(describeEnvironment(false));
        weatherLabel.setContentDescription(describeEnvironment(true));
        artwork.invalidate();
    }

    /** Read-only summary of the complete local catalog; never a touch target. */
    public void setLightSummary(org.json.JSONObject summary) {
        lightsOn = false;
        if (summary == null || !summary.optBoolean("ok", false)) {
            lightDetail = "灯光状态暂不可用"; lightStatus = "未知";
        } else {
            int total = summary.optInt("total", 0), on = summary.optInt("on", 0), unknown = summary.optInt("unknown", 0);
            lightsOn = on > 0;
            if (total == 0) { lightDetail = "灯光设备尚未接入"; lightStatus = "待接入"; }
            else {
                lightDetail = "已接入 " + total + " 路灯光" + (unknown > 0 ? " · " + unknown + " 路未知" : " · 最近读取");
                lightStatus = on > 0 ? on + " 路开启" : unknown > 0 ? "待更新" : "全部关闭";
            }
        }
        lightLabel.setContentDescription("全屋灯光，" + lightDetail + "，" + lightStatus);
        artwork.invalidate();
    }

    /** The owner supplies actual microphone/wake-engine state; the view never infers it. */
    public void setWakeStatus(String status) {
        String value = safe(status);
        wakeStatus = value.isEmpty() ? "点击开始语音" : value;
        voiceButton.setContentDescription("语音助手，点击进入语音模式。" + wakeStatus);
        artwork.invalidate();
    }

    public String describeEnvironment(boolean outdoor) {
        EnvironmentControls.State data = environmentState;
        if (data == null) return outdoor ? "室外天气，等待天气数据" : "室内温湿度，等待传感器数据";
        String reading = outdoor
                ? "室外天气，" + temperatureText(data.outdoorTemperatureC) + "，" + conditionLabel(data.condition)
                : "室内温度，" + temperatureText(data.temperatureC) + "，湿度 " + humidityText(data.humidity);
        String source = outdoor ? safe(data.weatherSource) : indoorSource(data);
        String status = passiveStatus(outdoor ? data.weatherStatus : data.indoorStatus);
        String reported = safe(outdoor ? data.weatherReportedAt : data.sensorReportedAt);
        return reading + (source.isEmpty() ? "" : "，来源：" + source)
                + (outdoor && !safe(data.weatherAttribution).isEmpty()
                        ? "，" + safe(data.weatherAttribution) : "")
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
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        portrait = h > w;
        designWidth = portrait ? 760 : 1168;
        designHeight = portrait ? 1160 : 784;
        scale = Math.min(w / designWidth, h / designHeight);
        offsetX = (w - designWidth * scale) / 2;
        offsetY = (h - designHeight * scale) / 2;
        if (portrait) {
            clock.set(40, 116, 720, 300);
            indoor.set(40, 356, 368, 604);
            weather.set(392, 356, 720, 604);
            plug.set(40, 681, 720, 791);
            lights.set(40, 809, 720, 919);
            searchEntry.set(40, 988, 284, 1060);
            voiceEntry.set(334, 970, 426, 1062);
            controlEntry.set(476, 988, 720, 1060);
        } else {
            clock.set(280, 50, 888, 232);
            indoor.set(166, 268, 572, 480);
            weather.set(596, 268, 1002, 480);
            plug.set(166, 542, 572, 652);
            lights.set(596, 542, 1002, 652);
            searchEntry.set(282, 686, 490, 750);
            voiceEntry.set(540, 668, 628, 756);
            controlEntry.set(678, 686, 886, 750);
        }
        position(clockLabel, clock);
        position(temperatureLabel, indoor);
        position(weatherLabel, weather);
        position(lightLabel, lights);
        position(plugCard, plug);
        positionAction(searchButton, searchEntry);
        positionAction(voiceButton, voiceEntry);
        positionAction(controlCenterButton, controlEntry);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void position(View view, RectF r) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        params.width = Math.round(r.width() * scale);
        params.height = Math.round(r.height() * scale);
        params.leftMargin = Math.round(offsetX + r.left * scale);
        params.topMargin = Math.round(offsetY + r.top * scale);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        view.setLayoutParams(params);
    }

    private void positionAction(View view, RectF r) {
        position(view, r);
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        int minimum = (int) Math.ceil(48 * getResources().getDisplayMetrics().density);
        int width = Math.max(params.width, minimum);
        int height = Math.max(params.height, minimum);
        params.leftMargin -= (width - params.width) / 2;
        params.topMargin -= (height - params.height) / 2;
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }

    private final class Artwork extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface body = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final Typeface serif = localFont("/system/fonts/MiSerifSC-Regular-01.ttf", Typeface.SERIF);
        private final Typeface numerals = localFont("/system/fonts/NotoSerifDisplay-Regular.ttf", serif);

        Artwork(Context context) {
            super(context);
            // Software supports the soft botanical shadows on API 26 as well.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        private Typeface localFont(String path, Typeface fallback) {
            try { return Typeface.createFromFile(path); }
            catch (RuntimeException ignored) { return fallback; }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            fill(0xFFF7EBD7);
            p.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                    new int[]{0xFFF3E9D8, 0xFFFFF8ED, 0xFFF4E9D8}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setShader(null);
            c.save();
            c.translate(offsetX, offsetY);
            c.scale(scale, scale);
            background(c);
            drawClock(c);
            metric(c, indoor, false);
            metric(c, weather, true);
            drawDeviceHeading(c);
            device(c, lights, false);
            device(c, plug, true);
            drawEntry(c, searchEntry, false);
            drawVoiceEntry(c);
            drawEntry(c, controlEntry, true);
            c.restore();
        }

        private void fill(int color) {
            p.reset(); p.setAntiAlias(true); p.setColor(color); p.setStyle(Paint.Style.FILL);
        }

        private void stroke(int color, float width) {
            fill(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }

        private void background(Canvas c) {
            fill(Color.WHITE);
            p.setShader(new RadialGradient(designWidth * .47f, designHeight * .35f, designWidth * .63f,
                    new int[]{0x7DFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRect(-offsetX / scale, -offsetY / scale, designWidth + offsetX / scale,
                    designHeight + offsetY / scale, p);
            p.setShader(null);
            branch(c, -35, -58, 1.10f, 24, 0x1C6E542C, 6);
            branch(c, 12, designHeight + 30, .80f, -152, 0x139A783F, 3);
            branch(c, designWidth + 48, designHeight + 10, .75f, -130, 0x10846335, 7);
            // Fixed, very fine paper grain, drawn without any bitmap or network dependency.
            Random random = new Random(34);
            fill(0x0595703D);
            for (int i = 0; i < 2200; i++) {
                c.drawCircle(random.nextFloat() * designWidth, random.nextFloat() * designHeight,
                        random.nextFloat() * .6f + .2f, p);
            }
        }

        private void branch(Canvas c, float x, float y, float size, float angle, int color, float blur) {
            c.save(); c.translate(x, y); c.rotate(angle); c.scale(size, size);
            stroke(color, 2.3f); p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            Path stem = new Path(); stem.moveTo(0, 0); stem.cubicTo(35, 60, 75, 150, 82, 244);
            c.drawPath(stem, p);
            for (int i = 0; i < 7; i++) {
                float sy = 26 + i * 28, sx = 8 + i * 10;
                leaf(c, sx, sy, sx - 70 + i * 5, sy + 64, color, blur);
                leaf(c, sx, sy, sx + 102 - i * 6, sy + 30, color, blur);
            }
            c.restore(); p.setMaskFilter(null);
        }

        private void leaf(Canvas c, float x, float y, float tx, float ty, int color, float blur) {
            float dx = tx - x, dy = ty - y;
            Path leaf = new Path(); leaf.moveTo(x, y);
            leaf.cubicTo(x + dx * .25f - dy * .13f, y + dy * .25f + dx * .13f,
                    x + dx * .72f - dy * .10f, y + dy * .72f + dx * .10f, tx, ty);
            leaf.cubicTo(x + dx * .70f + dy * .10f, y + dy * .70f - dx * .10f,
                    x + dx * .22f + dy * .10f, y + dy * .22f - dx * .10f, x, y);
            fill(color);
            if (blur > 0) p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            c.drawPath(leaf, p); p.setMaskFilter(null);
        }

        private void text(Canvas c, String text, float x, float baseline, float size, int color, Typeface font) {
            fill(color); p.setTypeface(font); p.setTextSize(size); c.drawText(text, x, baseline, p);
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

        private void drawClock(Canvas c) {
            Date now = new Date();
            String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(now);
            float size = portrait ? 104 : 112;
            float baseline = portrait ? 235 : 170;
            float width = textWidth(time, size, numerals);
            text(c, time, (designWidth - width) / 2, baseline, size, INK, numerals);
            String date = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now);
            float dateWidth = textWidth(date, 23, serif);
            text(c, date, (designWidth - dateWidth) / 2, baseline + 44, 23, MUTED, serif);
        }

        private void card(Canvas c, RectF r, float radius) {
            fill(0xB8FFFCF5);
            c.drawRoundRect(r, radius, radius, p);
            stroke(0x70DED0BA, 1);
            c.drawRoundRect(r, radius, radius, p);
        }

        private void metric(Canvas c, RectF r, boolean outdoor) {
            card(c, r, 24);
            float left = r.left + 28;
            text(c, outdoor ? "室外天气" : "室内环境", left, r.top + 39, 20, TEXT, serif);
            c.save();
            c.translate(r.right - 48, r.top + 43);
            c.scale(.46f, .46f);
            if (outdoor) weatherIcon(c, 0, 0, environmentState == null ? "" : environmentState.condition);
            else thermometer(c, 0, 0, 1);
            c.restore();
            Double temperature = environmentState == null ? null : outdoor
                    ? environmentState.outdoorTemperatureC : environmentState.temperatureC;
            String value = number(temperature);
            float valueSize = portrait ? 59 : 61;
            float valueWidth = textWidth(value, valueSize, body);
            while (valueSize > 28 && valueWidth + 40 > r.width() - 56) {
                valueSize--;
                valueWidth = textWidth(value, valueSize, body);
            }
            text(c, value, left, r.top + 110, valueSize, INK, body);
            text(c, "°C", left + valueWidth + 7, r.top + 108, 28, MUTED, body);
            String detail = outdoor ? environmentState == null ? "等待天气数据"
                    : conditionLabel(environmentState.condition)
                    : "湿度  " + humidityText(environmentState == null ? null : environmentState.humidity);
            text(c, fitted(detail, r.width() - 56, 18), left, r.top + 140, 18, TEXT, body);
            String source = environmentState == null ? "" : outdoor
                    ? safe(environmentState.weatherSource) : indoorSource(environmentState);
            if (source.isEmpty()) source = outdoor ? "等待天气数据" : "等待温湿度传感器";
            text(c, fitted(source, r.width() - 56, 14), left, r.bottom - 36, 14, MUTED, body);
            String status = environmentState == null ? "尚未接入" : passiveStatus(outdoor
                    ? environmentState.weatherStatus : environmentState.indoorStatus);
            if (environmentState != null && environmentState.busy) status = "正在刷新 · " + status;
            if (outdoor && environmentState != null && !safe(environmentState.weatherAttribution).isEmpty()) {
                status += " · " + weatherCredit(environmentState.weatherAttribution);
            }
            text(c, fitted(status, r.width() - 56, 12.5f), left, r.bottom - 16, 12.5f, MUTED, body);
        }

        private void drawDeviceHeading(Canvas c) {
            text(c, "设备状态", Math.min(lights.left, plug.left) + 2,
                    Math.min(lights.top, plug.top) - 21, 18, MUTED, serif);
        }

        private void thermometer(Canvas c, float x, float y, float s) {
            c.save(); c.translate(x, y); c.scale(s, s);
            stroke(0xFFC2A06C, .9f); c.drawCircle(0, 0, 45, p);
            stroke(0xFF9B703A, 2.2f);
            Path path = new Path(); path.moveTo(-6, 6); path.lineTo(-6, -22);
            path.cubicTo(-6, -30, 6, -30, 6, -22); path.lineTo(6, 6);
            path.cubicTo(21, 19, 0, 37, -10, 22); path.cubicTo(-14, 15, -10, 9, -6, 6);
            c.drawPath(path, p); c.drawCircle(0, 16, 7, p); c.drawLine(0, -10, 0, 10, p);
            c.restore();
        }

        private void weatherIcon(Canvas c, float x, float y, String condition) {
            c.save(); c.translate(x, y); c.scale(portrait ? .83f : 1, portrait ? .83f : 1);
            String value = safe(condition);
            if ("sunny".equals(value)) {
                sun(c, 0, 0, 21);
            } else if ("clear-night".equals(value)) {
                stroke(GOLD, 2.3f);
                Path moon = new Path(); moon.moveTo(8, -27);
                moon.cubicTo(-29, -37, -43, 16, -9, 28);
                moon.cubicTo(7, 34, 24, 25, 28, 11);
                moon.cubicTo(-1, 24, -20, -9, 8, -27);
                c.drawPath(moon, p);
                c.drawLine(23, -20, 23, -10, p); c.drawLine(18, -15, 28, -15, p);
            } else if ("partlycloudy".equals(value)) {
                sun(c, -15, -12, 16); cloud(c, 0, 5);
            } else if ("cloudy".equals(value) || "windy-variant".equals(value)) {
                cloud(c, -8, -12); cloud(c, 4, 8);
            } else if ("rainy".equals(value) || "pouring".equals(value)
                    || "lightning-rainy".equals(value)) {
                cloud(c, 0, -11); stroke(GOLD, 2.3f);
                for (int i = 0; i < 3; i++) c.drawLine(-19 + i * 19, 20, -24 + i * 19, 32, p);
                if ("pouring".equals(value)) {
                    for (int i = 0; i < 2; i++) c.drawLine(-7 + i * 19, 31, -12 + i * 19, 42, p);
                } else if ("lightning-rainy".equals(value)) lightning(c, 4, 19);
            } else if ("snowy".equals(value) || "snowy-rainy".equals(value) || "hail".equals(value)) {
                cloud(c, 0, -12);
                for (int i = 0; i < 3; i++) {
                    float sx = -20 + i * 20;
                    stroke(GOLD, 1.7f);
                    if ("hail".equals(value)) c.drawCircle(sx, 29, 3, p);
                    else if ("snowy-rainy".equals(value) && i == 2) c.drawLine(sx + 2, 22, sx - 2, 33, p);
                    else {
                        c.drawLine(sx, 23, sx, 35, p);
                        c.drawLine(sx - 5, 26, sx + 5, 32, p);
                        c.drawLine(sx - 5, 32, sx + 5, 26, p);
                    }
                }
            } else if ("fog".equals(value)) {
                cloud(c, 0, -13); stroke(GOLD, 2.1f);
                c.drawLine(-34, 20, 31, 20, p); c.drawLine(-26, 29, 38, 29, p);
                c.drawLine(-37, 38, 23, 38, p);
            } else if ("windy".equals(value)) {
                stroke(GOLD, 2.3f);
                for (int i = 0; i < 3; i++) {
                    float y0 = -20 + i * 20;
                    Path wind = new Path(); wind.moveTo(-32 + i * 5, y0);
                    wind.lineTo(18 - i * 8, y0); wind.cubicTo(40 - i * 8, y0, 32 - i * 8, y0 - 18, 20 - i * 8, y0 - 9);
                    c.drawPath(wind, p);
                }
            } else if ("lightning".equals(value)) {
                cloud(c, 0, -12); lightning(c, 0, 20);
            } else {
                // Missing and exceptional conditions never imply sunny or partly cloudy weather.
                stroke(0xFFC2A06C, 1.6f); c.drawCircle(0, 0, 32, p);
                c.drawLine(-11, 0, 11, 0, p);
            }
            c.restore();
        }

        private void sun(Canvas c, float x, float y, float radius) {
            stroke(GOLD, 2.1f); c.drawCircle(x, y, radius, p);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                c.drawLine(x + (float)Math.cos(a) * (radius + 8), y + (float)Math.sin(a) * (radius + 8),
                        x + (float)Math.cos(a) * (radius + 16), y + (float)Math.sin(a) * (radius + 16), p);
            }
        }

        private void cloud(Canvas c, float x, float y) {
            c.save(); c.translate(x, y);
            Path cloud = new Path(); cloud.moveTo(-23, 16); cloud.lineTo(26, 16);
            cloud.cubicTo(48, 15, 44, -9, 28, -9);
            cloud.cubicTo(25, -34, -8, -34, -13, -10);
            cloud.cubicTo(-35, -14, -43, 16, -23, 16); cloud.close();
            fill(0xFFFBF4E7); c.drawPath(cloud, p);
            stroke(0xFFBA8A42, 2.3f); c.drawPath(cloud, p);
            c.restore();
        }

        private void lightning(Canvas c, float x, float y) {
            Path bolt = new Path(); bolt.moveTo(x + 5, y - 9); bolt.lineTo(x - 6, y + 5);
            bolt.lineTo(x + 2, y + 5); bolt.lineTo(x - 3, y + 19);
            stroke(GOLD, 2.5f); c.drawPath(bolt, p);
        }

        private void device(Canvas c, RectF r, boolean socket) {
            card(c, r, 22);
            float iconX = r.left + 42;
            c.save();
            c.translate(iconX, r.centerY());
            c.scale(.73f, .73f);
            if (socket) plugIcon(c, 0, 0);
            else bulb(c, 0, 0);
            c.restore();
            float x = r.left + 80;
            String title = socket && state != null && state.configured ? safe(state.title)
                    : socket ? "插座" : "全屋灯光";
            text(c, fitted(title, r.width() - 205, 22), x, r.centerY() - 6, 22, TEXT, serif);
            String detail = socket ? state == null ? "等待状态"
                    : !state.configured ? "尚未配置" : passiveStatus(state.status) : lightDetail;
            if ("已开启".equals(detail) || "已关闭".equals(detail)) detail = "电源状态";
            text(c, fitted(detail, r.width() - 106, 15), x, r.centerY() + 23, 15, MUTED, body);
            String status = socket ? "待接入" : lightStatus;
            boolean on = !socket && lightsOn;
            if (socket && state != null && state.configured) {
                status = !state.known ? "未知" : state.on ? "已开启" : "已关闭";
                on = state.known && state.on;
            }
            float statusWidth = textWidth(status, 16, body);
            float statusX = r.right - 26 - statusWidth;
            fill(on ? 0xFFA8834C : 0xFFBAAC98);
            c.drawCircle(statusX - 11, r.centerY() - 12, 3, p);
            text(c, status, statusX, r.centerY() - 6, 16, on ? INK : MUTED, body);
        }

        private void bulb(Canvas c, float x, float y) {
            c.save(); c.translate(x, y);
            stroke(0xFF896137, 1.7f);
            Path bulb = new Path(); bulb.moveTo(-7, 17); bulb.lineTo(-7, 10);
            bulb.cubicTo(-7, 6, -14, 2, -13, -7); bulb.cubicTo(-12, -24, 13, -24, 13, -7);
            bulb.cubicTo(14, 2, 7, 6, 7, 10); bulb.lineTo(7, 17); bulb.close();
            c.drawPath(bulb, p); c.drawLine(-7, 21, 7, 21, p); c.drawLine(-3, 26, 3, 26, p);
            for (int i = 0; i < 7; i++) {
                double a = Math.toRadians(180 + i * 30);
                c.drawLine((float)Math.cos(a) * 21, -6 + (float)Math.sin(a) * 21,
                        (float)Math.cos(a) * 27, -6 + (float)Math.sin(a) * 27, p);
            }
            c.restore();
        }

        private void plugIcon(Canvas c, float x, float y) {
            c.save(); c.translate(x, y);
            stroke(0xFF896137, 1.7f); c.drawRoundRect(new RectF(-21, -23, 21, 21), 10, 10, p);
            c.drawRoundRect(new RectF(-10, -15, -6, -7), 2, 2, p);
            c.drawRoundRect(new RectF(6, -15, 10, -7), 2, 2, p);
            Path socket = new Path(); socket.moveTo(-12, -2); socket.lineTo(12, -2);
            socket.lineTo(12, 5); socket.quadTo(10, 14, 0, 14); socket.quadTo(-10, 14, -12, 5); socket.close();
            c.drawPath(socket, p); c.drawLine(0, 14, 0, 29, p); c.restore();
        }

        private void drawVoiceEntry(Canvas c) {
            float x = voiceEntry.centerX(), y = voiceEntry.centerY();
            float radius = voiceEntry.width() * .42f;
            // Keep the existing touch target; the airy rings are decorative only.
            for (int ring = 0; ring < 2; ring++) {
                float r = radius * (ring == 0 ? 1.28f : 1.46f);
                RectF orbit = new RectF(x - r, y - r, x + r, y + r);
                stroke(ring == 0 ? 0x2AA49D90 : 0x20A49D90, .55f);
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
            float size = 12.5f;
            String caption = fitted(wakeStatus, portrait ? 420 : 440, size);
            float captionWidth = textWidth(caption, size, body);
            text(c, caption, x - captionWidth / 2, voiceEntry.bottom + (portrait ? 24 : 22), size, MUTED, body);
        }

        private void drawEntry(Canvas c, RectF r, boolean controls) {
            fill(controls ? 0xFFEBDBC2 : 0x9EFFFBF3);
            c.drawRoundRect(r, 24, 24, p);
            stroke(controls ? 0x5EC5AB82 : 0x78D8C7AA, 1);
            c.drawRoundRect(r, 24, 24, p);
            String title = controls ? "控制中心" : "搜索应用";
            float titleWidth = textWidth(title, 20, body);
            float iconX = r.centerX() - (titleWidth + 42) / 2 + 11;
            float y = r.centerY();
            if (controls) {
                stroke(INK, 1.6f);
                for (int i = 0; i < 3; i++) {
                    float lineY = y - 10 + i * 10;
                    c.drawLine(iconX - 11, lineY, iconX + 11, lineY, p);
                    float knobX = iconX + (i == 1 ? 5 : -5);
                    fill(0xFFEBDBC2); c.drawCircle(knobX, lineY, 3.3f, p);
                    stroke(INK, 1.6f); c.drawCircle(knobX, lineY, 3.3f, p);
                }
            } else {
                stroke(INK, 1.8f);
                c.drawCircle(iconX - 2, y - 2, 8, p);
                c.drawLine(iconX + 4, y + 4, iconX + 11, y + 11, p);
            }
            text(c, title, iconX + 30, y + 7, 20, TEXT, body);
        }
    }
}
