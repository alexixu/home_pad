"""Offline protocol tests. Real fixture probe is opt-in, never run by unittest."""
import json
import queue
import subprocess
import threading
import time
import unittest

import codex_backend as backend

OFFER = 'v=0\r\ns=mock-browser-offer\r\n'
ANSWER = 'v=0\r\ns=mock-cloud-answer\r\n'
FIXTURE = {
    'ok': True, 'fixture': True, 'observed_at_ms': 0,
    'oven_plug': {'state': 'on', 'status': '演示 fixture，不是真实设备', 'network_available': False},
    'indoor': {'temperature_c': 23.5, 'humidity_percent': 48, 'status': '演示 fixture', 'recorded_at': None},
    'weather': {'temperature_c': None, 'condition': '', 'location': '', 'attribution': '', 'status': '未查询', 'recorded_at': None},
    'lights': 'not_connected',
}


class FakeReader:
    def __init__(self): self.q = queue.Queue()
    def emit(self, message): self.q.put((json.dumps(message) + '\n').encode())
    def readline(self, limit): return self.q.get(timeout=5)
    def close(self): self.q.put(b'')


class FakeWriter:
    def __init__(self, process): self.process = process
    def write(self, value): self.process.handle(json.loads(value)); return len(value)
    def flush(self): pass
    def close(self): self.process.exit_code = 0; self.process.stdout.close()


class FakeProcess:
    def __init__(self, command, *, mode='ok', **options):
        self.command, self.options, self.mode = command, options, mode
        self.stdout, self.exit_code = FakeReader(), None
        self.stdin, self.sent = FakeWriter(self), []
        self.terminated = self.killed = False
    def poll(self): return self.exit_code
    def wait(self, timeout):
        if self.exit_code is None: raise subprocess.TimeoutExpired('mock-child', timeout)
        return self.exit_code
    def terminate(self): self.terminated = True; self.exit_code = -15; self.stdout.close()
    def kill(self): self.killed = True; self.exit_code = -9; self.stdout.close()
    def emit(self, method, params, ident=None):
        message = {'method': method, 'params': params}
        if ident is not None: message['id'] = ident
        self.stdout.emit(message)
    def handle(self, message):
        self.sent.append(message)
        method = message.get('method')
        if 'id' not in message or not method: return
        if method == 'account/read':
            result = {'account': {'type': 'apiKey' if self.mode == 'api_key' else 'chatgpt', 'email': 'must-not-return@example.invalid'}, 'requiresOpenaiAuth': True}
        elif method == 'thread/start':
            result = {'thread': {'id': 'thread-mock', 'sessionId': 'thread-mock', 'ephemeral': True},
                      'approvalPolicy': 'never', 'sandbox': {'type': 'dangerFullAccess' if self.mode == 'unsafe_policy' else 'readOnly'}}
        elif method == 'mcpServerStatus/list':
            disabled = 'mcp_servers.mock_server.enabled=false' in self.command
            name = 'new_server' if disabled and self.mode == 'new_mcp_server' else 'mock_server'
            if self.mode == 'unsafe_mcp_name': name = 'unsafe.dotted'
            tools = {} if disabled and self.mode != 'mcp_not_disabled' else {'must_disable': {}}
            result = {'data': [{'name': name, 'tools': tools}], 'nextCursor': None}
        else: result = {}
        self.stdout.emit({'id': message['id'], 'result': result})
        if method == 'thread/realtime/start':
            self.emit('thread/realtime/started', {'threadId': 'thread-mock', 'version': 'v3'})
            if self.mode != 'missing_sdp':
                self.emit('thread/realtime/sdp', {'threadId': 'thread-mock', 'sdp': ANSWER})
        elif method == 'thread/realtime/stop':
            self.emit('thread/realtime/closed', {'threadId': 'thread-mock', 'reason': 'secret raw server reason'})


def make_session(mode='ok', *, read_only=True):
    return backend.CodexRealtimeSession('official-codex', read_only=read_only,
                                       process_factory=lambda command, **kwargs: FakeProcess(command, mode=mode, **kwargs))


