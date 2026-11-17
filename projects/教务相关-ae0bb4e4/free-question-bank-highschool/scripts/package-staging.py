#!/usr/bin/env python3
"""Package independently reviewed source material; never changes the default bank."""
import argparse, collections, hashlib, importlib.util, json, pathlib, re, shutil
from html.parser import HTMLParser

APP = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = APP.parent / 'source-research/open-geometry-20261102'
DEFAULT_INTERNATIONAL = APP.parent / 'expansion-20261102/humanities-science/ready/combined-highschool-nine-subjects.jsonl'
_spec=importlib.util.spec_from_file_location('staging_international',APP/'scripts/staging-international.py')
INTERNATIONAL=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(INTERNATIONAL)
SUBJECTS = {'mathematics': '数学', 'chemistry': '化学', 'physics': '物理', 'science_general': '科学通用'}
CHAPTERS = {
    'algebraic-expressions':'代数式','exponents':'指数','number-patterns':'数列规律',
    'equations-and-inequalities':'方程与不等式','trigonometry':'三角函数与解三角形',
    'functions':'函数','functions-and-graphs':'函数与图像','euclidean-geometry':'平面几何',
    'analytical-geometry':'解析几何','finance-and-growth':'金融与增长','finance':'金融数学',
    'statistics':'统计','probability':'概率','measurement':'测量与立体几何',
    'differential-calculus':'微分基础','skills-for-science':'科学方法与技能',
    'classification-of-matter':'物质分类','states-of-matter':'物态变化','the-atom':'原子结构',
    'the-periodic-table':'元素周期表','chemical-bonding':'化学键',
    'physical-and-chemical-change':'物理变化与化学变化','representing-chemical-change':'化学反应表示',
    'reactions-in-aqueous-solution':'水溶液中的反应','quantitative-aspects-of-chemical-change':'化学计量',
    'atomic-combinations':'化学键与原子结合','intermolecular-forces':'分子间作用力',
    'ideal-gases':'理想气体','energy-and-chemical-change':'化学反应能量','types-of-reactions':'反应类型',
    'lithosphere':'岩石圈与资源','organic-molecules':'有机分子','rate-and-extent-of-reaction':'化学反应速率',
    'chemical-equilibrium':'化学平衡','acids-and-bases':'酸与碱','electrochemical-reactions':'电化学',
    'chemical-industry':'化学工业','transverse-pulses':'横向脉冲','transverse-waves':'横波',
    'longitudinal-waves':'纵波','sound':'声学','electromagnetic-radiation':'电磁辐射',
    'magnetism':'磁学','electrostatics':'静电学','electric-circuits':'电路',
    'vectors-and-scalars':'矢量与标量','motion-in-one-dimension':'直线运动','mechanical-energy':'机械能',
    'vectors-in-two-dimensions':'平面向量','newtons-laws':'牛顿运动定律','geometrical-optics':'几何光学',
    '2d-and-3d-wavefronts':'波面','electromagnetism':'电磁学','momentum-and-impulse':'动量与冲量',
    'vertical-projectile-motion-in-one-dimension':'竖直抛体运动','doppler-effect':'多普勒效应',
    'work-energy-and-power':'功、能与功率','electrodynamics':'电动力学',
    'optical-phenomena-and-properties-of-matter':'光学现象与物质性质',
}

class SourceText(HTMLParser):
    """Preserve visible text and image gaps, without rendering untrusted HTML."""
    def __init__(self): super().__init__(convert_charrefs=True); self.parts=[]; self.skip=0
    def handle_starttag(self,tag,attrs):
        tag=tag.split(':')[-1];attrs=dict(attrs)
        if tag in ('script','style'):self.skip+=1
        if self.skip:return
        if tag=='img':self.parts.append('〔公式图片〕' if 'math-' in attrs.get('class','') else '〔原图〕')
        elif tag in ('p','li','tr','br','div'):self.parts.append('\n')
        elif tag in ('td','th'):self.parts.append(' | ')
    def handle_endtag(self,tag):
        tag=tag.split(':')[-1]
        if tag in ('script','style') and self.skip:self.skip-=1
        elif tag in ('p','li','tr','div') and not self.skip:self.parts.append('\n')
    def handle_data(self,data):
        if not self.skip:self.parts.append(data)
    def text(self):return '\n'.join(' '.join(line.split()) for line in ''.join(self.parts).splitlines() if line.strip())

def plain(value):
    parser=SourceText();parser.feed(value or '');return parser.text()
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    text=json.dumps(value,ensure_ascii=False,separators=(',',':'))+'\n'
    if not path.exists() or path.read_text()!=text:path.write_text(text)
def chapter_path(record):
    filename=record['source']['archiveHtmlPath'].split('/')[-1]
    key=re.sub(r'^\d+-','',filename).split('.cnxmlplus')[0]
    key=re.sub(r'-\d+$','',key).replace('.rework','')
    chapter=CHAPTERS.get(key,key.replace('-',' ').title())
    leaf=record.get('knowledgePath',[])
    return [SUBJECTS[record['subject']],chapter]+leaf[-2:]

