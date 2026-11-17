"""Root-owned cgroup-v1 envelope for a rootless Podman build on dedicated WSL.

Podman cannot set rootless v1 limits itself. The privileged supervisor places the
launcher in bounded host groups before dropping uid; repository code is only
executed inside the networkless, read-only, rootless container.
"""
import os,pathlib,pwd,re,signal,subprocess,time,uuid
CG=pathlib.Path('/sys/fs/cgroup')

def builder(args):
 user=pwd.getpwnam('siyue-build')
 return ['runuser','-u','siyue-build','--','env','XDG_RUNTIME_DIR=/run/user/'+str(user.pw_uid),'podman',*args]

def memory_bytes(value):
 if not isinstance(value,str) or not re.fullmatch(r'[1-9][0-9]{1,3}m',value):raise ValueError('Invalid build memory')
 size=int(value[:-1])*1024**2
 if not 64*1024**2<=size<=2048*1024**2:raise ValueError('Build memory outside policy')
 return size

class Envelope:
 def __init__(self,job,memory='896m'):
  self.job=str(uuid.UUID(job));self.memory=memory_bytes(memory)
  self.groups={k:CG/k/('siyue-build-'+self.job) for k in ('memory','cpu','pids')}
 def create(self):
  if os.getuid()!=0:raise PermissionError('Supervisor must be root')
  for p in self.groups.values():
   p.mkdir(mode=0o755);p.chmod(0o755)
  settings={'memory':{'memory.limit_in_bytes':self.memory,'memory.memsw.limit_in_bytes':self.memory*2},'cpu':{'cpu.cfs_period_us':100000,'cpu.cfs_quota_us':100000},'pids':{'pids.max':256}}
  for controller,values in settings.items():
   for name,value in values.items():
    p=self.groups[controller]/name;p.write_text(str(value))
    if int(p.read_text())!=value:raise RuntimeError('Resource limit was not applied')
  return self
 def enter(self):
  for p in self.groups.values():(p/'cgroup.procs').write_text(str(os.getpid()))
 def members(self):
  return {int(pid) for p in self.groups.values() if p.exists() for pid in (p/'cgroup.procs').read_text().split()}
 def evidence(self):
  result={'memoryLimit':int((self.groups['memory']/'memory.limit_in_bytes').read_text()),'memorySwapLimit':int((self.groups['memory']/'memory.memsw.limit_in_bytes').read_text()),'memoryPeak':int((self.groups['memory']/'memory.max_usage_in_bytes').read_text()),'cpuQuota':int((self.groups['cpu']/'cpu.cfs_quota_us').read_text()),'cpuPeriod':int((self.groups['cpu']/'cpu.cfs_period_us').read_text()),'cpuStat':(self.groups['cpu']/'cpu.stat').read_text(),'pidsLimit':int((self.groups['pids']/'pids.max').read_text()),'members':sorted(self.members())}
  return result
 def close(self):
  for _ in range(50):
   members=self.members()
   if not members:break
   for pid in members:
    if pid==os.getpid():raise RuntimeError('Supervisor unexpectedly entered build cgroup')
    try:os.kill(pid,signal.SIGKILL)
    except ProcessLookupError:pass
   time.sleep(.1)
  if self.members():raise RuntimeError('Build descendants remain alive')
  for p in reversed(list(self.groups.values())):
   if p.exists():p.rmdir()

def launch(job,args):
 """Called only by the root-owned worker with its validated, stored recipe."""
 Envelope(job).enter()
 user=pwd.getpwnam('siyue-build');os.setgroups([]);os.setgid(user.pw_gid);os.setuid(user.pw_uid);os.chdir(user.pw_dir)
 env={'HOME':user.pw_dir,'USER':user.pw_name,'LOGNAME':user.pw_name,'PATH':'/usr/local/bin:/usr/bin:/bin','XDG_RUNTIME_DIR':'/run/user/'+str(user.pw_uid),'LANG':'C.UTF-8'}
 os.nice(10);os.execve('/usr/bin/podman',['podman',*args],env)
