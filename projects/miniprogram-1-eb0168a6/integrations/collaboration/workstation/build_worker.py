#!/usr/bin/env python3
"""Durable build jobs; source archives never supply host commands or credentials."""
import base64,fcntl,hashlib,json,os,pathlib,pwd,re,shutil,signal,stat,select,subprocess,sys,tarfile,time,uuid
from build_sandbox import Envelope,builder,memory_bytes,launch
from receive_build import unpack
BASE=pathlib.Path('/var/lib/siyue-workstation-build')
CONFIG=pathlib.Path('/opt/siyue-workstation/build-config.json')
MAX=160*1024**2
# The pinned pilot image contains Node and a shell but omits coreutils sleep.
# Retain the observation window so short builds cannot finish before cgroup checks.
ENTRY_SCRIPT='set -eu; mkdir -p -m 700 "$XDG_RUNTIME_DIR"; if command -v sleep >/dev/null 2>&1; then sleep 2; else node -e \'setTimeout(()=>{},2000)\'; fi; exec "$@"'

def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def atomic(path,value):
 tmp=path.with_name(path.name+'.new');tmp.write_text(json.dumps(value,ensure_ascii=False));tmp.chmod(0o600);tmp.replace(path)
def config():
 s=CONFIG.stat()
 if s.st_uid!=0 or s.st_mode&0o022:raise ValueError('Unsafe build configuration')
 return json.loads(CONFIG.read_text())
def identity(q):
 job=str(uuid.UUID(q['jobId']));key=q['key'];commit=q['commit'];digest=q['sourceDigest'];recipe=q['recipeVersion']
 if not re.fullmatch('[a-z][a-z0-9-]{2,47}',key) or not re.fullmatch('[a-f0-9]{40,64}',commit) or not all(re.fullmatch('[a-f0-9]{64}',x) for x in (digest,recipe)):raise ValueError('Invalid build identity')
 return dict(jobId=job,key=key,commit=commit,sourceDigest=digest,recipeVersion=recipe)
def jobpath(job):return BASE/'jobs'/str(uuid.UUID(job))
def state(folder):return json.loads((folder/'state.json').read_text())
def stored(q):
 ident=identity(q);folder=jobpath(ident['jobId']);m=json.loads((folder/'manifest.json').read_text())
 if any(m[k]!=v for k,v in ident.items()):raise ValueError('Immutable build identity mismatch')
 return folder,m

def receive(q,stream):
 ident=identity(q);cfg=config();t=cfg['projects'].get(ident['key'])
 if not t or ident['recipeVersion']!=cfg['recipeVersion']:raise ValueError('Unprovisioned build recipe')
 size=q['size']
 if type(size)!=int or not 0<size<=MAX:raise ValueError('Invalid source size')
 jobs=BASE/'jobs';jobs.mkdir(parents=True,exist_ok=True);jobs.chmod(0o711);folder=jobpath(ident['jobId'])
 if folder.exists():
  stored(q);return state(folder)
 if shutil.disk_usage(BASE).free<8*1024**3 or sum(p.stat().st_size for p in jobs.glob('*/source.tar.gz'))>5*1024**3:raise ValueError('等待资源：工作区构建存储不足')
 temp=jobs/('.incoming-'+ident['jobId']);temp.mkdir(mode=0o700)
 try:
  archive=temp/'source.tar.gz';total=0
  with archive.open('wb') as out:
   while total<size:
    b=stream.read(min(size-total,256*1024))
    if not b:raise ValueError('Truncated source archive')
    out.write(b);total+=len(b)
  if stream.read(1) or sha(archive)!=ident['sourceDigest']:raise ValueError('Source checksum mismatch')
  atomic(temp/'manifest.json',dict(ident,size=size));atomic(temp/'recipe.json',dict(t,recipeVersion=cfg['recipeVersion']))
  atomic(temp/'state.json',dict(ident,state='stored',at=time.time()));temp.chmod(0o755);temp.rename(folder)
 except Exception:shutil.rmtree(temp,ignore_errors=True);raise
 return state(folder)

