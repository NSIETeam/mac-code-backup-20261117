#!/usr/bin/env python3
"""Prepare the inventoried candidate service, leaving its old instance running."""
import hashlib,io,json,os,pathlib,pwd,shlex,shutil,subprocess,sys,tarfile,time

ROOT=pathlib.Path('/opt/siyue-idp-candidate/v100')
STATE=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/idp-candidate-migration')
NODE='/opt/siyue-workstation/services/idp-candidate'
IMAGE='sha256:42ef7b8c90aa04417bdca7e0c9a4bd896bd3725fbcfb35780b6b6dc7640ba6eb'
NODE_IMAGE='sha256:261fbe54398fb9c73a271986a59d7d23a5ef17cc3086004f180204dbed1f791c'

def run(args,**kw):return subprocess.run(args,check=True,capture_output=True,timeout=180,**kw).stdout

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
    sys.path.insert(0,str(ROOT));from common import load,db_command,require_mysql
    from probe import verify
    from dependency_guard import dependent_snapshot
    root,state,private=load(ROOT)
    if state['imageId']!=IMAGE:raise ValueError('Candidate image changed')
    # Docker's containerd store identifies the manifest; Podman reports the config digest.
    # Verify the exported config and filesystem layers instead of assuming equal ID formats.
    with tarfile.open(STATE/'image.tar.gz') as archive:
        manifests=json.load(archive.extractfile('manifest.json'))
        if len(manifests)!=1:raise ValueError('Unexpected exported images')
        raw=archive.extractfile(manifests[0]['Config']).read()
        if 'sha256:'+hashlib.sha256(raw).hexdigest()!=NODE_IMAGE:raise ValueError('Exported image config changed')
        source=json.loads(run(['docker','image','inspect',IMAGE]))[0]
        if source['RootFS']['Layers']!=json.loads(raw)['rootfs']['diff_ids']:raise ValueError('Exported filesystem layers changed')
    renewed=json.loads((STATE/'tls-renewed.json').read_text())
    if renewed['caSha256']!=hashlib.sha256((ROOT/'ca.crt').read_bytes()).hexdigest():raise ValueError('CA changed after acceptance')
    if (STATE/'prepared.json').exists():raise ValueError('Preparation already completed; inspect receipt')
    before=dict(protocol=verify(root,state,private),dependencies=dependent_snapshot(root,state,private))
    (STATE/'baseline.json').write_text(json.dumps(before,indent=2))
    require_mysql(ROOT/'mysql-admin.cnf',state['schema'],state['mysqlServerUuid'])
    dump=STATE/'database-before.sql'
    if not dump.exists():
        raw=run(db_command('mysqldump',ROOT/'mysql-admin.cnf','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF',state['schema']))
        if not 1000<len(raw)<64*1024**2:raise ValueError('Unexpected database snapshot size')
        dump.write_bytes(raw);dump.chmod(0o600)
    user='siyue-ws-idp'
    try:account=pwd.getpwnam(user)
    except KeyError:
        run(['useradd','--system','--create-home','--shell','/usr/sbin/nologin',user]);account=pwd.getpwnam(user)
    key=STATE/'tunnel_key'
    if not key.exists():run(['ssh-keygen','-t','ed25519','-N','','-C','siyue-candidate-migration','-f',str(key)])
    directory=pathlib.Path(account.pw_dir)/'.ssh';directory.mkdir(mode=0o700,exist_ok=True)
    auth=directory/'authorized_keys';expected='restrict,port-forwarding,permitopen="127.0.0.1:50571",permitlisten="127.0.0.1:19449" '+key.with_suffix('.pub').read_text()
    if auth.exists() and auth.read_text()!=expected:raise ValueError('Unexpected existing candidate tunnel key')
    auth.write_text(expected);auth.chmod(0o600)
    for p in [directory,auth]:os.chown(p,account.pw_uid,account.pw_gid)
    policy='''Match User siyue-ws-idp
    ClientAliveInterval 10
    ClientAliveCountMax 2
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding yes
    PermitOpen 127.0.0.1:50571
    PermitListen 127.0.0.1:19449
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    MaxSessions 0
Match all
'''
    conf=pathlib.Path('/etc/ssh/sshd_config.d/91-siyue-workstation-idp.conf')
    if conf.exists() and conf.read_text()!=policy:raise ValueError('Unexpected existing candidate SSH policy')
    conf.write_text(policy);run(['/usr/sbin/sshd','-t'])
    effective=run(['/usr/sbin/sshd','-T','-C','user='+user+',host=localhost,addr=127.0.0.1']).decode().splitlines()
    for line in ['allowtcpforwarding yes','permitopen 127.0.0.1:50571','permitlisten 127.0.0.1:19449','maxsessions 0','gatewayports no']:
        if line not in effective:raise ValueError('Candidate SSH policy not effective')
    run(['systemctl','reload','ssh'])
    old=json.loads(run(['docker','inspect','siyue-idp-candidate-v100']))[0]
    if old['Image']!=IMAGE or not old['State']['Running']:raise ValueError('Old candidate unexpectedly changed')
    values=dict(x.split('=',1) for x in old['Config']['Env'] if '=' in x and x.startswith(('KC_','JAVA_OPTS_APPEND=')))
    # Existing DB user is limited to the candidate schema. Bootstrap admin secrets stay on the server.
    for k in list(values):
        if k.startswith('KC_BOOTSTRAP_'):del values[k]
    if any('\n' in v for v in values.values()):raise ValueError('Invalid multiline environment value')
    host=pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
    assets={'service.env':('\n'.join(k+'='+v for k,v in values.items())+'\n').encode(),
            'tunnel_key':key.read_bytes(),'known_hosts':('47.113.200.248 '+host[0]+' '+host[1]+'\n').encode(),
            'supervisor.py':(STATE/'candidate_supervisor.py').read_bytes()}
    buf=io.BytesIO()
    with tarfile.open(fileobj=buf,mode='w') as tar:
        for name,body in assets.items():
            info=tarfile.TarInfo(name);info.size=len(body);info.mode=0o600;tar.addfile(info,io.BytesIO(body))
        for p in (ROOT/'tls').iterdir():
            if p.is_file() and not p.is_symlink():tar.add(p,arcname='tls/'+p.name,recursive=False)
        for p in (ROOT/'theme-siyue-review/themes/siyue').rglob('*'):
            if p.is_symlink():raise ValueError('Unexpected theme symlink')
            if p.is_file():tar.add(p,arcname='theme/'+str(p.relative_to(ROOT/'theme-siyue-review/themes/siyue')),recursive=False)
    ssh=w.command(d.settings()['workstation'])[:-1]
    shell='umask 077; mkdir -p '+NODE+'; tar -xf - -C '+NODE
    subprocess.run(ssh+[shell],input=buf.getvalue(),check=True,timeout=60)
    script='''import pathlib,subprocess,json,os
r=pathlib.Path('/opt/siyue-workstation/services/idp-candidate');r.parent.chmod(0o700);r.chmod(0o700)
for name in ['tls','theme']:os.chown(r/name,1000,0);(r/name).chmod(0o500)
for p in (r/'tls').iterdir():os.chown(p,1000,0);p.chmod(0o400)
for p in (r/'theme').rglob('*'):
 os.chown(p,1000,0);p.chmod(0o500 if p.is_dir() else 0o400)
(r/'data').mkdir(exist_ok=True);os.chown(r/'data',1000,0);(r/'data').chmod(0o700)
image='sha256:261fbe54398fb9c73a271986a59d7d23a5ef17cc3086004f180204dbed1f791c'
v=json.loads(subprocess.check_output(['podman','image','inspect',image]))[0]
assert v['Id'].removeprefix('sha256:')==image.removeprefix('sha256:')
name='siyue-service-idp-candidate'
if subprocess.run(['podman','container','exists',name]).returncode==0:raise ValueError('Existing candidate container must be inspected')
args=['podman','create','--name',name,'--network','host','--memory','1g','--memory-swap','2g','--cpus','1','--pids-limit','256','--user','1000:0','--cap-drop','ALL','--security-opt','no-new-privileges','--read-only','--tmpfs','/tmp:rw,size=128m,mode=1777','--env-file',str(r/'service.env'),'-v',str(r/'tls')+':/candidate-tls:ro','-v',str(r/'theme')+':/opt/keycloak/themes/siyue:ro','-v',str(r/'data')+':/opt/keycloak/data:rw',image,'start','--optimized']
subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
# Validate mount traversal as the actual application user before stopping the old service.
subprocess.run(['podman','run','--rm','--network','none','--read-only','--memory','64m','--cpus','0.25','--pids-limit','32','--cap-drop','ALL','--security-opt','no-new-privileges','--user','1000:0','--volumes-from',name+':ro','--entrypoint','/bin/sh',image,'-c','test -r /candidate-tls/server.crt && test -r /candidate-tls/server.key && test -x /opt/keycloak/themes/siyue'],check=True)
launcher=pathlib.Path('/opt/siyue-workstation/services-start.sh')
content='#!/bin/bash\\nset -euo pipefail\\numask 077\\nnohup python3 '+str(r/'supervisor.py')+' </dev/null 8>&- 9>&- >>'+str(r/'supervisor.log')+' 2>&1 &\\n'
if launcher.exists() and launcher.read_text()!=content:raise ValueError('Unexpected service launcher')
launcher.write_text(content);launcher.chmod(0o700)
main=pathlib.Path('/opt/siyue-workstation/run.sh');text=main.read_text();line='if [ -x /opt/siyue-workstation/services-start.sh ]; then /opt/siyue-workstation/services-start.sh; fi\\n'
if line not in text:
 assert '\\nwhile true; do\\n' in text
 main.with_suffix('.before-services').write_text(text)
 main.write_text(text.replace('\\nwhile true; do\\n','\\n'+line+'while true; do\\n'));main.chmod(0o700)
subprocess.run([str(launcher)],check=True)
print(json.dumps(dict(containerCreated=True,enabled=False,image=image)))
'''
    result=subprocess.run(ssh+['python3 -'],input=script,text=True,capture_output=True,timeout=60)
    if result.returncode:raise RuntimeError('Node provisioning failed: '+result.stderr[-500:])
    receipt=dict(at=time.time(),image=IMAGE,nodeImage=NODE_IMAGE,schema=state['schema'],databaseServerUuid=state['mysqlServerUuid'],databaseSnapshotSha256=hashlib.sha256(dump.read_bytes()).hexdigest(),databaseRetainedOnController=True,node=NODE,sourceContainerId=old['Id'])
    (STATE/'prepared.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt),flush=True)

if __name__=='__main__':main()
