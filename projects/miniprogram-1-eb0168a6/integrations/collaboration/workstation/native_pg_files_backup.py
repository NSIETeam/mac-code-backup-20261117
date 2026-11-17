"""Resident files and verified Git bundles for the workplace PostgreSQL backup.

The controller's original files are only a hard-link baseline. They are never
overwritten. Every snapshot owns a fresh directory and rsync never uses inplace.
"""
import hashlib,json,os,pathlib,shlex,subprocess,sys,tarfile,time

NODE='/opt/siyue-workstation/services/native-postgres'

def ssh():
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as d,workstation_client as w
    return w.command(d.settings()['workstation'])[:-1]

def command(args,**kw):
    p=subprocess.run(args,capture_output=True,timeout=kw.pop('timeout',180),**kw)
    if p.returncode:raise RuntimeError('Workplace file backup failed: '+pathlib.Path(args[0]).name)
    return p.stdout

def safe_relative(name):
    p=pathlib.PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or str(p)!=name or name in ('','.'):raise ValueError('Unsafe backup member')
    return p

def link_record(snapshot,path):
    target=os.readlink(path)
    if os.path.isabs(target) or not path.resolve().is_relative_to(snapshot.resolve()):raise ValueError('Backup symlink escapes snapshot')
    raw=target.encode()
    return {'path':path.relative_to(snapshot).as_posix(),'type':'symlink','target':target,'size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}

def inventory():
    script='''import json,pathlib
r=pathlib.Path('/opt/siyue-workstation/services/native-postgres')
data=json.loads((r/'staged.json').read_text());data['prepared']=json.loads((r/'prepared.json').read_text())
data['mappings']['/opt/siyue-miniapp/data/objects']=data['objects']
data['mappings']['/opt/siyue-workstation/services/native-postgres/configs']=str(r/'configs')
for source,path in data['mappings'].items():
 if not pathlib.Path(path).exists():raise ValueError('Missing resident source')
data['directories']=[k for k,v in data['mappings'].items() if pathlib.Path(v).is_dir()]
print(json.dumps(data))
'''
    return json.loads(command(ssh()+['python3 -'],input=script.encode(),timeout=40))

def sync(snapshot,previous,meta):
    root=snapshot/'resident-files';root.mkdir(mode=0o700,exist_ok=True)
    transport=ssh()
    for source,node in meta['mappings'].items():
        relative=safe_relative(source.lstrip('/'))
        if not source.startswith('/opt/siyue-') or not node.startswith('/opt/siyue-workstation/'):raise ValueError('Unexpected resident path')
        directory=source in meta['directories'];destination=root/relative
        destination.mkdir(parents=True,exist_ok=True) if directory else destination.parent.mkdir(parents=True,exist_ok=True)
        old=previous/'resident-files'/relative if previous else pathlib.Path(source)
        args=['nice','-n','15','rsync','-aH','--numeric-ids','--safe-links','--delete-delay','--bwlimit=6144','--rsync-path=/opt/siyue-workstation/bounded-rsync.py']
        if old.exists():args+=['--link-dest='+str(old if directory else old.parent)]
        if source=='/opt/siyue-collaboration/gitea':args+=['--exclude=/repositories/']
        # Temporary converter work is intentionally absent from the inventory.
        args+=['-e',shlex.join(transport[:-1]),transport[-1]+':'+node+('/' if directory else ''),str(destination)+('/' if directory else '')]
        command(args,timeout=1800)
        if os.statvfs(snapshot).f_bavail*os.statvfs(snapshot).f_frsize<5*1024**3:raise RuntimeError('Backup disk reserve reached')

def consistent_sqlite(snapshot,meta):
    script='''import io,json,pathlib,sqlite3,sys,tarfile,tempfile
meta=META
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as archive:
 for source,node in meta['mappings'].items():
  root=pathlib.Path(node)
  if source not in ['/opt/siyue-miniapp/data/feedback','/opt/siyue-miniapp/data/question-bank','/opt/siyue-auxiliary/state']:continue
  for f in root.rglob('*'):
   if not f.is_file() or f.is_symlink() or f.suffix not in ('.db','.sqlite','.sqlite3'):continue
   with f.open('rb') as check:
    if check.read(16)!=b'SQLite format 3\\x00':continue
   with tempfile.TemporaryDirectory(prefix='siyue-backup-',dir='/opt/siyue-workstation/databases') as temp:
    output=pathlib.Path(temp)/'snapshot.sqlite'
    with sqlite3.connect(f.as_uri()+'?mode=ro',uri=True) as db,sqlite3.connect(output) as dest:
     db.backup(dest);assert dest.execute('PRAGMA quick_check').fetchone()[0]=='ok'
    info=archive.gettarinfo(str(output),arcname=(pathlib.PurePosixPath(source.lstrip('/'))/f.relative_to(root)).as_posix())
    original=f.stat();info.uid=original.st_uid;info.gid=original.st_gid;info.mode=original.st_mode&0o777
    with output.open('rb') as stream:archive.addfile(info,stream)
'''.replace('META',repr(meta))
    data=command(ssh()+['python3 -'],input=script.encode(),timeout=120)
    import io
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            relative=safe_relative(item.name)
            if not item.isfile():raise ValueError('Unexpected SQLite snapshot member')
            dest=snapshot/'resident-files'/relative;dest.parent.mkdir(parents=True,exist_ok=True)
            temporary=dest.with_name(dest.name+'.consistent-pending')
            with archive.extractfile(item) as src,temporary.open('wb') as out:
                import shutil
                shutil.copyfileobj(src,out)
            os.chown(temporary,item.uid,item.gid);temporary.chmod(item.mode);temporary.replace(dest)
            for suffix in ('-wal','-shm','-journal'):pathlib.Path(str(dest)+suffix).unlink(missing_ok=True)

def repository_bundles(snapshot,meta):
    # One bounded helper reads the mounted repository files; Git itself captures
    # refs/objects into complete bundles while the live server remains available.
    inside='''import pathlib,subprocess,sys,tarfile,io
root=pathlib.Path('/opt/siyue-collaboration/gitea/repositories')
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|') as archive:
 for repo in sorted(root.glob('*/*.git')):
  refs=subprocess.check_output(['git','-C',str(repo),'show-ref'],stderr=subprocess.DEVNULL) if subprocess.run(['git','-C',str(repo),'show-ref','--quiet']).returncode==0 else b''
  name=repo.parent.name+'--'+repo.name
  raw=subprocess.check_output(['git','-C',str(repo),'bundle','create','-','--all']) if refs else b''
  member=tarfile.TarInfo(name+('.bundle' if refs else '.empty'));member.mode=0o600;member.size=len(raw);archive.addfile(member,io.BytesIO(raw))
'''
    image=next(x['image'] for x in meta['prepared']['services'] if x['name']=='siyue-pg-gitea')
    script='import subprocess,sys\np=subprocess.run('+repr(['podman','run','--rm','--network=none','--read-only','--memory=256m','--cpus=.5','--pids-limit=64','--user','983:976','--volumes-from','siyue-pg-gitea:ro','--entrypoint','python3',image,'-c',inside])+',stdout=sys.stdout.buffer,stderr=sys.stderr)\nraise SystemExit(p.returncode)'
    archive=snapshot/'repositories.tar';archive.write_bytes(command(ssh()+['python3 -'],input=script.encode(),timeout=180))
    bundles=snapshot/'repositories';bundles.mkdir(mode=0o700)
    verify=snapshot/'verify.git';command(['git','init','--bare',str(verify)])
    count=0
    try:
        with tarfile.open(archive) as tar:
            for member in tar:
                name=safe_relative(member.name)
                if len(name.parts)!=1 or not member.isfile() or name.suffix not in ('.bundle','.empty'):raise ValueError('Invalid repository bundle')
                file=bundles/name
                with tar.extractfile(member) as src,file.open('wb') as out:
                    import shutil
                    shutil.copyfileobj(src,out)
                if name.suffix=='.bundle':command(['git','-C',str(verify),'bundle','verify',str(file)])
                count+=1
    finally:
        import shutil
        shutil.rmtree(verify)
    archive.unlink()
    return count

def controller_files(snapshot):
    import sqlite3,shutil,uuid
    source=pathlib.Path('/opt/siyue-collaboration');target=snapshot/'controller-files';target.mkdir(mode=0o700)
    with sqlite3.connect((source/'delivery.db').as_uri()+'?mode=ro',uri=True) as db,sqlite3.connect(target/'delivery.db') as dest:
        db.backup(dest);assert dest.execute('PRAGMA quick_check').fetchone()[0]=='ok'
        completed={row[0] for row in dest.execute("SELECT id FROM jobs WHERE kind='build' AND state='succeeded'")}
    with tarfile.open(target/'configuration.tar.gz','w:gz') as archive:
        for name in ['config','secrets.json','delivery.json','runtime',*[x.name for x in source.glob('*.py')]]:
            if (source/name).exists():archive.add(source/name,arcname=name)
    sites=target/'sites';sites.mkdir()
    # Published site versions are immutable; delivery swaps only the pointer.
    command(['rsync','-aH','--exclude=*.tmp','--exclude=*.new-*','--link-dest='+str(source/'sites'),str(source/'sites')+'/',str(sites)+'/'],timeout=300)
    for link in sites.rglob('*'):
        if not link.is_symlink():continue
        raw=os.readlink(link)
        if os.path.isabs(raw):
            original=pathlib.Path(raw)
            if not original.is_relative_to(source/'sites'):raise ValueError('Unexpected published site link')
            dest=sites/original.relative_to(source/'sites');link.unlink();link.symlink_to(os.path.relpath(dest,link.parent))
        link_record(snapshot,link)
    artifacts=target/'artifacts';artifacts.mkdir()
    for file in (source/'artifacts').glob('*.tar'):
        if file.stem not in completed:continue
        uuid.UUID(file.stem)
        if file.is_symlink() or file.stat().st_mode&0o222:raise ValueError('Completed artifact is not immutable')
        os.link(file,artifacts/file.name)

def finish(snapshot,meta):
    consistent_sqlite(snapshot,meta);count=repository_bundles(snapshot,meta)
    controller_files(snapshot)
    records=[];inodes={};size=0
    for folder in ['resident-files','repositories','controller-files']:
        for p in sorted((snapshot/folder).rglob('*')):
            if p.is_symlink():
                record=link_record(snapshot,p);records.append(record);size+=record['size'];continue
            if not p.is_file():continue
            before=p.stat();key=(before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)
            if key not in inodes:
                with p.open('rb') as stream:inodes[key]=hashlib.file_digest(stream,'sha256').hexdigest()
            after=p.stat()
            if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise ValueError('Snapshot file changed during hashing')
            records.append({'path':p.relative_to(snapshot).as_posix(),'size':before.st_size,'sha256':inodes[key]});size+=before.st_size
    result={'at':time.time(),'files':records,'logicalBytes':size,'repositoriesVerified':count,'databaseSystemIdentifier':meta['prepared']['systemIdentifier']}
    (snapshot/'resident-files-manifest.json').write_text(json.dumps(result,separators=(',',':')))
    return {'files':len(records),'logicalBytes':size,'repositoriesVerified':count}
