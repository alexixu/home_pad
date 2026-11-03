"""Offline safety and evidence tests; never contact Home Assistant or a device."""
import importlib.util
import io
import json
import os
import tempfile
import unittest
from http.client import IncompleteRead
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import ProxyHandler

spec = importlib.util.spec_from_file_location('soak_monitor', Path(__file__).with_name('soak_monitor.py'))
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class Response(io.BytesIO):
    status = 200


class FakeOpener:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        item = next(self.responses)
        if isinstance(item, Exception):
            raise item
        return Response(json.dumps(item).encode())


class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = {'base_url': monitor.BASE_URL, 'token': 'FAKE-private-test-token',
                       'entity_id': 'switch.private_test_entity'}

    def tearDown(self):
        self.temp.cleanup()

    def test_configuration_is_loopback_only_and_private(self):
        path = self.root / 'config.json'
        path.write_text(json.dumps(self.config))
        path.chmod(0o644)
        self.assertEqual(monitor.config_from_file(path), self.config)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        for key, value in [('base_url', 'http://example.com'), ('entity_id', 'switch.x/../../services'),
                           ('token', 'bad\nheader')]:
            path.write_text(json.dumps({**self.config, key: value}))
            with self.assertRaises(ValueError):
                monitor.config_from_file(path)

    def test_only_two_get_endpoints_and_no_secret_in_sample(self):
        opener = FakeOpener([{'message': 'API running.'}, {'state': 'on', 'entity_id': self.config['entity_id'],
                    'attributes': {'token': self.config['token']}, 'last_updated': '2026-10-30T12:00:00+00:00'}])
        reader = monitor.Reader(self.config, opener)
        with patch.object(monitor, 'device_metrics', return_value={}):
            record = monitor.sample(reader, 'test-run')
        self.assertTrue(record['api_ok'])
        self.assertTrue(record['entity_read_ok'])
        self.assertEqual(record['state'], 'on')
        self.assertEqual([r.get_method() for r in opener.requests], ['GET', 'GET'])
        self.assertEqual([r.full_url for r in opener.requests], [monitor.BASE_URL + '/api/',
                          monitor.BASE_URL + '/api/states/' + self.config['entity_id']])
        serialized = json.dumps(record)
        self.assertNotIn(self.config['token'], serialized)
        self.assertNotIn(self.config['entity_id'], serialized)
        with self.assertRaises(ValueError):
            reader.get('/api/services/switch/turn_on')

    def test_redirect_is_refused_and_error_details_are_not_recorded(self):
        error = HTTPError(monitor.BASE_URL, 302, self.config['token'], {}, None)
        reader = monitor.Reader(self.config, FakeOpener([error]))
        result = reader.get('/api/')
        self.assertFalse(result['ok'])
        self.assertEqual(result['error'], 'redirect_refused')
        self.assertNotIn(self.config['token'], json.dumps(result))
        self.assertIsNone(monitor.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://example.com'))

    def test_proxy_environment_is_ignored(self):
        with patch.dict(os.environ, {'http_proxy': 'http://proxy.invalid:1234'}):
            captured = []
            with patch.object(monitor, 'build_opener', side_effect=lambda *args: captured.extend(args)):
                monitor.Reader(self.config)
            self.assertEqual(next(h.proxies for h in captured if isinstance(h, ProxyHandler)), {})

    def test_unexpected_state_and_timestamp_are_sanitized(self):
        opener = FakeOpener([{'message': self.config['token']},
                            {'state': self.config['token'], 'last_updated': self.config['token']}])
        with patch.object(monitor, 'device_metrics', return_value={}):
            result = monitor.sample(monitor.Reader(self.config, opener), 'test-run')
        self.assertEqual(result['state'], 'unexpected')
        self.assertIsNone(result['last_updated'])
        self.assertFalse(result['api_ok'])
        self.assertNotIn(self.config['token'], json.dumps(result))

    def test_malformed_response_cannot_crash_sampler_or_leak_body(self):
        reader = monitor.Reader(self.config, FakeOpener([IncompleteRead(b'FAKE-private-test-token')]))
        result = reader.get('/api/')
        self.assertEqual(result['error'], 'incomplete_http_response')
        self.assertNotIn(self.config['token'], json.dumps(result))
        opener = FakeOpener([{'message': 'API running.'}, {'state': {'unexpected': self.config['token']}}])
        with patch.object(monitor, 'device_metrics', return_value={}):
            result = monitor.sample(monitor.Reader(self.config, opener), 'test-run')
        self.assertEqual(result['state'], 'unexpected')
        self.assertFalse(result['entity_read_ok'])

    def test_resume_preserves_run_and_deadline(self):
        path = self.root / 'run.state.json'
        original = monitor.load_run(path, 86400, 60, now=1000)
        resumed = monitor.load_run(path, 86400, 60, now=90000)
        self.assertEqual(original, resumed)
        self.assertEqual(resumed['deadline_epoch'], 87400)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError):
            monitor.load_run(path, 172800, 60, now=90000)

    def test_partial_log_row_is_preserved_and_prevents_complete_coverage(self):
        path = self.root / 'run.jsonl'
        run = monitor.load_run(self.root / 'run.state.json', 120, 60, now=1000)
        path.write_text('{"partial":')
        for epoch in [1000, 1060, 1120]:
            monitor.append_sample(path, {'run_id': run['run_id'], 'epoch_s': epoch,
                                        'monotonic_s': epoch, 'utc': monitor.utc(epoch), 'state': 'on'})
        summary = monitor.summarize(path, run, 'window_elapsed')
        self.assertEqual(summary['samples'], 3)
        self.assertEqual(summary['malformed_log_rows'], 1)
        self.assertFalse(summary['coverage_complete'])
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_large_sample_gap_prevents_complete_coverage(self):
        path = self.root / 'run.jsonl'
        run = monitor.load_run(self.root / 'run.state.json', 300, 60, now=1000)
        for epoch in [1000, 1060, 1300]:
            monitor.append_sample(path, {'run_id': run['run_id'], 'epoch_s': epoch,
                                        'monotonic_s': epoch, 'utc': monitor.utc(epoch), 'state': 'on'})
        summary = monitor.summarize(path, run, 'window_elapsed')
        self.assertEqual(summary['max_sample_gap_seconds'], 240)
        self.assertEqual(summary['sample_gaps_over_tolerance'], 1)
        self.assertFalse(summary['coverage_complete'])
        self.assertFalse(summary['physical_usb_unplug_verified'])
        self.assertEqual(summary['power_control_requests_sent'], 0)

    def test_unreadable_optional_metrics_are_null(self):
        metrics = monitor.device_metrics(self.root / 'missing_proc', self.root / 'missing_sysfs')
        self.assertTrue(all(value is None for value in metrics.values()))

    def test_offline_summary_cli_never_constructs_network_reader(self):
        path = self.root / 'run.jsonl'
        run = monitor.load_run(path.with_suffix('.state.json'), 120, 60, now=1000)
        for epoch in [1000, 1060, 1120]:
            monitor.append_sample(path, {'run_id': run['run_id'], 'epoch_s': epoch,
                                        'monotonic_s': epoch, 'utc': monitor.utc(epoch), 'state': 'on'})
        capture = io.StringIO()
        with patch.object(monitor, 'Reader', side_effect=AssertionError('No network permitted')), \
                patch('sys.stdout', capture):
            monitor.main(['--summarize', '--output', str(path)])
        result = json.loads(capture.getvalue())
        self.assertTrue(result['coverage_complete'])
        self.assertEqual(result['states']['on'], 3)

    def test_process_stat_handles_spaces_in_process_name(self):
        path = self.root / 'stat'
        # stat fields 3..22; PPID is 4, starttime is 22.
        path.write_text('123 (name with spaces) ' + ' '.join(['S', '456'] + ['0'] * 17 + ['789']))
        self.assertEqual(monitor.proc_stat(path), (456, 789))

    def test_proot_argv_is_not_mistaken_for_its_python_child(self):
        proc = self.root / 'proc'

        def process(pid, parent, start, argv):
            folder = proc / str(pid)
            folder.mkdir(parents=True)
            (folder / 'cmdline').write_bytes(b'\0'.join(argv) + b'\0')
            (folder / 'stat').write_text(str(pid) + ' (process) ' +
                ' '.join(['S', str(parent)] + ['0'] * 17 + [str(start)]))

        child_args = [b'/usr/local/bin/python3.14', b'-P', b'-m', b'homeassistant', b'--config', b'/config']
        process(12, 8, 100, [b'/data/data/com.termux/files/usr/bin/proot',
                            b'--rootfs=/some/rootfs', b'/usr/bin/env', b'-i'] + child_args)
        process(13, 12, 120, child_args)
        # A separate monitor Python process must also be ignored.
        process(20, 19, 150, [b'/usr/local/bin/python3', b'/config/.soak/monitor.py'])
        metrics = monitor.device_metrics(proc, self.root / 'missing_sysfs')
        self.assertEqual(metrics['ha_python_pid'], 13)
        self.assertEqual(metrics['ha_python_start_ticks'], 120)
        self.assertEqual(metrics['proot_pid'], 12)
        self.assertEqual(metrics['proot_start_ticks'], 100)


if __name__ == '__main__':
    unittest.main()
