import json,hashlib,zipfile,io,subprocess,tempfile
import xml.etree.ElementTree as E
from PIL import Image
from pathlib import Path
base=Path('/data/objects'); book='9e895b0b-96e8-431c-a3cb-8b14afdfca35'
s=json.loads((base/f'books/{book}/page-preview.json').read_text())
def fingerprint(im):
 im=im.convert('RGBA'); bg=Image.new('RGBA',im.size,'white');bg.alpha_composite(im)
 return hashlib.sha256(bg.convert('RGB').tobytes()).digest()
cached=[]
with zipfile.ZipFile(base/s['originalKey']) as z:
 rels={r.get('Id'):r.get('Target') for r in E.fromstring(z.read('word/_rels/document.xml.rels'))}
 for el in E.fromstring(z.read('word/document.xml')).iter():
  attrs={k.split('}')[-1]:v for k,v in el.attrib.items()}
  if 'link' in attrs and 'embed' in attrs:
   cached.append(fingerprint(Image.open(io.BytesIO(z.read('word/'+rels[attrs['embed']])))))
found=set()
with tempfile.TemporaryDirectory() as d:
 subprocess.run(['pdfimages','-png',str(base/s['pdfKey']),d+'/i'],check=True,stderr=subprocess.DEVNULL)
 for f in Path(d).glob('*.png'):
  with Image.open(f) as im:found.add(fingerprint(im))
print(json.dumps(dict(linked=len(cached),pixelExact=sum(x in found for x in cached),distinct=len(set(cached)))))
