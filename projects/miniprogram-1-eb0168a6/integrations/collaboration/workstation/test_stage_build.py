import hashlib,io,json,sqlite3,tarfile,tempfile,unittest,uuid
from pathlib import Path
from stage_build import stage

class StageTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)/'controller';self.out=Path(self.tmp.name)/'staged';(self.root/'artifacts').mkdir(parents=True);self.id=str(uuid.uuid4());self.commit='a'*40
  self.art=self.root/'artifacts'/(self.id+'.tar')
  self.make_archive('site/index.html');self.checksum=hashlib.sha256(self.art.read_bytes()).hexdigest()
  self.db=sqlite3.connect(self.root/'delivery.db');self.db.execute('create table jobs(id text,kind text,state text,request text,result text)');self.put('succeeded',self.checksum)
 def tearDown(self):self.db.close();self.tmp.cleanup()
 def make_archive(self,name,link=False):
  with tarfile.open(self.art,'w') as t:
   v=tarfile.TarInfo(name)
   if link:v.type=tarfile.SYMTYPE;v.linkname='/etc/passwd';t.addfile(v)
   else:
    content=b'checked artifact';v.size=len(content);t.addfile(v,io.BytesIO(content))
 def put(self,state,sha):
  self.db.execute('delete from jobs');self.db.execute('insert into jobs values (?,?,?,?,?)',(self.id,'build',state,json.dumps({'key':'siyue-example','sha':self.commit}),json.dumps({'sha':self.commit,'digest':sha})));self.db.commit()
 def test_snapshot_preserves_commit_and_immutable_bytes(self):
  m=stage(self.root,self.out,self.id);self.assertEqual(m['commit'],self.commit);self.assertEqual(m['sha256'],self.checksum);self.assertTrue(m['testOnly']);self.assertEqual(stage(self.root,self.out,self.id),m)
 def test_failed_build_or_tampering_never_stages(self):
  self.put('failed',self.checksum)
  with self.assertRaises(ValueError):stage(self.root,self.out,self.id)
  self.put('succeeded',self.checksum);self.art.write_bytes(b'tampered')
  with self.assertRaises(ValueError):stage(self.root,self.out,self.id)
 def test_unsafe_archive_is_rejected_even_with_matching_hash(self):
  for name,link in [('../outside',False),('site/link',True)]:
   self.make_archive(name,link);self.put('succeeded',hashlib.sha256(self.art.read_bytes()).hexdigest())
   with self.assertRaises(ValueError):stage(self.root,self.out,self.id)
if __name__=='__main__':unittest.main()