def acquire(path,blocking=False):
 f=path.open('a');os.fchmod(f.fileno(),0o600)
 try:fcntl.flock(f,fcntl.LOCK_EX|(0 if blocking else fcntl.LOCK_NB))
 except BlockingIOError:f.close();return None
 return f

def dispatch(q,stream):
 action=q['action'];ident=identity(q)
 BASE.mkdir(mode=0o711,parents=True,exist_ok=True);BASE.chmod(0o711)
 if action=='build-store':
  with acquire(BASE/'receive.lock',True):return receive(q,stream)
 if action=='build-has':
  if not jobpath(ident['jobId']).exists():return dict(stored=False)
  folder,_=stored(q);return dict(stored=True,**state(folder))
 folder,m=stored(q)
 if action=='build-retry':
  with acquire(folder/'start.lock',True):
   row=state(folder)
   if row['state']=='retry-requested':return row
   lock=acquire(folder/'worker.lock')
   if lock is None:raise ValueError('等待资源：原构建仍在处理')
   try:
    if row['state']!='failed':raise ValueError('Only failed builds can be retried')
    atomic(folder/'state.json',dict(row,state='retry-requested',at=time.time()))
   finally:lock.close()
   return state(folder)
 if action=='build-start':
  with acquire(folder/'start.lock',True):
   row=state(folder)
   if row['state'] in ('succeeded','failed'):return row
   lock=acquire(folder/'worker.lock')
   if lock is None:return row
   if row['state']=='running':
    # Do not rerun an interrupted job against potentially partial state.
    lock.close();atomic(folder/'state.json',dict(row,state='failed',error='Build worker interrupted; explicit new build required'));return state(folder)
   try:
    with (folder/'supervisor.log').open('ab') as log:
     subprocess.Popen([sys.executable,str(pathlib.Path(__file__).resolve()),'work',ident['jobId'],str(lock.fileno())],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True,pass_fds=(lock.fileno(),))
   finally:lock.close()
   return row
 if action=='build-status':
  row=state(folder)
  if row['state']=='running':
   lock=acquire(folder/'worker.lock')
   if lock is not None:
    lock.close();atomic(folder/'state.json',dict(row,state='failed',error='Build worker interrupted'));row=state(folder)
  return row
 if action=='build-read':
  row=state(folder)
  if row['state'] not in ('succeeded','failed'):raise ValueError('Build is not terminal')
  kind=q.get('file');name='artifact.tar' if kind=='artifact' and row['state']=='succeeded' else 'build.log' if kind=='log' else None
  if name is None:raise ValueError('Unsupported build output')
  p=folder/name;offset=q.get('offset',0)
  if type(offset)!=int or not 0<=offset<=p.stat().st_size:raise ValueError('Invalid output offset')
  with p.open('rb') as f:f.seek(offset);body=f.read(4*1024**2)
  return dict(offset=offset,size=p.stat().st_size,data=base64.b64encode(body).decode())
 raise ValueError('Unsupported build action')

def arguments(folder,t):
 image=t['check_image'];command=t['check_command']
 if not re.fullmatch('[a-f0-9]{64}',image) or not isinstance(command,list) or not command or not all(isinstance(x,str) for x in command):raise ValueError('Invalid trusted build command')
 if t.get('mode') not in ('static','package'):raise ValueError('Image-building recipes are not enabled on this node')
 recipe=BASE/'recipes'/t['recipeVersion']
 if recipe.stat().st_uid!=0 or recipe.stat().st_mode&0o022:raise ValueError('Unsafe trusted recipe snapshot')
 return ['run','--name','siyue-check-'+folder.name,'--pull=never','--cgroups=disabled','--network=none','--env','SIYUE_BUILD_SANDBOX=1','--env','NODE_PATH=/siyue-ci/node_modules','--env','XDG_RUNTIME_DIR=/tmp/siyue-runtime','--read-only','--userns=keep-id','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:rw,size=128m,mode=1777','-v',str(folder/'work/source')+':/workspace:rw','-v',str(recipe)+':/siyue-ci:ro','-w','/workspace',image,'/bin/sh','-c',ENTRY_SCRIPT,'siyue-build-entry',*command]

