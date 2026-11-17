"""Primary-host acceptance: compare identical image-analysis inputs across Linux/Windows."""
import base64,hashlib,json,os,shutil,subprocess,time,urllib.request
from pathlib import Path
root=Path('/opt/siyue-auxiliary');c=json.loads((root/'config.json').read_text())
dir=Path('/opt/siyue-miniapp/data/worker-scratch/auxiliary-acceptance');dir.mkdir(exist_ok=True);os.chown(dir,1654,1654)
for name in ['pixels_dispatch.py','pdf_pixels.local.py','dispatch.json']:
 shutil.copy2(root/name,dir/name);os.chown(dir/name,1654,1654)
(dir/'dispatch.json').chmod(0o600)
worker='siyue-miniapp-worker-1';wd='/work/auxiliary-acceptance'
def docker(args): return subprocess.run(['docker','exec',worker]+args,check=True,capture_output=True,text=True).stdout
# Synthetic fractions, roots, exponents, subscript-like ink, a diagram crossing a cut.
code='''from PIL import Image,ImageDraw
im=Image.new('RGB',(1000,1400),'white');d=ImageDraw.Draw(im)
for y in (100,340,700,1100):
 d.text((60,y),'Example 1: x2 + x_i = sqrt(1/2)',fill='black');d.line((300,y+20,410,y+20),fill='black',width=3);d.text((330,y+2),'1+x',fill='black');d.text((340,y+25),'2',fill='black')
d.polygon([(600,300),(850,650),(550,650)],outline='black',width=3)
im.save('/work/auxiliary-acceptance/synthetic.png')
'''
docker(['python3','-c',code])
real=next(Path('/opt/siyue-miniapp/data/objects/handouts').glob('*/source.pdf'))
subprocess.run(['docker','exec',worker,'pdftoppm','-f','1','-l','1','-singlefile','-r','140','-png','/data/objects/'+str(real.relative_to('/opt/siyue-miniapp/data/objects')),wd+'/real'],check=True,capture_output=True)
results=[]
for image in ['synthetic.png','real.png']:
 width,height=map(int,docker(['python3','-c',f'from PIL import Image;im=Image.open("{wd}/{image}");print(im.width,im.height)']).split())
 cases=[('audit',dict(width=width,height=height,regions=[[0,0,width,height]],cuts=[])),('audit',dict(width=width,height=height,regions=[[0,0,width//2,height]],cuts=[[0,height*.4,width]])),('layout',dict(anchors=[dict(index=0,left=60,right=450,top=100,bottom=130),dict(index=1,left=60,right=450,top=700,bottom=730)],graphics=[],allowContinuation=False))]
 for i,(mode,plan) in enumerate(cases):
  inp=dir/(image+f'-{i}.json');inp.write_text(json.dumps(plan));os.chown(inp,1654,1654)
  local=dir/'local.json';remote=dir/'remote.json'
  docker(['python3',wd+'/pdf_pixels.local.py',mode,wd+'/'+image,wd+'/'+inp.name,wd+'/local.json'])
  payload=dict(mode=mode,scriptHash=c['scriptHash'])
  for name,path in [('image',dir/image),('data',inp)]:
   data=path.read_bytes();payload[name]=base64.b64encode(data).decode();payload[name+'Hash']=hashlib.sha256(data).hexdigest()
  # Wait for lane availability between samples.
  for _ in range(40):
   req=urllib.request.Request('http://127.0.0.1:5096/internal/status',headers={'Authorization':'Bearer '+c['dispatchToken']})
   with urllib.request.urlopen(req) as response: status=json.load(response)
   if status['ready']: break
   time.sleep(.5)
  assert status['ready'],status
  req=urllib.request.Request('http://127.0.0.1:5096/internal/pixels',data=json.dumps(payload).encode(),headers={'Authorization':'Bearer '+c['dispatchToken'],'Content-Type':'application/json'})
  started=time.monotonic()
  with urllib.request.urlopen(req,timeout=15) as response: actual=json.load(response)['result']
  expected=json.loads(local.read_text());assert actual==expected,(image,mode,actual,expected)
  results.append(dict(image=image,mode=mode,identical=True,seconds=round(time.monotonic()-started,2)))
  time.sleep(.5)
# Public route never exposes internal dispatch even with node auth; unauthenticated node API is denied.
for path,token in [('node/manifest','invalid'),('internal/status',c['nodeToken'])]:
 req=urllib.request.Request('https://www.siyuejn.com/miniapp/aux-node/'+path,headers={'Authorization':'Bearer '+token})
 try:urllib.request.urlopen(req);raise AssertionError('Unexpected access')
 except urllib.error.HTTPError as e:assert e.code in (401,404)
(root/'acceptance.json').write_text(json.dumps(dict(comparisons=results,unauthorizedDenied=True),indent=2));print(json.dumps(results,indent=2))
