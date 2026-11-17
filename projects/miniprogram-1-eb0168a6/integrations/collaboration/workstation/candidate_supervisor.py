#!/usr/bin/env python3
"""Supervise the fixed candidate DB/HTTPS tunnel, never repository commands."""
import fcntl,json,os,pathlib,signal,socket,subprocess,time

ROOT=pathlib.Path('/opt/siyue-workstation/services/idp-candidate')
NAME='siyue-service-idp-candidate'
STOP=False
def stop(*_):
    global STOP
    STOP=True

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    lock=(ROOT/'supervisor.lock').open('a')
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:return
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    ssh=None
    command=['ssh','-N','-T','-o','BatchMode=yes','-o','ConnectTimeout=10',
             '-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','ExitOnForwardFailure=yes',
             '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(ROOT/'known_hosts'),
             '-i',str(ROOT/'tunnel_key'),'-L','127.0.0.1:50571:127.0.0.1:50571',
             '-R','127.0.0.1:19449:127.0.0.1:18449','siyue-ws-idp@47.113.200.248']
    try:
        while not STOP:
            if ssh is None or ssh.poll() is not None:
                ssh=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True)
            db=False
            try:
                with socket.create_connection(('127.0.0.1',50571),2) as stream:
                    stream.settimeout(2);db=bool(stream.recv(128))
            except OSError:pass
            running=False
            error=None
            try:
                with (ROOT/'activation.lock').open('a') as activation:
                    fcntl.flock(activation,fcntl.LOCK_EX)
                    result=subprocess.run(['podman','inspect','--format','{{.State.Running}}',NAME],capture_output=True,text=True,timeout=15)
                    running=result.returncode==0 and result.stdout.strip()=='true'
                    if (ROOT/'enabled').exists() and db and not running:
                        subprocess.run(['podman','start',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
            except (OSError,subprocess.TimeoutExpired) as exc:error=type(exc).__name__
            record=dict(at=time.time(),pid=os.getpid(),sshPid=ssh.pid,tunnelProcessAlive=ssh.poll() is None,databaseReachable=db,containerRunning=running,enabled=(ROOT/'enabled').exists(),error=error)
            pending=ROOT/'supervisor-state.pending';pending.write_text(json.dumps(record));pending.chmod(0o600);pending.replace(ROOT/'supervisor-state.json')
            time.sleep(5)
    finally:
        if ssh is not None and ssh.poll() is None:
            ssh.terminate()
            try:ssh.wait(timeout=8)
            except subprocess.TimeoutExpired:ssh.kill();ssh.wait()

if __name__=='__main__':main()