def package(folder,t):
 src=folder/'work/source';out=src/t.get('output','dist')
 if out.is_symlink() or src.resolve() not in out.resolve().parents or not out.is_dir():raise ValueError('Build output escaped workspace or is missing')
 if t['mode']=='static' and not (out/'index.html').is_file():raise ValueError('Static index missing')
 if (folder/'sources.json').is_file():shutil.copyfile(folder/'sources.json',out/'__siyue_sources.json')
 total=0;files=list(out.rglob('*'))
 if len(files)>15000:raise ValueError('Too many artifact files')
 target=folder/'artifact.tar.part'
 try:
  with tarfile.open(target,'w') as tar:
   for p in sorted(files):
    mode=p.lstat().st_mode
    if not(stat.S_ISREG(mode) or stat.S_ISDIR(mode)):raise ValueError('Unsafe artifact file')
    if stat.S_ISREG(mode):
     total+=p.stat().st_size
     if total>150*1024**2:raise ValueError('Artifact exceeds quota')
     tar.add(p,arcname=str(p.relative_to(out)),recursive=False)
  if target.stat().st_size>MAX:raise ValueError('Artifact archive exceeds quota')
  target.chmod(0o440);target.replace(folder/'artifact.tar')
 except Exception:target.unlink(missing_ok=True);raise
 return dict(digest=sha(folder/'artifact.tar'),size=(folder/'artifact.tar').stat().st_size)

