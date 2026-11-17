"""Prepare an exact, stopped destination. No application cutover occurs here."""
import io,json,os,pathlib,pwd,subprocess,tarfile,time
from homework_lab_common import *

def bundle():
    if (STATE/'bundle.json').exists():raise ValueError('Bundle already prepared; inspect before resuming')
    identity=source_identity();v,env=source()
    if not v['State']['Running']:raise ValueError('Source must be healthy and active')
    database(SOURCE/'data/isolated.db')
    if any('\n' in x or '\r' in x for x in v['Config']['Env']):raise ValueError('Multiline environment not supported')
    save('baseline.json',identity)
    p=STATE/'candidate.tar.gz';pack(SOURCE/'candidate',p)
    result=node('import pathlib,json\np=pathlib.Path('+repr(NODE)+');p.mkdir(mode=0o700,parents=True,exist_ok=False);(p/"candidate").mkdir();print(json.dumps({"created":True}))')
    send_archive(p,NODE+'/candidate')
    raw=('\n'.join(v['Config']['Env'])+'\n').encode();archive=STATE/'environment.tar.gz'
    with tarfile.open(archive,'w:gz') as t:
        info=tarfile.TarInfo('service.env');info.mode=0o600;info.size=len(raw);t.addfile(info,io.BytesIO(raw))
    archive.chmod(0o600);send_archive(archive,NODE)
    script='''import pathlib,json,hashlib,os
r=pathlib.Path(ROOT);files={}
for p in sorted((r/'candidate').rglob('*')):
 if p.is_file():
  with p.open('rb') as f:h=hashlib.file_digest(f,'sha256').hexdigest()
  files[str(p.relative_to(r/'candidate'))]={'sha256':h,'size':p.stat().st_size}
for p in [r/'candidate',*(r/'candidate').rglob('*')]:
 os.chown(p,1654,1654)
 if p.is_dir():p.chmod(0o755)
assert files==MANIFEST
print(json.dumps({'candidateVerified':True,'files':len(files)}))
'''.replace('ROOT',repr(NODE)).replace('MANIFEST',repr(identity['candidate']))
    proof=node(script);save('bundle.json',dict(at=time.time(),**proof));print(json.dumps(proof),flush=True)

