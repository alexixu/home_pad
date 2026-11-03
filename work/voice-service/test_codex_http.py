"""Offline Codex broker/HTTP tests: inert sessions, loopback HTTP only; no CLI, cloud, HA or audio."""
from __future__ import annotations

from contextlib import contextmanager
import http.client
import json
import threading
import time
import unittest
from unittest.mock import patch

import server

TOKEN = 'offline-codex-device-' + 'x' * 32
OFFER = 'v=0\r\ns=offline-codex-offer\r\n'
ANSWER = 'v=0\r\ns=offline-codex-answer\r\n'


def offer(nonce='mock-nonce-0001', **changes):
    return {'offer_sdp': OFFER, 'read_only': True, 'nonce': nonce, **changes}


class FakeSession:
    def __init__(self, identity, factory, read_only=True):
        self.identity, self.factory, self.read_only = identity, factory, read_only
        self.closed = False
        self.closes = self.polls = 0
        self.results, self.pending, self.queue = [], {}, []

    def start_webrtc(self, sdp, nonce, timeout):
        self.factory.started.set()
        self.factory.release.wait(2)
        if self.factory.start_error:
            raise self.factory.start_error
        return {'sessionId': self.identity, 'answerSdp': ANSWER,
                'protocol': 'live', 'backend': 'codex', 'readOnly': self.read_only,
                **self.factory.result_changes}

    def poll_events(self, timeout, max_events):
        self.polls += 1
        self.factory.poll_started.set()
        self.factory.poll_release.wait(2)
        if self.closed:
            return [{'type': 'closed', 'sessionId': self.identity}]
        events, self.queue = self.queue[:max_events], self.queue[max_events:]
        return events

    def submit_tool_result(self, call_id, result):
        if self.closed or call_id not in self.pending or self.pending[call_id] < time.monotonic():
            raise ValueError('expired or wrong session fixture')
        del self.pending[call_id]
        self.results.append((call_id, result))

    def close(self):
        self.closes += 1
        self.closed = True
        self.pending.clear()


class Factory:
    def __init__(self, blocked=False, start_error=None, result_changes=None):
        self.sessions = []
        self.start_error, self.result_changes = start_error, result_changes or {}
        self.started, self.release = threading.Event(), threading.Event()
        self.poll_started, self.poll_release = threading.Event(), threading.Event()
        if not blocked:
            self.release.set()
        self.poll_release.set()

    def __call__(self, *, read_only=True):
        session = FakeSession('codex_mock_%04d' % len(self.sessions), self, read_only)
        self.sessions.append(session)
        return session


