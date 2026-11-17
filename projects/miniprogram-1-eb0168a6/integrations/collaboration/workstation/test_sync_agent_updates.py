import json,unittest
from sync_agent_updates import validate

class UpdateManifestTests(unittest.TestCase):
    def setUp(self):self.m={'version':'1.2.3','sha256':'a'*64,'fileName':'agent-1.2.3.exe','size':500}
    def test_root_and_device_manifests(self):
        for path in ['release.json','devices/01234567-1234-1234-1234-0123456789ab/release.json']:
            self.assertEqual(validate(path,json.dumps(self.m)),self.m)
    def test_rejects_path_and_filename_escape(self):
        for path in ['/release.json','../release.json','devices/../../release.json','other/release.json']:
            with self.assertRaises(ValueError):validate(path,json.dumps(self.m))
        for name in ['../agent.exe','x/y.exe','x.exe;touch a','x.dll','.incoming.exe']:
            with self.assertRaises(ValueError):validate('release.json',json.dumps({**self.m,'fileName':name}))
    def test_rejects_unusable_package_metadata(self):
        for key,value in [('size',True),('size',0),('size',314572801),('sha256','x'*64),('version','latest')]:
            with self.assertRaises(ValueError):validate('release.json',json.dumps({**self.m,key:value}))

if __name__=='__main__':unittest.main()
