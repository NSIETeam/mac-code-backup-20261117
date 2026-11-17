#!/usr/bin/env python3
"""Enable only current-head workstation previews with live proof and rollback."""
import base64,hashlib,json,pathlib,shutil,sqlite3,subprocess,time,sys
from enable_preview import signed
R=pathlib.Path('/opt/siyue-collaboration');P=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116')
def main():
 proof=json.loads((P/'workstation-four-preview-proof.json').read_text())
 if time.time()-proof['at']>1800 or proof['concurrentHealthChecks']<8 or proof['running']!=4:raise ValueError('Fresh multi-project acceptance required')
 expected=json.loads((P/'workstation-controller-baseline.json').read_text())
 for name,digest in expected.items():
  if hashlib.sha256((R/name).read_bytes()).hexdigest()!=digest:raise ValueError('Controller changed: '+name)
 with sqlite3.connect((R/'delivery.db').as_uri()+'?mode=ro',uri=True) as db:
  if db.execute("select count(*) from jobs where state='running'").fetchone()[0]:raise ValueError('Build is running; defer restart')
 sys.path.insert(0,str(R));import delivery_service as d
 cfg=d.settings();keys=[]
 for p in proof['projects']:
  if d.recipes.git_json(d,cfg,cfg['projects'][p['key']]['repository'],'branches/main')['commit']['id']!=p['sha']:raise ValueError('Repository moved; verify new head first')
  d.completed_build(p['buildId'],p['key'],p['digest']);keys.append(p['key'])
 backup=P/('controller-before-four-previews-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
 names=('delivery.json','delivery_recipes.py','workstation_client.py')
 for name in names:shutil.copy2(R/name,backup/name)
 cfg['workstation']['projects']=keys
 try:
  for name in names[1:]:
   pending=R/(name+'.pending');shutil.copyfile(P/'workstation-code'/name,pending);pending.chmod(0o644);pending.replace(R/name)
  pending=R/'delivery.json.pending';pending.write_text(json.dumps(cfg,ensure_ascii=False,indent=2));pending.chmod(0o600);pending.replace(R/'delivery.json')
  subprocess.run(['python3','-m','py_compile',*[str(R/n) for n in names[1:]]],check=True)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30)
  for attempt in range(15):
   try:signed(cfg,'/describe',dict(key=keys[0]));break
   except OSError:time.sleep(1)
  else:raise RuntimeError('Controller unavailable')
  for p in proof['projects']:
   q=dict(key=p['key'],buildId=p['buildId'],digest=p['digest'],path=cfg['projects'][p['key']]['health_path'],method='GET',viewer='four-project-acceptance')
   if signed(cfg,'/preview',q)['status']!=200:raise RuntimeError('Routed health failed: '+p['key'])
   if signed(cfg,'/preview',dict(q,path='__siyue_version.json'))['status']!=200:raise RuntimeError('Routed heartbeat failed')
 except Exception:
  for name in names:shutil.copy2(backup/name,R/name)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30);raise
 result=dict(at=time.time(),projects=keys,health=True,heartbeats=True,backup=str(backup))
 (P/'workstation-four-preview-enabled.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
if __name__=='__main__':main()
