"""Windows native engine. Fixed process arguments, cached source, bounded output."""
import base64,hashlib,json,os,shutil,subprocess,time,threading
from pathlib import Path
from native_contract import validate,cache_key

def sha_file(p):
    with Path(p).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def execute(node,p,directory):
    validate(p)
    pending=node.native_prefetch.get(p['inputSha'])
    if pending is not None and not pending.done():return dict(cold=True,tool=p['tool'],seconds=0)
    lock=node.native_locks.setdefault(p['inputSha'],threading.Lock())
    with lock:
        sha=p['inputSha'];source=node.root/'native-cache'/(sha+'.input');mirror=node.root/'blobs'/sha[:2]/sha
        if not source.exists() and not mirror.exists() and p.get('inputSize',0)>2*1024**2:
            # Don't make the teacher wait for a large, cold source crossing the WAN.
            # Warm it in one bounded background lane; this invocation uses the primary.
            old=node.native_prefetch.get(sha)
            if old is None or old.done():node.native_prefetch[sha]=node.prefetch_pool.submit(_prefetch,node,p,lock)
            return dict(cold=True,tool=p['tool'],seconds=0)
        return _execute(node,p,directory)

def _prefetch(node,p,lock):
    with lock:_source(node,p)

def _source(node,p):
    data=node.root/'native-cache';data.mkdir(exist_ok=True);sha=p['inputSha'];source=data/(sha+'.input')
    if source.exists():return source
    mirror=node.root/'blobs'/sha[:2]/sha
    if mirror.exists() and sha_file(mirror)==sha:shutil.copyfile(mirror,source);return source
    temp=source.with_suffix('.partial');checksum=hashlib.sha256();size=0
    try:
        with node.request('/node/input?sha='+sha,timeout=120) as response,temp.open('wb') as output:
            while block:=response.read(256*1024):
                size+=len(block)
                if size>128*1024**2:raise ValueError('Source too large')
                output.write(block);checksum.update(block)
        if checksum.hexdigest()!=sha:raise ValueError('Source checksum')
        os.replace(temp,source);return source
    finally:temp.unlink(missing_ok=True)

def _execute(node,p,directory):
    opts=validate(p);tool=p['tool'];root=Path(node.c['nativeRoot']);library=root/'Library'/'bin'
    data=node.root/'native-cache';data.mkdir(exist_ok=True)
    sha=p['inputSha'];source=_source(node,p)
    if sha_file(source)!=sha:
        source.unlink(missing_ok=True);raise ValueError('Cached input checksum')
    os.utime(source,None)
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)|getattr(subprocess,'BELOW_NORMAL_PRIORITY_CLASS',0)
    env=os.environ.copy();env['PATH']=str(library)+os.pathsep+env.get('PATH','');env['OMP_THREAD_LIMIT']='2';env['OMP_NUM_THREADS']='2'
    env['SIYUE_OCR_FONT']=str(Path(node.c['ocrFont']));env['TESSDATA_PREFIX']=str(Path(node.c['tessdata']));env['FONTCONFIG_FILE']=str(Path(node.c['fontConfig']))
    engine=node.c['nativeFingerprint'];key=cache_key(p,engine);cached=data/(key+'.result')
    if cached.exists():
        result=json.loads(cached.read_text(encoding='utf8'));os.utime(cached,None);result['cacheHit']=True;return result
    start=time.monotonic();out=directory/'result'
    if tool=='rapid':
        from rapid_pool import recognize
        result=recognize(node,source,str(out)+'.tsv');Path(str(out)+'.json').write_text(json.dumps(result,ensure_ascii=False),encoding='utf8')
        outputs={'json':Path(str(out)+'.json'),'tsv':Path(str(out)+'.tsv')};run=None
    elif tool in ('pdftoppm','pdftotext'):
        target=str(out)+('.html' if '-bbox-layout' in opts else '.txt') if tool=='pdftotext' else str(out)
        cmd=[str(library/(tool+'.exe'))]+opts+[str(source),target]
        outputs={'png':Path(str(out)+'.png')} if tool=='pdftoppm' else {'html' if '-bbox-layout' in opts else 'txt':Path(target)}
    elif tool=='tesseract':
        cmd=[str(library/'tesseract.exe'),str(source),str(out)]+opts
        outputs={'tsv' if 'tsv' in opts else 'txt':Path(str(out)+('.tsv' if 'tsv' in opts else '.txt'))}
    if tool!='rapid':run=subprocess.run(cmd,cwd=directory,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60,creationflags=flags)
    if run is not None and run.returncode:raise ValueError('Native engine failed: '+run.stderr.decode(errors='replace')[-300:])
    result=dict(files={},tool=tool,engine=engine,seconds=round(time.monotonic()-start,4),cacheHit=False)
    for name,path in outputs.items():
        b=path.read_bytes()
        if len(b)>12*1024**2:raise ValueError('Output too large')
        result['files'][name]=dict(content=base64.b64encode(b).decode(),sha256=hashlib.sha256(b).hexdigest(),size=len(b))
    # Cache results in an atomic file; only pure transformations are eligible.
    tmp=cached.with_suffix('.tmp');tmp.write_text(json.dumps(result),encoding='utf8');os.replace(tmp,cached)
    # Only reconstructible native cache is reclaimed, never the replica directory.
    entries=[f for f in data.iterdir() if f.is_file() and f.suffix in ('.input','.result')]
    total=sum(f.stat().st_size for f in entries)
    for f in sorted(entries,key=lambda f:f.stat().st_mtime):
        if f==source or f==cached:continue
        if total<2*1024**3 and f.stat().st_mtime>time.time()-86400:continue
        try:
            if f.suffix=='.input' and f.stem in node.native_locks and node.native_locks[f.stem].locked():continue
            size=f.stat().st_size;f.unlink(missing_ok=True);total-=size
        except OSError:pass
    return result
