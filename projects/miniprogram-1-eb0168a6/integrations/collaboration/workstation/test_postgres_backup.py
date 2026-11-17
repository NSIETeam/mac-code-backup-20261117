import hashlib,pathlib,tempfile,unittest
from postgres_backup import validate_snapshot,NAME

class BackupIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=pathlib.Path(self.tmp.name)
        (self.root/'database-0.dump').write_bytes(b'private test fixture')
        self.manifest={'sha256':{'database-0.dump':hashlib.sha256(b'private test fixture').hexdigest()},'databases':[{'name':'example','file':'database-0.dump'}]}
    def test_intact_snapshot_is_accepted(self):validate_snapshot(self.root,self.manifest)
    def test_corruption_is_rejected(self):
        (self.root/'database-0.dump').write_bytes(b'damaged')
        with self.assertRaisesRegex(ValueError,'checksum'):validate_snapshot(self.root,self.manifest)
    def test_parent_path_is_rejected(self):
        self.manifest['sha256']['../private']='0'*64
        with self.assertRaisesRegex(ValueError,'path'):validate_snapshot(self.root,self.manifest)
    def test_symlink_is_rejected(self):
        (self.root/'database-0.dump').rename(self.root/'other')
        (self.root/'database-0.dump').symlink_to(self.root/'other')
        with self.assertRaisesRegex(ValueError,'regular'):validate_snapshot(self.root,self.manifest)
    def test_duplicate_database_file_is_rejected(self):
        self.manifest['databases'].append({'name':'different','file':'database-0.dump'})
        with self.assertRaisesRegex(ValueError,'duplicated'):validate_snapshot(self.root,self.manifest)
    def test_unchecksummed_dump_is_rejected(self):
        self.manifest['databases'][0]['file']='database-1.dump'
        with self.assertRaisesRegex(ValueError,'lacks'):validate_snapshot(self.root,self.manifest)
    def test_retention_cannot_match_manual_folders(self):
        self.assertTrue(NAME.fullmatch('20261117T020928Z-7fc984'))
        for name in ['latest','.partial-20261117T020928Z-7fc984','manual-restore','20261117T020928Z-7fc984/../data']:
            self.assertIsNone(NAME.fullmatch(name))

if __name__=='__main__':unittest.main()
