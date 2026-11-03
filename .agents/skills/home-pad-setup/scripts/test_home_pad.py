"""Inert regressions for deployment guards, cleanup and credential containment."""
from contextlib import ExitStack, nullcontext, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import Request

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_pad as setup
import verify_local_service as diagnostic
import toolchain


CONFIG = {'token': 'test_private_token_never_printed_12345', 'entity_id': 'switch.test_plug'}
STATE = {'checked_at_utc': 'test', 'plug_state': 'on', 'last_changed': 'unchanged'}
DETAILS = {'sha256': hashlib.sha256(b'new').hexdigest(), 'certificate': 'a' * 64,
           'version': '2.2', 'version_code': 16}


class Device:
    def __init__(self):
        self.calls = []

    def run(self, *args, **kwargs):
        self.calls.append(args)
        if args == ('forward', 'tcp:0', 'tcp:8123'):
            return '45678'
        if args == ('reverse', '--list'):
            return ''
        if args[:2] == ('install', '-r'):
            return 'Success'
        return ''


class Response(io.BytesIO):
    status = 200


class VerifyTests(unittest.TestCase):
    def test_two_gets_and_owned_forward_cleanup(self):
        device = Device()
        requests = []
        responses = [{'version': '2026.10.4', 'state': 'RUNNING', 'components': ['xiaomi_home']},
                     {'entity_id': CONFIG['entity_id'], 'state': 'on', 'last_changed': 'same'}]

        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response(json.dumps(responses.pop(0)).encode())

        with patch.object(diagnostic, 'build_opener', return_value=Opener()):
            result = diagnostic.verify(CONFIG, device)
        self.assertEqual([request.get_method() for request in requests], ['GET', 'GET'])
        self.assertEqual([request.full_url for request in requests],
                         ['http://127.0.0.1:45678/api/config',
                          'http://127.0.0.1:45678/api/states/switch.test_plug'])
        self.assertEqual(device.calls[-1], ('forward', '--remove', 'tcp:45678'))
        self.assertEqual(result['plug_state'], 'on')
        self.assertNotIn(CONFIG['token'], json.dumps(result))
        self.assertNotIn(CONFIG['entity_id'], json.dumps(result))

    def test_cleanup_after_failed_http_read(self):
        device = Device()
        with patch.object(diagnostic, 'build_opener') as opener:
            opener.return_value.open.side_effect = OSError('private server detail')
            with self.assertRaises(OSError):
                diagnostic.verify(CONFIG, device)
        self.assertEqual(device.calls[-1], ('forward', '--remove', 'tcp:45678'))

    def test_redirects_never_forward_authorization(self):
        with self.assertRaises(diagnostic.DiagnosticError):
            diagnostic.NoRedirect().redirect_request(Request('http://127.0.0.1/'), None,
                                                      302, '', {}, 'https://elsewhere.invalid/')

    def test_config_permissions_and_token_not_in_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'config.json'
            path.write_text(json.dumps(CONFIG))
            path.chmod(0o644)
            with self.assertRaises(diagnostic.DiagnosticError) as caught:
                diagnostic.read_ha_config(path)
            self.assertNotIn(CONFIG['token'], str(caught.exception))
            path.chmod(0o600)
            self.assertEqual(diagnostic.read_ha_config(path), CONFIG)
            link = Path(folder) / 'link.json'
            link.symlink_to(path)
            with self.assertRaises(diagnostic.DiagnosticError):
                diagnostic.read_ha_config(link)

    def test_entity_rejects_service_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'config.json'
            path.write_text(json.dumps({**CONFIG, 'entity_id': '../services/switch/turn_off'}))
            path.chmod(0o600)
            with self.assertRaises(diagnostic.DiagnosticError):
                diagnostic.read_ha_config(path)

    def test_ambiguous_device_does_not_issue_commands(self):
        result = type('Result', (), {'returncode': 0, 'stdout': 'List of devices attached\na\tdevice\nb\tdevice\n'})()
        with patch.dict(os.environ, {}, clear=True), patch.object(diagnostic.subprocess, 'run', return_value=result) as run:
            with self.assertRaises(diagnostic.DiagnosticError):
                diagnostic.Adb('/fake/adb')
            self.assertEqual(run.call_count, 1)

    def test_absent_package_is_a_first_install_not_an_adb_failure(self):
        device = Device()
        self.assertIsNone(setup.installed_apk(device))
        self.assertEqual(device.calls, [('shell', 'pm', 'list', 'packages', setup.PACKAGE)])


