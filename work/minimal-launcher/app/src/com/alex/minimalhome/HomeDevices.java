package com.alex.minimalhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tablet-local registry projection and capability-gated controller. Never owns an HA token. */
public final class HomeDevices {
    public interface Callback { void done(JSONObject result); }
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    public static final class PreparedAction {
        public final String targetRef, action, utterance, name, catalogRevision;
        public final Object value;
        private final HomeDevices owner;
        private final Entry entry;
        private final HomeDevicePolicy.Capability capability;
        private final HomeControls.DeviceConnection connection;
        private final long generation, createdNanos;
        private final AtomicBoolean consumed=new AtomicBoolean();
        private PreparedAction(HomeDevices owner,Entry entry,HomeDevicePolicy.Capability capability,Object value,String utterance) {
            this.owner=owner; this.entry=entry; this.capability=capability; this.value=value;
            targetRef=entry.entity.ref; action=capability.action; this.utterance=utterance;
            name=entry.entity.name; catalogRevision=owner.current.revision;
            connection=owner.current.connection; generation=owner.generation; createdNanos=System.nanoTime();
        }
    }
    private static final class Device {
        final String id,name,room,model;
        final List<HomeDevicePolicy.Entity> entities;
        Device(String id,String name,String room,String model,List<HomeDevicePolicy.Entity> entities) {
            this.id=id;this.name=name;this.room=room;this.model=model;this.entities=Collections.unmodifiableList(entities);
        }
    }
    private static final class Manifest {
        final List<Device> devices; final String digest,temperatureUnit,connectionBinding; final JSONObject stored;
        Manifest(List<Device> devices,JSONObject stored) {this.devices=Collections.unmodifiableList(devices);this.stored=stored;temperatureUnit=stored.optString("temperature_unit","");connectionBinding=stored.optString("connection_binding","");digest=HomeDevicePolicy.hash(stored.toString());}
    }
    private static final class Entry {
        final HomeDevicePolicy.Entity entity; final JSONObject state;
        final List<HomeDevicePolicy.Capability> capabilities;
        Entry(HomeDevicePolicy.Entity entity,JSONObject state,Set<String> services) {
            this.entity=entity;this.state=state==null?null:HomeDevicePolicy.copy(state);
            capabilities=Collections.unmodifiableList(HomeDevicePolicy.capabilities(entity,state,services));
        }
        JSONObject json() {
            JSONObject out=HomeDevicePolicy.object();HomeDevicePolicy.put(out,"ref",entity.ref);
            HomeDevicePolicy.put(out,"name",entity.name);HomeDevicePolicy.put(out,"domain",entity.domain);
            String value=HomeDevicePolicy.publicState(entity,state);
            HomeDevicePolicy.put(out,"state",value.isEmpty()?"unknown":value);
            JSONObject attrs=state==null?null:state.optJSONObject("attributes");
            HomeDevicePolicy.put(out,"unit",attrs==null?"":HomeDevicePolicy.safe(attrs.optString("unit_of_measurement",""),24));
            String time=state==null?"":state.optString("last_updated","");
            HomeDevicePolicy.put(out,"recorded_at",time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(?:Z|[+-][0-9]{2}:[0-9]{2})")?time:"");
            JSONArray actions=new JSONArray();for(HomeDevicePolicy.Capability c:capabilities)actions.put(c.json());
            HomeDevicePolicy.put(out,"actions",actions);return out;
        }
    }
    /** Deep-copy JSON projection only: no raw state attributes, entity IDs, address, registry IDs or token. */
    public static final class Snapshot {
        private final Manifest manifest;
        private final Map<String,Entry> entries;
        private final Set<String> services;
        private final HomeControls.DeviceConnection connection;
        private final String revision,error;
        private Snapshot(Manifest manifest,Map<String,Entry> entries,Set<String> services,HomeControls.DeviceConnection connection,String error) {
            this.manifest=manifest;this.entries=Collections.unmodifiableMap(entries);
            this.services=Collections.unmodifiableSet(new HashSet<>(services));this.connection=connection;this.error=error;
            StringBuilder value=new StringBuilder(manifest==null?"empty":manifest.digest);
            for(Entry entry:entries.values()) {value.append('|').append(entry.entity.ref);for(HomeDevicePolicy.Capability c:entry.capabilities)value.append('|').append(c.signature());}
            revision=HomeDevicePolicy.hash(value.toString());
        }
        public JSONObject toJson(String query) {
            if(query==null||query.length()>64)return error("invalid_query");
            JSONObject out=HomeDevicePolicy.object();HomeDevicePolicy.put(out,"ok",error.isEmpty());
            if(!error.isEmpty())HomeDevicePolicy.put(out,"error",error);
            HomeDevicePolicy.put(out,"catalog_revision",revision);
            HomeDevicePolicy.put(out,"total_devices",manifest==null?0:manifest.devices.size());
            JSONArray devices=new JSONArray();HomeDevicePolicy.put(out,"devices",devices);
            String match=HomeDevicePolicy.normalize(query);List<Device> matched=new ArrayList<>();
            if(manifest!=null) for(Device device:manifest.devices) {
                boolean matches=match.isEmpty()||HomeDevicePolicy.normalize(device.name+device.room+device.model).contains(match);
                if(!matches)for(HomeDevicePolicy.Entity e:device.entities) if(HomeDevicePolicy.normalize(e.name).contains(match)){matches=true;break;}
                if(matches)matched.add(device);
            }
            HomeDevicePolicy.put(out,"matched_devices",matched.size());HomeDevicePolicy.put(out,"truncated",false);
            boolean truncated=matched.size()>64;int perDevice=Math.min(64,Math.max(1,256/Math.max(1,Math.min(64,matched.size()))));
            List<List<Entry>> selected=new ArrayList<>();List<JSONArray> rows=new ArrayList<>();
            for(Device device:matched) {
                if(devices.length()>=64)break;
                JSONObject projected=HomeDevicePolicy.object();HomeDevicePolicy.put(projected,"id",device.id);
                HomeDevicePolicy.put(projected,"name",device.name);HomeDevicePolicy.put(projected,"room",device.room);HomeDevicePolicy.put(projected,"model",device.model);
                boolean available=false;JSONArray entities=new JSONArray();HomeDevicePolicy.put(projected,"entities",entities);rows.add(entities);
                List<Entry> candidates=new ArrayList<>();
                for(HomeDevicePolicy.Entity entity:device.entities) {
                    Entry entry=entries.get(entity.ref);if(entry==null)continue;
                    if(HomeDevicePolicy.available(entry.state))available=true;
                    // A more specific query can reach entities omitted by a broad catalog request.
                    if(!match.isEmpty()&&!HomeDevicePolicy.normalize(device.name+device.room+device.model).contains(match)
                            &&!HomeDevicePolicy.normalize(entity.name).contains(match))continue;
                    candidates.add(entry);
                }
                Collections.sort(candidates,(left,right)->Integer.compare(priority(left),priority(right)));
                if(candidates.size()>perDevice)truncated=true;
                selected.add(candidates);
                HomeDevicePolicy.put(projected,"available",available);devices.put(projected);
            }
            // Fair round-robin allocation retains every physical-device row before adding entities.
            int bytes=out.toString().getBytes(StandardCharsets.UTF_8).length;
            for(int round=0;round<perDevice;round++)for(int index=0;index<selected.size();index++) {
                List<Entry> candidates=selected.get(index);if(round>=candidates.size())continue;
                JSONObject value=candidates.get(round).json();int cost=value.toString().getBytes(StandardCharsets.UTF_8).length+1;
                if(bytes+cost>119*1024){truncated=true;continue;}
                rows.get(index).put(value);bytes+=cost;
            }
            HomeDevicePolicy.put(out,"truncated",truncated);return out;
        }
        private static int priority(Entry entry){return !entry.capabilities.isEmpty()?0:entry.entity.category.equals("normal")&&HomeDevicePolicy.available(entry.state)?1:2;}
        /** Full-catalog, display-only totals; never consults a name filter or the truncated projection. */
        public JSONObject lightSummary(){
            int total=0,on=0,off=0,unknown=0;
            for(Entry entry:entries.values()){
                HomeDevicePolicy.Entity entity=entry.entity;
                if(!entity.domain.equals("light")||!entity.category.equals("normal")||entity.hidden||entity.disabled)continue;
                total++;String state=entry.state==null?"":entry.state.optString("state","");
                if(state.equals("on"))on++;else if(state.equals("off"))off++;else unknown++;
            }
            JSONObject out=HomeDevicePolicy.object();HomeDevicePolicy.put(out,"ok",error.isEmpty());
            if(!error.isEmpty())HomeDevicePolicy.put(out,"error",error);
            HomeDevicePolicy.put(out,"total",total);HomeDevicePolicy.put(out,"on",on);HomeDevicePolicy.put(out,"off",off);HomeDevicePolicy.put(out,"unknown",unknown);return out;
        }
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener connectionListener=(p,k)->connectionChanged();
    private Manifest manifest;
    private Snapshot current;
    private Runnable listener;
    private boolean active,destroyed,busy;
    private volatile long generation;
    private Future<?> future;
    private HomeControls.DeviceRequest request;
    public HomeDevices(Activity activity) {
        context=activity.getApplicationContext();preferences=context.getSharedPreferences(HomeControls.PREFERENCES_NAME,Context.MODE_PRIVATE);
        try{manifest=parseManifest(readFile(new File(context.getFilesDir(),"home-device-catalog.json")));}catch(Exception ignored) { }
        current=emptySnapshot(manifest,manifest==null?"catalog_missing":"not_ready");
        importCatalog();
    }
    public synchronized void setListener(Runnable listener){this.listener=listener;}
    public synchronized Snapshot snapshot(){return current;}
    public synchronized void start(){if(destroyed||active)return;active=true;preferences.registerOnSharedPreferenceChangeListener(connectionListener);refresh(null);}
    public synchronized void stop(){active=false;preferences.unregisterOnSharedPreferenceChangeListener(connectionListener);cancel();}
    public synchronized void destroy(){stop();destroyed=true;listener=null;worker.shutdownNow();}
    public synchronized void cancel(){generation++;if(request!=null)request.cancel();if(future!=null)future.cancel(true);request=null;future=null;busy=false;}
    private synchronized void connectionChanged(){if(destroyed)return;cancel();current=emptySnapshot(manifest,"connection_changed");publish();if(active)refresh(null);}
    private void publish(){main.post(()->{Runnable callback;synchronized(HomeDevices.this){callback=destroyed?null:listener;}if(callback!=null)callback.run();});}
    private void callback(Callback callback,JSONObject result){if(callback!=null)main.post(()->callback.done(result));}
    private static JSONObject error(String code){JSONObject out=HomeDevicePolicy.object();HomeDevicePolicy.put(out,"ok",false);HomeDevicePolicy.put(out,"error",code);return out;}
    private static Snapshot emptySnapshot(Manifest manifest,String error){
        Map<String,Entry> all=new LinkedHashMap<>();Set<String> services=Collections.emptySet();
        if(manifest!=null)for(Device d:manifest.devices)for(HomeDevicePolicy.Entity e:d.entities)all.put(e.ref,new Entry(e,null,services));
        return new Snapshot(manifest,all,services,null,error);
    }
    /** Import a bounded registry projection. Unknown fields are discarded before private storage. */
    public synchronized boolean importCatalog(){
        if(destroyed)return false;
        File external=context.getExternalFilesDir(null);if(external==null)return false;
        File incoming=new File(external,"home-device-catalog.json");if(!incoming.isFile())return false;
        try {
            if(!incoming.getCanonicalFile().getParentFile().equals(external.getCanonicalFile()))throw new Failure("catalog_invalid");
            Manifest imported=parseManifest(readFile(incoming),HomeControls.deviceCatalogBinding(context));
            File stored=new File(context.getFilesDir(),"home-device-catalog.json"), temp=new File(context.getFilesDir(),"home-device-catalog.json.new");
            try(FileOutputStream output=new FileOutputStream(temp)){output.write(imported.stored.toString().getBytes(StandardCharsets.UTF_8));output.flush();output.getFD().sync();}
            if(!temp.renameTo(stored)){temp.delete();throw new Failure("catalog_invalid");}
            if(manifest==null||!manifest.digest.equals(imported.digest)){cancel();manifest=imported;current=emptySnapshot(manifest,"not_ready");publish();}
            incoming.delete();return true;
        }catch(Exception ignored){cancel();current=emptySnapshot(manifest,"catalog_invalid");publish();return false;}
    }
    private static String readFile(File file)throws Exception{
        if(!file.isFile()||file.length()>1024*1024)throw new Failure("catalog_invalid");
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1){if(output.size()+count>1024*1024)throw new Failure("catalog_invalid");output.write(bytes,0,count);}return output.toString("UTF-8");
        }
    }
    private static Manifest parseManifest(String text)throws Exception{
        return parseManifest(text,null);
    }
    private static Manifest parseManifest(String text,String bindingOverride)throws Exception{
        JSONObject root=new JSONObject(text);if(root.optInt("schema",0)!=1)throw new Failure("catalog_invalid");
        JSONArray devices=root.optJSONArray("devices");if(devices==null||devices.length()>512)throw new Failure("catalog_invalid");
        List<Device> result=new ArrayList<>();Set<String> ids=new HashSet<>(),refs=new HashSet<>();int total=0;
        String temperatureUnit=root.optString("temperature_unit","");
        if(!temperatureUnit.isEmpty()&&!temperatureUnit.equals("°C")&&!temperatureUnit.equals("°F"))throw new Failure("catalog_invalid");
        String binding=bindingOverride==null?root.optString("connection_binding",""):bindingOverride;
        if(!binding.matches("[a-f0-9]{64}"))throw new Failure("catalog_invalid");
        JSONObject stored=HomeDevicePolicy.object();HomeDevicePolicy.put(stored,"schema",1);HomeDevicePolicy.put(stored,"temperature_unit",temperatureUnit);HomeDevicePolicy.put(stored,"connection_binding",binding);JSONArray projected=new JSONArray();HomeDevicePolicy.put(stored,"devices",projected);
        for(int i=0;i<devices.length();i++){
            JSONObject d=devices.optJSONObject(i);if(d==null)throw new Failure("catalog_invalid");
            String id=d.optString("id","");if(!id.matches("d_[a-f0-9]{24}")||!ids.add(id))throw new Failure("catalog_invalid");
            String name=HomeDevicePolicy.safe(d.optString("name",""),96),room=HomeDevicePolicy.safe(d.optString("room",""),64),model=HomeDevicePolicy.safeModel(d.optString("model",""));
            if(name.isEmpty())throw new Failure("catalog_invalid");
            JSONArray entities=d.optJSONArray("entities");if(entities==null||entities.length()>512)throw new Failure("catalog_invalid");
            JSONObject clean=HomeDevicePolicy.object();HomeDevicePolicy.put(clean,"id",id);HomeDevicePolicy.put(clean,"name",name);HomeDevicePolicy.put(clean,"room",room);HomeDevicePolicy.put(clean,"model",model);
            JSONArray cleanEntities=new JSONArray();HomeDevicePolicy.put(clean,"entities",cleanEntities);projected.put(clean);
            List<HomeDevicePolicy.Entity> list=new ArrayList<>();
            for(int j=0;j<entities.length();j++){
                if(++total>4096)throw new Failure("catalog_invalid");JSONObject e=entities.optJSONObject(j);if(e==null)throw new Failure("catalog_invalid");
                String entity=e.optString("entity_id",""),ref=e.optString("ref",""),title=HomeDevicePolicy.safe(e.optString("name",""),96),category=e.optString("category","");
                if(!HomeDevicePolicy.validEntity(entity)||!HomeDevicePolicy.ref(entity).equals(ref)||!refs.add(ref)||title.isEmpty()
                        ||!(category.equals("normal")||category.equals("config")||category.equals("diagnostic"))
                        ||!(e.opt("disabled") instanceof Boolean)||!(e.opt("hidden") instanceof Boolean))throw new Failure("catalog_invalid");
                boolean disabled=e.optBoolean("disabled",true),hidden=e.optBoolean("hidden",true);
                list.add(new HomeDevicePolicy.Entity(entity,title,id,name,room,model,category,disabled,hidden));
                JSONObject ce=HomeDevicePolicy.object();HomeDevicePolicy.put(ce,"entity_id",entity);HomeDevicePolicy.put(ce,"ref",ref);HomeDevicePolicy.put(ce,"name",title);HomeDevicePolicy.put(ce,"category",category);HomeDevicePolicy.put(ce,"disabled",disabled);HomeDevicePolicy.put(ce,"hidden",hidden);cleanEntities.put(ce);
            }
            result.add(new Device(id,name,room,model,list));
        }
        return new Manifest(result,stored);
    }
    public synchronized void refresh(Callback callback){
        if(destroyed||!active){callback(callback,error("not_ready"));return;}
        if(busy){callback(callback,error("busy"));return;}
        File incomingDirectory=context.getExternalFilesDir(null);
        if(incomingDirectory!=null&&new File(incomingDirectory,"home-device-catalog.json").isFile()&&!importCatalog()){
            callback(callback,current.toJson(""));return;
        }
        if(manifest==null){callback(callback,current.toJson(""));return;}
        final HomeControls.DeviceConnection connection;
        try{connection=HomeControls.captureDeviceConnection(context);if(!manifest.connectionBinding.equals(HomeControls.deviceCatalogBinding(context)))throw new Failure("catalog_connection_changed");}catch(Failure failure){current=emptySnapshot(manifest,failure.code);publish();callback(callback,current.toJson(""));return;}
        final Manifest selected=manifest;final long ticket=generation;final HomeControls.DeviceRequest pending=new HomeControls.DeviceRequest();request=pending;busy=true;
        future=worker.submit(()->{
            Snapshot result;
            try{
                if(!selected.temperatureUnit.equals(HomeControls.readDeviceTemperatureUnit(context,connection,pending)))throw new Failure("catalog_unit_changed");
                Set<String> services=HomeDevicePolicy.services(HomeControls.readDeviceServices(context,connection,pending));
                JSONArray states=HomeControls.readDeviceStates(context,connection,pending);if(states.length()>10000)throw new Failure("response_too_large");
                Map<String,JSONObject> lookup=new HashMap<>();for(int i=0;i<states.length();i++){JSONObject state=states.optJSONObject(i);if(state!=null)lookup.put(state.optString("entity_id",""),state);}
                Map<String,Entry> entries=new LinkedHashMap<>();for(Device d:selected.devices)for(HomeDevicePolicy.Entity e:d.entities)entries.put(e.ref,new Entry(e,withUnit(e,lookup.get(e.entityId),selected.temperatureUnit),services));
                result=new Snapshot(selected,entries,services,connection,"");
            }catch(Failure failure){result=emptySnapshot(selected,failure.code);}catch(Exception ignored){result=emptySnapshot(selected,"request_failed");}
            final Snapshot ready=result;main.post(()->{synchronized(HomeDevices.this){if(destroyed||ticket!=generation||request!=pending){callback(callback,error("authorization_expired"));return;}busy=false;request=null;future=null;current=ready;publish();callback(callback,current.toJson(""));}});
        });
    }
    public synchronized PreparedAction prepare(String ref,String revision,String action,Object value,String utterance,boolean fromVoice)throws Failure{
        if(destroyed||!active||busy||!current.error.isEmpty()||current.connection==null)throw new Failure("not_ready");
        if(revision==null||!revision.matches("[a-f0-9]{64}")||!revision.equals(current.revision))throw new Failure("catalog_stale");
        if(!HomeControls.deviceConnectionUnchanged(context,current.connection))throw new Failure("connection_changed");
        Entry entry=current.entries.get(ref);if(entry==null||!HomeDevicePolicy.available(entry.state))throw new Failure("target_unavailable");
        HomeDevicePolicy.Capability capability=HomeDevicePolicy.capability(entry.capabilities,action);if(capability==null)throw new Failure("unsupported_action");
        Object validated=capability.validate(value);
        if(fromVoice){
            HomeDevicePolicy.verifyIntent(entry.entity,capability,validated,utterance);
            int specificity=0;List<Entry> named=new ArrayList<>();
            for(Entry candidate:current.entries.values())try{
                // Resolve names before filtering online status, permission or parameter ranges.
                HomeDevicePolicy.verifyIntent(candidate.entity,capability,validated,utterance);
                int length=HomeDevicePolicy.targetAlias(candidate.entity,utterance).length();
                if(length>specificity){specificity=length;named.clear();}if(length==specificity)named.add(candidate);
            }catch(Failure ignored){ }
            Set<String> devices=new HashSet<>();for(Entry candidate:named)devices.add(candidate.entity.deviceId);
            if(devices.size()!=1||!devices.contains(entry.entity.deviceId)||HomeDevicePolicy.targetAlias(entry.entity,utterance).length()!=specificity)
                throw new Failure("ambiguous_target");
            int matches=0;
            for(Entry candidate:named)if(HomeDevicePolicy.capability(candidate.capabilities,action)!=null
                    ||(!HomeDevicePolicy.available(candidate.state)&&candidate.entity.controllable()&&HomeDevicePolicy.potentialAction(candidate.entity.domain,action)))matches++;
            if(matches!=1)throw new Failure("ambiguous_target");
        }
        return new PreparedAction(this,entry,capability,validated,utterance==null?"":utterance);
    }
    private static JSONObject withUnit(HomeDevicePolicy.Entity entity,JSONObject state,String unit){
        if(state==null||!entity.domain.equals("climate"))return state;
        JSONObject copy=HomeDevicePolicy.copy(state),attrs=copy.optJSONObject("attributes");if(attrs==null){attrs=HomeDevicePolicy.object();HomeDevicePolicy.put(copy,"attributes",attrs);}
        // The HA global unit system is imported with the registry projection, never guessed.
        HomeDevicePolicy.put(attrs,"temperature_unit",unit);return copy;
    }
    private synchronized void validateTicket(PreparedAction ticket,AtomicBoolean authorization)throws Failure{
        if(ticket==null||ticket.owner!=this||destroyed||!active||ticket.generation!=generation
                ||authorization==null||!authorization.get()||System.nanoTime()-ticket.createdNanos>30_000_000_000L)throw new Failure("authorization_expired");
        if(!ticket.catalogRevision.equals(current.revision))throw new Failure("catalog_stale");
        if(!HomeControls.deviceConnectionUnchanged(context,ticket.connection))throw new Failure("connection_changed");
    }
    public synchronized void execute(PreparedAction ticket,AtomicBoolean authorization,Callback callback){
        execute(ticket,authorization,Long.MAX_VALUE,callback);
    }
    public synchronized void execute(PreparedAction ticket,AtomicBoolean authorization,long expiresAtNanos,Callback callback){
        try{validateTicket(ticket,authorization);if(busy)throw new Failure("busy");if(!ticket.consumed.compareAndSet(false,true))throw new Failure("authorization_expired");}
        catch(Failure failure){callback(callback,error(failure.code));return;}
        HomeControls.DeviceRequest pending=new HomeControls.DeviceRequest();pending.expiresAtNanos=Math.min(expiresAtNanos,ticket.createdNanos+30_000_000_000L);request=pending;busy=true;long selectedGeneration=generation;
        future=worker.submit(()->{
            JSONObject result,observation=null;
            try{
                validateTicket(ticket,authorization);
                Set<String> services=HomeDevicePolicy.services(HomeControls.readDeviceServices(context,ticket.connection,pending));
                JSONObject fresh=withUnit(ticket.entry.entity,HomeControls.readDeviceEntity(context,ticket.connection,pending,ticket.entry.entity.entityId),manifest.temperatureUnit);
                if(!HomeDevicePolicy.available(fresh))throw new Failure("target_unavailable");
                HomeDevicePolicy.Capability now=HomeDevicePolicy.capability(HomeDevicePolicy.capabilities(ticket.entry.entity,fresh,services),ticket.action);
                if(now==null||!now.signature().equals(ticket.capability.signature()))throw new Failure("catalog_stale");
                now.validate(ticket.value);validateTicket(ticket,authorization);
                if(ticket.entry.entity.domain.equals("climate")&&!manifest.temperatureUnit.equals(HomeControls.readDeviceTemperatureUnit(context,ticket.connection,pending)))throw new Failure("catalog_unit_changed");
                validateTicket(ticket,authorization);
                HomeControls.submitDeviceAction(context,ticket.connection,pending,ticket.entry.entity,now,ticket.value,authorization);
                // One bounded observation. No service retry and no optimistic physical-state claim.
                try{observation=withUnit(ticket.entry.entity,HomeControls.readDeviceEntity(context,ticket.connection,pending,ticket.entry.entity.entityId),manifest.temperatureUnit);}catch(Failure ignored){ }
                result=actionResult(ticket,observation==null?"result_unverified":"command_sent",observation);
            }catch(Failure failure){result=pending.submissionStarted?actionResult(ticket,"result_unverified",null):error(failure.code);}
            catch(Exception ignored){result=pending.submissionStarted?actionResult(ticket,"result_unverified",null):error("request_failed");}
            JSONObject done=result,observed=observation;main.post(()->{synchronized(HomeDevices.this){
                if(request==pending){request=null;future=null;busy=false;}
                if(!destroyed&&selectedGeneration==generation&&HomeControls.deviceConnectionUnchanged(context,ticket.connection)){
                    if(pending.submissionStarted){
                        Map<String,Entry> updated=new LinkedHashMap<>(current.entries);
                        updated.put(ticket.targetRef,new Entry(ticket.entry.entity,observed,current.services));
                        current=new Snapshot(manifest,updated,current.services,ticket.connection,current.error);
                    }
                    publish();
                }
                callback(callback,done);
            }});
        });
    }
    private static JSONObject actionResult(PreparedAction ticket,String operation,JSONObject state){
        JSONObject out=HomeDevicePolicy.object();HomeDevicePolicy.put(out,"ok",true);HomeDevicePolicy.put(out,"operation",operation);
        HomeDevicePolicy.put(out,"target_ref",ticket.targetRef);HomeDevicePolicy.put(out,"name",ticket.name);
        String observed=state==null?"unknown":HomeDevicePolicy.publicState(ticket.entry.entity,state);HomeDevicePolicy.put(out,"state",observed);return out;
    }
}
