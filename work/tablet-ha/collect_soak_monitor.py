#!/usr/bin/env python3
"""Recover final read-only soak evidence from a caller-selected NEW Termux session.

First use Termux NEW SESSION and select its empty bash. Supply its PID and /proc
start ticks. This program never opens Termux, changes sessions, sends interrupt
keys/signals, or changes recorder files. It contacts no ADB before the original
deadline and never overwrites an existing collection directory. Transfer success
is not a successful stability verdict.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
METADATA = ROOT / 'outputs/平板独立运行稳定性验证.json'
ADB = [str(ROOT / 'work/android-tools/platform-tools/adb'), '-s', '834b3988']
ALLOWED = frozenset(('samples.jsonl', 'samples.state.json', 'samples.summary.json'))
MAX_BYTES = 16 * 1024 * 1024


class CollectionError(RuntimeError):
    """Only fixed diagnostics; never include commands or transfer secrets."""


def utc(epoch=None):
    return datetime.fromtimestamp(time.time() if epoch is None else epoch, timezone.utc).isoformat()


def adb(*args):
    try:
        result = subprocess.run(ADB + list(args), capture_output=True, timeout=12)
    except (OSError, subprocess.TimeoutExpired):
        raise CollectionError('adb_unavailable') from None
    if result.returncode:
        raise CollectionError('adb_request_failed')
    return result.stdout.decode('utf-8', errors='strict')


def load_original_run(path=METADATA, now=None):
    """Local precondition, called before every possible ADB request."""
    metadata = json.loads(Path(path).read_text())
    run = metadata['initial_sample_summary']
    run_id = str(uuid.UUID(run['run_id']))
    deadline, started = run['deadline_epoch'], run['started_epoch']
    if (metadata['run_id'] != run_id or run['run_id'] != run_id
            or isinstance(deadline, bool) or not isinstance(deadline, (float, int))
            or not math.isfinite(deadline) or not math.isfinite(started)
            or deadline != started + run['duration_seconds']
            or abs(datetime.fromisoformat(metadata['deadline_utc']).timestamp() - deadline) > .001
            or abs(datetime.fromisoformat(run['deadline_utc']).timestamp() - deadline) > .001):
        raise CollectionError('original_run_metadata_invalid')
    if (time.time() if now is None else now) < deadline:
        raise CollectionError('original_soak_window_not_elapsed')
    return {key: run[key] for key in ('run_id', 'deadline_epoch', 'started_epoch',
                                     'deadline_utc', 'duration_seconds', 'interval_seconds')}


def proc_stat(raw):
    match = re.fullmatch(r'\s*(\d+) \(([^\n]*)\) (.+)\s*', raw)
    if not match:
        raise CollectionError('process_stat_invalid')
    tail = match[3].split()
    if len(tail) < 20:
        raise CollectionError('process_stat_invalid')
    return {'pid': int(match[1]), 'comm': match[2], 'state': tail[0],
            'ppid': int(tail[1]), 'start_ticks': int(tail[19])}


def process_uid(raw):
    match = re.search(r'^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$', raw, re.M)
    if not match or len(set(match.groups())) != 1:
        raise CollectionError('process_uid_invalid')
    return int(match[1])


def focused_termux(client):
    lines = [line for line in client('shell', 'dumpsys', 'window', 'windows').splitlines()
             if 'mCurrentFocus=' in line]
    return len(lines) == 1 and re.search(
        r'\bcom\.termux/(?:com\.termux)?\.app\.TermuxActivity\b', lines[0]) is not None


def observe_session(client, pid, start_ticks, require_fresh=True, require_empty=True):
    packages = client('shell', 'cmd', 'package', 'list', 'packages', '-U', 'com.termux')
    matches = re.findall(r'^package:com\.termux uid:(\d+)\s*$', packages, re.M)
    if len(matches) != 1:
        raise CollectionError('termux_uid_unverified')
    termux_uid = int(matches[0])
    process = proc_stat(client('shell', 'cat', '/proc/' + str(pid) + '/stat'))
    uid = process_uid(client('shell', 'cat', '/proc/' + str(pid) + '/status'))
    parent_pid = process['ppid']
    parent = proc_stat(client('shell', 'cat', '/proc/' + str(parent_pid) + '/stat'))
    parent_uid = process_uid(client('shell', 'cat', '/proc/' + str(parent_pid) + '/status'))
    if (process['pid'] != pid or process['start_ticks'] != start_ticks
            or process['comm'] != 'bash' or process['state'] not in ('R', 'S')
            or uid != termux_uid or parent_uid != termux_uid
            or parent['pid'] != parent_pid or parent['comm'] != 'com.termux'):
        raise CollectionError('new_session_identity_unverified')
    hz = int(client('shell', 'getconf', 'CLK_TCK').strip())
    uptime = float(client('shell', 'cat', '/proc/uptime').split()[0])
    if hz <= 0 or not math.isfinite(uptime):
        raise CollectionError('process_age_unverified')
    age = uptime - start_ticks / hz
    if age < 0 or (require_fresh and age > 120):
        raise CollectionError('new_session_not_recent')
    rows = []
    for line in client('shell', 'ps', '-A', '-o', 'PID,PPID').splitlines():
        if re.fullmatch(r'\s*PID\s+PPID\s*', line):
            continue
        match = re.fullmatch(r'\s*(\d+)\s+(\d+)\s*', line)
        if not match:
            raise CollectionError('process_tree_unverified')
        rows.append((int(match[1]), int(match[2])))
    if (pid, parent_pid) not in rows:
        raise CollectionError('process_tree_unverified')
    children = sorted(child for child, parent in rows if parent == pid)
    if require_empty and children:
        raise CollectionError('new_session_has_children')
    last = proc_stat(client('shell', 'cat', '/proc/' + str(pid) + '/stat'))
    if any(last[k] != process[k] for k in ('pid', 'comm', 'ppid', 'start_ticks')):
        raise CollectionError('session_identity_changed')
    return {**process, 'uid': uid, 'parent_comm': parent['comm'], 'age_seconds': age,
            'children': children, 'observed_at_utc': utc()}


def type_line(client, line):
    if '\n' in line or '\r' in line or not line.startswith(' '):
        raise CollectionError('input_line_invalid')
    client('shell', 'input', 'text', shlex.quote(line.replace(' ', '%s')))
    client('shell', 'input', 'keyevent', 'KEYCODE_ENTER')


def session_guard(pid, ticks):
    return ('[ "$$" = "' + str(pid) + '" ] && read -r hp_stat < /proc/$$/stat'
            + ' && hp_tail=${hp_stat##*) } && set -- $hp_tail && [ "${20}" = "' + str(ticks) + '" ]')


def download_script(base):
    return ('''#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
trap 'rm -f -- "$0"' EXIT
D="$HOME/tablet-ha/config/.soak"
BASE=__BASE__
for name in samples.state.json samples.summary.json samples.jsonl; do
    [ -f "$D/$name" ] && [ ! -L "$D/$name" ]
    curl -fsS --connect-timeout 5 --max-time 25 -X POST --data-binary @"$D/$name" "$BASE/$name" >/dev/null
done
printf '\\nRead-only records transferred; inspect the host receipt.\\n'
'''.replace('__BASE__', shlex.quote(base))).encode()


class Receiver:
    def __init__(self, destination, key=None, ready_key=None):
        self.destination = Path(destination)
        self.key = key or secrets.token_urlsafe(32)
        self.ready_key = ready_key or secrets.token_urlsafe(32)
        self.ready, self.complete = threading.Event(), threading.Event()
        self.lock = threading.Lock()
        self.received = {}
        self.script = b''
        self.closed = False

    def close(self):
        # Drain an in-flight bounded body before hashing; no late writer can change
        # the raw evidence after the receipt has been generated.
        with self.lock:
            self.closed = True

    def handler(self):
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def setup(self):
                super().setup()
                self.connection.settimeout(8)

            def do_GET(self):
                if self.path == '/' + receiver.ready_key + '/ready':
                    receiver.ready.set()
                    self.send_response(204)
                    self.end_headers()
                elif (receiver.ready.is_set() and receiver.script
                      and self.path == '/' + receiver.key + '/collect.sh'):
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(receiver.script)))
                    self.end_headers()
                    self.wfile.write(receiver.script)
                else:
                    self.send_error(404)

            def do_POST(self):
                prefix = '/' + receiver.key + '/'
                name = self.path[len(prefix):] if self.path.startswith(prefix) else ''
                if not receiver.ready.is_set() or name not in ALLOWED:
                    self.send_error(404)
                    return
                lengths = self.headers.get_all('Content-Length', [])
                if (len(lengths) != 1 or not re.fullmatch(r'[0-9]+', lengths[0])
                        or self.headers.get('Transfer-Encoding') is not None):
                    self.send_error(400)
                    return
                count = int(lengths[0])
                with receiver.lock:
                    if receiver.closed:
                        self.send_error(410)
                        return
                    if name in receiver.received:
                        self.send_error(409)
                        return
                    if not 0 <= count <= MAX_BYTES - sum(receiver.received.values()):
                        self.send_error(413)
                        return
                    try:
                        body = self.rfile.read(count)
                    except (OSError, TimeoutError):
                        self.send_error(408)
                        return
                    if len(body) != count:
                        self.send_error(400)
                        return
                    # Preserve exact bytes, including malformed JSON and partial rows.
                    try:
                        with (receiver.destination / name).open('xb') as output:
                            output.write(body)
                    except OSError:
                        self.send_error(409)
                        return
                    receiver.received[name] = len(body)
                    self.send_response(204)
                    self.end_headers()
                    if set(receiver.received) == ALLOWED:
                        receiver.complete.set()

        return Handler


def validate_records(destination, run):
    errors, parsed = [], {}
    for name in ('samples.state.json', 'samples.summary.json'):
        try:
            data = json.loads((destination / name).read_bytes())
            if not isinstance(data, dict):
                raise ValueError()
            parsed[name] = data
            if data.get('schema') != 1:
                errors.append(name + ':unsupported_schema')
            if data.get('run_id') != run['run_id']:
                errors.append(name + ':run_mismatch')
            for field in ('deadline_epoch', 'started_epoch', 'duration_seconds', 'interval_seconds'):
                if data.get(field) != run[field]:
                    errors.append(name + ':window_mismatch')
        except (ValueError, OSError):
            errors.append(name + ':invalid_json')
    summary = parsed.get('samples.summary.json', {})
    if summary.get('status') != 'window_elapsed':
        errors.append('final_summary_not_window_elapsed')
    try:
        summary_time = datetime.fromisoformat(summary['summary_utc'])
        if summary_time.tzinfo is None or summary_time.timestamp() < run['deadline_epoch']:
            raise ValueError()
    except (ValueError, TypeError, KeyError):
        errors.append('final_summary_timestamp_unverified')
    bad_rows = other_runs = matching_rows = 0
    try:
        for raw in (destination / 'samples.jsonl').read_bytes().splitlines():
            try:
                row = json.loads(raw)
                if not isinstance(row, dict):
                    raise ValueError()
                if row.get('run_id') != run['run_id']:
                    other_runs += 1
                    continue
                for field in ('epoch_s', 'monotonic_s'):
                    if (isinstance(row.get(field), bool) or not isinstance(row.get(field), (int, float))
                            or not math.isfinite(row[field])):
                        raise ValueError()
                if not isinstance(row.get('utc'), str):
                    raise ValueError()
                datetime.fromisoformat(row['utc'])
                matching_rows += 1
            except (ValueError, TypeError, UnicodeError):
                bad_rows += 1
    except OSError:
        errors.append('samples_missing')
    if bad_rows:
        errors.append('malformed_raw_rows_preserved')
    if other_runs:
        errors.append('foreign_run_rows_preserved')
    if not matching_rows:
        errors.append('no_valid_sample_rows')
    if summary.get('samples') != matching_rows or summary.get('malformed_log_rows') != bad_rows:
        errors.append('summary_row_count_mismatch')
    return {'valid_final_collection': not errors, 'errors': errors,
            'matching_sample_rows': matching_rows, 'malformed_raw_rows': bad_rows,
            'foreign_run_rows': other_runs, 'stability_verdict': 'pending_separate_offline_review',
            'physical_power_verified': False}


def collect(args, client=adb, metadata_path=METADATA, now=None):
    os.umask(0o077)
    run = load_original_run(metadata_path, now=now)
    destination = (Path(args.destination) if args.destination else HERE / 'soak-collected-runs'
                   / (run['run_id'] + '-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
                      + '-' + secrets.token_hex(4)))
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    destination.mkdir(mode=0o700, exist_ok=False)
    receipt = {'schema': 1, 'run_id': run['run_id'], 'original_deadline_utc': run['deadline_utc'],
               'collected_at_utc': utc(), 'sha256': {}, 'sizes': {}, 'process_before': None,
               'process_after': None, 'new_session_verified': False, 'session_shell_attested': False,
               'complete_transfer': False, 'validation': None, 'cleanup_errors': [],
               'physical_power_verified': False, 'device_control_requests_sent': 0}
    receiver = Receiver(destination)
    server = reverse_port = failure = None
    try:
        receipt['process_before'] = observe_session(client, args.session_pid, args.session_start_ticks)
        if not focused_termux(client):
            raise CollectionError('termux_activity_not_focused')
        receipt['new_session_verified'] = True
        server = ThreadingHTTPServer(('127.0.0.1', 0), receiver.handler())
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        # Successful --no-rebind bind reserves an unused device-side port.
        for unused in range(8):
            candidate = 20000 + secrets.randbelow(40000)
            try:
                client('reverse', '--no-rebind', 'tcp:' + str(candidate), 'tcp:' + str(server.server_port))
                reverse_port = candidate
                break
            except CollectionError:
                pass
        if reverse_port is None:
            raise CollectionError('temporary_reverse_unavailable')
        local = 'http://127.0.0.1:' + str(reverse_port)
        base = local + '/' + receiver.key
        receiver.script = download_script(base)
        guard = session_guard(args.session_pid, args.session_start_ticks)
        # Disable history without the transfer key; callback attests the selected shell.
        preparation = (' if ' + guard + '; then HISTFILE=/dev/null; set +o history; history -c; '
                       + 'curl -fsS --connect-timeout 3 --max-time 6 '
                       + shlex.quote(local + '/' + receiver.ready_key + '/ready') + ' >/dev/null; fi')
        observe_session(client, args.session_pid, args.session_start_ticks)
        if not focused_termux(client):
            raise CollectionError('termux_focus_changed')
        type_line(client, preparation)
        if not receiver.ready.wait(10):
            raise CollectionError('selected_new_shell_not_attested')
        receipt['session_shell_attested'] = True
        for attempt in range(20):
            try:
                observe_session(client, args.session_pid, args.session_start_ticks)
                break
            except CollectionError as error:
                if str(error) != 'new_session_has_children' or attempt == 19:
                    raise
                time.sleep(.1)
        if not focused_termux(client):
            raise CollectionError('termux_focus_changed')
        temporary = '"$HOME/.soak-collect-' + secrets.token_hex(12) + '.sh"'
        command = (' if ' + guard + '; then HISTFILE=/dev/null; set +o history; umask 077; '
                   + 'curl -fsS --connect-timeout 5 --max-time 15 ' + shlex.quote(base + '/collect.sh')
                   + ' -o ' + temporary + ' && bash ' + temporary + '; rm -f -- ' + temporary + '; fi')
        type_line(client, command)
        if not receiver.complete.wait(args.timeout):
            raise CollectionError('incomplete_raw_transfer')
        receipt['complete_transfer'] = True
        receipt['validation'] = validate_records(destination, run)
        if not receipt['validation']['valid_final_collection']:
            raise CollectionError('final_records_not_verified')
    except Exception as error:
        failure = str(error) if isinstance(error, CollectionError) else type(error).__name__
    finally:
        receiver.close()
        if reverse_port is not None:
            try:
                client('reverse', '--remove', 'tcp:' + str(reverse_port))
            except Exception:
                receipt['cleanup_errors'].append('temporary_reverse_remove_failed')
        if server is not None:
            server.shutdown()
            server.server_close()
        if receipt['new_session_verified']:
            try:
                receipt['process_after'] = observe_session(client, args.session_pid, args.session_start_ticks,
                                                            require_fresh=False, require_empty=False)
            except Exception:
                receipt['cleanup_errors'].append('session_after_unverified')
        if receipt['session_shell_attested']:
            try:
                if focused_termux(client):
                    client('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            except Exception:
                receipt['cleanup_errors'].append('return_home_unverified')
        for name in sorted(ALLOWED):
            path = destination / name
            if path.is_file():
                receipt['sha256'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
                receipt['sizes'][name] = path.stat().st_size
        receipt['finished_at_utc'], receipt['error'] = utc(), failure
        receipt['collection_ok'] = failure is None and not receipt['cleanup_errors']
        with (destination / 'collection-receipt.json').open('x') as output:
            json.dump(receipt, output, ensure_ascii=False, indent=2)
            output.write('\n')
    if failure or receipt['cleanup_errors']:
        raise CollectionError((failure or 'collection_cleanup_unverified') + '; raw_receipt=' + str(destination / 'collection-receipt.json'))
    return destination, receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session-pid', type=int, required=True)
    parser.add_argument('--session-start-ticks', type=int, required=True)
    parser.add_argument('--destination', type=Path, help='must be a new directory; never overwrites evidence')
    parser.add_argument('--timeout', type=int, default=90)
    args = parser.parse_args(argv)
    if args.session_pid <= 1 or args.session_start_ticks <= 0 or not 1 <= args.timeout <= 180:
        parser.error('positive new-session identifiers and timeout 1..180 are required')
    destination, receipt = collect(args)
    print('Read-only final records recovered; stability verdict still requires offline review.')
    print('Receipt:', destination / 'collection-receipt.json')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, CollectionError) else type(error).__name__
        raise SystemExit('Log collection failed: ' + message) from None
