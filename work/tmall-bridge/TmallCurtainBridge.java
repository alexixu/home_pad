import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tablet-local bridge. HA traffic is loopback only; vendor login stays inside its app. */
public final class TmallCurtainBridge {
    private static final String COMMAND = "input_text.homepad_tmall_command";
    private static final String PREFIX = "input_text.homepad_tmall_";
    private final String token;
    private final Set<String> users = new HashSet<>();
    private final boolean controlsEnabled;
    private final boolean lampStatusEnabled;
    private final File directory;
    private final AndroidCurtainUi ui;
    private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(1);
    private final Set<String> seen = new LinkedHashSet<>();
    private volatile boolean stopped;
    private volatile boolean initialized;
    private volatile String lastError = "";
    private volatile String lastCommandError = "";
    private String lastLampError = "";
    private final AtomicBoolean cleaning=new AtomicBoolean();
    private long queries;
    private long lampQueries;
    private long rejected;

    private TmallCurtainBridge(File config) throws Exception {
        StructStat info=Os.lstat(config.getAbsolutePath());
        if(Process.myUid()!=2000||info.st_uid!=2000||(info.st_mode&077)!=0||!OsConstants.S_ISREG(info.st_mode)||info.st_size>16384)
            throw new SecurityException("private_owned_configuration_required");
        directory=config.getCanonicalFile().getParentFile();
        StructStat parent=Os.lstat(directory.getAbsolutePath());
        if(parent.st_uid!=2000||(parent.st_mode&077)!=0)throw new SecurityException("private_directory_required");
        JSONObject data;
        try(InputStream stream=new FileInputStream(config)){data=new JSONObject(read(stream,16384));}
        token=data.getString("ha_token");
        if(!token.matches("[A-Za-z0-9._-]{16,4096}"))throw new SecurityException("invalid_ha_configuration");
        JSONArray allowed=data.getJSONArray("allowed_user_ids");
        for(int i=0;i<allowed.length();i++){String user=allowed.getString(i);if(!user.matches("[a-f0-9]{32}"))throw new SecurityException("invalid_source_identity");users.add(user);}
        if(users.isEmpty()||users.size()>4)throw new SecurityException("source_identity_required");
        controlsEnabled=data.getBoolean("controls_enabled");
        lampStatusEnabled=data.optBoolean("lamp_status_enabled",false);
        ui=new AndroidCurtainUi();
    }
    private static String read(InputStream input,int limit)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
        for(int count;(count=input.read(buffer))>=0;){if(bytes.size()+count>limit)throw new IllegalStateException("response_limit");bytes.write(buffer,0,count);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private JSONObject http(String method,String path,JSONObject body)throws Exception{
        if(!("GET".equals(method)&&path.equals("/api/states/"+COMMAND))
                &&!("POST".equals(method)&&path.equals("/api/services/input_text/set_value")&&body!=null
                    &&(Arrays.asList(PREFIX+"curtain_report",PREFIX+"sheer_report").contains(body.optString("entity_id"))
                        ||lampStatusEnabled&&(PREFIX+"lamp_report").equals(body.optString("entity_id")))))
            throw new SecurityException("ha_path_not_allowed");
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8123"+path).openConnection(java.net.Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);
        connection.setConnectTimeout(3500);connection.setReadTimeout(3500);
        connection.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            if(connection.getResponseCode()!=200)throw new IllegalStateException("ha_request_failed");
            String result;try(InputStream in=connection.getInputStream()){result=read(in,65536);}
            return result.startsWith("{")?new JSONObject(result):new JSONObject();
        }finally{connection.disconnect();}
    }
    private static double now(){return System.currentTimeMillis()/1000.0;}
    private static String reason(Exception failure){
        Throwable cause=failure.getCause()==null?failure:failure.getCause();String value=cause.getMessage();
        return value!=null&&value.matches("[a-z_]{1,80}")?value:cause.getClass().getSimpleName();
    }
    private static double epoch(String value){return BridgePolicy.epoch(value);}
    private JSONObject request(JSONObject state)throws Exception{
        String value=state.optString("state","");
        if(value.length()>255||!value.startsWith("{"))throw new SecurityException("invalid_command_record");
        JSONObject command=new JSONObject(value);
        if(command.length()!=4||!command.has("target")||!command.has("action")||!command.has("id")||!command.has("created"))throw new SecurityException("invalid_command_fields");
        if(!(command.opt("target") instanceof String)||!(command.opt("action") instanceof String)
                ||!(command.opt("id") instanceof String)||!(command.opt("created") instanceof Number))throw new SecurityException("invalid_command_types");
        JSONObject context=state.optJSONObject("context");
        String error=BridgePolicy.requestError(command.optString("target"),command.optString("action"),command.optString("id"),
            command.optDouble("created",Double.NaN),epoch(state.optString("last_updated")),context==null?null:context.optString("user_id",null),users,now());
        if(!error.isEmpty())throw new SecurityException(error);
        command.put("_updated",state.getString("last_updated"));return command;
    }
    private boolean authorize(JSONObject command){
        if(stopped||new File(directory,"stop.request").exists()||!controlsEnabled)return false;
        try{
            JSONObject latest=request(http("GET","/api/states/"+COMMAND,null));
            return latest.getString("id").equals(command.getString("id"))&&latest.getString("target").equals(command.getString("target"))
                &&latest.getString("action").equals(command.getString("action"))&&latest.getString("_updated").equals(command.getString("_updated"));
        }catch(Exception invalid){return false;}
    }
    private void poll(){
        while(!stopped&&!new File(directory,"stop.request").exists()){
            try{
                JSONObject state=http("GET","/api/states/"+COMMAND,null);
                String value=state.optString("state","");
                String id="";
                if(value.startsWith("{")&&value.length()<=255)try{id=new JSONObject(value).optString("id","");}catch(Exception ignored){}
                if(!initialized){if(!id.isEmpty())seen.add(id);initialized=true;}
                else if(!id.isEmpty()&&!seen.contains(id)){
                    seen.add(id);if(seen.size()>256)seen.remove(seen.iterator().next());
                    try{
                        JSONObject command=request(state);
                        if(!controlsEnabled||!queue.offer(command)){rejected++;lastError="command_not_queued";lastCommandError=lastError;}
                    }catch(Exception invalid){rejected++;lastError="invalid_or_expired_command";lastCommandError="request_"+reason(invalid);}
                }
            }catch(Exception failure){lastError="ha_command_poll_unavailable";}
            try{Thread.sleep(1000);}catch(InterruptedException ignored){}
        }
    }
    private void publish(String target,JSONObject report)throws Exception{
        if(!controlsEnabled)report.put("ready",false);
        String value=report.toString();if(value.length()>255)throw new IllegalStateException("report_limit");
        http("POST","/api/services/input_text/set_value",new JSONObject().put("entity_id",PREFIX+target+"_report").put("value",value));
    }
    private void unavailable(String target){try{publish(target,new JSONObject().put("online",false).put("ready",false).put("observed",now()).put("source","official_ui"));}catch(Exception ignored){}}
    private static void writePrivateJson(String path, JSONObject value) throws Exception {
        FileDescriptor fd = Os.open(path,
                OsConstants.O_CREAT | OsConstants.O_WRONLY | OsConstants.O_TRUNC | OsConstants.O_NOFOLLOW, 0600);
        try (FileOutputStream out = new FileOutputStream(fd)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            // Android's FileOutputStream borrows a caller-supplied descriptor.
            if (fd.valid()) Os.close(fd);
        }
    }

