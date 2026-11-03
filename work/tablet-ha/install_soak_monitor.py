#!/usr/bin/env python3
"""Install an independent read-only tablet soak monitor over temporary USB transport."""
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
ADB = [str(ROOT / 'work/android-tools/platform-tools/adb'), '-s', '834b3988']
HERE = Path(__file__).resolve().parent
PORT = 18082
DURATION = 86400

def command(*args):
    r = subprocess.run(ADB + list(args), capture_output=True)
    if r.returncode:
        raise RuntimeError('ADB setup operation failed; arguments withheld')
    return r.stdout

def main():
    os.umask(0o077)
    config = json.loads((ROOT / 'work/homeassistant/private/tablet-config.json').read_text())
    config = {'base_url': 'http://127.0.0.1:8123', 'token': config['token'],
              'entity_id': config['entity_id']}
    key = secrets.token_urlsafe(32)
    base = 'http://127.0.0.1:' + str(PORT) + '/' + key
    started = threading.Event()
    report = {}
    runner = r'''#!/data/data/com.termux/files/usr/bin/bash
set -eu
umask 077
TABLET_HOME=/data/data/com.termux/files/home/tablet-ha
exec 9>"$TABLET_HOME/config/.soak/monitor.lock"
flock -n 9 || exit 0
exec "$TABLET_HOME/run-in-ha" /usr/local/bin/python3 -P /config/.soak/monitor.py --config /config/.soak/config.json --output /config/.soak/samples.jsonl --duration-seconds 86400 --interval-seconds 60
'''
    boot = r'''#!/data/data/com.termux/files/usr/bin/bash
umask 077
nohup /data/data/com.termux/files/home/tablet-ha/start-soak-monitor >> /data/data/com.termux/files/home/tablet-ha/config/.soak/monitor.log 2>&1 < /dev/null &
'''
    installer = r'''#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
BASE=__BASE__
D="$HOME/tablet-ha/config/.soak"
mkdir -p "$D" "$HOME/.termux/boot"
chmod 700 "$D"
curl -fsS "$BASE/monitor.py" -o "$D/monitor.py"
curl -fsS "$BASE/config.json" -o "$D/config.json"
curl -fsS "$BASE/runner" -o "$HOME/tablet-ha/start-soak-monitor"
curl -fsS "$BASE/boot" -o "$HOME/.termux/boot/00-soak-monitor"
chmod 600 "$D/config.json" "$D/monitor.py"
chmod 700 "$HOME/tablet-ha/start-soak-monitor" "$HOME/.termux/boot/00-soak-monitor"
nohup "$HOME/tablet-ha/start-soak-monitor" >> "$D/monitor.log" 2>&1 < /dev/null &
for i in $(seq 1 30); do
  if [ -s "$D/samples.jsonl" ]; then
    curl -fsS -X POST --data-binary @"$D/samples.jsonl" "$BASE/status" >/dev/null
    printf '\nRead-only stability monitor started. Return to Home.\n'
    exit 0
  fi
  sleep 1
done
printf '\nMonitor did not produce a first sample.\n'
exit 1
'''.replace('__BASE__', "'" + base + "'")
    files = {'monitor.py': (HERE / 'soak_monitor.py').read_bytes(),
             'config.json': json.dumps(config).encode(), 'runner': runner.encode(),
             'boot': boot.encode(), 'setup.sh': installer.encode()}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            prefix = '/' + key + '/'
            name = self.path[len(prefix):] if self.path.startswith(prefix) else ''
            if name not in files:
                self.send_error(404)
                return
            data = files[name]
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        def do_POST(self):
            if self.path != '/' + key + '/status':
                self.send_error(404)
                return
            count = int(self.headers.get('Content-Length', '0'))
            if count <= 0 or count > 65536:
                self.send_error(400)
                return
            data = self.rfile.read(count)
            # The monitor owns this explicitly credential-free format.
            if config['token'].encode() in data:
                self.send_error(400)
                return
            samples = [json.loads(line) for line in data.splitlines() if line]
            report['initial_samples'] = samples
            report['installed_at_epoch'] = time.time()
            (HERE / 'soak-install-report.json').write_text(json.dumps(report, indent=2) + '\n')
            self.send_response(204)
            self.end_headers()
            started.set()
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        command('reverse', 'tcp:' + str(PORT), 'tcp:' + str(PORT))
        command('shell', 'am', 'start', '-n', 'com.termux/.app.TermuxActivity')
        time.sleep(1)
        if b'mInputShown=true' in command('shell', 'dumpsys', 'input_method'):
            command('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        command('shell', 'input', 'keycombination', '113', '31')
        line = "curl -fsS '" + base + "/setup.sh' -o \"$HOME/.soak-setup.sh\" && bash \"$HOME/.soak-setup.sh\"; rm -f \"$HOME/.soak-setup.sh\""
        command('shell', 'input', 'text', shlex.quote(line.replace(' ', '%s')))
        command('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
        if not started.wait(50):
            raise RuntimeError('No first sample received from the tablet')
        print('Tablet-local monitor produced an initial sample; temporary transfer removed.')
    finally:
        command('reverse', '--remove', 'tcp:' + str(PORT))
        server.shutdown()
        server.server_close()

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('Monitor installation failed: ' + type(error).__name__) from None
