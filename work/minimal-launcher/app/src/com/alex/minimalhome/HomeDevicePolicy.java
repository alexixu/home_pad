package com.alex.minimalhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, fail-closed policy. No Android, network, credentials or device side effects. */
public final class HomeDevicePolicy {
    private HomeDevicePolicy() { }
    private static final String DOMAINS = "binary_sensor button climate cover device_tracker event fan humidifier light media_player notify number select sensor switch text vacuum water_heater weather";
    private static final Pattern ADMIN = Pattern.compile("(?i)(reset|reboot|restart|firmware|update|upgrade|pair|unpair|factory|delete|remove|format|calibrat|diagnos|debug|credential|password|token|重置|复位|恢复出厂|重启|重新启动|固件|升级|更新|配对|解绑|删除|格式化|校准|诊断|调试|密码|密钥|清零|清码|对码|保护禁用|参数|自清洁|\\*)");
    private static final Pattern PRIVATE_TEXT = Pattern.compile("(?i)(https?://|bearer\\s|(?:sk-|eyJ)[A-Za-z0-9_-]{12,}|[a-f0-9]{32,}|[A-Za-z0-9_-]{48,}|(?:\\d{1,3}\\.){3}\\d{1,3}|[a-z_]+\\.[a-z0-9_]{3,})");
    static String safe(String value, int limit) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (PRIVATE_TEXT.matcher(clean).find()) return "";
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
    static String safeModel(String value) {
        if(value!=null&&value.length()<=96&&value.matches("[A-Za-z][A-Za-z0-9_-]{0,23}(?:\\.[A-Za-z0-9_-]{1,32}){1,3}")
                &&!domainAllowed(value.substring(0,value.indexOf('.')))&&!value.matches("(?i).*(?:token|password|secret|[a-f0-9]{24,}).*"))return value;
        return safe(value,96);
    }
    static String publicState(Entity entity,JSONObject state) {
        if(state==null)return "unavailable";
        if(!entity.category.equals("normal")||entity.hidden||entity.disabled||entity.domain.equals("text")||entity.domain.equals("notify")
                ||(entity.name+" "+entity.entityId).matches("(?is).*(password|token|secret|credential|serial|ssid|bssid|mac_address|ip_address|密码|密钥|序列号|网络地址).*"))return "unknown";
        String value=safe(state.optString("state","unknown"),96);
        if(value.matches("(?i).*(?:[a-f0-9]{2}:){5}[a-f0-9]{2}.*")||value.matches(".*[^\\s]+@[^\\s]+\\.[^\\s]+.*"))return "unknown";
        return value.isEmpty()?"unknown":value;
    }
    static boolean domainAllowed(String domain) { return (" " + DOMAINS + " ").contains(" " + domain + " "); }
    static boolean validEntity(String entity) {
        return entity != null && entity.length() <= 255 && entity.matches("[a-z_]+\\.[a-z0-9_]+")
                && domainAllowed(entity.substring(0, entity.indexOf('.')));
    }
    static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static String ref(String entity) { return "e_" + hash(entity).substring(0, 24); }
    static JSONObject object() { return new JSONObject(); }
    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid policy value"); }
    }
    static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); } catch (Exception invalid) { return object(); }
    }
    static boolean available(JSONObject state) {
        if (state == null) return false;
        String value = state.optString("state", "");
        return !value.isEmpty() && !value.equals("unknown") && !value.equals("unavailable");
    }
    static final class Entity {
        final String entityId, ref, name, domain, deviceId, deviceName, room, model, category;
        final boolean disabled, hidden;
        Entity(String entityId, String name, String deviceId, String deviceName, String room,
                String model, String category, boolean disabled, boolean hidden) {
            this.entityId = entityId; this.ref = ref(entityId); this.name = name;
            this.domain = entityId.substring(0, entityId.indexOf('.'));
            this.deviceId = deviceId; this.deviceName = deviceName; this.room = room;
            this.model = model; this.category = category; this.disabled = disabled; this.hidden = hidden;
        }
        boolean controllable() {
            return !disabled && !hidden && category.equals("normal")
                    && !ADMIN.matcher(name + " " + deviceName + " " + entityId).find();
        }
    }
    static final class Capability {
        final String action, label, type, service, parameter, unit;
        final double min, max, step, scale;
        final List<String> options;
        Capability(String action, String label, String type, String service, String parameter,
                double min, double max, double step, double scale, String unit, List<String> options) {
            this.action = action; this.label = label; this.type = type; this.service = service;
            this.parameter = parameter; this.min = min; this.max = max; this.step = step;
            this.scale = scale; this.unit = unit; this.options = Collections.unmodifiableList(options);
        }
        JSONObject json() {
            JSONObject out = object(); put(out,"action",action); put(out,"label",label); put(out,"value_type",type);
            if (type.equals("number")) {
                put(out,"min",min); put(out,"max",max); if (step > 0) put(out,"step",step);
                if (!unit.isEmpty()) put(out,"unit",unit);
            }
            if (type.equals("string")) {
                JSONArray values = new JSONArray();
                for (String value : options) {
                    JSONObject option=object(); put(option,"value",value); put(option,"label",optionLabel(value)); values.put(option);
                }
                put(out,"options",values);
            }
            return out;
        }
        String signature() { return json().toString()+"|"+service+"|"+parameter+"|"+scale; }
        Object validate(Object value) throws HomeDevices.Failure {
            if (type.equals("none")) {
                if (value != null && value != JSONObject.NULL) throw new HomeDevices.Failure("invalid_value");
                return null;
            }
            if (type.equals("boolean")) {
                if (!(value instanceof Boolean)) throw new HomeDevices.Failure("invalid_value");
                return value;
            }
            if (type.equals("string")) {
                if (!(value instanceof String) || !options.contains(value)) throw new HomeDevices.Failure("invalid_value");
                return value;
            }
            if (!(value instanceof Number)) throw new HomeDevices.Failure("invalid_value");
            double number=((Number)value).doubleValue();
            if (!Double.isFinite(number) || Math.abs(number)>1e12 || number<min-1e-8 || number>max+1e-8)
                throw new HomeDevices.Failure("invalid_value");
            if (step>0 && Math.abs((number-min)/step-Math.rint((number-min)/step))>1e-6)
                throw new HomeDevices.Failure("invalid_value");
            return number;
        }
        JSONObject payload(String entityId, Object value) {
            JSONObject out=object(); put(out,"entity_id",entityId);
            if (!parameter.isEmpty()) put(out,parameter,type.equals("number") ? ((Number)value).doubleValue()*scale : value);
            return out;
        }
    }
    static Set<String> services(JSONArray response) {
        Set<String> out=new HashSet<>();
        for(int i=0;i<response.length();i++) {
            JSONObject domain=response.optJSONObject(i); if(domain==null) continue;
            String name=domain.optString("domain",""); JSONObject list=domain.optJSONObject("services");
            if(!domainAllowed(name)||list==null) continue;
            java.util.Iterator<String> keys=list.keys();
            while(keys.hasNext()) {
                String key=keys.next();if(!key.matches("[a-z_]{1,48}"))continue;
                JSONObject definition=list.optJSONObject(key);if(definition==null)continue;
                String service=name+"."+key;out.add(service);
                JSONObject fields=definition.optJSONObject("fields");if(fields==null)continue;
                java.util.Iterator<String> names=fields.keys();
                while(names.hasNext()){
                    String field=names.next();if(!field.matches("[a-z_]{1,48}"))continue;
                    out.add(service+":"+field);JSONObject metadata=fields.optJSONObject(field);
                    if(metadata!=null&&metadata.optBoolean("required",false)&&!field.equals("entity_id"))out.add(service+":required:"+field);
                }
            }
        }
        return out;
    }
    private static double number(JSONObject attrs,String key) {
        Object value=attrs.opt(key); if(!(value instanceof Number)) return Double.NaN;
        double n=((Number)value).doubleValue(); return Double.isFinite(n) && Math.abs(n)<=1e12 ? n : Double.NaN;
    }
    private static List<String> options(JSONObject attrs,String key) {
        List<String> out=new ArrayList<>(); JSONArray values=attrs.optJSONArray(key);
        if(values==null || values.length()>64) return out;
        for(int i=0;i<values.length();i++) {
            Object value=values.opt(i); if(!(value instanceof String)) continue;
            String text=(String)value; if(!text.isEmpty() && text.length()<=64 && safe(text,64).equals(text)
                    && !ADMIN.matcher(text).find() && !out.contains(text)) out.add(text);
        }
        return out;
    }
    private static final class Builder {
        final String domain; final Set<String> services; final List<Capability> all=new ArrayList<>();
        Builder(String domain,Set<String> services) { this.domain=domain; this.services=services; }
        void add(String action,String label,String type,String service,String parameter,double min,double max,double step,double scale,String unit,List<String> opts) {
            if(!services.contains(domain+"."+service)) return;
            if(!parameter.isEmpty()&&!services.contains(domain+"."+service+":"+parameter))return;
            for(String field:services)if(field.startsWith(domain+"."+service+":required:")&&!field.equals(domain+"."+service+":required:"+parameter))return;
            if(type.equals("number") && (!Double.isFinite(min)||!Double.isFinite(max)||max<min||!Double.isFinite(step)||step<0)) return;
            if(type.equals("string") && opts.isEmpty()) return;
            all.add(new Capability(action,label,type,service,parameter,min,max,step,scale,unit,opts));
        }
        void none(String a,String l,String s) { add(a,l,"none",s,"",0,0,0,1,"",Collections.<String>emptyList()); }
        void num(String a,String l,String s,String p,double min,double max,double step,double scale,String unit) { add(a,l,"number",s,p,min,max,step,scale,unit,Collections.<String>emptyList()); }
        void bool(String a,String l,String s,String p) { add(a,l,"boolean",s,p,0,0,0,1,"",Collections.<String>emptyList()); }
        void enumeration(String a,String l,String s,String p,JSONObject attrs,String list) { add(a,l,"string",s,p,0,0,0,1,"",options(attrs,list)); }
    }
    static List<Capability> capabilities(Entity entity,JSONObject state,Set<String> services) {
        Builder b=new Builder(entity.domain,services);
        if(!entity.controllable() || !available(state)) return b.all;
        JSONObject a=state.optJSONObject("attributes"); if(a==null) a=object();
        // Flag values verified against Home Assistant core tag 2026.10.4 domain const.py.
        Object features=a.opt("supported_features");long f=0;
        if(features instanceof Number){double flags=((Number)features).doubleValue();if(Double.isFinite(flags)&&flags>=0&&flags<=Integer.MAX_VALUE&&flags==Math.rint(flags))f=(long)flags;}
        String d=entity.domain;
        if(d.equals("switch")||d.equals("light")||d.equals("humidifier")) {
            b.none("power_on","开启","turn_on"); b.none("power_off","关闭","turn_off");
        }
        if(d.equals("light")) {
            List<String> modes=options(a,"supported_color_modes");
            if(modes.contains("brightness")||modes.contains("color_temp")||modes.contains("hs")||modes.contains("xy")||modes.contains("rgb")||modes.contains("rgbw")||modes.contains("rgbww")||modes.contains("white"))
                b.num("set_brightness","亮度（%）","turn_on","brightness_pct",0,100,1,1,"%");
            if(modes.contains("color_temp")) b.num("set_color_temperature","色温（K）","turn_on","color_temp_kelvin",
                    number(a,"min_color_temp_kelvin"),number(a,"max_color_temp_kelvin"),1,1,"K");
        } else if(d.equals("fan")) {
            if((f&32)!=0) b.none("power_on","开启","turn_on");
            if((f&16)!=0) b.none("power_off","关闭","turn_off");
            // HA set_percentage accepts integral percentages; percentage_step is a suggested UI increment.
            if((f&1)!=0) b.num("set_percentage","风速（%）","set_percentage","percentage",0,100,1,1,"%");
            if((f&2)!=0) b.bool("set_oscillating","摇头","oscillate","oscillating");
            if((f&8)!=0) b.enumeration("set_preset_mode","预设模式","set_preset_mode","preset_mode",a,"preset_modes");
        } else if(d.equals("climate")) {
            if((f&256)!=0) b.none("power_on","开启","turn_on");
            if((f&128)!=0) b.none("power_off","关闭","turn_off");
            if((f&1)!=0) { double step=number(a,"target_temp_step"); b.num("set_temperature","目标温度","set_temperature","temperature",number(a,"min_temp"),number(a,"max_temp"),Double.isFinite(step)?step:0,1,safe(a.optString("temperature_unit",""),24)); }
            b.enumeration("set_hvac_mode","运行模式","set_hvac_mode","hvac_mode",a,"hvac_modes");
            if((f&8)!=0) b.enumeration("set_fan_mode","风速模式","set_fan_mode","fan_mode",a,"fan_modes");
            if((f&16)!=0) b.enumeration("set_preset_mode","预设模式","set_preset_mode","preset_mode",a,"preset_modes");
            if((f&4)!=0) b.num("set_humidity","目标湿度（%）","set_humidity","humidity",number(a,"min_humidity"),number(a,"max_humidity"),1,1,"%");
        } else if(d.equals("cover")) {
            if((f&1)!=0) b.none("open","打开","open_cover");
            if((f&2)!=0) b.none("close","关闭","close_cover");
            if((f&8)!=0) b.none("stop","停止","stop_cover");
            if((f&4)!=0) b.num("set_position","打开位置（%）","set_cover_position","position",0,100,1,1,"%");
        } else if(d.equals("humidifier")) {
            b.num("set_humidity","目标湿度（%）","set_humidity","humidity",number(a,"min_humidity"),number(a,"max_humidity"),1,1,"%");
            if((f&1)!=0) b.enumeration("set_preset_mode","运行模式","set_mode","mode",a,"available_modes");
        } else if(d.equals("vacuum")) {
            if((f&8192)!=0) b.none("start","开始清扫","start");
            if((f&4)!=0) b.none("pause","暂停","pause");
            if((f&8)!=0) b.none("stop","停止","stop");
            if((f&16)!=0) b.none("return_to_base","返回充电座","return_to_base");
            if((f&32)!=0) b.enumeration("set_fan_mode","吸力模式","set_fan_speed","fan_speed",a,"fan_speed_list");
        } else if(d.equals("media_player")) {
            if((f&128)!=0) b.none("power_on","开启","turn_on");
            if((f&256)!=0) b.none("power_off","关闭","turn_off");
            if((f&16384)!=0) b.none("play","播放","media_play");
            if((f&1)!=0) b.none("pause","暂停","media_pause");
            if((f&4096)!=0) b.none("stop","停止","media_stop");
            if((f&4)!=0) b.num("set_volume","音量（%）","volume_set","volume_level",0,100,1,0.01,"%");
            if((f&8)!=0) b.bool("set_muted","静音","volume_mute","is_volume_muted");
            if((f&2048)!=0) b.enumeration("select_source","输入来源","select_source","source",a,"source_list");
        } else if(d.equals("number")) {
            b.num("set_value","设定数值","set_value","value",number(a,"min"),number(a,"max"),number(a,"step"),1,safe(a.optString("unit_of_measurement",""),24));
        } else if(d.equals("select")) {
            b.enumeration("select_option","选择选项","select_option","option",a,"options");
        } else if(d.equals("button") && (entity.name+" "+entity.entityId).matches("(?is).*(开始|运行|执行|启动|start|run|execute).*")) {
            b.none("press","执行","press");
        }
        return b.all;
    }
    static Capability capability(List<Capability> all,String action) {
        for(Capability c:all) if(c.action.equals(action)) return c; return null;
    }
    static boolean potentialAction(String domain,String action) {
        String allowed="";
        if(action.equals("power_on")||action.equals("power_off"))allowed="switch light fan climate humidifier media_player";
        else if(action.equals("set_brightness")||action.equals("set_color_temperature"))allowed="light";
        else if(action.equals("set_temperature")||action.equals("set_hvac_mode"))allowed="climate";
        else if(action.equals("set_fan_mode"))allowed="climate vacuum";
        else if(action.equals("set_percentage")||action.equals("set_oscillating"))allowed="fan";
        else if(action.equals("set_preset_mode"))allowed="fan climate humidifier";
        else if(action.equals("open")||action.equals("close")||action.equals("set_position"))allowed="cover";
        else if(action.equals("stop"))allowed="cover vacuum media_player";
        else if(action.equals("set_humidity"))allowed="humidifier climate";
        else if(action.equals("play")||action.equals("set_volume")||action.equals("set_muted")||action.equals("select_source"))allowed="media_player";
        else if(action.equals("pause"))allowed="media_player vacuum";
        else if(action.equals("start")||action.equals("return_to_base"))allowed="vacuum";
        else if(action.equals("set_value"))allowed="number";
        else if(action.equals("select_option"))allowed="select";
        else if(action.equals("press"))allowed="button";
        return (" "+allowed+" ").contains(" "+domain+" ");
    }
    static String normalize(String text) {
        return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、,!?：:；;‘’“”\"'（）()]","")
                .replaceAll("(?<![0-9])\\.|\\.(?![0-9])","");
    }
    static String optionLabel(String value) {
        String[] raw={"off","on","auto","cool","heat","dry","fan_only","heat_cool","low","medium","high","silent","sleep","eco","normal","boost","comfort","away","home","none"};
        String[] zh={"关闭","开启","自动","制冷","制热","除湿","送风","冷暖自动","低速","中速","高速","静音","睡眠","节能","标准","强力","舒适","离家","在家","无"};
        for(int i=0;i<raw.length;i++) if(value.equals(raw[i])) return zh[i]; return value;
    }
    /** Match the full target, not an LLM-selected pronoun or partial device name. */
    static String targetAlias(Entity e,String utterance) {
        String text=normalize(utterance); String best="";
        String[] candidates={e.name,e.deviceName,e.room+e.name,e.room+e.deviceName};
        for(String candidate:candidates) { String n=normalize(candidate); if(n.length()>=2 && text.contains(n) && n.length()>best.length()) best=n; }
        return best;
    }
    static void verifyIntent(Entity e,Capability capability,Object value,String utterance) throws HomeDevices.Failure {
        if(utterance==null || utterance.isEmpty() || utterance.length()>160) throw new HomeDevices.Failure("intent_not_verified");
        if(utterance.matches("(?s).*[?？\"'“”‘’].*")) throw new HomeDevices.Failure("intent_not_verified");
        String text=normalize(utterance);
        if(text.matches("(?s).*(不要|别|不能|不许|不想|禁止|并非|不是|如果|假如|是否|能否|可否|还是|或者|然后|并且|同时|但是|再说|取消操作|吗|么|怎么|怎样|为什么|有没有|请问).*")) throw new HomeDevices.Failure("intent_not_verified");
        String target=targetAlias(e,utterance);
        if(target.isEmpty() || text.indexOf(target)!=text.lastIndexOf(target)) throw new HomeDevices.Failure("intent_not_verified");
        text=text.replace(target,"").replaceAll("^(?:你好小爱)?(?:请|帮我|给我|把|将)*","").replaceAll("(?:一下|吧)$","");
        text=text.replaceAll("^(?:的)",""); String action=capability.action;
        String grammar=null;
        if(action.equals("power_on")) grammar="(?:打开|开启|开机|启动|开)(?:电源)?|(?:电源)(?:打开|开启)";
        else if(action.equals("power_off")) grammar="(?:关闭|关掉|关机|关)(?:电源)?|(?:电源)(?:关闭|关掉)";
        else if(action.equals("open")) grammar="打开|开启|拉开|开";
        else if(action.equals("close")) grammar="关闭|关上|合上|拉上|关";
        else if(action.equals("stop")) grammar="停止|停下|停止播放|停止清扫";
        else if(action.equals("play")) grammar="播放|开始播放|继续播放";
        else if(action.equals("pause")) grammar="暂停|暂停播放|暂停清扫";
        else if(action.equals("start")) grammar="开始|启动|开始清扫|开始打扫|清扫|打扫";
        else if(action.equals("return_to_base")) grammar="回充|返回充电|返回充电座|回充电座|返回基站";
        else if(action.equals("press")) grammar="运行|执行|启动|开始";
        if(grammar!=null) { if(!text.matches("(?:"+grammar+")")) throw new HomeDevices.Failure("intent_not_verified"); return; }
        if(capability.type.equals("boolean")) {
            String yes=action.equals("set_muted")?"静音|开启静音|打开静音":"摇头|开启摇头|打开摇头|开始摇头";
            String no=action.equals("set_muted")?"取消静音|关闭静音|解除静音":"关闭摇头|停止摇头|取消摇头";
            if(!text.matches(Boolean.TRUE.equals(value)?yes:no)) throw new HomeDevices.Failure("intent_not_verified"); return;
        }
        if(capability.type.equals("string")) {
            String raw=normalize((String)value), label=normalize(optionLabel((String)value));
            String prefix="(?:模式|运行模式|风速模式|吸力模式|预设模式|输入来源|来源|选项)?(?:设置为|设为|设成|调为|调到|切换为|切换到|选择|使用)";
            if(!text.matches(prefix+"(?:"+Pattern.quote(raw)+"|"+Pattern.quote(label)+")(?:模式)?")) throw new HomeDevices.Failure("intent_not_verified"); return;
        }
        String descriptor="数值|值";
        if(action.equals("set_temperature")) descriptor="温度|目标温度";
        else if(action.equals("set_brightness")) descriptor="亮度";
        else if(action.equals("set_color_temperature")) descriptor="色温";
        else if(action.equals("set_percentage")) descriptor="风速|速度";
        else if(action.equals("set_position")) descriptor="位置|打开位置|开度";
        else if(action.equals("set_humidity")) descriptor="湿度|目标湿度";
        else if(action.equals("set_volume")) descriptor="音量|声音";
        Matcher match=Pattern.compile("^(?:(?:"+descriptor+"))?(?:设置为|设为|设成|调为|调到|调整到|调成|设置成)(.+)$").matcher(text);
        if(!match.matches()) throw new HomeDevices.Failure("intent_not_verified");
        String number=match.group(1);
        if(capability.unit.equals("%")) number=number.replaceAll("^百分之","").replaceAll("[%％]$","");
        else if(action.equals("set_temperature")) {
            boolean celsius=number.matches(".*(?:摄氏度|℃|°c)$"),fahrenheit=number.matches(".*(?:华氏度|℉|°f)$");
            if((celsius&&!capability.unit.equals("°C"))||(fahrenheit&&!capability.unit.equals("°F")))
                throw new HomeDevices.Failure("invalid_value");
            number=number.replaceAll("(?:摄氏度|华氏度|度|℃|℉|°c|°f)$","");
        }
        else if(capability.unit.equals("K")) number=number.replaceAll("(?:开尔文|k)$","");
        else if(!capability.unit.isEmpty()) number=number.replaceAll(Pattern.quote(normalize(capability.unit))+"$","");
        double observed=chineseNumber(number);
        if(!Double.isFinite(observed)||Math.abs(observed-((Number)value).doubleValue())>1e-7) throw new HomeDevices.Failure("intent_not_verified");
    }
    static double chineseNumber(String text) {
        if(text==null||text.isEmpty()||text.length()>24) return Double.NaN;
        if(text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) { try{return Double.parseDouble(text);}catch(Exception ignored){return Double.NaN;} }
        boolean negative=text.startsWith("负"); if(negative) text=text.substring(1);
        String[] pieces=text.split("[点點]",-1); if(pieces.length>2||pieces[0].isEmpty()) return Double.NaN;
        String digits="零一二三四五六七八九"; double whole=0; int section=0,current=-1,lastUnit=10000;boolean zeroAfterUnit=false;
        boolean hasUnit=pieces[0].matches(".*[十百千万].*");
        if(!hasUnit) {
            for(char ch:pieces[0].toCharArray()) { int d=ch=='两'?2:digits.indexOf(ch); if(d<0)return Double.NaN; whole=whole*10+d; }
        } else {
            for(char ch:pieces[0].toCharArray()) {
                int d=ch=='两'?2:digits.indexOf(ch);
                if(d>=0) { if(current>0||(current==0&&d==0))return Double.NaN; current=d;if(d==0)zeroAfterUnit=true;continue; }
                int unit=ch=='十'?10:ch=='百'?100:ch=='千'?1000:ch=='万'?10000:0;
                if(unit==0||current==0)return Double.NaN;
                if(unit==10000) { if(whole!=0)return Double.NaN; whole=(section+(current<0?(section==0?1:0):current))*10000;section=0;current=-1;lastUnit=10000;zeroAfterUnit=false; }
                else { if(unit>=lastUnit)return Double.NaN; section+=(current<0?1:current)*unit;current=-1;lastUnit=unit;zeroAfterUnit=false; }
            }
            if(current==0||(current>0&&lastUnit>10&&!zeroAfterUnit))return Double.NaN;
            whole+=section+(current<0?0:current);
        }
        if(pieces.length==2) { if(pieces[1].isEmpty())return Double.NaN; double place=.1;
            for(char ch:pieces[1].toCharArray()) { int d=digits.indexOf(ch); if(d<0)return Double.NaN;whole+=d*place;place/=10; } }
        return negative?-whole:whole;
    }
}
