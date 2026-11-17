import unittest,tempfile,pathlib
from native_pg_files_backup import safe_relative,link_record

class BackupPathTests(unittest.TestCase):
    def test_rejects_escape_and_ambiguous_members(self):
        for name in ['/etc/passwd','../outside','opt/../../outside','opt//file','./file','','.']:
            with self.subTest(name=name),self.assertRaises(ValueError):safe_relative(name)
    def test_preserves_valid_bundle_and_resident_paths(self):
        for name in ['opt/siyue-miniapp/data/objects/print-files/abc/document.pdf','team--repo.git.bundle']:
            self.assertEqual(safe_relative(name).as_posix(),name)
    def test_keeps_internal_tool_links_but_rejects_external_links(self):
        with tempfile.TemporaryDirectory() as folder:
            root=pathlib.Path(folder);(root/'native_dispatch.py').write_text('print(1)')
            link=root/'pdftotext';link.symlink_to('native_dispatch.py')
            self.assertEqual(link_record(root,link)['target'],'native_dispatch.py')
            link.unlink();link.symlink_to('/etc/passwd')
            with self.assertRaises(ValueError):link_record(root,link)
            link.unlink();link.symlink_to('../outside')
            with self.assertRaises(ValueError):link_record(root,link)

if __name__=='__main__':unittest.main()