@contextmanager
def running(factory=None, **settings):
    factory = factory or Factory()
    service = server.make_server(server.Settings(TOKEN, provider='codex', sessions_per_minute=10,
                                                **settings), '127.0.0.1', 0, factory)
    thread = threading.Thread(target=service.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try:
        yield service, factory
    finally:
        factory.release.set()
        factory.poll_release.set()
        service.shutdown()
        service.server_close()
        thread.join(2)


def request(service, method='POST', path='/v1/voice/sessions', value=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', service.server_port, timeout=3)
    h = {'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'}
    h.update(headers or {})
    body = json.dumps(offer() if value is None else value) if method == 'POST' else None
    connection.request(method, path, body, h)
    response = connection.getresponse()
    result = response.status, dict(response.getheaders()), json.loads(response.read())
    connection.close()
    return result


class CodexHttpTests(unittest.TestCase):
    def test_large_catalog_result_has_separate_bounded_http_limit(self):
        with running() as (service, factory):
            sid = request(service)[2]['sessionId']
            factory.sessions[0].pending['catalog-call'] = time.monotonic() + 30
            # FakeSession is inert: this verifies framing only, while backend catalog
            # tests enforce the smaller semantic limits and field allowlist.
            value = {'call_id': 'catalog-call', 'result': {'fixture': 'x' * 130000}}
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/results', value=value)[0], 200)
            value['result']['fixture'] = 'x' * server.MAX_TOOL_RESULT_REQUEST
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/results', value=value)[0], 413)
            self.assertEqual(request(service, value={'fixture': 'x' * server.MAX_REQUEST})[0], 413)
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/close',
                                     value={'fixture': 'x' * server.MAX_REQUEST})[0], 413)

    def test_every_endpoint_authenticates_before_session_access(self):
        with running() as (service, factory):
            sid = request(service)[2]['sessionId']
            endpoints = [('GET', '/health', None), ('POST', '/v1/voice/sessions', offer('fresh-nonce-001')),
                         ('GET', f'/v1/voice/sessions/{sid}/events', None),
                         ('POST', f'/v1/voice/sessions/{sid}/results', {'call_id': 'call', 'result': {}}),
                         ('POST', f'/v1/voice/sessions/{sid}/close', {})]
            for method, path, value in endpoints:
                for auth in ['', 'Bearer wrong', 'Basic ' + TOKEN]:
                    with self.subTest(method=method, path=path, auth_kind=auth[:5]):
                        status, headers, body = request(service, method, path, value,
                                                        {'Authorization': auth})
                        self.assertEqual(status, 401)
                        self.assertNotIn(TOKEN, json.dumps(body))
                        self.assertNotIn('Access-Control-Allow-Origin', headers)
                self.assertEqual(request(service, method, path, value, {'Origin': 'null'})[0], 403)
            self.assertEqual(len(factory.sessions), 1)
            self.assertEqual((factory.sessions[0].polls, factory.sessions[0].closes), (0, 0))
            self.assertFalse(factory.sessions[0].results)

    def test_duplicate_auth_on_tool_results_is_rejected(self):
        with running() as (service, factory):
            sid = request(service)[2]['sessionId']
            payload = json.dumps({'call_id': 'call', 'result': {}})
            c = http.client.HTTPConnection('127.0.0.1', service.server_port)
            c.putrequest('POST', f'/v1/voice/sessions/{sid}/results')
            for _ in range(2):
                c.putheader('Authorization', 'Bearer ' + TOKEN)
            c.putheader('Content-Type', 'application/json')
            c.putheader('Content-Length', str(len(payload)))
            c.endheaders(payload.encode())
            response = c.getresponse()
            self.assertEqual(response.status, 401)
            response.read(); c.close()
            self.assertFalse(factory.sessions[0].results)

    def test_codex_control_requires_both_client_and_server_permission(self):
        for server_allows, client_read_only in [(False, False), (False, True), (True, True), (True, False)]:
            with self.subTest(server_allows=server_allows, client_read_only=client_read_only):
                with running(allow_control=server_allows) as (service, factory):
                    status, headers, result = request(service, value=offer(read_only=client_read_only))
                    effective = client_read_only or not server_allows
                    self.assertEqual(status, 201)
                    self.assertEqual(result, {'sessionId': factory.sessions[0].identity, 'answerSdp': ANSWER,
                                             'protocol': 'live', 'backend': 'codex', 'readOnly': effective})
                    self.assertIs(factory.sessions[0].read_only, effective)
                    self.assertEqual(headers['Cache-Control'], 'no-store')
                    with patch.object(server.shutil, 'which', return_value='/fake/codex'):
                        health = request(service, 'GET', '/health')[2]
                    self.assertEqual(health['provider'], 'codex-app-server')
                    self.assertIs(health['allowControl'], server_allows)
                    self.assertEqual(health['authentication'], 'managed_by_codex')
                    self.assertFalse(factory.sessions[0].results)

    def test_default_codex_policy_remains_read_only_and_missing_client_mode_is_rejected(self):
        with running() as (service, factory):
            self.assertTrue(request(service)[2]['readOnly'])
            self.assertTrue(factory.sessions[0].read_only)
            for mode in [None, 'false', 0]:
                self.assertEqual(request(service, value=offer('invalid-mode-001', read_only=mode))[0], 400)
            self.assertEqual(request(service, value={'offer_sdp': OFFER, 'nonce': 'missing-mode-001'})[0], 400)
            self.assertEqual(len(factory.sessions), 1)

    def test_backend_permission_must_match_effective_policy_and_failure_is_not_retried(self):
        for allowed in [False, True]:
            with self.subTest(server_allows=allowed):
                factory = Factory(result_changes={'readOnly': allowed})
                with running(factory, allow_control=allowed) as (service, _):
                    for _ in range(2):
                        status, _, body = request(service, value=offer(read_only=False))
                        self.assertEqual((status, body['error']['code']), (502, 'codex_voice_unavailable'))
                    self.assertEqual(len(factory.sessions), 1)
                    self.assertEqual(factory.sessions[0].closes, 1)
                    self.assertFalse(service.broker.sessions)

    def test_nonce_cannot_change_effective_permission(self):
        with running(allow_control=True) as (service, factory):
            self.assertTrue(request(service)[2]['readOnly'])
            status, _, body = request(service, value=offer(read_only=False))
            self.assertEqual((status, body['error']['code']), (409, 'nonce_conflict'))
            self.assertEqual(len(factory.sessions), 1)

    def test_same_nonce_reuses_live_session_and_conflict_is_rejected(self):
        with running() as (service, factory):
            first = request(service)[2]
            self.assertEqual(request(service)[2], first)
            status, _, body = request(service, value={**offer(), 'offer_sdp': OFFER + 'a=other\r\n'})
            self.assertEqual((status, body['error']['code']), (409, 'nonce_conflict'))
            self.assertEqual(len(factory.sessions), 1)

    def test_tool_result_is_bound_to_session_and_consumed_once(self):
        with running() as (service, factory):
            first = request(service)[2]['sessionId']
            second = request(service, value=offer('second-nonce-002'))[2]['sessionId']
            factory.sessions[0].pending['read-call'] = time.monotonic() + 30
            value = {'call_id': 'read-call', 'result': {'ok': True}}
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{second}/results', value=value)[0], 409)
            self.assertIn('read-call', factory.sessions[0].pending)
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{first}/results', value=value)[0], 200)
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{first}/results', value=value)[0], 409)
            self.assertEqual(len(factory.sessions[0].results), 1)
            self.assertFalse(factory.sessions[1].results)

    def test_expired_tool_call_is_rejected_without_submission(self):
        with running() as (service, factory):
            sid = request(service)[2]['sessionId']
            factory.sessions[0].pending['expired-call'] = time.monotonic() - 1
            status, _, body = request(service, path=f'/v1/voice/sessions/{sid}/results',
                                      value={'call_id': 'expired-call', 'result': {'ok': True}})
            self.assertEqual((status, body['error']['code']), (409, 'tool_result_rejected'))
            self.assertFalse(factory.sessions[0].results)

    def test_result_and_close_schemas_and_unknown_session(self):
        with running() as (service, _):
            sid = request(service)[2]['sessionId']
            for value in [[], {}, {'call_id': 4, 'result': {}}, {'call_id': 'x' * 201, 'result': {}},
                          {'call_id': 'call', 'result': []}, {'call_id': 'call', 'result': {}, 'extra': True}]:
                with self.subTest(value_type=type(value).__name__):
                    self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/results', value=value)[0], 400)
            self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/close', value={'extra': True})[0], 400)
            for method, suffix, value in [('GET', 'events', None), ('POST', 'results', {'call_id': 'call', 'result': {}})]:
                self.assertEqual(request(service, method, f'/v1/voice/sessions/unknown_12345/{suffix}', value)[0], 410)

    def test_close_is_idempotent_and_nonce_cannot_resurrect(self):
        with running() as (service, factory):
            sid = request(service)[2]['sessionId']
            for _ in range(2):
                self.assertEqual(request(service, path=f'/v1/voice/sessions/{sid}/close', value={})[0], 200)
            self.assertEqual(factory.sessions[0].closes, 1)
            self.assertEqual(request(service, 'GET', f'/v1/voice/sessions/{sid}/events')[0], 410)
            self.assertEqual(request(service)[0], 410)
            self.assertEqual(len(factory.sessions), 1)
            self.assertNotIn(ANSWER, str(service.broker.nonces['mock-nonce-0001']))

    def test_start_failure_is_sanitized_and_nonce_is_not_retried(self):
        factory = Factory(start_error=RuntimeError('fixture-secret-do-not-return'))
        with running(factory) as (service, _):
            for _ in range(2):
                status, _, body = request(service)
                self.assertEqual((status, body['error']['code']), (502, 'codex_voice_unavailable'))
                self.assertNotIn('fixture-secret', json.dumps(body))
            self.assertEqual(len(factory.sessions), 1)
            self.assertEqual(factory.sessions[0].closes, 1)
            self.assertEqual(service.broker.pending_creates, 0)