    private synchronized void status() {
        try{
            JSONObject out=new JSONObject().put("pid",Process.myPid()).put("alive",!stopped).put("updated",now()).put("queries",queries)
                .put("device_actions",ui.uiActions).put("rejected",rejected).put("controls_enabled",controlsEnabled)
                .put("device_action_attempts",ui.uiActionAttempts).put("last_command_error",lastCommandError)
                .put("last_command_evidence",ui.commandEvidence)
                .put("lamp_status_enabled",lampStatusEnabled).put("lamp_queries",lampQueries).put("last_lamp_error",lastLampError)
                .put("command_poll_initialized",initialized).put("last_error",lastError);
            String temporary=new File(directory,"status.json.new").getAbsolutePath();
            writePrivateJson(temporary, out);
            Os.rename(temporary,new File(directory,"status.json").getAbsolutePath());
        }catch(Exception ignored){}
    }
    private void inspectDashboard()throws Exception{
        File request=new File(directory,"inspect-dashboard.request");
        if(!request.exists())return;
        request.delete();JSONObject proof;
        try{proof=ui.inspectDashboard();}catch(Exception failure){proof=new JSONObject().put("inspection_error",reason(failure)).put("device_actions_sent",0);}
        proof.put("observed",now());
        writePrivateJson(new File(directory,"dashboard-proof.json").getAbsolutePath(), proof);
    }
    private void stop(){
        if(!cleaning.compareAndSet(false,true))return;
        stopped=true;queue.clear();unavailable("curtain");unavailable("sheer");
        if(lampStatusEnabled)unavailable("lamp");
        ui.close();status();
    }
    private void run()throws Exception{
        Runtime.getRuntime().addShutdownHook(new Thread(()->stop(),"homepad-tmall-stop"));
        Thread poller=new Thread(()->poll(),"homepad-tmall-local-command-poll");poller.setDaemon(true);poller.start();
        unavailable("curtain");unavailable("sheer");
        if(lampStatusEnabled)unavailable("lamp");
        status();
        double nextRead=0, nextLampRead=0;
        File stopRequest=new File(directory,"stop.request");
        while(!stopped&&!stopRequest.exists()){
            try{
                JSONObject command=queue.poll(1,TimeUnit.SECONDS);
                if(command!=null){
                    String target=command.getString("target");
                    if(!authorize(command)){rejected++;lastError="command_no_longer_authorized";lastCommandError=lastError;}
                    else try{
                        JSONObject report=ui.read(target,command.getString("action"),()->authorize(command));queries++;
                        publish(target,report);lastError="";lastCommandError="";
                    }catch(Exception failure){rejected++;lastError="command_failed_no_retry";lastCommandError="command_"+reason(failure);unavailable(target);ui.endSession();}
                }
                if(now()>=nextRead){
                    nextRead=now()+30;
                    if(!ui.primaryReady()){
                        unavailable("curtain");unavailable("sheer");ui.endSession();lastError="waiting_for_dashboard";
                    }else{
                        for(String target:new String[]{"curtain","sheer"}){
                            if(stopRequest.exists())break;
                            try{JSONObject report=ui.read(target,"",()->false);queries++;publish(target,report);lastError="";}
                            catch(Exception failure){unavailable(target);lastError="read_"+reason(failure);ui.endSession();}
                            if(!queue.isEmpty())break;
                        }
                    }
                }
                if(lampStatusEnabled&&now()>=nextLampRead&&queue.isEmpty()&&!stopRequest.exists()){
                    nextLampRead=now()+60;
                    if(!ui.primaryReady()){
                        unavailable("lamp");
                        lastLampError="waiting_for_dashboard";
                    }else try{
                        JSONObject report=ui.readLampStatus(()->queue.isEmpty()&&!stopRequest.exists());
                        publish("lamp",report);
                        lampQueries++;
                        lastLampError="";
                    }catch(Exception failure){
                        lastLampError=reason(failure);
                        if(!"lamp_read_interrupted".equals(lastLampError)){
                            unavailable("lamp");
                            ui.endSession();
                        }
                    }
                }
                if(ui.primaryReady())try{inspectDashboard();}catch(Exception failure){lastError="dashboard_"+reason(failure);}
            }catch(Exception failure){lastError="cycle_"+reason(failure);ui.endSession();}
            status();
        }
        stop();
    }
    public static void main(String[] args){
        try{
            if(args.length==1&&"--read-once".equals(args[0])){
                JSONObject result=new JSONObject();
                try(AndroidCurtainUi ui=new AndroidCurtainUi()){
                    for(String target:new String[]{"curtain","sheer"})result.put(target,ui.read(target,"",()->false));
                    result.put("device_actions",ui.uiActions).put("primary_dashboard_kept",ui.primaryReady()).put("control_diagnostics",ui.controlDiagnostics);
                }
                System.out.println(result.toString());System.exit(0);
            }
            if(args.length!=1)throw new SecurityException("one_private_configuration_required");
            new TmallCurtainBridge(new File(args[0])).run();System.exit(0);
        }catch(Throwable failure){
            String reason=failure.getMessage();
            if(reason==null||!reason.matches("[a-z_]{1,80}"))reason="internal_error";
            System.out.println("{\"bridge_error\":\""+failure.getClass().getSimpleName()+"\",\"reason\":\""+reason+"\"}");System.exit(1);
        }
    }
}
