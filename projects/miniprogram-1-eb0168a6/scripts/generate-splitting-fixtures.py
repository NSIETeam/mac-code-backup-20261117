#!/usr/bin/env python3
"""Generate disposable, synthetic Word handouts and independent expected results."""
import io, json, sys, subprocess
from pathlib import Path
from copy import deepcopy
from docx import Document
from docx.oxml import OxmlElement, parse_xml
from docx.oxml.ns import qn
from docx.shared import Inches, Pt
from PIL import Image, ImageDraw
ROOT = Path(sys.argv[1] if len(sys.argv)>1 else '.local/acceptance/splitting-20261023').resolve()
ROOT.mkdir(parents=True, exist_ok=True)
manifest=[]
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
M='http://schemas.openxmlformats.org/officeDocument/2006/math'
def base():
    d=Document(); d.styles['Normal'].font.name='宋体'; d.styles['Normal'].font.size=Pt(10.5)
    d.styles['Normal']._element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'),'宋体')
    d.add_paragraph('第一讲 临时拆题测试')
    return d
def p(d,s): return d.add_paragraph(s)
def expected(label,*tokens,images=0,tables=0,math=0,issue=None):
    return dict(label=label,tokens=list(tokens),images=images,tables=tables,math=math,issue=issue)
def save(d,name,qs=None,reject=None,reason=''):
    file=name+'.docx'; d.save(ROOT/file)
    manifest.append(dict(file=file,title=name,questions=qs or [],reject=reject,reason=reason))
def num(d,text,num_id=50,level=0,style=None):
    para=p(d,text)
    if style: para.style=style
    else:
        np=OxmlElement('w:numPr'); il=OxmlElement('w:ilvl'); il.set(qn('w:val'),str(level)); ni=OxmlElement('w:numId'); ni.set(qn('w:val'),str(num_id)); np.extend([il,ni]); para._p.get_or_add_pPr().append(np)
    return para
def numbering(d,prefix='%1.',nested='%2.',restart=False):
    tree=d.part.numbering_part.element
    tree.append(parse_xml(f'''<w:abstractNum xmlns:w="{W}" w:abstractNumId="50"><w:multiLevelType w:val="multilevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="{prefix}"/></w:lvl><w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="{nested}"/></w:lvl></w:abstractNum>'''))
    for ident,start in [(50,None)]+([(51,5)] if restart else []):
        tree.append(parse_xml(f'<w:num xmlns:w="{W}" w:numId="{ident}"><w:abstractNumId w:val="50"/>'+ (f'<w:lvlOverride w:ilvl="0"><w:startOverride w:val="{start}"/></w:lvlOverride>' if start else '')+'</w:num>'))
def picture(d,caption='矩形示意图',floating=False):
    im=Image.new('RGB',(480,180),'white'); draw=ImageDraw.Draw(im); draw.rectangle((55,25,420,150),outline='black',width=4); draw.text((200,75),'12 x 5',fill='black'); b=io.BytesIO(); im.save(b,format='PNG'); b.seek(0)
    inline=d.add_picture(b,width=Inches(2.4))._inline
    inline.docPr.set('descr',caption)
    if floating:
        anchor=parse_xml(f'<wp:anchor xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="1" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH><wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:wrapNone/></wp:anchor>')
        for c in list(inline): anchor.append(deepcopy(c))
        inline.getparent().replace(inline,anchor)
