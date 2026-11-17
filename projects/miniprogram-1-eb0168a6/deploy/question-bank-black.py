#!/usr/bin/env python3
"""Deploy the black palette and transparent laboratory logo on a dedicated container."""
import datetime,hashlib,importlib.util,json,os,shutil,socket,sqlite3,subprocess,time
from pathlib import Path
ROOT=Path('/opt/siyue-miniapp');HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('shared',ROOT/'releases/question-bank-shared-20261102/question-bank-shared.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);p=m.p
OLD='siyue-question-bank-v3';NAME='siyue-question-bank-v4';PORT=5094;TAG='siyue-miniapp:20261102-question-bank-black-logo-v4'
def require(v,message):m.require(v,message)
def main():
 before=m.protected();old=p.inspect(OLD);require(old['State']['Running'],'Current bank must be running');require(old['Image']=='sha256:5319ef27b0d605a8dd57840e151cc9229dabb3e304319d5d81539f4856886294','Current bank image changed')
 conf=m.SNIPPET.read_bytes();owned=m.block(conf);require(b'5093'in owned,'Owned proxy block changed');replacement=owned.replace(b'5093',b'5094')
 require(subprocess.run(['docker','inspect',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0,'Candidate already exists')
 sock=socket.socket();sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);sock.bind(('127.0.0.1',PORT));sock.close()
 stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S');backup=ROOT/'backups'/('question-bank-black-'+stamp);backup.mkdir(mode=0o700);shutil.copy2(m.SNIPPET,backup/'nginx.conf')
 with sqlite3.connect(ROOT/'data/question-bank/community.db')as src,sqlite3.connect(backup/'community.db')as dst:src.backup(dst)
 os.chmod(backup/'community.db',0o600);envfile=backup/'temporary.env';started=False;switched=False;report={'backup':str(backup),'serverCodeChanged':True,'primaryDatabaseWrites':0}
 try:
  values=dict(x.split('=',1)for x in old['Config']['Env']if'='in x);values['ASPNETCORE_URLS']='http://127.0.0.1:'+str(PORT);require('default_transaction_read_only=on'in values['ConnectionStrings__Database'],'Primary database must remain read-only');p.private_env(envfile,values)
  p.run(['docker','build','-t',TAG,str(HERE)])
  p.run(['docker','run','-d','--name',NAME,'--label','com.siyue.component=question-bank','--restart','unless-stopped','--network','host','--read-only','--memory','1g','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true','--cap-drop','ALL','--tmpfs','/work:rw,uid=1654,gid=1654,size=64m','--tmpfs','/tmp:rw,size=32m','--mount',f'type=bind,source={ROOT}/data/keys,target=/data/keys,readonly','--mount',f'type=bind,source={ROOT}/data/question-bank,target=/data/question-bank','--env-file',str(envfile),TAG]);started=True;envfile.unlink(missing_ok=True)
  local='http://127.0.0.1:'+str(PORT)
  for _ in range(40):
   try:
    if m.http(local,'/health')[0]==200:break
   except Exception:pass
   time.sleep(.25)
  expected={name:(HERE/'publish/QuestionBankAssets'/name).read_bytes()for name in ['index.html','style.css','app.mjs','siyue-ai-logo.png']}
  def assets(base):
   return all(m.http(base,'/question-bank/'+name)[1]==data for name,data in expected.items())
  require(assets(local),'Candidate mobile assets mismatch');require(m.http(local,'/api/question-bank')[0]==401,'Anonymous catalog allowed');require(m.http(local,'/api/question-bank/uploads',body={})[0]==401,'Anonymous upload allowed')
  session=json.loads((HERE/'session-private.json').read_text());token=session['token']
  result=m.api(local,'/api/question-bank/recommendations',token,{'subject':'数学','stage':'高中','topic':'点线距离','type':'填空题','limit':5});require(result['returned']==5,'Candidate recommendation failed')
  require(m.protected()==before,'Other services changed');current=m.SNIPPET.read_bytes();require(m.block(current)==owned,'Owned proxy changed concurrently');switched=True;m.switch(current.replace(owned,replacement,1))
  for _ in range(40):
   if assets('https://www.siyuejn.com/miniapp'):break
   time.sleep(.25)
  require(assets('https://www.siyuejn.com/miniapp'),'Public mobile assets mismatch');require(m.protected()==before,'Other services changed during switch')
  require(m.api('https://www.siyuejn.com/miniapp','/api/question-bank',token)['uploadsEnabled'],'Public upload service unavailable');p.run(['docker','stop',OLD])
  report.update({'ok':True,'imageId':p.inspect(NAME)['Image'],'protectedServicesUnchanged':True,'uploadsPreserved':True,'publicAssetsMatch':True,'portal':'https://www.siyuejn.com/miniapp/question-bank/'})
 except Exception:
  if switched:
   current=m.SNIPPET.read_bytes();require(m.block(current)==replacement,'Owned block changed during rollback');m.switch(current.replace(replacement,owned,1))
  if started:p.run(['docker','rm','-f',NAME])
  report.update({'ok':False,'rolledBack':True});raise
 finally:
  envfile.unlink(missing_ok=True);(backup/'deployment.json').write_text(json.dumps(report,indent=2));os.chmod(backup/'deployment.json',0o600)
 print(json.dumps(report))
if __name__=='__main__':main()
