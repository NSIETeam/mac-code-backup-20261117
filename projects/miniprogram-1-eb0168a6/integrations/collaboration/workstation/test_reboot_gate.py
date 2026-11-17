import fcntl,json,pathlib,tempfile,time,unittest
from unittest.mock import patch
import reboot_gate as g

class RebootGateTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
  self.root=pathlib.Path(self.temp.name);self.boot=self.root/'boot';self.boot.write_text('boot-a\n')
  for name,value in [('BASE',self.root),('RECEIPT',self.root/'gate.json'),('BOOT',self.boot)]:
   p=patch.object(g,name,value);p.start();self.addCleanup(p.stop)
  p=patch.object(g,'start_ticks',return_value='123');p.start();self.addCleanup(p.stop)
  self.row=dict(nonce='request-a',bootId='boot-a',expires=time.time()+60,pid=1,startTicks='123')
  g.RECEIPT.write_text(json.dumps(self.row))
 def test_receipt_does_not_authorize_without_live_lock(self):
  with self.assertRaisesRegex(ValueError,'no longer held'):g.check('request-a')
 def test_live_gate_rejects_other_request_boot_and_process(self):
  with (self.root/'build.lock').open('a') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX)
   self.assertEqual(g.check('request-a')['nonce'],'request-a')
   for changed in [dict(nonce='other'),dict(bootId='other'),dict(expires=0),dict(startTicks='other')]:
    g.RECEIPT.write_text(json.dumps({**self.row,**changed}))
    with self.assertRaises(ValueError):g.check('request-a')
 def test_active_build_is_not_interrupted(self):
  with (self.root/'build.lock').open('a') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX)
   with self.assertRaises(BlockingIOError):g.hold('request-a')
   self.assertEqual(json.loads(g.RECEIPT.read_text()),self.row)
 def test_holder_cleans_receipt_and_releases_lock(self):
  with patch.object(g.time,'sleep',side_effect=lambda _: self.assertEqual(g.check('request-a')['nonce'],'request-a')),patch('builtins.print'):
   g.hold('request-a')
  self.assertFalse(g.RECEIPT.exists())
  with (self.root/'build.lock').open('a') as lock:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)

if __name__=='__main__':unittest.main()
