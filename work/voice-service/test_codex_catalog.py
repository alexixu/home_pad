"""Offline all-device protocol and redaction tests; never uses HA or a real child."""
import json
import unittest

import codex_backend as backend
from test_codex_backend import OFFER, make_session, tool_request, wait_for

REF = 'e_' + '1' * 24
REVISION = 'a' * 64


def catalog_fixture(device_count=1, entities_per_device=1):
    devices = []
    for d in range(device_count):
        entities = []
        for e in range(entities_per_device):
            entities.append({'ref': 'e_' + format(d * entities_per_device + e + 1, '024x'),
                'name': '客厅灯', 'domain': 'light', 'state': 'on', 'unit': '',
                'recorded_at': '2026-10-31T15:00:00+00:00',
                'actions': [{'action': 'power_off', 'label': '关闭', 'value_type': 'none'},
                            {'action': 'set_brightness', 'label': '亮度', 'value_type': 'number',
                             'min': 0, 'max': 100, 'step': 1}]})
        devices.append({'id': 'd_' + format(d + 1, '024x'), 'name': '演示灯', 'room': '客厅',
                        'model': 'fixture.light', 'available': True, 'entities': entities})
    return {'ok': True, 'fixture': True, 'catalog_revision': REVISION,
            'total_devices': device_count, 'matched_devices': device_count,
            'truncated': False, 'devices': devices}


def control_arguments(**updates):
    args = {'target_ref': REF, 'catalog_revision': REVISION, 'action': 'power_off',
            'value': None, 'utterance': ' 请关闭客厅灯。 '}
    args.update(updates)
    return args


