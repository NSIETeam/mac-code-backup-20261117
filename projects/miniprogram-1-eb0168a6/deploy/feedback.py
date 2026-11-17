"""Start the independent feedback service; publish routes only after acceptance."""
from pathlib import Path
import json,os,subprocess,socket,urllib.request,time,datetime,hashlib,shutil,sys
ROOT=Path('/opt/siyue-miniapp');RELEASE=ROOT/'releases/feedback-20261104';PORT=5124;NAME='siyue-feedback-20261104'
def run(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout
def inspect(name):return json.loads(run(['docker','inspect',name]))[0]
def protected():
 names=run(['docker','ps','--format','{{.Names}}']).splitlines()
 return {n:{'id':inspect(n)['Id'],'image':inspect(n)['Image'],'started':inspect(n)['State']['StartedAt']} for n in names if n!=NAME}
def get(url):
 with urllib.request.urlopen(url,timeout=15)as r:return r.read()
def start():
 assert not (RELEASE/'baseline.json').exists(),'Already started; inspect existing receipt'
 before=protected();sock=socket.socket();sock.bind(('127.0.0.1',PORT));sock.close()
 data=ROOT/'data/feedback';data.mkdir(mode=0o700,exist_ok=True);os.chown(data,1654,1654)
 src=inspect('siyue-miniapp-api-1');env=dict(x.split('=',1)for x in src['Config']['Env']if'='in x)
 cfg={'ConnectionStrings__Database':env['ConnectionStrings__Database']+';Options=-c default_transaction_read_only=on','ASPNETCORE_URLS':'http://127.0.0.1:'+str(PORT),'Feedback__Database':'/data/feedback.db','ASPNETCORE_ENVIRONMENT':'Production','Logging__LogLevel__Microsoft.AspNetCore':'Warning'}
 f=RELEASE/'temporary.env';f.write_text('\n'.join(k+'='+v for k,v in cfg.items()));f.chmod(0o600)
 try:run(['docker','run','-d','--name',NAME,'--network','host','--restart','unless-stopped','--read-only','--memory','256m','--cpus','0.5','--pids-limit','128','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp:rw,size=32m','--mount','type=bind,source='+str(data)+',target=/data','--env-file',str(f),'siyue-feedback:20261104'])
 finally:f.unlink(missing_ok=True)
 for _ in range(40):
  try:
   if json.loads(get('http://127.0.0.1:5124/health'))['status']=='ok':break
  except Exception:pass
  time.sleep(.25)
 else:raise RuntimeError('Feedback health failed')
 assert protected()==before,'Other containers changed'
 (RELEASE/'baseline.json').write_text(json.dumps(before,indent=2));print(json.dumps({'serviceStarted':True,'publicRoutesPublished':False,'otherContainersUnchanged':True}))
def publish():
 before=json.loads((RELEASE/'baseline.json').read_text());assert protected()==before,'Other services changed; reassess before publishing'
 snippet=Path('/etc/nginx/snippets/siyue-miniapp.conf');original=snippet.read_text();assert '# BEGIN SIYUE FEEDBACK' not in original,'Already published'
 workbench=Path('/opt/siyue-workbench/current/index.html').resolve();index=workbench.read_text();assert 'support/widget.js' not in index
 stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S');backup=ROOT/'backups'/('feedback-web-'+stamp);backup.mkdir(mode=0o700)
 (backup/'nginx.conf').write_text(original);(backup/'workbench-index.html').write_text(index)
 widget='<script src="/miniapp/support/widget.js?v=20261104-1" defer></script>'
 routes='''# BEGIN SIYUE FEEDBACK
location = /miniapp/support { return 308 /miniapp/support/; }
location ^~ /miniapp/support/ {
 proxy_pass http://127.0.0.1:5124/;
 proxy_set_header Host $host;
 proxy_set_header X-Real-IP $remote_addr;
 proxy_set_header Accept-Encoding "";
 access_log off;
}
location ^~ /miniapp/api/support/ {
 proxy_pass http://127.0.0.1:5124/api/support/;
 proxy_set_header Host $host;
 proxy_set_header X-Real-IP $remote_addr;
 client_max_body_size 4m;
 proxy_read_timeout 35s;
 access_log off;
}
# END SIYUE FEEDBACK
'''
 new=original
 for location in ['location ^~ /miniapp/question-bank/ {','location ^~ /miniapp/academic-pilot/ {','location ^~ /miniapp/academic-lab/ {','location ^~ /miniapp/ {']:
  assert new.count(location)==1,location
  new=new.replace(location,location+'\n    # SIYUE FEEDBACK WIDGET\n    proxy_set_header Accept-Encoding "";\n    sub_filter_once on;\n    sub_filter \'</body>\' \''+widget+'</body>\';',1)
 new=routes+new
 try:
  assert snippet.read_text()==original and workbench.read_text()==index,'Concurrent frontend edit'
  snippet.write_text(new);run(['nginx','-t']);temp=workbench.with_suffix('.feedback.tmp');temp.write_text(index.replace('</body>',widget+'</body>'));os.chmod(temp,0o644);os.replace(temp,workbench)
  run(['nginx','-s','reload'])
  for _ in range(20):
   try:
    if b'\xe9\x97\xae\xe9\xa2\x98\xe5\x8f\x8d\xe9\xa6\x88'in get('https://www.siyuejn.com/miniapp/support/'):break
   except Exception:pass
   time.sleep(.25)
  assert b'support/widget.js'in get('https://www.siyuejn.com/miniapp/workbench/')
  assert b'support/widget.js'in get('https://www.siyuejn.com/miniapp/question-bank/')
  assert protected()==before,'Other containers changed'
 except Exception:
  snippet.write_text(original);workbench.write_text(index);run(['nginx','-t']);run(['nginx','-s','reload']);raise
 result={'published':True,'url':'https://www.siyuejn.com/miniapp/support/','backup':str(backup),'otherContainersUnchanged':True,'workbenchWidget':True,'questionBankWidget':True,'accountChanges':0}
 (RELEASE/'deployment.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
if __name__=='__main__':{'start':start,'publish':publish}[sys.argv[1]]()
