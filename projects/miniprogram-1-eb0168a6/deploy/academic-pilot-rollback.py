#!/usr/bin/env python3
"""Remove only the academic pilot proxy/container; retain additive tables and history.

Default is read-only preflight. Add --apply to execute rollback.
The sibling academic-pilot.py must be present. No legacy service is restarted.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('academic_pilot_deploy', Path(__file__).with_name('academic-pilot.py'))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--apply',action='store_true');args=p.parse_args()
    pilot.require(os.geteuid()==0,'Run on deployment host as root')
    pilot.require(pilot.STATE.exists(),'No completed pilot deployment state found')
    state=json.loads(pilot.STATE.read_text())
    pilot.require(state.get('ok') and state.get('container')==pilot.CONTAINER,'Unexpected deployment state')
    current=pilot.SNIPPET.read_bytes();block=pilot.ROUTES.encode()
    pilot.require(current.count(block)==1,'Pilot route block changed; inspect before rollback')
    pilot.require(pilot.digest(block)==state.get('routesHash'),'Route block differs from deployed version')
    cfg=pilot.inspect(pilot.CONTAINER)
    pilot.require(cfg['Config'].get('Labels',{}).get('com.siyue.pilot')=='academic-feedback','Unexpected container identity')
    old_ids=[pilot.inspect(name)['Id'] for name in ['siyue-miniapp-api-1','siyue-miniapp-worker-1']]
    if not args.apply:
        print(json.dumps({'preflight':True,'action':'Remove pilot routes and stop only pilot container; preserve database tables.'}));return
    with (pilot.STATE.parent/'deploy.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        pilot.require(pilot.SNIPPET.read_bytes()==current,'Nginx changed concurrently')
        replacement=current.replace(block,b'',1)
        mode=pilot.SNIPPET.stat().st_mode&0o777
        pilot.atomic(pilot.SNIPPET,replacement,mode)
        try:
            pilot.run(['nginx','-t']);pilot.run(['nginx','-s','reload'])
        except Exception:
            pilot.atomic(pilot.SNIPPET,current,mode)
            pilot.run(['nginx','-t']);pilot.run(['nginx','-s','reload']);raise
        pilot.run(['docker','stop','--time','20',pilot.CONTAINER]);pilot.run(['docker','rm',pilot.CONTAINER])
        pilot.require([pilot.inspect(name)['Id'] for name in ['siyue-miniapp-api-1','siyue-miniapp-worker-1']]==old_ids,'Legacy container identity changed')
        pilot.require(pilot.http('https://www.siyuejn.com/miniapp/health')[0]==200,'Legacy health failed')
        state['rolledBack']=True;state['ok']=False
        pilot.atomic(pilot.STATE,json.dumps(state,ensure_ascii=False,indent=2).encode())
        print(json.dumps({'rolledBack':True,'pilotTablesRetained':True,'legacyServicesUnchanged':True}))


if __name__=='__main__':
    try:
        main()
    except Exception as e:
        print('ERROR: '+(str(e) if isinstance(e,RuntimeError) else type(e).__name__),file=sys.stderr);sys.exit(1)
