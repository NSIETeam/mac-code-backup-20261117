#!/usr/bin/env python3
"""Fixed service return tunnels; starts staged services only after explicit PG promotion."""
import fcntl
import json
import os
import pathlib
import signal
import subprocess
import time

ROOT = pathlib.Path('/opt/siyue-workstation/services/native-postgres')
PG = pathlib.Path('/opt/siyue-workstation/databases')
RETURNS = {15082:5082, 15083:5083, 25095:15095, 15126:5126, 15088:5088,
           15103:5103, 15117:5117, 15106:5106, 15301:5301, 15300:5300, 15096:5096}
CONTAINERS = ('siyue-pg-gitea', 'siyue-pg-auxiliary', 'siyue-pg-api', 'siyue-pg-worker',
              'siyue-pg-teaching', 'siyue-pg-feedback', 'siyue-pg-academic',
              'siyue-pg-papers', 'siyue-pg-features', 'siyue-pg-archive', 'siyue-pg-collaboration')
STOP = False

def stop(*_):
    global STOP
    STOP = True

def can_start(enabled, mode, expected_id, identity, recovery):
    return enabled and mode == 'primary' and bool(expected_id) and identity == expected_id and recovery == 'f'

def database_ready():
    manifest = json.loads((ROOT/'prepared.json').read_text())
    mode = json.loads((PG/'mode.json').read_text())['mode']
    p = subprocess.run(['runuser','-u','postgres','--','psql','-h',str(PG/'postgres/run'),
                        '-p','25432','-XqAt','-v','ON_ERROR_STOP=1','-c',
                        'SELECT system_identifier,pg_is_in_recovery() FROM pg_control_system();'],
                       text=True,capture_output=True,timeout=8)
    if p.returncode: return False
    identity, recovery = p.stdout.strip().split('|')
    return can_start((ROOT/'enabled').exists(), mode, manifest['systemIdentifier'], identity, recovery)

def main():
    if os.geteuid() != 0: raise PermissionError('Root supervisor required')
    os.umask(0o077)
    lock=(ROOT/'supervisor.lock').open('a')
    try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError: return
    signal.signal(signal.SIGTERM,stop); signal.signal(signal.SIGINT,stop)
    command=['ssh','-N','-T','-o','BatchMode=yes','-o','ConnectTimeout=10',
             '-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','ExitOnForwardFailure=yes',
             '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(ROOT/'known_hosts'),
             '-i',str(ROOT/'tunnel_key'),'-L','127.0.0.1:5302:127.0.0.1:5302']
    for remote,local in RETURNS.items(): command += ['-R',f'127.0.0.1:{remote}:127.0.0.1:{local}']
    command += ['siyue-ws-miniapp@47.113.200.248']
    ssh=None
    try:
        while not STOP:
            if ssh is None or ssh.poll() is not None:
                ssh=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True)
            ready=False; running=[]; errors=[]
            try:
                with (ROOT/'activation.lock').open('a') as activation:
                    fcntl.flock(activation,fcntl.LOCK_EX)
                    # A missing preparation receipt or standby never starts a writer.
                    if (ROOT/'enabled').exists(): ready=database_ready()
                    if ready:
                        for name in CONTAINERS:
                            p=subprocess.run(['podman','inspect','--format','{{.State.Running}}',name],text=True,capture_output=True,timeout=10)
                            if p.returncode:
                                errors.append(name+':not-prepared');continue
                            if p.stdout.strip()!='true':
                                p=subprocess.run(['podman','start',name],capture_output=True,timeout=30)
                                if p.returncode:errors.append(name+':start-failed');continue
                            running.append(name)
            except (OSError,ValueError,KeyError,subprocess.TimeoutExpired) as exc:errors.append(type(exc).__name__)
            state=dict(at=time.time(),pid=os.getpid(),sshPid=ssh.pid,tunnelProcessAlive=ssh.poll() is None,
                       enabled=(ROOT/'enabled').exists(),primaryVerified=ready,running=running,errors=errors)
            p=ROOT/'supervisor-state.pending';p.write_text(json.dumps(state));p.replace(ROOT/'supervisor-state.json')
            time.sleep(3)
    finally:
        # Service lifetime is independent of a tunnel restart. No automatic promotion/failback.
        if ssh is not None and ssh.poll() is None:
            ssh.terminate()
            try: ssh.wait(timeout=8)
            except subprocess.TimeoutExpired: ssh.kill();ssh.wait()

if __name__=='__main__': main()
