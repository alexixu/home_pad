import java.util.Set;
import java.time.OffsetDateTime;

/** Pure validation shared by the actual runtime and offline checks. */
public final class BridgePolicy {
    private BridgePolicy() {}
    public static boolean canReusePanel(String target, String verifiedTarget, int window,
            int verifiedWindow, long verifiedAt, long now) {
        return ("curtain".equals(target) || "sheer".equals(target))
                && target.equals(verifiedTarget) && window == verifiedWindow && window >= 0
                && verifiedAt > 0 && now >= verifiedAt && now - verifiedAt < 30000;
    }
    public static boolean navigationReady(long lastInput, long now, String receipt) {
        return lastInput == 0 || now - lastInput >= 6000
                || "acknowledged".equals(receipt) || "failure".equals(receipt);
    }
    public static boolean targetIdentity(String target,int curtainNames,int sheerNames,int bedroomNames,int deviceNameLabels){
        return bedroomNames==1&&deviceNameLabels==1&&
            (("curtain".equals(target)&&curtainNames==2&&sheerNames==0)||
             ("sheer".equals(target)&&sheerNames==2&&curtainNames==0));
    }
    public static double epoch(String value) {
        // Android's Java 8 ISO_INSTANT rejects HA's explicit +00:00 suffix.
        if(value==null||value.length()>64||!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"))return Double.NaN;
        try{return OffsetDateTime.parse(value).toInstant().toEpochMilli()/1000.0;}
        catch(Exception invalid){return Double.NaN;}
    }
    public static String requestError(String target, String action, String requestId,
            double created, double updated, String user, Set<String> users, double now) {
        if (!"curtain".equals(target) && !"sheer".equals(target)) return "unknown_target";
        if (!"open".equals(action) && !"close".equals(action) && !"stop".equals(action)) return "unknown_action";
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{8,96}")) return "invalid_request_id";
        if (user == null || !users.contains(user)) return "unauthorized_source";
        if (!Double.isFinite(created) || !Double.isFinite(updated) || !Double.isFinite(now)
                || created > now + 2 || updated > now + 2 || now - created > 15
                || now - updated > 15 || Math.abs(created - updated) > 5) return "request_expired";
        return "";
    }
    public static boolean fresh(double observed, double now) {
        return Double.isFinite(observed) && Double.isFinite(now) && observed <= now + 2 && now - observed < 120;
    }
    public static int position(String text) {
        if (text == null || !text.matches("(?:100|[1-9]?[0-9])%")) return -1;
        return Integer.parseInt(text.substring(0, text.length() - 1));
    }
    public static String controlLabel(String action) {
        if ("open".equals(action)) return "打开窗帘";
        if ("close".equals(action)) return "关闭窗帘";
        if ("stop".equals(action)) return "停止窗帘";
        return "";
    }
}
