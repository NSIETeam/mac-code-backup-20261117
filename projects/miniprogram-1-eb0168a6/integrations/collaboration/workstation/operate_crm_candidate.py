"""Verified single-writer cutover and return of the exact CRM candidate runtime."""
import fcntl,json,pathlib,socket,subprocess,sys,time
from crm_candidate_common import *
from crm_candidate_probe import verify

def record(state,**extra):
    value=dict(state=state,at=time.time(),**extra)
    save('operation.json',value);save('operation-'+str(time.time_ns())+'.json',value)
    return value

def source_running():return int(run(['systemctl','show',UNIT,'-p','MainPID','--value'],text=True).strip())>0

def control(action):
    assert action in ('status','start','stop')
    prepared=json.loads((STATE/'prepared.json').read_text())
    script='''import pathlib,subprocess,json,fcntl,hashlib,time
r=pathlib.Path(NODE);lock=(r/'activation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX)
v=json.loads(subprocess.check_output(['podman','inspect',CONTAINER]))[0]
assert v['Id']==EXPECTED
if ACTION=='start':
 assert not v['State']['Running'] and not (r/'enabled').exists()
 (r/'enabled').write_text('CRM candidate cutover authorized\\n')
 subprocess.run(['podman','start',CONTAINER],check=True,stdout=subprocess.DEVNULL)
elif ACTION=='stop':
 (r/'enabled').unlink(missing_ok=True)
 if v['State']['Running']:subprocess.run(['podman','stop','--time','30',CONTAINER],check=True,stdout=subprocess.DEVNULL)
v=json.loads(subprocess.check_output(['podman','inspect',CONTAINER]))[0];h=v['HostConfig']
assert h['Memory']==1073741824 and h['PidsLimit']==256 and h['ReadonlyRootfs']
assert h['NetworkMode']=='host' and v['Config']['User']=='1000:0'
env={}
for value in v['Config']['Env']:
 k,x=value.split('=',1)
 if k.startswith(('SIYUE_','SPRING_')):env[k]=x
memory=int([x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')][0])
print(json.dumps(dict(containerId=v['Id'],running=v['State']['Running'],pid=v['State']['Pid'],image=v['Image'],enabled=(r/'enabled').exists(),
 memoryLimit=h['Memory'],cpuQuota=h['CpuQuota'],cpuPeriod=h['CpuPeriod'],readonly=h['ReadonlyRootfs'],pidsLimit=h['PidsLimit'],
 availableMemoryKiB=memory,environmentSha256=hashlib.sha256(json.dumps(env,sort_keys=True).encode()).hexdigest(),
 jarSha256=hashlib.file_digest((r/'runtime/siyue-core.jar').open('rb'),'sha256').hexdigest(),
 truststoreSha256=hashlib.file_digest((r/'runtime/truststore').open('rb'),'sha256').hexdigest(),
 supervisor=json.loads((r/'supervisor-state.json').read_text()))))
'''
    for key,value in {'NODE':NODE,'CONTAINER':CONTAINER,'EXPECTED':prepared['node']['containerId'],'ACTION':action}.items():script=script.replace(key,repr(value))
    result=node(script,100)
    if result['image'].removeprefix('sha256:')!=IMAGE:raise ValueError('Node image changed')
    for key in ('environmentSha256','jarSha256','truststoreSha256'):
        if result[key]!=prepared['source'][key]:raise ValueError('Node runtime changed: '+key)
    return result

def wait_protocol(base=PUBLIC,timeout=150):
    deadline=time.monotonic()+timeout
    while True:
        try:
            result=verify(base)
            baseline=json.loads((STATE/'baseline-protocol.json').read_text())
            if result['publicConfigSha256']!=baseline['publicConfigSha256']:raise ValueError('OIDC public contract changed')
            return result
        except (OSError,ValueError,RuntimeError,AssertionError):
            if time.monotonic()>deadline:raise
            time.sleep(3)

