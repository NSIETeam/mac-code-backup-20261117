#!/usr/bin/env python3
"""Release deterministic question text display assets, with scoped routing and rollback."""
import datetime,hashlib,importlib.util,json,os,shutil,socket,sqlite3,subprocess,time
from pathlib import Path
ROOT=Path('/opt/siyue-miniapp');HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('shared',ROOT/'releases/question-bank-shared-20261102/question-bank-shared.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);p=m.p
OLD='siyue-question-bank-v5';NAME='siyue-question-bank-v6';PORT=5096;TAG='siyue-miniapp:20261102-question-bank-text-v6'
EXPECTED='sha256:10fe6779ef7c08c8cbdd4e8089c0fb10c4373e28a6362e35cc2f1744d8f2375c'
def require(value,message):m.require(value,message)
def protected():
 names=subprocess.run(['docker','ps','--format','{{.Names}}'],check=True,capture_output=True,text=True).stdout.splitlines()
 return {n:{'id':p.inspect(n)['Id'],'image':p.inspect(n)['Image'],'started':p.inspect(n)['State']['StartedAt']}for n in names if n not in [OLD,NAME]}
def main():
 before=protected();old=p.inspect(OLD);require(old['State']['Running']and old['Image']==EXPECTED,'Current bank changed; review baseline')
 conf=m.SNIPPET.read_bytes();owned=m.block(conf);require(b'5095'in owned,'Owned route changed');replacement=owned.replace(b'5095',b'5096')
 require(subprocess.run(['docker','inspect',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0,'Candidate exists')
 sock=socket.socket();sock.bind(('127.0.0.1',PORT));sock.close()
 stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S');backup=ROOT/'backups'/('question-bank-text-'+stamp);backup.mkdir(mode=0o700);shutil.copy2(m.SNIPPET,backup/'nginx.conf')
 with sqlite3.connect(ROOT/'data/question-bank/community.db')as src,sqlite3.connect(backup/'community.db')as dst:src.backup(dst)
 os.chmod(backup/'community.db',0o600);envfile=backup/'temporary.env';started=False;switched=False;created=[];report={'backup':str(backup),'businessDatabaseWrites':0,'questionDataRewritten':False,'modelApiCalls':0}
 try:
  subprocess.run(['docker','cp',OLD+':/app/QuestionBankAssets',str(backup/'QuestionBankAssets')],check=True,stdout=subprocess.DEVNULL)
  values=dict(x.split('=',1)for x in old['Config']['Env']if'='in x);values['ASPNETCORE_URLS']='http://127.0.0.1:'+str(PORT);require('default_transaction_read_only=on'in values['ConnectionStrings__Database'],'Primary database must remain read-only');p.private_env(envfile,values)
  p.run(['docker','build','-t',TAG,str(HERE)])
  p.run(['docker','run','-d','--name',NAME,'--label','com.siyue.component=question-bank','--restart','unless-stopped','--network','host','--read-only','--memory','1g','--cpus','1','--pids-limit','128','--security-opt','no-new-privileges:true','--cap-drop','ALL','--tmpfs','/work:rw,uid=1654,gid=1654,size=64m','--tmpfs','/tmp:rw,size=32m','--mount',f'type=bind,source={ROOT}/data/keys,target=/data/keys,readonly','--mount',f'type=bind,source={ROOT}/data/question-bank,target=/data/question-bank','--env-file',str(envfile),TAG]);started=True;envfile.unlink(missing_ok=True)
  local='http://127.0.0.1:'+str(PORT)
  for _ in range(40):
   try:
    if m.http(local,'/health')[0]==200:break
   except Exception:pass
   time.sleep(.25)
  expected={name:(HERE/'QuestionBankAssets'/name).read_bytes()for name in ['app.mjs','index.html']}
  def assets(base):return all(m.http(base,'/question-bank/'+name)[1]==data for name,data in expected.items())
  require(assets(local),'Candidate assets mismatch');require(m.http(local,'/api/question-bank')[0]==401,'Anonymous access allowed')
  teachers=p.json_sql('''SELECT coalesce(json_agg(x),'[]') FROM (SELECT s."Id" id,s."OpenId" open_id FROM "Staff" s JOIN "PasswordAccounts" a ON a."StaffId"=s."Id" WHERE s."Enabled" AND NOT s."Archived" AND s."Role"='teacher' AND NOT a."MustChangePassword" ORDER BY s."Id" LIMIT 1)x''');require(len(teachers)==1,'Probe identity unavailable')
  token=m.session(teachers[0],created)
  def probe(base):
   catalog=m.api(base,'/api/question-bank',token);require(catalog['count']>=121153 and catalog['uploadsEnabled'],'Catalog mismatch')
   for subject,topic in [('数学','点线距离'),('化学','限量反应物'),('地理','太阳高度角')]:
    q={'subject':subject,'stage':'高中','topic':topic,'limit':5};result=m.api(base,'/api/question-bank/recommendations',token,q);require(result['returned']>0,'Probe returned no questions')
    require(all(x['question']['analysis'] for x in result['matches']),'Analysis missing')
   return {'catalogCount':catalog['count'],'subjects':['数学','化学','地理'],'uploadsEnabled':True}
  report['candidate']=probe(local);require(protected()==before,'Other containers changed')
  current=m.SNIPPET.read_bytes();require(m.block(current)==owned,'Owned route changed concurrently');switched=True;m.switch(current.replace(owned,replacement,1))
  public='https://www.siyuejn.com/miniapp'
  for _ in range(40):
   if assets(public):break
   time.sleep(.25)
  require(assets(public),'Public assets mismatch');report['public']=probe(public);require(protected()==before,'Other containers changed')
  p.run(['docker','stop',OLD]);report.update({'ok':True,'imageId':p.inspect(NAME)['Image'],'otherContainersUnchanged':True,'uploadsPreserved':True,'publicAssetsMatch':True,'assetSha256':{n:hashlib.sha256(b).hexdigest()for n,b in expected.items()}})
 except Exception:
  if switched:
   current=m.SNIPPET.read_bytes();require(m.block(current)==replacement,'Owned route changed during rollback');m.switch(current.replace(replacement,owned,1))
  if started:p.run(['docker','rm','-f',NAME])
  report.update({'ok':False,'rolledBack':True});raise
 finally:
  for sid,hashed in created:p.sql('DELETE FROM "Sessions" WHERE "Id"='+p.quote(sid)+' AND "TokenHash"='+p.quote(hashed))
  report['temporarySessionsRemoved']=True;envfile.unlink(missing_ok=True);(backup/'deployment.json').write_text(json.dumps(report,indent=2));os.chmod(backup/'deployment.json',0o600)
 print(json.dumps(report))
if __name__=='__main__':main()
