"""Keep the fixed isolated homework laboratory endpoint alive, with an activation interlock."""
import fcntl,json,os,pathlib,signal,socket,subprocess,time
ROOT=pathlib.Path('/opt/siyue-workstation/services/homework-lab')
NAME='siyue-service-homework-lab'
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
    command=['ssh','-N','-T','-o','BatchMode=yes','-o','ConnectTimeout=10',
        '-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','ExitOnForwardFailure=yes',
        '-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(ROOT/'known_hosts'),
        '-i',str(ROOT/'tunnel_key'),'-R','127.0.0.1:19592:127.0.0.1:5092','siyue-ws-homework@47.113.200.248']
    ssh=None
    try:
        while not STOP:
            if ssh is None or ssh.poll() is not None:
                ssh=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True)
            dependencies=True
            for port in ():
                try:
                    with socket.create_connection(('127.0.0.1',port),2):pass
                except OSError:dependencies=False
            running=False;error=None
            try:
                with (ROOT/'activation.lock').open('a') as activation:
                    fcntl.flock(activation,fcntl.LOCK_EX)
                    p=subprocess.run(['podman','inspect','--format','{{.State.Running}}',NAME],text=True,capture_output=True,timeout=15)
                    running=p.returncode==0 and p.stdout.strip()=='true'
                    if (ROOT/'enabled').exists() and dependencies and not running:
                        subprocess.run(['podman','start',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
            except (OSError,subprocess.TimeoutExpired) as exc:error=type(exc).__name__
            value=dict(at=time.time(),pid=os.getpid(),sshPid=ssh.pid,tunnelProcessAlive=ssh.poll() is None,
                dependenciesReachable=dependencies,containerRunning=running,enabled=(ROOT/'enabled').exists(),error=error)
            p=ROOT/'supervisor-state.pending';p.write_text(json.dumps(value));p.chmod(0o600);p.replace(ROOT/'supervisor-state.json')
            time.sleep(5)
    finally:
        if ssh is not None and ssh.poll() is None:
            ssh.terminate()
            try:ssh.wait(timeout=8)
            except subprocess.TimeoutExpired:ssh.kill();ssh.wait()

if __name__=='__main__':main()
