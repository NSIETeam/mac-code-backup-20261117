"""Primary-only reversible interpreter dispatch activation. Existing binary/API are preserved."""
import hashlib,json,shutil,subprocess,time,sys
from pathlib import Path
root=Path('/opt/siyue-miniapp'); cfg=root/'compose.yml'; aux=Path('/opt/siyue-auxiliary')
before=cfg.read_text(); image=subprocess.check_output(['docker','inspect','siyue-miniapp-worker-1','--format','{{.Config.Image}}'],text=True).strip()
# Refuse unexpected live changes; a different release must be revalidated before activation.
assert len(sys.argv)==2 and image==sys.argv[1],image
liveHash=subprocess.check_output(['docker','exec','siyue-miniapp-worker-1','sha256sum','/app/pdf_pixels.py'],text=True).split()[0]
assert liveHash==json.loads((aux/'config.json').read_text())['scriptHash'],'Parser contract changed; revalidate node first'
stamp=time.strftime('%Y%m%d-%H%M%S');backup=aux/('compose-before-'+stamp+'.yml');shutil.copy2(cfg,backup)
needle="      Documents__ParsingStrategy: fixed"
assert before.count(needle)==1
text=before.replace(needle,needle+"\n      Documents__Python: /auxiliary/python-dispatch")
needle='"./data/worker-control:/worker-control:ro"'
assert text.count(needle)==1
text=text.replace(needle,needle+', "/opt/siyue-auxiliary/pixels_dispatch.py:/auxiliary/python-dispatch:ro", "/opt/siyue-auxiliary/dispatch.json:/run/siyue-aux/dispatch.json:ro"')
drain=root/'data/worker-control/drain'
assert not drain.exists(),'Worker was already draining; do not override another operation'
meta=dict(backup=str(backup),image=image,beforeHash=hashlib.sha256(before.encode()).hexdigest(),activated=False)
record=aux/'worker-activation.json';record.write_text(json.dumps(meta,indent=2))
def run(args):return subprocess.run(args,cwd=root,check=True,capture_output=True,text=True).stdout
try:
 drain.touch()
 for _ in range(60):
  running=int(run(['runuser','-u','postgres','--','psql','-d','siyue_miniapp','-Atc', '''SELECT count(*) FROM "Work" WHERE "State"='running';''']).strip())
  if running==0:break
  time.sleep(2)
 else:raise RuntimeError('Active documents still running; retry later')
 cfg.write_text(text);run(['docker','compose','config','--quiet'])
 run(['docker','compose','up','-d','--no-deps','worker'])
 actual=run(['docker','exec','siyue-miniapp-worker-1','printenv','Documents__Python']).strip();assert actual=='/auxiliary/python-dispatch'
 # Fixed shim is executable under the original non-root worker identity.
 run(['docker','exec','siyue-miniapp-worker-1','test','-x','/auxiliary/python-dispatch'])
 meta.update(activated=True,afterHash=hashlib.sha256(text.encode()).hexdigest());record.write_text(json.dumps(meta,indent=2))
 print(json.dumps(dict(activated=True,imageUnchanged=image,backup=str(backup))))
except Exception:
 cfg.write_text(before);run(['docker','compose','up','-d','--no-deps','worker']);raise
finally:drain.unlink(missing_ok=True)
