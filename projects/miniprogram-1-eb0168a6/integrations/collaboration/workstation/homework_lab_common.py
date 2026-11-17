"""Fixed-target transfer and integrity checks for the isolated homework laboratory."""
import hashlib,io,json,os,pathlib,sqlite3,subprocess,sys,tarfile,time
STATE=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/homework-lab-migration')
SOURCE=pathlib.Path('/opt/siyue-miniapp/academic-homework-lab/20261102')
NODE='/opt/siyue-workstation/services/homework-lab'
SOURCE_NAME='siyue-homework-lab-20261102'
NAME='siyue-service-homework-lab'
SOURCE_IMAGE='d04e0a5f04000c569dcda0e81df561f524430b98778b01036ba83fe6bcd06ae2'
# Docker exports this source manifest with the following configuration digest.
IMAGE='4466a408b270591435b03d3afa7fcbbb22fe950f4ad516c4bad285a355f7e0be'
DLL='6134fcd1a0c9cf12a3fff2eef220d51fe9146e8d2251b18e6c70daa2cbfb4119'
PROXY='siyue-workstation-homework-lab'
PUBLIC='https://www.siyuejn.com/miniapp/academic-lab/'

def digest(p):
    with pathlib.Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def run(args,**kwargs):
    p=subprocess.run(args,capture_output=True,timeout=kwargs.pop('timeout',120),**kwargs)
    if p.returncode:raise RuntimeError('Command failed: '+args[0]+' exit '+str(p.returncode))
    return p.stdout

def save(name,obj):
    STATE.mkdir(mode=0o700,parents=True,exist_ok=True)
    p=STATE/name;q=p.with_suffix('.pending');q.write_text(json.dumps(obj,ensure_ascii=False,indent=2));q.chmod(0o600);q.replace(p)

def ssh():
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as d,workstation_client as w
    return w.command(d.settings()['workstation'])[:-1]

def node(script,timeout=120):
    return json.loads(run(ssh()+['python3 -'],input=script,text=True,timeout=timeout))

def manifest(root):
    root=pathlib.Path(root);files={}
    for p in sorted(root.rglob('*')):
        if p.is_symlink():raise ValueError('Unexpected symlink in fixed service tree')
        if p.is_file():files[str(p.relative_to(root))]={'sha256':digest(p),'size':p.stat().st_size}
    return files

# Shared verbatim with the remote process, so both sides hash SQLite rows identically.
DATABASE_CODE='''
def database(path):
    import sqlite3,hashlib,json
    db=sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True);db.execute('BEGIN')
    if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('SQLite integrity failure')
    tables=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    result={}
    for t in tables:
        quoted='"'+t.replace('"','""')+'"'
        rows=[json.dumps(list(r),ensure_ascii=False,separators=(',',':'),default=lambda x:x.hex()) for r in db.execute('SELECT * FROM '+quoted)]
        result[t]={'rows':len(rows),'sha256':hashlib.sha256('\\n'.join(sorted(rows)).encode()).hexdigest()}
    active=db.execute("SELECT count(*) FROM AcademicGradingJobs WHERE State <> 'reviewed'").fetchone()[0]
    db.close()
    if active:raise ValueError('Pending AI work must finish before migration')
    return result
'''
exec(DATABASE_CODE)

def source():
    v=json.loads(run(['docker','inspect',SOURCE_NAME],text=True))[0]
    e=dict(x.split('=',1) for x in v['Config']['Env'])
    assert v['Image']=='sha256:'+SOURCE_IMAGE and v['Config']['Entrypoint']==['dotnet']
    assert v['Config']['Cmd']==['/candidate/Siyue.Server.dll'] and v['Config']['User']=='app'
    assert e['ConnectionStrings__Database']=='Data Source=/data/isolated.db'
    assert e['AcademicPilot__IsolatedHomeworkLab']=='true' and e['RunWorker']=='false'
    assert e['Database__Initialize']=='false' and e['ASPNETCORE_URLS']=='http://127.0.0.1:5092'
    assert e['PublicUrl']==PUBLIC.rstrip('/')
    assert digest(SOURCE/'candidate/Siyue.Server.dll')==DLL
    mounts={x['Destination']:(x['Source'],x['RW']) for x in v['Mounts']}
    assert mounts=={'/candidate':(str(SOURCE/'candidate'),False),'/data':(str(SOURCE/'data'),True)}
    return v,e

def source_identity():
    v,e=source()
    return dict(id=v['Id'],image=v['Image'],configSha256=hashlib.sha256(json.dumps(v['Config'],sort_keys=True).encode()).hexdigest(),
                candidate=manifest(SOURCE/'candidate'),restartPolicy=v['HostConfig']['RestartPolicy'])

def ensure_identity():
    saved=json.loads((STATE/'baseline.json').read_text());now=source_identity()
    for k in ('id','image','configSha256','candidate'):
        if saved[k]!=now[k]:raise ValueError('Source changed: '+k)
    return saved

def pack(directory,target):
    with tarfile.open(target,'w:gz',compresslevel=1) as t:
        for p in pathlib.Path(directory).iterdir():t.add(p,arcname=p.name)
    pathlib.Path(target).chmod(0o600)

def send_archive(archive,remote_dir):
    import shlex
    script='umask 077; mkdir -p '+shlex.quote(remote_dir)+' && tar -xzf - -C '+shlex.quote(remote_dir)
    with pathlib.Path(archive).open('rb') as f:
        p=subprocess.run(ssh()+[script],stdin=f,capture_output=True,timeout=240)
    if p.returncode:raise RuntimeError('Service archive transfer failed')

def receive_data(target):
    with pathlib.Path(target).open('xb') as f:
        p=subprocess.run(ssh()+['tar -czf - -C '+NODE+'/data .'],stdout=f,stderr=subprocess.PIPE,timeout=120)
    target.chmod(0o600)
    if p.returncode:raise RuntimeError('Data return transfer failed')

def extract_data(archive,target):
    target.mkdir(mode=0o700)
    with tarfile.open(archive,'r:gz') as tar:
        for member in tar.getmembers():
            if member.issym() or member.islnk() or member.isdev():raise ValueError('Unexpected archive member')
            if not (target/member.name).resolve().is_relative_to(target.resolve()):raise ValueError('Archive path escapes data directory')
        tar.extractall(target,filter='data')
    for p in [target,*target.rglob('*')]:os.chown(p,1654,1654)
    target.chmod(0o755)

def remote_integrity():
    script='import pathlib,json,hashlib\n'+DATABASE_CODE+'''\nr=pathlib.Path(ROOT)
rows=database(r/'data/isolated.db')
files={}
for p in sorted((r/'data').rglob('*')):
 if p.is_file():
  with p.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
  files[str(p.relative_to(r/'data'))]={'sha256':sha,'size':p.stat().st_size}
print(json.dumps({'files':files,'database':rows}))
'''
    return node(script.replace('ROOT',repr(NODE)))


def image_equivalence(source,target):
    for key in ('Architecture','Os','Variant','Created','Config'):
        if source.get(key)!=target.get(key):raise ValueError('Image configuration differs: '+key)
    if source['RootFS']['Layers']!=target['RootFS']['Layers']:raise ValueError('Image layers differ')
    return dict(sourceId=source['Id'],targetId=target['Id'],layerCount=len(source['RootFS']['Layers']),configEqual=True,layersEqual=True)
