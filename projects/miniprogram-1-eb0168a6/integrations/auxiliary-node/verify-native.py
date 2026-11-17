"""Primary-host native acceptance against original engines and real source lectures."""
import base64,hashlib,http.client,json,os,re,shutil,subprocess,time,urllib.request,xml.etree.ElementTree as ET
from pathlib import Path
from native_contract import VERSION
root=Path('/opt/siyue-auxiliary');cfg=json.loads((root/'config.json').read_text());work=Path('/opt/siyue-miniapp/data/worker-scratch/native-acceptance');work.mkdir(exist_ok=True);os.chown(work,1654,1654)
worker='siyue-miniapp-worker-1';wd='/work/native-acceptance'
def docker(args):return subprocess.run(['docker','exec',worker]+args,check=True,capture_output=True,text=True).stdout
def sql(text):return subprocess.run(['runuser','-u','postgres','--','psql','-d','siyue_miniapp','-At'],input=text,text=True,check=True,capture_output=True).stdout

def api(route,value=None):
 request=urllib.request.Request('http://127.0.0.1:5096'+route,data=None if value is None else json.dumps(value).encode(),headers={'Authorization':'Bearer '+cfg['dispatchToken'],'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=65) as f:return json.load(f)
def stage(path):
 with path.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
 try:api('/internal/input?sha='+sha)
 except urllib.error.HTTPError as e:
  assert e.code==404
  c=http.client.HTTPConnection('127.0.0.1',5096,timeout=30)
  with path.open('rb') as f:c.request('PUT','/internal/input?sha='+sha,body=f,headers={'Authorization':'Bearer '+cfg['dispatchToken'],'Content-Length':str(path.stat().st_size)})
  response=c.getresponse();assert response.status==200,response.read();response.read();c.close()
 return sha

def native(tool,path,opts):
 sha=stage(path);p=dict(mode='native',nativeVersion=VERSION,tool=tool,inputSha=sha,inputSize=path.stat().st_size,options=opts)
 for _ in range(25):
  start=time.monotonic()
  try:r=api('/internal/native',p)['result'];break
  except urllib.error.HTTPError as e:
   if e.code!=503:raise
   time.sleep(2)
 else:raise AssertionError('Native node stayed unavailable')
 out={k:base64.b64decode(v['content']) for k,v in r['files'].items()}
 for k,b in out.items():assert hashlib.sha256(b).hexdigest()==r['files'][k]['sha256']
 return out,r,time.monotonic()-start

def image_comparison(a,b):
 script='''from PIL import Image,ImageChops
import json
from pathlib import Path
a=Image.open("/work/native-acceptance/"+"'''+a+'''").convert('L');b=Image.open("/work/native-acceptance/"+"'''+b+'''").convert('L')
assert a.size==b.size,(a.size,b.size)
x=a.point(lambda v:255 if v<210 else 0,'1');y=b.point(lambda v:255 if v<210 else 0,'1')
inter=ImageChops.logical_and(x,y).histogram()[255];union=ImageChops.logical_or(x,y).histogram()[255]
print(json.dumps(dict(width=a.width,height=a.height,inkIoU=inter/max(1,union),leftBounds=x.getbbox(),rightBounds=y.getbbox())))
'''
 return json.loads(docker(['python3','-c',script]))
rows=sql('''SELECT DISTINCT "FileName" || E'\\t' || "SourcePdfKey" FROM "Handouts" WHERE "FileName" LIKE '%自招%' AND length("SourcePdfKey")>0 LIMIT 4;''').strip().splitlines()
fixtures=[]
for row in rows:
 title,key=row.split('\t',1);path=Path('/opt/siyue-miniapp/data/objects')/key
 if path.exists() and path.stat().st_size<100*1024**2 and key not in [x[1] for x in fixtures]:fixtures.append((title,key))
if not fixtures:
 for path in list(Path('/opt/siyue-miniapp/data/objects/handouts').glob('*/source.pdf'))[:2]:fixtures.append((path.parent.name,str(path.relative_to('/opt/siyue-miniapp/data/objects'))))
fixtures=fixtures[:2]
if (root/'native-fixtures.json').exists():fixtures=json.loads((root/'native-fixtures.json').read_text())
results=[]
for i,(title,key) in enumerate(fixtures):
 source=Path('/opt/siyue-miniapp/data/objects')/key;local=work/('source'+str(i)+'.pdf');shutil.copy2(source,local);os.chown(local,1654,1654)
 pages=int(re.search(r'Pages:\s*(\d+)',docker(['pdfinfo',wd+'/'+local.name])).group(1))
 for page in sorted(set([1,min(4,pages)])):
  opts=['-f',str(page),'-l',str(page),'-singlefile','-scale-to','1600','-png']
  left=f'left-{i}-{page}';start=time.monotonic();docker(['pdftoppm']+opts+[wd+'/'+local.name,wd+'/'+left]);local_time=time.monotonic()-start
  files,meta,elapsed=native('pdftoppm',local,opts);right=f'right-{i}-{page}.png';(work/right).write_bytes(files['png'])
  quality=image_comparison(left+'.png',right);assert quality['inkIoU']>.90,quality
  assert all(abs(a-b)<=3 for a,b in zip(quality['leftBounds'],quality['rightBounds'])),quality
  # Repeat hits primary cache; no second Windows render or source transfer.
  _,cached,repeat=native('pdftoppm',local,opts);assert cached['cacheHit']
  results.append(dict(title=title,page=page,quality=quality,localSeconds=round(local_time,3),remoteSeconds=round(elapsed,3),engineSeconds=meta['seconds'],repeatSeconds=round(repeat,3)))
  opts2=['-f',str(page),'-l',str(page),'-bbox-layout'];text=wd+f'/text-{i}-{page}.html';docker(['pdftotext']+opts2+[wd+'/'+local.name,text])
  files,_,_=native('pdftotext',local,opts2)
  original=ET.fromstring((work/Path(text).name).read_bytes());other=ET.fromstring(files['html'])
  a=[(x.text,x.attrib) for x in original.iter() if x.tag.endswith('}word')];b=[(x.text,x.attrib) for x in other.iter() if x.tag.endswith('}word')];assert a==b,(title,page,'word positions changed')
 # Test exact native crop coordinates at high DPI, including diagrams/formula boundaries.
 opts=['-f','1','-l','1','-singlefile','-r','180','-x','50','-y','160','-W','1300','-H','900','-png']
 docker(['pdftoppm']+opts+[wd+'/'+local.name,wd+f'/crop-left-{i}']);files,_,_=native('pdftoppm',local,opts);(work/f'crop-right-{i}.png').write_bytes(files['png'])
 q=image_comparison(f'crop-left-{i}.png',f'crop-right-{i}.png');assert q['inkIoU']>.90,q
 results.append(dict(title=title,crop=True,quality=q))
(root/'native-render-acceptance.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
# Compare both OCR engines on the SAME source raster; math remains source pixels.
image=work/'left-0-1.png'
for tool in (['rapid'] if os.environ.get('SKIP_TESSERACT')=='1' else ['tesseract','rapid']):
 if tool=='tesseract':
  opts=['-l','chi_sim+eng','--psm','3','tsv'];docker(['tesseract',wd+'/'+image.name,wd+'/ocr-local']+opts)
  original=(work/'ocr-local.tsv').read_text();files,meta,elapsed=native(tool,image,opts)
  def words(s):return [r.split('\t')[-1].strip() for r in s.splitlines() if r.startswith('5\t') and r.split('\t')[-1].strip()]
  a=words(original);b=words(files['tsv'].decode('utf8'))
 else:
  opts=[];docker(['/opt/ocr/bin/python','/app/pdf_pixels.py','rapid',wd+'/'+image.name,wd+'/rapid-local.tsv',wd+'/rapid-local.json'])
  original=json.loads((work/'rapid-local.json').read_text());files,meta,elapsed=native(tool,image,opts)
  a=[x['text'] for x in original['lines']];b=[x['text'] for x in json.loads(files['json'])['lines']]
 assert a==b,(tool,'OCR output changed',len(a),len(b))
 results.append(dict(tool=tool,matched=True,lines=len(a),remoteSeconds=round(elapsed,3),engineSeconds=meta['seconds']))
(root/'native-acceptance.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(json.dumps(results,ensure_ascii=False,indent=2))
