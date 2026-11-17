#!/usr/bin/env python3
"""Fixed workplace PostgreSQL service and private tunnel; no repository commands."""
import fcntl,json,os,pathlib,pwd,signal,subprocess,time

ROOT=pathlib.Path('/opt/siyue-workstation/databases')
PG=ROOT/'postgres';DATA=PG/'data';STOP=False
def stop(*_):
 global STOP
 STOP=True
def save(value):
 p=ROOT/'supervisor-state.pending';p.write_text(json.dumps(value));p.chmod(0o600);p.replace(ROOT/'supervisor-state.json')
def envelope():
 values={'memory':{'memory.limit_in_bytes':2*1024**3,'memory.memsw.limit_in_bytes':4*1024**3},'cpu':{'cpu.cfs_period_us':100000,'cpu.cfs_quota_us':150000},'pids':{'pids.max':512}}
 for controller,settings in values.items():
  folder=pathlib.Path('/sys/fs/cgroup')/controller/'siyue-postgres';folder.mkdir(exist_ok=True)
  for key,value in settings.items():
   p=folder/key;p.write_text(str(value))
   if int(p.read_text())!=value:raise RuntimeError('PostgreSQL resource limit not applied')
def child():
 for controller in ('memory','cpu','pids'):
  (pathlib.Path('/sys/fs/cgroup')/controller/'siyue-postgres/cgroup.procs').write_text(str(os.getpid()))
 user=pwd.getpwnam('postgres');os.setgroups([]);os.setgid(user.pw_gid);os.setuid(user.pw_uid);os.chdir(PG)
def running():
 p=DATA/'postmaster.pid'
 if not p.exists():return False
 try:
  pid=int(p.read_text().splitlines()[0]);proc=pathlib.Path('/proc')/str(pid)
  command=(proc/'cmdline').read_bytes().split(b'\0')
  return proc.stat().st_uid==pwd.getpwnam('postgres').pw_uid and command[:3]==[b'/usr/lib/postgresql/16/bin/postgres',b'-D',str(DATA).encode()]
 except (OSError,ValueError,IndexError):return False
def main():
 if os.geteuid()!=0:raise PermissionError('Root supervisor required')
 lock=(ROOT/'supervisor.lock').open('a')
 try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 except BlockingIOError:return
 signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
 command=['ssh','-N','-T','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','ExitOnForwardFailure=yes','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(ROOT/'known_hosts'),'-i',str(ROOT/'tunnel_key'),'-L','127.0.0.1:15433:127.0.0.1:5432','-R','127.0.0.1:15432:127.0.0.1:25432','siyue-ws-database@47.113.200.248']
 ssh=None;database=None
 try:
  while not STOP:
   if ssh is None or ssh.poll() is not None:ssh=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True)
   error=None
   try:
    with (ROOT/'activation.lock').open('a') as activation:
     fcntl.flock(activation,fcntl.LOCK_EX)
     if database is not None and database.poll() is not None:
      error='PostgreSQL exited with status '+str(database.returncode)+'; inspect postgres.log'
     if (ROOT/'enabled').exists() and not running():
      mode=json.loads((ROOT/'mode.json').read_text())['mode']
      if mode not in ('standby','primary'):raise ValueError('Unknown database mode')
      if (DATA/'PG_VERSION').read_text().strip()!='16':raise ValueError('Unexpected database version')
      if mode=='standby' and not (DATA/'standby.signal').exists():raise ValueError('Standby promotion was not authorized')
      envelope()
      user=pwd.getpwnam('postgres')
      environment={k:v for k,v in os.environ.items() if not k.startswith('PG')}
      environment.update(HOME=user.pw_dir,USER=user.pw_name,LOGNAME=user.pw_name)
      with (ROOT/'postgres.log').open('ab') as log:
       database=subprocess.Popen(['/usr/lib/postgresql/16/bin/postgres','-D',str(DATA),'-c','config_file='+str(PG/'postgresql.conf')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,preexec_fn=child,start_new_session=True,env=environment)
   except Exception as exc:error=type(exc).__name__+': '+str(exc)[:300]
   save(dict(at=time.time(),pid=os.getpid(),sshPid=ssh.pid,tunnelAlive=ssh.poll() is None,databaseRunning=running(),enabled=(ROOT/'enabled').exists(),error=error))
   time.sleep(2)
 finally:
  # Database lifetime is independent of a transport/supervisor restart.
  if ssh is not None and ssh.poll() is None:
   ssh.terminate()
   try:ssh.wait(timeout=8)
   except subprocess.TimeoutExpired:ssh.kill();ssh.wait()
if __name__=='__main__':main()
