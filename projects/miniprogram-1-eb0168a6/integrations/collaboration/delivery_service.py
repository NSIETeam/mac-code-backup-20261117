#!/usr/bin/env python3
"""Trusted localhost controller. Repository code runs only as an isolated rootless user.

Configuration and production targets are root-owned and never loaded from a repository.
Requests contain opaque target keys, commit hashes and immutable artifact digests only.
"""
import sys
import delivery_recipes as recipes
import workstation_build
import base64, concurrent.futures, hashlib, hmac, http.server, io, json, mimetypes, os, pathlib, re, shutil, signal, sqlite3, subprocess, tarfile, tempfile, threading, time, urllib.request, urllib.error, uuid

ROOT=pathlib.Path(os.environ.get('SIYUE_DELIVERY_ROOT','/opt/siyue-collaboration'))
CONFIG=pathlib.Path(os.environ.get('SIYUE_DELIVERY_CONFIG',str(ROOT/'delivery.json')))
LOCK=threading.RLock()
POOL=concurrent.futures.ThreadPoolExecutor(max_workers=1)
ACTIVE=set()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

LOCAL_HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())

def settings():
    s=CONFIG.stat()
    if s.st_mode & 0o022: raise ValueError('controller configuration must not be writable by group or others')
    return json.loads(CONFIG.read_text())

class Database(sqlite3.Connection):
    def __exit__(self,*args):
        try:return super().__exit__(*args)
        finally:self.close()

def db():
    con=sqlite3.connect(ROOT/'delivery.db',timeout=15,factory=Database)
    con.execute('PRAGMA journal_mode=WAL')
    con.execute('CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, kind TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL)')
    return con

def update(job,state,**values):
    with LOCK,db() as c:
        row=c.execute('SELECT result FROM jobs WHERE id=?',(job,)).fetchone()
        result=json.loads(row[0]) if row else {}
        result.update(state=state,**values)
        c.execute('UPDATE jobs SET state=?,result=? WHERE id=?',(state,json.dumps(result,ensure_ascii=False),job))

def run(args,timeout=900,log_path=None,**kwargs):
    with tempfile.TemporaryFile() as output:
        p=subprocess.Popen(args,stdout=output,stderr=subprocess.STDOUT,start_new_session=True,**kwargs);start=time.monotonic()
        while p.poll() is None:
            if time.monotonic()-start>timeout or os.fstat(output.fileno()).st_size>4*1024*1024:
                os.killpg(p.pid,signal.SIGKILL);p.wait();raise RuntimeError('execution exceeded time/output quota')
            time.sleep(.2)
        output.seek(0);data=output.read(4*1024*1024)
        if log_path:log_path.parent.mkdir(exist_ok=True);log_path.write_bytes(data)
        if p.returncode:raise RuntimeError('command failed: '+data.decode(errors='replace')[-2500:])
        return data

def validate_key(value):
    if not isinstance(value,str) or not re.fullmatch('[a-z][a-z0-9-]{2,47}',value):raise ValueError('invalid deployment key')
    return value

def validate_id(value):return str(uuid.UUID(value))
def sha(value):
    if not isinstance(value,str) or not re.fullmatch('[0-9a-f]{40,64}',value):raise ValueError('invalid commit/digest')
    return value

def file_digest(path):
    with path.open('rb') as file:return hashlib.file_digest(file,'sha256').hexdigest()

def artifact_output(source,relative):
    out=source/relative
    if out.is_symlink() or source.resolve() not in out.resolve().parents:raise ValueError('output must stay inside the build workspace')
    return out

def target(request):
    key=validate_key(request['key']);cfg=settings();t=cfg['projects'].get(key)
    if not t:raise ValueError('管理员尚未配置此项目的构建和部署环境')
    return cfg,t,key

