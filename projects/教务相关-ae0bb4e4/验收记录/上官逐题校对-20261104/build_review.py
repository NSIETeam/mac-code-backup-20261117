from pathlib import Path
import json, math, zipfile
from PIL import Image, ImageChops, ImageDraw, ImageFont
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / '第一讲' / 'rects.json'
OUT = ROOT / '交付'
OUT.mkdir(exist_ok=True)
IMG = OUT / '独立题图'
IMG.mkdir(exist_ok=True)
doc = json.loads(MANIFEST.read_text())
questions = doc['questions']
assert len(questions) == 31
assert len({q['label'] for q in questions}) == 31
pdfmetrics.registerFont(TTFont('STSong-Light','/System/Library/Fonts/Supplemental/Arial Unicode.ttf'))
pdf = canvas.Canvas(str(OUT / '第一讲-逐题校对版.pdf'), pagesize=(595.3,841.95))
pdf.setTitle('第一讲 绝对值和整式巅峰突破 - 逐题校对版')
audit=[]
thumbs=[]
for n,q in enumerate(questions,1):
    parts=[]
    for r in q['regions']:
        im=Image.open(ROOT/'tmp'/f"highres-{r['page']:03d}.png").convert('RGB')
        x0,y0,x1,y1=r['bbox']
        assert 0 <= x0 < x1 <= 595.3 and 0 <= y0 < y1 <= 841.95
        sx,sy=im.width/595.3,im.height/841.95
        crop=im.crop((math.floor(x0*sx),math.floor(y0*sy),math.ceil(x1*sx),math.ceil(y1*sy)))
        # Trim only blank top/bottom rows. Keep the original shared horizontal scale.
        diff=ImageChops.difference(crop,Image.new('RGB',crop.size,'white')).convert('L')
        bounds=diff.point(lambda v:255 if v>35 else 0).getbbox()
        assert bounds is not None, (q['label'],r)
        top=max(0,bounds[1]-10); bottom=min(crop.height,bounds[3]+10)
        parts.append(crop.crop((0,top,crop.width,bottom)))
    combined=Image.new('RGB',(max(p.width for p in parts)+24,sum(p.height for p in parts)+20*(len(parts)-1)+24),'white')
    yy=12
    for part in parts:
        combined.paste(part,(12,yy)); yy+=part.height+20
    filename=f'{n:02d}-{q["label"]}.png'
    combined.save(IMG/filename,optimize=True)
    pages='、'.join(str(r['page']) for r in q['regions'])
    pdf.setFont('STSong-Light',13); pdf.drawString(36,804,f'第一讲 · {q["label"]}')
    pdf.setFont('STSong-Light',9); pdf.setFillColorRGB(.4,.4,.4)
    pdf.drawRightString(559,805,f'原 PDF 第 {pages} 页')
    pdf.setFillColorRGB(0,0,0)
    scale=min(523/combined.width,700/combined.height)
    width,height=combined.width*scale,combined.height*scale
    pdf.drawImage(ImageReader(combined),36,777-height,width=width,height=height)
    pdf.setFont('STSong-Light',9); pdf.setFillColorRGB(.4,.4,.4)
    pdf.drawString(36,24,'保留原题文字、公式与图形；本文件未写入在线讲义。')
    pdf.drawRightString(559,24,f'{n} / {len(questions)}')
    pdf.setFillColorRGB(0,0,0); pdf.showPage()
    audit.append({'order':n,'label':q['label'],'sourcePages':[r['page'] for r in q['regions']],'file':filename,'imageSize':combined.size})
    thumb=combined.copy(); thumb.thumbnail((380,390))
    cell=Image.new('RGB',(420,440),'#eceef1'); cell.paste(thumb,((420-thumb.width)//2,40))
    ImageDraw.Draw(cell).text((16,12),f'{n:02d} / source {pages}',fill='black')
    thumbs.append(cell)
pdf.save()
assert len(PdfReader(OUT/'第一讲-逐题校对版.pdf').pages)==31
for start in range(0,len(thumbs),12):
    sheet=Image.new('RGB',(1260,1760),'white')
    for k,im in enumerate(thumbs[start:start+12]): sheet.paste(im,((k%3)*420,(k//3)*440))
    sheet.save(ROOT/'tmp'/f'输出总览-{start//12+1}.png')
(OUT/'逐题核对记录.json').write_text(json.dumps({'source':'2026初一秋季数学自招（上）.pdf','lesson':1,'onlineSaved':False,'questions':audit,'excluded':doc.get('excluded',[])},ensure_ascii=False,indent=2))
with zipfile.ZipFile(OUT/'第一讲-31道独立题图.zip','w',zipfile.ZIP_DEFLATED) as z:
    for path in sorted(IMG.glob('*.png')): z.write(path,'独立题图/'+path.name)
    z.write(OUT/'逐题核对记录.json','逐题核对记录.json')
print(json.dumps({'questions':len(questions),'merged':[x['label'] for x in audit if len(x['sourcePages'])>1],'pdfBytes':(OUT/'第一讲-逐题校对版.pdf').stat().st_size,'zipBytes':(OUT/'第一讲-31道独立题图.zip').stat().st_size},ensure_ascii=False))
