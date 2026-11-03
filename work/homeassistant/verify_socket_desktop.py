"""Read-only device verification; never sends switch or light service calls."""
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from configure_tablet import ADB, QA, ROOT, run, snapshot
from ha_session import api

ENTITY = 'switch.wiing_cn_1169635918_v8icm_on_p_2_1'
APP = 'com.alex.minimalhome'

def texts(nodes):
    return [n.get('text') for n in nodes if n.get('text')]

def idle_home():
    run('shell', 'am', 'force-stop', APP)
    run('shell', 'input', 'keyevent', 'KEYCODE_HOME')

def await_state(name, expected):
    for attempt in range(6):
        nodes = snapshot(name)
        values = texts(nodes)
        if expected in values:
            return nodes
        time.sleep(1)
    raise RuntimeError('Expected desktop state did not appear: ' + expected)

before = api('/api/states/' + ENTITY)['state']
assert before == 'on', 'Unexpected current plug state; no device operation performed'
run('reverse', 'tcp:18123', 'tcp:8123')
idle_home()
online = await_state('socket-cache-online', '已开启')
assert '烤箱插座' in texts(online)
print('Online:', texts(online), flush=True)
try:
    run('reverse', '--remove', 'tcp:18123')
    idle_home()
    offline = await_state('socket-cache-offline', '无法连接 · 点击刷新')
    assert '烤箱插座' in texts(offline)
    assert ENTITY not in texts(offline)
    control = next(n for n in offline if n.get('text') == '等待状态')
    assert control.get('enabled') == 'false'
    print('Offline cold start:', texts(offline), flush=True)
finally:
    run('reverse', 'tcp:18123', 'tcp:8123')
    idle_home()
restored = await_state('socket-final', '已开启')
assert '烤箱插座' in texts(restored)
assert next(n for n in restored if n.get('text') == '关闭').get('enabled') == 'true'
print('Reconnected:', texts(restored), flush=True)
after = api('/api/states/' + ENTITY)['state']
assert after == before
preview = ROOT / 'outputs/桌面插座控制预览.png'
with preview.open('wb') as f:
    subprocess.run(ADB + ['exec-out', 'screencap', '-p'], stdout=f, check=True)
report = {
    'verified_at': datetime.now(timezone.utc).isoformat(),
    'device': '烤箱插座', 'entity_id': ENTITY,
    'state_before': before, 'state_after': after,
    'reloaded_encrypted_credentials': True,
    'disconnection_disables_control': True,
    'offline_cold_start_preserves_device_name': True,
    'reconnection_restores_state': True,
    'actual_power_toggle_tested': False,
    'physical_control_commands_sent': False,
    'screenshot': str(preview)
}
(QA / 'socket-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print('PASS: current power state preserved; no physical control commands sent.', flush=True)
