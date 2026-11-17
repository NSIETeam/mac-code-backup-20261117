import json,hashlib,zipfile,io,subprocess,tempfile
import xml.etree.ElementTree as E
from PIL import Image,ImageOps,ImageChops
from pathlib import Path
base=Path('/data/objects'); book='9e895b0b-96e8-431c-a3cb-8b14afdfca35'
s=json.loads((base/f'books/{book}/page-preview.json').read_text())
def fingerprint(im):
 im=im.convert('RGBA'); bg=Image.new('RGBA',im.size,'white');bg.alpha_composite(im)
 gray=bg.convert('L')
 box=gray.point(lambda v:255 if v<215 else 0).getbbox()
 if not box:return None
 crop=gray.crop(box)
 return (crop.width/crop.height,list(crop.resize((24,24),Image.Resampling.LANCZOS).getdata()))

cached=[]
with zipfile.ZipFile(base/s['originalKey']) as z:
 rels={r.get('Id'):r.get('Target') for r in E.fromstring(z.read('word/_rels/document.xml.rels'))}
 for el in E.fromstring(z.read('word/document.xml')).iter():
  attrs={k.split('}')[-1]:v for k,v in el.attrib.items()}
  if 'link' in attrs and 'embed' in attrs:
   cached.append(fingerprint(Image.open(io.BytesIO(z.read('word/'+rels[attrs['embed']])))))
found=[]
with tempfile.TemporaryDirectory() as d:
 cmds=['pdfimages']
 cli=subprocess.check_output(cmds+['-list',str(base/s['pdfKey'])],text=True)
 types=[row.split()[2] for row in cli.splitlines()[2:]]
 subprocess.run(cmds+['-png',str(base/s['pdfKey']),d+'/i'],check=True,stderr=subprocess.DEVNULL)
 for i,t in enumerate(types):
  if t!='image':continue
  f=Path(d)/('i-%03d.png'%i)
  if not f.is_file():continue
  with Image.open(f) as raw:
   img=raw.convert('RGBA')
   if types[i+1:i+2] in (['smask'],['mask']):
    with Image.open(Path(d)/('i-%03d.png'%(i+1))) as mask:
     if img.size==mask.size:img.putalpha(mask.convert('L'))
   v=fingerprint(img)
   if v:found.append(v)
scores=[]
for target in cached:
 if not target:continue
 ratio,stamp=target
 scores.append(min((sum(abs(l-r) for l,r in zip(stamp,v))/len(stamp) for k,v in found if 0.92 < k/ratio < 1.08),default=255))
print(json.dumps(dict(references=len(cached),nonblank=len(scores),close=sum(x<=9 for x in scores),maxError=max(scores))))
