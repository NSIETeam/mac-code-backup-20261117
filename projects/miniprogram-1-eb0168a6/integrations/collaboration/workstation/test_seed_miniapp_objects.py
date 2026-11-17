import unittest
from seed_miniapp_objects import safe_key

class ObjectPathTests(unittest.TestCase):
    def test_object_key_is_preserved(self):self.assertEqual(str(safe_key('handouts/abc/source.pdf')),'handouts/abc/source.pdf')
    def test_escape_or_noncanonical_keys_are_rejected(self):
        for key in ['', '/etc/passwd', '../config.json', 'handouts/../../config', 'a//b', './source.pdf']:
            with self.subTest(key=key),self.assertRaises(ValueError):safe_key(key)

if __name__=='__main__':unittest.main()
