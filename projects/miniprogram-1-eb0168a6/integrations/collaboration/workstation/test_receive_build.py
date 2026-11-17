import hashlib,io,tarfile,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
from receive_build import receive

class Response(io.BytesIO):
 def __enter__(self):return self
 def __exit__(self,*args):self.close()
class ReceiveTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.bid=str(uuid.uuid4());self.cfg={'url':'https://example.invalid/aux','token':'fixture'}
 def tearDown(self):self.tmp.cleanup()
 def archive(self,name='site/index.html'):
  with io.BytesIO() as b:
   with tarfile.open(fileobj=b,mode='w') as t:
    c=b'fixed output';i=tarfile.TarInfo(name);i.size=len(c);t.addfile(i,io.BytesIO(c))
   return b.getvalue()
 def manifest(self,b):return dict(schema=1,project='siyue-example',buildId=self.bid,commit='a'*40,sha256=hashlib.sha256(b).hexdigest(),size=len(b),testOnly=True)
 def test_verified_build_staged_without_execution(self):
  b=self.archive();m=self.manifest(b)
  with patch('receive_build.open_pinned',side_effect=lambda *a,**k:Response(b)):
   self.assertEqual(receive(self.cfg,m,self.root),m);self.assertEqual((self.root/self.bid/'files/site/index.html').read_bytes(),b'fixed output');self.assertEqual(receive(self.cfg,m,self.root),m)
 def test_corrupt_download_and_traversal_rejected(self):
  b=self.archive();m=self.manifest(b)
  with patch('receive_build.open_pinned',side_effect=lambda *a,**k:Response(b'bad')):
   with self.assertRaises(ValueError):receive(self.cfg,m,self.root)
  b=self.archive('../escape');m=self.manifest(b)
  with patch('receive_build.open_pinned',side_effect=lambda *a,**k:Response(b)):
   with self.assertRaises(ValueError):receive(self.cfg,m,self.root)
  self.assertFalse((self.root.parent/'escape').exists())
if __name__=='__main__':unittest.main()
