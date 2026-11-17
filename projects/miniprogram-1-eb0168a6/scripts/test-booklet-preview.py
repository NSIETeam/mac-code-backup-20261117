#!/usr/bin/env python3
"""Generate local booklet acceptance cases using the actual import/export/preview APIs."""
import json,sys,time,uuid,hashlib,copy,shutil
from pathlib import Path
from docx import Document
from docx.shared import Inches
from docx.oxml.ns import qn
from PIL import Image,ImageDraw
import importlib.util
spec=importlib.util.spec_from_file_location('splitting_api',Path(__file__).with_name('test-splitting-api.py')); api=importlib.util.module_from_spec(spec); spec.loader.exec_module(api)
ROOT=Path('.local/acceptance/booklet-preview-20261023').resolve(); ROOT.mkdir(parents=True,exist_ok=True)
SOURCE=Path('.local/acceptance/splitting-20261023').resolve(); split=json.loads((SOURCE/'api-run.json').read_text())
STATE=ROOT/'run.json'

def dump(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2))
def wait_handout(hid):
    for _ in range(450):
        d=api.request('/api/handouts/'+hid)
        if d['handout']['state']=='draft' or d['handout']['state']=='published': return d
        if d['handout']['state']=='failed': raise RuntimeError(d['handout']['error'])
        time.sleep(2)
    raise TimeoutError(hid)
def publish(hid):
    d=wait_handout(hid)
    if d['handout']['state']=='published': return d
    for q in d['questions']:
        if not q['confirmed']:
            r=api.request('/api/handouts/'+hid+'/edit',dict(action='confirm',questionId=q['id'],revision=d['handout']['revision']))
            d['handout']['revision']=r['revision']
    api.request('/api/handouts/'+hid+'/publish',dict(revision=d['handout']['revision']))
    return api.request('/api/handouts/'+hid)
def upload(name,path,sequence):
    lecture=api.request('/api/lectures',dict(classId=split['classId'],courseId=split['courseId'],title=name,sequence=sequence))
    boundary=uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode())
    h=api.request('/api/lectures/'+lecture['id']+'/handouts',body,ctype='multipart/form-data; boundary='+boundary)
    return dict(lectureId=lecture['id'],handoutId=h['id'],file=path.name)
def generate_inputs():
    d=Document(SOURCE/'01_混合题号.docx')
    for child in list(d._element.body):
        if child.tag!=qn('w:sectPr'): d._element.body.remove(child)
    d.add_paragraph('第二十讲 排版边界测试')
    for q in range(1,3):
        d.add_paragraph(f'{q}. 请列方程解答下列问题。')
        for i in range(1,9): d.add_paragraph(f'第{i:02d}步：保留完整条件，写出计算过程。')
    d.save(ROOT/'20_留白溢页.docx')
    d=Document(SOURCE/'08_图片位置和重复引用.docx')
    pic=copy.deepcopy(next(p for p in d._element.body if p.findall('.//'+qn('w:drawing'))))
    mathdoc=Document(SOURCE/'09_公式上下标和表格.docx')
    formula=copy.deepcopy(next(p for p in mathdoc._element.body if p.findall('.//{http://schemas.openxmlformats.org/officeDocument/2006/math}oMath')))
    for child in list(d._element.body):
        if child.tag!=qn('w:sectPr'): d._element.body.remove(child)
    d.add_paragraph('第二十一讲 长题续页测试'); d.add_paragraph('例1. 【长题起】保留以下全部条件，并写出完整解题过程。')
    for i in range(1,71):
        d.add_paragraph(f'【长题行{i:02d}】已知数量关系，说明计算依据并核对结果。')
        if i in (25,55): d._element.body.insert(len(d._element.body)-1,copy.deepcopy(pic))
        if i==35: d._element.body.insert(len(d._element.body)-1,formula)
    d.add_paragraph('【长题终】以上70行、2幅图和分式上下标必须完整保留。'); d.save(ROOT/'21_长题图文跨页.docx')
    d=Document(SOURCE/'01_混合题号.docx')
    for child in list(d._element.body):
        if child.tag!=qn('w:sectPr'): d._element.body.remove(child)
    d.add_paragraph('第二十二讲 大图缩放测试'); d.add_paragraph('1. 【宽图题】下图必须等比缩放，不能越过右侧题目区域。')
    im=Image.new('RGB',(1800,360),'white'); draw=ImageDraw.Draw(im); draw.rectangle((5,5,1794,354),outline='black',width=8); draw.line((5,5,1794,354),fill='black',width=4); draw.text((25,30),'WIDE IMAGE 5:1',fill='black'); im.save(ROOT/'wide.png'); d.add_picture(str(ROOT/'wide.png'),width=Inches(8))
    d.add_paragraph('2. 【高图题】图形四边及底部的 END 标记应可见。')
    im=Image.new('RGB',(400,1000),'white'); draw=ImageDraw.Draw(im); draw.rectangle((5,5,394,994),outline='black',width=6); draw.text((150,960),'END',fill='black'); im.save(ROOT/'tall.png'); d.add_picture(str(ROOT/'tall.png'),height=Inches(6.8)); d.save(ROOT/'22_宽高图片.docx')

