#!/usr/bin/env python3
"""Set up only this bridge's HA helpers; never calls a physical-device service."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.request import Request, build_opener, ProxyHandler
import websocket

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(__file__).resolve().parent
PRIVATE = BASE / 'private'
sys.path.insert(0, str(ROOT/'work/tablet-ha'))
sys.path.insert(0, str(ROOT/'work/homeassistant'))
from verify_local_service import Adb, read_ha_config, read_private_json, DEFAULT_CONFIG, NoRedirect
import ha_session

TARGETS = {'curtain': ('主卧窗帘', 'cover.homepad_bedroom_curtain'),
           'sheer': ('主卧窗纱', 'cover.homepad_bedroom_sheer')}
COMMAND = 'input_text.homepad_tmall_command'
LAMP_NAME = '奋腾达吸顶灯'
LAMP_ENTITY = 'binary_sensor.homepad_tmall_lamp'
LAMP_REPORT = 'input_text.homepad_tmall_lamp_report'
LAMP_HELPER = 'HomePad Tmall Lamp Report'
# Match the launcher's public-label guard before sending the projection.
PRIVATE_LABEL = re.compile(
    r'https?://|bearer\s|(?:sk-|eyJ)[A-Za-z0-9_-]{12,}|[a-f0-9]{32,}|'
    r'[A-Za-z0-9_-]{48,}|(?:\d{1,3}\.){3}\d{1,3}|[a-z_]+\.[a-z0-9_]{3,}',
    re.IGNORECASE,
)


def private_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
    path.chmod(0o600)


class API:
    def __init__(self):
        self.adb = Adb()
        self.port = None
        self.socket = None
        self.seq = 0
        self.new_template_entities = set()

    def __enter__(self):
        self.port = self.adb.run('forward', 'tcp:0', 'tcp:8123')
        if not re.fullmatch(r'\d{1,5}', self.port):
            raise RuntimeError('invalid_owned_forward')
        self.base = 'http://127.0.0.1:'+self.port
        try:
            ha_session.BASE = self.base
            self.token = ha_session.access_token()
            self.socket = self.connect(self.token)
            return self
        except Exception:
            self.__exit__(None, None, None)
            raise

    def connect(self, token):
        sock = websocket.create_connection('ws://127.0.0.1:'+self.port+'/api/websocket', timeout=15,
                                           http_proxy_host=None)
        if json.loads(sock.recv()).get('type') != 'auth_required':
            raise RuntimeError('unexpected_ha_authentication')
        sock.send(json.dumps({'type':'auth','access_token':token}))
        if json.loads(sock.recv()).get('type') != 'auth_ok':
            sock.close()
            raise RuntimeError('ha_authentication_failed')
        return sock

    def __exit__(self, *unused):
        if self.socket:
            self.socket.close()
        if self.port:
            self.adb.run('forward', '--remove', 'tcp:'+self.port)

    def ws(self, kind, **fields):
        if kind not in {'auth/current_user','config/entity_registry/list','config/device_registry/list',
                        'config/area_registry/list','config/entity_registry/update','input_text/list',
                        'input_text/create','config_entries/get'}:
            raise ValueError('unsupported_configuration_operation')
        if kind == 'config/entity_registry/update' and not fields.get('entity_id','').startswith(('cover.homepad_', 'input_text.homepad_tmall_')):
            # Initial template IDs are validated by the caller against our new config entry.
            if fields.get('entity_id') not in self.new_template_entities:
                raise ValueError('registry_scope_refused')
        if kind == 'input_text/create' and fields.get('name') not in {
                'HomePad Tmall Command','HomePad Tmall Curtain Report','HomePad Tmall Sheer Report',LAMP_HELPER}:
            raise ValueError('helper_scope_refused')
        self.seq += 1
        self.socket.send(json.dumps({'id':self.seq,'type':kind,**fields}))
        while True:
            reply = json.loads(self.socket.recv())
            if reply.get('id') != self.seq:
                continue
            if not reply.get('success'):
                raise RuntimeError('ha_configuration_failed_'+str(reply.get('error',{}).get('code','unknown')))
            return reply.get('result')

    def http(self, path, method='GET', body=None):
        allowed = method=='GET' and (path in {'/api/states','/api/config','/api/services','/api/states/'+LAMP_ENTITY} or path.startswith('/api/states/cover.homepad_'))
        allowed |= method in {'POST','DELETE'} and path.startswith('/api/config/config_entries/flow')
        if not allowed:
            raise ValueError('ha_http_scope_refused')
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base+path, data=data, method=method,
                          headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json'})
        with build_opener(ProxyHandler({}),NoRedirect()).open(request,timeout=20) as response:
            data = response.read(2*1024*1024+1)
        if len(data)>2*1024*1024:
            raise RuntimeError('response_too_large')
        return json.loads(data) if data else None


def cover_options(target):
    name, _ = TARGETS[target]
    report = 'input_text.homepad_tmall_'+target+'_report'
    parsed = "states('"+report+"') | from_json({})"
    actions = {}
    for action in ['open','close','stop']:
        value = ('{{ {"target":"'+target+'","action":"'+action+'",'
                 '"id":((as_timestamp(now())*1000000)|int|string),"created":as_timestamp(now())} | to_json }}')
        actions[action+'_cover'] = [
            {'condition':'template','value_template':
             "{% set r = "+parsed+" %}{{ r.get('ready',false) and r.get('online',false) and 0 <= as_timestamp(now()) - r.get('observed',0)|float(0) < 120 }}"},
            {'action':'input_text.set_value','target':{'entity_id':COMMAND},'data':{'value':value}}]
    return {'name':name,'device_class':'curtain',
            'state':"{% set r = "+parsed+" %}{{ 'closed' if r.get('position',-1)|int(-1) == 0 else 'open' }}",
            'position':"{% set r = "+parsed+" %}{{ r.get('position') }}",
            'additional_options':{'availability':
                "{% set r = "+parsed+" %}{{ r.get('online',false) and r.get('ready',false) and 0 <= as_timestamp(now()) - r.get('observed',0)|float(0) < 120 }}"},
            **actions}


def start_template_flow(api, domain):
    flow = api.http('/api/config/config_entries/flow','POST',{'handler':'template','show_advanced_options':True})
    path = '/api/config/config_entries/flow/'+flow['flow_id']
    if flow.get('type')=='menu' and domain in flow.get('menu_options',[]):
        flow = api.http(path,'POST',{'next_step_id':domain})
    if flow.get('step_id')!=domain:
        api.http(path,'DELETE')
        raise RuntimeError('unexpected_template_flow')
    return path, flow


def inspect(api):
    path, flow = start_template_flow(api, 'cover')
    try:
        print(json.dumps({'template_cover_form':flow,'existing_bridge_helpers':
                          [row for row in api.ws('input_text/list') if row.get('name','').startswith('HomePad Tmall')]},ensure_ascii=False))
    finally:
        api.http(path,'DELETE')


def ensure_template_entity(api, entity, options):
    domain = entity.split('.')[0]
    current = [row for row in api.ws('config/entity_registry/list') if row['entity_id'] == entity]
    if current:
        if (len(current) != 1 or current[0].get('platform') != 'template'
                or entity == LAMP_ENTITY and current[0].get('name') != options['name']):
            raise RuntimeError('existing_entity_identity_conflict')
        return current[0]['config_entry_id']

    path, _ = start_template_flow(api, domain)
    result = api.http(path, 'POST', options)
    if result.get('type') != 'create_entry':
        private_json(PRIVATE/'last-flow-error.json', result)
        api.http(path, 'DELETE')
        raise RuntimeError('template_configuration_rejected')
    entry = result['result']['entry_id']
    for _ in range(30):
        matches = [row for row in api.ws('config/entity_registry/list')
                   if row.get('config_entry_id') == entry and row['entity_id'].startswith(domain + '.')]
        if len(matches) == 1:
            break
        time.sleep(.2)
    if len(matches) != 1:
        raise RuntimeError('template_registry_not_ready')
    api.new_template_entities.add(matches[0]['entity_id'])
    api.ws('config/entity_registry/update', entity_id=matches[0]['entity_id'], new_entity_id=entity, name=options['name'])
    return entry


def install_lamp(api):
    config_path = PRIVATE/'runtime-config.json'
    runtime = read_private_json(config_path)
    helpers = [row for row in api.ws('input_text/list')
               if row.get('id') == LAMP_REPORT.split('.')[1] or row.get('name') == LAMP_HELPER]
    if not helpers:
        helpers = [api.ws('input_text/create', name=LAMP_HELPER, min=0, max=255, mode='text')]
    if (len(helpers) != 1 or helpers[0].get('name') != LAMP_HELPER
            or helpers[0].get('id') != LAMP_REPORT.split('.')[1]):
        raise RuntimeError('lamp_helper_identity_conflict')
    parsed = "{% set r = states('" + LAMP_REPORT + "') | from_json({}) %}"
    ensure_template_entity(api, LAMP_ENTITY, {
        'name': LAMP_NAME,
        'state': parsed + "{{ r.get('power') == 'on' }}",
        'additional_options': {'availability': parsed +
            "{{ r.get('online',false) and r.get('power') in ['on','off'] and 0 <= as_timestamp(now()) - r.get('observed',0)|float(0) < 150 }}"},
    })
    runtime['lamp_status_enabled'] = True
    private_json(config_path, runtime)
    print(json.dumps({'lamp_entity_configured': True, 'read_only': True, 'device_actions_sent': 0}))


def install(api):
    existing = {row['name']:row for row in api.ws('input_text/list')}
    for name in ['HomePad Tmall Command','HomePad Tmall Curtain Report','HomePad Tmall Sheer Report']:
        if name not in existing:
            api.ws('input_text/create',name=name,min=0,max=255,mode='text')
    entries={target:ensure_template_entity(api, entity, cover_options(target))
             for target,(_,entity) in TARGETS.items()}
    owner=api.ws('auth/current_user')
    config=read_ha_config(DEFAULT_CONFIG)
    sock=api.connect(config['token'])
    try:
        sock.send(json.dumps({'id':1,'type':'auth/current_user'}))
        user=json.loads(sock.recv())
        if not user.get('success'):
            raise RuntimeError('tablet_user_unavailable')
        user=user['result']
    finally:
        sock.close()
    runtime_path=PRIVATE/'runtime-config.json'
    lamp_enabled=runtime_path.is_file() and read_private_json(runtime_path).get('lamp_status_enabled') is True
    private_json(runtime_path,{'ha_token':config['token'],
                 'allowed_user_ids':sorted({owner['id'],user['id']}),'controls_enabled':True,
                 'lamp_status_enabled':lamp_enabled})
    private_json(PRIVATE/'installed.json',{'entries':entries,'entities':{key:value[1] for key,value in TARGETS.items()},
                 'configured_at_utc':datetime.now(timezone.utc).isoformat(),'device_actions_sent':0})
    print(json.dumps({'configured':True,'cover_count':len(entries),'physical_device_service_calls':0}))


def export_catalog(api):
    registry=api.ws('config/entity_registry/list')
    devices=api.ws('config/device_registry/list')
    areas={row['area_id']:row['name'] for row in api.ws('config/area_registry/list')}
    states={row['entity_id']:row for row in api.http('/api/states')}
    config=api.http('/api/config')
    allowed=set('binary_sensor button climate cover device_tracker event fan humidifier light media_player notify number select sensor switch text vacuum water_heater weather'.split())
    entries={row['entry_id'] for row in api.ws('config_entries/get') if row.get('domain')=='xiaomi_home'}
    manifest={'schema':1,'temperature_unit':config['unit_system']['temperature'],'devices':[]}
    def entity(row):
        eid=row['entity_id']; friendly=states.get(eid,{}).get('attributes',{}).get('friendly_name') or row.get('name') or row.get('original_name') or eid
        if not friendly.strip() or PRIVATE_LABEL.search(friendly):
            return None
        category=row.get('entity_category') or 'normal'
        if category not in {'normal', 'config', 'diagnostic'}: category='diagnostic'
        if re.search(r'\s\*\s',friendly): category='config'
        return {'entity_id':eid,'ref':'e_'+hashlib.sha256(eid.encode()).hexdigest()[:24],
                'name':friendly,'category':category,'disabled':row.get('disabled_by') is not None,'hidden':row.get('hidden_by') is not None}
    for device in devices:
        if not entries.intersection(device.get('config_entries',[])):
            continue
        selected=[row for row in registry if row.get('device_id')==device['id'] and row['entity_id'].split('.')[0] in allowed]
        projected = [value for row in selected if (value := entity(row)) is not None]
        manifest['devices'].append({'id':'d_'+hashlib.sha256(device['id'].encode()).hexdigest()[:24],
            'name':device.get('name_by_user') or device.get('name') or '未命名设备',
            'room':areas.get(device.get('area_id'),'') or device.get('suggested_area') or '',
            'model':device.get('model') or '', 'entities':projected})
    for target,(name,eid) in TARGETS.items():
        rows=[row for row in registry if row['entity_id']==eid and row.get('platform')=='template']
        if len(rows)!=1 or eid not in states:
            raise RuntimeError('actual_cover_entity_required')
        projected = entity(rows[0])
        if projected is None:
            raise RuntimeError('unsafe_cover_label')
        manifest['devices'].append({'id':'d_'+hashlib.sha256(('homepad_tmall:'+rows[0]['config_entry_id']).encode()).hexdigest()[:24],
            'name':name,'room':'主卧','model':'杜亚 V7 · 天猫精灵桥接','entities':[projected]})
    lamp = [row for row in registry if row['entity_id'] == LAMP_ENTITY and row.get('platform') == 'template'
            and row.get('name') == LAMP_NAME]
    if len(lamp) == 1 and LAMP_ENTITY in states:
        projected = entity(lamp[0])
        if projected is None:
            raise RuntimeError('unsafe_lamp_label')
        manifest['devices'].append({'id':'d_'+hashlib.sha256(('homepad_tmall:'+lamp[0]['config_entry_id']).encode()).hexdigest()[:24],
            'name':LAMP_NAME,'room':'客厅','model':'FTD 奋腾达 · 天猫精灵桥接','entities':[projected]})
    private_json(PRIVATE/'home-device-catalog.json',manifest)
    api.adb.run('push',str(PRIVATE/'home-device-catalog.json'),'/sdcard/Android/data/com.alex.minimalhome/files/home-device-catalog.json')
    print(json.dumps({'catalog_exported':True,'total_devices':len(manifest['devices']),'bridge_covers':2,
                      'bridge_read_only_lamp':len(lamp) == 1 and LAMP_ENTITY in states,'device_actions_sent':0}))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('operation',choices=['inspect','install','install-lamp','export','verify'])
    args=parser.parse_args()
    with API() as api:
        if args.operation=='inspect':inspect(api)
        elif args.operation=='install':install(api)
        elif args.operation=='install-lamp':install_lamp(api)
        elif args.operation=='export':export_catalog(api)
        else:
            result=[]
            for _,eid in TARGETS.values():
                state=api.http('/api/states/'+eid)
                result.append({'entity':eid,'state':state['state'],'position':state['attributes'].get('current_position'),
                               'supported_features':state['attributes'].get('supported_features')})
            print(json.dumps({'covers':result,'device_actions_sent':0},ensure_ascii=False))


if __name__=='__main__':
    os.umask(0o077)
    try: main()
    except Exception as error:
        print(json.dumps({'ha_setup_error':type(error).__name__}))
        raise SystemExit(1)
