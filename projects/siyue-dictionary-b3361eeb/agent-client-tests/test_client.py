import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.request

spec=importlib.util.spec_from_file_location('siyue_client',Path(__file__).resolve().parents[1]/'public/agent/siyue_dictionary.py')
sdk=importlib.util.module_from_spec(spec);spec.loader.exec_module(sdk)
class Response:
 def __init__(self,body,headers=None):self.body=body;self.headers=headers or {}
 def read(self,limit):return json.dumps(self.body).encode()[:limit]
 def __enter__(self):return self
 def __exit__(self,*args):pass
class ClientTests(unittest.TestCase):
 def test_search_and_read_use_fixed_https_origin_and_encoded_inputs(self):
  client=sdk.Client(token='a'*48)
  with patch.object(client.opener,'open',return_value=Response({'results':[]})) as call:
   client.search('补卡 怎么办');request=call.call_args.args[0]
   self.assertTrue(request.full_url.startswith(sdk.BASE_URL+'/api/agent/v1/search?q='))
   self.assertNotIn('补卡',request.full_url);self.assertEqual(request.get_header('Cookie'),'siyue_work_session='+'a'*48)
   client.read('policy:policy-leave-correction');self.assertTrue(call.call_args.args[0].full_url.endswith('/entries/policy/policy-leave-correction'))
   self.assertEqual(call.call_args.args[0].get_method(),'GET')
 def test_rejects_credential_redirect_and_arbitrary_read_paths(self):
  client=sdk.Client(token='a'*48)
  for key in ['https://other.example/x','policy:../../data','document:foo?x=1','user:123']:
   with self.assertRaises(sdk.ApiError):client.read(key)
  with self.assertRaises(sdk.ApiError):sdk.NoRedirect().redirect_request(urllib.request.Request(sdk.BASE_URL),None,302,'redirect',{},'https://other.example/')
 def test_login_keeps_only_session_not_password_and_logout_revokes_it(self):
  with tempfile.TemporaryDirectory() as root:
   path=Path(root)/'session.json';client=sdk.Client(path)
   with patch.object(client,'_request',side_effect=[({}, {'Set-Cookie':'siyue_work_session='+'b'*48+'; Path=/work/; HttpOnly'}),({'accessStatus':'active'}, {})]) as request:
    result=client.login('测试员工','DisposableTestPassword')
    self.assertEqual(result,{'accessStatus':'active'});state=path.read_text();self.assertNotIn('DisposableTestPassword',state);self.assertNotIn('测试员工',state)
    self.assertEqual(path.stat().st_mode&0o777,0o600)
   with patch.object(client,'_request',return_value=({'ok':True},{})) as request:
    client.logout();request.assert_called_once_with('/api/account/logout',{});self.assertFalse(path.exists())
 def test_missing_invalid_or_overpermissive_session_fails_before_network(self):
  with tempfile.TemporaryDirectory() as root:
   path=Path(root)/'session.json';client=sdk.Client(path)
   with patch.dict(sdk.os.environ,{},clear=True):
    with self.assertRaises(sdk.ApiError):client.me()
   path.write_text(json.dumps({'origin':sdk.BASE_URL,'token':'c'*48}));path.chmod(0o644)
   if sdk.os.name=='posix':
    with self.assertRaises(sdk.ApiError):client.me()
   path.chmod(0o600);path.write_text(json.dumps({'origin':'https://wrong.example','token':'c'*48}))
   with self.assertRaises(sdk.ApiError):client.me()
 def test_bounds_parameters_without_sending_request(self):
  client=sdk.Client(token='a'*48)
  for args in [('',5,0),('test',21,0),('test',1,-1),('x'*201,5,0),('test',True,0)]:
   with self.assertRaises(sdk.ApiError):client.search(*args)
if __name__=='__main__':unittest.main()
