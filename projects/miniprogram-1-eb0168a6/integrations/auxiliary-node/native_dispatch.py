#!/usr/bin/python3
"""Native-tool entrypoint. Unsupported options and node failures execute the original tool."""
import base64,hashlib,http.client,json,os,struct,sys,uuid
from pathlib import Path
from urllib.parse import urlsplit
from native_contract import VERSION,options

tool=Path(sys.argv[0]).name
raw=sys.argv[1:];original={'pdftoppm':'/usr/bin/pdftoppm','pdftotext':'/usr/bin/pdftotext','tesseract':'/usr/bin/tesseract','rapid-python':'/opt/ocr/bin/python'}[tool]
try:
    if os.environ.get('SIYUE_AUX_NATIVE_LOCAL')=='1':raise ValueError('local-benchmark')
    cfg=json.loads(Path(os.environ.get('SIYUE_AUX_CONFIG','/run/siyue-aux/dispatch.json')).read_text())
    url=urlsplit(cfg['url']);headers={'Authorization':'Bearer '+cfg['token']}
    if tool=='rapid-python':
        script,mode,source,tsv,result=raw
        if mode!='rapid' or hashlib.sha256(Path(script).read_bytes()).hexdigest()!=cfg['scriptHash']:raise ValueError('Script mismatch')
        name='rapid';args=[];targets={'tsv':tsv,'json':result}
    elif tool=='tesseract':
        source,prefix=raw[:2];args=raw[2:];name=tool;targets={('tsv' if 'tsv' in args else 'txt'):prefix+('.tsv' if 'tsv' in args else '.txt')}
    else:
        source,prefix=raw[-2:];args=raw[:-2];name=tool
        targets={'png':prefix+'.png'} if tool=='pdftoppm' else {('html' if '-bbox-layout' in args else 'txt'):prefix}
    if name not in cfg.get('nativeTools',['pdftoppm','pdftotext','rapid']):raise ValueError('Tool retained on primary')
    if any(target=='-' for target in targets.values()):raise ValueError('Keep stdout semantics local')
    args=options(name,args);path=Path(source);size=path.stat().st_size
    if not 0<size<=128*1024**2:raise ValueError('Source limit')
    with path.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
    def request(method,route,body=None,extra=None,timeout=10):
        conn=http.client.HTTPConnection(url.hostname,url.port,timeout=timeout)
        conn.request(method,url.path+route,body=body,headers=dict(headers,**(extra or {})));response=conn.getresponse();data=response.read();conn.close()
        return response.status,data
    payload=dict(mode='native',nativeVersion=VERSION,inputSha=sha,inputSize=size,tool=name,options=args)
    code,body=request('POST','/internal/native',json.dumps(dict(payload,cacheOnly=True)).encode(),{'Content-Type':'application/json'})
    if code==404:
        if os.environ.get('SIYUE_AUX_NATIVE_FORCE')!='1' and sha not in cfg.get('forceSources',[]) and name not in cfg.get('nativeAlwaysTools',['rapid']):
            counters=dict((x.split()[0],int(x.split()[1])) for x in Path('/proc/meminfo').read_text().splitlines() if len(x.split())>=2)
            pressure=os.getloadavg()[0]>=cfg.get('pressureLoad',1.5) or counters.get('MemAvailable:',0)<1536*1024
            if not pressure:raise ValueError('Local execution has lower latency')
        code,_=request('GET','/internal/input?sha='+sha)
        if code!=200:
            _,state=request('GET','/internal/status')
            if not json.loads(state).get('nativeReady'):raise ValueError('Node unavailable')
            with path.open('rb') as f:code,_=request('PUT','/internal/input?sha='+sha,f,{'Content-Length':str(size),'Content-Type':'application/octet-stream'},timeout=30)
            if code!=200:raise ValueError('Staging failed')
        code,body=request('POST','/internal/native',json.dumps(payload).encode(),{'Content-Type':'application/json'},timeout=65)
    if code!=200:raise ValueError('Native task unavailable')
    result=json.loads(body)['result']
    if result.get('engine')!=cfg['nativeFingerprint'] or set(result['files'])!=set(targets):raise ValueError('Output contract')
    verified={}
    for kind,target in targets.items():
        f=result['files'][kind];b=base64.b64decode(f['content'],validate=True)
        if len(b)!=f['size'] or hashlib.sha256(b).hexdigest()!=f['sha256']:raise ValueError('Output checksum')
        if kind=='png':
            if b[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('Not PNG')
            w,h=struct.unpack('>II',b[16:24])
            if not 0<w*h<=45_000_000:raise ValueError('Image dimensions')
        verified[target]=b
    for target,b in verified.items():
        p=Path(target);tmp=p.with_name(p.name+'.aux-'+uuid.uuid4().hex)
        try:tmp.write_bytes(b);os.replace(tmp,p)
        finally:tmp.unlink(missing_ok=True)
except Exception:
    os.execv(original,[original]+raw)
