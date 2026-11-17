import sys
import importlib.util,io,json,pathlib,tarfile,tempfile,unittest,unittest.mock as mock,uuid,hashlib
sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
SPEC=importlib.util.spec_from_file_location('delivery',pathlib.Path(__file__).parents[1]/'delivery_service.py')
d=importlib.util.module_from_spec(SPEC);sys.modules[SPEC.name]=d;SPEC.loader.exec_module(d)
class DeliveryTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();d.ROOT=pathlib.Path(self.temp.name);d.CONFIG=d.ROOT/'config.json';d.CONFIG.write_text(json.dumps({'projects':{'pilot':{'repository':'org/pilot','mode':'static','production_health_url':'https://www.example.com/pilot/__siyue_version.json'}}}));d.CONFIG.chmod(0o600)
 def tearDown(self):self.temp.cleanup()
 def archive(self,name,content=b'hello',kind=None):
  stream=io.BytesIO()
  with tarfile.open(fileobj=stream,mode='w') as tar:
   row=tarfile.TarInfo(name);row.size=len(content)
   if kind:row.type=kind;row.linkname='/etc/passwd';row.size=0
   tar.addfile(row,io.BytesIO(content))
  return stream.getvalue()
 def artifact(self):
  build=str(uuid.uuid4());folder=d.ROOT/'artifacts';folder.mkdir(exist_ok=True);path=folder/(build+'.tar');path.write_bytes(self.archive('index.html',b'<h1>safe pilot</h1>'));digest=hashlib.sha256(path.read_bytes()).hexdigest()
  with d.db() as db:db.execute('INSERT INTO jobs VALUES(?,?,?,?,?)',(build,'build',json.dumps({'key':'pilot'}),'succeeded',json.dumps({'state':'succeeded','digest':digest,'sha':'a'*40})))
  return build,digest
 def test_reject_archive_traversal_symlinks_and_device_files(self):
  for name,kind in [('../outside',None),('/etc/passwd',None),('escape',tarfile.SYMTYPE),('device',tarfile.CHRTYPE)]:
   with self.assertRaises(ValueError):d.unpack(self.archive(name,kind=kind),d.ROOT/'unpacked')
 def test_no_arbitrary_deployment_targets(self):
  for key in ['../../etc','pilot;sh','unknown']:
   with self.assertRaises(ValueError):d.target({'key':key})
 def test_build_output_cannot_link_to_host_files(self):
  source=d.ROOT/'source';source.mkdir();(source/'dist').symlink_to(d.ROOT,target_is_directory=True)
  for path in ['dist','../config.json','/etc']:
   with self.assertRaises(ValueError):d.artifact_output(source,path)
 def test_tampered_or_mismatched_artifact_is_rejected(self):
  build,digest=self.artifact();self.assertTrue(d.completed_build(build,'pilot',digest))
  with self.assertRaises(ValueError):d.completed_build(build,'other',digest)
  (d.ROOT/'artifacts'/(build+'.tar')).write_bytes(b'tampered')
  with self.assertRaises(ValueError):d.completed_build(build,'pilot',digest)
 def test_failed_production_health_restores_previous_symlink(self):
  build,digest=self.artifact();old=d.ROOT/'sites/pilot/versions/old';old.mkdir(parents=True);(old/'index.html').write_text('old');current=old.parent.parent/'production';current.symlink_to(old)
  with mock.patch.object(d.urllib.request,'urlopen',side_effect=OSError('health failed')):
   with self.assertRaises(OSError):d.deploy_static(str(uuid.uuid4()),{'id':str(uuid.uuid4()),'buildId':build,'key':'pilot','digest':digest},True)
  self.assertEqual(current.resolve(),old.resolve())
 def test_preview_cannot_escape_project_root(self):
  for path in ['../config.json','/etc/passwd','foo/../../outside','foo\\..\\secret']:
   with self.assertRaises(ValueError):d.preview({'key':'pilot','path':path})
 def test_single_job_id_cannot_be_reused_for_other_commit(self):
  job=str(uuid.uuid4());request={'id':job,'key':'pilot','sha':'a'*40,'kind':'check'}
  with mock.patch.object(d.POOL,'submit'):
   d.enqueue('build',request)
   with self.assertRaises(ValueError):d.enqueue('build',{**request,'sha':'b'*40})
 def test_low_memory_queues_without_fetching_or_running_code(self):
  job=str(uuid.uuid4());request={'id':job,'key':'pilot','sha':'a'*40,'kind':'check'}
  with d.db() as db:db.execute('INSERT INTO jobs VALUES(?,?,?,?,?)',(job,'build',json.dumps(request),'queued','{}'))
  with mock.patch.object(d,'memory_available',return_value=0),mock.patch.object(d.urllib.request,'urlopen') as network,mock.patch.object(d,'run') as execute:
   d.do_build(job,request);network.assert_not_called();execute.assert_not_called()
  with d.db() as db:result=json.loads(db.execute('SELECT result FROM jobs WHERE id=?',(job,)).fetchone()[0])
  self.assertEqual(result['state'],'queued');self.assertIn('内存不足',result['summary'])
 def test_branch_preview_does_not_replace_shared_main_version(self):
  build,digest=self.artifact();old=d.ROOT/'sites/pilot/versions/old';old.mkdir(parents=True);current=old.parent.parent/'testing';current.symlink_to(old)
  d.deploy_static('test-'+build,{'id':build,'key':'pilot','digest':digest,'kind':'check'},False)
  self.assertEqual(current.resolve(),old.resolve())
  result=d.preview({'key':'pilot','buildId':build,'digest':digest,'path':'index.html'})
  self.assertEqual(result['status'],200)
 def test_package_never_guesses_a_production_target(self):
  with self.assertRaisesRegex(ValueError,'正式发布目标'):d.recipes.package(d,'job',{},True)
 def test_preview_rejects_unverified_artifact_identity(self):
  build,digest=self.artifact()
  with self.assertRaises(ValueError):d.preview({'key':'pilot','buildId':build,'digest':'c'*64,'path':'index.html'})
 def test_published_assets_do_not_start_runtime_or_serve_private_files(self):
  build,digest=self.artifact();release=d.ROOT/'sites/pilot/versions'/build;web=release/'wwwroot';web.mkdir(parents=True);(web/'app.mjs').write_text('export const ok=true;');(release/'private.json').write_text('secret')
  target={'environment':{'SIYUE_PREVIEW_KIND':'miniapp'}};request={'buildId':build,'digest':digest,'path':'app.mjs'}
  with mock.patch.object(d.recipes,'ensure_runtime',side_effect=ValueError('runtime forbidden')) as runtime:
   result=d.recipes.preview(d,request,target,'pilot');self.assertEqual(result['status'],200);runtime.assert_not_called()
   with self.assertRaisesRegex(ValueError,'runtime forbidden'):d.recipes.preview(d,{**request,'path':'../private.json'},target,'pilot')
 def test_preview_heartbeat_only_keeps_its_own_runtime_alive(self):
  build,digest=self.artifact();release=d.ROOT/'sites/pilot/versions'/build;release.mkdir(parents=True);(release/'__siyue_version.json').write_text('{}');state=d.ROOT/'preview-runtime.json';other={'buildId':str(uuid.uuid4()),'usedAt':0};state.write_text(json.dumps(other));request={'buildId':build,'digest':digest,'path':'__siyue_version.json'}
  d.recipes.preview(d,request,{},'pilot');self.assertEqual(json.loads(state.read_text()),other)
  state.write_text(json.dumps({'buildId':build,'usedAt':0}));d.recipes.preview(d,request,{},'pilot');self.assertGreater(json.loads(state.read_text())['usedAt'],0)
if __name__=='__main__':unittest.main()
