import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONArray;

/** Real shell UID and official app; actuator clicks target unique verified live nodes. */
public final class AndroidCurtainUi implements AutoCloseable {
    private static final String PKG = "com.alibaba.ailabs.tg";
    private static final String HOME = PKG + "/com.alibaba.ailabs.tg.activity.HomeActivity";
    private static final String PANEL = PKG + "/com.alibaba.ailabs.tg.dynamic.windvane.WVUCWebViewActivity";
    private static final String LAMP_PANEL = PKG + "/com.alipay.mobile.nebulax.integration.mpaas.activity.NebulaActivity$Main";
    private static final String MARKETING = PKG + "/com.alibaba.ailabs.tg.oneservice.cluster.services.h5alert.MarketingPopupWebViewActivity";
    private final HandlerThread thread = new HandlerThread("homepad-tmall-accessibility");
    private UiAutomation automation;
    private VirtualDisplay display;
    private ImageReader frames;
    private int displayId = -1;
    private final Context context;
    public long uiActions;
    public long uiActionAttempts;
    public JSONArray controlDiagnostics=new JSONArray();
    public volatile JSONObject commandEvidence=new JSONObject();
    private volatile long actionEventStart;
    private volatile String vendorUiResult="";

    public AndroidCurtainUi() throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("real_shell_uid_required");
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object appThread = at.getMethod("systemMain").invoke(null);
        Context system = (Context)at.getMethod("getSystemContext").invoke(appThread);
        context = new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public Context createPackageContext(String name, int flags) { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
            }
        };
        thread.start();
    }
    private Object tasks() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
    private List<?> roots() throws Exception {
        Object manager = tasks(); return (List<?>)manager.getClass().getMethod("getAllRootTaskInfos").invoke(manager);
    }
    private static boolean visible(Object root) throws Exception {
        try { return root.getClass().getField("visible").getBoolean(root); }
        catch (NoSuchFieldException e) { return root.getClass().getField("isVisible").getBoolean(root); }
    }
    public boolean primaryReady() throws Exception {
        boolean home = false;
        for (Object root : roots()) {
            if (root.getClass().getField("displayId").getInt(root) != 0 || !visible(root)) continue;
            ComponentName top = (ComponentName)root.getClass().getField("topActivity").get(root);
            if (top == null) continue;
            if ("com.alex.minimalhome".equals(top.getPackageName())) home = true;
            else return false;
        }
        Object wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        return home && !(Boolean)wm.getClass().getMethod("isKeyguardLocked").invoke(wm);
    }
    public void focusHome() throws Exception {
        if (!primaryReady()) return;
        Object manager = tasks();
        for (Object root : roots()) {
            ComponentName top = (ComponentName)root.getClass().getField("topActivity").get(root);
            if (root.getClass().getField("displayId").getInt(root) == 0 && visible(root)
                    && top != null && "com.alex.minimalhome".equals(top.getPackageName())) {
                int id = root.getClass().getField("taskId").getInt(root);
                manager.getClass().getMethod("setFocusedTask", int.class).invoke(manager, id); return;
            }
        }
    }
    private String top() throws Exception {
        for (Object root : roots()) if (root.getClass().getField("displayId").getInt(root) == displayId && visible(root)) {
            ComponentName top = (ComponentName)root.getClass().getField("topActivity").get(root);
            if (top != null) return top.flattenToString();
        }
        return "";
    }
    private static void command(String... args) throws Exception {
        java.lang.Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (java.io.InputStream stream = p.getInputStream()) { byte[] buffer = new byte[2048]; while (stream.read(buffer) >= 0) {} }
        if (p.waitFor() != 0) throw new IllegalStateException("android_navigation_failed");
    }
    public void ensureSession() throws Exception {
        if (!primaryReady()) throw new IllegalStateException("primary_not_dashboard");
        if (display != null) return;
        command("/system/bin/am", "force-stop", PKG);
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class); ctor.setAccessible(true);
        DisplayManager manager = ctor.newInstance(context);
        frames = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
        frames.setOnImageAvailableListener(reader -> { try (Image image = reader.acquireLatestImage()) {} catch (Exception ignored) {} }, new Handler(thread.getLooper()));
        // Shell-owned trusted display, own group, destroy content on removal. No physical mirroring.
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1<<6) | (1<<7) | (1<<8) | (1<<10) | (1<<11) | (1<<12);
        display = manager.createVirtualDisplay("HomePad-Tmall-Bridge", 720, 1280, 240, frames.getSurface(), flags);
        if (display == null || (displayId = display.getDisplay().getDisplayId()) <= 0) throw new SecurityException("virtual_display_required");
        Object wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, displayId, 2);
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        for (Constructor<?> constructor : UiAutomation.class.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length == 2 && constructor.getParameterTypes()[0] == Looper.class) {
                constructor.setAccessible(true); automation = (UiAutomation)constructor.newInstance(thread.getLooper(), connection); break;
            }
        }
        if (automation == null) throw new IllegalStateException("accessibility_unavailable");
        UiAutomation.class.getMethod("connect", int.class).invoke(automation, 1);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        automation.setOnAccessibilityEventListener(event->{
            if(actionEventStart==0||event.getEventTime()<actionEventStart||event.getEventType()!=AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                    ||event.getPackageName()==null||!PKG.contentEquals(event.getPackageName()))return;
            for(CharSequence item:event.getText()){
                String text=item==null?"":item.toString();
                for(String label:Arrays.asList("操作失败","控制失败","设备离线","网络异常","设备不在线"))if(text.contains(label))vendorUiResult="failure";
                if(vendorUiResult.isEmpty())for(String label:Arrays.asList("操作成功","控制成功","指令已发送"))if(text.contains(label))vendorUiResult="acknowledged";
            }
        });
        command("/system/bin/am", "start", "--display", String.valueOf(displayId), "-f", "0x10000000", "-n", PKG + "/.splash.WelcomeActivity", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER");
        long until = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < until && !HOME.equals(top())) {
            if (!primaryReady()) throw new SecurityException("primary_changed"); Thread.sleep(300);
            if (top().contains("UserLoginActivity") || top().contains("LoginStartActivity")) throw new IllegalStateException("login_required");
        }
        if (!HOME.equals(top())) throw new IllegalStateException("vendor_home_unavailable");
        focusHome();
    }
    private AccessibilityNodeInfo root() throws Exception {
        long until=System.currentTimeMillis()+2000;
        while(true){
        if (!primaryReady() || display == null) throw new SecurityException("display_guard");
        SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all.get(displayId);
        AccessibilityNodeInfo selected=null;int layer=Integer.MIN_VALUE,foreignLayer=Integer.MIN_VALUE;
        if(windows!=null)for(AccessibilityWindowInfo window:windows){
            AccessibilityNodeInfo node=window.getRoot();if(node==null)continue;
            if(PKG.contentEquals(node.getPackageName())&&window.getLayer()>layer){
                if(selected!=null)selected.recycle();selected=node;layer=window.getLayer();
            }else{if(!PKG.contentEquals(node.getPackageName()))foreignLayer=Math.max(foreignLayer,window.getLayer());node.recycle();}
        }
        if(foreignLayer!=Integer.MIN_VALUE&&(selected==null||foreignLayer>=layer)){if(selected!=null)selected.recycle();throw new SecurityException("unexpected_vendor_window");}
        if(selected!=null)return selected;
        if(System.currentTimeMillis()>=until)throw new IllegalStateException("vendor_window_loading_timeout");
        Thread.sleep(100);
        }
    }
    private static void resourceNodes(AccessibilityNodeInfo node,String id,List<AccessibilityNodeInfo> out,int depth){
        if(node==null||depth>40||!node.isVisibleToUser())return;
        if((PKG+":id/"+id).equals(node.getViewIdResourceName()))out.add(AccessibilityNodeInfo.obtain(node));
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);
            try{resourceNodes(child,id,out,depth+1);}finally{if(child!=null)child.recycle();}}
    }
    private void dismissReadOnlyPrompt()throws Exception{
        if(!primaryReady())throw new SecurityException("primary_changed");
        if(MARKETING.equals(top())){
            command("/system/bin/input","-d",String.valueOf(displayId),"keyevent","4");Thread.sleep(250);return;
        }
        if(!HOME.equals(top()))return;
        AccessibilityNodeInfo page=root();List<AccessibilityNodeInfo> messages=new ArrayList<>(),negative=new ArrayList<>();
        try{
            if(!has(page,"通知","tg_dialog_title"))return;
            resourceNodes(page,"tg_dialog_message",messages,0);resourceNodes(page,"tg_dialog_negative_button",negative,0);
            if(messages.size()!=1||negative.size()!=1)throw new SecurityException("notification_prompt_ambiguous");
            CharSequence value=messages.get(0).getText();String text=value==null?"":value.toString();
            if(!text.contains("天猫精灵")||!text.contains("通知")||(!text.contains("允许")&&!text.contains("权限")))throw new SecurityException("notification_prompt_unknown");
            AccessibilityNodeInfo button=negative.get(0);
            if(!button.isClickable()||!button.isEnabled()||!button.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("notification_decline_failed");
            Thread.sleep(250);
        }finally{for(AccessibilityNodeInfo node:messages)node.recycle();for(AccessibilityNodeInfo node:negative)node.recycle();page.recycle();}
    }
    private static void textNodes(AccessibilityNodeInfo node, String text, String resource, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser()) return;
        boolean matches=(node.getText()!=null&&text.equals(node.getText().toString().trim()))
            ||(node.getContentDescription()!=null&&text.equals(node.getContentDescription().toString().trim()));
        if (!node.isPassword() && !node.isEditable() && matches
                && (resource == null || (PKG + ":id/" + resource).equals(node.getViewIdResourceName()))) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { textNodes(child, text, resource, out, depth+1); } finally { if (child != null) child.recycle(); }
        }
    }
    private static boolean has(AccessibilityNodeInfo root, String text, String resource) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>(); textNodes(root, text, resource, nodes, 0);
        boolean value = !nodes.isEmpty(); for (AccessibilityNodeInfo node : nodes) node.recycle(); return value;
    }
    private void goHome() throws Exception {
        dismissReadOnlyPrompt();
        String activity = top();
        if (PANEL.equals(activity) || LAMP_PANEL.equals(activity)) {
            AccessibilityNodeInfo current = root();
            try {
                boolean expected = PANEL.equals(activity)
                        ? has(current, "智能窗帘V7", "va_my_nav_bar_title") : lampPanel(current);
                if (!expected) throw new SecurityException("unexpected_panel");
            }
            finally { current.recycle(); }
            command("/system/bin/input", "-d", String.valueOf(displayId), "keyevent", "4");
            long until = System.currentTimeMillis()+3000;
            while (!HOME.equals(top()) && System.currentTimeMillis()<until) Thread.sleep(150);
        }
        if (!HOME.equals(top())) throw new IllegalStateException("vendor_home_required");
    }
    private static boolean lampPanel(AccessibilityNodeInfo root) {
        return has(root, "灯", null) && has(root, "亮度", null);
    }

    /** Opens the uniquely named room's device card. The quick-control child is never clicked. */
    public JSONObject readLampStatus(BooleanSupplier keepReading) throws Exception {
        ensureSession();
        goHome();
        boolean opened = false;
        long deadline = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < deadline) {
            if (!keepReading.getAsBoolean()) throw new IllegalStateException("lamp_read_interrupted");
            dismissReadOnlyPrompt();
            AccessibilityNodeInfo page = root();
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            try {
                cards(page, "灯", "客厅", false, matches, 0);
                if (matches.size() > 1 || !HOME.equals(top()) || !primaryReady())
                    throw new SecurityException("lamp_card_unverified");
                if (matches.size() == 1) {
                    opened = matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (!opened) throw new IllegalStateException("lamp_panel_navigation_failed");
                    break;
                }
            } finally {
                for (AccessibilityNodeInfo match : matches) match.recycle();
                page.recycle();
            }
            Thread.sleep(250);
        }
        if (!opened) throw new IllegalStateException("lamp_card_not_found");

        try {
            deadline = System.currentTimeMillis() + 6500;
            String previous = "";
            while (System.currentTimeMillis() < deadline) {
                if (!keepReading.getAsBoolean()) throw new IllegalStateException("lamp_read_interrupted");
                if (!primaryReady()) throw new SecurityException("primary_changed");
                String power = "";
                if (LAMP_PANEL.equals(top())) {
                    AccessibilityNodeInfo page = root();
                    try {
                        boolean on = has(page, "设备已开启", null) || has(page, "设备已打开", null);
                        boolean off = has(page, "设备已关闭", null);
                        if (lampPanel(page) && on != off && !has(page, "设备离线", null)
                                && !has(page, "设备未连网", null)) power = on ? "on" : "off";
                    } finally { page.recycle(); }
                }
                if (!power.isEmpty() && power.equals(previous))
                    return new JSONObject().put("power", power).put("online", true)
                            .put("observed", System.currentTimeMillis() / 1000.0).put("source", "official_ui");
                previous = power;
                Thread.sleep(550);
            }
            throw new IllegalStateException("lamp_state_unverified");
        } finally {
            goHome();
            focusHome();
        }
    }

    private static void cards(AccessibilityNodeInfo node, String title, List<AccessibilityNodeInfo> out, int depth) {
        cards(node, title, "主卧", true, out, depth);
    }
    private static void cards(AccessibilityNodeInfo node, String title, String room, boolean requireOnline,
            List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 45 || !node.isVisibleToUser()) return;
        if ((PKG + ":id/rl_device_card").equals(node.getViewIdResourceName()) && node.isClickable() && node.isEnabled()
                && has(node, title, "tv_device_title") && has(node, room, "tv_device_location")
                && (requireOnline ? has(node, "已连网", "tv_device_status") : !has(node, "未连网", "tv_device_status")))
            out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { cards(child, title, room, requireOnline, out, depth+1); } finally { if (child != null) child.recycle(); }
        }
    }
    private static void positions(AccessibilityNodeInfo node, List<Integer> values, int depth) {
        if (node == null || depth>45 || !node.isVisibleToUser()) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!node.isPassword() && !node.isEditable() && node.getText()!=null && bounds.top < 300) {
            int value=BridgePolicy.position(node.getText().toString().trim()); if (value>=0) values.add(value);
        }
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo child=node.getChild(i);
            try { positions(child,values,depth+1); } finally { if(child!=null)child.recycle(); } }
    }
    private static AccessibilityNodeInfo button(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> labels = new ArrayList<>(); textNodes(root,label,null,labels,0);
        AccessibilityNodeInfo result = null;
        try {
            if(labels.size()!=1)return null;
            AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(labels.get(0));
            for(int depth=0;node!=null&&depth<8;depth++) {
                Rect b=new Rect();node.getBoundsInScreen(b);
                if(node.isClickable()&&node.isEnabled()&&node.isVisibleToUser()&&b.top>=650&&b.bottom<=1000&&b.width()>0&&b.width()<=300&&b.height()<=220) {
                    result=node;node=null;break;
                }
                AccessibilityNodeInfo parent=node.getParent();node.recycle();node=parent;
            }
            if(node!=null)node.recycle();
            return result;
        } finally { for(AccessibilityNodeInfo node:labels)node.recycle(); }
    }
    private JSONObject observe(AccessibilityNodeInfo root) throws Exception {
        if(!has(root,"智能窗帘V7","va_my_nav_bar_title")||!has(root,"窗帘打开位置",null)||!has(root,"位置",null))throw new SecurityException("unexpected_cover_schema");
        List<Integer> values=new ArrayList<>();positions(root,values,0);
        if(values.size()!=1)throw new IllegalStateException("ambiguous_position");
        boolean controls=true;
        controlDiagnostics=new JSONArray();diagnoseControls(root,controlDiagnostics,0);
        List<Rect> bounds=new ArrayList<>();
        for(String action:Arrays.asList("open","close","stop")) {
            AccessibilityNodeInfo node=button(root,BridgePolicy.controlLabel(action));
            if(node==null){controls=false;continue;}
            Rect b=new Rect();node.getBoundsInScreen(b);node.recycle();
            // UC WebView rounds CSS bounds outward: adjacent buttons overlap by 1-2 px.
            // Actions target the verified accessibility node, not a point in that overlap.
            for(Rect other:bounds){
                if(Math.abs(b.centerX()-other.centerX())<100)controls=false;
                if(Rect.intersects(b,other)&&Math.min(b.right,other.right)-Math.max(b.left,other.left)>4)controls=false;
            }
            bounds.add(b);
        }
        return new JSONObject().put("position",values.get(0)).put("position_trusted",false).put("online",true).put("ready",controls)
            .put("observed",System.currentTimeMillis()/1000.0).put("source","official_ui");
    }
    private static void settingImages(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out,int depth){
        if(node==null||depth>5||!node.isVisibleToUser())return;
        Rect b=new Rect();node.getBoundsInScreen(b);
        if(node.isEnabled()&&node.isClickable()&&"android.widget.ImageView".contentEquals(node.getClassName())&&b.top>=0&&b.bottom<80&&b.left>620)
            out.add(AccessibilityNodeInfo.obtain(node));
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);
            try{settingImages(child,out,depth+1);}finally{if(child!=null)child.recycle();}}
    }
    private static int labelCount(AccessibilityNodeInfo page,String label){
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();textNodes(page,label,null,nodes,0);
        int count=nodes.size();for(AccessibilityNodeInfo node:nodes)node.recycle();return count;
    }
    /** Verify the official settings page's device name and room before any actuator click. */
    private void verifyTargetIdentity(String target)throws Exception{
        // First paint can precede working page navigation. Require stability first;
        // the settings round-trip then proves an actual response from the page.
        long stableUntil=System.currentTimeMillis()+2200;
        while(System.currentTimeMillis()<stableUntil){
            if(!primaryReady()||!PANEL.equals(top()))throw new SecurityException("identity_loading_guard");
            AccessibilityNodeInfo loading=root();
            try{if(!has(loading,"智能窗帘V7","va_my_nav_bar_title")||!has(loading,"窗帘打开位置",null))throw new SecurityException("identity_model_changed");}
            finally{loading.recycle();}
            Thread.sleep(150);
        }
        AccessibilityNodeInfo page=root();List<AccessibilityNodeInfo> parents=new ArrayList<>(),images=new ArrayList<>();
        try{
            if(!PANEL.equals(top())||!has(page,"智能窗帘V7","va_my_nav_bar_title"))throw new SecurityException("identity_panel_guard");
            resourceNodes(page,"va_nav_bar_right",parents,0);
            if(parents.size()!=1)throw new SecurityException("identity_settings_ambiguous");
            settingImages(parents.get(0),images,0);
            if(images.size()!=1||!primaryReady())throw new SecurityException("identity_settings_ambiguous");
            if(!images.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("identity_navigation_failed");
        }finally{page.recycle();for(AccessibilityNodeInfo node:parents)node.recycle();for(AccessibilityNodeInfo node:images)node.recycle();}
        boolean verified=false;long until=System.currentTimeMillis()+3500;
        while(System.currentTimeMillis()<until){
            if(!primaryReady()||!PANEL.equals(top()))throw new SecurityException("identity_activity_changed");
            page=root();
            try{
                if(has(page,"设备名称",null)){
                    verified=BridgePolicy.targetIdentity(target,labelCount(page,"窗帘"),labelCount(page,"窗纱"),labelCount(page,"主卧"),labelCount(page,"设备名称"));
                    if(!verified)throw new SecurityException("identity_target_mismatch");
                    break;
                }
            }finally{page.recycle();}
            Thread.sleep(150);
        }
        if(!verified)throw new IllegalStateException("identity_read_timeout");
        command("/system/bin/input","-d",String.valueOf(displayId),"keyevent","4");
        until=System.currentTimeMillis()+3500;long stableSince=0;int window=-1;
        while(System.currentTimeMillis()<until){
            if(!primaryReady()||!PANEL.equals(top()))throw new SecurityException("identity_return_guard");
            page=root();boolean ready=false;
            try{
                if(has(page,"智能窗帘V7","va_my_nav_bar_title")&&has(page,"窗帘打开位置",null)){
                    JSONObject report=observe(page);ready=report.getBoolean("ready");
                    if(page.getWindowId()!=window){window=page.getWindowId();stableSince=0;}
                }
            }finally{page.recycle();}
            if(ready){if(stableSince==0)stableSince=System.currentTimeMillis();if(System.currentTimeMillis()-stableSince>=700)return;}
            else stableSince=0;
            Thread.sleep(150);
        }
        throw new IllegalStateException("identity_return_timeout");
    }
    private static void diagnoseControls(AccessibilityNodeInfo node,JSONArray out,int depth)throws Exception{
        if(node==null||depth>45||!node.isVisibleToUser()||out.length()>12)return;
        if(!node.isPassword()&&!node.isEditable())for(String label:Arrays.asList("打开窗帘","停止窗帘","关闭窗帘")){
            String text=node.getText()==null?"":node.getText().toString();
            String description=node.getContentDescription()==null?"":node.getContentDescription().toString();
            if(text.contains(label)||description.contains(label)){
                JSONObject item=new JSONObject().put("label",label).put("exact_text",text.trim().equals(label)).put("exact_description",description.trim().equals(label));
                JSONArray parents=new JSONArray();AccessibilityNodeInfo parent=AccessibilityNodeInfo.obtain(node);
                for(int i=0;i<6&&parent!=null;i++){
                    Rect b=new Rect();parent.getBoundsInScreen(b);
                    parents.put(new JSONObject().put("class",String.valueOf(parent.getClassName())).put("clickable",parent.isClickable())
                        .put("bounds",new JSONArray(Arrays.asList(b.left,b.top,b.right,b.bottom))).put("actions",parent.getActions()));
                    AccessibilityNodeInfo next=parent.getParent();parent.recycle();parent=next;
                }
                if(parent!=null)parent.recycle();item.put("ancestors",parents);out.put(item);
            }
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);
            try{diagnoseControls(child,out,depth+1);}finally{if(child!=null)child.recycle();}}
    }
    public JSONObject read(String target, String action, BooleanSupplier authorize) throws Exception {
        if(!"curtain".equals(target)&&!"sheer".equals(target))throw new SecurityException("target_refused");
        if(!action.isEmpty()&&BridgePolicy.controlLabel(action).isEmpty())throw new SecurityException("action_refused");
        ensureSession();goHome();
        boolean opened=false;long cardsUntil=System.currentTimeMillis()+9000;
        while(System.currentTimeMillis()<cardsUntil){
            dismissReadOnlyPrompt();
            AccessibilityNodeInfo home=root();List<AccessibilityNodeInfo> matches=new ArrayList<>();
            try {
                cards(home,"curtain".equals(target)?"窗帘":"窗纱",matches,0);
                if(matches.size()>1||!primaryReady()||!HOME.equals(top()))throw new SecurityException("target_ambiguous_or_offline");
                if(matches.size()==1){
                    if(!matches.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("panel_navigation_failed");
                    opened=true;break;
                }
            }finally{for(AccessibilityNodeInfo node:matches)node.recycle();home.recycle();}
            Thread.sleep(300);
        }
        if(!opened)throw new IllegalStateException("target_offline_or_not_loaded");
        JSONObject observation=null;long until=System.currentTimeMillis()+9000;
        while(System.currentTimeMillis()<until) {
            if(!primaryReady())throw new SecurityException("primary_changed");
            if(PANEL.equals(top())) {
                AccessibilityNodeInfo page=root();
                try{observation=observe(page);break;}catch(IllegalStateException|SecurityException pending){}
                finally{page.recycle();}
            }
            Thread.sleep(250);
        }
        if(observation==null)throw new IllegalStateException("panel_read_timeout");
        if(!action.isEmpty()){
            commandEvidence=new JSONObject().put("target",target).put("action",action).put("phase","verifying_target");
            verifyTargetIdentity(target);
            commandEvidence=new JSONObject().put("target",target).put("action",action).put("identity_verified",true).put("phase","target_verified");
        }
        focusHome();
        if(!action.isEmpty()) {
            if(!observation.getBoolean("ready")||!authorize.getAsBoolean()||!primaryReady()||!PANEL.equals(top()))throw new SecurityException("command_expired_or_unavailable");
            AccessibilityNodeInfo page=root(), control=null;
            try {
                JSONObject fresh=observe(page);
                if(!fresh.getBoolean("ready"))throw new SecurityException("capabilities_changed");
                control=button(page,BridgePolicy.controlLabel(action));
                if(control==null||!authorize.getAsBoolean()||!primaryReady())throw new SecurityException("final_command_guard");
                vendorUiResult="";actionEventStart=android.os.SystemClock.uptimeMillis();uiActionAttempts++;
                commandEvidence=new JSONObject().put("target",target).put("action",action).put("identity_verified",true).put("input_method","accessibility_click").put("phase","click_attempted");
                if(!control.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("device_action_not_applied");
                uiActions++;
                commandEvidence=new JSONObject().put("target",target).put("action",action).put("identity_verified",true).put("input_method","accessibility_click").put("phase","clicked_unconfirmed");
            }finally{if(control!=null)control.recycle();page.recycle();}
            // Let the official app finish its asynchronous work before leaving this page.
            long settleUntil=System.currentTimeMillis()+6000;
            while(System.currentTimeMillis()<settleUntil){
                if(!primaryReady()||!PANEL.equals(top()))throw new SecurityException("post_click_activity_changed");
                if("failure".equals(vendorUiResult)){
                    commandEvidence.put("phase","vendor_reported_failure");throw new IllegalStateException("vendor_reported_failure");
                }
                Thread.sleep(250);
            }
            actionEventStart=0;
            commandEvidence.put("phase","acknowledged".equals(vendorUiResult)?"vendor_ui_acknowledged":"clicked_unconfirmed");
            page=root();try{observation=observe(page);}finally{page.recycle();}
        }
        goHome();focusHome();return observation;
    }
    private AccessibilityNodeInfo primaryRoot()throws Exception{
        if(!primaryReady())throw new SecurityException("primary_not_dashboard");
        List<AccessibilityWindowInfo> windows=automation.getWindowsOnAllDisplays().get(0);
        AccessibilityNodeInfo selected=null;int layer=Integer.MIN_VALUE;
        if(windows!=null)for(AccessibilityWindowInfo window:windows){
            AccessibilityNodeInfo node=window.getRoot();
            if(node==null)continue;
            if("com.alex.minimalhome".contentEquals(node.getPackageName())&&window.getLayer()>layer){
                if(selected!=null)selected.recycle();selected=node;layer=window.getLayer();
            }else node.recycle();
        }
        if(selected==null)throw new SecurityException("primary_home_window_unavailable");return selected;
    }
    private static AccessibilityNodeInfo firstClickable(AccessibilityNodeInfo node){
        AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<7&&current!=null;i++){
            Rect b=new Rect();current.getBoundsInScreen(b);
            if(current.isVisibleToUser()&&current.isEnabled()&&current.isClickable()&&b.height()>0&&b.height()<=300)return current;
            AccessibilityNodeInfo next=current.getParent();current.recycle();current=next;
        }
        if(current!=null)current.recycle();return null;
    }
    private static void byClass(AccessibilityNodeInfo node,String cls,List<AccessibilityNodeInfo> out,int depth){
        if(node==null||depth>45||!node.isVisibleToUser())return;
        if(cls.contentEquals(node.getClassName()))out.add(AccessibilityNodeInfo.obtain(node));
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);
            try{byClass(child,cls,out,depth+1);}finally{if(child!=null)child.recycle();}}
    }
    private JSONObject inspectPlugPowerOnly()throws Exception{
        for(int step=0;step<7;step++){
            AccessibilityNodeInfo page=primaryRoot();List<AccessibilityNodeInfo> on=new ArrayList<>(),off=new ArrayList<>();
            try{
                textNodes(page,"客厅 · 烤箱插座，开启，确认后执行",null,on,0);
                textNodes(page,"客厅 · 烤箱插座，关闭，确认后执行",null,off,0);
                if(on.size()==1&&off.size()==1){
                    AccessibilityNodeInfo row=on.get(0).getParent(),card=row==null?null:row.getParent();
                    try{if(card!=null&&has(card,"烤箱插座",null))card.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());}
                    finally{if(card!=null)card.recycle();if(row!=null)row.recycle();}
                    return new JSONObject().put("open_visible",true).put("close_visible",true)
                        .put("open_enabled",on.get(0).isEnabled()).put("close_enabled",off.get(0).isEnabled())
                        .put("actuator_inputs",0);
                }
                List<AccessibilityNodeInfo> scrolls=new ArrayList<>();byClass(page,"android.widget.ScrollView",scrolls,0);
                try{if(scrolls.size()!=1||!scrolls.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))break;}
                finally{for(AccessibilityNodeInfo node:scrolls)node.recycle();}
            }finally{page.recycle();for(AccessibilityNodeInfo node:on)node.recycle();for(AccessibilityNodeInfo node:off)node.recycle();}
            Thread.sleep(300);
        }
        return new JSONObject().put("open_visible",false).put("close_visible",false).put("actuator_inputs",0);
    }
    /** Only opens the control center, scrolls its list and inspects an action menu. */
    public JSONObject inspectDashboard()throws Exception{
        ensureSession();focusHome();JSONObject result=new JSONObject().put("device_actions_sent",0);
        AccessibilityNodeInfo page=primaryRoot();
        try{
            if(!has(page,"返回首页",null)){
                List<AccessibilityNodeInfo> labels=new ArrayList<>();textNodes(page,"控制中心",null,labels,0);
                try{
                    if(labels.size()!=1)throw new SecurityException("unique_control_center_entry_required");
                    AccessibilityNodeInfo entry=firstClickable(labels.get(0));
                    try{if(entry==null||!entry.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("control_center_entry_unavailable");}
                    finally{if(entry!=null)entry.recycle();}
                }finally{for(AccessibilityNodeInfo node:labels)node.recycle();}
            }
        }finally{page.recycle();}
        Thread.sleep(1400);boolean curtain=false,sheer=false,available=false,refreshed=false;
        for(int step=0;step<7;step++){
            page=primaryRoot();
            try{
                if(!refreshed){
                    List<AccessibilityNodeInfo> refresh=new ArrayList<>();textNodes(page,"刷新",null,refresh,0);
                    try{if(refresh.size()==1){AccessibilityNodeInfo button=firstClickable(refresh.get(0));
                        try{if(button!=null){refreshed=button.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(800);}}
                        finally{if(button!=null)button.recycle();}}}
                    finally{for(AccessibilityNodeInfo node:refresh)node.recycle();}
                }
                curtain|=has(page,"主卧窗帘",null);sheer|=has(page,"主卧窗纱",null);
                available|=has(page,"可操作",null);
                if(curtain&&sheer)break;
                List<AccessibilityNodeInfo> scrolls=new ArrayList<>();byClass(page,"android.widget.ScrollView",scrolls,0);
                try{if(scrolls.size()!=1||!scrolls.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;}
                finally{for(AccessibilityNodeInfo node:scrolls)node.recycle();}
            }finally{page.recycle();}
            Thread.sleep(350);
        }
        result.put("curtain_visible",curtain).put("sheer_visible",sheer).put("available_label_visible",available);
        JSONArray directButtons=new JSONArray();page=primaryRoot();
        try{
            for(String name:Arrays.asList("主卧窗帘","主卧窗纱"))for(String label:Arrays.asList("打开","关闭")){
                List<AccessibilityNodeInfo> matches=new ArrayList<>();
                textNodes(page,"主卧 · "+name+"，"+label+"，确认后执行",null,matches,0);
                try{directButtons.put(new JSONObject().put("device",name).put("action",label).put("visible",matches.size()==1)
                    .put("enabled",matches.size()==1&&matches.get(0).isEnabled()).put("clickable",matches.size()==1&&matches.get(0).isClickable()));}
                finally{for(AccessibilityNodeInfo node:matches)node.recycle();}
            }
        }finally{page.recycle();}
        result.put("direct_cover_buttons",directButtons);
        page=primaryRoot();List<AccessibilityNodeInfo> names=new ArrayList<>();
        java.util.Set<AccessibilityNodeInfo> buttons=new java.util.HashSet<>();
        try{
            textNodes(page,"主卧窗帘",null,names,0);
            for(AccessibilityNodeInfo name:names){
                AccessibilityNodeInfo parent=AccessibilityNodeInfo.obtain(name);
                for(int depth=0;depth<5&&parent!=null;depth++){
                    List<AccessibilityNodeInfo> operations=new ArrayList<>();textNodes(parent,"操作…",null,operations,0);
                    try{if(operations.size()==1){AccessibilityNodeInfo button=firstClickable(operations.get(0));if(button!=null)buttons.add(button);break;}}
                    finally{for(AccessibilityNodeInfo node:operations)node.recycle();}
                    AccessibilityNodeInfo next=parent.getParent();parent.recycle();parent=next;
                }
                if(parent!=null)parent.recycle();
            }
            if(buttons.size()==1&&primaryReady()){
                if(!buttons.iterator().next().performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("action_menu_unavailable");
            }else throw new IllegalStateException("unique_curtain_menu_required");
        }finally{for(AccessibilityNodeInfo node:names)node.recycle();for(AccessibilityNodeInfo node:buttons)node.recycle();page.recycle();}
        Thread.sleep(250);page=primaryRoot();
        try{
            result.put("open_choice_visible",has(page,"打开",null)).put("close_choice_visible",has(page,"关闭",null)).put("stop_choice_visible",has(page,"停止",null));
            List<AccessibilityNodeInfo> cancel=new ArrayList<>();textNodes(page,"取消",null,cancel,0);
            try{if(cancel.size()!=1)throw new SecurityException("cancel_button_required");AccessibilityNodeInfo button=firstClickable(cancel.get(0));
                try{if(button==null||!button.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("menu_cancel_failed");}
                finally{if(button!=null)button.recycle();}}
            finally{for(AccessibilityNodeInfo node:cancel)node.recycle();}
        }finally{page.recycle();}
        result.put("oven_power_controls",inspectPlugPowerOnly());
        result.put("primary_dashboard_kept",primaryReady());return result;
    }
    public void endSession() {
        actionEventStart=0;
        // Stop delayed vendor callbacks BEFORE removing their destination display.
        boolean vendorOnPrimary=false;
        try{for(Object root:roots()){
            ComponentName top=(ComponentName)root.getClass().getField("topActivity").get(root);
            if(root.getClass().getField("displayId").getInt(root)==0&&visible(root)&&top!=null&&PKG.equals(top.getPackageName()))vendorOnPrimary=true;
        }}catch(Exception ignored){}
        // A user who deliberately opened the original app keeps their foreground app.
        if(!vendorOnPrimary)try { command("/system/bin/am","force-stop",PKG); } catch(Exception ignored){}
        if(automation!=null)try{UiAutomation.class.getMethod("disconnect").invoke(automation);}catch(Exception ignored){}
        automation=null;
        if(display!=null)display.release();display=null;displayId=-1;
        if(frames!=null)frames.close();frames=null;
        try{focusHome();}catch(Exception ignored){}
    }
    @Override public void close(){endSession();thread.quitSafely();}
}
