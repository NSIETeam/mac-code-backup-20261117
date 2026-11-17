"""Single-writer SQLite handoff, including return of current data during rollback."""
import fcntl,json,os,pathlib,socket,subprocess,sys,time
from homework_lab_common import *
from homework_lab_probe import verify

def record(state,**fields):
    value=json.loads((STATE/'operation.json').read_text()) if (STATE/'operation.json').exists() else {}
    value.update(state=state,at=time.time(),**fields)
    save('operation.json',value);save('operation-'+str(time.time_ns())+'.json',value);return value

def source_running():return json.loads(run(['docker','inspect',SOURCE_NAME],text=True))[0]['State']['Running']

def control(action):
    if action not in ('status','start','stop'):raise ValueError('Unknown node action')
    expected=json.loads((STATE/'prepared.json').read_text())['node']['containerId']
    script='''import pathlib,subprocess,json,fcntl,time
r=pathlib.Path(ROOT)
with (r/'activation.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 v=json.loads(subprocess.check_output(['podman','inspect',NAME]))[0]
 assert v['Id']==EXPECTED and v['Image'].removeprefix('sha256:')==IMAGE
 h=v['HostConfig'];assert h['Memory']==805306368 and h['PidsLimit']==256 and h['ReadonlyRootfs']
 assert v['Config']['User']=='app' and h['NetworkMode']=='host'
 if ACTION=='start':
  assert not v['State']['Running'] and not (r/'enabled').exists()
  assert (r/'data/isolated.db').exists()
  (r/'enabled').write_text('Isolated homework laboratory handoff authorized\\n')
  subprocess.run(['podman','start',NAME],check=True,stdout=subprocess.DEVNULL)
 elif ACTION=='stop':
  (r/'enabled').unlink(missing_ok=True)
  if v['State']['Running']:subprocess.run(['podman','stop','--time','30',NAME],check=True,stdout=subprocess.DEVNULL)
 v=json.loads(subprocess.check_output(['podman','inspect',NAME]))[0]
 print(json.dumps({'containerId':v['Id'],'running':v['State']['Running'],'enabled':(r/'enabled').exists(),
 'pid':v['State']['Pid'],'supervisor':json.loads((r/'supervisor-state.json').read_text()),
 'availableKiB':int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))}))
'''
    for k,x in {'ROOT':NODE,'NAME':NAME,'EXPECTED':expected,'IMAGE':IMAGE,'ACTION':action}.items():script=script.replace(k,repr(x))
    return node(script)

def require_stopped(status):
    if status['running'] or status['enabled'] or status['pid']!=0:raise ValueError('Destination stop not confirmed')

def wait_protocol(base,timeout=100):
    deadline=time.monotonic()+timeout
    while True:
        try:return verify(base)
        except (OSError,ValueError,RuntimeError,AssertionError):
            if time.monotonic()>deadline:raise
            time.sleep(3)

def proxy_units():
    socket='''[Unit]
Description=Workstation isolated homework laboratory endpoint
[Socket]
ListenStream=127.0.0.1:5092
NoDelay=true
[Install]
WantedBy=sockets.target
'''
    service='''[Unit]
Description=Loopback proxy to workstation isolated homework laboratory
Requires=siyue-workstation-homework-lab.socket
After=siyue-workstation-homework-lab.socket
[Service]
ExecStart=/usr/lib/systemd/systemd-socket-proxyd 127.0.0.1:19592
DynamicUser=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
NoNewPrivileges=yes
MemoryMax=64M
TasksMax=32
'''
    for ext,value in [('socket',socket),('service',service)]:
        p=pathlib.Path('/etc/systemd/system')/(PROXY+'.'+ext)
        if p.exists() and p.read_text()!=value:raise ValueError('Unexpected proxy configuration')
        p.write_text(value)
    run(['systemctl','daemon-reload'])

def rollback(reason='explicit rehearsal'):
    stopped=control('stop');require_stopped(stopped)
    if source_running():raise ValueError('Source unexpectedly active; inspect before returning data')
    prior=json.loads((STATE/'operation.json').read_text())
    ensure_identity();record('returning-to-source',reason=reason)
    # Disable the proxy only after the destination is positively stopped.
    run(['systemctl','stop',PROXY+'.socket',PROXY+'.service']);run(['systemctl','disable',PROXY+'.socket'])
    if prior['activeData']=='node':
        before=remote_integrity();archive=STATE/('data-return-'+str(time.time_ns())+'.tar.gz');receive_data(archive)
        stage=SOURCE/('data.return-'+str(time.time_ns()));extract_data(archive,stage)
        if database(stage/'isolated.db')!=before['database'] or manifest(stage)!=before['files']:
            raise ValueError('Returned data integrity mismatch; both copies remain stopped')
        if remote_integrity()!=before:raise ValueError('Destination data changed while stopped')
        # Retain the previous source snapshot; never restore stale pre-cutover data.
        old=SOURCE/('data.before-return-'+str(time.time_ns()));(SOURCE/'data').rename(old);stage.rename(SOURCE/'data')
    elif prior['activeData']=='source':
        before={'database':database(SOURCE/'data/isolated.db')}
    else:raise ValueError('Data ownership unknown')
    baseline=json.loads((STATE/'baseline.json').read_text())
    policy=baseline['restartPolicy']['Name'];assert policy=='unless-stopped'
    run(['docker','update','--restart',policy,SOURCE_NAME]);run(['docker','start',SOURCE_NAME])
    if not source_running():raise ValueError('Source failed to start')
    proof=wait_protocol(PUBLIC)
    if database(SOURCE/'data/isolated.db')!=before['database']:raise ValueError('Rows changed after return')
    return record('rolled-back',activeData='source',protocol=proof,nodeStopped=True)

