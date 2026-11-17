"""Opt-in offload of reviewed builds; Gitea credentials stay on the controller."""
import base64,hashlib,json,pathlib,shutil,tarfile,time
import workstation_client as w
MAX=160*1024**2

def config(d,key):
 c=d.settings().get('workstation',{})
 return c if c.get('enabled') is True and key in c.get('buildProjects',[]) else None

def prepare(d,cfg,t,key,job,commit):
 folder=d.ROOT/'workstation-builds'/job;folder.mkdir(parents=True,exist_ok=True)
 manifest=folder/'manifest.json';archive=folder/'source.tar.gz'
 if manifest.exists():
  q=json.loads(manifest.read_text())
  if q['key']!=key or q['commit']!=commit or d.file_digest(archive)!=q['sourceDigest']:raise ValueError('Prepared build identity changed')
  return folder,q
 workspace=folder/'assembly'
 if workspace.exists():shutil.rmtree(workspace)
 workspace.mkdir()
 src=d.recipes.assemble(d,cfg,t,commit,workspace);_,version=d.recipes.snapshot(cfg)
 temp=folder/'source.tar.gz.part'
 with tarfile.open(temp,'w:gz',compresslevel=1) as tar:
  for p in sorted(src.rglob('*')):
   if p.is_symlink() or not(p.is_file() or p.is_dir()):raise ValueError('Unsafe source file')
   tar.add(p,arcname=str(p.relative_to(src)),recursive=False)
 if not 0<temp.stat().st_size<=MAX:raise ValueError('Source archive exceeds quota')
 temp.replace(archive)
 q=dict(jobId=job,key=key,commit=commit,sourceDigest=d.file_digest(archive),recipeVersion=version,size=archive.stat().st_size)
 pending=manifest.with_suffix('.new');pending.write_text(json.dumps(q));pending.chmod(0o600);pending.replace(manifest)
 shutil.rmtree(workspace);return folder,q

def download(c,q,name,target,expected=None):
 temp=target.with_suffix(target.suffix+'.part');offset=0
 try:
  with temp.open('wb') as out:
   while True:
    reply=w.rpc(c,dict(q,action='build-read',file=name,offset=offset),timeout=30)
    bound=MAX if name=='artifact' else 4*1024**2
    if type(reply['size'])!=int or not 0<=reply['size']<=bound or reply['offset']!=offset:raise ValueError('Invalid build output bounds')
    data=base64.b64decode(reply['data'],validate=True)
    if offset+len(data)>reply['size'] or (not data and offset<reply['size']):raise ValueError('Truncated build output')
    out.write(data);offset+=len(data)
    if offset==reply['size']:break
  if expected:
   with temp.open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
   if actual!=expected:raise ValueError('Returned artifact checksum mismatch')
   with tarfile.open(temp) as tar:
    entries=tar.getmembers()
    if len(entries)>15000 or sum(e.size for e in entries)>150*1024**2:raise ValueError('Artifact expansion exceeds quota')
    for e in entries:
     p=pathlib.PurePosixPath(e.name)
     if p.is_absolute() or '..' in p.parts or '\\' in e.name or ':' in e.name or not(e.isfile() or e.isdir()):raise ValueError('Unsafe returned artifact')
  temp.chmod(0o440 if name=='artifact' else 0o600);temp.replace(target)
 except Exception:temp.unlink(missing_ok=True);raise

def build(d,cfg,t,key,job,request):
 c=config(d,key)
 if c is None:return False
 commit=d.sha(request['sha'])
 # The controller only assembles bounded sources; compilation consumes node RAM.
 if d.memory_available()<512*1024**2 or shutil.disk_usage(d.ROOT).free<5*1024**3:
  d.update(job,'queued',summary='源码准备等待服务器资源');return True
 if sum(p.stat().st_size for p in (d.ROOT/'artifacts').glob('*.tar'))>10*1024**3:
  d.update(job,'queued',summary='构建产物达到保留限额，等待资源');return True
 try:
  d.update(job,'running',summary='正在准备固定提交的工作区构建',backend='workstation')
  folder,q=prepare(d,cfg,t,key,job,commit)
  if not w.rpc(c,dict(q,action='build-has'))['stored']:
   w.rpc(c,dict(q,action='build-store'),archive=folder/'source.tar.gz',timeout=600)
  row=w.rpc(c,dict(q,action='build-start'))
  deadline=time.monotonic()+int(t.get('timeout',900))+120
  while row['state'] in ('stored','queued','running','retry-requested'):
   d.update(job,'running',summary='工作区正在隔离环境中检查代码',backend='workstation')
   if time.monotonic()>deadline:
    d.update(job,'queued',summary='工作区任务仍在处理，等待资源并继续查询',backend='workstation');return True
   # Fast completion pickup for short checks; back off while waiting for resources.
   time.sleep(1 if row['state']=='running' else 3)
   row=w.rpc(c,dict(q,action='build-status'))
   if row['state'] in ('stored','queued','retry-requested'):row=w.rpc(c,dict(q,action='build-start'))
  (d.ROOT/'logs').mkdir(exist_ok=True);download(c,q,'log',d.ROOT/'logs'/(job+'.log'))
  if row['state']!='succeeded':raise RuntimeError(row.get('error','Workstation build failed'))
  digest=d.sha(row['digest']);artifacts=d.ROOT/'artifacts';artifacts.mkdir(exist_ok=True)
  download(c,q,'artifact',artifacts/(job+'.tar'),digest)
  (folder/'receipt.json').write_text(json.dumps(row,indent=2))
  d.update(job,'succeeded',digest=digest,sha=commit,summary='工作区隔离检查通过，构建产物已校验并固定',backend='workstation',node='main',sourceDigest=q['sourceDigest'],recipeVersion=q['recipeVersion'])
 except w.Unavailable:
  d.update(job,'queued',summary='工作区连接暂不可用，等待资源恢复后续查原构建',backend='workstation')
 return True

def retry(d,job,key):
 manifest=d.ROOT/'workstation-builds'/job/'manifest.json'
 if not manifest.exists():return
 q=json.loads(manifest.read_text())
 if q['key']!=key:raise ValueError('Build does not belong to project')
 c=config(d,key)
 if not c:raise ValueError('原工作区构建路由已停用，请先恢复再重试')
 w.rpc(c,dict(q,action='build-retry'),timeout=15)
