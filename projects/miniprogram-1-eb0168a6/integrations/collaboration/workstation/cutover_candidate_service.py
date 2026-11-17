#!/usr/bin/env python3
"""Single-writer candidate cutover; rollback never restores or copies its DB."""
import fcntl,json,os,pathlib,ssl,subprocess,sys,time,urllib.request

ROOT=pathlib.Path('/opt/siyue-idp-candidate/v100')
STATE=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/idp-candidate-migration')
SOURCE='siyue-idp-candidate-v100'
DEST='siyue-service-idp-candidate'
UNIT='siyue-workstation-idp-candidate'

def run(args,timeout=90):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=timeout).stdout

def write_state(state,**extra):
    pending=STATE/'cutover.pending';pending.write_text(json.dumps(dict(state=state,at=time.time(),**extra),indent=2));pending.chmod(0o600);pending.replace(STATE/'cutover.json')

def readiness(port,timeout=120):
    context=ssl.create_default_context(cafile=str(ROOT/'ca.crt'));deadline=time.monotonic()+timeout
    while True:
        try:
            with urllib.request.urlopen('https://localhost:'+str(port)+'/realms/master/.well-known/openid-configuration',context=context,timeout=5) as r:v=json.load(r)
            if v['issuer']!='https://localhost:18449/realms/master':raise ValueError('Candidate issuer changed')
            return
        except OSError:
            if time.monotonic()>deadline:raise
            time.sleep(3)

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    lock=(STATE/'cutover.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    if (STATE/'cutover.json').exists():raise ValueError('Existing cutover must be inspected, not repeated')
    prepared=json.loads((STATE/'prepared.json').read_text());before=json.loads((STATE/'baseline.json').read_text())
    sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
    ssh=w.command(d.settings()['workstation'])[:-1]
    def node(script,timeout=60):
        p=subprocess.run(ssh+['python3 -'],input=script,text=True,capture_output=True,timeout=timeout)
        if p.returncode:raise RuntimeError('Candidate node command failed: '+p.stderr[-500:])
        return json.loads(p.stdout)
    sys.path.insert(0,str(ROOT));from common import load,require_mysql
    from probe import verify
    from dependency_guard import dependent_snapshot
    root,state,private=load(ROOT)
    require_mysql(ROOT/'mysql-admin.cnf',state['schema'],prepared['databaseServerUuid'])
    old=json.loads(run(['docker','inspect',SOURCE]))[0]
    if old['Id']!=prepared['sourceContainerId'] or old['Image']!=prepared['image'] or not old['State']['Running']:
        raise ValueError('Source runtime changed')
    current=dependent_snapshot(root,state,private)
    if current!=before['dependencies']:raise ValueError('Dependent identity boundaries changed')
    check=node('''import pathlib,json,time,subprocess
r=pathlib.Path('/opt/siyue-workstation/services/idp-candidate');v=json.loads((r/'supervisor-state.json').read_text())
assert time.time()-v['at']<20 and v['databaseReachable'] and not v['enabled'] and not v['containerRunning']
memory=int([l.split()[1] for l in pathlib.Path('/proc/meminfo').read_text().splitlines() if l.startswith('MemAvailable:')][0]);assert memory>8*1024*1024
print(json.dumps(v))
''')
    binary=pathlib.Path('/usr/lib/systemd/systemd-socket-proxyd')
    if not binary.is_file():raise ValueError('Socket proxy unavailable')
    socket='''[Unit]
Description=Workstation candidate identity endpoint
[Socket]
ListenStream=127.0.0.1:18449
NoDelay=true
[Install]
WantedBy=sockets.target
'''
    service='''[Unit]
Description=Loopback proxy to the workstation candidate identity service
Requires=siyue-workstation-idp-candidate.socket
After=siyue-workstation-idp-candidate.socket
[Service]
ExecStart=/usr/lib/systemd/systemd-socket-proxyd 127.0.0.1:19449
DynamicUser=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
NoNewPrivileges=yes
MemoryMax=64M
TasksMax=32
'''
    for suffix,content in [('socket',socket),('service',service)]:
        p=pathlib.Path('/etc/systemd/system')/(UNIT+'.'+suffix)
        if p.exists():raise ValueError('Unexpected existing service proxy')
        p.write_text(content)
    run(['systemctl','daemon-reload'])
    write_state('prepared',sourceContainerId=old['Id'])
    stopped=False
    try:
        stopped=True
        run(['docker','stop','--time','30',SOURCE])
        if json.loads(run(['docker','inspect',SOURCE]))[0]['State']['Running']:raise ValueError('Source still running')
        write_state('starting-workstation')
        node('''import pathlib,json,subprocess,fcntl
r=pathlib.Path('/opt/siyue-workstation/services/idp-candidate');lock=(r/'activation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX)
(r/'enabled').write_text('candidate cutover authorized\\n')
subprocess.run(['podman','start','siyue-service-idp-candidate'],check=True,stdout=subprocess.DEVNULL)
print(json.dumps(dict(started=True)))
''')
        readiness(19449,180)
        run(['systemctl','enable','--now',UNIT+'.socket'])
        readiness(18449,30)
        after=dict(protocol=verify(root,state,private),dependencies=dependent_snapshot(root,state,private))
        if after['dependencies']!=before['dependencies']:raise ValueError('Candidate realm or client changed during migration')
        with urllib.request.urlopen('https://www.siyuejn.com/java-candidate-v101/auth/realms/siyue-migration-v101/.well-known/openid-configuration',timeout=20) as r:info=json.load(r)
        if info['issuer']!=state['dependentRealms'][0]['issuer']:raise ValueError('Public issuer changed')
        evidence=node('''import json,subprocess,pathlib
v=json.loads(subprocess.check_output(['podman','inspect','siyue-service-idp-candidate']))[0];h=v['HostConfig']
assert v['State']['Running'] and h['Memory']==1073741824 and h['PidsLimit']==256 and h['ReadonlyRootfs']
assert h['NetworkMode']=='host' and v['Config']['User']=='1000:0'
print(json.dumps(dict(containerId=v['Id'],image=v['Image'],running=True,memoryLimit=h['Memory'],pidsLimit=h['PidsLimit'],cpuQuota=h['CpuQuota'],cpuPeriod=h['CpuPeriod'],readonly=h['ReadonlyRootfs'],supervisor=json.loads(pathlib.Path('/opt/siyue-workstation/services/idp-candidate/supervisor-state.json').read_text()))))
''')
        if evidence['image'].removeprefix('sha256:')!=prepared['nodeImage'].removeprefix('sha256:'):raise ValueError('Node image identity changed')
        if json.loads(run(['docker','inspect',SOURCE]))[0]['State']['Running']:raise ValueError('Source restarted unexpectedly')
        require_mysql(ROOT/'mysql-admin.cnf',state['schema'],prepared['databaseServerUuid'])
        write_state('succeeded',sourceStopped=True,databaseRetained=True,protocol=after['protocol'],dependencies=after['dependencies'],publicDiscovery=200,node=evidence)
        print((STATE/'cutover.json').read_text(),flush=True)
    except Exception as error:
        run(['systemctl','stop',UNIT+'.socket',UNIT+'.service'])
        run(['systemctl','disable',UNIT+'.socket'])
        try:
            node('''import pathlib,subprocess,json,fcntl
r=pathlib.Path('/opt/siyue-workstation/services/idp-candidate');lock=(r/'activation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX)
(r/'enabled').unlink(missing_ok=True)
subprocess.run(['podman','stop','--time','30','siyue-service-idp-candidate'],check=True,stdout=subprocess.DEVNULL)
v=json.loads(subprocess.check_output(['podman','inspect','siyue-service-idp-candidate']))[0]
assert not v['State']['Running'];print(json.dumps(dict(stopped=True)))
''',90)
        except Exception:
            write_state('rollback-needs-node-confirmation',errorType=type(error).__name__)
            raise RuntimeError('Node stop could not be confirmed; source not restarted to prevent dual writers') from error
        if stopped:
            run(['docker','start',SOURCE]);readiness(18449,120)
        write_state('rolled-back',errorType=type(error).__name__,detail=str(error)[:500])
        raise

if __name__=='__main__':main()
