"""Local Home Assistant setup client; credentials never appear in command arguments."""
import json
import os
from pathlib import Path
import time
from urllib.request import Request, urlopen
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parent
PRIVATE = ROOT / 'private'
BASE = 'http://127.0.0.1:8123'
CLIENT = BASE + '/'

def save_private(name, data):
    path = PRIVATE / name
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

def request(path, method='GET', data=None, token=None, form=False):
    headers = {}
    body = None
    if data is not None:
        body = (urlencode(data) if form else json.dumps(data)).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    with urlopen(Request(BASE + path, body, headers, method=method), timeout=60) as r:
        text = r.read().decode()
        return json.loads(text) if text else None

def access_token():
    session = json.loads((PRIVATE / 'session.json').read_text())
    if time.time() + 120 >= session.get('expires_at', 0):
        fresh = request('/auth/token', 'POST', {
            'grant_type':'refresh_token', 'refresh_token':session['refresh_token'],
            'client_id':CLIENT}, form=True)
        session.update(fresh)
        session['expires_at'] = time.time() + fresh['expires_in']
        save_private('session.json', session)
    return session['access_token']

def api(path, method='GET', data=None):
    return request(path, method, data, access_token())

if __name__ == '__main__':
    import secrets
    status = request('/api/onboarding')
    done = {x['step']:x['done'] for x in status}
    if not done['user']:
        owner_path = PRIVATE / 'owner.json'
        if owner_path.exists():
            owner = json.loads(owner_path.read_text())
        else:
            owner = {'name':'Alex', 'username':'alex', 'password':secrets.token_urlsafe(30)}
            save_private('owner.json', owner)
        result = request('/api/onboarding/users', 'POST', {
            **owner, 'client_id':CLIENT, 'language':'zh-Hans'})
        session = request('/auth/token', 'POST', {
            'grant_type':'authorization_code', 'code':result['auth_code'], 'client_id':CLIENT}, form=True)
        session['expires_at'] = time.time() + session['expires_in']
        save_private('session.json', session)
        print('Created local owner account; credentials kept in a mode-600 local file.')
    if not (PRIVATE / 'session.json').exists():
        raise SystemExit('Existing account found without this setup session; manual login required.')
    for step in ['core_config','analytics','integration']:
        if done[step]:
            continue
        body = {'client_id':CLIENT,'redirect_uri':CLIENT} if step == 'integration' else {}
        api('/api/onboarding/' + step, 'POST', body)
        print('Completed onboarding:', step)
    print('API authentication:', api('/api/').get('message'))