def proxy_units():
    socket='''[Unit]
Description=Workstation CRM candidate endpoint
[Socket]
ListenStream=127.0.0.1:18086
NoDelay=true
[Install]
WantedBy=sockets.target
'''
    service='''[Unit]
Description=Loopback proxy to workstation CRM candidate
Requires=siyue-workstation-crm-candidate.socket
After=siyue-workstation-crm-candidate.socket
[Service]
ExecStart=/usr/lib/systemd/systemd-socket-proxyd 127.0.0.1:19086
DynamicUser=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
NoNewPrivileges=yes
MemoryMax=64M
TasksMax=32
'''
    for suffix,content in (('socket',socket),('service',service)):
        p=pathlib.Path('/etc/systemd/system')/(PROXY+'.'+suffix)
        if p.exists() and p.read_text()!=content:raise ValueError('Unexpected CRM proxy unit')
        p.write_text(content)
    run(['systemctl','daemon-reload'])

def rollback(reason='explicit rehearsal'):
    prepared=json.loads((STATE/'prepared.json').read_text())
    stopped=control('stop')
    if stopped['running'] or stopped['enabled']:raise ValueError('Node stop unconfirmed; source remains stopped')
    record('returning-to-source',nodeStopped=True,reason=reason)
    run(['systemctl','stop',PROXY+'.socket',PROXY+'.service']);run(['systemctl','disable',PROXY+'.socket'])
    run(['systemctl','start',UNIT],timeout=120)
    if prepared['source']['unitFileState']=='enabled':run(['systemctl','enable',UNIT])
    current,_=source_identity()
    for key in ('jarSha256','environmentSha256','truststoreSha256','databaseServerUuid','unitSha256'):
        if current[key]!=prepared['source'][key]:raise ValueError('Source identity changed: '+key)
    proof=wait_protocol();database_identity()
    return record('rolled-back',nodeStopped=True,databaseRetained=True,protocol=proof,sourcePid=current['pid'],reason=reason)

def cutover():
    previous=json.loads((STATE/'operation.json').read_text()) if (STATE/'operation.json').exists() else None
    if previous and previous['state']!='rolled-back':raise ValueError('Existing operation must be inspected')
    before=json.loads((STATE/'prepared.json').read_text());current,_=source_identity()
    for key in ('jarSha256','environmentSha256','truststoreSha256','databaseServerUuid','unitSha256'):
        if current[key]!=before['source'][key]:raise ValueError('Source changed: '+key)
    status=control('status');supervisor=status['supervisor']
    if status['running'] or status['enabled']:raise ValueError('Destination already active')
    if status['availableMemoryKiB']<8*1024*1024:raise ValueError('Insufficient workstation memory')
    if time.time()-supervisor['at']>20 or not supervisor['dependenciesReachable'] or not supervisor['tunnelProcessAlive']:
        raise ValueError('CRM supervisor or dependencies not ready')
    with socket.create_connection(('127.0.0.1',19086),5):pass
    wait_protocol(timeout=30);proxy_units();record('prepared',sourcePid=current['pid'])
    try:
        run(['systemctl','stop',UNIT],timeout=90)
        if source_running():raise ValueError('Source stop not confirmed')
        run(['systemctl','disable',UNIT]);record('starting-workstation',sourceStopped=True)
        control('start');direct=wait_protocol('http://127.0.0.1:19086/api/v2/')
        run(['systemctl','enable','--now',PROXY+'.socket'])
        public=wait_protocol();status=control('status')
        if not status['running'] or source_running():raise ValueError('Single-writer check failed')
        database_identity()
        return record('succeeded',sourceStopped=True,databaseRetained=True,directProtocol=direct,publicProtocol=public,node=status)
    except Exception as error:
        record('cutover-failed',errorType=type(error).__name__)
        try:rollback('automatic after '+type(error).__name__)
        except Exception as rollback_error:
            record('rollback-needs-inspection',errorType=type(rollback_error).__name__)
            raise RuntimeError('CRM rollback incomplete; inspect source and node before any restart') from rollback_error
        raise

def main():
    lock=(STATE/'operation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    probe=json.loads((STATE/'probe-private.json').read_text())
    if probe['phase']!='ready':raise ValueError('Create a fresh unprivileged probe before operating')
    if sys.argv[1]=='cutover':result=cutover()
    elif sys.argv[1]=='rollback':
        if json.loads((STATE/'operation.json').read_text())['state']!='succeeded':raise ValueError('No active accepted migration')
        result=rollback()
    else:raise ValueError('Unknown operation')
    print(json.dumps(result),flush=True)

if __name__=='__main__':main()
