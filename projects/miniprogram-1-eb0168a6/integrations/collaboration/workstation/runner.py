#!/usr/bin/env python3
"""Private SSH RPC for the dedicated workstation; project code only runs in containers.

The controller installs root-owned recipes, authenticated artifact digests and image IDs.
No repository credentials, production sessions or production databases are configured here.
"""
import base64,fcntl,hashlib,json,os,pathlib,shutil,sys,tempfile,time,uuid
from types import SimpleNamespace

ROOT=pathlib.Path(os.environ.get('SIYUE_WORKSTATION_ROOT','/opt/siyue-workstation/runtime'))
MAX=150*1024**2
MAX_PREVIEWS=4

def compatible_run(engine,args,**kwargs):
 # Podman's documented tmpfs form uses mode; retain the same 64 MiB bound.
 # https://docs.podman.io/en/v4.6.1/markdown/podman-run.1.html
 args=list(args)
 if args[:2]==['docker','run']:
  args=[a.replace('/tmp:rw,size=64m,uid=1000,gid=1000','/tmp:rw,size=64m,mode=1777') for a in args]
 return engine.run(args,**kwargs)

def preview_slot(key,engine):
 """Bound active runtimes to four projects; preserve recently viewed versions."""
 slots=ROOT/'slots';slots.mkdir(exist_ok=True)
 destination=slots/key;destination.mkdir(exist_ok=True)
 active=[]
 for state in slots.glob('*/preview-runtime.json'):
  row=json.loads(state.read_text())
  if state.parent!=destination:active.append((row.get('usedAt',0),row,state))
 if not (destination/'preview-runtime.json').exists() and len(active)>=MAX_PREVIEWS:
  candidates=[entry for entry in active if time.time()-max(entry[0],entry[1].get('viewerAt',0))>90]
  if not candidates:raise ValueError('测试环境正在使用，等待资源空闲后重试')
  _,row,state=min(candidates,key=lambda entry:entry[0])
  engine.run(['docker','rm','-f',row['container']],timeout=30)
  state.unlink()
 (destination/'logs').mkdir(exist_ok=True)
 return SimpleNamespace(ROOT=destination,settings=lambda:dict(workstation=dict(enabled=False)),run=lambda args,**kw:compatible_run(engine,args,**kw),memory_available=engine.memory_available)

def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def touch(key,bid):
 state=ROOT/'slots'/key/'preview-runtime.json'
 if not state.exists():return dict(touched=False)
 row=json.loads(state.read_text())
 if row['buildId']!=bid or row['key']!=key:return dict(touched=False)
 row.update(usedAt=time.time(),viewerAt=time.time())
 state.write_text(json.dumps(row));return dict(touched=True)

def identity(q):
 import re
 key=q['key'];bid=str(uuid.UUID(q['buildId']));sha=q['digest']
 if not re.fullmatch('[a-z][a-z0-9-]{2,47}',key) or not re.fullmatch('[a-f0-9]{64}',sha):raise ValueError('Invalid artifact identity')
 return key,bid,sha

def receive(q,stream):
 from receive_build import unpack
 key,bid,sha=identity(q);size=q['size']
 import re
 if not re.fullmatch('[a-f0-9]{40,64}',q['commit']):raise ValueError('Invalid source commit')
 if type(size)!=int or not 0<size<=MAX:raise ValueError('Invalid artifact size')
 target=ROOT/'sites'/key/'versions'/bid
 if target.exists():
  existing=json.loads((target/'__siyue_version.json').read_text())
  if existing['digest']!=sha or existing['sha']!=q['commit']:raise ValueError('Immutable artifact collision')
  return dict(stored=True,reused=True)
 target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='.incoming-',dir=target.parent) as td:
  folder=pathlib.Path(td);archive=folder/'artifact.tar';total=0
  with archive.open('wb') as out:
   while total<size:
    block=stream.read(min(256*1024,size-total))
    if not block:raise ValueError('Truncated artifact')
    total+=len(block);out.write(block)
  if stream.read(1):raise ValueError('Artifact has extra bytes')
  if digest(archive)!=sha:raise ValueError('Artifact digest mismatch')
  content=folder/'files';unpack(archive,content)
  (content/'__siyue_version.json').write_text(json.dumps(dict(buildId=bid,sha=q['commit'],digest=sha)))
  for file in [content,*content.rglob('*')]:file.chmod(0o755 if file.is_dir() else 0o644)
  content.rename(target)
 return dict(stored=True,reused=False)