def unpack(data,destination):
    """Reject links, devices, traversal, excessive files and decompression bombs."""
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:*') as tar:
        files=tar.getmembers();total=0
        if len(files)>15000:raise ValueError('too many source files')
        for item in files:
            parts=pathlib.PurePosixPath(item.name).parts
            if item.name.startswith('/') or '..' in parts or item.issym() or item.islnk() or not(item.isdir() or item.isfile()):raise ValueError('unsafe archive entry')
            total+=item.size
            if total>150*1024*1024:raise ValueError('source archive too large')
        tar.extractall(destination,filter='data')

def builder(args,cfg):
    uid=cfg['builder_uid'];return ['runuser','-u',cfg['builder_user'],'--','env',f'XDG_RUNTIME_DIR=/run/user/{uid}',f'DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{uid}/bus','podman',*args]

def memory_available():
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):return int(line.split()[1])*1024
    return 0

def do_build(job,request):
    cfg,t,key=target(request)
    if workstation_build.build(sys.modules[__name__],cfg,t,key,job,request):return
    recipes.reap(sys.modules[__name__])
    if memory_available()<cfg.get('minimum_free_mb',900)*1024*1024:
        update(job,'queued',summary='服务器可用内存不足，等待资源后继续');return
    if shutil.disk_usage(ROOT).free<5*1024**3 or sum(p.stat().st_size for p in (ROOT/'artifacts').glob('*.tar'))>10*1024**3:
        update(job,'queued',summary='构建存储达到保留限额，等待管理员清理历史产物或扩容');return
    if shutil.disk_usage('/var/lib/siyue-build').free<1024**3:
        update(job,'queued',summary='构建工作区可用空间不足，等待管理员清理失败任务或镜像缓存');return
    commit=sha(request['sha']);workspace=pathlib.Path('/var/lib/siyue-build/jobs')/job
    workspace.parent.mkdir(parents=True,exist_ok=True);os.chown(workspace.parent,cfg['builder_uid'],cfg['builder_gid']);workspace.parent.chmod(0o700)
    update(job,'running',summary='正在隔离环境中检查代码')
    if workspace.exists():shutil.rmtree(workspace)
    workspace.mkdir(parents=True)
    src=recipes.assemble(sys.modules[__name__],cfg,t,commit,workspace)
    recipe_dir,recipe_version=recipes.snapshot(cfg)
    cache_key=None
    if (src/'__siyue_sources.json').exists():
        cache_cfg={k:t.get(k) for k in ('check_image','check_command','output','mode')}
        cache_key=hashlib.sha256((src/'__siyue_sources.json').read_bytes()+json.dumps(cache_cfg,sort_keys=True).encode()+recipe_version.encode()).hexdigest()
        cache=ROOT/'build-cache'/(cache_key+'.json')
        if cache.exists():
            cached=json.loads(cache.read_text());old=ROOT/'artifacts'/cached['file']
            if old.is_file() and file_digest(old)==cached['digest']:
                artifact=ROOT/'artifacts'/(job+'.tar');shutil.copyfile(old,artifact);artifact.chmod(0o440)
                update(job,'succeeded',digest=cached['digest'],sha=commit,summary='完整源码组合与已通过的检查一致，复用已验证产物');shutil.rmtree(workspace);return
    for path in [workspace,*workspace.rglob('*')]:os.chown(path,cfg['builder_uid'],cfg['builder_gid'])
    image=t['check_image'];command=t['check_command']
    if not isinstance(command,list) or not all(isinstance(a,str) for a in command):raise ValueError('invalid trusted check command')
    container='siyue-check-'+job
    args=['run','--name',container,'--rm','--pull=never','--network=none','--env','SIYUE_BUILD_SANDBOX=1','--env','NODE_PATH=/siyue-ci/node_modules','--read-only','--userns=keep-id','--cap-drop=ALL','--security-opt=no-new-privileges','--memory='+t.get('build_memory','640m'),'--cpus=1','--pids-limit=256','--tmpfs','/tmp:rw,size=128m', '-v',str(src)+':/workspace:rw','-v',str(recipe_dir)+':/siyue-ci:ro','-w','/workspace',image,*command]
    try: output=run(builder(args,cfg),timeout=t.get('timeout',300),log_path=ROOT/'logs'/(job+'.log'))
    finally:
        subprocess.run(builder(['rm','-f',container],cfg),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20)
    logdir=ROOT/'logs';logdir.mkdir(exist_ok=True);(logdir/(job+'.log')).write_bytes(output[-512*1024:])
    artifacts=ROOT/'artifacts';artifacts.mkdir(exist_ok=True);artifact=artifacts/(job+'.tar')
    if t.get('mode','static') in ('static','package'):
        out=artifact_output(src,t.get('output','dist'))
        if not out.is_dir() or (t.get('mode','static')=='static' and not (out/'index.html').is_file()):raise ValueError('missing build output')
        if (src/'__siyue_sources.json').exists():shutil.copyfile(src/'__siyue_sources.json',out/'__siyue_sources.json')
        files=list(out.rglob('*'));total=0
        with tarfile.open(artifact,'w') as tar:
            for f in sorted(files):
                if f.is_symlink() or not(f.is_file() or f.is_dir()):raise ValueError('unsafe artifact file')
                if f.is_file():
                    total+=f.stat().st_size
                    if total>150*1024*1024:raise ValueError('artifact exceeds quota')
                    tar.add(f,arcname=str(f.relative_to(out)),recursive=False)
    else:
        # No Docker socket is exposed. Even image construction runs as the rootless build identity.
        tag='localhost/siyue-build:'+job
        run(builder(['build','--network=none','--pull=never','--memory=384m','--cpu-period=100000','--cpu-quota=50000','--cap-drop=ALL','--no-cache','-t',tag,'-f',str(src/'Containerfile'),str(src)],cfg),timeout=600)
        temp=workspace/'image.tar';run(builder(['save','--format','docker-archive','-o',str(temp),tag],cfg),timeout=120)
        if temp.stat().st_size>1024*1024*1024:raise ValueError('image exceeds quota')
        shutil.copyfile(temp,artifact);run(builder(['rmi',tag],cfg),timeout=30)
    digest=file_digest(artifact)
    artifact.chmod(0o440)
    if cache_key:
        (ROOT/'build-cache').mkdir(exist_ok=True);(ROOT/'build-cache'/(cache_key+'.json')).write_text(json.dumps({'file':artifact.name,'digest':digest}))
    update(job,'succeeded',digest=digest,sha=commit,summary='检查通过，构建产物已固定')
    shutil.rmtree(workspace)

