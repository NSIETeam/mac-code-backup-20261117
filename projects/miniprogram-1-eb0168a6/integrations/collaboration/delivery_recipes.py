"""Trusted source assembly and on-demand package previews. Installed by administrators.
No commands, remote URLs, mounts or runtime privileges are accepted from source manifests.
"""
import base64,hashlib,html,json,mimetypes,os,pathlib,re,secrets,shutil,sqlite3,subprocess,threading,time,urllib.request,urllib.error
import workstation_client as workstation
RUNTIME_LOCK=threading.RLock()


def snapshot(cfg):
    source=pathlib.Path(cfg.get('recipes_path','/opt/siyue-ci'));digest=hashlib.sha256()
    for file in sorted(source.rglob('*')):
        if file.is_symlink():raise ValueError('trusted recipes may not contain symlinks')
        if file.is_file():digest.update(str(file.relative_to(source)).encode()+b'\0'+file.read_bytes())
    version=digest.hexdigest();root=pathlib.Path('/opt/siyue-ci-versions');root.mkdir(mode=0o755,exist_ok=True);root.chmod(0o755);target=root/version
    if not target.exists():
        temp=root/(version+'.tmp');shutil.rmtree(temp,ignore_errors=True);shutil.copytree(source,temp)
        for file in temp.rglob('*'):file.chmod(0o555 if file.is_dir() else 0o444)
        temp.chmod(0o555);temp.rename(target)
    return target,version

def git_json(d,cfg,repo,path):
    request=urllib.request.Request(cfg['gitea_url'].rstrip('/')+'/api/v1/repos/'+repo+'/'+path,headers={'Authorization':'token '+cfg['gitea_token']})
    with urllib.request.urlopen(request,timeout=45) as response:return json.load(response)

def fetch(d,cfg,repo,commit,dest):
    d.sha(commit)
    req=urllib.request.Request(cfg['gitea_url'].rstrip('/')+'/api/v1/repos/'+repo+'/archive/'+commit+'.tar.gz',headers={'Authorization':'token '+cfg['gitea_token']})
    with urllib.request.urlopen(req,timeout=45) as response:data=response.read(100*1024**2+1)
    if len(data)>100*1024**2:raise ValueError('source exceeds quota')
    dest.mkdir(parents=True);d.unpack(data,dest);children=list(dest.iterdir())
    if len(children)!=1 or not children[0].is_dir():raise ValueError('unexpected source layout')
    return children[0]

def assemble(d,cfg,t,commit,workspace):
    family=t.get('family');origin=t['repository']
    if not family:return fetch(d,cfg,origin,commit,workspace/'source')
    parent=family['parent'];parent_sha=commit if origin==parent else git_json(d,cfg,parent,'branches/main')['commit']['id']
    src=fetch(d,cfg,parent,parent_sha,workspace/'source');manifest={parent:parent_sha};owned=set()
    # Registry comes from root-owned configuration, never .gitmodules supplied by a teacher.
    for mod in family['modules']:
        pin=commit if mod['repository']==origin else git_json(d,cfg,parent,'contents/'+mod['path']+'?ref='+parent_sha)['sha']
        sub=fetch(d,cfg,mod['repository'],pin,workspace/'parts'/mod['name']);manifest[mod['repository']]=pin
        paths=json.loads((sub/'module-manifest.json').read_text())['files']
        for name in paths:
            rel=pathlib.PurePosixPath(name)
            if rel.is_absolute() or '..' in rel.parts or '\\' in name or not rel.parts or rel.parts[0] not in {'server','miniprogram','integrations','tests','agent','deploy'}:raise ValueError('unsafe module path')
            if name in owned:raise ValueError('duplicate module ownership')
            owned.add(name);source=sub/name;target=src/name
            if not source.is_file() or source.is_symlink() or target.exists():raise ValueError('module file collision or missing source: '+name)
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    (src/'__siyue_sources.json').write_text(json.dumps(manifest,sort_keys=True))
    return src

