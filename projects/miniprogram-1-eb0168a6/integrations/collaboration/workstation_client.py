"""Controller-to-workstation transport; identities and recipes remain administrator-owned."""
import hashlib,json,os,pathlib,stat,subprocess,tempfile,shutil,threading

class Unavailable(ValueError):pass
SEEN=set()
LOCK=threading.RLock()
CONTROL_ROOT=pathlib.Path('/run/siyue-workstation-ssh')

def connection_options(c):
 if c.get('reuseConnection') is not True:return []
 # Only the controller account can create or replace connection sockets.
 CONTROL_ROOT.mkdir(mode=0o700,exist_ok=True)
 s=CONTROL_ROOT.lstat()
 if not stat.S_ISDIR(s.st_mode) or s.st_uid!=os.geteuid() or s.st_mode&0o077:
  raise ValueError('Unsafe workstation SSH control directory')
 # Replacing credentials or known-host trust must establish a fresh connection.
 identity=[]
 for key in ('identity','knownHosts'):
  p=pathlib.Path(c[key]);s=p.stat()
  identity.append([str(p.resolve()),s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns])
 name=hashlib.sha256(json.dumps(identity).encode()).hexdigest()[:32]
 return ['-o','ControlMaster=auto','-o','ControlPersist=60','-o','ControlPath='+str(CONTROL_ROOT/name)]

def config(d,key):
 c=d.settings().get('workstation',{})
 return c if c.get('enabled') is True and key in c.get('projects',[]) else None

def command(c):
 # Only the already-provisioned local return tunnel is an allowed endpoint.
 if c.get('host')!='127.0.0.1' or c.get('port')!=5399:raise ValueError('Invalid workstation endpoint')
 return ['ssh','-T',*connection_options(c),'-o','BatchMode=yes','-o','ConnectTimeout=5','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+c['knownHosts'],'-i',c['identity'],'-p','5399','root@127.0.0.1','python3 /opt/siyue-workstation/runner.py']

def rpc(c,q,*,archive=None,body=None,timeout=60):
 with tempfile.TemporaryFile() as request,tempfile.TemporaryFile() as out,tempfile.TemporaryFile() as err:
  request.write(json.dumps(q,separators=(',',':')).encode()+b'\n')
  if archive:
   with pathlib.Path(archive).open('rb') as source:shutil.copyfileobj(source,request)
  if body:request.write(body)
  request.seek(0)
  try:p=subprocess.run(command(c),stdin=request,stdout=out,stderr=err,timeout=timeout)
  except (subprocess.TimeoutExpired,OSError) as e:raise Unavailable('工作区连接暂不可用，等待资源恢复；此测试版本保留，稍后重试') from e
  out.seek(0);data=out.read(12*1024**2+1);err.seek(0);error=err.read(4096)
  if p.returncode:
   if p.returncode==255:raise Unavailable('工作区连接暂不可用，等待资源恢复；此测试版本保留，稍后重试')
   try:failure=json.loads(error)
   except (ValueError,UnicodeError):failure={}
   if failure.get('error')=='ValueError' and any(v in failure.get('message','') for v in ('正在使用','等待资源')):raise Unavailable(failure['message'])
   raise RuntimeError('workstation operation failed: '+error.decode(errors='replace')[-1000:])
  if len(data)>12*1024**2:raise ValueError('Workstation response exceeds limit')
  return json.loads(data)

def ensure(d,t,key,bid,release,viewed=False):
 c=config(d,key)
 if not c:return None
 meta=json.loads((release/'__siyue_version.json').read_text());digest=meta['digest']
 artifact,build=d.completed_build(bid,key,digest)
 q=dict(key=key,buildId=bid,digest=digest);identity=(key,bid,digest)
 with LOCK:
  if identity not in SEEN:
   if not rpc(c,dict(q,action='has'))['stored']:
    rpc(c,dict(q,action='store',commit=build['sha'],size=artifact.stat().st_size),archive=artifact,timeout=600)
   SEEN.add(identity)
  try:return rpc(c,dict(q,action='ensure',viewed=viewed),timeout=90)
  except Exception:
   SEEN.discard(identity);raise

def request(d,row,q):
 c=config(d,row['key'])
 if not c:raise Unavailable('工作区路由已停用，等待管理员恢复此测试版本')
 raw=json.dumps(q,separators=(',',':')).encode()
 return rpc(c,dict(action='request',key=row['key'],buildId=row['buildId'],digest=row['digest'],requestBytes=len(raw)),body=raw,timeout=35)

def metadata(d,row):
 c=config(d,row['key'])
 if not c:return {}
 return rpc(c,dict(action='metadata',key=row['key'],buildId=row['buildId'],digest=row['digest']),timeout=15)

def touch(d,key,bid,digest):
 c=config(d,key)
 if c:return rpc(c,dict(action='touch',key=key,buildId=bid,digest=digest),timeout=15)
 return None