def work(job,lockfd):
 folder=jobpath(job);m=json.loads((folder/'manifest.json').read_text());t=json.loads((folder/'recipe.json').read_text());p=None;e=None;mounted=False;diskCreated=False;namespaceOwned=False;proof={};result={}
 # The inherited worker lock prevents duplicate launches across SSH retries.
 with os.fdopen(lockfd,'a'),acquire(BASE/'build.lock',True):
  try:
   if state(folder)['state']=='retry-requested':
    # An explicit controller retry preserves the original source and recipe.
    subprocess.run(builder(['rm','-f','siyue-check-'+job]),cwd='/home/siyue-build',capture_output=True,check=True,timeout=20)
    Envelope(job).close()
    active=subprocess.check_output(builder(['ps','-q']),cwd='/home/siyue-build',text=True).strip()
    if active:raise RuntimeError('Unexpected active build during retry cleanup')
    subprocess.run(builder(['system','migrate']),cwd='/home/siyue-build',capture_output=True,check=True,timeout=20)
    if os.path.ismount(folder/'work'):subprocess.run(['umount',str(folder/'work')],check=True,timeout=20)
    (folder/'work.img').unlink(missing_ok=True)
    stamp=str(time.time_ns())
    for name in ('build.log','evidence.json','artifact.tar','artifact.tar.part'):
     file=folder/name
     if file.exists():file.rename(folder/(name+'.attempt-'+stamp))
   memory=memory_bytes(t.get('build_memory','896m'))
   available=next(int(x.split()[1])*1024 for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:'))
   if available<memory+2*1024**3:atomic(folder/'state.json',dict(m,state='queued',error='等待资源：工作区可用内存不足'));return
   atomic(folder/'state.json',dict(m,state='running',pid=os.getpid(),at=time.time()))
   if sha(folder/'source.tar.gz')!=m['sourceDigest']:raise ValueError('Stored source changed')
   disk=folder/'work.img';workdir=folder/'work';workdir.mkdir(exist_ok=True);workdir.chmod(0o755)
   if disk.exists():raise ValueError('Partial build disk exists; do not overwrite')
   subprocess.run(['truncate','-s','4G',str(disk)],check=True)
   diskCreated=True
   subprocess.run(['mkfs.ext4','-q','-F',str(disk)],check=True)
   subprocess.run(['mount','-o','loop,nosuid,nodev',str(disk),str(workdir)],check=True);mounted=True
   unpack(folder/'source.tar.gz',workdir/'source');user=pwd.getpwnam('siyue-build')
   if (workdir/'source/__siyue_sources.json').is_file():shutil.copyfile(workdir/'source/__siyue_sources.json',folder/'sources.json')
   for path in [workdir/'source',*(workdir/'source').rglob('*')]:
    os.chown(path,user.pw_uid,user.pw_gid);path.chmod(0o755 if path.is_dir() or path.stat().st_mode&0o111 else 0o644)
   # A rootless pause namespace predating the bounded disk cannot see its mount.
   # This identity runs only serialized builds; refresh it after mounting the disk.
   active=subprocess.check_output(builder(['ps','-q']),cwd=user.pw_dir,text=True).strip()
   if active:raise RuntimeError('Unexpected active container in dedicated build account')
   namespaceOwned=True
   subprocess.run(builder(['system','migrate']),cwd=user.pw_dir,check=True,capture_output=True,timeout=20)
   e=Envelope(job,t.get('build_memory','896m')).create()
   with (folder/'build.log').open('wb') as log:
    p=subprocess.Popen([sys.executable,str(pathlib.Path(__file__).resolve()),'launch',job],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
    os.set_blocking(p.stdout.fileno(),False)
    start=time.monotonic();observed=False
    while True:
     ready,_,_=select.select([p.stdout],[],[],.2)
     if ready:
      block=os.read(p.stdout.fileno(),65536)
      if block:
       if log.tell()+len(block)>4*1024**2:raise RuntimeError('Build exceeded log quota')
       log.write(block);log.flush()
      elif p.poll() is not None:break
     if p.poll() is None and not observed and time.monotonic()-start>1:
      probe=subprocess.run(builder(['inspect','--format','{{.State.Pid}}','siyue-check-'+job]),cwd=user.pw_dir,capture_output=True,text=True,timeout=10)
      if probe.returncode==0 and probe.stdout.strip().isdigit() and int(probe.stdout)>0:
       pid=int(probe.stdout);membership=pathlib.Path('/proc')/str(pid)/'cgroup';proof['containerCgroups']=membership.read_text()
       if not all(k+':/siyue-build-'+job in proof['containerCgroups'] for k in ('memory','cpu','pids')):raise RuntimeError('Container escaped resource envelope')
       observed=True
     if time.monotonic()-start>min(int(t.get('timeout',900)),1800) or log.tell()>4*1024**2:raise RuntimeError('Build exceeded time or log quota')
     time.sleep(.2)
    code=p.returncode;p.stdout.close()
   proof.update(e.evidence());proof['exitCode']=code
   if code:raise RuntimeError('Isolated build exited '+str(code))
   if not observed:raise RuntimeError('Build finished before its resource envelope was verified')
   subprocess.run(builder(['rm','-f','siyue-check-'+job]),cwd=user.pw_dir,capture_output=True,check=True,timeout=20)
   e.close();e=None
   result=package(folder,t)
  except Exception as error:result={'error':str(error)[:1800]}
  finally:
   if p is not None and p.poll() is None:p.kill();p.wait()
   try:
    subprocess.run(builder(['rm','-f','siyue-check-'+job]),cwd='/home/siyue-build',capture_output=True,timeout=20)
    if e:e.close()
    if namespaceOwned:subprocess.run(builder(['system','migrate']),cwd='/home/siyue-build',check=True,capture_output=True,timeout=20)
    if mounted:subprocess.run(['umount',str(folder/'work')],check=True,timeout=20);mounted=False
    if diskCreated and not mounted:(folder/'work.img').unlink(missing_ok=True)
   except Exception as error:result={'error':'Build cleanup failed: '+str(error)[:1000]}
  if not (folder/'build.log').exists():(folder/'build.log').write_text(result.get('error',''))
  atomic(folder/'evidence.json',proof)
  atomic(folder/'state.json',dict(m,**result,state='failed' if 'error' in result else 'succeeded',backend='workstation',at=time.time(),limits=proof))

if __name__=='__main__':
 os.umask(0o077)
 if sys.argv[1]=='work':work(str(uuid.UUID(sys.argv[2])),int(sys.argv[3]))
 elif sys.argv[1]=='launch':
  job=str(uuid.UUID(sys.argv[2]));folder=jobpath(job);t=json.loads((folder/'recipe.json').read_text());launch(job,arguments(folder,t))
 else:raise SystemExit('Unsupported entry point')