def completed_build(build_id,key,digest):
    with db() as c:row=c.execute('SELECT request,state,result FROM jobs WHERE id=? AND kind=?',(validate_id(build_id),'build')).fetchone()
    if not row or row[1]!='succeeded':raise ValueError('build has not succeeded')
    request=json.loads(row[0]);result=json.loads(row[2])
    if request['key']!=key or not hmac.compare_digest(result['digest'],sha(digest)):raise ValueError('artifact identity mismatch')
    artifact=ROOT/'artifacts'/(build_id+'.tar')
    if not hmac.compare_digest(file_digest(artifact),digest):raise ValueError('artifact checksum mismatch')
    return artifact,result

def atomic_link(destination,path):
    tmp=path.with_name(path.name+'.new-'+uuid.uuid4().hex);tmp.symlink_to(destination,target_is_directory=True);tmp.replace(path)

def deploy_static(job,request,production):
    cfg,t,key=target(request);build_id=validate_id(request.get('buildId',request['id']));artifact,build=completed_build(build_id,key,request['digest'])
    versions=ROOT/'sites'/key/'versions';versions.mkdir(parents=True,exist_ok=True);release=versions/build_id
    for parent in [ROOT/'sites',ROOT/'sites'/key,versions]:parent.chmod(0o755)
    if not release.exists():
        temp=versions/(build_id+'.tmp');temp.mkdir(exist_ok=True);unpack(artifact.read_bytes(),temp)
        if not (temp/'index.html').is_file():raise ValueError('index missing')
        (temp/'__siyue_version.json').write_text(json.dumps({'buildId':build_id,'sha':build['sha'],'digest':request['digest']}))
        for p in [temp,*temp.rglob('*')]:p.chmod(0o755 if p.is_dir() else 0o644)
        temp.rename(release)
    current=ROOT/'sites'/key/('production' if production else 'testing');old=current.resolve() if current.is_symlink() else None
    backup=ROOT/'backups';backup.mkdir(exist_ok=True);(backup/(job+'.json')).write_text(json.dumps({'key':key,'previous':str(old) if old else None,'next':str(release),'production':production}))
    try:
        if production or request.get('kind','main')=='main':atomic_link(release,current)
        if production:
            # The URL is trusted configuration; repository content cannot choose the target.
            health=t.get('production_health_url')
            if not health:raise ValueError('production health URL is required')
            with urllib.request.urlopen(health,timeout=15) as response:observed=json.load(response)
            if observed.get('digest')!=request['digest']:raise ValueError('production version verification failed')
        else:
            observed=json.loads((release/'__siyue_version.json').read_text())
            if observed['digest']!=request['digest']:raise ValueError('test version verification failed')
    except Exception:
        if old:atomic_link(old,current)
        elif current.is_symlink():current.unlink()
        raise
    update(job,'succeeded',summary=('正式版本已切换并核验；上一版本已保留' if production else '测试环境已更新'),digest=request['digest'],sha=build['sha'])

