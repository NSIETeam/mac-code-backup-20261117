from pathlib import Path
import hashlib, json, zipfile
from docx import Document
from docx.shared import Mm, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'交付'
record=json.loads((OUT/'逐题核对记录.json').read_text())
doc=Document()
section=doc.sections[0]
section.page_width=Mm(210); section.page_height=Mm(297)
section.top_margin=Mm(17); section.bottom_margin=Mm(17)
section.left_margin=Mm(17); section.right_margin=Mm(17)
for name in ['Normal','Title']:
    st=doc.styles[name]; st.font.name='Arial Unicode MS'; st.font.color.rgb=RGBColor(0,0,0)
    fonts=st.element.get_or_add_rPr().get_or_add_rFonts()
    for key in ['ascii','hAnsi','eastAsia','cs']: fonts.set(qn('w:'+key),'Arial Unicode MS')
    for key in list(fonts.attrib):
        if key.endswith('Theme'): del fonts.attrib[key]
    st.font.size=Pt(11 if name=='Normal' else 17)
    st.paragraph_format.space_after=Pt(6)
for el in list(doc.styles.element.iter(qn('w:pBdr'))): el.getparent().remove(el)
doc.core_properties.title='第一讲 绝对值和整式巅峰突破'
doc.core_properties.subject='上官 周五晚初一自招 第一讲逐题校对讲义'
doc.core_properties.author='思跃教育'
doc.add_paragraph('第一讲 绝对值和整式巅峰突破','Title')
doc.add_paragraph('例题与练习25道，限时训练6道。题目保留原稿文字、公式与图形。')
rows=[]
for idx,q in enumerate(record['questions']):
    label=q['label']
    if label.startswith('限时训练'):
        label='测'+label.removeprefix('限时训练').strip()
    marker='【'+label+'】'
    p=doc.add_paragraph(marker)
    p.paragraph_format.keep_with_next=True
    p.paragraph_format.page_break_before=idx>0
    p.runs[0].bold=True
    path=OUT/'独立题图'/q['file']
    with Image.open(path) as im:
        w,h=im.size
    width=min(176,220*w/h)
    p=doc.add_paragraph()
    p.paragraph_format.space_after=Pt(0)
    run=p.add_run(); inline=run.add_picture(str(path),width=Mm(width))
    inline._inline.docPr.set('descr',f"{q['label']} 原PDF第{','.join(map(str,q['sourcePages']))}页")
    rows.append({'order':idx+1,'label':marker,'sourceLabel':q['label'],'sourcePages':q['sourcePages'],'imageSha256':hashlib.sha256(path.read_bytes()).hexdigest()})
path=OUT/'第一讲-正式导入.docx'
doc.save(path)
with zipfile.ZipFile(path) as z:
    media=[z.read(n) for n in z.namelist() if n.startswith('word/media/')]
    hashes={hashlib.sha256(x).hexdigest() for x in media}
    assert len(media)==31 and hashes=={q['imageSha256'] for q in rows}
    from xml.etree import ElementTree as ET
    root=ET.fromstring(z.read('word/document.xml'))
    ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    texts=[''.join(p.itertext()) for p in []]
    texts=[''.join(t.text or '' for t in p.findall('.//w:t',ns)) for p in root.findall('.//w:body/w:p',ns)]
    labels=[t for t in texts if t.startswith('【')]
    assert labels==[q['label'] for q in rows]
manifest={'classId':'7cdc2f8c-8293-d7b9-b9fa-4d72134c188e','className':'上官—周五晚初一自招','lectureSequence':1,'lectureTitle':'绝对值和整式巅峰突破','recognitionMode':'named','expectedQuestionCount':31,'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'questions':rows,'published':False,'publicationStrategy':'resolve exact existing class and lecture; upload new handout version; verify 31 questions, labels, images and no blocking issues; confirm; publish; reread state','priorVersion':'retain; do not replace existing class-session handout references'}
(OUT/'正式发布清单.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({'docxBytes':path.stat().st_size,'questions':len(rows),'imagesByteIdentical':True,'labels':[q['label'] for q in rows]},ensure_ascii=False))