def main():
 os.umask(0o077);ROOT.mkdir(parents=True,exist_ok=True)
 header=sys.stdin.buffer.readline(65537)
 if len(header)>65536 or not header.endswith(b'\n'):raise ValueError('Invalid request header')
 q=json.loads(header);action=q['action']
 if action.startswith('build-'):
  import build_worker
  print(json.dumps(build_worker.dispatch(q,sys.stdin.buffer),ensure_ascii=False));return
 cfgfile=ROOT/'projects.json'
 if cfgfile.stat().st_mode&0o022:raise ValueError('Unsafe recipe permissions')
 cfg=json.loads(cfgfile.read_text())
 if action=='status':
  import subprocess
  result=dict(host='SIYUE12B001',kernel=os.uname().release,freeBytes=shutil.disk_usage(ROOT).free,projects=list(cfg),containers=json.loads(subprocess.check_output(['docker','ps','--format','json'],text=True)))
 else:
  key,bid,sha=identity(q)
  if key not in cfg:raise ValueError('Unconfigured project')
  lock=ROOT/'runtime.lock'
  with lock.open('a') as file:
   fcntl.flock(file,fcntl.LOCK_EX)
   if action=='has':
    p=ROOT/'sites'/key/'versions'/bid/'__siyue_version.json'
    result=dict(stored=p.exists() and json.loads(p.read_text()).get('digest')==sha)
   elif action=='store':result=receive(q,sys.stdin.buffer)
   else:
    sys.path.insert(0,str(pathlib.Path(__file__).parent/'controller'))
    os.environ['SIYUE_DELIVERY_ROOT']=str(ROOT)
    import delivery_service as d
    t=cfg[key];release=ROOT/'sites'/key/'versions'/bid
    observed=json.loads((release/'__siyue_version.json').read_text())
    if observed['digest']!=sha:raise ValueError('Artifact digest differs from request')
    slot=ROOT/'slots'/key
    if action=='touch':result=touch(key,bid)
    elif action=='ensure':
     node=preview_slot(key,d)
     row=d.recipes.ensure_runtime(node,t,key,bid,release,viewed=q.get('viewed',False))
     result=dict(**row,backend='workstation',node='main',digest=sha)
    elif action in ('request','metadata'):
     row=json.loads((slot/'preview-runtime.json').read_text())
     if row['buildId']!=bid or row['key']!=key:raise ValueError('Runtime identity changed')
     if action=='request':
      length=q.get('requestBytes',0)
      if type(length)!=int or not 0<length<=12*1024**2:raise ValueError('Invalid HTTP request size')
      raw=sys.stdin.buffer.read(length+1)
      if len(raw)!=length:raise ValueError('Invalid HTTP request body')
      result=d.recipes.runtime_request(d,row,json.loads(raw),t)
     else:
      import subprocess
      raw=subprocess.check_output(['docker','exec',row['container'],'python3','-c',"import pathlib; print(pathlib.Path('/data/preview-meta.json').read_text())"],timeout=5)
      meta=json.loads(raw);result={k:meta[k] for k in ('storage','hint') if k in meta}
    else:raise ValueError('Unsupported action')
 print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':
 try:main()
 except Exception as error:
  print(json.dumps(dict(error=type(error).__name__,message=str(error)[:1000])),file=sys.stderr);sys.exit(1)
