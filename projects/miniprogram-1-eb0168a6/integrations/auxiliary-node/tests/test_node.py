import hashlib,importlib.util,io,json,sqlite3,tempfile,threading,unittest,time
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parents[1]))
spec=importlib.util.spec_from_file_location('node',Path(__file__).parents[1]/'node.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Response(io.BytesIO):pass
class ReplicaTests(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.n=object.__new__(m.Node);self.n.root=Path(self.t.name);(self.n.root/'blobs').mkdir();self.n.c={};self.n.stats={'bytes':0,'objects':0};self.n.stop=threading.Event();self.db=sqlite3.connect(':memory:');self.db.execute('CREATE TABLE replicas(key TEXT,sha TEXT,size INTEGER,verified REAL,PRIMARY KEY(key,sha))')
  self.data=b'%PDF test';self.sha=hashlib.sha256(self.data).hexdigest();self.item=dict(key='handouts/a/source.pdf',sha256=self.sha,size=len(self.data));self.n.request=lambda *a,**k:Response(self.data)
 def tearDown(self):self.db.close();self.t.cleanup()
 def test_verified_atomic_publication(self):
  self.n.replicate(self.item,self.db);p=self.n.root/'blobs'/self.sha[:2]/self.sha;self.assertEqual(p.read_bytes(),self.data);self.assertEqual(self.db.execute('SELECT count(*) FROM replicas').fetchone()[0],1);self.assertFalse(p.with_suffix('.partial').exists())
 def test_wrong_bytes_do_not_publish(self):
  self.n.request=lambda *a,**k:Response(b'bad data!')
  with self.assertRaises(ValueError):self.n.replicate(self.item,self.db)
  self.assertEqual(self.db.execute('SELECT count(*) FROM replicas').fetchone()[0],0);self.assertFalse((self.n.root/'blobs'/self.sha[:2]/self.sha).exists())
 def test_corrupt_old_cache_is_repaired(self):
  self.n.replicate(self.item,self.db);p=self.n.root/'blobs'/self.sha[:2]/self.sha;p.write_bytes(b'x'*len(self.data));self.db.execute('UPDATE replicas SET verified=?',(time.time()-90000,));self.db.commit();self.n.replicate(self.item,self.db);self.assertEqual(p.read_bytes(),self.data)
 def test_quota_preserves_reserve(self):
  self.n.c['quotaBytes']=1
  with self.assertRaises(OSError):self.n.replicate(self.item,self.db)
if __name__=='__main__':unittest.main()
