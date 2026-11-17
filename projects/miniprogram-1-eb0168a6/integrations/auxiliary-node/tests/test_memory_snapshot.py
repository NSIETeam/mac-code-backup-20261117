import importlib.util,json,os,pathlib,subprocess,sys,unittest,io,contextlib
from unittest.mock import patch,Mock

source=pathlib.Path(__file__).resolve().parent/'memory_snapshot.py'
if not source.exists():source=source.parent.parent/'memory_snapshot.py'
spec=importlib.util.spec_from_file_location('memory_snapshot',source)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class Policy(unittest.TestCase):
    def test_local_diagnostic_never_loads_config_uploads_or_cleans(self):
        api=Mock();api.snapshot.return_value={'at':123,'physicalAvailableMiB':1400}
        output=io.StringIO()
        with patch.object(m.sys,'argv',['memory_snapshot.py','--local-json']), patch.object(m,'Api',return_value=api), patch.object(m,'send',side_effect=AssertionError('Must not upload')), patch.object(m.Path,'read_text',side_effect=AssertionError('Must not read configuration')), contextlib.redirect_stdout(output):
            m.main()
        self.assertEqual(json.loads(output.getvalue()),api.snapshot.return_value)
        self.assertEqual([c[0] for c in api.method_calls],['snapshot'])
    def test_screensaver_cleanup_requires_exact_name_and_valid_vendor_signature(self):
        signature=dict(status='Valid',subject='CN=Beijing Qihu Technology Co. Ltd.')
        self.assertTrue(m.approved_screensaver(r'C:\Program Files\360\360huabao.scr',signature))
        for name in ('wps.exe','360se.exe','spoolsv.exe','360huabao.exe','fake360huabao.scr'):
            self.assertFalse(m.approved_screensaver(name,signature))
        self.assertFalse(m.approved_screensaver('360huabao.scr',dict(signature,status='NotSigned')))
        self.assertFalse(m.approved_screensaver('360huabao.scr',dict(signature,subject='CN=Unrelated Publisher')))
    def test_reuse_busy_and_service_processes_are_excluded(self):
        before=dict(pid=42,name='chrome.exe',creationTicks='123',cpuTicks='10',workingSetMiB=500)
        self.assertTrue(m.eligible(before,dict(before,cpuTicks='100')))
        for changes in [dict(creationTicks='124'),dict(cpuTicks='10000000'),dict(name='spoolsv.exe'),dict(name='Siyue.Agent.exe'),dict(workingSetMiB=100)]:
            self.assertFalse(m.eligible(before,dict(before,**changes)))
        for name in ('360se.exe','quark.exe'):
            row=dict(before,name=name,workingSetMiB=128)
            self.assertTrue(m.eligible(row,dict(row,cpuTicks='100')))
            self.assertFalse(m.eligible(row,dict(row,cpuTicks='10000000')))
        for name in ('wps.exe','wpscloudsvr.exe','360huabao.scr','spoolsv.exe'):
            row=dict(before,name=name)
            self.assertFalse(m.eligible(row,row))

@unittest.skipUnless(os.name=='nt','Real Windows API acceptance')
class Windows(unittest.TestCase):
    def test_fixed_handle_exit_and_document_process_guard(self):
        api=m.Api();child=subprocess.Popen([sys.executable,'-c',"import time; print('ready',flush=True); time.sleep(60)"],stdout=subprocess.PIPE,text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(),'ready')
            row=api.process(child.pid)
            with self.assertRaisesRegex(ValueError,'authorized screensaver'):
                api.stop_screensaver_checked(row,{})
            with self.assertRaisesRegex(ValueError,'identity changed'):
                api.stop_screensaver_checked(dict(row,creationTicks='0'),{})
            self.assertIsNone(child.poll())
            handle=api.checked(api.k.OpenProcess(0x101001,False,child.pid))
            try:api._terminate_handle(handle)
            finally:api.k.CloseHandle(handle)
            self.assertEqual(child.wait(timeout=5),0)
        finally:
            if child.poll() is None:child.terminate();child.wait(timeout=5)
            child.stdout.close()
    def test_snapshot_and_trim_preserve_owned_child_data(self):
        api=m.Api();snapshot=api.snapshot()
        self.assertGreater(snapshot['physicalTotalMiB'],0);self.assertGreater(snapshot['processCount'],0)
        script="import sys; x=bytearray(128*1024**2); x[::4096]=bytes([77])*len(x[::4096]); print('ready',flush=True); sys.stdin.readline(); print(all(v==77 for v in x[::4096]),flush=True)"
        child=subprocess.Popen([sys.executable,'-c',script],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(),'ready')
            before=api.process(child.pid);self.assertGreater(before['workingSetMiB'],100)
            with self.assertRaises(ValueError):api.trim_checked(dict(before,creationTicks='0'))
            proof=api.trim_checked(before)
            self.assertLess(proof['afterWorkingSetMiB'],proof['beforeWorkingSetMiB']-50)
            self.assertGreaterEqual(proof['privateAfterMiB'],proof['privateBeforeMiB']-2)
            child.stdin.write('check\n');child.stdin.flush();self.assertEqual(child.stdout.readline().strip(),'True')
            self.assertEqual(child.wait(timeout=15),0)
            out=pathlib.Path('diagnostics');out.mkdir(exist_ok=True)
            (out/'memory-api.json').write_text(json.dumps(dict(realWindowsApi=True,childDataPreserved=True,**proof),indent=2))
        finally:
            if child.poll() is None:child.terminate();child.wait(timeout=10)
            child.stdin.close();child.stdout.close()

if __name__=='__main__':unittest.main()
