#!/usr/bin/env python3
"""Enable one verified workstation preview with an automatic controller rollback."""
import base64,hashlib,hmac,json,os,pathlib,shutil,sqlite3,subprocess,time,urllib.request
R=pathlib.Path('/opt/siyue-collaboration')
P=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116')
def signed(cfg,path,payload):
 body=json.dumps(payload).encode();stamp=str(int(time.time()));sig=hmac.new(cfg['secret'].encode(),stamp.encode()+b'\n'+path.encode()+b'\n'+body,hashlib.sha256).hexdigest()
 req=urllib.request.Request('http://127.0.0.1:5302'+path,data=body,headers={'Content-Type':'application/json','X-Siyue-Time':stamp,'X-Siyue-Signature':sig})
 with urllib.request.urlopen(req,timeout=100) as f:return json.load(f)
def main():
 baseline='5eca379be8ec3673c6639b73c4e02a5e40114b27ad328773eac3111dbd389945'
 if hashlib.sha256((R/'delivery_recipes.py').read_bytes()).hexdigest()!=baseline:raise ValueError('Controller changed; reconcile before deployment')
 with sqlite3.connect((R/'delivery.db').as_uri()+'?mode=ro',uri=True) as db:
  if db.execute("select count(*) from jobs where state='running'").fetchone()[0]:raise ValueError('A build is running; defer controller restart')
 proof=json.loads((P/'admissions-workstation-functional.json').read_text())
 if not all(proof.get(k) is True for k in ('login','read','write','readAfterWrite','staleWriteRejected')):raise ValueError('Functional acceptance missing')
 isolation=json.loads((P/'admissions-workstation-isolation.json').read_text());limits=isolation['limits']
 if isolation['user']!='1000:1000' or limits['Memory']!=384*1024**2 or limits['CpuQuota']!=50000 or limits['NetworkMode']!='none' or not limits['ReadonlyRootfs']:raise ValueError('Isolation acceptance missing')
 backup=P/('controller-before-workstation-'+time.strftime('%Y%m%d-%H%M%S'));backup.mkdir(mode=0o700)
 for name in ('delivery.json','delivery_recipes.py'):
  shutil.copy2(R/name,backup/name)
 client=R/'workstation_client.py';had_client=client.exists()
 if had_client:shutil.copy2(client,backup/client.name)
 cfg=json.loads((R/'delivery.json').read_text())
 cfg['workstation']=dict(enabled=True,projects=['siyue-admissions-scheduling'],host='127.0.0.1',port=5399,identity=str(P/'workstation-access/controller'),knownHosts=str(P/'workstation-access/workstation_known_hosts'))
 try:
  for name in ('delivery_recipes.py','workstation_client.py'):
   source=P/'workstation-code'/name;dest=R/(name+'.pending');shutil.copyfile(source,dest);dest.chmod(0o644);dest.replace(R/name)
  dest=R/'delivery.json.pending';dest.write_text(json.dumps(cfg,ensure_ascii=False,indent=2));dest.chmod(0o600);dest.replace(R/'delivery.json')
  subprocess.run(['python3','-m','py_compile',str(R/'delivery_recipes.py'),str(client)],check=True)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30)
  for attempt in range(15):
   try:signed(cfg,'/describe',dict(key='siyue-admissions-scheduling'));break
   except (OSError,ValueError):time.sleep(1)
  else:raise RuntimeError('Controller health did not recover')
  q=dict(key='siyue-admissions-scheduling',buildId='eb1cc7de-3410-4c36-8e93-f2fa98b6931f',digest='612f2ef99ac9e7c5c92911f86d94844ccad30806589e0b84c11610c370475f6c',path='api/ready',method='GET',viewer='workstation-acceptance')
  result=signed(cfg,'/preview',q)
  if result['status']!=200 or json.loads(base64.b64decode(result['body'])).get('ok') is not True:raise RuntimeError('Live controller preview health failed')
  result=signed(cfg,'/preview',dict(q,path='index.html'))
  if result['status']!=200 or len(base64.b64decode(result['body']))<1000 or not result.get('hint'):raise RuntimeError('Live controller page or preview login hint failed')
 except Exception:
  for name in ('delivery.json','delivery_recipes.py'):shutil.copy2(backup/name,R/name)
  if had_client:shutil.copy2(backup/client.name,client)
  else:client.unlink(missing_ok=True)
  subprocess.run(['systemctl','restart','siyue-collaboration-delivery'],check=True,timeout=30)
  raise
 result=dict(enabled=True,project='siyue-admissions-scheduling',buildId=q['buildId'],previewHealth=200,pageHealth=200,backup=str(backup))
 (P/'workstation-preview-enabled.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
if __name__=='__main__':main()
