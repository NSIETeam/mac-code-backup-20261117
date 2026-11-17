#!/usr/bin/env python3
"""Enable verified node builds with controller source checks and rollback."""
import hashlib,json,pathlib,shutil,sqlite3,subprocess,time
from enable_preview import signed
R=pathlib.Path('/opt/siyue-collaboration');P=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116')
def main():
 expected='03d87dea496aa292cabb09efc837e0fb33e6f9115b2ed4c43607c9c0a71ebce9'
 if hashlib.sha256((R/'delivery_service.py').read_bytes()).hexdigest()!=expected:raise ValueError('Controller changed; reconcile first')
 for key in ('siyue-admissions-scheduling','siyue-miniapp-teaching'):
  proof=json.loads((P/('remote-build-proof-'+key+'.json')).read_text());receipt=proof['receipt'];limits=receipt['limits']
  if receipt['state']!='succeeded' or receipt['backend']!='workstation' or limits['exitCode']!=0 or limits['memoryLimit']>1024**3 or limits['cpuQuota']!=100000 or limits['pidsLimit']!=256:raise ValueError('Live build acceptance missing')
 with sqlite3.connect((R/'delivery.db').as_uri()+'?mode=ro',uri=True) as db:
  if db.execute("select count(*) from jobs where state='running'").fetchone()[0]:raise ValueError('Controller job running; defer restart')
 cfg=json.loads((R/'delivery.json').read_text());backup=P/('controller-before-node-builds-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
 for name in ('delivery_service.py','delivery.json'):shutil.copy2(R/name,backup/name)
 client=R/'workstation_build.py';existed=client.exists()
 if existed:shutil.copy2(client,backup/client.name)
 cfg['workstation']['buildProjects']=['siyue-admissions-scheduling','siyue-miniapp-teaching','siyue-miniapp-collaboration']
 try:
  for name in ('workstation_build.py','delivery_service.py'):
   temp=R/(name+'.pending');shutil.copyfile(P/'workstation-code'/name,temp);temp.chmod(0o644);temp.replace(R/name)
  temp=R/'delivery.json.pending';temp.write_text(json.dumps(cfg,ensure_ascii=False,indent=2));temp.chmod(0o600);temp.replace(R/'delivery.json')
  subprocess.run(['python3','-m','py_compile',str(R/'delivery_service.py'),str(client)],check=True)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30)
  for attempt in range(15):
   try:signed(cfg,'/describe',dict(key='siyue-admissions-scheduling'));break
   except OSError:time.sleep(1)
  else:raise RuntimeError('Controller health failed')
 except Exception:
  for name in ('delivery_service.py','delivery.json'):shutil.copy2(backup/name,R/name)
  if existed:shutil.copy2(backup/client.name,client)
  else:client.unlink(missing_ok=True)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30);raise
 result=dict(buildProjects=cfg['workstation']['buildProjects'],backup=str(backup),at=time.time());(P/'workstation-builds-enabled.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
if __name__=='__main__':main()
