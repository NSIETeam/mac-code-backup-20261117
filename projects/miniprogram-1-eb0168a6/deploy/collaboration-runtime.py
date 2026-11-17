#!/usr/bin/env python3
import json,os,pathlib,pwd,subprocess,time,urllib.request,shutil,re
ROOT=pathlib.Path('/opt/siyue-collaboration')
os.chdir(ROOT)
def run(args,**kw):
 r=subprocess.run(args,capture_output=True,text=True,**kw)
 if r.returncode:raise RuntimeError(str(args[0])+' failed: '+r.stderr[-1000:])
 return r.stdout
def write(p,text,mode=0o600):p=pathlib.Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);p.chmod(mode)
s=json.loads((ROOT/'secrets.json').read_text());info=pwd.getpwnam('siyue-collab');build=pwd.getpwnam('siyue-build')
# A separate bounded filesystem contains source, image cache, build output and temporary files.
disk=ROOT/'build-storage.img';buildroot=pathlib.Path('/var/lib/siyue-build')
if not disk.exists():
 run(['truncate','-s','4G',str(disk)]);run(['mkfs.ext4','-F',str(disk)])
if not os.path.ismount(buildroot):
 if list(buildroot.iterdir()):shutil.copytree(buildroot,ROOT/'build-home-initial',dirs_exist_ok=True)
 run(['mount','-o','loop,nosuid,nodev',str(disk),str(buildroot)]);os.chown(buildroot,build.pw_uid,build.pw_gid)
fstab=pathlib.Path('/etc/fstab');line=f'{disk} {buildroot} ext4 loop,nosuid,nodev,nofail 0 0\n'
if str(disk) not in fstab.read_text():
 shutil.copy2(fstab,ROOT/'fstab-before');fstab.write_text(fstab.read_text()+'\n'+line)
for path in ['/etc/subuid','/etc/subgid']:
 p=pathlib.Path(path)
 if 'siyue-build:' not in p.read_text():p.write_text(p.read_text()+'siyue-build:500000:65536\n')
