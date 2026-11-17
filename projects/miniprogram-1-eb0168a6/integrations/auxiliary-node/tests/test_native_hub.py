import http.client,json,sys,tempfile,threading,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]))
from hub import Store,BoundedServer,Handler,digest
from native_contract import cache_key
class NativeHubTests(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();r=Path(self.t.name);(r/'objects').mkdir()
  self.s=Store(dict(state=str(r/'state'),objects=str(r/'objects'),scriptHash='a'*64,nativeFingerprint='engine-a',nodeToken='test-node-token',dispatchToken='test-dispatch-token'))
  self.s.heartbeat(dict(seen=0,nativeVersion='native-v2',nativeFingerprint='engine-a',active=0,freeBytes=30*1024**3))
  self.p=dict(mode='native',nativeVersion='native-v2',tool='pdftoppm',inputSha='a'*64,inputSize=100,options=['-f','1','-l','1','-singlefile','-png'])
 def tearDown(self):self.t.cleanup()
 def test_expected_fingerprint_and_freshness_required(self):
  self.assertTrue(self.s.native_ready());self.s.node['nativeFingerprint']='other';self.assertFalse(self.s.native_ready());self.s.node['nativeFingerprint']='engine-a';self.s.node['seen']=0;self.assertFalse(self.s.native_ready())
 def test_cache_probe_never_enqueues_work(self):
  self.assertEqual(self.s.native_submit(dict(self.p,cacheOnly=True))[0],404)
  with self.s.db() as d:self.assertEqual(d.execute('SELECT count(*) FROM jobs').fetchone()[0],0)
 def test_cached_result_works_offline_and_is_version_bound(self):
  key=cache_key(self.p,'engine-a');(self.s.root/'native-results'/key).write_text(json.dumps(dict(files={},engine='engine-a')));self.s.node['seen']=0
  self.assertEqual(self.s.native_submit(self.p)[0],200);self.s.c['nativeFingerprint']='engine-b';self.assertEqual(self.s.native_submit(self.p)[0],503)
 def test_tesseract_held_on_primary(self):
  self.assertEqual(self.s.native_submit(dict(self.p,tool='tesseract',options=['-l','eng']))[0],503)
 def test_http_staging_hash_and_auth(self):
  server=BoundedServer(('127.0.0.1',0),Handler);server.store=self.s;thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();port=server.server_address[1]
  def put(sha,b,token):
   c=http.client.HTTPConnection('127.0.0.1',port);c.request('PUT','/internal/input?sha='+sha,body=b,headers={'Authorization':'Bearer '+token,'Content-Length':str(len(b))});r=c.getresponse();data=r.read();status=r.status;c.close();return status
  try:
   data=b'%PDF-safe-test';sha=digest(data)
   self.assertEqual(put(sha,data,'test-node-token'),401)
   self.assertEqual(put('b'*64,data,'test-dispatch-token'),400)
   self.assertFalse(self.s.native_input('b'*64).exists())
   self.assertEqual(put(sha,data,'test-dispatch-token'),200);self.assertEqual(self.s.native_input(sha).read_bytes(),data)
   with self.assertRaises(ValueError):self.s.native_input('../../secret')
  finally:server.shutdown();server.server_close();thread.join()
if __name__=='__main__':unittest.main()
