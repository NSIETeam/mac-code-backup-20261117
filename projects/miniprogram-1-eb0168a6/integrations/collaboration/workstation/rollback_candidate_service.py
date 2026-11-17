#!/usr/bin/env python3
"""Explicit rollback of the verified candidate move, preserving the current DB."""
import fcntl,json,pathlib,shutil,subprocess,sys,time
from cutover_candidate_service import ROOT,STATE,SOURCE,UNIT,run,readiness,write_state

def main():
    lock=(STATE/'cutover.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    accepted=json.loads((STATE/'cutover.json').read_text());prepared=json.loads((STATE/'prepared.json').read_text())
    if accepted['state']!='succeeded':raise ValueError('Only the accepted active migration can be rolled back')
    old=json.loads(run(['docker','inspect',SOURCE]))[0]
    if old['Id']!=prepared['sourceContainerId'] or old['State']['Running']:raise ValueError('Source state changed')
    sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
    sys.path.insert(0,str(ROOT));from common import load,require_mysql
    from probe import verify
    from dependency_guard import dependent_snapshot
    root,state,private=load(ROOT);require_mysql(ROOT/'mysql-admin.cnf',state['schema'],prepared['databaseServerUuid'])
    # Fail closed if the node cannot confirm that the exact migrated container is stopped.
    script='''import pathlib,json,subprocess,fcntl
r=pathlib.Path('/opt/siyue-workstation/services/idp-candidate');lock=(r/'activation.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX)
v=json.loads(subprocess.check_output(['podman','inspect','siyue-service-idp-candidate']))[0]
assert v['Id']==EXPECTED
(r/'enabled').unlink(missing_ok=True)
subprocess.run(['podman','stop','--time','30','siyue-service-idp-candidate'],check=True,stdout=subprocess.DEVNULL)
v=json.loads(subprocess.check_output(['podman','inspect','siyue-service-idp-candidate']))[0]
assert not v['State']['Running'];print(json.dumps(dict(stopped=True,containerId=v['Id'])))
'''.replace('EXPECTED',repr(accepted['node']['containerId']))
    ssh=w.command(d.settings()['workstation'])[:-1]
    result=subprocess.run(ssh+['python3 -'],input=script,text=True,capture_output=True,timeout=90)
    if result.returncode:raise RuntimeError('Node stop not confirmed; source remains stopped')
    if not json.loads(result.stdout)['stopped']:raise ValueError('Node stop not confirmed')
    shutil.copy2(STATE/'cutover.json',STATE/'cutover-succeeded-before-rollback.json')
    write_state('rolling-back',nodeStopped=True)
    run(['systemctl','stop',UNIT+'.socket',UNIT+'.service']);run(['systemctl','disable',UNIT+'.socket'])
    run(['docker','start',SOURCE]);readiness(18449,120)
    proof=verify(root,state,private);dependencies=dependent_snapshot(root,state,private)
    if dependencies!=accepted['dependencies']:raise ValueError('Dependent identity changed')
    write_state('rolled-back',nodeStopped=True,databaseRetained=True,protocol=proof,dependencies=dependencies)
    print((STATE/'cutover.json').read_text(),flush=True)

if __name__=='__main__':main()
