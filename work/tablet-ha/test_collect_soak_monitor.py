"""Inert ADB fixtures and loopback HTTP only; never contacts a tablet or HA."""
import argparse
import hashlib
import http.client
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

import collect_soak_monitor as collector


RUN = {'schema': 1, 'run_id': '11111111-1111-4111-8111-111111111111', 'started_epoch': 100.,
       'deadline_epoch': 1000., 'deadline_utc': collector.utc(1000),
       'duration_seconds': 900, 'interval_seconds': 60}


def fixture_files():
    row = {'run_id': RUN['run_id'], 'epoch_s': 100., 'monotonic_s': 10., 'utc': collector.utc(100)}
    return {'samples.state.json': json.dumps(RUN).encode(),
            'samples.summary.json': json.dumps({**RUN, 'status': 'window_elapsed',
                                                'summary_utc': collector.utc(1001),
                                                'samples': 1, 'malformed_log_rows': 0}).encode(),
            'samples.jsonl': json.dumps(row).encode() + b'\n'}


def request(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


class InertAdb:
    def __init__(self):
        self.calls, self.lines = [], []
        self.pid, self.ppid, self.ticks, self.uid = 123, 50, 10000, 10345
        self.comm, self.parent_comm, self.age, self.children = 'bash', 'com.termux', 100, []
        self.focus, self.attest, self.upload = True, True, True
        self.parent_uid = self.uid
        self.files = fixture_files()
        self.host_port = self.reverse_port = None
        self.removed, self.homes = [], 0
        self.downloaded_script = None

    def stat(self, pid):
        name, parent, ticks = (self.comm, self.ppid, self.ticks) if pid == self.pid else (self.parent_comm, 1, 20)
        return str(pid) + ' (' + name + ') ' + ' '.join(['S', str(parent)] + ['0'] * 17 + [str(ticks)]) + '\n'

    def __call__(self, *args):
        self.calls.append(args)
        if args[:2] == ('reverse', '--no-rebind'):
            self.reverse_port = int(args[2].split(':')[1])
            self.host_port = int(args[3].split(':')[1])
            return ''
        if args[:2] == ('reverse', '--remove'):
            self.removed.append(args[2])
            return ''
        if args == ('shell', 'cmd', 'package', 'list', 'packages', '-U', 'com.termux'):
            return 'package:com.termux uid:10345\n'
        if args[:2] == ('shell', 'cat'):
            if args[2] == '/proc/uptime':
                return str(self.ticks / 100 + self.age) + ' 0\n'
            match = re.fullmatch('/proc/(\d+)/(stat|status)', args[2])
            if match:
                pid = int(match[1])
                if match[2] == 'stat':
                    return self.stat(pid)
                uid = self.uid if pid == self.pid else self.parent_uid
                return 'Uid:\t' + '\t'.join([str(uid)] * 4) + '\n'
        if args == ('shell', 'getconf', 'CLK_TCK'):
            return '100\n'
        if args == ('shell', 'ps', '-A', '-o', 'PID,PPID'):
            return 'PID PPID\n123 50\n50 1\n' + ''.join(str(pid) + ' 123\n' for pid in self.children)
        if args == ('shell', 'dumpsys', 'window', 'windows'):
            return 'mCurrentFocus=Window{abc u0 ' + ('com.termux/.app.TermuxActivity' if self.focus else 'other/.Main') + '}\n'
        if args[:3] == ('shell', 'input', 'text'):
            self.lines.append(shlex.split(args[3])[0].replace('%s', ' '))
            return ''
        if args == ('shell', 'input', 'keyevent', 'KEYCODE_ENTER'):
            line = self.lines[-1]
            url = re.search(r'http://127\.0\.0\.1:\d+/[\w-]+/(?:ready|collect\.sh)', line)[0]
            path = urlsplit(url).path
            if path.endswith('/ready') and self.attest:
                assert request(self.host_port, 'GET', path)[0] == 204
            elif path.endswith('/collect.sh') and self.upload:
                status, script = request(self.host_port, 'GET', path)
                assert status == 200
                self.downloaded_script = script
                base = shlex.split(re.search(rb'^BASE=(.*)$', script, re.M)[1].decode())[0]
                prefix = urlsplit(base).path
                for name, raw in self.files.items():
                    assert request(self.host_port, 'POST', prefix + '/' + name, raw)[0] == 204
            return ''
        if args == ('shell', 'input', 'keyevent', 'KEYCODE_HOME'):
            self.homes += 1
            return ''
        raise AssertionError('Unexpected inert ADB command category: ' + repr(args[:3]))


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.metadata = self.base / 'metadata.json'
        self.metadata.write_text(json.dumps({'run_id': RUN['run_id'], 'deadline_utc': RUN['deadline_utc'],
                                             'initial_sample_summary': RUN}))
        self.args = argparse.Namespace(session_pid=123, session_start_ticks=10000,
                                       destination=self.base / 'new', timeout=1)

    def tearDown(self):
        self.temp.cleanup()

    def collect(self, fake, now=1001):
        return collector.collect(self.args, client=fake, metadata_path=self.metadata, now=now)

    def test_deadline_before_any_adb_or_output(self):
        fake = InertAdb()
        with self.assertRaisesRegex(collector.CollectionError, 'not_elapsed'):
            self.collect(fake, 999)
        self.assertEqual(fake.calls, [])
        self.assertFalse(self.args.destination.exists())

    def test_original_run_mismatch_before_adb(self):
        data = json.loads(self.metadata.read_text())
        data['run_id'] = '22222222-2222-4222-8222-222222222222'
        self.metadata.write_text(json.dumps(data))
        fake = InertAdb()
        with self.assertRaises(collector.CollectionError):
            self.collect(fake)
        self.assertEqual(fake.calls, [])

    def test_existing_destination_is_never_overwritten(self):
        self.args.destination.mkdir()
        prior = self.args.destination / 'samples.jsonl'
        prior.write_bytes(b'previous run')
        fake = InertAdb()
        with self.assertRaises(FileExistsError):
            self.collect(fake)
        self.assertEqual(prior.read_bytes(), b'previous run')
        self.assertEqual(fake.calls, [])

    def test_wrong_uid_parent_process_ticks_old_or_busy_refuse_typing(self):
        cases = [('uid', 10346), ('parent_uid', 10346), ('parent_comm', 'unrelated'),
                 ('comm', 'python'), ('ticks', 10001), ('age', 121), ('children', [321]), ('focus', False)]
        for index, (field, value) in enumerate(cases):
            with self.subTest(field=field):
                self.args.destination = self.base / ('fail-' + str(index))
                fake = InertAdb()
                setattr(fake, field, value)
                with self.assertRaises(collector.CollectionError):
                    self.collect(fake)
                self.assertEqual(fake.lines, [])
                self.assertEqual(fake.homes, 0)

    def test_success_preserves_bytes_sha_and_process_receipt(self):
        fake = InertAdb()
        destination, receipt = self.collect(fake)
        self.assertTrue(receipt['collection_ok'])
        self.assertTrue(receipt['validation']['valid_final_collection'])
        self.assertEqual(receipt['validation']['stability_verdict'], 'pending_separate_offline_review')
        self.assertFalse(receipt['physical_power_verified'])
        self.assertEqual(set(receipt['sha256']), collector.ALLOWED)
        for name, raw in fake.files.items():
            self.assertEqual((destination / name).read_bytes(), raw)
            self.assertEqual(receipt['sha256'][name], hashlib.sha256(raw).hexdigest())
        self.assertEqual(receipt['process_before']['pid'], 123)
        self.assertEqual(receipt['process_after']['start_ticks'], 10000)
        self.assertTrue(receipt['session_shell_attested'])
        self.assertEqual(fake.removed, ['tcp:' + str(fake.reverse_port)])
        self.assertEqual(fake.homes, 1)
        self.assertEqual(len(fake.lines), 2)
        self.assertTrue(all(line.startswith(' ') and 'HISTFILE=/dev/null' in line for line in fake.lines))
        script_key = re.search(r'http://127\.0\.0\.1:\d+/([\w-]+)/collect.sh', fake.lines[1])[1]
        self.assertNotIn(script_key, fake.lines[0])
        self.assertNotIn(script_key, (destination / 'collection-receipt.json').read_text())
        for call in fake.calls:
            self.assertNotIn('keycombination', call)
            self.assertNotIn('am', call)
        # Parse generated shell locally; never execute it or connect to a device.
        for line in fake.lines:
            subprocess.run(['/bin/bash', '-n'], input=line.encode(), check=True, capture_output=True)
        subprocess.run(['/bin/bash', '-n'], input=fake.downloaded_script, check=True, capture_output=True)

    def test_wrong_final_summary_preserved_but_not_success(self):
        for index, changes in enumerate([{'run_id': 'wrong'}, {'status': 'running'}, {'deadline_epoch': 1060.},
                                         {'summary_utc': collector.utc(999)}, {'schema': 2}]):
            with self.subTest(changes=changes):
                self.args.destination = self.base / ('bad-summary-' + str(index))
                fake = InertAdb()
                summary = json.loads(fake.files['samples.summary.json'])
                summary.update(changes)
                fake.files['samples.summary.json'] = json.dumps(summary).encode()
                with self.assertRaisesRegex(collector.CollectionError, 'final_records_not_verified'):
                    self.collect(fake)
                receipt = json.loads((self.args.destination / 'collection-receipt.json').read_text())
                self.assertFalse(receipt['collection_ok'])
                self.assertEqual(set(receipt['sha256']), collector.ALLOWED)
                self.assertEqual((self.args.destination / 'samples.summary.json').read_bytes(), fake.files['samples.summary.json'])
                self.assertEqual(len(fake.removed), 1)

    def test_malformed_raw_rows_are_never_erased_or_successful(self):
        fake = InertAdb()
        fake.files['samples.jsonl'] += b'{broken partial row'
        with self.assertRaisesRegex(collector.CollectionError, 'final_records_not_verified'):
            self.collect(fake)
        self.assertEqual((self.args.destination / 'samples.jsonl').read_bytes(), fake.files['samples.jsonl'])
        receipt = json.loads((self.args.destination / 'collection-receipt.json').read_text())
        self.assertEqual(receipt['validation']['malformed_raw_rows'], 1)
        self.assertFalse(receipt['collection_ok'])

    def test_partial_upload_keeps_raw_and_cleans_reverse(self):
        fake = InertAdb()
        del fake.files['samples.summary.json']
        with self.assertRaisesRegex(collector.CollectionError, 'incomplete_raw_transfer'):
            self.collect(fake)
        receipt = json.loads((self.args.destination / 'collection-receipt.json').read_text())
        self.assertFalse(receipt['complete_transfer'])
        self.assertEqual(set(receipt['sha256']), set(fake.files))
        self.assertEqual(len(fake.removed), 1)

    def test_unattested_selected_shell_never_receives_transfer_key_or_home(self):
        class FastEvent(threading.Event):
            def wait(self, timeout=None):
                return super().wait(.01)
        class FastReceiver(collector.Receiver):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.ready = FastEvent()
        fake = InertAdb()
        fake.attest = False
        with patch.object(collector, 'Receiver', FastReceiver):
            with self.assertRaisesRegex(collector.CollectionError, 'not_attested'):
                self.collect(fake)
        self.assertEqual(len(fake.lines), 1)
        self.assertNotIn('/collect.sh', fake.lines[0])
        self.assertEqual(fake.homes, 0)
        self.assertEqual(len(fake.removed), 1)

    def test_cleanup_failure_is_recorded_not_reported_as_success(self):
        fake = InertAdb()
        def client(*args):
            if args[:2] == ('reverse', '--remove'):
                raise collector.CollectionError('inert_cleanup_failure')
            return fake(*args)
        with self.assertRaisesRegex(collector.CollectionError, 'cleanup_unverified'):
            self.collect(client)
        receipt = json.loads((self.args.destination / 'collection-receipt.json').read_text())
        self.assertFalse(receipt['collection_ok'])
        self.assertIn('temporary_reverse_remove_failed', receipt['cleanup_errors'])
        self.assertEqual(set(receipt['sha256']), collector.ALLOWED)

    def test_cli_requires_explicit_new_session_identity(self):
        with patch.object(collector, 'collect') as collect:
            with patch('sys.stderr', io.StringIO()), self.assertRaises(SystemExit):
                collector.main([])
            collect.assert_not_called()

    def test_actual_generated_shell_guard_binds_pid_and_start_ticks(self):
        fixture = self.base / 'stat'
        fixture.write_text(InertAdb().stat(123))
        for correct in (True, False):
            shell = subprocess.Popen(['/bin/bash'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            guard = collector.session_guard(shell.pid if correct else shell.pid + 1, 10000)
            guard = guard.replace('/proc/$$/stat', shlex.quote(str(fixture)))
            output, error = shell.communicate(('if ' + guard + '; then printf accepted; else printf refused; fi').encode(), timeout=3)
            self.assertEqual(shell.returncode, 0, error)
            self.assertEqual(output, b'accepted' if correct else b'refused')


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.receiver = collector.Receiver(self.temp.name, key='inert-test', ready_key='inert-ready')
        self.server = collector.ThreadingHTTPServer(('127.0.0.1', 0), self.receiver.handler())
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def call(self, method, path, body=None, headers=None):
        return request(self.server.server_port, method, path, body, headers)[0]

    def test_only_attested_exact_allowlist_and_no_overwrite(self):
        self.assertEqual(self.call('POST', '/inert-test/samples.jsonl', b'raw'), 404)
        self.assertEqual(self.call('GET', '/inert-ready/ready'), 204)
        for path in ('/wrong/samples.jsonl', '/inert-test/config.json', '/inert-test/../samples.jsonl',
                     '/inert-test/samples.jsonl?x', '/inert-test/done'):
            self.assertEqual(self.call('POST', path, b'raw'), 404)
        self.assertEqual(self.call('POST', '/inert-test/samples.jsonl', b'{broken'), 204)
        self.assertEqual(self.call('POST', '/inert-test/samples.jsonl', b'changed'), 409)
        self.assertEqual((Path(self.temp.name) / 'samples.jsonl').read_bytes(), b'{broken')

    def test_aggregate_limit_and_bad_lengths(self):
        self.call('GET', '/inert-ready/ready')
        self.assertEqual(self.call('POST', '/inert-test/samples.state.json', b'x', {'Content-Length': 'bad'}), 400)
        with patch.object(collector, 'MAX_BYTES', 5):
            self.assertEqual(self.call('POST', '/inert-test/samples.state.json', b'123'), 204)
            self.assertEqual(self.call('POST', '/inert-test/samples.summary.json', b'456'), 413)
        self.assertFalse(self.receiver.complete.is_set())

    def test_closed_receiver_cannot_add_late_evidence_and_empty_bytes_preserved(self):
        self.call('GET', '/inert-ready/ready')
        self.assertEqual(self.call('POST', '/inert-test/samples.jsonl', b''), 204)
        self.assertEqual((Path(self.temp.name) / 'samples.jsonl').read_bytes(), b'')
        self.receiver.close()
        self.assertEqual(self.call('POST', '/inert-test/samples.state.json', b'late'), 410)
        self.assertFalse((Path(self.temp.name) / 'samples.state.json').exists())


if __name__ == '__main__':
    unittest.main()
