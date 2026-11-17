import json,subprocess,zipfile,re,sys,xml.etree.ElementTree as ET
from pathlib import Path
import pdfplumber
from PIL import Image,ImageChops
root=Path(sys.argv[1] if len(sys.argv)>1 else '.local/acceptance/booklet-preview-20261023').resolve(); run=json.loads((root/'run.json').read_text()); base={x['name']:x for x in json.loads((root/'baseline-results.json').read_text())}; results=[]
ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main','m':'http://schemas.openxmlformats.org/officeDocument/2006/math','a':'http://schemas.openxmlformats.org/drawingml/2006/main','wp':'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'}
for name,case in run['cases'].items():
 folder=root/name; pdf=folder/'错题本.pdf'; errors=[]
 with pdfplumber.open(pdf) as doc:
  texts=[p.extract_text() or '' for p in doc.pages]; text='\n'.join(texts); images=[im for p in doc.pages for im in p.images]; pages=len(doc.pages)
  if text.count('日期')!=1:errors.append('日期不唯一')
  if len(doc.pages)!=case['previewPages']:errors.append('PDF/小程序页数不同')
  for i,p in enumerate(doc.pages):
   if case.get('studentName','预览验收·'+name) not in texts[i]:errors.append(f'第{i+1}页缺少学生名')
   for word in p.extract_words():
    if word['x0']<20 or word['x1']>p.width-20 or word['bottom']>p.height-20:errors.append(f'第{i+1}页文字越界')
   for im in p.images:
    if im['x0']<20 or im['x1']>p.width-20 or im['bottom']>p.height-20:errors.append(f'第{i+1}页图片越界')
    source_width,source_height=im['srcsize']
    if abs(im['width']/im['height']-source_width/source_height)>.01:errors.append(f'第{i+1}页图片比例失真')
  if name=='长题跨页':
   for i in range(1,71):
    if f'长题行{i:02d}' not in text:errors.append('长题缺行'+str(i))
   if '长题终' not in text:errors.append('长题末段丢失')
   if pages<2:errors.append('未覆盖续页')
   for i,t in enumerate(texts[1:],2):
    if '原题' not in t or '例1.' not in re.sub(r'\s','',t):errors.append(f'第{i}页未标明同题来源')
  expected_images=sum(len(b['images']) for q in case['questions'] for b in q['blocks'])
  if len(images)!=expected_images:errors.append(f'图片次数 {len(images)} != {expected_images}')
  if 'SYEND' in text:errors.append('内部测量标记泄露')
 with zipfile.ZipFile(folder/'错题本.docx') as z: xml=ET.fromstring(z.read('word/document.xml'))
 formula_count=len(xml.findall('.//m:oMath',ns))
 if name in ('公式及表格','长题跨页'):
  for tag in ('f','sSup','sSub'):
   if xml.find('.//m:'+tag,ns) is None: errors.append('公式结构缺少'+tag)
 # Every correction area retains nine 284-twip lines, at least 45 mm in total.
 correction=[s for s in xml.findall('.//w:spacing',ns) if s.get('{'+ns['w']+'}lineRule')=='exact' and s.get('{'+ns['w']+'}line')=='284']
 if len(correction)!=len(case['questions'])*9:errors.append('45毫米订正区结构不足')
 for r in xml.findall('.//w:r',ns):
  t=r.find('w:t',ns)
  if t is not None and t.text and any(x in t.text for x in ('长题行','【A','【H','【I','第01步')):
   size=r.find('w:rPr/w:sz',ns)
   if size is None or size.get('{'+ns['w']+'}val')!='21':errors.append('题干字号不是10.5磅')
 subprocess.run(['pdftoppm','-scale-to','1600','-png',str(pdf),str(folder/'exact-pdf')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 for i in range(1,pages+1):
  actual=next(folder.glob(f'exact-pdf-{i:0{len(str(pages))}d}.png')); expected=folder/f'preview-{i}.png'
  a=Image.open(actual).convert('RGB'); b=Image.open(expected).convert('RGB')
  if a.size!=b.size or ImageChops.difference(a,b).getbbox():errors.append(f'第{i}页预览与PDF像素不同')
 result=dict(name=name,questions=len(case['questions']),pages=pages,normalPages=base[name]['normalPages'],images=len(images),formulas=formula_count,passed=not errors,errors=errors)
 results.append(result);print(result,flush=True)
(root/'checks.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)); assert all(r['passed'] for r in results)
