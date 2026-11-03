#!/usr/bin/env python3
"""Build, install and read-only verify home_pad on an authorized Xiaomi Pad 5 Pro."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import http.client
import json
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[4]
LAUNCHER = ROOT / 'work/minimal-launcher'
sys.path.insert(0, str(LAUNCHER))
sys.path.insert(0, str(ROOT / 'work/tablet-ha'))
from toolchain import resolve_tools
from verify_local_service import Adb, DiagnosticError, read_ha_config, read_private_json, verify


PACKAGE = 'com.alex.minimalhome'
APK = ROOT / 'outputs/极简搜索桌面.apk'


def host_run(arguments, timeout=300):
    result = subprocess.run(list(map(str, arguments)), cwd=ROOT,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        raise DiagnosticError('host_command_failed_' + Path(str(arguments[0])).name)
    return result.stdout.decode('utf-8', errors='replace')


@contextmanager
def operation_lock():
    directory = LAUNCHER / 'build'
    directory.mkdir(exist_ok=True)
    with (directory / 'home-pad-setup.lock').open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DiagnosticError('another_home_pad_setup_is_running') from None
        yield


def device_info(device):
    fields = {}
    for key in ('ro.product.model', 'ro.product.device', 'ro.build.version.sdk', 'ro.product.cpu.abilist'):
        fields[key] = device.run('shell', 'getprop', key)
    supported = fields['ro.product.device'] in ('elish', 'enuma')
    supported = supported or 'pad 5 pro' in fields['ro.product.model'].lower()
    return {'model': fields['ro.product.model'], 'supported_model': supported,
            'android_api': int(fields['ro.build.version.sdk']),
            'arm64': 'arm64-v8a' in fields['ro.product.cpu.abilist'].split(',')}


def process_snapshot(device):
    processes = []
    rows = device.run('shell', 'ps', '-A', '-o', 'PID,NAME').splitlines()[1:]
    for row in rows:
        columns = row.split()
        if len(columns) != 2 or not columns[0].isdigit() or columns[1] not in ('proot', 'python3'):
            continue
        pid = columns[0]
        try:
            command = device.run('shell', 'cat', '/proc/' + pid + '/cmdline')
            stat = device.run('shell', 'cat', '/proc/' + pid + '/stat')
        except DiagnosticError:
            raise DiagnosticError('process_identity_unavailable_before_upgrade') from None
        role = 'soak' if 'monitor.py' in command else 'ha' if 'homeassistant' in command else None
        if role is not None:
            tail = stat[stat.rfind(')') + 2:].split()
            if len(tail) < 20:
                raise DiagnosticError('process_identity_unavailable')
            processes.append({'pid': int(pid), 'start_ticks': tail[19], 'role': role})
    return sorted(processes, key=lambda item: item['pid'])


def voice_idle(pairing_path):
    if pairing_path is None:
        return
    pairing = read_private_json(pairing_path)
    endpoint = urlsplit(pairing['endpoint'])
    if (endpoint.scheme != 'https' or not endpoint.hostname or endpoint.username is not None
            or endpoint.password is not None or endpoint.path not in ('', '/')
            or endpoint.query or endpoint.fragment):
        raise DiagnosticError('invalid_voice_endpoint')
    cert = Path(pairing_path).parent / 'tls-cert.pem'
    # Pairing trusts one exact leaf, including locally CA-issued certificates.
    # Verify its pin, SAN and dates before any credential is sent.
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    if fingerprint != pairing['cert_sha256']:
        raise DiagnosticError('voice_certificate_file_changed')
    decoded = ssl._ssl._test_decode_cert(str(cert))
    ssl.match_hostname(decoded, endpoint.hostname)
    if not ssl.cert_time_to_seconds(decoded['notBefore']) <= time.time() <= ssl.cert_time_to_seconds(decoded['notAfter']):
        raise DiagnosticError('voice_certificate_expired_or_not_yet_valid')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = http.client.HTTPSConnection(endpoint.hostname, endpoint.port or 443,
                                            context=context, timeout=10)
    try:
        connection.connect()
        fingerprint = hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest()
        if fingerprint != pairing['cert_sha256']:
            raise DiagnosticError('voice_certificate_changed')
        token = pairing['token']
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', token):
            raise DiagnosticError('invalid_voice_pairing')
        connection.request('GET', '/health', headers={'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        data = response.read(65537)
        if response.status != 200 or len(data) > 65536:
            raise DiagnosticError('voice_health_unavailable')
        health = json.loads(data)
        sessions = health.get('sessions')
        if (health.get('service') != 'home-pad-voice' or not isinstance(sessions, dict)
                or sessions.get('active_sessions') != 0 or sessions.get('pending_sessions') != 0):
            raise DiagnosticError('voice_session_active_or_unknown')
    finally:
        connection.close()


def apk_details(path):
    java, _, tools = resolve_tools()
    manifest = host_run([tools / 'aapt2', 'dump', 'badging', path])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest, re.M)
    if package is None or package[1] != PACKAGE:
        raise DiagnosticError('unexpected_apk_package')
    signature = host_run([java / 'bin/java', '-jar', tools / 'lib/apksigner.jar',
                          'verify', '--print-certs', path])
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', signature)
    if len(certs) != 1:
        raise DiagnosticError('apk_signature_unavailable')
    return {'version': package[3], 'version_code': int(package[2]),
            'certificate': certs[0].lower(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def launcher_audio_idle(device):
    package = device.run('shell', 'dumpsys', 'package', PACKAGE)
    user_id = re.search(r'\buserId=(\d+)\b', package)
    if user_id is None:
        raise DiagnosticError('launcher_audio_identity_unknown')
    audio = device.run('shell', 'dumpsys', 'audio')
    for line in audio.splitlines():
        own_uid = re.search(r'\buid(?:/pid)?[:=]\s*' + user_id[1] + r'\b', line)
        if own_uid and re.search(r'\bstate:\s*started\b', line, re.I):
            raise DiagnosticError('launcher_audio_session_active')


def installed_apk(device):
    packages = device.run('shell', 'pm', 'list', 'packages', PACKAGE).splitlines()
    if 'package:' + PACKAGE not in packages:
        return None
    output = device.run('shell', 'pm', 'path', PACKAGE)
    if not output:
        return None
    paths = [line.removeprefix('package:') for line in output.splitlines()]
    if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9_./+=~-]+\.apk', paths[0]):
        raise DiagnosticError('installed_apk_path_invalid')
    return device.run('exec-out', 'cat', paths[0], binary=True)


def install_apk(device, path, config, check_only=False, pairing_path=None):
    # The build output can be replaced by another build while this run is checking HA.
    with tempfile.TemporaryDirectory(prefix='home-pad-install-') as directory:
        candidate = Path(directory) / 'candidate.apk'
        shutil.copyfile(path, candidate)
        return install_snapshot(device, candidate, config, check_only, pairing_path)


def install_snapshot(device, path, config, check_only=False, pairing_path=None):
    candidate = apk_details(path)
    info = device_info(device)
    if not info['supported_model'] or not info['arm64'] or info['android_api'] < 26:
        raise DiagnosticError('unsupported_tablet_check_compatibility')
    current = installed_apk(device)
    if current is not None and hashlib.sha256(current).hexdigest() == candidate['sha256']:
        return {'action': 'already_installed', 'installed': True, 'changed': False,
                'version': candidate['version'], 'apk_sha256': candidate['sha256'],
                'ha': verify(config, device)}
    if current is not None:
        with tempfile.TemporaryDirectory(prefix='home-pad-signature-') as directory:
            previous = Path(directory) / 'installed.apk'
            previous.write_bytes(current)
            installed = apk_details(previous)
        if installed['certificate'] != candidate['certificate']:
            raise DiagnosticError('signing_key_mismatch_restore_original_key')
        if installed['version_code'] > candidate['version_code']:
            raise DiagnosticError('apk_downgrade_refused')
    before_processes = process_snapshot(device)
    if any(item['role'] == 'soak' for item in before_processes):
        raise DiagnosticError('soak_recorder_active_preserve_logs_before_upgrade')
    if not any(item['role'] == 'ha' for item in before_processes):
        raise DiagnosticError('tablet_local_ha_process_not_observed')
    before = verify(config, device)
    if current is not None:
        launcher_audio_idle(device)
    voice_idle(pairing_path)
    if check_only:
        return {'action': 'install_plan', 'changed': False, 'version': candidate['version'],
                'signing_compatible': True, 'ha': before}
    if process_snapshot(device) != before_processes:
        raise DiagnosticError('processes_changed_before_install')
    output = device.run('install', '-r', str(path), timeout=120)
    if not re.search(r'^Success\s*$', output, re.M):
        raise DiagnosticError('install_result_unconfirmed_recheck_before_retry')
    actual = installed_apk(device)
    if actual is None or hashlib.sha256(actual).hexdigest() != candidate['sha256']:
        raise DiagnosticError('installed_apk_does_not_match')
    after = verify(config, device)
    if process_snapshot(device) != before_processes:
        raise DiagnosticError('ha_process_identity_changed_after_install')
    if (before['plug_state'], before['last_changed']) != (after['plug_state'], after['last_changed']):
        raise DiagnosticError('ha_report_changed_after_install_review_without_switching')
    return {'action': 'installed', 'installed': True, 'changed': True,
            'version': candidate['version'], 'apk_sha256': candidate['sha256'],
            'process_identity_preserved': True, 'ha_report_preserved': True, 'ha': after}


def prepare():
    resolve_tools()
    host_run([sys.executable, LAUNCHER / 'prepare_wake_dependencies.py'], timeout=600)
    return {'action': 'prepared', 'wake_dependencies': 'pinned and checksum-verified'}


def build(check_only=False):
    arguments = [sys.executable, LAUNCHER / 'build.py']
    if check_only:
        arguments.append('--check-only')
    host_run(arguments)
    return {'action': 'built', 'signed': not check_only, 'installed': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('doctor', 'prepare', 'build', 'install', 'verify', 'setup'))
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    parser.add_argument('--config', type=Path,
                        help='Owned mode-0600 HA config containing token and entity_id; never paste credentials')
    parser.add_argument('--apk', type=Path, help='Install this signed APK; setup skips rebuilding when supplied')
    parser.add_argument('--voice-pairing', type=Path, help='Private broker pairing file, with tls-cert.pem alongside')
    parser.add_argument('--check-only', action='store_true', help='Inspect installation or build an isolated unsigned APK')
    args = parser.parse_args(argv)
    if args.action in ('verify', 'install', 'setup') and args.config is None:
        parser.error('--config is required for read-only HA acceptance')
    if args.check_only and args.action == 'prepare':
        parser.error('prepare writes dependency files; use doctor for inspection')
    try:
        config = read_ha_config(args.config) if args.config else None
        if args.action == 'prepare':
            with operation_lock():
                result = prepare()
        elif args.action == 'build':
            with operation_lock():
                result = build(args.check_only)
        else:
            device = Adb(args.adb, args.serial)
            if args.action == 'doctor':
                try:
                    resolve_tools()
                    toolchain = 'ready'
                except RuntimeError:
                    toolchain = 'needs_jdk17_android33_build_tools35'
                result = {'action': 'doctor', 'device': device_info(device), 'toolchain': toolchain}
                if config:
                    result['ha'] = verify(config, device)
            elif args.action == 'verify':
                result = {'action': 'verified', 'ha': verify(config, device)}
            else:
                pairing = args.voice_pairing
                default_pairing = ROOT / 'work/voice-service/private/tablet-voice-setup.json'
                if pairing is None and default_pairing.is_file():
                    pairing = default_pairing
                with operation_lock():
                    if args.action == 'setup' and not args.check_only and args.apk is None:
                        identity = LAUNCHER / 'signing'
                        if installed_apk(device) is not None and not all(
                                (identity / name).is_file() for name in ('launcher.p12', 'password.txt')):
                            raise DiagnosticError('existing_install_requires_original_local_signing_identity')
                        prepare()
                        build()
                    result = install_apk(device, (args.apk or APK).resolve(), config,
                                         args.check_only, pairing)
        print(json.dumps({'ok': True, **result, 'device_control_requests_sent': 0},
                         ensure_ascii=False, indent=2))
        return 0
    except DiagnosticError as error:
        print(json.dumps({'ok': False, 'error': str(error)}, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'ok': False, 'error': type(error).__name__}, ensure_ascii=False))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
