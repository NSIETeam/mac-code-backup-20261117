import base64,hashlib,io,json,pathlib,sys,tarfile,tempfile,unittest
from types import SimpleNamespace
from unittest.mock import Mock,patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
import workstation_build as b
class BuildTests(unittest.TestCase):
 def archive(self,name='index.html'):
  f=io.BytesIO()
  with tarfile.open(fileobj=f,mode='w') as tar:
   item=tarfile.TarInfo(name);item.size=2;tar.addfile(item,io.BytesIO(b'ok'))
  return f.getvalue()
 def test_tampered_or_unsafe_remote_artifact_never_replaces_existing(self):
  with tempfile.TemporaryDirectory() as td:
   target=pathlib.Path(td)/'artifact.tar';target.write_bytes(b'previous')
   for raw,digest in [(self.archive(),'a'*64),(self.archive('../escape'),None)]:
    digest=digest or hashlib.sha256(raw).hexdigest()
    with patch.object(b.w,'rpc',return_value=dict(offset=0,size=len(raw),data=base64.b64encode(raw).decode())):
     with self.assertRaises(ValueError):b.download({},dict(), 'artifact',target,digest)
    self.assertEqual(target.read_bytes(),b'previous')
 def test_reconnect_reuses_prepared_source_instead_of_resolving_new_heads(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);folder=root/'workstation-builds'/'job';folder.mkdir(parents=True);archive=folder/'source.tar.gz';archive.write_bytes(b'source')
   q=dict(key='test-project',commit='a'*40,sourceDigest=hashlib.sha256(b'source').hexdigest());(folder/'manifest.json').write_text(json.dumps(q))
   d=SimpleNamespace(ROOT=root,file_digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest(),recipes=Mock())
   self.assertEqual(b.prepare(d,{}, {},'test-project','job','a'*40)[1],q);d.recipes.assemble.assert_not_called()
 def test_remote_opt_in_is_separate_from_preview_routing(self):
  d=SimpleNamespace(settings=lambda:dict(workstation=dict(enabled=True,projects=['test-project'])))
  self.assertIsNone(b.config(d,'test-project'))
 def test_offline_build_is_queued_without_local_fallback(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);d=SimpleNamespace(ROOT=root,settings=lambda:dict(workstation=dict(enabled=True,buildProjects=['test-project'])),sha=lambda x:x,memory_available=lambda:2*1024**3,update=Mock())
   with patch.object(b,'prepare',return_value=(root,{})),patch.object(b.w,'rpc',side_effect=b.w.Unavailable('offline')):
    self.assertTrue(b.build(d,{}, {},'test-project','job',dict(sha='a'*40)))
   self.assertEqual(d.update.call_args.args[1],'queued')
if __name__=='__main__':unittest.main()