class CodexCatalogTests(unittest.TestCase):
    def test_catalog_read_allowed_in_both_modes_and_results_stay_correlated(self):
        for readonly in (True, False):
            with self.subTest(readonly=readonly), make_session(read_only=readonly) as session:
                session.start_webrtc(OFFER)
                tool_request(session, tool='get_home_devices', arguments={'query': '客厅'})
                event = wait_for(session, 'tool.request')
                self.assertEqual(event['arguments'], {'query': '客厅'})
                self.assertEqual(event['sessionId'], session.session_id)
                raw = catalog_fixture()
                raw['token'] = 'top-secret'
                session.submit_tool_result('call-1', raw)
                reply = next(m for m in session._process.sent if m.get('id') == 'server-call-1')
                clean = json.loads(reply['result']['contentItems'][0]['text'])
                self.assertEqual(clean['catalog_revision'], REVISION)
                self.assertEqual(clean['devices'][0]['entities'][0]['actions'][1]['max'], 100)
                self.assertNotIn('top-secret', json.dumps(reply))
                with self.assertRaises(backend.CodexBackendError):
                    session.submit_tool_result('call-1', raw)

    def test_catalog_query_requires_exact_schema_and_bounded_text(self):
        session = make_session()
        for invalid in ({}, {'query': None}, {'query': []}, {'query': 'x' * 65},
                        {'query': '客厅\n'}, {'query': '\u202e隐藏'}, {'query': '', 'url': 'bad'}, []):
            with self.subTest(invalid=invalid):
                self.assertIsNone(session._tool_arguments('get_home_devices', invalid))
        self.assertEqual(session._tool_arguments('get_home_devices', {'query': ''}), {'query': ''})

    def test_readonly_never_exposes_or_queues_generic_control(self):
        with make_session() as session:
            session.start_webrtc(OFFER)
            thread = next(m['params'] for m in session._process.sent if m.get('method') == 'thread/start')
            self.assertEqual([tool['name'] for tool in thread['dynamicTools']], ['get_home_status', 'get_home_devices'])
            tool_request(session, tool='control_home_device', arguments=control_arguments())
            self.assertEqual(wait_for(session, 'error')['code'], 'tool_rejected')
            self.assertEqual(session._tools, {})

    def test_normal_control_is_queued_and_bound_to_requested_target(self):
        with make_session(read_only=False) as session:
            session.start_webrtc(OFFER)
            args = control_arguments()
            tool_request(session, tool='control_home_device', arguments=args)
            self.assertEqual(wait_for(session, 'tool.request')['arguments'], args)
            self.assertFalse(any(m.get('id') == 'server-call-1' for m in session._process.sent))
            with self.assertRaises(backend.CodexBackendError):
                session.submit_tool_result('call-1', {'ok': True, 'target_ref': 'e_' + '2' * 24,
                                                      'operation': 'command_sent'})
            self.assertIn('call-1', session._tools)
            session.submit_tool_result('call-1', {'ok': True, 'target_ref': REF, 'operation': 'command_sent',
                'name': '客厅灯', 'state': 'on', 'entity_id': 'light.private', 'url': 'https://secret.invalid'})
            reply = next(m for m in session._process.sent if m.get('id') == 'server-call-1')
            clean = json.loads(reply['result']['contentItems'][0]['text'])
            self.assertEqual(clean['target_ref'], REF)
            self.assertEqual(clean['operation'], 'command_sent')
            self.assertNotIn('private', json.dumps(reply))
            self.assertNotIn('secret.invalid', json.dumps(reply))

    def test_control_arguments_reject_arbitrary_targets_fields_types_and_values(self):
        session = make_session(read_only=False)
        invalid = [control_arguments(target_ref='light.kitchen'), control_arguments(target_ref='e_' + 'A' * 24),
            control_arguments(catalog_revision='b' * 63), control_arguments(catalog_revision='B' * 64),
            control_arguments(action='toggle'), control_arguments(action=[]), control_arguments(value=False),
            control_arguments(action='set_temperature', value=True),
            control_arguments(action='set_temperature', value=float('nan')),
            control_arguments(action='set_temperature', value=float('inf')),
            control_arguments(action='set_temperature', value=10 ** 1000),
            control_arguments(action='set_hvac_mode', value='x' * 65),
            control_arguments(action='set_hvac_mode', value='https://private.invalid'),
            control_arguments(action='set_hvac_mode', value=['heat']),
            control_arguments(action='set_muted', value=1), control_arguments(utterance=''),
            control_arguments(utterance='x' * 161), control_arguments(utterance='用户\n命令'),
            control_arguments(utterance='\ud800'), control_arguments(service='turn_off')]
        missing = control_arguments(); del missing['value']; invalid.append(missing)
        for args in invalid:
            with self.subTest(args=str(args)[:100]):
                self.assertIsNone(session._tool_arguments('control_home_device', args))
        for action, value in [('power_off', None), ('set_temperature', 23.5), ('set_muted', True),
                              ('set_hvac_mode', 'heat'), ('select_option', '睡眠')]:
            args = control_arguments(action=action, value=value)
            self.assertEqual(session._tool_arguments('control_home_device', args), args)

    def test_catalog_projection_drops_credentials_entity_ids_urls_and_unknown_fields(self):
        raw = catalog_fixture()
        device = raw['devices'][0]; entity = device['entities'][0]
        raw['token'] = 'must-not-return'
        device.update(room='http://private.invalid', name='Bearer must-not-return', registry_id='private')
        entity.update(entity_id='light.real', attributes={'password': 'private'}, state='light.real',
                      unit='secret=abcd', recorded_at='not a timestamp', name='x' * 97)
        entity['actions'][0]['url'] = 'https://private.invalid'
        clean = backend.sanitize_home_devices(raw)
        rendered = json.dumps(clean)
        for prohibited in ('must-not-return', 'private', 'light.real', 'secret=abcd', 'entity_id', 'attributes'):
            self.assertNotIn(prohibited, rendered)
        projected = clean['devices'][0]['entities'][0]
        self.assertEqual(projected['state'], '')
        self.assertIsNone(projected['recorded_at'])
        self.assertEqual(clean['total_devices'], 1)
        self.assertEqual(clean['matched_devices'], 1)

    def test_catalog_action_constraints_and_options_are_checked(self):
        raw = catalog_fixture()
        raw['devices'][0]['entities'][0]['actions'] = [
            {'action': 'set_temperature', 'label': '温度', 'value_type': 'number', 'min': 30, 'max': 10},
            {'action': 'set_brightness', 'label': '亮度', 'value_type': 'number', 'step': True},
            {'action': 'power_on', 'label': '开', 'value_type': 'boolean'},
            {'action': 'factory_reset', 'label': '重置', 'value_type': 'none'},
            {'action': 'select_option', 'label': '模式', 'value_type': 'string', 'options': [
                {'value': '睡眠', 'label': '睡眠', 'entity_id': 'switch.secret'},
                {'value': 'Bearer secret', 'label': 'bad'}, {'value': '睡眠', 'label': '重复'}]}]
        clean = backend.sanitize_home_devices(raw)
        actions = clean['devices'][0]['entities'][0]['actions']
        self.assertTrue(clean['truncated'])
        self.assertEqual(actions, [{'action': 'select_option', 'label': '模式', 'value_type': 'string',
                                    'options': [{'value': '睡眠', 'label': '睡眠'}]}])

    def test_numeric_action_units_preserved_without_leaking_identifiers_or_secrets(self):
        for unit in ('°C', '°F', '%', 'K'):
            raw = catalog_fixture()
            raw['devices'][0]['entities'][0]['actions'][1]['unit'] = unit
            clean = backend.sanitize_home_devices(raw)
            self.assertEqual(clean['devices'][0]['entities'][0]['actions'][1]['unit'], unit)
        for unit in ('sensor.private', 'Bearer do-not-send', 'https://private.invalid', 'x' * 25):
            raw = catalog_fixture()
            raw['devices'][0]['entities'][0]['actions'][1]['unit'] = unit
            clean = backend.sanitize_home_devices(raw)
            self.assertEqual(clean['devices'][0]['entities'][0]['actions'][1]['unit'], '')

    def test_miot_model_codes_survive_without_exposing_entity_ids_urls_or_credentials(self):
        raw = catalog_fixture()
        for model in ('yeelink.light.v1', 'lumi.aircondition.acn05', 'zhimi.airpurifier.ma4'):
            raw['devices'][0]['model'] = model
            self.assertEqual(backend.sanitize_home_devices(raw)['devices'][0]['model'], model)
        for model in ('switch.private', 'https://private.invalid', 'http.private.invalid/path',
                      'token.air.demo', 'Bearer private', 'sk-abcdefghijkl.demo', 'a' * 64, 'x' * 97,
                      '192.168.1.1', 'www.private.invalid', 'aa:bb:cc:dd:ee:ff'):
            raw['devices'][0]['model'] = model
            self.assertEqual(backend.sanitize_home_devices(raw)['devices'][0]['model'], '', model)
        raw['devices'][0].update(model='yeelink.light.v1', name='switch.private')
        self.assertEqual(backend.sanitize_home_devices(raw)['devices'][0]['name'], '')

    def test_catalog_device_entity_and_total_limits_preserve_true_source_counts(self):
        raw = catalog_fixture(70, 70)
        raw.update(total_devices=100, matched_devices=70, total_entities=7000, matched_entities=4900)
        clean = backend.sanitize_home_devices(raw)
        self.assertEqual(clean['total_devices'], 100)
        self.assertEqual(clean['matched_devices'], 70)
        self.assertEqual(clean['total_entities'], 7000)
        self.assertEqual(clean['matched_entities'], 4900)
        self.assertTrue(clean['truncated'])
        self.assertEqual(len(clean['devices']), 64)
        self.assertEqual(sum(len(d['entities']) for d in clean['devices']), 256)
        self.assertTrue(all(len(d['entities']) <= 64 for d in clean['devices']))

    def test_catalog_utf8_byte_budget_truncates_without_fabricating_completeness(self):
        raw = catalog_fixture(4, 64)
        options = [{'value': str(i), 'label': '模' * 64} for i in range(64)]
        for device in raw['devices']:
            for entity in device['entities']:
                entity['actions'] = [{'action': 'select_option', 'label': '模式', 'value_type': 'string',
                                      'options': options}]
        clean = backend.sanitize_home_devices(raw)
        self.assertLessEqual(len(json.dumps(clean, ensure_ascii=False, separators=(',', ':')).encode()), 128 * 1024)
        self.assertTrue(clean['truncated'])
        self.assertEqual(clean['total_devices'], 4)
        self.assertEqual(clean['matched_devices'], 4)
        self.assertLess(sum(len(d['entities']) for d in clean['devices']), 256)

    def test_invalid_catalog_counters_revision_and_duplicates_cannot_claim_full_catalog(self):
        for changes in [{'total_devices': True}, {'matched_devices': -1}, {'matched_devices': 0},
                        {'total_devices': 0}, {'catalog_revision': 'secret'}, {'truncated': 'false'}]:
            with self.subTest(changes=changes), self.assertRaises(backend.CodexBackendError):
                backend.sanitize_home_devices(dict(catalog_fixture(), **changes))
        raw = catalog_fixture(2)
        raw['devices'][1]['entities'][0]['ref'] = raw['devices'][0]['entities'][0]['ref']
        clean = backend.sanitize_home_devices(raw)
        self.assertTrue(clean['truncated'])
        self.assertEqual(sum(len(d['entities']) for d in clean['devices']), 1)
        self.assertEqual(backend.sanitize_home_devices({'ok': False, 'error': 'catalog_not_imported'})['error'],
                         'catalog_not_imported')
        partial = dict(catalog_fixture(), total_devices=20, matched_devices=10, truncated=False)
        self.assertTrue(backend.sanitize_home_devices(partial)['truncated'])
        weather = catalog_fixture()
        weather['devices'][0]['entities'][0].update(domain='weather', actions=[])
        self.assertEqual(backend.sanitize_home_devices(weather)['devices'][0]['entities'][0]['domain'], 'weather')

    def test_control_results_do_not_invent_success_and_require_success_target(self):
        for operation in ('command_sent', 'result_unverified', 'already_in_target_state', 'target_state_observed'):
            clean = backend.sanitize_device_control({'ok': True, 'target_ref': REF, 'operation': operation}, REF)
            self.assertEqual(clean['operation'], operation)
        for raw in [{'ok': True}, {'ok': True, 'target_ref': REF, 'operation': 'success'},
                    {'ok': True, 'target_ref': REF, 'operation': 'rejected'}, {'ok': 1},
                    {'ok': False, 'target_ref': 'e_' + '2' * 24}]:
            with self.subTest(raw=raw), self.assertRaises(backend.CodexBackendError):
                backend.sanitize_device_control(raw, REF)
        clean = backend.sanitize_device_control({'ok': False, 'error': 'catalog_changed', 'operation': 'rejected'}, REF)
        self.assertFalse(clean['ok'])
        self.assertEqual(clean['error'], 'catalog_changed')
        self.assertEqual(clean['target_ref'], REF)

    def test_normal_prompt_and_schema_preserve_native_evidence_policy(self):
        with make_session(read_only=False) as session:
            session.start_webrtc(OFFER)
            thread = next(m['params'] for m in session._process.sent if m.get('method') == 'thread/start')
            tools = {t['name']: t['inputSchema'] for t in thread['dynamicTools']}
            self.assertFalse(tools['control_home_device']['additionalProperties'])
            self.assertEqual(set(tools['control_home_device']['required']), set(control_arguments()))
            self.assertEqual(tools['get_home_devices']['properties']['query']['maxLength'], 64)
            self.assertEqual(thread['sandbox'], 'read-only')
            for phrase in ('先查 get_home_devices', '无需二次确认', '每会话最多一次控制',
                           'VoiceIntentEvidence', '只读传感器', 'command_sent'):
                self.assertIn(phrase, thread['developerInstructions'])


if __name__ == '__main__':
    unittest.main()