def main():
    api.token=api.request('/api/auth/preview',{})['token']
    run=json.loads(STATE.read_text()) if STATE.exists() else dict(classId=split['classId'],courseId=split['courseId'],imports={},cases={})
    generate_inputs()
    for key in ['20_留白溢页','21_长题图文跨页','22_宽高图片']:
        if key not in run['imports']:
            run['imports'][key]=upload(key,ROOT/(key+'.docx'),int(key[:2])); dump(STATE,run); print('uploaded '+key,flush=True)
    refs={x['file'][:2]:x for x in split['items']}
    refs.update({k[:2]:v for k,v in run['imports'].items()})
    use=['01','07','08','09','11','19','20','21','22']; details={}
    for key in use:
        details[key]=publish(refs[key]['handoutId']); print('published '+key,flush=True)
    cases=[('图片与浮动图',[('08',None),('11',[0])]),('公式及表格',[('09',None)]),('旧版图片',[('19',None)]),('长题跨页',[('21',None)]),('三题尾页压缩',[('01',[0,1,2])]),('留白溢页压缩',[('20',None)]),('题内分页重排',[('07',None)]),('宽高图片',[('22',None)])]
    pending=[]
    for index,(name,selection) in enumerate(cases,1):
        if name not in run['cases']:
            s=api.request('/api/admin/catalog/students',dict(name='预览验收·'+name,code=f'PREVIEW{index:02d}',classId=split['classId']))
            run['cases'][name]=dict(studentId=s['id']); dump(STATE,run)
        case=run['cases'][name]
        if '--reflow' in sys.argv and 'documentId' in case:
            next_doc=api.request('/api/exports/'+case['documentId']+'/reflow',{})
            if next_doc['id']!=case['documentId']:
                folder=ROOT/name; before=folder/'before-v3'; before.mkdir(parents=True,exist_ok=True)
                for file in list(folder.glob('错题本.*'))+list(folder.glob('preview-*.png')):
                    shutil.copy2(file,before/file.name)
                    if file.suffix=='.png': file.unlink()
                case.setdefault('previousDocuments',[]).append(dict(id=case['documentId'],pdfHash=case.get('pdfHash')))
                case['documentId']=next_doc['id']; dump(STATE,run)
        lectureids=[]; expected=[]
        for key,indices in selection:
            d=details[key]; qs=d['questions']; chosen=qs if indices is None else [qs[i] for i in indices]
            api.request('/api/wrong-questions',dict(handoutId=refs[key]['handoutId'],classId=split['classId'],studentIds=[case['studentId']],questionIds=[q['id'] for q in chosen],mode='replace'),method='PUT')
            lectureids.append(refs[key]['lectureId']); expected.extend(dict(id=q['id'],label=q['label'],blocks=q['blocks'],halfPage=q['halfPage']) for q in chosen)
        if 'documentId' not in case:
            batch=api.request('/api/exports',dict(classId=split['classId'],courseId=split['courseId'],studentIds=[case['studentId']],lectureIds=lectureids))
            case.update(documentId=batch['documents'][0]['id'],batchId=batch['batchId']); dump(STATE,run)
        case['questions']=expected; dump(STATE,run); pending.append((name,case))
        print('queued '+name,flush=True)
    for name,case in pending:
        folder=ROOT/name; folder.mkdir(exist_ok=True)
        for _ in range(600):
            exports=api.request('/api/exports?batchId='+case['batchId']); item=next(d for d in exports if d['id']==case['documentId'])
            if item['state']=='ready': break
            if item['state']=='failed': raise RuntimeError(item['error'])
            time.sleep(2)
        else: raise TimeoutError(name)
        for fmt in ('pdf','docx'):
            link=api.request('/api/exports/'+case['documentId']+'/download?format='+fmt)
            (folder/('错题本.'+fmt)).write_bytes(api.request(link['url'],raw=True))
        api.request('/api/previews/export/'+case['documentId'],{})
        for _ in range(300):
            preview=api.request('/api/previews/export/'+case['documentId'])
            if preview['state']=='ready': break
            if preview['state']=='failed': raise RuntimeError(preview['error'])
            time.sleep(2)
        else: raise TimeoutError('preview '+name)
        for page in preview['pages']: (folder/f'preview-{page["number"]}.png').write_bytes(api.request(page['url'],raw=True))
        case['previewPages']=len(preview['pages']); case['pdfHash']=hashlib.sha256((folder/'错题本.pdf').read_bytes()).hexdigest(); dump(STATE,run)
        print('READY '+name+f' {len(preview["pages"])} pages',flush=True)
if __name__=='__main__': main()