class InstallTests(unittest.TestCase):
    def setUp(self):
        self.device = Device()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        directory = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.candidate = Path(directory) / 'candidate.apk'
        self.candidate.write_bytes(b'new')
        for name, value in [
            ('apk_details', DETAILS),
            ('device_info', {'supported_model': True, 'arm64': True, 'android_api': 33}),
            ('installed_apk', None),
            ('process_snapshot', [{'pid': 42, 'role': 'ha', 'start_ticks': '99'}]),
            ('verify', STATE), ('voice_idle', None), ('launcher_audio_idle', None),
        ]:
            mock = self.stack.enter_context(patch.object(setup, name, return_value=value))
            setattr(self, name, mock)

    def install(self, **kwargs):
        return setup.install_apk(self.device, self.candidate, CONFIG, **kwargs)

    def assert_no_install(self):
        self.assertFalse(any(call[0] == 'install' for call in self.device.calls))

    def test_same_apk_is_idempotent_and_does_not_close_sessions(self):
        self.installed_apk.return_value = b'new'
        result = self.install()
        self.assertEqual(result['action'], 'already_installed')
        self.assertFalse(result['changed'])
        self.process_snapshot.assert_not_called()
        self.voice_idle.assert_not_called()
        self.assert_no_install()

    def test_signature_mismatch_prevents_upgrade(self):
        self.installed_apk.return_value = b'old'
        self.apk_details.side_effect = [DETAILS, {**DETAILS, 'certificate': 'b' * 64}]
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'signing_key_mismatch'):
            self.install()
        self.assert_no_install()

    def test_downgrade_prevents_upgrade(self):
        self.installed_apk.return_value = b'old'
        self.apk_details.side_effect = [DETAILS, {**DETAILS, 'version_code': 17}]
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'downgrade'):
            self.install()
        self.assert_no_install()

    def test_active_soak_prevents_upgrade(self):
        self.process_snapshot.return_value = [{'pid': 42, 'role': 'soak', 'start_ticks': '99'}]
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'soak_recorder_active'):
            self.install()
        self.assert_no_install()

    def test_active_voice_prevents_upgrade(self):
        self.voice_idle.side_effect = diagnostic.DiagnosticError('voice_session_active_or_unknown')
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'voice_session'):
            self.install()
        self.assert_no_install()

    def test_check_only_never_installs(self):
        self.assertEqual(self.install(check_only=True)['action'], 'install_plan')
        self.assert_no_install()

    def test_process_changes_prevent_install(self):
        self.process_snapshot.side_effect = [[{'pid': 42, 'role': 'ha', 'start_ticks': '99'}], []]
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'processes_changed_before'):
            self.install()
        self.assert_no_install()

    def test_success_installs_once_and_reads_back_exact_apk(self):
        self.installed_apk.side_effect = [None, b'new']
        result = self.install()
        self.assertTrue(result['process_identity_preserved'])
        self.assertTrue(result['ha_report_preserved'])
        self.assertEqual(sum(call[0] == 'install' for call in self.device.calls), 1)
        self.assertEqual(self.verify.call_count, 2)

    def test_partial_install_is_not_automatically_retried(self):
        self.device.run = lambda *args, **kwargs: 'Failure [INSTALL_FAILED_TEST]'
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'install_result_unconfirmed'):
            self.install()
        self.installed_apk.assert_called_once()

    def test_concurrent_build_cannot_replace_the_verified_candidate(self):
        uploaded = []
        def read_state(*args):
            self.candidate.write_bytes(b'changed by concurrent build')
            return STATE
        def run(*args, **kwargs):
            if args[:2] == ('install', '-r'):
                uploaded.append(Path(args[2]).read_bytes())
                return 'Success'
            return ''
        self.verify.side_effect = read_state
        self.device.run = run
        self.installed_apk.side_effect = [None, b'new']
        self.install()
        self.assertEqual(uploaded, [b'new'])

    def test_wrong_model_is_rejected(self):
        self.device_info.return_value = {'supported_model': False, 'arm64': True, 'android_api': 33}
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'unsupported_tablet'):
            self.install()
        self.assert_no_install()


