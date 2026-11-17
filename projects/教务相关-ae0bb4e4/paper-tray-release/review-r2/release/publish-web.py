from pathlib import Path
import hashlib,json,fcntl,subprocess,os,shutil,urllib.request,uuid
HERE=Path(__file__).resolve().parent;ROOT=Path('/opt/siyue-workbench');CURRENT=ROOT/'current';TAG='20261107-workbench-classic'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def hashes(root):return {str(p.relative_to(root)):sha(p) for p in root.rglob('*') if p.is_file()}
def identity(name):return subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.Image}}',name],text=True).strip()
def activate(path):
 temp=ROOT/('.browser-connection-'+uuid.uuid4().hex);temp.symlink_to(path);os.replace(temp,CURRENT)
locks=[]
for path in [ROOT/'.handout-library-ui-publish.lock',Path('/opt/siyue-miniapp/.queue-review-deploy.lock'),Path('/opt/siyue-miniapp/.class-create-deploy.lock'),Path('/opt/siyue-miniapp/.release.lock')]:
 lock=path.open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);locks.append(lock)
m=json.loads((HERE/'web-manifest.json').read_text());previous=CURRENT.resolve();assert str(previous)==m['previous']
for f,h in m['before'].items():assert sha(CURRENT/f)==h,'Concurrent edit: '+f
oldHashes=hashes(previous);target=ROOT/'releases'/TAG;assert not target.exists()
api=identity('siyue-miniapp-api-1');worker=identity('siyue-miniapp-worker-1')
for name in ['siyue-miniapp-api-1','siyue-miniapp-worker-1']:
 assert subprocess.check_output(['docker','exec',name,'sha256sum','/app/Siyue.Server.dll'],text=True).split()[0]=='342064cacb07d07a12c20614d4b6e26a76b342df9fa2899d5f0afc5cb9abaa1c'
shutil.copytree(previous,target)
for f,h in m['after'].items():
 assert sha(HERE/'web'/f)==h
 shutil.copy2(HERE/'web'/f,target/f);(target/f).chmod(0o644)
expected={**oldHashes,**m['after']};assert hashes(target)==expected
assert CURRENT.resolve()==previous and hashes(previous)==oldHashes
assert identity('siyue-miniapp-api-1')==api and identity('siyue-miniapp-worker-1')==worker
switched=False
try:
 activate(target);switched=True
 for f,h in m['after'].items():
  req=urllib.request.Request('https://www.siyuejn.com/miniapp/workbench/'+f+'?release='+TAG,headers={'Cache-Control':'no-cache','Accept-Encoding':'identity'})
  assert hashlib.sha256(urllib.request.urlopen(req,timeout=15).read()).hexdigest()==h,f
 assert json.load(urllib.request.urlopen('https://www.siyuejn.com/miniapp/health',timeout=15))['status']=='ok'
 assert identity('siyue-miniapp-api-1')==api and identity('siyue-miniapp-worker-1')==worker
 assert hashes(target)==expected
 result={'ok':True,'tag':TAG,'previous':str(previous),'current':str(target),'changedFiles':list(m['after']),'otherFilesUnchanged':len(oldHashes)-len(m['before']),'apiAndWorkerUnchanged':True,'publicAssetsVerified':True}
 (HERE/'deployed.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
except Exception:
 if switched and CURRENT.resolve()==target and hashes(previous)==oldHashes:activate(previous)
 raise
