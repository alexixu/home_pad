#!/usr/bin/env python3
"""Scoped, reversible removal of reviewed optional preload packages."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
ADB = ROOT / 'work/android-tools/platform-tools/adb'
SERIAL = '834b3988'
TARGETS = [
    ('com.miui.systemAdSolution', '系统广告组件'),
    ('com.miui.analytics', '小米统计分析'),
    ('com.miui.hybrid', '快应用'),
    ('com.miui.thirdappassistant', '第三方应用辅助'),
    ('com.xiaomi.gamecenter', '游戏中心'),
    ('com.xiaomi.gamecenter.sdk.service', '游戏服务'),
    ('com.duokan.reader', '多看阅读'),
    ('com.miui.player', '小米音乐'),
    ('com.miui.video', '小米视频'),
    ('com.miui.personalassistant', '负一屏/智能助理'),
    ('com.android.quicksearchbox', '全局搜索'),
    ('com.miui.yellowpage', '黄页'),
    ('com.miui.cleanmaster', '清理大师'),
    ('com.miui.miservice', '服务与反馈'),
    ('cn.wps.moffice_eng', '预装 WPS Office'),
    ('cn.wps.moffice_eng.xiaomi.lite', '预装 WPS 小米版'),
    ('com.mipay.wallet', '小米钱包'),
    ('com.xiaomi.smarthome', '米家'),
    ('com.miui.huanji', '小米换机'),
    ('com.miui.newmidrive', '小米云盘'),
]

def adb(*args, timeout=90):
    return subprocess.run([str(ADB), '-s', SERIAL, *args], text=True,
                          capture_output=True, timeout=timeout)

def shell(*args):
    r = adb('shell', *args)
    if r.returncode:
        raise RuntimeError(r.stderr or r.stdout)
    return r.stdout.strip()

def installed():
    return set(shell('pm', 'list', 'packages', '--user', '0').replace('package:', '').splitlines())

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def save(plan):
    (OUT / 'plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2))

def preflight():
    if shell('getprop', 'ro.product.device') != 'elish':
        raise RuntimeError('Wrong device')
    if shell('am', 'get-current-user') != '0':
        raise RuntimeError('Unexpected foreground user')

def prepare():
    if (OUT / 'plan.json').exists():
        raise RuntimeError('Plan exists; preserve previous records')
    current = installed()
    system = set(shell('pm', 'list', 'packages', '-s').replace('package:', '').splitlines())
    plan = {'prepared_at': datetime.datetime.now().astimezone().isoformat(),
            'device': 'elish', 'serial': SERIAL, 'user': 0,
            'mode': 'pm uninstall -k --user 0',
            'data_policy': 'Keep application data via -k; this is not a backup of private application data.',
            'packages': []}
    for package, label in TARGETS:
        if package not in current:
            raise RuntimeError(f'{package} not installed')
        paths = shell('pm', 'path', package).splitlines()
        paths = [s.removeprefix('package:') for s in paths if s.startswith('package:')]
        if not paths:
            raise RuntimeError(f'No APK path for {package}')
        directory = OUT / 'apk-backups' / package
        directory.mkdir(parents=True, exist_ok=True)
        files = []
        for remote in paths:
            local = directory / Path(remote).name
            r = adb('pull', remote, str(local), timeout=180)
            if r.returncode or not local.is_file() or local.stat().st_size == 0:
                raise RuntimeError(f'APK backup failed for {package}: {r.stderr}')
            files.append({'remote': remote, 'local': str(local), 'bytes': local.stat().st_size,
                          'sha256': sha(local)})
        dump = shell('dumpsys', 'package', package)
        fields = [s.strip() for s in dump.splitlines() if re.match(
            r'\s*(codePath=|versionCode=|versionName=|firstInstallTime=|lastUpdateTime=|pkgFlags=|flags=|User 0:)', s)]
        plan['packages'].append({'package': package, 'label': label,
                                 'system_package': package in system,
                                 'apk_backups': files, 'metadata': fields, 'status': 'prepared'})
        save(plan)
        print(f'已备份 {label} ({package})', flush=True)
    plan['prepared_complete'] = True
    save(plan)
    print(f'准备完成：{len(plan["packages"])} 项，APK 备份 '
          f'{sum(f["bytes"] for p in plan["packages"] for f in p["apk_backups"])} 字节', flush=True)

def apply():
    plan = json.loads((OUT / 'plan.json').read_text())
    if not plan.get('prepared_complete') or [(p['package'],p['label']) for p in plan['packages']] != TARGETS:
        raise RuntimeError('Incomplete or unexpected plan')
    for p in plan['packages']:
        for f in p['apk_backups']:
            if sha(Path(f['local'])) != f['sha256']:
                raise RuntimeError('APK backup verification failed')
    for p in plan['packages']:
        if p['status'] == 'uninstalled_for_user':
            continue
        if p['package'] not in installed():
            raise RuntimeError(f'Unexpected state for {p["package"]}')
        r = adb('shell', 'pm', 'uninstall', '-k', '--user', '0', p['package'])
        present = p['package'] in installed()
        p['result'] = (r.stdout + r.stderr).strip()
        p['status'] = 'uninstalled_for_user' if r.returncode == 0 and 'Success' in r.stdout and not present else 'failed'
        p['verified_absent_from_user_0'] = not present
        p['changed_at'] = datetime.datetime.now().astimezone().isoformat()
        save(plan)
        print(f'{p["label"]}: {p["status"]} — {p["result"]}', flush=True)
    plan['finished_at'] = datetime.datetime.now().astimezone().isoformat()
    save(plan)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['prepare', 'apply'])
    args = parser.parse_args()
    preflight()
    prepare() if args.action == 'prepare' else apply()
