import base64, hashlib, importlib.util, json, tempfile, threading, time, unittest
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parents[1]))
spec=importlib.util.spec_from_file_location('hub',Path(__file__).parents[1]/'hub.py'); hub=importlib.util.module_from_spec(spec);spec.loader.exec_module(hub)

class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); root=Path(self.temp.name); (root/'objects').mkdir()
        self.s=hub.Store(dict(state=str(root/'state'),objects=str(root/'objects'),scriptHash='a'*64))
        self.s.heartbeat(dict(busy=False,scriptHash='a'*64,freeBytes=20*1024**3))
        self.p=dict(mode='audit',scriptHash='a'*64,image=base64.b64encode(b'image').decode(),data=base64.b64encode(b'{}').decode(),imageHash=hub.digest(b'image'),dataHash=hub.digest(b'{}'))
    def tearDown(self): self.temp.cleanup()
    def test_offline_fails_fast(self):
        self.s.node['seen']=0; start=time.monotonic(); self.assertEqual(self.s.submit(self.p)[0],503);self.assertLess(time.monotonic()-start,.2)
    def test_claim_is_atomic_and_stale_completion_rejected(self):
        result=[]; worker=threading.Thread(target=lambda:result.append(self.s.submit(self.p,wait=.4)));worker.start()
        time.sleep(.1); jobs=[]
        threads=[threading.Thread(target=lambda:jobs.append(self.s.claim())) for _ in range(8)]
        [t.start() for t in threads];[t.join() for t in threads]
        jobs=[j for j in jobs if j];self.assertEqual(len(jobs),1);worker.join();self.assertEqual(result[0][0],504)
        j=jobs[0];self.assertFalse(self.s.finish(dict(id=j['id'],claim=j['claim'],ok=True,result={})))
    def test_success_and_token_fencing(self):
        result=[];t=threading.Thread(target=lambda:result.append(self.s.submit(self.p)));t.start();time.sleep(.1); j=self.s.claim()
        self.assertFalse(self.s.finish(dict(id=j['id'],claim='wrong',ok=True,result={})))
        self.assertTrue(self.s.finish(dict(id=j['id'],claim=j['claim'],ok=True,result={'missingPixels':0})))
        self.assertFalse(self.s.finish(dict(id=j['id'],claim=j['claim'],ok=True,result={})))
        t.join();self.assertEqual(result[0][0],200)
    def test_changed_contract_and_inputs_are_rejected(self):
        self.assertEqual(self.s.submit(dict(self.p,scriptHash='b'*64))[0],400)
        self.assertEqual(self.s.submit(dict(self.p,imageHash='b'*64))[0],400)
    def test_only_indexed_object_version_is_served(self):
        root=Path(self.s.c['objects']);(root/'a.pdf').write_bytes(b'pdf');self.s.scan()
        self.assertEqual(self.s.object('a.pdf',hub.digest(b'pdf')).read_bytes(),b'pdf')
        with self.assertRaises(FileNotFoundError): self.s.object('../secret','0'*64)
        with self.assertRaises(FileNotFoundError): self.s.object('a.pdf','0'*64)
    def test_restart_does_not_resume_old_jobs(self):
        with self.s.db() as db: db.execute('INSERT INTO jobs(id,state,payload,result,claim,expires,created) VALUES(?,?,?,?,?,?,?)',('x','running','{}',None,'c',time.time()+99,time.time()))
        fresh=hub.Store(self.s.c)
        with fresh.db() as db:self.assertEqual(db.execute('SELECT state FROM jobs WHERE id=?',('x',)).fetchone()[0],'cancelled')
if __name__=='__main__':unittest.main()