runtime=pathlib.Path('/run/user')/str(build.pw_uid);runtime.mkdir(parents=True,exist_ok=True);os.chown(runtime,build.pw_uid,build.pw_gid);runtime.chmod(0o700)
run(['loginctl','enable-linger','siyue-build'])
builder=['runuser','-u','siyue-build','--','env',f'XDG_RUNTIME_DIR={runtime}',f'DBUS_SESSION_BUS_ADDRESS=unix:path={runtime}/bus','podman']
# Small, pinned execution image; arbitrary network access is disabled during every build.
image='localhost/siyue-check:node24'
if subprocess.run(builder+['image','exists',image],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
 # Build a clean runtime from selected executables/libraries already present on this host.
 # No application directory, config, home directory or production credential is copied.
 source='siyue-cs-dev-runtime:local';clean=ROOT/'check-image';rootfs=clean/'rootfs';rootfs.mkdir(parents=True,exist_ok=True)
 binaries=run(['docker','run','--rm','--network','none','--read-only','--entrypoint','sh',source,'-c','for binary in sh cp mkdir env node; do command -v "$binary"; done']).splitlines()
 dependencies=set(binaries)
 for binary in binaries:
  output=run(['docker','run','--rm','--network','none','--read-only','--entrypoint','/lib64/ld-linux-x86-64.so.2',source,'--list',binary]);dependencies.update(re.findall(r'(/[^\s()]+)',output))
 container=run(['docker','create','--entrypoint','true',source]).strip()
 try:
  for path in sorted(dependencies):
   dest=rootfs/path.lstrip('/');dest.parent.mkdir(parents=True,exist_ok=True);run(['docker','cp','-L',container+':'+path,str(dest)])
 finally:run(['docker','rm',container])
 (rootfs/'tmp').mkdir(exist_ok=True);(rootfs/'tmp').chmod(0o1777)
 write(clean/'Dockerfile','FROM scratch\nCOPY rootfs/ /\nENV PATH=/usr/local/bin:/usr/bin:/bin\nWORKDIR /workspace\n',0o644)
 run(['docker','build','--pull=false','--network=none','-t','siyue-collaboration-check:node24',str(clean)],timeout=120)
 archive=buildroot/'check-image.tar';run(['docker','save','-o',str(archive),'siyue-collaboration-check:node24'],timeout=90);os.chown(archive,build.pw_uid,build.pw_gid)
 run(builder+['load','-i',str(archive)],timeout=120);archive.unlink()
 loaded=json.loads(run(builder+['images','--format','json']))
 selected=next(x['Id'] for x in loaded if any('siyue-collaboration-check' in n for n in x.get('Names',[])))
 run(builder+['tag',selected,image])
image_info=json.loads(run(builder+['image','inspect',image]))[0]
check_image=image_info['Id']
delivery={'secret':s['delivery'],'gitea_url':'http://127.0.0.1:5300','gitea_token':s['gitea_token'],'builder_uid':build.pw_uid,'builder_gid':build.pw_gid,'builder_user':'siyue-build','minimum_free_mb':900,'projects':{'collaboration-pilot':{'repository':'siyue-projects/collaboration-pilot','mode':'static','check_image':check_image,'check_command':['sh','-c','node --test && mkdir -p dist && cp -R site/. dist/'],'output':'dist','timeout':120,'production_health_url':'https://www.siyuejn.com/collaboration-pilot/__siyue_version.json'}}}
if not (ROOT/'delivery.json').exists():write(ROOT/'delivery.json',json.dumps(delivery,indent=2))
shutil.copy2(ROOT/'incoming/delivery_service.py',ROOT/'delivery_service.py');(ROOT/'delivery_service.py').chmod(0o700)
write('/etc/systemd/system/siyue-collaboration-delivery.service',f'''[Unit]
Description=Siyue trusted build and release controller
After=network.target docker.service postgresql.service
RequiresMountsFor=/var/lib/siyue-build
[Service]
ExecStart=/usr/bin/python3 {ROOT}/delivery_service.py
Restart=on-failure
MemoryMax=192M
CPUQuota=50%
UMask=0077
[Install]
WantedBy=multi-user.target
''',0o644)
stage=ROOT/'releases/20261114-v1';stage.mkdir(parents=True,exist_ok=True);run(['tar','-xzf',str(ROOT/'incoming/app.tgz'),'-C',str(stage)])
for p in [stage,*stage.rglob('*')]:p.chmod(0o755 if p.is_dir() else 0o644)
write(stage/'Dockerfile','FROM siyue-miniapp:20261114-usage-dashboard\nWORKDIR /collaboration\nENV TMPDIR=/tmp\nCOPY . .\nENTRYPOINT ["dotnet", "Collaboration.dll"]\n',0o644)
run(['docker','build','--pull=false','--network=none','-t','siyue-collaboration:20261114-v1',str(stage)],timeout=120)
exists=subprocess.run(['docker','inspect','siyue-collaboration-api'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
if exists:raise RuntimeError('Existing collaboration container must be updated via a reviewed replacement')
run(['docker','run','-d','--name','siyue-collaboration-api','--restart','unless-stopped','--network','host','--user',f'{info.pw_uid}:{info.pw_gid}','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--memory=256m','--cpus=0.5','--pids-limit=128','--tmpfs','/tmp:rw,size=64m','-v',str(ROOT/'config')+':/config:ro','-v',str(ROOT/'keys')+':/keys','-v',str(ROOT/'attachments')+':/attachments','-e','ASPNETCORE_URLS=http://127.0.0.1:5301','-e','CollaborationConfig=/config/settings.json','siyue-collaboration:20261114-v1'])
for _ in range(40):
 try:
  with urllib.request.urlopen('http://127.0.0.1:5301/health',timeout=2) as response:
   if response.status==200:break
 except Exception:time.sleep(1)
else:raise RuntimeError('Collaboration API did not start')
nginx=pathlib.Path('/etc/nginx/sites-enabled/siyue-crm.conf').resolve();before=nginx.read_text();shutil.copy2(nginx,ROOT/'nginx-before.conf');snippet=pathlib.Path('/etc/nginx/snippets/siyue-collaboration.conf');shutil.copy2(ROOT/'incoming/nginx.conf',snippet);snippet.chmod(0o644)
marker=' server_name www.siyuejn.com;';include=' include /etc/nginx/snippets/siyue-collaboration.conf;'
if include not in before:
 if before.count(marker)!=1:raise RuntimeError('Unexpected nginx structure')
 nginx.write_text(before.replace(marker,marker+'\n'+include))
try:run(['nginx','-t']);run(['systemctl','reload','nginx'])
except Exception:nginx.write_text(before);raise
run(['systemctl','daemon-reload']);run(['systemctl','enable','--now','siyue-collaboration-delivery'])
print('Collaboration API, rootless build sandbox, release controller and path routes installed')
