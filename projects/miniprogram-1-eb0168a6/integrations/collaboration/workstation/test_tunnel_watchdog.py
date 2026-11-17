import unittest
import tunnel_watchdog as w

class TransportGuard(unittest.TestCase):
 def test_tcp_parser_requires_receive_evidence_and_handles_parent_and_child(self):
  sample='ESTAB 0 500 172.20.5.62:22 192.0.2.1:51000 users:(("sshd",pid=8,fd=4),("sshd",pid=9,fd=4))\n\t cubic bytes_received:12000 lastrcv:49000 lastack:1\nESTAB 0 0 x:22 y:4 users:(("sshd",pid=10,fd=4))\n\t cubic lastack:1\n'
  self.assertEqual(w.tcp_rows(sample),[dict(pids=[8,9],peer='192.0.2.1:51000',received=12000,idleMs=49000)])
 def test_healthy_silent_new_or_reused_pid_is_not_reaped(self):
  ident=(8,'123','siyue-ws-crm');row=dict(received=100,peer='192.0.2.1:1',idleMs=60000)
  state,expired=w.observe({}, {ident:row},100);self.assertEqual(expired,[])
  state,expired=w.observe(state,{ident:row},144);self.assertEqual(expired,[])
  _,expired=w.observe(state,{ident:row},146);self.assertEqual(len(expired),1)
  _,expired=w.observe(state,{ident:dict(row,received=101)},146);self.assertEqual(expired,[])
  _,expired=w.observe(state,{ident:dict(row,idleMs=0)},146);self.assertEqual(expired,[])
  _,expired=w.observe(state,{(8,'999','siyue-ws-crm'):row},146);self.assertEqual(expired,[])
 def test_final_recheck_rejects_changed_missing_or_recovered_connections(self):
  ident=(8,'123','siyue-ws-crm');row=dict(received=100,peer='192.0.2.1:1',idleMs=60000)
  self.assertTrue(w.still_stale(ident,row,{ident:row}))
  for newer in [{},{ident:dict(row,received=101)},{ident:dict(row,peer='192.0.2.1:2')},{ident:dict(row,idleMs=0)}]:
   self.assertFalse(w.still_stale(ident,row,newer))

if __name__=='__main__':unittest.main()
