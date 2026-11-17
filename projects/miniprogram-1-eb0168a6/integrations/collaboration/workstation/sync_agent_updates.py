#!/usr/bin/env python3
"""Publish an update manifest on workplace only after its installer is verified.

Main remains the package publishing source. Run --once after publishing for an
acknowledged deployment; the timer also reconciles existing publishing scripts.
"""
import argparse,base64,fcntl,hashlib,json,os,pathlib,re,shlex,subprocess,sys,time

SOURCE=pathlib.Path('/opt/siyue-miniapp/data/updates')
TARGET='/opt/siyue-workstation/services/native-postgres/source-tree/opt/siyue-miniapp/data/updates'
STATE=pathlib.Path('/opt/siyue-collaboration/workstation-update-sync')

def validate(relative,raw):
    path=pathlib.PurePosixPath(relative)
    if str(path)!=relative or path.is_absolute() or '..' in path.parts:raise ValueError('Invalid manifest path')
    if relative!='release.json' and not re.fullmatch(r'devices/[0-9a-fA-F-]{36}/release\.json',relative):raise ValueError('Unexpected manifest location')
    m=json.loads(raw)
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}',m['version']):raise ValueError('Invalid version')
    if not re.fullmatch(r'[0-9a-fA-F]{64}',m['sha256']):raise ValueError('Invalid installer digest')
    name=m['fileName']
    if not re.fullmatch(r'[A-Za-z0-9._-]+\.exe',name) or name.startswith('.'):raise ValueError('Invalid installer filename')
    if type(m['size']) is not int or not 0<m['size']<=314572800:raise ValueError('Invalid installer size')
    return m