# Conventional mixed labels, same number across example/exercise, and manual fullwidth punctuation.
d=base()
for s in ['例1. 【A1】解方程：2x+3=11。','练1. 【A2】计算：12÷3。','2． 【A3】求正方形面积。','3、【A4】比较大小。']: p(d,s)
save(d,'01_混合题号',[expected('例1.','【A1】'),expected('练1.','【A2】'),expected('2．','【A3】'),expected('3、','【A4】')])
d=base(); p(d,'例 2：【B1】阅读材料并回答问题。'); p(d,'（1）写出等量关系。'); p(d,'(2) 列出方程。'); p(d,'A. 3.14  B. 2.50  C. 2026'); p(d,'3.14是近似值，2026.10为记录月份。'); p(d,'练二：【B2】完成计算。')
save(d,'02_小问选项小数',[expected('例 2：','【B1】','（1）','(2)','A.','3.14'),expected('练二：','【B2】')])
d=base(); p(d,'1. 【C1】同一题的第一段。'); [p(d,'') for _ in range(5)]; p(d,'【C1尾】空行之后仍属于第一题。'); p(d,'2. 【C2】无空行紧接的第二题。')
save(d,'03_空行与连续段落',[expected('1.','【C1】','【C1尾】'),expected('2.','【C2】')])
d=base(); numbering(d); num(d,'【D1】自动编号第一题。'); num(d,'【D1小问】自动编号的第一级小问，显示1.。',level=1); num(d,'【D1小问二】仍属于第一题。',level=1); num(d,'【D2】自动编号第二题。')
save(d,'04_自动编号含数字小问',[expected('1.','【D1】','【D1小问】','【D1小问二】'),expected('2.','【D2】')])
d=base(); numbering(d,prefix='例%1：',nested='（%2）'); num(d,'【E1】自动例题。'); num(d,'【E1小问】计算第一步。',level=1); num(d,'【E2】自动例题第二题。')
save(d,'05_自动例题前缀',[expected('例1：','【E1】','【E1小问】'),expected('例2：','【E2】')])
d=base(); numbering(d,restart=True)
from docx.enum.style import WD_STYLE_TYPE
st=d.styles.add_style('QuestionNumberBase',WD_STYLE_TYPE.PARAGRAPH); st.element.get_or_add_pPr().append(parse_xml(f'<w:numPr xmlns:w="{W}"><w:ilvl w:val="0"/><w:numId w:val="50"/></w:numPr>'))
child=d.styles.add_style('QuestionNumberChild',WD_STYLE_TYPE.PARAGRAPH); child.base_style=st
num(d,'【F1】样式继承自动编号。',style='QuestionNumberChild'); num(d,'【F2】样式继承第二题。',style='QuestionNumberChild'); num(d,'【F5】重启编号从5开始。',num_id=51)
save(d,'06_编号继承及起始值',[expected('1.','【F1】'),expected('2.','【F2】'),expected('5.','【F5】')])
d=base(); p(d,'例1. 【G1】跨页题题干。'); d.add_page_break(); p(d,'【G1续】跨页后的同一道题。'); p(d,'（1）写出完整过程。'); p(d,'练1. 【G2】下一题。')
save(d,'07_题内分页',[expected('例1.','【G1】','【G1续】','（1）'),expected('练1.','【G2】')])
d=base(); p(d,'1. 【H1】如图求面积。'); picture(d); p(d,'【H1中】同一张图重复出现。'); picture(d); p(d,'2. 【H2】另一题使用同一图片。'); picture(d)
save(d,'08_图片位置和重复引用',[expected('1.','【H1】','【H1中】',images=2),expected('2.','【H2】',images=1)])
d=base(); p(d,'例1. 【I1】化简下面的分式及幂。'); para=p(d,''); para._p.append(parse_xml(f'<m:oMath xmlns:m="{M}"><m:f><m:num><m:r><m:t>x+1</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup><m:sSub><m:e><m:r><m:t>a</m:t></m:r></m:e><m:sub><m:r><m:t>1</m:t></m:r></m:sub></m:sSub></m:oMath>')); p(d,'练1. 【I2】根据表格回答问题。')
t=d.add_table(rows=2,cols=2); t.style='Table Grid'
for cell,text in zip([c for row in t.rows for c in row.cells],['1. 表内编号','2. 仍在表格内','速度','60']): cell.text=text
save(d,'09_公式上下标和表格',[expected('例1.','【I1】','x+1',math=1),expected('练1.','【I2】','1. 表内编号','速度',tables=1)])
d=base(); p(d,'一、例题'); p(d,'例1. 【J1】第一节例题。'); p(d,'二、练习'); p(d,'例1. 【J2】不同节重复例题号。'); p(d,'例1. 【J3】同节重复例题号，应提醒。')
save(d,'10_分节与重复题号',[expected('例1.','【J1】'),expected('例1.','【J2】',issue='duplicate-label'),expected('例1.','【J3】',issue='duplicate-label')])
d=base(); p(d,'1. 【K1】浮动图形需校对归属。'); picture(d,floating=True); p(d,'2. 【K2】普通题。')
save(d,'11_浮动图片',[expected('1.','【K1】',images=1,issue='floating-image'),expected('2.','【K2】')])
d=base(); p(d,'1. 【L1】含旧式对象，应阻止发布。'); para=p(d,''); para._p.append(parse_xml(f'<w:r xmlns:w="{W}"><w:object><o:OLEObject xmlns:o="urn:schemas-microsoft-com:office:office" Type="Embed" ProgID="Equation.3"/></w:object></w:r>')); p(d,'2. 【L2】普通题。')
save(d,'12_未知对象阻止发布',[expected('1.','【L1】',issue='unsupported-object'),expected('2.','【L2】')])
d=Document(); picture(d); save(d,'13_纯扫描图片应拒绝',reject='没有可拆分的正文',reason='首版无OCR，不应生成可发布的假题目')
d=base(); p(d,'1. 【N1】缺少题号的说明文字应保留。'); p(d,'这是一段连续说明。'); p(d,'2. 【N2】第二题。')
save(d,'14_普通正文与题号',[expected('1.','【N1】','连续说明'),expected('2.','【N2】')])
# Less common but valid Word numbering and content controls.
d=base(); numbering(d)
ni=d.part.numbering_part.element.xpath('./w:num[@w:numId="50"]')[0]
ni.append(parse_xml(f'<w:lvlOverride xmlns:w="{W}" w:ilvl="0"><w:lvl w:ilvl="0"><w:start w:val="5"/><w:numFmt w:val="decimal"/><w:lvlText w:val="练%1."/></w:lvl></w:lvlOverride>'))
num(d,'【P5】实例层覆盖编号格式。'); num(d,'【P6】实例层第二题。')
save(d,'16_编号实例格式覆盖',[expected('练5.','【P5】'),expected('练6.','【P6】')])
d=base(); numbering(d)
st=d.styles.add_style('InheritedQuestion',WD_STYLE_TYPE.PARAGRAPH); st.element.get_or_add_pPr().append(parse_xml(f'<w:numPr xmlns:w="{W}"><w:ilvl w:val="0"/><w:numId w:val="50"/></w:numPr>'))
for text in ['【Q1】部分编号属性来自样式。','【Q2】第二题。']:
    para=p(d,text); para.style=st; para._p.get_or_add_pPr().append(parse_xml(f'<w:numPr xmlns:w="{W}"><w:ilvl w:val="0"/></w:numPr>'))
