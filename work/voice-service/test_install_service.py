"""Offline deployment guard tests; never execute launchctl or contact a service."""
import contextlib
from dataclasses import replace
import io
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import install_service as service


class LaunchAgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        source = root / 'project' / 'work' / 'voice-service'
        source.mkdir(parents=True)
        (source / 'server.py').write_text('# fixture, never run\n')
        private = source / 'private'
        private.mkdir(mode=0o700)
        config = private / 'config.json'
        # Deliberately invalid JSON: installer must not parse configuration.
        config.write_text('DO_NOT_READ_THIS_PRIVATE_VALUE')
        config.chmod(0o600)
        self.plan = service.Plan(root / 'home', source, config,
                                 Path(sys.executable), service.CODEX,
                                 uid=os.getuid())
        self.calls = []

    def runner(self, *args):
        self.calls.append(args)
        raise AssertionError('No launchctl operation was expected')

    def write_owned(self):
        self.plan.plist.parent.mkdir(parents=True)
        self.plan.plist.write_bytes(self.plan.encoded())
        self.plan.plist.chmod(0o600)

    def fake_result(self, code=0, output=''):
        return subprocess.CompletedProcess([], code, output, 'PRIVATE STDERR MUST NOT BE PRINTED')

    def test_preview_is_read_only_and_redacts_paths_and_config(self):
        with patch.object(service.os, 'access', return_value=True), \
                patch.object(Path, 'is_file', return_value=True):
            result = service.execute(self.plan, 'install', check_only=True, runner=self.runner)
        self.assertEqual(self.calls, [])
        self.assertFalse(self.plan.home.exists())
        self.assertNotIn('DO_NOT_READ', json.dumps(result))
        self.assertNotIn(str(self.plan.home), json.dumps(result))
        self.assertNotIn(str(self.plan.config), json.dumps(result))
        self.assertTrue(result['check_only'])

    def test_no_action_never_calls_launchctl(self):
        with patch.object(service, 'launchctl', side_effect=AssertionError), \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                service.main([])
        self.assertEqual(result.exception.code, 2)

    def test_plist_contains_only_path_environment_and_normal_identity(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'SENSITIVE_FIXTURE', 'CODEX_HOME': '/unused/private'}):
            encoded = self.plan.encoded()
        document = plistlib.loads(encoded)
        self.assertEqual(document['EnvironmentVariables'], {'PATH': str(service.CODEX.parent) + ':' + service.SYSTEM_PATH})
        self.assertNotIn(b'SENSITIVE_FIXTURE', encoded)
        self.assertNotIn('UserName', document)
        self.assertNotIn('GroupName', document)
        self.assertTrue(document['KeepAlive'])
        self.assertGreaterEqual(document['ThrottleInterval'], 10)
        self.assertGreater(document['ExitTimeOut'], 0)
        self.assertEqual(document['Umask'], 0o077)

    def test_foreign_plist_and_symlink_are_never_replaced(self):
        self.plan.plist.parent.mkdir(parents=True)
        original = plistlib.dumps({'Label': service.LABEL, 'ProgramArguments': ['/bin/echo', 'foreign']})
        self.plan.plist.write_bytes(original)
        self.plan.plist.chmod(0o600)
        with self.assertRaisesRegex(service.ServiceError, 'not_managed'):
            service.execute(self.plan, 'stop', runner=self.runner)
        self.assertEqual(self.plan.plist.read_bytes(), original)
        self.plan.plist.unlink()
        self.plan.plist.symlink_to(self.plan.config)
        with self.assertRaisesRegex(service.ServiceError, 'permissions_invalid'):
            service.execute(self.plan, 'stop', runner=self.runner)
        self.assertTrue(self.plan.plist.is_symlink())

    def test_owned_plist_is_recognized(self):
        self.write_owned()
        self.assertEqual(service.existing_plist(self.plan), self.plan.encoded())

    def test_public_private_config_rejected_without_reading(self):
        self.plan.config.chmod(0o644)
        with self.assertRaisesRegex(service.ServiceError, 'permissions_invalid'):
            service.validate_install(self.plan)

    def test_ipv6_rejected_before_touching_existing_service(self):
        self.write_owned()
        original = self.plan.plist.read_bytes()
        for host in ('::', '::1', '::ffff:127.0.0.1'):
            with self.subTest(host=host), patch.object(service, 'write_plist') as write:
                with self.assertRaises(service.ServiceError) as caught:
                    service.execute(replace(self.plan, host=host), 'install', runner=self.runner)
                self.assertEqual(str(caught.exception), 'host_must_be_numeric_ipv4')
                write.assert_not_called()
        self.assertEqual(self.calls, [])
        self.assertEqual(self.plan.plist.read_bytes(), original)
        self.assertFalse(self.plan.logs.exists())

    def test_running_foreign_origin_cannot_be_stopped(self):
        self.write_owned()
        def runner(*args, **kwargs):
            self.calls.append(args)
            self.assertEqual(args[0], 'print')
            return self.fake_result(output='\tpath = /foreign/job.plist\n\tstate = running\n')
        with patch.object(service.sys, 'platform', 'darwin'):
            with self.assertRaisesRegex(service.ServiceError, 'loaded_job_not_managed'):
                service.execute(self.plan, 'stop', runner=runner)
        self.assertEqual(len(self.calls), 1)

    def test_unknown_launchctl_error_is_not_treated_as_absence(self):
        with self.assertRaisesRegex(service.ServiceError, 'status_failed'):
            service.state(self.plan, lambda *args: self.fake_result(code=5))

    def test_status_projects_only_safe_fields(self):
        raw = ('\tpath = ' + str(self.plan.plist) + '\n\tstate = running\n\tpid = 101\n'
               '\tlast exit code = 0\n\tenvironment = { OPENAI_API_KEY = PRIVATE }\n')
        value = service.state(self.plan, lambda *args: self.fake_result(output=raw))
        self.assertEqual(value, {'loaded': True, 'managed_origin': True, 'state': 'running',
                                 'pid': 101, 'last_exit_code': 0})
        self.assertNotIn('PRIVATE', json.dumps(value))

    def test_stop_disables_owned_agent_and_verifies_unloaded(self):
        self.write_owned()
        responses = [self.fake_result(output='\tpath = ' + str(self.plan.plist)),
                     self.fake_result(), self.fake_result(), self.fake_result(code=113)]
        def runner(*args, **kwargs):
            self.calls.append(args)
            return responses.pop(0)
        with patch.object(service.sys, 'platform', 'darwin'):
            value = service.execute(self.plan, 'stop', runner=runner)
        self.assertEqual([call[0] for call in self.calls], ['print', 'disable', 'bootout', 'print'])
        self.assertFalse(value['next_login_starts_service'])
        self.assertTrue(self.plan.plist.exists())

    def test_install_creates_private_files_then_bootstraps_only_owned_label(self):
        responses = [self.fake_result(code=113), self.fake_result(), self.fake_result(),
                     self.fake_result(output='\tpath = ' + str(self.plan.plist) + '\n\tstate = running\n\tpid = 123')]
        def runner(*args, **kwargs):
            self.calls.append(args)
            return responses.pop(0)
        with patch.object(service.sys, 'platform', 'darwin'), \
                patch.object(service.os, 'access', return_value=True), \
                patch.object(Path, 'is_file', return_value=True):
            result = service.execute(self.plan, 'install', runner=runner)
        self.assertEqual([call[0] for call in self.calls], ['print', 'enable', 'bootstrap', 'print'])
        self.assertEqual(self.calls[2], ('bootstrap', 'gui/' + str(os.getuid()), str(self.plan.plist)))
        self.assertEqual(self.plan.plist.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.plan.logs.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.plan.logs / 'stdout.log').stat().st_mode & 0o777, 0o600)
        self.assertEqual(service.existing_plist(self.plan), self.plan.encoded())
        self.assertTrue(result['installed'])
        self.assertFalse(result['voice_connection_verified'])

    def test_reinstall_waits_for_label_absence_before_write_and_bootstrap(self):
        self.write_owned()
        loaded = self.fake_result(output='\tpath = ' + str(self.plan.plist) + '\n\tstate = running')
        responses = [loaded, self.fake_result(), loaded, loaded, self.fake_result(code=113),
                     self.fake_result(), self.fake_result(), loaded]
        absent_seen = False
        timeline = []
        def runner(*args, **kwargs):
            nonlocal absent_seen
            timeline.append(args[0])
            result = responses.pop(0)
            if args[0] == 'print' and result.returncode == 113:
                absent_seen = True
            if args[0] in ('enable', 'bootstrap'):
                self.assertTrue(absent_seen)
            return result
        actual_write = service.write_plist
        def guarded_write(*args):
            self.assertTrue(absent_seen)
            timeline.append('write')
            actual_write(*args)
        with patch.object(service.sys, 'platform', 'darwin'), \
                patch.object(service, 'validate_install'), \
                patch.object(service.time, 'sleep') as sleeper, \
                patch.object(service, 'write_plist', side_effect=guarded_write):
            result = service.execute(self.plan, 'install', runner=runner)
        self.assertTrue(result['installed'])
        self.assertEqual(timeline, ['print', 'bootout', 'print', 'print', 'print',
                                    'write', 'enable', 'bootstrap', 'print'])
        self.assertEqual([call.args[0] for call in sleeper.call_args_list], [0.25, 0.25])

    def test_stop_waits_for_async_unload(self):
        self.write_owned()
        loaded = self.fake_result(output='\tpath = ' + str(self.plan.plist))
        responses = [loaded, self.fake_result(), self.fake_result(), loaded,
                     self.fake_result(code=3)]
        def runner(*args, **kwargs):
            self.calls.append(args)
            return responses.pop(0)
        with patch.object(service.sys, 'platform', 'darwin'), \
                patch.object(service.time, 'sleep') as sleeper:
            result = service.execute(self.plan, 'stop', runner=runner)
        self.assertFalse(result['loaded'])
        self.assertEqual([call[0] for call in self.calls], ['print', 'disable', 'bootout', 'print', 'print'])
        sleeper.assert_called_once_with(0.25)

    def test_unload_timeout_never_writes_or_bootstraps_and_shares_budget(self):
        self.write_owned()
        now = [0.0]
        sleeps = []
        loaded = self.fake_result(output='\tpath = ' + str(self.plan.plist))
        def runner(*args, **kwargs):
            self.calls.append(args)
            if args[0] == 'bootout':
                self.assertEqual(kwargs['timeout'], 20)
                now[0] += 2.0
                return self.fake_result()
            self.assertEqual(args[0], 'print')
            if 'timeout' in kwargs:
                self.assertLessEqual(kwargs['timeout'], 20 - now[0])
            return loaded
        def sleep(seconds):
            self.assertGreater(seconds, 0)
            self.assertLessEqual(seconds, 1)
            sleeps.append(seconds)
            now[0] += seconds
        with patch.object(service.sys, 'platform', 'darwin'), \
                patch.object(service, 'validate_install'), \
                patch.object(service.time, 'monotonic', side_effect=lambda: now[0]), \
                patch.object(service.time, 'sleep', side_effect=sleep), \
                patch.object(service, 'write_plist') as write:
            with self.assertRaisesRegex(service.ServiceError, '^launchctl_unload_timeout$'):
                service.execute(self.plan, 'install', runner=runner)
        write.assert_not_called()
        self.assertEqual(now[0], 20)
        self.assertEqual(sum(call[0] == 'bootout' for call in self.calls), 1)
        self.assertNotIn('bootstrap', [call[0] for call in self.calls])
        self.assertTrue(sleeps)

    def test_uncertain_or_foreign_unload_status_aborts_without_replacement(self):
        self.write_owned()
        for uncertain in (self.fake_result(code=5), self.fake_result(output='\tpath = /foreign/job.plist')):
            with self.subTest(exit_code=uncertain.returncode):
                loaded = self.fake_result(output='\tpath = ' + str(self.plan.plist))
                responses = [loaded, self.fake_result(), uncertain]
                operations = []
                def runner(*args, **kwargs):
                    operations.append(args[0])
                    return responses.pop(0)
                with patch.object(service.sys, 'platform', 'darwin'), \
                        patch.object(service, 'validate_install'), \
                        patch.object(service.time, 'sleep') as sleeper, \
                        patch.object(service, 'write_plist') as write:
                    with self.assertRaises(service.ServiceError):
                        service.execute(self.plan, 'install', runner=runner)
                write.assert_not_called()
                sleeper.assert_not_called()
                self.assertEqual(operations, ['print', 'bootout', 'print'])

    def test_launchctl_honors_remaining_timeout(self):
        with patch.object(subprocess, 'run', return_value=self.fake_result()) as run:
            service.launchctl('print', self.plan.target, timeout=0.4)
        self.assertEqual(run.call_args.kwargs['timeout'], 0.4)

    def test_marked_non_dictionary_plist_is_refused(self):
        self.plan.plist.parent.mkdir(parents=True)
        raw = plistlib.dumps(['foreign'])
        raw = raw.replace(b'<plist', service.MARKER + b'\n<plist', 1)
        self.plan.plist.write_bytes(raw)
        self.plan.plist.chmod(0o600)
        with self.assertRaisesRegex(service.ServiceError, 'not_managed'):
            service.existing_plist(self.plan)

    def test_launchctl_timeout_has_no_raw_output(self):
        failure = subprocess.TimeoutExpired('launchctl', 20, output=b'PRIVATE')
        with patch.object(subprocess, 'run', side_effect=failure):
            with self.assertRaisesRegex(service.ServiceError, '^launchctl_timeout$'):
                service.launchctl('print', self.plan.target)


if __name__ == '__main__':
    unittest.main()
