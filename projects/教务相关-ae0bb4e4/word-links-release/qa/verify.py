import json,hashlib,subprocess
from pathlib import Path
base=Path('/opt/siyue-miniapp/data/objects')
book='9e895b0b-96e8-431c-a3cb-8b14afdfca35'
s=json.loads((base/f'books/{book}/page-preview.json').read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(base/s['originalKey'])==s['originalHash']=='3a4003f25a471fd177ff4296fae6aa0572209ca684bfbffc69c7d61acfbf0004'
assert sha(base/s['pdfKey'])==s['pdfHash']
worker=['docker','exec','siyue-miniapp-worker-1']
pdf='/data/objects/'+s['pdfKey']
info=subprocess.check_output(worker+['pdfinfo',pdf],text=True)
import re
pages=int(re.search(r'^Pages:\s+(\d+)',info,re.M)[1]);assert pages==s['pageCount']
images=subprocess.check_output(worker+['pdfimages','-list',pdf],text=True).splitlines()[2:]
report=[]
for page in [1,(pages+1)//2,pages]:
 ppm=subprocess.check_output(worker+['pdftoppm','-f',str(page),'-l',str(page),'-singlefile','-scale-to','480','-gray',pdf])
 assert ppm.startswith(b'P5') and sum(v<160 for v in ppm.split(b'\n',3)[-1])>300
 report.append(page)
print(json.dumps(dict(pages=pages,images=len(images),checkedPages=report,originalVerified=True)))