def deploy_container(job,request,production):
    cfg,t,key=target(request);build_id=validate_id(request.get('buildId',request['id']));artifact,build=completed_build(build_id,key,request['digest'])
    with tarfile.open(artifact) as tar:
        manifest=json.load(tar.extractfile('manifest.json'));config_name=manifest[0]['Config'];image_id='sha256:'+pathlib.PurePosixPath(config_name).name.removesuffix('.json')
    if not re.fullmatch('sha256:[a-f0-9]{64}',image_id):raise ValueError('invalid image identity')
    run(['docker','load','-i',str(artifact)],timeout=120)
    env='production' if production else 'testing';envcfg=t[env];network='siyue-isolated-'+key+'-'+env
    if subprocess.run(['docker','network','inspect',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:run(['docker','network','create','--internal',network])
    network_id=json.loads(run(['docker','network','inspect',network]))[0]['Id'];bridge='br-'+network_id[:12]
    rule=['INPUT','-i',bridge,'-m','conntrack','--ctstate','NEW','-j','DROP']
    if subprocess.run(['iptables','-C',*rule],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:run(['iptables','-I',*rule])
    name='siyue-project-'+key+'-'+env+'-'+job[:8];port=int(envcfg['port'])
    data=ROOT/'data'/key/env;data.mkdir(parents=True,exist_ok=True);disk=data.parent/(env+'.img')
    if not disk.exists():
        run(['truncate','-s',str(t.get('data_quota_mb',256))+'M',str(disk)]);run(['mkfs.ext4','-F',str(disk)])
    if not os.path.ismount(data):run(['mount','-o','loop,nosuid,nodev',str(disk),str(data)])
    os.chown(data,int(t.get('runtime_uid',1000)),int(t.get('runtime_uid',1000)))
    # Each environment has its own data volume (including any embedded database), never a production host mount.
    args=['docker','run','-d','--name',name,'--user',str(t.get('runtime_uid',1000)),'--label','siyue.project='+key,'--network',network,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--memory=256m','--cpus=0.5','--pids-limit=128','--tmpfs','/tmp:rw,size=32m','-v',str(data)+':/data','-p',f'127.0.0.1:{port}:{int(t.get("container_port",8080))}']
    for k,v in envcfg.get('environment',{}).items():args.extend(['-e',k+'='+str(v)])
    previous=ROOT/'runtime'/key/(env+'.json');previous.parent.mkdir(parents=True,exist_ok=True);old=json.loads(previous.read_text()) if previous.exists() else None
    if old:run(['docker','stop',old['container']],timeout=40)
    try:
        run(args+[image_id],timeout=60)
        healthy=False
        for _ in range(20):
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}'+t.get('health_path','/health'),timeout=2) as response:healthy=response.status==200
                if healthy:break
            except (OSError,urllib.error.URLError):pass
            time.sleep(.5)
        if not healthy:raise ValueError('new container health check failed')
        data={'container':name,'image':image_id,'digest':request['digest'],'buildId':build_id,'previous':old}
        temp=previous.with_suffix('.tmp');temp.write_text(json.dumps(data));temp.replace(previous)
    except Exception:
        subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if old:run(['docker','start',old['container']],timeout=30)
        raise
    update(job,'succeeded',summary='隔离容器健康检查通过，版本已切换',digest=request['digest'],sha=build['sha'])

def execute(job,kind,request):
    try:
        if kind=='build':do_build(job,request)
        else:
            _,t,_=target(request)
            if t.get('mode')=='package':recipes.package(sys.modules[__name__],job,request,kind=='publish')
            elif t.get('mode','static')=='static':deploy_static(job,request,kind=='publish')
            else:deploy_container(job,request,kind=='publish')
    except Exception as e:
        if kind=='test' and any(x in str(e) for x in ['正在使用','等待资源']):
            update(job,'queued',summary=str(e));return
        (ROOT/'logs').mkdir(exist_ok=True)
        (ROOT/'logs'/(job+'.error')).write_text(str(e))
        summary=('代码检查失败：'+str(e)[-1800:]) if kind=='build' else '部署失败，已保留或恢复上一版本；请管理员查看服务日志'
        update(job,'failed',summary=summary)
    finally:
        with LOCK:ACTIVE.discard(job)

def enqueue(kind,request):
    cfg,t,key=target(request);raw=validate_id(request['id']);job=('test-'+raw) if kind=='test' else raw
    if kind=='build':sha(request['sha'])
    else:sha(request['digest']);validate_id(request.get('buildId',raw))
    packed=json.dumps(request,sort_keys=True)
    with LOCK,db() as c:
        old=c.execute('SELECT kind,request,result FROM jobs WHERE id=?',(job,)).fetchone()
        if old:
            if old[0]!=kind or old[1]!=packed:raise ValueError('idempotency conflict')
            result=json.loads(old[2])
        else:
            result={'state':'queued','summary':'已进入单并发队列'};c.execute('INSERT INTO jobs VALUES(?,?,?,?,?)',(job,kind,packed,'queued',json.dumps(result)));c.commit()
        if result['state'] in ('queued','running') and job not in ACTIVE:
            ACTIVE.add(job);POOL.submit(execute,job,kind,request)
        return result

def preview(request):
    cfg,t,key=target(request);path=request.get('path','index.html') or 'index.html'
    if path.startswith('/') or '..' in pathlib.PurePosixPath(path.split('?')[0]).parts or '\\' in path:raise ValueError('invalid preview path')
    if t.get('mode')=='package':return recipes.preview(sys.modules[__name__],request,t,key)
    if t.get('mode','static')=='static':
        if request.get('buildId'):
            bid=validate_id(request['buildId']);completed_build(bid,key,request['digest']);root=(ROOT/'sites'/key/'versions'/bid).resolve()
        else:root=(ROOT/'sites'/key/'testing').resolve()
        file=(root/path.split('?')[0]).resolve()
        if root not in file.parents:raise ValueError('invalid preview path')
        if file.is_dir():file=file/'index.html'
        if not file.is_file():return {'status':404,'body':'','type':'text/plain'}
        if file.stat().st_size>8*1024*1024:raise ValueError('preview response exceeds quota')
        return {'status':200,'body':base64.b64encode(file.read_bytes()).decode(),'type':mimetypes.guess_type(file.name)[0] or 'application/octet-stream'}
    method=request.get('method','GET')
    if method not in ('GET','POST','PUT','PATCH','DELETE','OPTIONS'):raise ValueError('unsupported method')
    body=base64.b64decode(request.get('body',''));headers={'Content-Type':request.get('type','application/json'),'X-Forwarded-Prefix':request.get('prefix','')}
    req=urllib.request.Request(f'http://127.0.0.1:{int(t["testing"]["port"])}/'+path,data=body if body else None,method=method,headers=headers)
    try:
        with LOCAL_HTTP.open(req,timeout=15) as response:return {'status':response.status,'body':base64.b64encode(response.read(8*1024*1024)).decode(),'type':response.headers.get_content_type()}
    except urllib.error.HTTPError as error:return {'status':error.code,'body':base64.b64encode(error.read(1024*1024)).decode(),'type':'text/plain'}

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        try:
            length=int(self.headers.get('Content-Length','0'))
            if length>6*1024*1024:raise ValueError('request too large')
            body=self.rfile.read(length);stamp=self.headers.get('X-Siyue-Time','');signature=self.headers.get('X-Siyue-Signature','')
            expected=hmac.new(settings()['secret'].encode(),stamp.encode()+b'\n'+self.path.encode()+b'\n'+body,hashlib.sha256).hexdigest()
            if abs(time.time()-int(stamp))>60 or not hmac.compare_digest(signature,expected):self.send_error(403);return
            payload=json.loads(body)
            if self.path=='/retry':
                _,_,key=target(payload);bid=validate_id(payload['id']);kind=payload.get('kind','build')
                if kind not in ('build','test'):raise ValueError('only failed checks/test deployments may be retried')
                job=('test-'+bid) if kind=='test' else bid
                with LOCK,db() as con:
                    row=con.execute('SELECT request,state FROM jobs WHERE id=? AND kind=?',(job,kind)).fetchone()
                    if not row or json.loads(row[0])['key']!=key or row[1]!='failed' or job in ACTIVE:raise ValueError('job is not a failed job in this repository')
                    if kind=='build':workstation_build.retry(sys.modules[__name__],job,key)
                    for suffix in ['.log','.error']:
                        file=ROOT/'logs'/(job+suffix)
                        if file.exists():file.rename(file.with_name(file.name+'.attempt-'+str(time.time_ns())))
                    con.execute('UPDATE jobs SET state="queued",result=? WHERE id=?',(json.dumps({'state':'queued','summary':'已重新排队，等待隔离检查'}),job))
                result={'state':'queued'}
            elif self.path=='/logs':
                _,_,key=target(payload);bid=validate_id(payload['id'])
                with db() as con:row=con.execute('SELECT request FROM jobs WHERE id=? AND kind="build"',(bid,)).fetchone()
                if not row or json.loads(row[0])['key']!=key:raise ValueError('build log does not belong to repository')
                file=ROOT/'logs'/(bid+'.log');fallback=ROOT/'logs'/(bid+'.error');file=file if file.exists() else fallback
                data=file.read_bytes() if file.exists() else b'';result={'text':data[-262144:].decode(errors='replace'),'truncated':len(data)>262144}
            elif self.path=='/describe':
                _,t,_=target(payload);result={'entry':t.get('entry','index.html'),'notes':t.get('notes','隔离测试环境，数据不进入正式业务。')}
            elif self.path=='/preview':result=preview(payload)
            elif self.path in ('/build','/test','/publish'):result=enqueue(self.path[1:],payload)
            else:self.send_error(404);return
            out=json.dumps(result,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(out)));self.end_headers();self.wfile.write(out)
        except (ValueError,KeyError,TypeError):self.send_error(400)
        except Exception:self.send_error(500)

if __name__=='__main__':
    ROOT.mkdir(parents=True,exist_ok=True)
    with db():pass
    def recycle_idle_previews():
        while True:
            time.sleep(20)
            try:recipes.reap(sys.modules[__name__])
            except Exception:pass
    threading.Thread(target=recycle_idle_previews,daemon=True).start()
    class Server(http.server.ThreadingHTTPServer):
        request_queue_size=128
        daemon_threads=True
    Server(('127.0.0.1',5302),Handler).serve_forever()
