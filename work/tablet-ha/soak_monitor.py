#!/usr/bin/env python3
"""Bounded, read-only tablet HA soak recorder. No device service calls exist here.

State reads are HA's cached entity reports, not independent measurements of load power.
Run inside the tablet runtime with --config /config/.soak-monitor.json and
--output /config/soak-monitor.jsonl. State and summary use the same stem.
"""
import argparse
import fcntl
from http.client import HTTPException
import json
import os
import re
import signal
import socket
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

BASE_URL = 'http://127.0.0.1:8123'
STATES = {'on', 'off', 'unknown', 'unavailable'}
EVIDENCE_LIMIT = 'GET reads cached HA reports; it does not measure physical power or force a device refresh.'


def utc(epoch=None):
    return datetime.fromtimestamp(time.time() if epoch is None else epoch, timezone.utc).isoformat()


def private_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Expected regular private JSON file')
    path.chmod(0o600)
    with path.open(encoding='utf-8') as handle:
        raw = handle.read(65537)
        if len(raw) > 65536:
            raise ValueError('Private JSON file exceeds limit')
        return json.loads(raw)


def write_json(path, data):
    path = Path(path)
    fd, temp = tempfile.mkstemp(prefix='.' + path.name + '.', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            os.fchmod(handle.fileno(), 0o600)
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write('\n')
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def config_from_file(path):
    data = private_json(path)
    if (not isinstance(data, dict) or data.get('base_url') != BASE_URL
            or not isinstance(data.get('token'), str) or not data['token']
            or '\r' in data['token'] or '\n' in data['token']
            or not isinstance(data.get('entity_id'), str)
            or not re.fullmatch(r'(?:switch|light)\.[a-z0-9_]+', data['entity_id'])):
        raise ValueError('Invalid private monitor configuration')
    return data


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class Reader:
    def __init__(self, config, opener=None):
        self.token = config['token']
        self.entity_path = '/api/states/' + config['entity_id']
        self.opener = opener if opener is not None else build_opener(ProxyHandler({}), NoRedirect())

    def get(self, path):
        if path not in ('/api/', self.entity_path):
            raise ValueError('Endpoint not allowed')
        started = time.monotonic()
        result = {'ok': False, 'http_status': None, 'error': None, 'data': None}
        request = Request(BASE_URL + path, headers={'Authorization': 'Bearer ' + self.token}, method='GET')
        try:
            with self.opener.open(request, timeout=5) as response:
                result['http_status'] = response.status
                raw = response.read(65537)
                if len(raw) > 65536:
                    raise ValueError('Response too large')
                result['data'] = json.loads(raw)
                if not isinstance(result['data'], dict):
                    raise ValueError('Expected object')
                result['ok'] = True
        except HTTPError as error:
            result['http_status'] = error.code
            result['error'] = 'redirect_refused' if 300 <= error.code < 400 else 'http_error'
        except (socket.timeout, TimeoutError):
            result['error'] = 'timeout'
        except URLError as error:
            result['error'] = 'timeout' if isinstance(error.reason, (socket.timeout, TimeoutError)) else 'connection_error'
        except (ValueError, UnicodeError):
            result['error'] = 'invalid_response'
        except HTTPException:
            result['error'] = 'incomplete_http_response'
        except OSError:
            result['error'] = 'io_error'
        finally:
            result['latency_ms'] = round((time.monotonic() - started) * 1000, 3)
        return result


def safe_timestamp(value):
    if not isinstance(value, str) or len(value) > 40:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        return parsed.isoformat() if parsed.tzinfo is not None else None
    except ValueError:
        return None


def optional_text(path):
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return None


def optional_number(path, low, high, scale=1):
    try:
        value = int(optional_text(path))
        return (value if scale == 1 else value / scale) if low <= value <= high else None
    except (TypeError, ValueError):
        return None


def proc_stat(path):
    try:
        fields = Path(path).read_text().rsplit(')', 1)[1].split()
        return int(fields[1]), int(fields[19])  # PPID and Linux starttime ticks
    except (OSError, ValueError, IndexError):
        return None


def device_metrics(proc=Path('/proc'), sysfs=Path('/sys/class/power_supply')):
    boot = optional_text(proc / 'sys/kernel/random/boot_id')
    if not boot or not re.fullmatch(r'[0-9a-f-]{36}', boot):
        boot = None
    result = {'boot_id': boot, 'ha_python_pid': None, 'ha_python_start_ticks': None,
              'proot_pid': None, 'proot_start_ticks': None,
              'battery_percent': optional_number(sysfs / 'battery/capacity', 0, 100),
              'battery_temperature_c': optional_number(sysfs / 'battery/temp', -500, 1500, 10),
              'usb_power_online': optional_number(sysfs / 'usb/online', 0, 1)}
    matches = []
    try:
        for entry in proc.iterdir():
            if not entry.name.isdigit():
                continue
            try:
                args = (entry / 'cmdline').read_bytes()[:8192].split(b'\0')
                # proot's own argv also includes the complete child command. Count only
                # the actual Python executable, then resolve its proot parent separately.
                executable = args[0].rsplit(b'/', 1)[-1] if args else b''
                if (re.fullmatch(rb'python(?:[0-9]+(?:\.[0-9]+)*)?', executable)
                        and any(args[i:i + 2] == [b'-m', b'homeassistant'] for i in range(len(args) - 1))):
                    stats = proc_stat(entry / 'stat')
                    if stats:
                        matches.append((int(entry.name), stats))
            except OSError:
                continue
    except OSError:
        pass
    if len(matches) == 1:
        pid, (parent, ticks) = matches[0]
        result.update(ha_python_pid=pid, ha_python_start_ticks=ticks)
        try:
            args = (proc / str(parent) / 'cmdline').read_bytes().split(b'\0')
            stats = proc_stat(proc / str(parent) / 'stat')
            if args and Path(os.fsdecode(args[0])).name == 'proot' and stats:
                result.update(proot_pid=parent, proot_start_ticks=stats[1])
        except (OSError, ValueError):
            pass
    return result


def sample(reader, run_id):
    record = {'run_id': run_id, 'utc': utc(), 'epoch_s': time.time(), 'monotonic_s': time.monotonic()}
    api = reader.get('/api/')
    entity = reader.get(reader.entity_path)
    api_ok = api['ok'] and api['data'].get('message') == 'API running.'
    data = entity['data'] if entity['ok'] else {}
    state = data.get('state')
    state_valid = isinstance(state, str) and state in STATES
    record.update(ha_reachable=any(r['http_status'] is not None for r in (api, entity)),
                  api_ok=api_ok, entity_read_ok=entity['ok'] and state_valid,
                  state=state if state_valid else ('unexpected' if entity['ok'] else None),
                  last_changed=safe_timestamp(data.get('last_changed')),
                  last_updated=safe_timestamp(data.get('last_updated')),
                  api_http_status=api['http_status'], state_http_status=entity['http_status'],
                  api_latency_ms=api['latency_ms'], state_latency_ms=entity['latency_ms'],
                  api_error=api['error'] or (None if api_ok else 'unexpected_response'),
                  state_error=entity['error'] or (None if state_valid else 'unexpected_response'))
    record.update(device_metrics())
    return record


def load_run(path, duration, interval, now=None):
    now = time.time() if now is None else now
    if path.exists():
        state = private_json(path)
        if (not isinstance(state, dict) or state.get('schema') != 1
                or not re.fullmatch(r'[0-9a-f-]{36}', str(state.get('run_id', '')))
                or state.get('duration_seconds') != duration or state.get('interval_seconds') != interval
                or not isinstance(state.get('started_epoch'), (int, float))
                or state.get('deadline_epoch') != state['started_epoch'] + duration):
            raise ValueError('Run metadata mismatch; use a new output path for a new run')
        return state
    state = {'schema': 1, 'run_id': str(uuid.uuid4()), 'started_utc': utc(now),
             'started_epoch': now, 'deadline_epoch': now + duration,
             'deadline_utc': utc(now + duration), 'duration_seconds': duration,
             'interval_seconds': interval, 'evidence_limit': EVIDENCE_LIMIT}
    write_json(path, state)
    return state


def append_sample(path, record):
    if path.is_symlink():
        raise ValueError('Log must not be a symlink')
    with path.open('a+', encoding='utf-8') as handle:
        os.fchmod(handle.fileno(), 0o600)
        # A crash may leave a truncated final row. Preserve it as a separate invalid row.
        handle.seek(0, os.SEEK_END)
        if handle.tell():
            handle.seek(handle.tell() - 1)
            if handle.read(1) != '\n':
                handle.seek(0, os.SEEK_END)
                handle.write('\n')
        handle.seek(0, os.SEEK_END)
        handle.write(json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n')
        handle.flush()
        os.fsync(handle.fileno())


def summarize(path, run, status):
    rows, malformed = [], 0
    if path.exists():
        for line in path.read_text().splitlines():
            try:
                item = json.loads(line)
                if item.get('run_id') == run['run_id']:
                    if (not isinstance(item.get('epoch_s'), (int, float))
                            or not isinstance(item.get('monotonic_s'), (int, float))
                            or safe_timestamp(item.get('utc')) is None):
                        raise ValueError('Malformed sample')
                    rows.append(item)
            except (ValueError, AttributeError):
                malformed += 1
    gaps, anomalies = [], 0
    for previous, current in zip(rows, rows[1:]):
        gap = current['epoch_s'] - previous['epoch_s']
        gaps.append(gap)
        if gap < 0 or (current.get('boot_id') and current.get('boot_id') == previous.get('boot_id')
                       and abs(gap - current['monotonic_s'] + previous['monotonic_s']) > 5):
            anomalies += 1
    identities = [(r.get('boot_id'), r.get('proot_pid'), r.get('proot_start_ticks')) for r in rows]
    known = [i for i in identities if i[0] is not None and i[1] is not None and i[2] is not None]
    covered = max(0, rows[-1]['epoch_s'] - rows[0]['epoch_s']) if len(rows) >= 2 else 0
    expected_gap = run['interval_seconds'] * 1.5 + 10
    counters = {s: sum(r.get('state') == s for r in rows) for s in sorted(STATES | {'unexpected'})}
    return {**run, 'status': status, 'summary_utc': utc(), 'samples': len(rows),
            'first_sample_utc': rows[0]['utc'] if rows else None,
            'last_sample_utc': rows[-1]['utc'] if rows else None,
            'covered_seconds': covered, 'max_sample_gap_seconds': max(gaps, default=0),
            'sample_gaps_over_tolerance': sum(g > expected_gap for g in gaps),
            'wall_clock_anomalies': anomalies, 'malformed_log_rows': malformed,
            'api_success_samples': sum(r.get('api_ok') is True for r in rows),
            'entity_read_success_samples': sum(r.get('entity_read_ok') is True for r in rows),
            'states': counters, 'proot_identity_changes_observed': sum(a != b for a, b in zip(known, known[1:])),
            'coverage_complete': (status == 'window_elapsed' and len(rows) >= 2
                                  and rows[0]['epoch_s'] <= run['started_epoch'] + expected_gap
                                  and rows[-1]['epoch_s'] >= run['deadline_epoch'] - expected_gap
                                  and covered >= run['duration_seconds'] - expected_gap
                                  and not malformed and not anomalies and all(0 <= g <= expected_gap for g in gaps)),
            'physical_usb_unplug_verified': False, 'power_control_requests_sent': 0}


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--summarize', action='store_true',
                        help='Print an offline summary from JSONL + sibling state JSON; no config or API access')
    parser.add_argument('--duration-seconds', type=int, default=86400)
    parser.add_argument('--interval-seconds', type=int, default=60)
    args = parser.parse_args(argv)
    if args.summarize:
        run = private_json(args.output.with_suffix('.state.json'))
        status = 'window_elapsed' if time.time() >= run['deadline_epoch'] else 'snapshot'
        print(json.dumps(summarize(args.output, run, status), ensure_ascii=False, indent=2))
        return
    if args.config is None:
        parser.error('--config is required unless --summarize is set')
    if args.duration_seconds <= 0 or args.interval_seconds <= 0:
        raise ValueError('Duration and interval must be positive')
    args.output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = config_from_file(args.config)
    with args.output.with_suffix('.lock').open('a') as lock:
        os.fchmod(lock.fileno(), 0o600)
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        run = load_run(args.output.with_suffix('.state.json'), args.duration_seconds, args.interval_seconds)
        reader = Reader(config)
        stopped = threading.Event()
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, lambda *_: stopped.set())
        summary_path = args.output.with_suffix('.summary.json')
        status = 'interrupted'
        try:
            # An already-expired run remains expired; a restart never grants another 24h.
            if time.time() >= run['deadline_epoch']:
                status = 'window_elapsed'
            else:
                while not stopped.is_set():
                    tick = time.monotonic()
                    append_sample(args.output, sample(reader, run['run_id']))
                    if time.time() >= run['deadline_epoch']:
                        status = 'window_elapsed'
                        break
                    write_json(summary_path, summarize(args.output, run, 'running'))
                    wait = min(max(0, args.interval_seconds - (time.monotonic() - tick)),
                               max(0, run['deadline_epoch'] - time.time()))
                    stopped.wait(wait)
        finally:
            write_json(summary_path, summarize(args.output, run, status))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print exception messages: HTTP errors/configuration may contain secrets.
        raise SystemExit('Monitor stopped: ' + type(error).__name__) from None