def cutover():
    previous=json.loads((STATE/'operation.json').read_text()) if (STATE/'operation.json').exists() else None
    if previous and previous['state']!='rolled-back':raise ValueError('Prior operation must be inspected, not repeated')
    ensure_identity()
    if not source_running():raise ValueError('Source is not active')
    status=control('status');require_stopped(status)
    if status['availableKiB']<8*1024*1024:raise ValueError('Insufficient workstation memory')
    s=status['supervisor']
    if time.time()-s['at']>20 or not s['tunnelProcessAlive']:raise ValueError('Destination tunnel not ready')
    database(SOURCE/'data/isolated.db');wait_protocol(PUBLIC,30)
    with socket.create_connection(('127.0.0.1',19592),5):pass
    proxy_units();record('stopping-source',activeData='source')
    try:
        run(['docker','update','--restart','no',SOURCE_NAME]);run(['docker','stop','--time','30',SOURCE_NAME])
        v,_=source()
        if v['State']['Running'] or v['State']['Pid']!=0:raise ValueError('Source stop unconfirmed')
        rows=database(SOURCE/'data/isolated.db');files=manifest(SOURCE/'data')
        record('transferring-data',activeData='source',sourceFiles=files,database=rows)
        archive=STATE/('data-cutover-'+str(time.time_ns())+'.tar.gz');pack(SOURCE/'data',archive)
        if manifest(SOURCE/'data')!=files:raise ValueError('Source data changed after stop')
        node('import pathlib,json,os,time\nr=pathlib.Path('+repr(NODE)+');assert not (r/"enabled").exists();(r/"data").rename(r/("data.before-"+str(time.time_ns())));(r/"data").mkdir(mode=0o755);os.chown(r/"data",1654,1654);print(json.dumps({"ready":True}))')
        send_archive(archive,NODE+'/data')
        node('import pathlib,os,json\nr=pathlib.Path('+repr(NODE+'/data')+');\nfor p in [r,*r.rglob("*")]:os.chown(p,1654,1654)\nprint(json.dumps({"ownershipVerified":True}))')
        actual=remote_integrity()
        if actual['files']!=files or actual['database']!=rows:raise ValueError('Copied data differs')
        record('starting-workstation',activeData='node',copiedDataVerified=True)
        control('start');direct=wait_protocol('http://127.0.0.1:19592/')
        run(['systemctl','enable','--now',PROXY+'.socket']);public=wait_protocol(PUBLIC)
        status=control('status')
        if not status['running'] or source_running():raise ValueError('Single-writer verification failed')
        if remote_integrity()['database']!=rows:raise ValueError('Database rows changed during handoff')
        return record('succeeded',activeData='node',directProtocol=direct,publicProtocol=public,node=status,sourceStopped=True)
    except Exception as error:
        record('cutover-failed',errorType=type(error).__name__)
        try:
            if source_running():
                if json.loads((STATE/'operation.json').read_text())['activeData']!='source':
                    raise ValueError('Source restarted while destination owned data; manual reconciliation required')
                # Stop failed before data moved; keep the existing writer and restore only its restart policy.
                require_stopped(control('stop'))
                run(['docker','update','--restart','unless-stopped',SOURCE_NAME])
                record('rolled-back',activeData='source',reason='source-stop-failed')
            else:rollback('automatic after '+type(error).__name__)
        except Exception as recovery:
            record('rollback-needs-inspection',errorType=type(recovery).__name__)
            raise RuntimeError('Handoff uncertain; inspect both writers before retry') from recovery
        raise

def main():
    lock=(STATE/'operation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    if sys.argv[1]=='cutover':result=cutover()
    elif sys.argv[1]=='rollback':
        if json.loads((STATE/'operation.json').read_text())['state']!='succeeded':raise ValueError('No accepted migration to roll back')
        result=rollback()
    else:raise ValueError('Unknown action')
    print(json.dumps({'state':result['state'],'activeData':result['activeData']}),flush=True)

if __name__=='__main__':main()
