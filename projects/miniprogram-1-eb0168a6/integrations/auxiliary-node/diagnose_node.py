"""Fixed hardware/startup diagnostics, delivered only to the authenticated owning hub."""
import ctypes,hashlib,json,os,shutil,subprocess,sys,urllib.request,winreg
from pathlib import Path
r=Path(sys.argv[1]);c=json.loads((r/'config.json').read_text(encoding='utf-8-sig'));data=Path(c['data'])
class Memory(ctypes.Structure):
    _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ('total','available','totalPage','availablePage','totalVirtual','availableVirtual','extended')]
m=Memory();m.length=ctypes.sizeof(m);assert ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r'HARDWARE\DESCRIPTION\System\CentralProcessor\0') as k:cpu=winreg.QueryValueEx(k,'ProcessorNameString')[0].strip()
report=dict(host=os.environ.get('COMPUTERNAME'),cpu=cpu,logicalCpus=os.cpu_count(),totalMemoryMiB=m.total//1024**2,availableMemoryMiB=m.available//1024**2,python=sys.version.split()[0],disks=[])
for drive in sorted({r.anchor,data.anchor,'C:\\','D:\\','E:\\'}):
    if Path(drive).exists():
        u=shutil.disk_usage(drive);report['disks'].append(dict(drive=drive,totalGiB=round(u.total/1024**3,1),freeGiB=round(u.free/1024**3,1)))
try:
    p=subprocess.run(['schtasks.exe','/Query','/TN','Siyue-Auxiliary-Node','/FO','LIST','/V'],capture_output=True,timeout=8)
    report['task']=p.stdout.decode('gbk',errors='replace')[-3000:]
except Exception as e:report['taskError']=type(e).__name__
for name in ('startup-error.txt','logs/node.log'):
    path=data/name
    if path.exists():report[name]=path.read_text(encoding='utf-8',errors='replace')[-4500:]
report['scriptHash']=hashlib.sha256((r/'pdf_pixels.local.py').read_bytes()).hexdigest()
try:
    p=subprocess.run([sys.executable,'-c','import PIL,onnxruntime,rapidocr;print("imports-ok")'],capture_output=True,timeout=15)
    report['runtimeImports']=dict(exitCode=p.returncode,stderr=p.stderr.decode(errors='replace')[-1800:])
except Exception as e:report['runtimeImports']=dict(error=type(e).__name__)
phase=sys.argv[2] if len(sys.argv)>2 else 'current';report['phase']=phase
prior=data/'diagnostic-before.json'
if phase=='before':prior.write_text(json.dumps(report,ensure_ascii=False),encoding='utf-8')
elif prior.exists():report['beforeRepairImports']=json.loads(prior.read_text(encoding='utf-8')).get('runtimeImports')
raw=json.dumps(report,ensure_ascii=False).replace(c['token'],'<redacted>').encode()
assert len(raw)<16384
(data/'diagnostic-latest.json').write_bytes(raw)
q=urllib.request.Request(c['url']+'/node/report',data=raw,headers={'Authorization':'Bearer '+c['token'],'Content-Type':'application/json'})
with urllib.request.urlopen(q,timeout=15) as response:assert response.status==200
(r/'diagnostic-sent.json').write_text(json.dumps(dict(sent=True,host=report['host'])))