def package(source,out,international_source=DEFAULT_INTERNATIONAL):
    international,international_root=INTERNATIONAL.load_records(international_source,out)
    all_records=[]
    for subject in SUBJECTS:
        data=json.loads((source/f'staging-{subject}-siyavula.json').read_text())
        all_records.extend(data['records'])
    assert len(all_records)==7406 and len({q['id'] for q in all_records})==7406
    assert sum(q['hasSourceSolution'] for q in all_records)==7023
    chunks=out/'staging-chunks';chunks.mkdir(parents=True,exist_ok=True)
    index=[];chunk_meta=[];status_counts=collections.Counter();subject_counts=collections.Counter()
    source_counts=collections.Counter();needs_translation=0
    def append_chunk(name,group,source_set=None):
        nonlocal needs_translation
        for q in group:
            actual_source=source_set or q['sourceSet'];translate=q.get('needsTranslation',True)
            preview=' '.join(q['stemPlainText'].split())[:180]
            index.append({'id':q['id'],'subject':q['subject'],'sourceGrade':q['sourceGrade'],
                'knowledgePath':q['knowledgePath'],'preview':preview,'hasSourceSolution':q['hasSourceSolution'],
                'status':q['status'],'hasAssets':bool(q['assets']),'sourceSet':actual_source,
                'needsTranslation':translate,'chunk':name})
            status_counts[q['status']]+=1;subject_counts[q['subject']]+=1;source_counts[actual_source]+=1
            needs_translation+=int(translate)
        target=chunks/name;write(target,{'schemaVersion':'question-staging/1','count':len(group),'defaultAssemblyEnabled':False,'records':group})
        chunk_meta.append({'file':name,'count':len(group),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    for offset in range(0,len(all_records),100):
        name=f'{offset//100:03d}.json';group=[]
        for original in all_records[offset:offset+100]:
            q=dict(original)
            q['stemPlainText']=plain(q['stemHtml'])
            q['sharedContextPlainText']=[plain(h) for h in q.get('sharedContextHtml',[])]
            q['sourceSolutionPlainText']=plain(q.get('sourceSolutionHtml'))
            q['sourceKnowledgePath']=q['knowledgePath'];q['knowledgePath']=chapter_path(q)
            assert not q['validation']['eligibleForDefaultAssembly']
            group.append(q)
        append_chunk(name,group,'siyavula')
    for offset in range(0,len(international),100):
        append_chunk(f'{len(chunk_meta):03d}.json',international[offset:offset+100])
    assert len({q['id'] for q in index})==len(index),'题源ID重复；拒绝写索引。'
    write(out/'staging-index.json',{'schemaVersion':'question-staging-index/1','total':len(index),
        'hasSourceSolution':7023,'defaultAssemblyEnabled':False,'sourceBooks':6,
        'sourceSets':dict(source_counts),'internationalRecords':len(international),'needsTranslationCount':needs_translation,
        'educationSystem':'South Africa CAPS / international high-school sources','chinaCurriculumMapped':False,'semanticDeduplicated':False,
        'countMeaning':'教材及考试抽取记录，可能含题组、子题或跨源近似题；国际资料已折叠官方平行译题及精确重复，未完成跨题语义去重。',
        'subjects':dict(subject_counts),'statuses':dict(status_counts),'chunkSize':100,'chunks':chunk_meta,
        'provenanceReadme':'staging-provenance/README.txt','records':index})
    catalog=json.loads((source/'open-source-catalog.json').read_text())
    catalog['scope']='来源目录含已收集题目与仅元数据入口；题目条数以staging-index.json为准，不加入默认组卷。'
    write(out/'open-source-catalog.json',catalog)
    reviewed=json.loads((source/'staging-ready-reviewed-16.json').read_text())
    for q in reviewed['records']:
        q['stemPlainText']=plain(q['stemHtml']);q['sharedContextPlainText']=[plain(h) for h in q.get('sharedContextHtml',[])]
        q['sourceSolutionPlainText']=plain(q.get('sourceSolutionHtml'))
    write(out/'staging-reviewed-16.json',reviewed)
    provenance=out/'staging-provenance';provenance.mkdir(exist_ok=True)
    for name in ['README.txt','validation-report.json','staging-summary.json']:
        shutil.copy2(source/name,provenance/name)
    shutil.copytree(source/'licenses',provenance/'licenses',dirs_exist_ok=True)
    INTERNATIONAL.copy_provenance(international_root,out)
    extra=f'\n\n国际高中原题追加记录：{len(international)} 条；来源数量：{json.dumps(dict(source_counts),ensure_ascii=False)}。\nEXAMS 为CC BY-SA4.0，MMLU 为MIT；仅高中范围、声明许可、答案结构经过检查。\n保留原数据答案，不伪造原解；均待中文翻译、教学审核和中国课标映射，不进入默认组卷。\n官方平行翻译与精确重复已处理，不声明完成全部语义去重。不含C-Eval/CMMLU非商业限制数据。\n详见international-source-manifest.json与licenses/EXAMS-LICENSE.txt、licenses/MMLU-LICENSE.txt。\n'
    with (provenance/'README.txt').open('a') as f:f.write(extra)
    print(json.dumps({'records':len(index),'sourceSolutions':7023,'chunks':len(chunk_meta),
        'indexBytes':(out/'staging-index.json').stat().st_size,'chunkBytes':sum(c['bytes'] for c in chunk_meta),
        'statusCounts':dict(status_counts),'sourceSets':dict(source_counts),'reviewed':len(reviewed['records'])},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=pathlib.Path,default=DEFAULT_SOURCE)
    parser.add_argument('--out',type=pathlib.Path,default=APP/'dist/data')
    parser.add_argument('--international-source',type=pathlib.Path,default=DEFAULT_INTERNATIONAL)
    args=parser.parse_args();package(args.source,args.out,args.international_source)
