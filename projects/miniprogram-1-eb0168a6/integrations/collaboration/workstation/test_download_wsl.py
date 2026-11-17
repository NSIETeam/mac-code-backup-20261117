import hashlib,io,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from download_wsl import download
class Response(io.BytesIO):
 def __enter__(self):return self
 def __exit__(self,*a):self.close()
class DownloadTest(unittest.TestCase):
 def test_verified_and_corrupt_download(self):
  b=b'fixed image';s={'size':len(b),'sha256':hashlib.sha256(b).hexdigest()};c={'url':'https://example.invalid/node','token':'fixture'}
  with tempfile.TemporaryDirectory() as d:
   with patch('download_wsl.open_pinned',side_effect=lambda *a,**k:Response(b)):
    p=download(c,Path(d),s);self.assertEqual(p.read_bytes(),b);self.assertEqual(download(c,Path(d),s),p)
   p.write_bytes(b'changed')
   with self.assertRaises(ValueError):download(c,Path(d),s)
 def test_insecure_transport_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(ValueError):download({'url':'http://bad','token':'x'},Path(d),{'sha256':'a'*64,'size':10})
if __name__=='__main__':unittest.main()
