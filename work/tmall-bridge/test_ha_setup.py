import unittest
import ha_setup
import deploy


class ConfigurationScopeTests(unittest.TestCase):
    def test_physical_device_service_paths_are_refused_before_network(self):
        api=ha_setup.API.__new__(ha_setup.API)
        for path in ['/api/services/cover/open_cover','/api/services/cover/close_cover',
                     '/api/services/cover/stop_cover','/api/services/switch/turn_off',
                     '/api/services/switch/turn_on','/api/services/homeassistant/toggle']:
            with self.assertRaises(ValueError):
                api.http(path,'POST',{'entity_id':'cover.example'})

    def test_cover_actions_only_write_the_owned_command_helper(self):
        for target in ha_setup.TARGETS:
            options=ha_setup.cover_options(target)
            self.assertNotIn('set_cover_position',options)
            for action in ['open','close','stop']:
                sequence=options[action+'_cover']
                self.assertEqual(sequence[0]['condition'],'template')
                self.assertIn("r.get('ready',false)",sequence[0]['value_template'])
                self.assertEqual(sequence[1]['action'],'input_text.set_value')
                self.assertEqual(sequence[1]['target'],{'entity_id':ha_setup.COMMAND})
                self.assertIn('"target":"'+target+'"',sequence[1]['data']['value'])
                self.assertIn('"action":"'+action+'"',sequence[1]['data']['value'])

    def test_helper_creation_has_exact_name_scope(self):
        api=ha_setup.API.__new__(ha_setup.API)
        with self.assertRaises(ValueError):
            api.ws('input_text/create',name='unrelated')
        with self.assertRaises(ValueError):
            api.ws('call_service',domain='cover',service='open_cover')

    def test_availability_requires_live_ready_observation(self):
        for target in ha_setup.TARGETS:
            template=ha_setup.cover_options(target)['additional_options']['availability']
            for guard in ["r.get('ready',false)","r.get('online',false)","r.get('observed',0)",'< 120']:
                self.assertIn(guard,template)

    def test_process_ownership_requires_uid_class_and_exact_configuration(self):
        row=['1234','2000','app_process','app_process','/system/bin','TmallCurtainBridge',deploy.REMOTE+'/runtime-config.json']
        self.assertTrue(deploy.owned_process([row],1234))
        for index,value in [(0,'9999'),(1,'1000'),(5,'UnrelatedService'),(6,'/other/config.json')]:
            changed=list(row);changed[index]=value
            self.assertFalse(deploy.owned_process([changed],1234))

    def test_stale_pid_without_a_live_process_is_not_owned(self):
        self.assertFalse(deploy.owned_process([],1234))
        self.assertFalse(deploy.owned_process([['1234','2000','app_process']],1234))


if __name__=='__main__':unittest.main()
