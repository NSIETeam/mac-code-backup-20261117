import hashlib,io,json,pathlib,tempfile,unittest,uuid,os,subprocess,stat,shutil
from unittest.mock import patch
import build_worker as b
from build_sandbox import memory_bytes
class WorkerTests(unittest.TestCase):
 @unittest.skipUnless(shutil.which('node'), 'Node is required for the pinned-image compatibility test')
 def test_entry_supports_pinned_node_image_without_sleep(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);bin=root/'bin';bin.mkdir()
   for name in ('mkdir','node'):
    executable=shutil.which(name);self.assertIsNotNone(executable);(bin/name).symlink_to(executable)
   result=subprocess.run(['/bin/sh','-c',b.ENTRY_SCRIPT,'entry','/bin/sh','-c','printf "%s" "$1"; exit 19','command','literal $value'],env={**os.environ,'PATH':str(bin),'XDG_RUNTIME_DIR':str(root/'runtime')},capture_output=True,text=True,timeout=10)
   self.assertEqual(result.returncode,19);self.assertEqual(result.stdout,'literal $value')
   self.assertEqual(stat.S_IMODE((root/'runtime').stat().st_mode),0o700)
 def test_entry_supports_shell_only_images_and_preserves_literal_arguments(self):
  with tempfile.TemporaryDirectory() as td:
   runtime=pathlib.Path(td)/'runtime with spaces';marker=pathlib.Path(td)/'unexpected'
   literal='$(touch '+str(marker)+'); quoted " argument'
   result=subprocess.run(['/bin/sh','-c',b.ENTRY_SCRIPT,'entry','/bin/sh','-c','printf "%s" "$1"; exit 19','command',literal],env={**os.environ,'XDG_RUNTIME_DIR':str(runtime)},capture_output=True,text=True)
   self.assertEqual(result.returncode,19);self.assertEqual(result.stdout,literal)
   self.assertEqual(stat.S_IMODE(runtime.stat().st_mode),0o700);self.assertFalse(marker.exists())
 def test_entry_does_not_run_command_if_runtime_directory_cannot_be_created(self):
  with tempfile.TemporaryDirectory() as td:
   runtime=pathlib.Path(td)/'file';runtime.write_text('occupied');marker=pathlib.Path(td)/'unexpected'
   result=subprocess.run(['/bin/sh','-c',b.ENTRY_SCRIPT,'entry','touch',str(marker)],env={**os.environ,'XDG_RUNTIME_DIR':str(runtime)},capture_output=True,text=True)
   self.assertNotEqual(result.returncode,0);self.assertFalse(marker.exists())
 def test_explicit_retry_preserves_source_and_rejects_completed_build(self):
  with tempfile.TemporaryDirectory() as td,patch.object(b,'BASE',pathlib.Path(td)),patch.object(b,'config',return_value=dict(projects={'test-project':{'mode':'package'}},recipeVersion='a'*64)):
   raw=b'fixture';q=dict(jobId=str(uuid.uuid4()),key='test-project',commit='b'*40,sourceDigest=hashlib.sha256(raw).hexdigest(),recipeVersion='a'*64,size=len(raw))
   b.receive(q,io.BytesIO(raw));folder=b.jobpath(q['jobId']);row=b.state(folder)
   b.atomic(folder/'state.json',dict(row,state='failed'))
   self.assertEqual(b.dispatch(dict(q,action='build-retry'),io.BytesIO())['state'],'retry-requested')
   self.assertEqual((folder/'source.tar.gz').read_bytes(),raw)
   b.atomic(folder/'state.json',dict(row,state='succeeded'))
   with self.assertRaisesRegex(ValueError,'Only failed'):b.dispatch(dict(q,action='build-retry'),io.BytesIO())
 def test_source_id_cannot_be_reused_or_truncated(self):
  with tempfile.TemporaryDirectory() as td,patch.object(b,'BASE',pathlib.Path(td)),patch.object(b,'config',return_value=dict(projects={'test-project':{}},recipeVersion='a'*64)):
   # An empty dict is not a provisioned recipe.
   b.config.return_value['projects']['test-project']={'mode':'package'}
   raw=b'fixture';q=dict(jobId=str(uuid.uuid4()),key='test-project',commit='b'*40,sourceDigest=hashlib.sha256(raw).hexdigest(),recipeVersion='a'*64,size=len(raw))
   self.assertEqual(b.receive(q,io.BytesIO(raw))['state'],'stored')
   with self.assertRaisesRegex(ValueError,'identity'):b.receive(dict(q,commit='c'*40),io.BytesIO(raw))
   q=dict(q,jobId=str(uuid.uuid4()))
   with self.assertRaisesRegex(ValueError,'Truncated'):b.receive(q,io.BytesIO(raw[:-1]))
   self.assertFalse(b.jobpath(q['jobId']).exists())
 def test_caller_cannot_select_unprovisioned_command_or_recipe(self):
  with tempfile.TemporaryDirectory() as td,patch.object(b,'BASE',pathlib.Path(td)),patch.object(b,'config',return_value=dict(projects={},recipeVersion='a'*64)):
   q=dict(jobId=str(uuid.uuid4()),key='test-project',commit='b'*40,sourceDigest='c'*64,recipeVersion='a'*64,size=1,check_command=['sh'])
   with self.assertRaisesRegex(ValueError,'Unprovisioned'):b.receive(q,io.BytesIO(b'x'))
 def test_build_artifact_cannot_follow_symlinks(self):
  with tempfile.TemporaryDirectory() as td:
   p=pathlib.Path(td);out=p/'work/source/dist';out.mkdir(parents=True);(out/'escape').symlink_to('/etc/passwd')
   with self.assertRaisesRegex(ValueError,'Unsafe'):b.package(p,dict(mode='package'))
   self.assertFalse((p/'artifact.tar').exists())
 def test_resource_policy_rejects_unbounded_or_arbitrary_limits(self):
  for v in ('0m','1m','9999m','-1','max','640m;sh',None,1000):
   with self.assertRaises(ValueError):memory_bytes(v)
  self.assertEqual(memory_bytes('896m'),896*1024**2)
if __name__=='__main__':unittest.main()