def unchanged(path,before):
    after=path.stat()
    return (before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_ino,after.st_size,after.st_mtime_ns)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--once',action='store_true');parser.parse_args()
    if os.geteuid()!=0:raise PermissionError('Root required')
    os.umask(0o077);STATE.mkdir(mode=0o700,exist_ok=True)
    with (STATE/'sync.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
        ssh=w.command(d.settings()['workstation'])[:-1]
        def node(code):
            p=subprocess.run(ssh+['python3 -'],input=code,text=True,capture_output=True,timeout=90)
            if p.returncode:
                (STATE/'last-error.log').write_text(p.stderr);raise RuntimeError('Workplace update sync failed; see private log')
            return json.loads(p.stdout)
        receipts=[]
        for path in sorted(SOURCE.rglob('release.json')):
            if path.is_symlink():raise ValueError('Manifest must not be a symlink')
            relative=path.relative_to(SOURCE).as_posix();raw=path.read_bytes();m=validate(relative,raw)
            # Immutable target names keep the previous manifest usable during publication.
            published={**m,'fileName':m['fileName'][:-4]+'-'+m['sha256'].lower()[:16]+'.exe'}
            published_raw=json.dumps(published,ensure_ascii=False,separators=(',',':')).encode()
            package=path.parent/m['fileName']
            if package.is_symlink() or not package.is_file() or package.stat().st_size!=m['size']:raise ValueError('Installer missing or invalid')
            cachekey=hashlib.sha256(relative.encode()).hexdigest()
            identity={'manifestSha256':hashlib.sha256(raw).hexdigest(),'inode':package.stat().st_ino,'mtime':package.stat().st_mtime_ns,'size':m['size']}
            cached=STATE/(cachekey+'.json')
            if not cached.exists() or json.loads(cached.read_text()).get('source')!=identity:
                before=package.stat()
                with package.open('rb') as stream:sha=hashlib.file_digest(stream,'sha256').hexdigest()
                if sha!=m['sha256'].lower() or not unchanged(package,before):raise ValueError('Installer changed or hash mismatch')
            parent=str(pathlib.PurePosixPath(TARGET)/pathlib.PurePosixPath(relative).parent)
            code='''import hashlib,json,pathlib,os
p=pathlib.Path(PARENT);p.mkdir(parents=True,exist_ok=True)
f=p/NAME;manifest=p/'release.json'
cache=pathlib.Path('/opt/siyue-workstation/services/native-postgres/update-receipts');cache.mkdir(mode=0o700,exist_ok=True)
receipt=cache/KEY
def stats(f):
 s=f.stat();return [s.st_ino,s.st_size,s.st_mtime_ns]
ready=False
if receipt.exists() and f.is_file() and not f.is_symlink():
 c=json.loads(receipt.read_text());ready=c.get('sha256')==SHA and c.get('stat')==stats(f)
if not ready:
 for candidate in [f,p/SOURCE_NAME]:
  if candidate.is_file() and not candidate.is_symlink() and candidate.stat().st_size==SIZE:
   with candidate.open('rb') as stream:valid=hashlib.file_digest(stream,'sha256').hexdigest()==SHA
   if valid:
    if candidate!=f:
     if f.exists():raise ValueError('Immutable package path already has different contents')
     os.link(candidate,f)
    ready=True;receipt.write_text(json.dumps({'sha256':SHA,'stat':stats(f)}));receipt.chmod(0o600);break
print(json.dumps({'packageReady':ready,'manifestReady':manifest.is_file() and hashlib.sha256(manifest.read_bytes()).hexdigest()==MANIFEST}))
'''
            for k,v in {'PARENT':parent,'SOURCE_NAME':m['fileName'],'NAME':published['fileName'],'KEY':cachekey+'.json','SIZE':m['size'],'SHA':m['sha256'].lower(),'MANIFEST':hashlib.sha256(published_raw).hexdigest()}.items():code=code.replace(k,repr(v))
            status=node(code)
            pending=parent+'/.incoming-'+m['sha256'].lower()+'.exe'
            if not status['packageReady']:
                # The pending name is never exposed by the running API.
                with (STATE/'transfer.log').open('ab') as log:
                    p=subprocess.run(['rsync','-a','--partial','--bwlimit=6144','--rsync-path=/opt/siyue-workstation/bounded-rsync.py','-e',shlex.join(ssh[:-1]),str(package),ssh[-1]+':'+pending],stdout=log,stderr=log,timeout=900)
                if p.returncode:raise RuntimeError('Installer transfer failed; previous release remains active')
            if path.read_bytes()!=raw:raise RuntimeError('Release changed during transfer; retry without publishing stale manifest')
            if not status['packageReady'] or not status['manifestReady']:
                code='''import base64,hashlib,json,os,pathlib
p=pathlib.Path(PARENT);pending=pathlib.Path(PENDING);target=p/NAME
f=target if READY else pending
if f.is_symlink() or not f.is_file() or f.stat().st_size!=SIZE:raise ValueError('Invalid incoming installer')
with f.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==SHA
with f.open('rb') as stream:os.fsync(stream.fileno())
if not READY:
 os.chown(f,1654,1654);f.chmod(0o644);f.replace(target)
raw=base64.b64decode(RAW);tmp=p/'.release.pending';tmp.write_bytes(raw);os.chown(tmp,1654,1654);tmp.chmod(0o644)
with tmp.open('rb') as stream:os.fsync(stream.fileno())
tmp.replace(p/'release.json');fd=os.open(p,os.O_DIRECTORY);os.fsync(fd);os.close(fd)
print(json.dumps({'published':True}))
'''
                for k,v in {'PARENT':parent,'PENDING':pending,'NAME':published['fileName'],'READY':status['packageReady'],'SIZE':m['size'],'SHA':m['sha256'].lower(),'RAW':base64.b64encode(published_raw).decode()}.items():code=code.replace(k,repr(v))
                node(code)
            receipt={'source':identity,'relative':relative,'version':m['version'],'sha256':m['sha256'].lower(),'acknowledgedAt':time.time()}
            tmp=cached.with_suffix('.pending');tmp.write_text(json.dumps(receipt));tmp.replace(cached);receipts.append(receipt)
        (STATE/'state.json').write_text(json.dumps({'at':time.time(),'acknowledged':receipts},indent=2))
        print(json.dumps({'acknowledged':len(receipts),'versions':[x['version'] for x in receipts]}))

if __name__=='__main__':main()
