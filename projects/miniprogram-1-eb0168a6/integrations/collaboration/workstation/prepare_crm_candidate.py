"""Stage the exact running Java candidate; do not stop or start either app copy."""
import io,json,os,pathlib,pwd,subprocess,tarfile,time
from crm_candidate_common import *

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    if (STATE/'prepared.json').exists():raise ValueError('Preparation already completed')
    identity,env=source_identity();before=json.loads((STATE/'source-baseline.json').read_text())
    if identity!=before:raise ValueError('Source changed after baseline')
    if not json.loads((STATE/'jre-node-proof.json').read_text())['javaVerified']:raise ValueError('JRE not verified')
    if not (STATE/'baseline-protocol.json').exists():raise ValueError('No authenticated baseline')
    user='siyue-ws-crm'
    try:account=pwd.getpwnam(user)
    except KeyError:
        run(['useradd','--system','--create-home','--shell','/usr/sbin/nologin',user]);account=pwd.getpwnam(user)
    key=STATE/'tunnel_key'
    if not key.exists():run(['ssh-keygen','-t','ed25519','-N','','-C','siyue-crm-candidate-migration','-f',str(key)])
    directory=pathlib.Path(account.pw_dir)/'.ssh';directory.mkdir(mode=0o700,exist_ok=True)
    auth=directory/'authorized_keys';expected='restrict,port-forwarding,permitlisten="127.0.0.1:19086" '+key.with_suffix('.pub').read_text()
    if auth.exists() and auth.read_text()!=expected:raise ValueError('Existing CRM tunnel identity differs')
    auth.write_text(expected);auth.chmod(0o600)
    for p in (directory,auth):os.chown(p,account.pw_uid,account.pw_gid)
    policy='''Match User siyue-ws-crm
    ClientAliveInterval 10
    ClientAliveCountMax 2
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding remote
    PermitOpen none
    PermitListen 127.0.0.1:19086
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    MaxSessions 0
Match all
'''
    config=pathlib.Path('/etc/ssh/sshd_config.d/92-siyue-workstation-crm.conf')
    if config.exists() and config.read_text()!=policy:raise ValueError('Existing CRM SSH policy differs')
    config.write_text(policy);run(['/usr/sbin/sshd','-t'])
    effective=run(['/usr/sbin/sshd','-T','-C','user='+user+',host=localhost,addr=127.0.0.1'],text=True).splitlines()
    for line in ('allowtcpforwarding remote','permitopen none','permitlisten 127.0.0.1:19086','maxsessions 0','gatewayports no'):
        if line not in effective:raise ValueError('CRM SSH restrictions are not effective')
    run(['systemctl','reload','ssh'])
    host=pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
    assets={'service.env':('\n'.join(k+'='+v for k,v in env.items())+'\n').encode(),
        'tunnel_key':key.read_bytes(),'known_hosts':('47.113.200.248 '+host[0]+' '+host[1]+'\n').encode(),
        'supervisor.py':(STATE/'crm_candidate_supervisor.py').read_bytes(),
        'runtime/siyue-core.jar':(RUNTIME/'siyue-core.jar').read_bytes(),'runtime/truststore':(RUNTIME/'truststore').read_bytes()}
    buf=io.BytesIO()
    with tarfile.open(fileobj=buf,mode='w') as tar:
        for name,body in assets.items():
            info=tarfile.TarInfo(name);info.size=len(body);info.mode=0o600;tar.addfile(info,io.BytesIO(body))
    subprocess.run(ssh()+['umask 077; tar -xf - -C '+NODE],input=buf.getvalue(),check=True,timeout=180)
    script='''import pathlib,subprocess,json,hashlib,os
r=pathlib.Path(NODE);r.chmod(0o700)
runtime=r/'runtime';os.chown(runtime,1000,0);runtime.chmod(0o500)
for p in runtime.iterdir():os.chown(p,1000,0);p.chmod(0o400)
assert hashlib.file_digest((runtime/'siyue-core.jar').open('rb'),'sha256').hexdigest()==JAR
assert hashlib.file_digest((runtime/'truststore').open('rb'),'sha256').hexdigest()==TRUST
env=dict(x.split('=',1) for x in (r/'service.env').read_text().splitlines())
assert hashlib.sha256(json.dumps(env,sort_keys=True).encode()).hexdigest()==ENVHASH
image=json.loads(subprocess.check_output(['podman','image','inspect',IMAGE]))[0]
assert image['Id'].removeprefix('sha256:')==IMAGE
name=CONTAINER
if subprocess.run(['podman','container','exists',name]).returncode==0:raise ValueError('CRM container already exists')
args=['podman','create','--name',name,'--network','host','--memory','1g','--memory-swap','2g','--cpus','1','--pids-limit','256',
 '--user','1000:0','--cap-drop','ALL','--security-opt','no-new-privileges','--read-only','--tmpfs','/tmp:rw,size=128m,mode=1777',
 '--env-file',str(r/'service.env'),'-v',str(r/'jre')+':/opt/java17:ro','-v',str(runtime)+':/opt/siyue-java-real-runtime/v101:ro',
 '--workdir','/opt/siyue-java-real-runtime/v101','--entrypoint','/opt/java17/bin/java',IMAGE,
 '-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=1','-Djavax.net.ssl.trustStore=/opt/siyue-java-real-runtime/v101/truststore',
 '-Djavax.net.ssl.trustStorePassword=changeit','-jar','/opt/siyue-java-real-runtime/v101/siyue-core.jar']
subprocess.run(args,check=True,stdout=subprocess.DEVNULL)
subprocess.run(['podman','run','--rm','--network','none','--read-only','--memory','64m','--cpus','0.25','--pids-limit','32',
 '--user','1000:0','--cap-drop','ALL','--security-opt','no-new-privileges','--volumes-from',name+':ro','--entrypoint','/bin/sh',IMAGE,
 '-c','test -r /opt/siyue-java-real-runtime/v101/truststore && test -r /opt/siyue-java-real-runtime/v101/siyue-core.jar'],check=True)
launcher=pathlib.Path('/opt/siyue-workstation/services-start.sh');old=launcher.read_text()
assert old.startswith('#!/bin/bash\\n') and '/services/idp-candidate/supervisor.py' in old
line='nohup python3 '+str(r/'supervisor.py')+' </dev/null 8>&- 9>&- >>'+str(r/'supervisor.log')+' 2>&1 &\\n'
if line not in old:
 launcher.with_suffix('.before-crm').write_text(old);launcher.write_text(old+line);launcher.chmod(0o700)
subprocess.run([str(launcher)],check=True)
v=json.loads(subprocess.check_output(['podman','inspect',name]))[0]
assert not v['State']['Running']
print(json.dumps(dict(containerId=v['Id'],image=v['Image'],enabled=False,jarSha256=JAR,environmentSha256=ENVHASH,truststoreSha256=TRUST)))
'''
    replacements={'NODE':NODE,'JAR':JAR,'TRUST':identity['truststoreSha256'],'ENVHASH':identity['environmentSha256'],'IMAGE':IMAGE,'CONTAINER':CONTAINER}
    for key,value in replacements.items():script=script.replace(key,repr(value))
    proof=node(script,120);save('prepared.json',dict(at=time.time(),source=identity,node=proof,databaseRetained=True))
    print(json.dumps(proof),flush=True)

if __name__=='__main__':main()
