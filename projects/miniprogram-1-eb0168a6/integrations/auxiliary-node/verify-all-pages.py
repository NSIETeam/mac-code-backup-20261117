"""Whole-file geometry/content regression for the user's two original PDFs."""
import base64,hashlib,json,re,subprocess,time,urllib.request,urllib.error,xml.etree.ElementTree as ET
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
root=Path('/opt/siyue-auxiliary');work=Path('/opt/siyue-miniapp/data/worker-scratch/native-acceptance');c=json.loads((root/'config.json').read_text());wd='/work/native-acceptance'
def run(args):return subprocess.run(['docker','exec','siyue-miniapp-worker-1']+args,check=True,capture_output=True,text=True).stdout
def native(tool,sha,size,opts):
 payload=dict(mode='native',nativeVersion='native-v2',inputSha=sha,inputSize=size,tool=tool,options=opts)
 for _ in range(80):
  req=urllib.request.Request('http://127.0.0.1:5096/internal/native',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+c['dispatchToken'],'Content-Type':'application/json'})
  try:
   with urllib.request.urlopen(req,timeout=65) as r:return json.load(r)['result']
  except urllib.error.HTTPError as e:
   if e.code!=503:raise
   time.sleep(.25)
 raise AssertionError('Node unavailable during whole-file acceptance')
def verify(i):
 source=work/f'source{i}.pdf';sha=hashlib.sha256(source.read_bytes()).hexdigest();size=source.stat().st_size
 pages=int(re.search(r'Pages:\s*(\d+)',run(['pdfinfo',wd+'/'+source.name])).group(1));started=time.monotonic()
 for page in range(1,pages+1):
  name=f'whole-{i}';opts=['-f',str(page),'-l',str(page),'-singlefile','-scale-to','1600','-png']
  run(['nice','-n','15','/usr/bin/pdftoppm']+opts+[wd+'/'+source.name,wd+'/'+name+'-local'])
  result=native('pdftoppm',sha,size,opts);(work/(name+'-native.png')).write_bytes(base64.b64decode(result['files']['png']['content']))
  run(['python3','-c',f"from PIL import Image,ImageChops;a=Image.open('{wd}/{name}-local.png').convert('RGB');b=Image.open('{wd}/{name}-native.png').convert('RGB');assert a.size==b.size and ImageChops.difference(a,b).getbbox() is None,'Pixel difference: document {i} page {page}'"])
  opts=['-f',str(page),'-l',str(page),'-bbox-layout'];run(['/usr/bin/pdftotext']+opts+[wd+'/'+source.name,wd+'/'+name+'.html']);result=native('pdftotext',sha,size,opts)
  left=ET.fromstring((work/(name+'.html')).read_bytes());right=ET.fromstring(base64.b64decode(result['files']['html']['content']))
  words=lambda x:[(e.text,e.attrib) for e in x.iter() if e.tag.endswith('}word')]
  assert words(left)==words(right),(i,page,'Text/coordinate mismatch')
  if page%10==0:print(json.dumps(dict(document=i,pagesVerified=page,total=pages)),flush=True)
 report=dict(document=i,pages=pages,pixelsIdentical=True,wordsAndCoordinatesIdentical=True,seconds=round(time.monotonic()-started,2));return report
with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(verify,[0,1]))
(root/'whole-pdf-acceptance.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
