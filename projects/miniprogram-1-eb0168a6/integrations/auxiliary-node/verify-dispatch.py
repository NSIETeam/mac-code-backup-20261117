import json,os,subprocess,time,urllib.request,sys
from pathlib import Path
c=json.loads(Path('/opt/siyue-auxiliary/config.json').read_text())
def status():
 r=urllib.request.Request('http://127.0.0.1:5096/internal/status',headers={'Authorization':'Bearer '+c['dispatchToken']})
 with urllib.request.urlopen(r) as f:return json.load(f)
def run(cmd):return subprocess.run(['docker','exec','siyue-miniapp-worker-1']+cmd,check=True,capture_output=True,text=True)
w='/work/auxiliary-acceptance';host=Path('/opt/siyue-miniapp/data/worker-scratch/auxiliary-acceptance');mode=sys.argv[1]
if mode=='online':
 for _ in range(40):
  before=status()
  if before['ready']: break
  time.sleep(.5)
 assert before['ready']
run(['python3','/app/pdf_pixels.py','audit',w+'/real.png',w+'/real.png-1.json',w+'/expected.json'])
start=time.monotonic();run(['env','SIYUE_AUX_FORCE=1','/auxiliary/python-dispatch','/app/pdf_pixels.py','audit',w+'/real.png',w+'/real.png-1.json',w+'/actual.json']);seconds=time.monotonic()-start
assert json.loads((host/'expected.json').read_text())==json.loads((host/'actual.json').read_text())
after=status()
if mode=='online':assert after['jobs'].get('done',0)>before['jobs'].get('done',0),'Shim silently fell back'
if mode=='offline':assert seconds<14,'Fallback blocked too long'
result=dict(mode=mode,seconds=round(seconds,3),identical=True,node=after['node'].get('host'),jobs=after['jobs'])
Path('/opt/siyue-auxiliary/dispatch-'+mode+'.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
