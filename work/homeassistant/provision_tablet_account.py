"""Create a local, non-admin HA client identity. Never print credentials."""
import json
import secrets
import time
import websocket
from ha_session import api, request, access_token, save_private, PRIVATE, CLIENT

ENTITY = 'switch.wiing_cn_1169635918_v8icm_on_p_2_1'

class HAWebSocket:
    def __init__(self, token):
        self.socket = websocket.create_connection('ws://127.0.0.1:8123/api/websocket', timeout=30)
        assert json.loads(self.socket.recv())['type'] == 'auth_required'
        self.socket.send(json.dumps({'type':'auth','access_token':token}))
        assert json.loads(self.socket.recv())['type'] == 'auth_ok'
        self.seq = 0
    def call(self, kind, **data):
        self.seq += 1
        self.socket.send(json.dumps({'id':self.seq,'type':kind,**data}))
        while True:
            result = json.loads(self.socket.recv())
            if result.get('id') == self.seq:
                if not result.get('success'):
                    raise RuntimeError('WebSocket operation failed: ' + kind + ' ' + str(result.get('error',{}).get('code')))
                return result.get('result')
    def close(self): self.socket.close()

if __name__ == '__main__':
    path = PRIVATE / 'tablet-account.json'
    data = json.loads(path.read_text()) if path.exists() else {
        'username':'tablet_launcher', 'password':secrets.token_urlsafe(30)}
    save_private('tablet-account.json', data)
    owner = HAWebSocket(access_token())
    try:
        if not data.get('user_id'):
            user = owner.call('config/auth/create',name='平板桌面',group_ids=['system-users'],local_only=True)['user']
            assert user['is_owner'] is False and user['local_only'] is True
            assert user['group_ids'] == ['system-users']
            data['user_id'] = user['id']
            save_private('tablet-account.json',data)
        if not data.get('credentials_created'):
            owner.call('config/auth_provider/homeassistant/create',user_id=data['user_id'],
                       username=data['username'],password=data['password'])
            data['credentials_created'] = True
            save_private('tablet-account.json',data)
        owner.call('config/entity_registry/update',entity_id=ENTITY,name='烤箱插座')
    finally:
        owner.close()
    if not data.get('long_lived_token'):
        flow = request('/auth/login_flow','POST',{
            'client_id':CLIENT,'handler':['homeassistant',None],'redirect_uri':CLIENT})
        signed_in = request('/auth/login_flow/'+flow['flow_id'],'POST',{
            'client_id':CLIENT,'username':data['username'],'password':data['password']})
        assert signed_in['type'] == 'create_entry'
        session = request('/auth/token','POST',{
            'client_id':CLIENT,'grant_type':'authorization_code','code':signed_in['result']},form=True)
        client = HAWebSocket(session['access_token'])
        try:
            user = client.call('auth/current_user')
            assert user['id'] == data['user_id'] and not user['is_owner'] and not user['is_admin']
            data['long_lived_token'] = client.call('auth/long_lived_access_token',client_name='极简桌面（USB）',lifespan=365)
            data['issued_at'] = time.time()
            data['lifespan_days'] = 365
            save_private('tablet-account.json',data)
        finally:
            client.close()
        request('/auth/token','POST',{'action':'revoke','token':session['refresh_token']},form=True)
    state = request('/api/states/'+ENTITY,token=data['long_lived_token'])
    assert state['entity_id'] == ENTITY
    save_private('tablet-config.json',{
        'base_url':'http://127.0.0.1:18123','token':data['long_lived_token'],
        'entity_id':ENTITY,'label':''})
    print(json.dumps({'client':'local non-admin user','token_lifespan_days':365,
                      'device':state['attributes'].get('friendly_name'),
                      'state':state['state'],'physical_commands_sent':False},ensure_ascii=False))