def wait_for(session, event_type):
    end = time.monotonic() + 1
    while time.monotonic() < end:
        events = session.poll_events(.1)
        for event in events:
            if event['type'] == event_type: return event
    raise AssertionError('Expected public event absent: ' + event_type)


def tool_request(session, call='call-1', **overrides):
    session._process.emit('turn/started', {'threadId': session.thread_id, 'turn': {'id': 'turn-1'}})
    params = {'threadId': session.thread_id, 'turnId': 'turn-1', 'callId': call,
              'tool': 'get_home_status', 'arguments': {}}
    params.update(overrides)
    session._process.emit('item/tool/call', params, 'server-' + call)


class CodexBackendTests(unittest.TestCase):
    def test_real_client_identity_readonly_ephemeral_and_lifecycle(self):
        session = make_session()
        try:
            answer = session.start_webrtc(OFFER, 'nonce-one')
            self.assertEqual(answer, {'sessionId': session.session_id, 'answerSdp': ANSWER,
                                      'protocol': 'live', 'backend': 'codex', 'readOnly': True})
            self.assertNotEqual(session.session_id, session.thread_id)
            requests = {m.get('method'): m.get('params') for m in session._process.sent if 'method' in m}
            self.assertEqual(requests['initialize']['clientInfo']['name'], 'home_pad_codex')
            self.assertEqual(requests['account/read'], {'refreshToken': False})
            self.assertTrue(requests['thread/start']['ephemeral'])
            self.assertEqual(requests['thread/start']['approvalPolicy'], 'never')
            self.assertEqual([x['name'] for x in requests['thread/start']['dynamicTools']], ['get_home_status', 'get_home_devices'])
            self.assertFalse(requests['thread/realtime/start']['clientManagedHandoffs'])
            self.assertTrue(requests['thread/realtime/start']['includeStartupContext'])
            self.assertIn('features.shell_tool=false', session._process.command)
            self.assertIn('mcp_servers={}', session._process.command)
            self.assertIn('mcp_servers.mock_server.enabled=false', session._process.command)
            self.assertNotIn('must-not-return', json.dumps(session.poll_events()))
        finally: session.close()
        self.assertEqual(session._process.poll(), 0)
        self.assertIn('thread/realtime/stop', [m.get('method') for m in session._process.sent])

    def test_same_nonce_never_starts_second_session(self):
        with make_session() as session:
            first = session.start_webrtc(OFFER, 'nonce-one')
            self.assertEqual(first, session.start_webrtc(OFFER, 'nonce-one'))
            with self.assertRaises(backend.CodexBackendError) as caught:
                session.start_webrtc(OFFER + 'x', 'nonce-one')
            self.assertEqual(caught.exception.code, 'nonce_conflict')
            self.assertEqual(sum(m.get('method') == 'thread/realtime/start' for m in session._process.sent), 1)

    def test_tool_request_correlated_and_only_sanitized_result_returned(self):
        with make_session() as session:
            session.start_webrtc(OFFER)
            tool_request(session)
            event = wait_for(session, 'tool.request')
            self.assertEqual(event['sessionId'], session.session_id)
            self.assertEqual(event['arguments'], {})
            result = dict(FIXTURE, token='secret', url='http://private.invalid')
            session.submit_tool_result('call-1', result)
            response = next(m for m in session._process.sent if m.get('id') == 'server-call-1')
            data = json.loads(response['result']['contentItems'][0]['text'])
            self.assertTrue(data['fixture']); self.assertTrue(data['ok'])
            self.assertEqual(data['indoor']['humidity_percent'], 48)
            self.assertNotIn('secret', json.dumps(response)); self.assertNotIn('private.invalid', json.dumps(response))
            with self.assertRaises(backend.CodexBackendError): session.submit_tool_result('call-1', FIXTURE)

    def test_foreign_thread_turn_session_tool_arguments_and_duplicate_call_rejected(self):
        for invalid in [{'threadId': 'foreign'}, {'turnId': 'foreign'}, {'sessionId': 'foreign'},
                        {'tool': 'set_device_power'}, {'arguments': {'url': 'private'}}, {'namespace': 'foreign'}]:
            with self.subTest(invalid=list(invalid)):
                with make_session() as session:
                    session.start_webrtc(OFFER); tool_request(session, **invalid)
                    self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')
                    self.assertEqual(session._tools, {})
        with make_session() as session:
            session.start_webrtc(OFFER); tool_request(session); wait_for(session, 'tool.request')
            session.submit_tool_result('call-1', FIXTURE); tool_request(session)
            self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')

    def test_approval_and_external_token_refresh_are_rejected(self):
        for method in ['item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
                       'account/chatgptAuthTokens/refresh', 'mcpServer/elicitation/request']:
            with self.subTest(method=method):
                with make_session() as session:
                    session.start_webrtc(OFFER)
                    session._process.emit(method, {'threadId': session.thread_id, 'secret': 'never-copy'}, 'forbidden')
                    event = wait_for(session, 'error')
                    self.assertNotIn('never-copy', json.dumps(event))
                    reply = next(m for m in session._process.sent if m.get('id') == 'forbidden')
                    self.assertEqual(reply['error']['code'], -32601)

    def test_api_auth_and_unsafe_policy_abort_child_before_realtime(self):
        for mode, error in [('api_key', 'login_required'), ('unsafe_policy', 'policy_rejected')]:
            session = make_session(mode)
            with self.assertRaises(backend.CodexBackendError) as caught: session.start_webrtc(OFFER)
            self.assertEqual(caught.exception.code, error)
            self.assertIsNotNone(session._process.poll())
            self.assertNotIn('thread/realtime/start', [m.get('method') for m in session._process.sent])

    def test_rpc_ack_without_sdp_times_out_and_closes_owned_child(self):
        session = make_session('missing_sdp')
        with self.assertRaises(backend.CodexBackendError) as caught: session.start_webrtc(OFFER, timeout=1)
        self.assertEqual(caught.exception.code, 'timeout')
        self.assertIsNotNone(session._process.poll())

    def test_errors_transcripts_sdp_account_and_reasoning_are_not_polled(self):
        with make_session() as session:
            session.start_webrtc(OFFER); session.poll_events()
            for method in ['thread/realtime/transcript/delta', 'item/agentMessage/delta']:
                session._process.emit(method, {'threadId': session.thread_id, 'delta': 'private transcript'})
            session._process.emit('thread/realtime/error', {'threadId': session.thread_id, 'message': 'Bearer private-token https://private.invalid'})
            event = wait_for(session, 'error')
            self.assertEqual(event['code'], 'realtime_failed')
            self.assertNotIn('private', json.dumps(event))

    def test_projection_preserves_native_contract_but_drops_credentials(self):
        raw = dict(FIXTURE, password='do-not-send')
        raw['weather'] = dict(FIXTURE['weather'], condition='晴', attribution='Bearer never-copy', location='杭州')
        raw['indoor'] = dict(FIXTURE['indoor'], temperature_c=float('nan'))
        clean = backend.sanitize_home_status(raw)
        self.assertEqual(clean['weather']['condition'], '晴')
        self.assertEqual(clean['weather']['location'], '杭州')
        self.assertEqual(clean['weather']['attribution'], '')
        self.assertIsNone(clean['indoor']['temperature_c'])
        self.assertNotIn('password', clean)
        # Legacy status is limited to its measured fields; the separate device
        # catalog owns lighting coverage, so never invent a disconnected claim.
        self.assertNotIn('lights', clean)
        self.assertNotIn('lights', backend.sanitize_home_status({'ok': True, 'lights': 'connected'}))
        self.assertIsNone(backend.sanitize_home_status({'indoor': {'temperature_c': 10 ** 1000}})['indoor']['temperature_c'])

    def test_late_tool_result_rejected(self):
        with make_session() as session:
            session.start_webrtc(OFFER); tool_request(session); wait_for(session, 'tool.request')
            session._tools['call-1']['created'] -= 31
            with self.assertRaises(backend.CodexBackendError): session.submit_tool_result('call-1', FIXTURE)

    def test_unexpected_builtin_tool_stops_only_owned_child(self):
        with make_session() as session:
            session.start_webrtc(OFFER)
            session._process.emit('item/started', {'threadId': session.thread_id,
                                                  'item': {'type': 'commandExecution', 'command': 'never-copy'}})
            self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')
            self.assertTrue(session._process.terminated)
            self.assertNotIn('never-copy', json.dumps(session.poll_events()))

    def test_mcp_inventory_blocks_unaddressable_names_new_servers_and_enabled_tools(self):
        for mode in ['unsafe_mcp_name', 'new_mcp_server', 'mcp_not_disabled']:
            with self.subTest(mode=mode):
                session = make_session(mode)
                with self.assertRaises(backend.CodexBackendError) as caught:
                    session.start_webrtc(OFFER)
                self.assertEqual(caught.exception.code, 'policy_rejected')
                if session._process is not None:
                    self.assertNotIn('thread/realtime/start', [m.get('method') for m in session._process.sent])
                    self.assertIsNotNone(session._process.poll())

    def test_control_mode_registers_only_fixed_home_tools_without_os_privileges(self):
        with make_session(read_only=False) as session:
            answer = session.start_webrtc(OFFER)
            self.assertFalse(answer['readOnly'])
            requests = {m.get('method'): m.get('params') for m in session._process.sent if 'method' in m}
            thread = requests['thread/start']
            self.assertEqual([t['name'] for t in thread['dynamicTools']],
                             ['get_home_status', 'set_device_power', 'get_home_devices', 'control_home_device'])
            self.assertEqual(thread['sandbox'], 'read-only')
            self.assertEqual(thread['approvalPolicy'], 'never')
            self.assertIn('mcp_servers.mock_server.enabled=false', session._process.command)
            self.assertIn('无需二次确认', thread['developerInstructions'])
            self.assertIn('逐字保留', thread['developerInstructions'])
            self.assertIn('疑问、否定', thread['developerInstructions'])
            self.assertTrue(requests['thread/realtime/start']['includeStartupContext'])
            schema = thread['dynamicTools'][1]['inputSchema']
            self.assertFalse(schema['additionalProperties'])
            self.assertEqual(schema['properties']['device']['enum'], ['oven_plug'])
            self.assertEqual(schema['properties']['power']['enum'], ['on', 'off'])
            self.assertEqual(schema['properties']['utterance']['maxLength'], 160)

    def test_readonly_mode_rejects_even_well_formed_power_request(self):
        with make_session() as session:
            session.start_webrtc(OFFER)
            tool_request(session, tool='set_device_power',
                         arguments={'device': 'oven_plug', 'power': 'on', 'utterance': '请打开烤箱插座'})
            self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')
            self.assertFalse(session._tools)

    def test_control_request_is_only_queued_with_exact_utterance_and_native_result(self):
        with make_session(read_only=False) as session:
            session.start_webrtc(OFFER)
            arguments = {'device': 'oven_plug', 'power': 'off', 'utterance': ' 请关闭烤箱插座。 '}
            tool_request(session, tool='set_device_power', arguments=arguments)
            event = wait_for(session, 'tool.request')
            self.assertEqual(event['tool'], 'set_device_power')
            self.assertEqual(event['arguments'], arguments)
            self.assertFalse(any(m.get('id') == 'server-call-1' for m in session._process.sent))
            session.submit_tool_result('call-1', dict(FIXTURE, operation='result_unverified'))
            response = next(m for m in session._process.sent if m.get('id') == 'server-call-1')
            result = json.loads(response['result']['contentItems'][0]['text'])
            self.assertEqual(result['operation'], 'result_unverified')
            self.assertEqual(wait_for(session, 'tool.completed')['tool'], 'set_device_power')
            with self.assertRaises(backend.CodexBackendError): session.submit_tool_result('call-1', FIXTURE)

    def test_control_argument_shape_limits_and_other_tools_are_rejected(self):
        valid = {'device': 'oven_plug', 'power': 'on', 'utterance': '请打开烤箱插座'}
        invalid = [[], None, {}, dict(valid, device='other_plug'), dict(valid, power='toggle'),
                   dict(valid, power=True), dict(valid, utterance=''), dict(valid, utterance='   '),
                   dict(valid, utterance='x' * 161), dict(valid, utterance='原话\n附加内容'),
                   dict(valid, utterance=123), dict(valid, url='https://untrusted.invalid')]
        for arguments in invalid:
            with self.subTest(argument_type=type(arguments).__name__):
                with make_session(read_only=False) as session:
                    session.start_webrtc(OFFER)
                    tool_request(session, tool='set_device_power', arguments=arguments)
                    self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')
                    self.assertFalse(session._tools)
        with make_session(read_only=False) as session:
            session.start_webrtc(OFFER)
            tool_request(session, tool='turn_on', arguments={})
            self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')

    def test_mode_must_be_explicit_boolean_and_cannot_mutate_public_property(self):
        for value in [None, 'false', 0, 1]:
            with self.assertRaises(backend.CodexBackendError): make_session(read_only=value)
        with make_session() as session:
            with self.assertRaises(AttributeError): session.read_only = False

    def test_operation_projection_does_not_invent_success_or_echo_arbitrary_strings(self):
        for operation in backend.OPERATIONS:
            clean = backend.sanitize_home_status(dict(FIXTURE, operation=operation), include_operation=True)
            self.assertEqual(clean['operation'], operation)
            self.assertNotIn('operation', backend.sanitize_home_status(dict(FIXTURE, operation=operation)))
        for operation in ['success', 'Bearer hidden-value', {'url': 'private'}]:
            self.assertNotIn('operation', backend.sanitize_home_status(dict(FIXTURE, operation=operation), include_operation=True))
        with make_session(read_only=False) as session:
            session.start_webrtc(OFFER)
            tool_request(session, tool='set_device_power',
                         arguments={'device': 'oven_plug', 'power': 'on', 'utterance': '请打开烤箱插座'})
            wait_for(session, 'tool.request')
            session.submit_tool_result('call-1', {'ok': False, 'error': 'explicit_single_command_required'})
            response = next(m for m in session._process.sent if m.get('id') == 'server-call-1')
            result = json.loads(response['result']['contentItems'][0]['text'])
            self.assertFalse(result['ok'])
            self.assertEqual(result['error'], 'explicit_single_command_required')
            self.assertNotIn('operation', result)


def live_fixture_probe(offer, apply_answer_and_ask_fixture, timeout=60):
    """Explicit opt-in integration helper; caller owns browser/audio and SDP.

    Callback receives the answer and must label its question as a demonstration.
    No actual home state, device, HA, or token is accessed by this helper.
    It intentionally does not claim RTP/audio or truthful real-device validation.
    """
    calls = 0
    with backend.CodexRealtimeSession() as session:
        answer = session.start_webrtc(offer, 'explicit-fixture-' + str(int(time.time())))
        apply_answer_and_ask_fixture(answer)
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for event in session.poll_events(1):
                if event['type'] == 'tool.request':
                    session.submit_tool_result(event['callId'], FIXTURE); calls += 1
                elif event['type'] in ('error', 'closed'):
                    return {'fixture': True, 'negotiated': True, 'toolCalls': calls, 'audioVerified': False, 'terminal': event['type']}
            if calls:
                return {'fixture': True, 'negotiated': True, 'toolCalls': calls, 'audioVerified': False}
        return {'fixture': True, 'negotiated': True, 'toolCalls': calls, 'audioVerified': False, 'timedOut': True}


if __name__ == '__main__':
    unittest.main()
