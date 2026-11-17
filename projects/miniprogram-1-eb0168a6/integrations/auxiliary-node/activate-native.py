"""Enable native overflow without changing the current release or parsing policy."""
from pathlib import Path
import hashlib,json,shutil,subprocess,sys,time,urllib.request
root=Path('/opt/siyue-miniapp');aux=Path('/opt/siyue-auxiliary');compose=root/'compose.yml';before=compose.read_text()
def run(args):return subprocess.run(args,cwd=root,check=True,capture_output=True,text=True).stdout
image=run(['docker','inspect','siyue-miniapp-worker-1','--format','{{.Config.Image}}']).strip();assert len(sys.argv)==2 and image==sys.argv[1]
c=json.loads((aux/'config.json').read_text());req=urllib.request.Request('http://127.0.0.1:5096/internal/status',headers={'Authorization':'Bearer '+c['dispatchToken']})
for _ in range(30):
 with urllib.request.urlopen(req) as f:ready=json.load(f)['nativeReady']
 if ready:break
 time.sleep(.5)
else:raise RuntimeError('Native capability not validated/online')
needle='      Documents__Python: /auxiliary/python-dispatch';assert before.count(needle)==1
text=before.replace(needle,needle+'\n      Documents__PdfToPpm: /auxiliary-tools/pdftoppm\n      Documents__PdfToText: /auxiliary-tools/pdftotext\n      Documents__OcrPython: /auxiliary-tools/rapid-python')
needle='"/opt/siyue-auxiliary/dispatch.json:/run/siyue-aux/dispatch.json:ro"';assert text.count(needle)==1
text=text.replace(needle,needle+', "/opt/siyue-auxiliary/tools:/auxiliary-tools:ro"')
backup=aux/('compose-before-native-'+time.strftime('%Y%m%d-%H%M%S')+'.yml');backup.write_text(before)
drain=root/'data/worker-control/drain';assert not drain.exists(),'Another maintenance already draining'
try:
 drain.touch()
 for _ in range(90):
  n=int(run(['runuser','-u','postgres','--','psql','-d','siyue_miniapp','-Atc', '''SELECT count(*) FROM "Work" WHERE "State"='running';''']).strip())
  if n==0:break
  time.sleep(1)
 else:raise RuntimeError('Active documents have not drained')
 assert compose.read_text()==before,'Concurrent deployment detected'
 compose.write_text(text);run(['docker','compose','config','--quiet']);run(['docker','compose','up','-d','--no-deps','worker'])
 for tool in ['pdftoppm','pdftotext','rapid-python']:run(['docker','exec','siyue-miniapp-worker-1','test','-x','/auxiliary-tools/'+tool])
 assert run(['docker','inspect','siyue-miniapp-worker-1','--format','{{.Config.Image}}']).strip()==image
 (aux/'native-activation.json').write_text(json.dumps(dict(image=image,backup=str(backup),composeHash=hashlib.sha256(text.encode()).hexdigest(),tools=['pdftoppm','pdftotext','rapid'],tesseract='primary'),indent=2))
 print(json.dumps(dict(activated=True,image=image,tesseract='primary')))
except Exception:
 if compose.read_text()==text:
  compose.write_text(before);run(['docker','compose','up','-d','--no-deps','worker'])
 raise
finally:drain.unlink(missing_ok=True)
