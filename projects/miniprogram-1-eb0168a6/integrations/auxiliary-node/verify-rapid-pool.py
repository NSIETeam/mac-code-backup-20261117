"""Measure distinct lossless encodings, so model reuse is not confused with result-cache hits."""
import base64,hashlib,http.client,json,subprocess,time,urllib.request,uuid
from pathlib import Path
r=Path('/opt/siyue-auxiliary');c=json.loads((r/'config.json').read_text());work=Path('/opt/siyue-miniapp/data/worker-scratch/native-acceptance');wd='/work/native-acceptance';report=[]
def run(cmd):return subprocess.run(['docker','exec','siyue-miniapp-worker-1']+cmd,check=True,capture_output=True,text=True)
def api(p):
 q=urllib.request.Request('http://127.0.0.1:5096/internal/native',data=json.dumps(p).encode(),headers={'Authorization':'Bearer '+c['dispatchToken'],'Content-Type':'application/json'})
 with urllib.request.urlopen(q,timeout=65) as f:return json.load(f)['result']
for i,image in enumerate(['left-0-1.png','left-1-1.png','left-0-1.png']):
 name=f'pool-{i}.png';nonce=uuid.uuid4().hex
 run(['python3','-c',f"from PIL import Image,PngImagePlugin;im=Image.open('{wd}/{image}');m=PngImagePlugin.PngInfo();m.add_text('AuxVerification','{nonce}');im.save('{wd}/{name}',pnginfo=m)"])
 start=time.monotonic();run(['/opt/ocr/bin/python','/app/pdf_pixels.py','rapid',wd+'/'+name,wd+'/pool-local.tsv',wd+'/pool-local.json']);local=time.monotonic()-start
 expected=json.loads((work/'pool-local.json').read_text())
 source=work/name;sha=hashlib.sha256(source.read_bytes()).hexdigest();conn=http.client.HTTPConnection('127.0.0.1',5096,timeout=30)
 with source.open('rb') as f:conn.request('PUT','/internal/input?sha='+sha,body=f,headers={'Authorization':'Bearer '+c['dispatchToken'],'Content-Length':str(source.stat().st_size)})
 response=conn.getresponse();assert response.status==200;response.read();conn.close()
 payload=dict(mode='native',nativeVersion='native-v2',tool='rapid',inputSha=sha,inputSize=source.stat().st_size,options=[])
 start=time.monotonic();result=api(payload);elapsed=time.monotonic()-start
 actual=json.loads(base64.b64decode(result['files']['json']['content']))
 assert [x['text'] for x in actual['lines']]==[x['text'] for x in expected['lines']]
 for a,b in zip(actual['lines'],expected['lines']):
  assert all(abs(a[k]-b[k])<=1 for k in ['left','right','top','bottom']),(a,b)
 assert not result['cacheHit'],'This must exercise actual OCR, not the result cache'
 report.append(dict(image=image,lines=len(actual['lines']),localSeconds=round(local,3),remoteSeconds=round(elapsed,3),nodeComputeSeconds=result['seconds'],textMatched=True,coordinatesMatched=True,resultCacheHit=False))
(r/'rapid-pool-acceptance.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
