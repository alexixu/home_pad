#!/bin/zsh
set -eu
# Restore only the reviewed preload packages removed by this task.
TASK_ROOT="$(cd -P -- "$(dirname -- "$0")" && cd -P .. && pwd -P)"
/usr/bin/python3 - "$TASK_ROOT" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
plan_path = root / 'work/tablet-debloat/plan.json'
plan = json.loads(plan_path.read_text())
adb = str(root / 'work/android-tools/platform-tools/adb')
serial = plan['serial']

def run(*args):
    return subprocess.run([adb, '-s', serial, *args], text=True,
                          capture_output=True, timeout=180)

def installed():
    r = run('shell', 'pm', 'list', 'packages', '--user', '0')
    if r.returncode:
        raise RuntimeError(r.stderr or r.stdout)
    return set(r.stdout.replace('package:', '').splitlines())

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

r = run('shell', 'getprop', 'ro.product.device')
if r.returncode or r.stdout.strip() != 'elish':
    raise SystemExit('请连接原来的小米平板，并允许 USB 调试。')

failures = []
for item in plan['packages']:
    if item.get('status') not in ('uninstalled_for_user', 'disabled_for_user'):
        continue
    package = item['package']
    if item['status'] == 'disabled_for_user':
        r = run('shell', 'pm', 'default-state', '--user', '0', package)
        disabled = run('shell', 'pm', 'list', 'packages', '-d', '--user', '0')
        if r.returncode == 0 and package not in disabled.stdout.replace('package:', '').splitlines():
            print(item['label'] + '：已恢复原启用状态', flush=True)
        else:
            failures.append(package)
            print(item['label'] + '：恢复启用失败 ' + (r.stdout + r.stderr).strip(), flush=True)
        continue
    if package in installed():
        print(item['label'] + '：已安装，跳过', flush=True)
        continue
    r = run('shell', 'cmd', 'package', 'install-existing', '--user', '0', package)
    if package not in installed():
        files = item['apk_backups']
        if not all(Path(f['local']).is_file() and digest(Path(f['local'])) == f['sha256'] for f in files):
            failures.append(package)
            print(item['label'] + '：APK 备份缺失或校验失败', flush=True)
            continue
        command = 'install' if len(files) == 1 else 'install-multiple'
        r = run(command, '-r', *[f['local'] for f in files])
    if package in installed():
        print(item['label'] + '：恢复成功', flush=True)
    else:
        failures.append(package)
        print(item['label'] + '：恢复失败 ' + (r.stdout + r.stderr).strip(), flush=True)

print('恢复完成。' if not failures else '未恢复：' + ', '.join(failures))
sys.exit(bool(failures))
PY
