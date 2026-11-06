import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BridgePolicyTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("bridge policy check " + checks); }
    public static void main(String[] ignored) {
        Set<String> users = new HashSet<>(Arrays.asList("authorized-user"));
        check(BridgePolicy.targetIdentity("curtain",2,0,1,1));
        check(BridgePolicy.targetIdentity("sheer",0,2,1,1));
        check(!BridgePolicy.targetIdentity("curtain",0,2,1,1));
        check(!BridgePolicy.targetIdentity("sheer",2,0,1,1));
        check(!BridgePolicy.targetIdentity("curtain",2,2,1,1));
        check(!BridgePolicy.targetIdentity("curtain",2,0,0,1));
        check(!BridgePolicy.targetIdentity("curtain",2,0,1,0));
        check(!BridgePolicy.targetIdentity("curtain",1,0,1,1));
        double haTime=1793890309.293;
        for(String value:new String[]{"2026-11-05T14:51:49.293387+00:00","2026-11-05T14:51:49.293387Z","2026-11-05T22:51:49.293387+08:00"})
            check(Math.abs(BridgePolicy.epoch(value)-haTime)<0.0001);
        for(String value:new String[]{null,"","2026-11-05T14:51:49","2026-02-30T14:51:49Z","NaN"})
            check(Double.isNaN(BridgePolicy.epoch(value)));
        check(BridgePolicy.requestError("curtain","close","request-123",haTime,BridgePolicy.epoch("2026-11-05T14:51:49.293387+00:00"),"authorized-user",users,haTime+1).isEmpty());
        check("request_expired".equals(BridgePolicy.requestError("curtain","close","request-123",haTime,haTime,"authorized-user",users,haTime+16)));
        for (String target : new String[]{"curtain", "sheer"}) for (String action : new String[]{"open", "close", "stop"})
            check(BridgePolicy.requestError(target, action, "request-123", 100, 100, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("plug", "open", "request-123", 100, 100, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "toggle", "request-123", 100, 100, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "open", "bad", 100, 100, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "open", "request-123", 100, 100, null, users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "open", "request-123", 100, 100, "other", users, 101).isEmpty());
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 1, 150})
            check(!BridgePolicy.requestError("curtain", "open", "request-123", value, 100, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "open", "request-123", 100, 1, "authorized-user", users, 101).isEmpty());
        check(!BridgePolicy.requestError("curtain", "open", "request-123", 100, 110, "authorized-user", users, 111).isEmpty());
        check(BridgePolicy.position("0%") == 0 && BridgePolicy.position("100%") == 100 && BridgePolicy.position("10%") == 10);
        for (String value : new String[]{null, "101%", "-1%", "10", "10.5%", "010%"}) check(BridgePolicy.position(value) == -1);
        check(BridgePolicy.fresh(100, 101)); check(!BridgePolicy.fresh(100, 221)); check(!BridgePolicy.fresh(150, 100));
        check(BridgePolicy.controlLabel("toggle").isEmpty());
        System.out.println("bridge_policy_checks_passed=" + checks + "; device_actions=0");
    }
}