save(d,'17_段落与样式编号合并',[expected('1.','【Q1】'),expected('2.','【Q2】')])
d=base(); sdt=parse_xml(f'<w:sdt xmlns:w="{W}"><w:sdtPr><w:tag w:val="Questions"/></w:sdtPr><w:sdtContent/></w:sdt>'); content=sdt.find(qn('w:sdtContent'))
for text in ['1. 【R1】内容控件中的第一题。','2. 【R2】内容控件中的第二题。']:
    para=p(d,text); content.append(para._p)
d._body._body.insert(1,sdt)
save(d,'18_内容控件中的题目',[expected('1.','【R1】'),expected('2.','【R2】')])
if '--legacy' in sys.argv:
    source_dir=ROOT/'legacy-source'; source_dir.mkdir(exist_ok=True)
    for index,name in [(0,'15_旧版混合题号'),(7,'19_旧版图片重复引用')]:
        fixture=manifest[index]; source=source_dir/(name+'.docx'); source.write_bytes((ROOT/fixture['file']).read_bytes())
        profile=Path('/tmp')/('siyue-fixture-'+name)
        subprocess.run(['soffice','-env:UserInstallation='+profile.as_uri(),'--headless','--convert-to','doc:MS Word 97','--outdir',str(ROOT),str(source)],check=True,stdout=subprocess.DEVNULL)
        item=deepcopy(fixture); item.update(file=name+'.doc',title=name); manifest.append(item)
(ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
print(f'{len(manifest)} Word fixtures: {ROOT}')