class ToolchainTests(unittest.TestCase):
    def test_existing_install_without_original_signing_key_stops_before_build(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
            stack.enter_context(patch.object(setup, 'LAUNCHER', Path(folder)))
            stack.enter_context(patch.object(setup, 'read_ha_config', return_value=CONFIG))
            stack.enter_context(patch.object(setup, 'Adb', return_value=Device()))
            stack.enter_context(patch.object(setup, 'installed_apk', return_value=b'old'))
            stack.enter_context(patch.object(setup, 'operation_lock', return_value=nullcontext()))
            prepare = stack.enter_context(patch.object(setup, 'prepare'))
            build = stack.enter_context(patch.object(setup, 'build'))
            with redirect_stdout(output):
                code = setup.main(['setup', '--config', '/private/config.json'])
            self.assertEqual(code, 1)
            self.assertEqual(json.loads(output.getvalue())['error'],
                             'existing_install_requires_original_local_signing_identity')
            prepare.assert_not_called()
            build.assert_not_called()

    def test_standard_sdk_works_without_author_local_directories(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            java = root / 'jdk'
            sdk = root / 'sdk'
            for path in [java / 'bin/java', java / 'bin/javac',
                         sdk / 'platforms/android-33/android.jar',
                         sdk / 'build-tools/35.0.0/aapt2', sdk / 'build-tools/35.0.0/zipalign',
                         sdk / 'build-tools/35.0.0/lib/apksigner.jar', sdk / 'build-tools/35.0.0/lib/d8.jar']:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            environment = {'JAVA_HOME': str(java), 'ANDROID_HOME': str(sdk)}
            with patch.object(toolchain, 'ROOT', root / 'empty-checkout'), patch.dict(os.environ, environment, clear=True):
                resolved = toolchain.resolve_tools()
            self.assertEqual(resolved, (java, sdk / 'platforms/android-33/android.jar', sdk / 'build-tools/35.0.0'))


class VoiceTests(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.connection = self.stack.enter_context(patch.object(setup.http.client, 'HTTPSConnection')).return_value
        self.connection.sock.getpeercert.return_value = b'certificate'
        fingerprint = hashlib.sha256(b'certificate').hexdigest()
        self.pairing = {'endpoint': 'https://voice.invalid', 'cert_sha256': fingerprint, 'token': 'x' * 48}
        self.stack.enter_context(patch.object(setup, 'read_private_json', return_value=self.pairing))
        self.stack.enter_context(patch.object(Path, 'read_text', return_value='certificate'))
        self.stack.enter_context(patch.object(setup.ssl, 'PEM_cert_to_DER_cert', return_value=b'certificate'))
        self.stack.enter_context(patch.object(setup.ssl._ssl, '_test_decode_cert', return_value={
            'notBefore': 'before', 'notAfter': 'after'}))
        self.stack.enter_context(patch.object(setup.ssl, 'match_hostname'))
        self.stack.enter_context(patch.object(setup.ssl, 'cert_time_to_seconds', side_effect=[0, 9999999999]))

    def response(self, active=0):
        payload = {'service': 'home-pad-voice', 'sessions': {'active_sessions': active, 'pending_sessions': 0}}
        self.connection.getresponse.return_value = Response(json.dumps(payload).encode())

    def test_idle_uses_actual_nested_health_contract(self):
        self.response()
        setup.voice_idle(Path('/private/tablet-voice-setup.json'))
        self.connection.request.assert_called_once_with('GET', '/health',
            headers={'Authorization': 'Bearer ' + self.pairing['token']})
        self.connection.close.assert_called_once()

    def test_busy_session_is_rejected(self):
        self.response(active=1)
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'voice_session_active'):
            setup.voice_idle(Path('/private/tablet-voice-setup.json'))

    def test_pin_is_verified_before_credentials_are_sent(self):
        self.connection.sock.getpeercert.return_value = b'different certificate'
        with self.assertRaisesRegex(diagnostic.DiagnosticError, 'voice_certificate_changed'):
            setup.voice_idle(Path('/private/tablet-voice-setup.json'))
        self.connection.request.assert_not_called()
        self.connection.close.assert_called_once()


class RepositoryPortabilityTests(unittest.TestCase):
    def test_tracked_text_has_no_personal_absolute_paths_or_local_relocation_record(self):
        files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=setup.ROOT).decode().split('\0')
        self.assertNotIn('work/project-relocation.json', files)
        personal_path = re.compile(
            r'/(?:Users|Volumes)/[^\s\x22\x27`]+|'
            r'(?<![A-Za-z0-9_./-])/(?:home)/[^\s\x22\x27`]+|'
            r'/(?:private/)?var/folders/[^\s\x22\x27`]+|'
            r'[A-Za-z]:[\\/]+Users[\\/]+[^\s\x22\x27`]+', re.I)
        failures = []
        for name in filter(None, files):
            data = (setup.ROOT / name).read_bytes()
            if b'\0' in data:
                continue
            for number, line in enumerate(data.decode('utf-8', errors='replace').splitlines(), 1):
                if personal_path.search(line):
                    failures.append(name + ':' + str(number))
        self.assertEqual(failures, [], 'Personal paths found in tracked files listed by name and line')

    def test_legacy_environment_resolves_checkout_through_spaces_and_compatibility_symlink(self):
        with tempfile.TemporaryDirectory() as folder:
            sandbox = Path(folder)
            checkout = sandbox / 'checkout with spaces'
            source = checkout / 'work/homeassistant/env.sh'
            source.parent.mkdir(parents=True)
            shutil.copyfile(setup.ROOT / 'work/homeassistant/env.sh', source)
            compatibility = sandbox / 'previous-work'
            compatibility.symlink_to(checkout / 'work', target_is_directory=True)
            source_via_link = compatibility / 'homeassistant/env.sh'
            for shell in ('bash', 'zsh'):
                executable = shutil.which(shell)
                if executable is None:
                    continue
                with self.subTest(shell=shell):
                    command = ('source "$1"; printf "%s\\n" "$COLIMA_HOME" '
                               '"$COLIMA_CACHE_HOME" "$DOCKER_CONFIG" "$DOCKER_HOST" "$HOME"')
                    result = subprocess.run([executable, '-c', command, 'home-pad-env-test', str(source_via_link)],
                                            cwd=sandbox, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    expected = [str(checkout.resolve() / relative) for relative in
                                ('work/ha-vm', 'work/ha-cache', 'work/ha-client')]
                    expected.extend(['unix://' + expected[0] + '/ha/docker.sock', os.environ['HOME']])
                    self.assertEqual(result.stdout.splitlines(), expected)

if __name__ == '__main__':
    unittest.main()
