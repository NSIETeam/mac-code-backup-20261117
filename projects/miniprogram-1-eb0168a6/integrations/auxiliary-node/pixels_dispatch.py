#!/usr/bin/python3
"""Python interpreter shim; the current container script remains authoritative."""
import base64, hashlib, json, os, runpy, sys, urllib.request
from pathlib import Path
original=Path(sys.argv[1]); sys.argv=sys.argv[1:]
if original.name != 'pdf_pixels.py':
    os.execv('/usr/bin/python3',['python3']+sys.argv)
try:
    cfg=json.loads(Path(os.environ.get('SIYUE_AUX_CONFIG','/run/siyue-aux/dispatch.json')).read_text())
    mode,image,data,output=sys.argv[1:]
    # Small pixel checks are faster locally on an idle primary. Use the node to
    # absorb real CPU/memory pressure, rather than adding WAN latency to every page.
    if os.environ.get('SIYUE_AUX_FORCE') != '1':
        load=os.getloadavg()[0]
        counters=dict((x.split()[0],int(x.split()[1])) for x in Path('/proc/meminfo').read_text().splitlines() if len(x.split())>=2)
        pressure=load>=cfg.get('pressureLoad',1.5) or counters.get('MemAvailable:',0)<1536*1024
        if not pressure: raise ValueError('local-is-faster')
    if mode not in ('audit','layout','ocr'): raise ValueError('local-mode')
    # Avoid huge uploads or any caller-controlled remote executable/script path.
    a=Path(image).read_bytes(); b=Path(data).read_bytes()
    if len(a)>24*1024**2 or len(b)>1024**2: raise ValueError('large-input')
    payload=dict(mode=mode,scriptHash=hashlib.sha256(original.read_bytes()).hexdigest(),image=base64.b64encode(a).decode(),data=base64.b64encode(b).decode(),imageHash=hashlib.sha256(a).hexdigest(),dataHash=hashlib.sha256(b).hexdigest())
    request=urllib.request.Request(cfg['url']+'/internal/pixels',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+cfg['token']})
    with urllib.request.urlopen(request,timeout=12) as response: result=json.load(response)['result']
    if mode=='audit' and not all(k in result for k in ('missingPixels','missingBounds','cutInk')): raise ValueError('invalid-audit')
    if mode=='layout' and not all(k in result for k in ('regions','columns','missingPixels','error')): raise ValueError('invalid-layout')
    if mode=='ocr' and not all(k in result for k in ('width','height','lines','gaps')): raise ValueError('invalid-ocr')
    Path(output).write_text(json.dumps(result,ensure_ascii=False),encoding='utf8')
except Exception:
    runpy.run_path(str(original),run_name='__main__')
