package com.alex.minimalhome;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deliberately small, local Chinese command vocabulary. Parsing never executes an action.
 * SET_DEVICE describes a fixed target; HomeControls must validate the bound connection and state.
 */
public final class VoiceCommand {
    public enum Kind {
        QUERY_HOME, QUERY_INDOOR, QUERY_TEMPERATURE, QUERY_HUMIDITY,
        QUERY_WEATHER, QUERY_DEVICE, QUERY_LIGHTS, SET_DEVICE, UNSUPPORTED, CANCEL
    }

    public final Kind kind;
    /** Meaningful only for SET_DEVICE. Do not infer an action from this field otherwise. */
    public final boolean targetOn;

    private VoiceCommand(Kind kind, boolean targetOn) {
        this.kind = kind;
        this.targetOn = targetOn;
    }

    private static final String POLITE = "(?:请帮我|麻烦帮我|帮我|请|麻烦)?";
    private static final String QUERY_START = POLITE
            + "(?:查询一下|查询|查一下|查下|查看一下|查看|看看|看一下|看下|告诉我|问)?(?:现在)?";
    private static final String QUERY_END = "(?:的)?(?:是多少|多少|是什么|怎么样|如何|什么情况|什么状态|状态|情况)?(?:呢|呀|啊|吗)?";
    private static final String ON = "(?:打开|开启|开|开一下|打开一下|开启一下)";
    private static final String OFF = "(?:关闭|关掉|关|关一下|关闭一下|关掉一下)";

    public static VoiceCommand parse(String text, String configuredDeviceTitle) {
        if (text != null && text.length() > 512) return of(Kind.UNSUPPORTED);
        String phrase = normalize(text);
        if (phrase.isEmpty() || phrase.length() > 128) return of(Kind.UNSUPPORTED);
        if (phrase.matches("(?:取消|算了|不用了|不操作了|停止|退出|取消操作)")) return of(Kind.CANCEL);

        // Questions take precedence over commands. In particular, “插座开了吗” is read-only.
        String device = devicePattern(configuredDeviceTitle);
        if (matches(phrase, QUERY_START + device
                + "(?:的)?(?:电源)?(?:现在)?(?:状态|状态是什么|是什么状态|什么状态|怎么样|情况|是开还是关|是开着还是关着|开着还是关着|开着没|关着没|开了没|关了没|有没有开|有没有关|是否开启|是否关闭|是否开着|是否关着|是不是开着|是不是关着|是不是开了|是不是关了|是不是没开|是不是没关|开了吗|关了吗|打开了吗|关闭了吗|开启了吗|开着吗|关着吗|开了吗|关了吗)(?:呢|呀|啊|吗)?")) {
            return of(Kind.QUERY_DEVICE);
        }
        if (matches(phrase, QUERY_START + "(?:室内|客厅|家里|屋里)?(?:的)?(?:温湿度|温度和湿度|温度与湿度|温度湿度)" + QUERY_END)) {
            return of(Kind.QUERY_INDOOR);
        }
        if (matches(phrase, QUERY_START + "(?:室内|客厅|家里|屋里)?(?:的)?(?:温度|气温)" + QUERY_END)
                || matches(phrase, QUERY_START + "(?:室内|客厅|家里|屋里)?(?:有)?(?:几度|多少度)(?:呢|呀|啊)?")) {
            return of(Kind.QUERY_TEMPERATURE);
        }
        if (matches(phrase, QUERY_START + "(?:室内|客厅|家里|屋里)?(?:的)?(?:相对)?湿度" + QUERY_END)) {
            return of(Kind.QUERY_HUMIDITY);
        }
        if (matches(phrase, QUERY_START + "(?:今天|今日)?(?:的)?(?:杭州市拱墅区|杭州拱墅区|拱墅区|杭州|杭州市|室外|外面)?(?:的)?(?:天气|气温|室外温度)" + QUERY_END)
                || matches(phrase, QUERY_START + "(?:今天)?(?:室外|外面)(?:的)?(?:温度|天气|气温|几度|多少度)" + QUERY_END)) {
            return of(Kind.QUERY_WEATHER);
        }
        if (matches(phrase, QUERY_START + "(?:全屋灯光|全屋的灯|全屋灯|灯光|灯)(?:的)?(?:状态|情况|怎么样|开了吗|关了吗|开着吗|关着吗|亮着吗|亮了吗|有没有开|是不是开着|是否开启|是否关闭)(?:呢|呀|啊|吗)?")) {
            return of(Kind.QUERY_LIGHTS);
        }
        if (matches(phrase, QUERY_START + "(?:家里|家中|所有设备|全部设备|家居)(?:的)?(?:状态|情况|怎么样|什么情况|什么状态)(?:呢|呀|啊)?")
                || matches(phrase, POLITE + "(?:查询|查看|查一下|看看)(?:全部|所有)?状态")) {
            return of(Kind.QUERY_HOME);
        }

        // Exact whole-sentence imperative grammar: no substring-based actions, toggles,
        // negations, conditions, other devices, or multiple actions can pass this branch.
        String tail = "(?:一下)?(?:吧)?";
        if (matches(phrase, POLITE + ON + device + tail)
                || matches(phrase, POLITE + "(?:把|将)?" + device + "(?:的电源)?" + ON + tail)) {
            return new VoiceCommand(Kind.SET_DEVICE, true);
        }
        if (matches(phrase, POLITE + OFF + device + tail)
                || matches(phrase, POLITE + "(?:把|将)?" + device + "(?:的电源)?" + OFF + tail)) {
            return new VoiceCommand(Kind.SET_DEVICE, false);
        }
        return of(Kind.UNSUPPORTED);
    }

    private static String devicePattern(String title) {
        Set<String> names = new LinkedHashSet<>();
        String normalizedTitle = normalize(title);
        if (!normalizedTitle.isEmpty() && normalizedTitle.length() <= 64) names.add(normalizedTitle);
        // The application currently owns one configured plug. Never alias “设备” or “开关”.
        if (normalizedTitle.isEmpty() || normalizedTitle.contains("插座")) names.add("插座");
        // This familiar shorthand is specific to the installed oven plug. Do not
        // strip “插座” from arbitrary appliance names or infer other device aliases.
        if (normalizedTitle.equals("烤箱插座")) names.add("烤箱");
        // An empty alternative would match the empty string and turn a bare
        // “打开” or “关闭” into a device command when an unusable title is configured.
        if (names.isEmpty()) return "(?!)";
        StringBuilder expression = new StringBuilder("(?:");
        for (String name : names) {
            if (expression.length() > 3) expression.append('|');
            expression.append(Pattern.quote(name));
        }
        return expression.append(')').toString();
    }

    private static String normalize(String input) {
        if (input == null) return "";
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000]+", "");
        // ASR commonly adds terminal punctuation. Internal punctuation is retained so
        // “打开插座，关闭插座” cannot collapse into a supported command.
        return text.replaceAll("[。.!！?？…]+$", "");
    }

    private static boolean matches(String phrase, String pattern) { return phrase.matches(pattern); }
    private static VoiceCommand of(Kind kind) { return new VoiceCommand(kind, false); }
}
