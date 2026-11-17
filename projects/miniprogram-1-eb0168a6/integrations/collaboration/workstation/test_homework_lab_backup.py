import io,json,pathlib,sqlite3,tarfile,tempfile,unittest
from unittest.mock import patch
import homework_lab_snapshot as s
import homework_lab_backup as b

class Backup(unittest.TestCase):
    def test_only_engine_metadata_may_be_added_to_environment(self):
        self.assertEqual(s.verify_environment({'APP':'same'},{'APP':'same','container':'podman','HOSTNAME':'generated','HOME':'/app'}),['HOME','HOSTNAME','container'])
        for actual in [{'APP':'changed'},{'APP':'same','NEW_SETTING':'unknown'}]:
            with self.assertRaises(ValueError):s.verify_environment({'APP':'same'},actual)

    def fixture(self,root):
        data=root/'data';data.mkdir();(data/'keys').mkdir();(data/'keys/key.xml').write_text('private-key-test')
        db=sqlite3.connect(data/'isolated.db');db.execute('PRAGMA journal_mode=WAL');db.execute('CREATE TABLE photos(id TEXT, bytes BLOB)');db.execute('INSERT INTO photos VALUES (?,?)',('one',b'photo-body'));db.commit();return data,db

    def test_online_backup_includes_committed_wal_and_excludes_uncommitted(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td);data,db=self.fixture(root)
            db.execute('INSERT INTO photos VALUES (?,?)',('two',b'new-photo'));db.commit()
            db.execute('INSERT INTO photos VALUES (?,?)',('uncommitted',b'not-visible'))
            receipt=s.snapshot(data,root/'snapshots',b'KEY=private\n',{'image':'fixed'})
            manifest=b.unpack(receipt['file'],root/'restore')
            restored=sqlite3.connect(root/'restore/data/isolated.db')
            self.assertEqual(restored.execute('SELECT id,bytes FROM photos ORDER BY id').fetchall(),[('one',b'photo-body'),('two',b'new-photo')])
            self.assertEqual((root/'restore/data/keys/key.xml').read_text(),'private-key-test')
            self.assertEqual(manifest['database']['photos']['rows'],2)
            restored.close();db.rollback();db.close()

    def test_asset_change_does_not_publish_archive(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td);data,db=self.fixture(root);original=s.shutil.copytree
            def change(*args,**kwargs):
                result=original(*args,**kwargs);(data/'keys/key.xml').write_text('changed-key');return result
            with patch.object(s.shutil,'copytree',side_effect=change):
                with self.assertRaisesRegex(ValueError,'Assets changed'):s.snapshot(data,root/'snapshots',b'',{})
            self.assertEqual(list((root/'snapshots').iterdir()),[]);db.close()

    def test_tampered_archive_is_rejected_without_rotating_good_backup(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td);data,db=self.fixture(root);receipt=s.snapshot(data,root/'snapshots',b'A=1\n',{})
            staging=root/'bad';b.unpack(receipt['file'],staging);(staging/'data/keys/key.xml').write_text('tampered')
            archive=root/'bad.tar.gz'
            with tarfile.open(archive,'w:gz') as t:
                for p in staging.iterdir():t.add(p,arcname=p.name)
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):b.unpack(archive,root/'rejected')
            self.assertFalse((root/'latest').exists());db.close()

    def test_tar_links_and_traversal_are_rejected(self):
        for name,kind in [('../escape','file'),('data/link','link')]:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as td:
                root=pathlib.Path(td);archive=root/'bad.tar.gz'
                with tarfile.open(archive,'w:gz') as t:
                    m=tarfile.TarInfo(name)
                    if kind=='link':m.type=tarfile.SYMTYPE;m.linkname='/etc/passwd';t.addfile(m)
                    else:m.size=1;t.addfile(m,io.BytesIO(b'x'))
                with self.assertRaises(ValueError):b.unpack(archive,root/'target')

    def test_retention_only_rotates_completed_owned_snapshots(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td)
            for name in ['20261101T000000Z-aaaaaaaa','20261102T000000Z-bbbbbbbb']:
                p=root/name;p.mkdir();(p/'COMPLETE').write_text('ok')
            partial=root/'unfinished.partial';partial.mkdir()
            unknown=root/'do-not-delete';unknown.mkdir()
            pending=root/'new.partial';pending.mkdir()
            with patch.object(b,'ROOT',root),patch.object(b,'KEEP',2):b.commit(pending,'20261103T000000Z-cccccccc')
            self.assertFalse((root/'20261101T000000Z-aaaaaaaa').exists())
            self.assertTrue(partial.exists() and unknown.exists())
            self.assertEqual((root/'latest').resolve().name,'20261103T000000Z-cccccccc')

if __name__=='__main__':unittest.main()