def prepare():
    if (STATE/'prepared.json').exists():raise ValueError('Already prepared')
    baseline=ensure_identity();v,env=source()
    if not (STATE/'bundle.json').exists():raise ValueError('Bundle missing')
    image=json.loads((STATE/'image-manifest.json').read_text())
    script='''import pathlib,subprocess,json,hashlib
p=pathlib.Path('/opt/siyue-workstation/images')/(SHA+'.tar.gz')
assert p.stat().st_size==SIZE
with p.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==SHA
if subprocess.run(['podman','image','exists',IMAGE],stdout=subprocess.DEVNULL).returncode:
 subprocess.run(['podman','load','-i',str(p)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=240)
v=json.loads(subprocess.check_output(['podman','image','inspect',IMAGE]))[0]
assert v['Id'].removeprefix('sha256:')==IMAGE
print(json.dumps(v))
'''
    for k,x in {'SHA':image['sha256'],'SIZE':image['size'],'IMAGE':IMAGE}.items():script=script.replace(k,repr(x))
    loaded=node(script,300)
    original=json.loads(run(['docker','image','inspect',SOURCE_IMAGE],text=True))[0]
    save('image-equivalence.json',image_equivalence(original,loaded))
    user='siyue-ws-homework'
    try:account=pwd.getpwnam(user)
    except KeyError:run(['useradd','--system','--create-home','--shell','/usr/sbin/nologin',user]);account=pwd.getpwnam(user)
    key=STATE/'tunnel_key'
    if not key.exists():run(['ssh-keygen','-t','ed25519','-N','','-C','siyue-homework-lab','-f',str(key)])
    directory=pathlib.Path(account.pw_dir)/'.ssh';directory.mkdir(mode=0o700,exist_ok=True)
    auth=directory/'authorized_keys';text='restrict,port-forwarding,permitlisten="127.0.0.1:19592" '+key.with_suffix('.pub').read_text()
    if auth.exists() and auth.read_text()!=text:raise ValueError('Unexpected tunnel identity')
    auth.write_text(text);auth.chmod(0o600)
    for p in [directory,auth]:os.chown(p,account.pw_uid,account.pw_gid)
    policy='''Match User siyue-ws-homework
    ClientAliveInterval 10
    ClientAliveCountMax 2
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding remote
    PermitOpen none
    PermitListen 127.0.0.1:19592
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    MaxSessions 0
Match all
'''
    config=pathlib.Path('/etc/ssh/sshd_config.d/93-siyue-workstation-homework.conf')
    if config.exists() and config.read_text()!=policy:raise ValueError('Unexpected SSH policy')
    config.write_text(policy);run(['/usr/sbin/sshd','-t'])
    effective=run(['/usr/sbin/sshd','-T','-C','user='+user+',host=localhost,addr=127.0.0.1'],text=True).splitlines()
    for expected in ['allowtcpforwarding remote','permitopen none','permitlisten 127.0.0.1:19592','maxsessions 0','gatewayports no']:
        if expected not in effective:raise ValueError('SSH restriction missing')
    run(['systemctl','reload','ssh'])
    host=pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
    assets={'tunnel_key':key.read_bytes(),'known_hosts':('47.113.200.248 '+host[0]+' '+host[1]+'\n').encode(),'supervisor.py':(STATE/'homework_lab_supervisor.py').read_bytes()}
    archive=STATE/'supervisor.tar.gz'
    with tarfile.open(archive,'w:gz') as t:
        for name,body in assets.items():
            info=tarfile.TarInfo(name);info.mode=0o600;info.size=len(body);t.addfile(info,io.BytesIO(body))
    archive.chmod(0o600);send_archive(archive,NODE)
    script='''import pathlib,subprocess,json,socket,os
r=pathlib.Path(ROOT)
if (r/'enabled').exists():raise ValueError('Destination unexpectedly enabled')
with socket.socket() as s:s.bind(('127.0.0.1',5092))
if subprocess.run(['podman','container','exists',NAME]).returncode==0:raise ValueError('Destination already exists')
(r/'data').mkdir(mode=0o755);os.chown(r/'data',1654,1654)
args=['podman','create','--name',NAME,'--network','host','--memory','768m','--memory-swap','1536m','--cpus','0.75','--pids-limit','256',
 '--user','app','--cap-drop','ALL','--security-opt','no-new-privileges','--read-only','--tmpfs','/tmp:rw,size=64m',
 '--env-file',str(r/'service.env'),'-v',str(r/'candidate')+':/candidate:ro','-v',str(r/'data')+':/data:rw',
 '--workdir',WORKDIR,'--entrypoint','dotnet',IMAGE,'/candidate/Siyue.Server.dll']
subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
launcher=pathlib.Path('/opt/siyue-workstation/services-start.sh');old=launcher.read_text();assert old.startswith('#!/bin/bash\\n')
line='nohup python3 '+str(r/'supervisor.py')+' </dev/null 8>&- 9>&- >>'+str(r/'supervisor.log')+' 2>&1 &\\n'
assert line not in old
launcher.with_suffix('.before-homework').write_text(old);launcher.write_text(old+line);launcher.chmod(0o700)
subprocess.run([str(launcher)],check=True)
v=json.loads(subprocess.check_output(['podman','inspect',NAME]))[0];assert not v['State']['Running']
print(json.dumps({'containerId':v['Id'],'image':v['Image'],'enabled':False}))
'''
    for k,x in {'ROOT':NODE,'NAME':NAME,'WORKDIR':v['Config']['WorkingDir'],'IMAGE':IMAGE}.items():script=script.replace(k,repr(x))
    result=node(script);save('prepared.json',dict(at=time.time(),source=baseline,node=result));print(json.dumps(result),flush=True)

if __name__=='__main__':
    if sys.argv[1]=='bundle':bundle()
    elif sys.argv[1]=='prepare':prepare()
    else:raise ValueError('Unknown command')
