#!/usr/bin/env python3
"""Read tablet-local HA with two GET requests and an owned diagnostic forward."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / 'work/homeassistant/private/tablet-config.json'


class DiagnosticError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise DiagnosticError('ha_redirect_refused')


def read_private_json(path):
    path = Path(path).expanduser().absolute()
    if path.is_symlink() or not path.is_file():
        raise DiagnosticError('private_config_missing_or_symlink')
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_size > 65536:
        raise DiagnosticError('private_config_requires_owned_mode_0600_file')
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise DiagnosticError('private_config_invalid')
    return value


def read_ha_config(path):
    config = read_private_json(path)
    if not isinstance(config.get('token'), str) or not re.fullmatch(r'[A-Za-z0-9._-]{16,4096}', config['token']):
        raise DiagnosticError('ha_token_invalid')
    if not isinstance(config.get('entity_id'), str) or not re.fullmatch(r'(switch|light)\.[a-z0-9_]+', config['entity_id']):
        raise DiagnosticError('ha_entity_invalid')
    return config


class Adb:
    def __init__(self, executable=None, serial=None):
        local = ROOT / 'work/android-tools/platform-tools/adb'
        selected = executable or (str(local) if local.is_file() else shutil.which('adb'))
        if not selected:
            raise DiagnosticError('adb_not_found')
        self.executable = str(selected)
        result = subprocess.run([self.executable, 'devices'], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise DiagnosticError('adb_inventory_failed')
        devices = [line.split()[0] for line in result.stdout.splitlines()
                   if len(line.split()) == 2 and line.split()[1] == 'device']
        serial = serial or os.environ.get('ANDROID_SERIAL')
        if serial is None:
            if len(devices) != 1:
                raise DiagnosticError('select_exactly_one_authorized_device_with_serial')
            serial = devices[0]
        if serial not in devices or not re.fullmatch(r'[A-Za-z0-9._:-]+', serial):
            raise DiagnosticError('selected_device_unavailable')
        self.serial = serial

    def run(self, *args, timeout=30, binary=False):
        result = subprocess.run([self.executable, '-s', self.serial, *map(str, args)],
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise DiagnosticError('adb_operation_failed')
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def verify(config, device):
    reverse_empty = not device.run('reverse', '--list')
    port = device.run('forward', 'tcp:0', 'tcp:8123')
    if not re.fullmatch(r'\d{1,5}', port) or not 1 <= int(port) <= 65535:
        raise DiagnosticError('invalid_diagnostic_port')
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())

        def get(path):
            request = Request('http://127.0.0.1:' + port + path,
                          headers={'Authorization': 'Bearer ' + config['token']},
                          method='GET')
            with opener.open(request, timeout=15) as response:
                if response.status != 200:
                    raise DiagnosticError('ha_get_failed')
                data = response.read(2 * 1024 * 1024 + 1)
                if len(data) > 2 * 1024 * 1024:
                    raise DiagnosticError('ha_response_too_large')
                return json.loads(data)

        server = get('/api/config')
        state = get('/api/states/' + config['entity_id'])
        if not isinstance(server, dict) or not isinstance(state, dict) or state.get('entity_id') != config['entity_id']:
            raise DiagnosticError('ha_response_invalid')
        return {
            'checked_at_utc': datetime.now(timezone.utc).isoformat(),
            'target': 'tablet localhost:8123',
            'version': server.get('version'),
            'server_state': server.get('state'),
            'xiaomi_home_loaded': 'xiaomi_home' in server.get('components', []),
            'plug_state': state.get('state'),
            'last_changed': state.get('last_changed'),
            'last_updated': state.get('last_updated'),
            'adb_reverse_empty': reverse_empty,
            'requests': 'GET only',
        }
    finally:
        device.run('forward', '--remove', 'tcp:' + port)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    args = parser.parse_args()
    try:
        config = read_ha_config(args.config)
        result = verify(config, Adb(args.adb, args.serial))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except DiagnosticError as error:
        print(json.dumps({'ok': False, 'error': str(error)}))
    except Exception as error:
        print(json.dumps({'ok': False, 'error': type(error).__name__}))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
