#!/usr/bin/env python3
"""Update only the independent collaboration API, retaining the previous container."""
import os,pathlib,pwd,subprocess,sys,time,urllib.request
ROOT=pathlib.Path('/opt/siyue-collaboration');os.chdir(ROOT)
stamp=time.strftime('%Y%m%d-%H%M%S');stage=ROOT/'releases'/stamp;stage.mkdir(parents=True)
def run(args):return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True)
run(['tar','-xzf',str(ROOT/'incoming/app.tgz'),'-C',str(stage)])
for p in [stage,*stage.rglob('*')]:p.chmod(0o755 if p.is_dir() else 0o644)
(stage/'Dockerfile').write_text('FROM siyue-miniapp:20261114-usage-dashboard\nWORKDIR /collaboration\nENV TMPDIR=/tmp\nCOPY . .\nENTRYPOINT ["dotnet", "Collaboration.dll"]\n')
image='siyue-collaboration:'+stamp;run(['docker','build','--pull=false','--network=none','-t',image,str(stage)])
backup=ROOT/'backups'/stamp;backup.mkdir(parents=True,mode=0o700)
with (backup/'collaboration.dump').open('wb') as f:subprocess.run(['runuser','-u','postgres','--','pg_dump','-Fc','siyue_collaboration'],stdout=f,check=True)
old='siyue-collaboration-api';previous=old+'-before-'+stamp
run(['docker','stop',old]);run(['docker','rename',old,previous]);run(['docker','update','--restart=no',previous])
user=pwd.getpwnam('siyue-collab')
try:
 run(['docker','run','-d','--name',old,'--restart','unless-stopped','--network','host','--user',f'{user.pw_uid}:{user.pw_gid}','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--memory=256m','--cpus=0.5','--pids-limit=128','--log-opt','max-size=10m','--log-opt','max-file=3','--tmpfs','/tmp:rw,size=64m','-v',str(ROOT/'config')+':/config:ro','-v',str(ROOT/'keys')+':/keys','-v',str(ROOT/'attachments')+':/attachments','-e','ASPNETCORE_URLS=http://127.0.0.1:5301','-e','CollaborationConfig=/config/settings.json',image])
 for _ in range(60):
  try:
   with urllib.request.urlopen('http://127.0.0.1:5301/health',timeout=2) as r:
    if r.status==200:break
  except Exception:time.sleep(1)
 else:raise RuntimeError('New API failed health check')
except Exception:
 run(['docker','rm','-f',old]);run(['docker','rename',previous,old]);run(['docker','update','--restart=unless-stopped',old]);run(['docker','start',old]);raise
print('Independent API updated; previous container retained: '+previous)
