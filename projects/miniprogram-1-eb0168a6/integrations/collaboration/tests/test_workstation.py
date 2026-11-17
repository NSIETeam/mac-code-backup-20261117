import json,pathlib,sys,tempfile,unittest,uuid
from types import SimpleNamespace
from unittest.mock import Mock,patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
import workstation_client as w
import delivery_recipes as recipes

class WorkstationTest(unittest.TestCase):
 def test_reused_connection_is_private_and_changes_with_trust(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);key=root/'key';known=root/'known';key.write_text('key');known.write_text('host')
   c=dict(host='127.0.0.1',port=5399,identity=str(key),knownHosts=str(known),reuseConnection=True)
   with patch.object(w,'CONTROL_ROOT',root/'control'):
    first=w.command(c);self.assertEqual(first,w.command(c));self.assertIn('StrictHostKeyChecking=yes',first)
    known.write_text('replacement host');self.assertNotEqual(first,w.command(c))
    (root/'control').chmod(0o755)
    with self.assertRaisesRegex(ValueError,'Unsafe'):w.command(c)
 def test_control_directory_symlink_is_rejected(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);(root/'control').symlink_to(root,target_is_directory=True)
   with patch.object(w,'CONTROL_ROOT',root/'control'):
    with self.assertRaisesRegex(ValueError,'Unsafe'):w.connection_options(dict(reuseConnection=True))
 def test_reuse_is_opt_in(self):
  self.assertEqual(w.connection_options({}),[])
 def test_preview_heartbeat_reaches_workstation_without_starting_runtime(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);bid=str(uuid.uuid4());release=root/'sites/test-project/versions'/bid;release.mkdir(parents=True)
   (release/'__siyue_version.json').write_text('{"version":"fixture"}')
   d=SimpleNamespace(ROOT=root,validate_id=lambda x:x,completed_build=Mock(),settings=lambda:dict(workstation=dict(enabled=True,projects=['test-project'])))
   with patch.object(w,'rpc',return_value=dict(touched=True)) as rpc,patch.object(recipes,'ensure_runtime') as ensure:
    result=recipes.preview(d,dict(buildId=bid,digest='a'*64,path='__siyue_version.json'),{},'test-project')
    self.assertEqual(result['status'],200);self.assertEqual(rpc.call_args.args[1]['action'],'touch');ensure.assert_not_called()
 def test_remote_endpoint_cannot_be_supplied_by_repository(self):
  c=dict(host='remote.invalid',port=5399,identity='/key',knownHosts='/known')
  with self.assertRaises(ValueError):w.command(c)
 def test_disabled_remote_never_changes_local_target(self):
  d=SimpleNamespace(settings=lambda:dict(workstation=dict(enabled=False,projects=['test-project'])))
  self.assertIsNone(w.ensure(d,{},'test-project','unused',pathlib.Path('/unused')))
 def test_offline_workstation_does_not_silently_start_different_local_data(self):
  with tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td);bid=str(uuid.uuid4());sha='a'*64
   (root/'__siyue_version.json').write_text(json.dumps(dict(digest=sha)))
   artifact=root/'release.tar';artifact.write_bytes(b'fixture')
   d=SimpleNamespace(settings=lambda:dict(workstation=dict(enabled=True,projects=['test-project'])),completed_build=Mock(return_value=(artifact,dict(sha='b'*40))),ROOT=root)
   with patch.object(w,'rpc',side_effect=w.Unavailable('等待资源')),patch.object(recipes.subprocess,'run') as local:
    with self.assertRaises(w.Unavailable):recipes.ensure_runtime(d,{},'test-project',bid,root)
    local.assert_not_called()
 def test_runtime_http_requests_use_workstation_when_row_is_remote(self):
  row=dict(backend='workstation',key='test-project',buildId=str(uuid.uuid4()),digest='a'*64)
  with patch.object(w,'request',return_value=dict(status=200,body='')) as remote,patch.object(recipes.subprocess,'run') as local:
   self.assertEqual(recipes.runtime_request(object(),row,dict(path='health'),{})['status'],200)
   remote.assert_called_once();local.assert_not_called()

if __name__=='__main__':unittest.main()