class ManualReaperBroker(server.CodexBroker):
    """Keep background scheduling inert; tests exercise the real reap loop deterministically."""
    def _reap(self):
        pass


class OneReap:
    def __init__(self): self.calls, self.closed = 0, False
    def wait(self, timeout): self.calls += 1; return self.calls > 1 or self.closed
    def is_set(self): return self.closed
    def set(self): self.closed = True


class CodexLifecycleTests(unittest.TestCase):
    def make_broker(self, factory=None):
        factory = factory or Factory()
        broker = ManualReaperBroker(server.Settings(TOKEN, provider='codex', sessions_per_minute=10), factory)
        self.addCleanup(broker.close)
        self.addCleanup(factory.release.set)
        self.addCleanup(factory.poll_release.set)
        return broker, factory

    def assert_error(self, code, operation):
        with self.assertRaises(server.SafeError) as caught:
            operation()
        self.assertEqual(caught.exception.code, code)

    def test_pending_nonce_and_two_sessions_bound_start_concurrency(self):
        factory = Factory(blocked=True)
        broker, _ = self.make_broker(factory)
        errors, results = [], []
        def start():
            try: results.append(broker.create(offer()))
            except Exception as error: errors.append(error)
        thread = threading.Thread(target=start)
        thread.start()
        try:
            self.assertTrue(factory.started.wait(1))
            self.assert_error('request_pending', lambda: broker.create(offer()))
            self.assertEqual(len(factory.sessions), 1)
        finally:
            factory.release.set(); thread.join(2)
        self.assertFalse(errors)
        self.assertEqual(broker.create(offer()), results[0])
        broker.create(offer('second-nonce-002'))
        self.assert_error('service_busy', lambda: broker.create(offer('third-nonce-003')))
        self.assertEqual(len(factory.sessions), 2)

    def test_shutdown_during_start_closes_new_child_and_does_not_publish_it(self):
        factory = Factory(blocked=True)
        broker, _ = self.make_broker(factory)
        errors = []
        def start():
            try: broker.create(offer())
            except Exception as error: errors.append(error)
        thread = threading.Thread(target=start); thread.start()
        try:
            self.assertTrue(factory.started.wait(1))
            broker.close()
        finally:
            factory.release.set(); thread.join(2)
        self.assertEqual(len(errors), 1)
        self.assertIsInstance(errors[0], server.SafeError)
        self.assertFalse(broker.sessions)
        self.assertEqual((broker.pending_creates, factory.sessions[0].closes), (0, 1))
        self.assert_error('service_stopping', lambda: broker.create(offer('new-after-stop')))

    def test_nonce_history_survives_previous_expiry_window(self):
        broker, factory = self.make_broker()
        first = broker.create(offer())
        now = time.monotonic() + 301
        broker.sessions[first['sessionId']]['seen'] = now
        with patch.object(server.time, 'monotonic', return_value=now):
            self.assertEqual(broker.create(offer()), first)
        self.assertEqual(len(factory.sessions), 1)
        broker.close_session(first['sessionId'])
        with patch.object(server.time, 'monotonic', return_value=now + 301):
            self.assert_error('session_closed', lambda: broker.create(offer()))
        self.assertEqual(len(factory.sessions), 1)

    def test_nonce_history_limit_rejects_new_request_without_evicting_old_tombstone(self):
        broker, factory = self.make_broker()
        sid = broker.create(offer())['sessionId']
        broker.close_session(sid)
        existing = broker.nonces['mock-nonce-0001']
        for i in range(4095):
            broker.nonces['old-nonce-%04d' % i] = dict(existing)
        with self.assertRaises(server.SafeError) as caught:
            broker.create(offer('new-nonce-full'))
        self.assertEqual(caught.exception.status, 503)
        self.assertEqual(len(broker.nonces), 4096)
        self.assert_error('session_closed', lambda: broker.create(offer()))
        self.assertEqual(len(factory.sessions), 1)

    def test_expired_session_cannot_be_revived_by_poll_or_tool_result(self):
        for operation in ['events', 'result']:
            with self.subTest(operation=operation):
                broker, factory = self.make_broker()
                sid = broker.create(offer())['sessionId']
                broker.sessions[sid]['seen'] = time.monotonic() - 61
                factory.sessions[0].pending['call'] = time.monotonic() + 30
                action = (lambda: broker.events(sid)) if operation == 'events' else (
                    lambda: broker.tool_result(sid, {'call_id': 'call', 'result': {}}))
                self.assert_error('session_closed', action)
                self.assertNotIn(sid, broker.sessions)
                self.assertEqual(factory.sessions[0].closes, 1)
                self.assertFalse(factory.sessions[0].results)

    def test_max_lifetime_applies_even_if_polling_is_fresh(self):
        broker, factory = self.make_broker()
        sid = broker.create(offer())['sessionId']
        broker.sessions[sid]['created'] = time.monotonic() - 3601
        self.assert_error('session_closed', lambda: broker.events(sid))
        self.assertEqual(factory.sessions[0].closes, 1)

    def test_idle_reaper_cannot_close_session_refreshed_after_stale_snapshot(self):
        broker, factory = self.make_broker()
        sid = broker.create(offer())['sessionId']
        broker.sessions[sid]['seen'] = time.monotonic() - 61
        inner = broker.lock
        class RefreshAfterUnlock:
            refreshed = False
            def __enter__(self): inner.acquire(); return self
            def __exit__(self, *ignored):
                inner.release()
                # Model a poll winning immediately after a stale snapshot lock is released.
                # If reap atomically removed the entry, this interleaving cannot happen.
                if not self.refreshed and sid in broker.sessions:
                    broker.sessions[sid]['seen'] = time.monotonic()
                    self.refreshed = True
        guard = RefreshAfterUnlock()
        broker.lock, broker.stopped = guard, OneReap()
        server.CodexBroker._reap(broker)
        if guard.refreshed:
            self.assertIn(sid, broker.sessions)
            self.assertEqual(factory.sessions[0].closes, 0)
        else:
            self.assertNotIn(sid, broker.sessions)
            self.assertEqual(factory.sessions[0].closes, 1)

    def test_poll_close_race_cannot_restore_session_or_deliver_pending_tool(self):
        broker, factory = self.make_broker()
        sid = broker.create(offer())['sessionId']
        factory.poll_release.clear()
        factory.sessions[0].queue = [{'type': 'tool_call', 'callId': 'queued'}]
        results = []
        thread = threading.Thread(target=lambda: results.append(broker.events(sid)))
        thread.start()
        try:
            self.assertTrue(factory.poll_started.wait(1))
            broker.close_session(sid)
        finally:
            factory.poll_release.set(); thread.join(2)
        self.assertNotIn(sid, broker.sessions)
        self.assertEqual(results[0]['events'], [{'type': 'closed', 'sessionId': sid}])
        self.assertEqual(factory.sessions[0].closes, 1)
        self.assert_error('session_closed', lambda: broker.create(offer()))


if __name__ == '__main__':
    unittest.main()