def package(d,job,request,production):
    if production:raise ValueError('此项目尚未配置正式发布目标；测试包不能更新现有正式网址')
    cfg,t,key=d.target(request);bid=d.validate_id(request.get('buildId',request['id']));artifact,build=d.completed_build(bid,key,request['digest'])
    release=d.ROOT/'sites'/key/'versions'/bid
    if not release.exists():
        temp=release.with_name(bid+'.tmp');temp.mkdir(parents=True,exist_ok=True);d.unpack(artifact.read_bytes(),temp)
        (temp/'__siyue_version.json').write_text(json.dumps({'buildId':bid,'sha':build['sha'],'digest':request['digest']}))
        for f in [temp,*temp.rglob('*')]:f.chmod(0o755 if f.is_dir() else 0o644)
        temp.rename(release)
    # Health check the actual application before declaring this build preview-ready.
    with RUNTIME_LOCK:ensure_runtime(d,t,key,bid,release)
    d.update(job,'succeeded',summary='隔离测试启动及健康检查通过；可在项目内体验此提交',digest=request['digest'],sha=build['sha'])

def reap(d,keep=None):
    with RUNTIME_LOCK:
        state=d.ROOT/'preview-runtime.json'
        if state.exists():
            row=json.loads(state.read_text())
            if row['buildId']!=keep and time.time()-row.get('usedAt',0)>90:
                subprocess.run(['docker','rm','-f',row['container']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30);state.unlink(missing_ok=True)

def ensure_runtime(d,t,key,bid,release,viewed=False):
    remote=workstation.ensure(d,t,key,bid,release,viewed=viewed)
    if remote is not None:return remote
    state=d.ROOT/'preview-runtime.json';old=json.loads(state.read_text()) if state.exists() else None
    if old and old['buildId']==bid:
        alive=subprocess.run(['docker','inspect','-f','{{.State.Running}}',old['container']],capture_output=True,text=True).stdout.strip()=='true'
        if alive:
            old['usedAt']=time.time()
            if viewed:old['viewerAt']=time.time()
            state.write_text(json.dumps(old));return old
    # One active preview slot. Another active viewer receives a retry, never an unrelated build.
    if old and old['buildId']!=bid and time.time()-old.get('viewerAt',0)<25:raise ValueError('另一测试版本正在使用，稍后重试；当前版本不会被替换')
    if old:subprocess.run(['docker','rm','-f',old['container']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30);state.unlink(missing_ok=True)
    if d.memory_available()<800*1024**2:raise ValueError('服务器内存不足，测试环境等待资源')
    name='siyue-preview-'+bid[:8];data=d.ROOT/'preview-storage';data.mkdir(parents=True,exist_ok=True);disk=d.ROOT/'preview-storage.img'
    if not disk.exists():d.run(['truncate','-s','768M',str(disk)]);d.run(['mkfs.ext4','-F',str(disk)])
    if not os.path.ismount(data):d.run(['mount','-o','loop,nosuid,nodev,noexec',str(disk),str(data)])
    # Only one preview runs. Its bounded test disk is wiped after the previous container stops.
    for file in data.iterdir():
        if file.is_dir() and not file.is_symlink():shutil.rmtree(file)
        else:file.unlink()
    os.chown(data,1000,1000);data.chmod(0o700)
    args=['docker','run','-d','--name',name,'--label','siyue.preview=true','--network','none','--read-only','--user','1000:1000','--cap-drop=ALL','--security-opt=no-new-privileges','--memory=384m','--cpus=0.5','--pids-limit=128','--tmpfs','/tmp:rw,size=64m,uid=1000,gid=1000','-v',str(data)+':/data:rw','-v',str(release)+':/app:ro','-w','/app']
    for k,v in t.get('environment',{}).items():args.extend(['-e',k+'='+str(v)])
    token=secrets.token_hex(32)
    args.extend(['-e','SIYUE_PREVIEW_TOKEN='+token,'-e','Preview__SessionToken='+token])
    args.extend([t['runtime_image'],*t['runtime_command']])
    subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    d.run(args,timeout=45)
    # --network=none has only loopback: proxy via docker exec stdin, never host networking.
    row={'container':name,'buildId':bid,'key':key,'usedAt':time.time(),'viewerAt':time.time() if viewed else 0}
    try:
        for _ in range(60):
            try:
                result=runtime_request(d,row,{'path':t.get('health_path','health'),'method':'GET'},t)
                if result['status']==200:
                    ready=subprocess.run(['docker','exec',name,'python3','-c',"import pathlib,sys; sys.exit(0 if pathlib.Path('/data/preview-meta.json').is_file() else 1)"],capture_output=True,timeout=5)
                    if ready.returncode==0:break
            except Exception:pass
            time.sleep(.5)
        else:raise ValueError('测试应用健康检查失败')
    except Exception:
        logs=subprocess.run(['docker','logs','--tail','30',name],capture_output=True,text=True).stdout
        (d.ROOT/'logs'/(bid+'.runtime.log')).write_text(logs[-12000:]);subprocess.run(['docker','rm','-f',name],capture_output=True);raise
    state.write_text(json.dumps(row));return row

# A fixed in-container helper transports HTTP over stdin/stdout. No shell interpolation.
NODE_PROXY="""const http=require('node:http');let s='';process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{const q=JSON.parse(s),r=http.request({host:'127.0.0.1',port:8080,path:'/'+q.path,method:q.method||'GET',headers:q.headers||{}},res=>{let chunks=[],size=0;res.on('data',c=>{size+=c.length;if(size>8388608)process.exit(2);chunks.push(c)});res.on('end',()=>process.stdout.write(JSON.stringify({status:res.statusCode,body:Buffer.concat(chunks).toString('base64'),type:res.headers['content-type']||'text/plain',cookies:res.headers['set-cookie']||[]})))});r.on('error',()=>process.exit(3));r.setTimeout(12000,()=>process.exit(4));r.end(Buffer.from(q.body||'','base64'))})"""
PY_PROXY="""import json,sys,urllib.request,urllib.error,base64
q=json.load(sys.stdin);r=urllib.request.Request('http://127.0.0.1:8080/'+q['path'],data=base64.b64decode(q.get('body','')) or None,method=q.get('method','GET'),headers=q.get('headers',{}))
try:r=urllib.request.urlopen(r,timeout=12)
except urllib.error.HTTPError as e:r=e
print(json.dumps({'status':r.status,'body':base64.b64encode(r.read(8388609)).decode(),'type':r.headers.get('Content-Type','text/plain'),'cookies':r.headers.get_all('Set-Cookie') or []}))
"""
def runtime_request(d,row,request,t):
    if row.get('backend')=='workstation':return workstation.request(d,row,request)
    args=['docker','exec','-i',row['container'],*(t.get('proxy_command') or ['node','-e',NODE_PROXY])]
    p=subprocess.run(args,input=json.dumps(request).encode(),capture_output=True,timeout=20)
    if p.returncode or len(p.stdout)>12*1024**2:raise ValueError('测试服务未响应')
    result=json.loads(p.stdout)
    if len(base64.b64decode(result['body']))>8*1024**2:raise ValueError('preview response exceeds quota')
    return result

def preview(d,request,t,key):
    bid=d.validate_id(request['buildId']);d.completed_build(bid,key,request['digest']);release=d.ROOT/'sites'/key/'versions'/bid
    if not release.is_dir():raise ValueError('测试部署尚未完成')
    path=request.get('path','')
    if not path or path=='index.html':path=t.get('entry','index.html')
    if path.split('?')[0]=='__siyue_version.json':
        # Visible preview pages must protect their workstation slot too.
        workstation.touch(d,key,bid,request['digest'])
        with RUNTIME_LOCK:
            state=d.ROOT/'preview-runtime.json'
            if state.exists():
                row=json.loads(state.read_text())
                if row['buildId']==bid:row['usedAt']=time.time();row['viewerAt']=time.time();state.write_text(json.dumps(row))
        return {'status':200,'type':'application/json','body':base64.b64encode((release/'__siyue_version.json').read_bytes()).decode()}
    # Serve immutable published web assets directly; only application requests need a runtime.
    # In particular, dozens of ES module imports must not each start a docker-exec process.
    kind=t.get('environment',{}).get('SIYUE_PREVIEW_KIND');asset=path.split('?')[0];webroot=None
    if kind in ('miniapp','collaboration'):webroot=release/'wwwroot'
    elif kind=='crm' and '/' not in asset and asset!='deployment-config.js' and pathlib.Path(asset).suffix.lower() in ('.js','.css','.svg','.png','.jpg','.woff','.woff2'):webroot=release/'site'
    elif kind=='information' and asset.startswith('operations-static/'):
        webroot=release/'site/operations/static';asset=asset.removeprefix('operations-static/')
    if request.get('method','GET')=='GET' and webroot is not None:
        webroot=webroot.resolve();file=(webroot/asset).resolve()
        if webroot in file.parents and file.suffix.lower() not in ('.html','.htm') and file.is_file():
            if file.stat().st_size>8*1024**2:raise ValueError('preview response exceeds quota')
            return {'status':200,'type':mimetypes.guess_type(file.name)[0] or 'application/octet-stream','body':base64.b64encode(file.read_bytes()).decode()}
    with RUNTIME_LOCK:
        try:row=ensure_runtime(d,t,key,bid,release,viewed=True)
        except ValueError as error:
            if not any(x in str(error) for x in ['正在使用','等待资源']):raise
            page='<html><head><meta charset="utf-8"></head><body><p>'+html.escape(str(error))+'</p><p>此页面保持当前提交，5 秒后自动重试。</p><script>setTimeout(()=>location.reload(),5000)</script></body></html>'
            return {'status':503,'type':'text/html; charset=utf-8','body':base64.b64encode(page.encode()).decode()}
        headers={'Content-Type':request.get('type','application/json')}
        auth=request.get('authorization','')
        if auth:headers['Authorization']=auth
        if request.get('workbenchToken'):headers['X-Workbench-Token']=request['workbenchToken']
        if request.get('csrfToken'):headers['X-CSRF-Token']=request['csrfToken']
        if request.get('idempotencyKey'):headers['Idempotency-Key']=request['idempotencyKey']
        if request.get('materialName'):headers['X-Material-Name']=request['materialName']
        # Test cookies are held per opaque preview ticket; no browser/workbench cookie is forwarded.
        jar=d.ROOT/'preview-cookies';jar.mkdir(exist_ok=True);sid=hashlib.sha256(request.get('viewer','').encode()).hexdigest();file=jar/(bid+'-'+sid+'.json');cookies=json.loads(file.read_text()) if file.exists() else {}
        if cookies:headers['Cookie']='; '.join(k+'='+v for k,v in cookies.items())
        if t.get('origin'):headers['Origin']=t['origin']
        result=runtime_request(d,row,{**request,'path':path,'headers':headers},t)
        for cookie in result.pop('cookies',[]):
            pair=cookie.split(';')[0];k,_,v=pair.partition('=');cookies[k]=v
        file.write_text(json.dumps(cookies));file.chmod(0o600)
        if result.get('type','').startswith('text/html') and row.get('backend')=='workstation':
            result.update(workstation.metadata(d,row))
        elif result.get('type','').startswith('text/html'):
            metadata=subprocess.run(['docker','exec',row['container'],'python3','-c',"import pathlib; p=pathlib.Path('/data/preview-meta.json'); print(p.read_text() if p.exists() else '{}')"],capture_output=True,text=True,timeout=5)
            if metadata.returncode==0:
                meta=json.loads(metadata.stdout);result.update({k:meta[k] for k in ['storage','hint'] if k in meta})
        return result
