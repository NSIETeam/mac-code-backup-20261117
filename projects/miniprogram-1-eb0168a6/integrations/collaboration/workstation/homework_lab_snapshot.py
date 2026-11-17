"""Online, private snapshots of the isolated homework lab; no application writes."""
from contextlib import closing
import datetime,fcntl,hashlib,json,os,pathlib,shutil,sqlite3,subprocess,tarfile,time,uuid
MAX_BYTES=4*1024**3

def digest(path):
    with pathlib.Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def files(root):
    result={}
    for p in sorted(pathlib.Path(root).rglob('*')):
        if p.is_symlink():raise ValueError('Snapshot cannot follow symlinks')
        if p.is_file():result[str(p.relative_to(root))]={'sha256':digest(p),'size':p.stat().st_size}
    return result

def rows(path):
    db=sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True)
    try:
        db.execute('BEGIN')
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('SQLite integrity failure')
        result={}
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
            quoted='"'+table.replace('"','""')+'"'
            hashes=[]
            for row in db.execute('SELECT * FROM '+quoted):
                body=json.dumps(list(row),ensure_ascii=False,separators=(',',':'),default=lambda x:x.hex()).encode()
                hashes.append(hashlib.sha256(body).hexdigest())
            result[table]={'rows':len(hashes),'sha256':hashlib.sha256('\n'.join(sorted(hashes)).encode()).hexdigest()}
        return result
    finally:db.close()

def stable_assets(data):
    names={p.name for p in data.iterdir()}
    if names-{'isolated.db','isolated.db-wal','isolated.db-shm','keys','objects'}:raise ValueError('Unreviewed data directory')
    return {name:files(data/name) if (data/name).exists() else {} for name in ('keys','objects')}

def snapshot(data,parent,environment,identity):
    os.umask(0o077);data=pathlib.Path(data);parent=pathlib.Path(parent)
    if data.is_symlink() or not (data/'isolated.db').is_file():raise ValueError('Unexpected data root')
    parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    if shutil.disk_usage(parent).free<5*1024**3:raise ValueError('Insufficient snapshot reserve')
    name=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
    stage=parent/(name+'.partial');stage.mkdir(mode=0o700);dest=stage/'data';dest.mkdir()
    archive=None
    try:
        assets=stable_assets(data)
        estimate=sum(x['size'] for group in assets.values() for x in group.values())+sum(p.stat().st_size for p in data.glob('isolated.db*'))+1024*1024
        if estimate>MAX_BYTES:raise ValueError('Snapshot exceeds fixed size limit')
        if shutil.disk_usage(parent).free<5*1024**3+2*estimate:raise ValueError('Insufficient staging reserve')
        deadline=time.monotonic()+60
        def progress(*_):
            if time.monotonic()>deadline:raise TimeoutError('SQLite snapshot deadline')
        with closing(sqlite3.connect('file:'+str(data/'isolated.db')+'?mode=ro',uri=True,timeout=10)) as source,closing(sqlite3.connect(dest/'isolated.db')) as target:
            source.backup(target,pages=256,progress=progress,sleep=0.05)
            if target.execute("PRAGMA journal_mode=DELETE").fetchone()[0]!="delete":raise ValueError("Snapshot journal finalization failed")
        for directory in ('keys','objects'):
            if (data/directory).exists():shutil.copytree(data/directory,dest/directory)
        if stable_assets(data)!=assets:raise ValueError('Assets changed while taking snapshot; retry later')
        for directory in ('keys','objects'):
            if (files(dest/directory) if (dest/directory).exists() else {})!=assets[directory]:raise ValueError('Copied asset mismatch')
        (stage/'service.env').write_bytes(environment)
        manifest={'createdAt':time.time(),'identity':identity,'files':files(dest),'database':rows(dest/'isolated.db'),'environmentSha256':digest(stage/'service.env')}
        (stage/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
        expanded=sum(p.stat().st_size for p in stage.rglob('*') if p.is_file())
        if expanded>MAX_BYTES:raise ValueError('Expanded snapshot exceeds limit')
        archive=parent/(name+'.tar.gz')
        with tarfile.open(archive,'w:gz',compresslevel=1) as tar:
            for item in stage.iterdir():tar.add(item,arcname=item.name)
        archive.chmod(0o600)
        result={'name':name,'file':str(archive),'size':archive.stat().st_size,'sha256':digest(archive),'tables':len(manifest['database']),'expandedBytes':expanded}
        shutil.rmtree(stage);return result
    except Exception:
        # No completed snapshot or retention update can result from partial work.
        shutil.rmtree(stage,ignore_errors=True)
        if archive is not None:archive.unlink(missing_ok=True)
        raise

def verify_environment(expected,actual):
    extras=set(actual)-set(expected)
    if extras-{'HOSTNAME','HOME','container'} or any(actual.get(k)!=v for k,v in expected.items()):
        raise ValueError('Application runtime environment changed')
    return sorted(extras)

def node_snapshot(root,container_id,image,dll):
    import resource
    os.nice(15)
    root=pathlib.Path(root)
    with (root/'activation.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        v=json.loads(subprocess.check_output(['podman','inspect','siyue-service-homework-lab']))[0]
        if v['Id']!=container_id or not v['State']['Running'] or not (root/'enabled').exists():raise ValueError('Destination identity or ownership changed')
        if v['Image'].removeprefix('sha256:')!=image or digest(root/'candidate/Siyue.Server.dll')!=dll:raise ValueError('Runtime changed')
        identity={'location':'workstation','containerId':v['Id'],'image':image,'dllSha256':dll,'workingDirectory':v['Config']['WorkingDir']}
        environment=(root/'service.env').read_bytes()
        actual=dict(x.split('=',1) for x in v['Config']['Env'])
        identity['engineEnvironmentKeys']=verify_environment(dict(x.split('=',1) for x in environment.decode().splitlines()),actual)
        resource.setrlimit(resource.RLIMIT_AS,(512*1024**2,512*1024**2))
        return snapshot(root/'data',root/'snapshots',environment,identity)
