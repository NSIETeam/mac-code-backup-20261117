import base64,json,sys,tempfile,threading,time,unittest,http.client
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]))
from hub import Store,BoundedServer,Handler,digest

class MultiNodeTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();r=Path(self.tmp.name);(r/'objects').mkdir()
  self.s=Store(dict(state=str(r/'state'),objects=str(r/'objects'),scriptHash='a'*64,nativeFingerprint='engine',nodeToken='old',dispatchToken='dispatch',nodes={n:dict(token=n+'-token',maxConcurrency=1,mirror=False) for n in ('west','north')},offlineSeconds=.3))
  self.hb=dict(busy=False,active=0,capacity=1,scriptHash='a'*64,nativeVersion='native-v2',nativeFingerprint='engine',freeBytes=20*1024**3)
  self.p=dict(mode='audit',scriptHash='a'*64,image=base64.b64encode(b'i').decode(),data=base64.b64encode(b'{}').decode(),imageHash=digest(b'i'),dataHash=digest(b'{}'))
  self.threads=[]
 def tearDown(self):
  for t in self.threads:t.join(3)
  self.tmp.cleanup()
 def launch(self,wait=2,payload=None):
  result=[];t=threading.Thread(target=lambda:result.append(self.s.submit(payload or self.p,wait=wait,native=(payload or self.p).get('mode')=='native')));self.threads.append(t);t.start();return result
 def wait_jobs(self,n):
  end=time.monotonic()+1
  while time.monotonic()<end:
   with self.s.db() as db:count=db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0]
   if count==n:return
   time.sleep(.01)
  self.fail('queue did not reach '+str(n))
 def done(self,job,node):return self.s.finish(dict(id=job['id'],claim=job['claim'],ok=True,result={'ok':True}),node)
 def test_independent_heartbeats_and_three_computers(self):
  for n in ('legacy','west','north'):self.s.heartbeat(self.hb,n)
  results=[self.launch() for _ in range(3)];self.wait_jobs(3)
  jobs={n:self.s.claim(n) for n in ('legacy','west','north')}
  self.assertTrue(all(jobs.values()));self.assertEqual(len({j['id'] for j in jobs.values()}),3)
  self.assertEqual(self.s.submit(self.p,wait=.1)[0],503)
  for n,j in jobs.items():self.assertTrue(self.done(j,n))
  for t in self.threads:t.join()
  self.assertTrue(all(r[0][0]==200 for r in results))
 def test_node_cannot_take_or_finish_another_nodes_job(self):
  self.s.heartbeat(self.hb,'west');self.launch();self.wait_jobs(1)
  self.assertIsNone(self.s.claim('north'));job=self.s.claim('west')
  self.assertFalse(self.done(job,'north'));self.assertTrue(self.done(job,'west'))
 def test_offline_job_transfers_and_old_result_is_fenced(self):
  self.s.heartbeat(self.hb,'west');result=self.launch();self.wait_jobs(1);old=self.s.claim('west')
  self.s.heartbeat(self.hb,'north');self.s.nodes['west']['seen']=0
  with self.s.db() as db:db.execute('BEGIN IMMEDIATE');self.s.recover(db)
  new=self.s.claim('north');self.assertEqual(new['id'],old['id']);self.assertNotEqual(new['claim'],old['claim'])
  self.assertFalse(self.done(old,'west'));self.assertTrue(self.done(new,'north'))
  self.threads[-1].join();self.assertEqual(result[0][0],200)
 def test_printing_and_wrong_engine_receive_no_new_work(self):
  self.s.heartbeat(dict(self.hb,pauseReason='printing'),'west')
  self.assertFalse(self.s.ready());self.assertEqual(self.s.submit(self.p)[0],503)
  self.s.heartbeat(dict(self.hb,nativeFingerprint='wrong'),'north')
  self.assertFalse(self.s.native_ready());self.assertTrue(self.s.ready())
 def test_light_node_cannot_receive_ocr(self):
  self.s.c['nodes']['west']['nativeTools']=['pdftoppm','pdftotext']
  self.s.heartbeat(self.hb,'west')
  self.assertTrue(self.s.compatible('west',dict(mode='native',tool='pdftoppm')))
  self.assertFalse(self.s.compatible('west',dict(mode='native',tool='rapid')))
  self.s.heartbeat(dict(self.hb,nativeTools=['pdftotext']),'north')
  self.assertFalse(self.s.compatible('north',dict(mode='native',tool='pdftoppm')))
  self.assertTrue(self.s.compatible('north',dict(mode='native',tool='pdftotext')))
 def test_deadline_is_not_extended_by_transfer(self):
  self.s.heartbeat(self.hb,'west');result=self.launch(wait=.2);self.wait_jobs(1);old=self.s.claim('west')
  time.sleep(.22);self.s.heartbeat(self.hb,'north');self.s.nodes['west']['seen']=0
  self.assertIsNone(self.s.claim('north'));self.assertFalse(self.done(old,'west'))
  self.threads[-1].join();self.assertEqual(result[0][0],504)
 def test_compute_node_gets_only_assigned_input(self):
  sha='b'*64
  self.assertFalse(self.s.input_allowed('west',sha))
  self.s.grant_input('west',dict(inputSha=sha),time.time()+20)
  self.assertTrue(self.s.input_allowed('west',sha));self.assertFalse(self.s.input_allowed('north',sha))
 def test_paused_queued_work_can_move_without_cancelling_active_work(self):
  self.s.heartbeat(self.hb,'west');self.launch();self.wait_jobs(1)
  self.s.heartbeat(dict(self.hb,pauseReason='printing'),'west');self.s.heartbeat(self.hb,'north')
  job=self.s.claim('north');self.assertIsNotNone(job);self.assertTrue(self.done(job,'north'))
 def test_http_identity_comes_from_token_not_body(self):
  server=BoundedServer(('127.0.0.1',0),Handler);server.store=self.s;t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  def request(method,path,token,value=None):
   c=http.client.HTTPConnection('127.0.0.1',server.server_address[1]);c.request(method,path,body=json.dumps(value).encode() if value is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'});r=c.getresponse();data=json.loads(r.read());code=r.status;c.close();return code,data
  try:
   self.assertEqual(request('POST','/node/heartbeat','west-token',dict(self.hb,nodeId='north'))[0],200)
   self.assertIn('west',self.s.nodes);self.assertNotIn('north',self.s.nodes)
   self.assertEqual(request('GET','/internal/status','west-token')[0],401)
   self.assertEqual(request('GET','/node/manifest','west-token')[1]['entries'],[])
   self.assertEqual(request('GET','/node/input?sha='+'a'*64,'west-token')[0],403)
   self.assertEqual(request('POST','/node/report','west-token',{'cpu':'fixture','totalMemoryMiB':8192})[0],200)
   self.assertEqual(self.s.node_reports['west']['cpu'],'fixture')
   self.assertNotIn('north',self.s.node_reports)
   self.assertEqual(request('POST','/node/report','wrong-token',{})[0],401)
  finally:server.shutdown();server.server_close();t.join()

 def test_runtime_export_requires_legacy_opt_in_and_pinned_download(self):
  server=BoundedServer(('127.0.0.1',0),Handler);server.store=self.s;t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  def request(method,token,sha,body=None):
   c=http.client.HTTPConnection('127.0.0.1',server.server_address[1]);c.request(method,'/node/runtime?sha='+sha,body=body,headers={'Authorization':'Bearer '+token});r=c.getresponse();data=r.read();code=r.status;c.close();return code,data
  try:
   body=b'fixed-runtime';sha=digest(body)
   self.assertEqual(request('PUT','old',sha,body)[0],403)
   self.s.c['runtimeExportEnabled']=True
   self.assertEqual(request('PUT','west-token',sha,body)[0],403)
   self.assertEqual(request('PUT','old','a'*64,body)[0],400)
   self.assertEqual(request('PUT','old',sha,body)[0],200)
   self.assertEqual(request('GET','west-token',sha)[0],403)
   self.s.c['nodes']['west']['runtimeSha']=sha
   self.assertEqual(request('GET','west-token',sha),(200,body))
   self.assertEqual(request('GET','north-token',sha)[0],403)
  finally:server.shutdown();server.server_close();t.join()

if __name__=='__main__':unittest.main()
