import hashlib,io,json,pathlib,sys,tarfile,tempfile,time,unittest,uuid
from types import SimpleNamespace
from unittest.mock import Mock,patch
import runner

class RunnerTest(unittest.TestCase):
 def test_visible_page_heartbeat_protects_slot_but_old_version_does_not(self):
  with tempfile.TemporaryDirectory() as td,patch.object(runner,'ROOT',pathlib.Path(td)):
   now=time.time();states=[]
   for i in range(4):
    p=pathlib.Path(td)/'slots'/('project-'+str(i));p.mkdir(parents=True)
    state=p/'preview-runtime.json';state.write_text(json.dumps(dict(key='project-'+str(i),buildId='current',container='preview-'+str(i),usedAt=now-180,viewerAt=now-180)));states.append(state)
   self.assertFalse(runner.touch('project-0','old')['touched'])
   self.assertEqual(json.loads(states[0].read_text())['viewerAt'],now-180)
   for i in range(4):self.assertTrue(runner.touch('project-'+str(i),'current')['touched'])
   engine=SimpleNamespace(run=Mock(),memory_available=Mock(return_value=8*1024**3))
   with self.assertRaisesRegex(ValueError,'正在使用'):runner.preview_slot('project-new',engine)
   engine.run.assert_not_called()
 def test_preview_pool_preserves_active_viewers_and_only_reclaims_idle_slot(self):
  with tempfile.TemporaryDirectory() as td,patch.object(runner,'ROOT',pathlib.Path(td)):
   now=time.time();states=[]
   for i in range(4):
    p=pathlib.Path(td)/'slots'/('project-'+str(i));p.mkdir(parents=True)
    state=p/'preview-runtime.json';state.write_text(json.dumps(dict(container='preview-'+str(i),usedAt=now,viewerAt=now)));states.append(state)
   engine=SimpleNamespace(run=Mock(),memory_available=Mock(return_value=8*1024**3))
   with self.assertRaisesRegex(ValueError,'正在使用'):runner.preview_slot('project-new',engine)
   engine.run.assert_not_called()
   states[0].write_text(json.dumps(dict(container='preview-0',usedAt=now-180,viewerAt=now-180)))
   runner.preview_slot('project-new',engine)
   engine.run.assert_called_once_with(['docker','rm','-f','preview-0'],timeout=30)
   self.assertFalse(states[0].exists());self.assertTrue(all(p.exists() for p in states[1:]))
 def archive(self,name='index.html',data=b'checked release'):
  out=io.BytesIO()
  with tarfile.open(fileobj=out,mode='w') as t:
   item=tarfile.TarInfo(name);item.size=len(data);t.addfile(item,io.BytesIO(data))
  return out.getvalue()
 def request(self,data):return dict(key='test-project',buildId=str(uuid.uuid4()),digest=hashlib.sha256(data).hexdigest(),size=len(data),commit='a'*40)
 def test_only_hash_verified_nontraversing_artifact_can_become_release(self):
  with tempfile.TemporaryDirectory() as td,patch.object(runner,'ROOT',pathlib.Path(td)):
   raw=self.archive();q=self.request(raw)
   runner.receive(q,io.BytesIO(raw))
   p=pathlib.Path(td)/'sites/test-project/versions'/q['buildId']
   self.assertEqual((p/'index.html').read_bytes(),b'checked release')
   self.assertEqual(json.loads((p/'__siyue_version.json').read_text())['sha'],'a'*40)
   for raw in (self.archive('../../escape'),self.archive()[:-1]):
    q=self.request(raw)
    q['digest']='b'*64
    with self.assertRaises(ValueError):runner.receive(q,io.BytesIO(raw))
    self.assertFalse((pathlib.Path(td)/'sites/test-project/versions'/q['buildId']).exists())
   raw=self.archive('../../escape');q=self.request(raw)
   with self.assertRaises(ValueError):runner.receive(q,io.BytesIO(raw))
   self.assertFalse((pathlib.Path(td)/'escape').exists())
 def test_immutable_release_cannot_be_rebound_to_another_commit(self):
  with tempfile.TemporaryDirectory() as td,patch.object(runner,'ROOT',pathlib.Path(td)):
   raw=self.archive();q=self.request(raw);runner.receive(q,io.BytesIO(raw))
   q['commit']='b'*40
   with self.assertRaisesRegex(ValueError,'collision'):runner.receive(q,io.BytesIO(raw))

if __name__=='__main__':unittest.main()
