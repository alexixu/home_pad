#!/usr/bin/env python3
"""Manage only the current user's Home Pad voice LaunchAgent.

Preview: python3 install_service.py --check-only
Install: python3 install_service.py install [--config /absolute/private/config.json]
Inspect/stop: python3 install_service.py status|stop

This installer never reads configuration contents or Codex authentication files.
The service uses the existing user's normal Codex login. It requires a logged-in
macOS GUI session and an awake, network-connected Mac. Tablet-local HA is separate.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import sys
import tempfile
import time

LABEL = 'com.alex.homepad.voice'
CODEX = Path('/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex')
SYSTEM_PATH = '/usr/bin:/bin:/usr/sbin:/sbin'
MARKER = b'<!-- Managed by home_pad/work/voice-service/install_service.py; schema=1 -->'
LAUNCHCTL = '/bin/launchctl'
COMMAND_TIMEOUT = 20
UNLOAD_TIMEOUT = 20
POLL_INTERVAL = 0.25


class ServiceError(Exception):
    """An intentionally credential-free error code."""


@dataclass(frozen=True)
class Plan:
    home: Path
    service_dir: Path
    config: Path
    python: Path
    codex: Path
    host: str = '0.0.0.0'
    port: int = 18443
    uid: int = 0

    @property
    def plist(self):
        return self.home / 'Library' / 'LaunchAgents' / (LABEL + '.plist')

    @property
    def logs(self):
        return self.home / 'Library' / 'Logs' / 'HomePadVoice'

    @property
    def target(self):
        return 'gui/' + str(self.uid) + '/' + LABEL

    @property
    def server(self):
        return self.service_dir / 'server.py'

    def document(self):
        return {
            'Label': LABEL,
            'ProgramArguments': [str(self.python), '-u', str(self.server),
                                 '--config', str(self.config), '--host', self.host,
                                 '--port', str(self.port)],
            'WorkingDirectory': str(self.service_dir),
            # Do not propagate the installer's environment or put credentials here.
            'EnvironmentVariables': {'PATH': str(self.codex.parent) + ':' + SYSTEM_PATH},
            'RunAtLoad': True,
            'KeepAlive': True,
            'ThrottleInterval': 15,
            'ExitTimeOut': 15,
            'ProcessType': 'Background',
            'Umask': 0o077,
            'StandardOutPath': str(self.logs / 'stdout.log'),
            'StandardErrorPath': str(self.logs / 'stderr.log'),
        }

    def encoded(self):
        data = plistlib.dumps(self.document(), fmt=plistlib.FMT_XML, sort_keys=False)
        first, rest = data.split(b'\n', 1)
        return first + b'\n' + MARKER + b'\n' + rest

    def summary(self, action):
        return {
            'action': action, 'check_only': True, 'label': LABEL,
            'scope': 'current-user GUI LaunchAgent',
            'command': ['<current Python>', '-u', '<project>/work/voice-service/server.py',
                        '--config', '<existing private configuration>', '--host', self.host,
                        '--port', str(self.port)],
            'environment_keys': ['PATH'],
            'path_scope': 'official bundled Codex directory and macOS system directories only',
            'credentials_read': False, 'credentials_embedded': False,
            'keep_alive': True, 'restart_throttle_seconds': 15,
            'shutdown_grace_seconds': 15,
            'runtime_requires': ['macOS user GUI login', 'Mac awake', 'network access',
                                 'existing normal Codex login'],
            'tablet_ha_modified': False,
        }


def private_stat(path, uid, kind='file'):
    """Check metadata without reading contents or following a final symlink."""
    try:
        info = path.lstat()
    except OSError:
        raise ServiceError('required_private_' + kind + '_unavailable') from None
    expected = stat.S_ISREG if kind == 'file' else stat.S_ISDIR
    if not expected(info.st_mode) or info.st_uid != uid or info.st_mode & 0o077:
        raise ServiceError('required_private_' + kind + '_permissions_invalid')
    return info


def existing_plist(plan):
    """Only this installer's own private plist may be replaced or stopped."""
    if not os.path.lexists(plan.plist):
        return None
    info = private_stat(plan.plist, plan.uid)
    if info.st_size > 65536:
        raise ServiceError('existing_plist_not_managed')
    try:
        fd = os.open(plan.plist, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as handle:
            actual = os.fstat(handle.fileno())
            if (actual.st_ino, actual.st_dev) != (info.st_ino, info.st_dev):
                raise ServiceError('existing_plist_changed')
            data = handle.read(65537)
        parsed = plistlib.loads(data)
    except (OSError, ValueError, plistlib.InvalidFileException):
        raise ServiceError('existing_plist_not_managed') from None
    args = parsed.get('ProgramArguments') if isinstance(parsed, dict) else None
    if (not isinstance(parsed, dict)
            or MARKER not in data.split(b'<plist', 1)[0]
            or parsed.get('Label') != LABEL
            or not isinstance(args, list) or len(args) < 3
            or args[1:3] != ['-u', str(plan.server)]
            or parsed.get('WorkingDirectory') != str(plan.service_dir)):
        raise ServiceError('existing_plist_not_managed')
    return data


def validate_install(plan):
    if not 1 <= plan.port <= 65535:
        raise ServiceError('invalid_port')
    try:
        # The broker's ThreadingHTTPServer uses AF_INET. Reject IPv6 before
        # touching an installed job rather than creating a restart loop.
        ipaddress.IPv4Address(plan.host)
    except ValueError:
        raise ServiceError('host_must_be_numeric_ipv4') from None
    private_stat(plan.config, plan.uid)
    if not plan.server.is_file() or not plan.python.is_file() or not os.access(plan.python, os.X_OK):
        raise ServiceError('server_or_python_unavailable')
    if not plan.codex.is_file() or not os.access(plan.codex, os.X_OK):
        raise ServiceError('official_codex_unavailable')
    if plan.codex != CODEX:
        raise ServiceError('official_codex_path_required')
    existing_plist(plan)


def launchctl(*args, timeout=COMMAND_TIMEOUT):
    try:
        return subprocess.run([LAUNCHCTL, *args], stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise ServiceError('launchctl_timeout') from None
    except OSError:
        raise ServiceError('launchctl_unavailable') from None


def state(plan, runner=launchctl, *, timeout=None):
    result = (runner('print', plan.target) if timeout is None
              else runner('print', plan.target, timeout=timeout))
    if result.returncode:
        # Missing jobs have a specific exit status. Other failures must not be
        # treated as absence; no retry/bootout may act on an uncertain target.
        if result.returncode in (3, 113):
            return {'loaded': False}
        raise ServiceError('launchctl_status_failed')
    origin = re.search(r'^\s*path = (.+)$', result.stdout, re.MULTILINE)
    owned = bool(origin and origin.group(1).strip() == str(plan.plist))
    value = {'loaded': True, 'managed_origin': owned}
    for name, pattern in (
            ('state', r'^\s*state = ([a-zA-Z -]+)$'),
            ('pid', r'^\s*pid = ([0-9]+)$'),
            ('last_exit_code', r'^\s*last exit code = (-?[0-9]+)$')):
        found = re.search(pattern, result.stdout, re.MULTILINE)
        if found:
            value[name] = found.group(1).strip() if name == 'state' else int(found.group(1))
    return value


def check_origin(loaded, prior):
    if loaded['loaded'] and (prior is None or not loaded.get('managed_origin')):
        raise ServiceError('loaded_job_not_managed')


def unload_owned(plan, prior, runner=launchctl):
    """bootout is asynchronous; confirm absence before writing or bootstrapping.

    The command and all subsequent queries share one 20-second deadline. Each
    query is bounded by its remaining budget, and uncertain/foreign state fails
    immediately. A successful bootout or an exiting PID alone is not evidence
    that launchd has removed the service label.
    """
    deadline = time.monotonic() + UNLOAD_TIMEOUT
    if runner('bootout', plan.target, timeout=UNLOAD_TIMEOUT).returncode:
        raise ServiceError('launchctl_stop_failed')
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ServiceError('launchctl_unload_timeout')
        current = state(plan, runner, timeout=remaining)
        check_origin(current, prior)
        if not current['loaded']:
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ServiceError('launchctl_unload_timeout')
        time.sleep(min(POLL_INTERVAL, remaining))


def prepare_storage(plan):
    # Do not chmod or replace existing directories or logs owned by another job.
    for parent in (plan.plist.parent, plan.logs.parent):
        parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != plan.uid or info.st_mode & 0o022:
            raise ServiceError('service_directory_not_owned')
    plan.logs.mkdir(mode=0o700, exist_ok=True)
    private_stat(plan.logs, plan.uid, 'directory')
    for name in ('stdout.log', 'stderr.log'):
        log = plan.logs / name
        if os.path.lexists(log):
            private_stat(log, plan.uid)
        else:
            try:
                fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                os.close(fd)
            except OSError:
                raise ServiceError('private_log_creation_failed') from None


def write_plist(plan, data):
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=plan.plist.parent, prefix='.homepad-voice-', delete=False) as handle:
            name = handle.name
            os.fchmod(handle.fileno(), 0o600)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        existing_plist(plan)  # Recheck ownership immediately before atomic replace.
        os.replace(name, plan.plist)
    finally:
        if name and os.path.exists(name):
            os.unlink(name)


def execute(plan, action, *, check_only=False, runner=launchctl):
    prior = existing_plist(plan)
    if action == 'install':
        validate_install(plan)
    if check_only:
        value = plan.summary(action)
        value['managed_plist_exists'] = prior is not None
        return value
    if sys.platform != 'darwin' or plan.uid == 0 or plan.uid != os.getuid():
        raise ServiceError('current_non_root_macos_user_required')
    loaded = state(plan, runner)
    check_origin(loaded, prior)
    if action == 'status':
        return {'label': LABEL, 'managed_plist_exists': prior is not None, **loaded}
    if action == 'stop':
        if prior is not None and runner('disable', plan.target).returncode:
            raise ServiceError('launchctl_disable_failed')
        if loaded['loaded']:
            unload_owned(plan, prior, runner)
        return {'label': LABEL, 'loaded': False, 'plist_retained': prior is not None,
                'next_login_starts_service': False}
    if action != 'install':
        raise ServiceError('explicit_action_required')
    prepare_storage(plan)
    if loaded['loaded']:
        unload_owned(plan, prior, runner)
    write_plist(plan, plan.encoded())
    # enable reverses an earlier user disable only for this verified owned label.
    if runner('enable', plan.target).returncode:
        raise ServiceError('launchctl_enable_failed')
    if runner('bootstrap', 'gui/' + str(plan.uid), str(plan.plist)).returncode:
        raise ServiceError('launchctl_bootstrap_failed')
    after = state(plan, runner)
    if not after['loaded'] or not after.get('managed_origin'):
        raise ServiceError('launchctl_install_not_confirmed')
    return {'label': LABEL, 'installed': True, **after,
            'voice_connection_verified': False, 'tablet_ha_modified': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('action', nargs='?', choices=('install', 'status', 'stop'))
    parser.add_argument('--check-only', action='store_true', help='Validate metadata and print a redacted plan; no writes or launchctl')
    service_dir = Path(__file__).resolve().parent
    parser.add_argument('--config', type=Path, default=service_dir / 'private' / 'config.json')
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=18443)
    args = parser.parse_args(argv)
    if not args.action and not args.check_only:
        parser.error('Choose install, status or stop; use --check-only to preview installation.')
    plan = Plan(Path.home(), service_dir, args.config.expanduser().absolute(),
                Path(sys.executable).absolute(), CODEX, args.host, args.port, os.getuid())
    try:
        result = execute(plan, args.action or 'install', check_only=args.check_only)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except ServiceError as error:
        print(json.dumps({'ok': False, 'error': str(error)}), file=sys.stderr)
        return 1
    except OSError:
        print(json.dumps({'ok': False, 'error': 'service_file_operation_failed'}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
