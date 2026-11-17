#!/usr/bin/env python3
"""Local-only source normalization. No generated items, no app mutation or publishing."""
import collections,csv,hashlib,io,json,pathlib,re,tarfile,unicodedata
from html.parser import HTMLParser

ROOT=pathlib.Path(__file__).resolve().parent
WORKSPACE=ROOT.parents[1]
SUBJECT_ZH={'biology':'生物','history':'历史','geography':'地理','physics':'物理','chemistry':'化学','politics':'政治','mathematics':'数学','english':'英语','chinese':'语文'}
EXAMS_MAP={'Biology':'biology','History':'history','Geography':'geography','Physics':'physics','Chemistry':'chemistry','Politics':'politics','Citizenship':'politics'}
MMLU_MAP={'high_school_biology':'biology','high_school_chemistry':'chemistry','high_school_geography':'geography','high_school_government_and_politics':'politics','high_school_mathematics':'mathematics','high_school_statistics':'mathematics','high_school_physics':'physics','high_school_world_history':'history','high_school_european_history':'history','high_school_us_history':'history'}
EXAMS_REV='f859e665de6c370f6214ca5f36a34ace36ada6cb'
MMLU_REV='4450500f923c49f1fb1dd3d99108a0bd9717b660'

def dump(name,value):
    p=ROOT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def jsonl(name,rows):
    p=ROOT/name;p.parent.mkdir(parents=True,exist_ok=True)
    with p.open('w') as f:
        for row in rows:f.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
def sha(b):return hashlib.sha256(b).hexdigest()
def stable(value):return sha(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode())
def norm(text):return ' '.join(unicodedata.normalize('NFKC',str(text)).casefold().split())
def full_fingerprint(stem,options):
    text=unicodedata.normalize('NFKC',stem+' '+' '.join(o['text'] for o in options)).casefold()
    text=re.sub(r'(^|\s)[A-Fa-f][.、．:]\s*',' ',text)
    text=re.sub(r'^\s*\d+[.)、．]\s*','',text)
    return sha(''.join(c for c in text if c.isalnum()).encode())
def fingerprint(q):return stable({'subject':q['subject'],'stem':norm(q['stem']),'choices':sorted(norm(o['text']) for o in q['options'])})
def source_ref(file,record,line=None):return {'rawFile':str(file.relative_to(ROOT)),'rawLine':line,'rawRecordSha256':stable(record)}

class Plain(HTMLParser):
    def __init__(self):super().__init__();self.data=[]
    def handle_data(self,x):self.data.append(x)
def plain(html):p=Plain();p.feed(html or '');return ' '.join(p.data)
def existing_fingerprints():
    fingerprints=set();counts={}
    path=WORKSPACE/'free-question-bank-highschool/dist/data/questions.json'
    if path.exists():
        arr=json.loads(path.read_text());counts['GAOKAO-Bench']=len(arr)
        for q in arr:fingerprints.add(full_fingerprint(q['stem'],[]))
    root=WORKSPACE/'source-research/open-geometry-20261102'
    for path in root.glob('staging-*-siyavula.json'):
        data=json.loads(path.read_text());counts[path.name]=len(data['records'])
        for q in data['records']:
            fingerprints.add(full_fingerprint(' '.join(plain(s) for s in q.get('sharedContextHtml',[]))+' '+plain(q.get('stemHtml')),[]))
    return fingerprints,counts

parent={}
def find(x):
    parent.setdefault(x,x)
    if parent[x]!=x:parent[x]=find(parent[x])
    return parent[x]
def union(a,b):
    a,b=find(a),find(b)
    if a!=b:parent[max(a,b)]=min(a,b)

def normalize():
    for line in (ROOT/'raw/exams-parallel_questions.jsonl').read_text().splitlines():
        for key,values in json.loads(line).items():
            for value in values:union(key,value)
    stats=collections.Counter();raw_subjects=collections.Counter();ready=[];hold=[];adjacent=[];by_id={}
    archives=sorted((ROOT/'raw').glob('exams-cross-*.tar.gz'))
    # Supplement with the complete test file if it has been downloaded and verified.
    test=ROOT/'raw/exams-test-complete.jsonl.tar.gz'
    if test.exists():archives.append(test)
    for archive in archives:
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                if not member.isfile():continue
                for line_no,line in enumerate(tar.extractfile(member),1):
                    if not line.strip():continue
                    row=json.loads(line);ident=row['id'];ref=source_ref(archive,row,line_no);ref['archiveMember']=member.name
                    stats['examsRawRows']+=1
                    if ident in by_id:
                        by_id[ident][1].append(ref);stats['examsRepeatedSourceIds']+=1
                    else:by_id[ident]=(row,[ref])
    stats['examsDistinctSourceIds']=len(by_id)
    for ident,(row,refs) in by_id.items():
        info=row.get('info',{});raw_subjects[info.get('subject')]+=1
        source_grade=info.get('grade');subject=EXAMS_MAP.get(info.get('subject'))
        if source_grade not in [10,11,12]:hold.append({'id':ident,'reason':'source grade is not explicitly10–12','sourceRecord':row,'sourceRefs':refs});continue
        if subject is None:
            adjacent.append({'id':ident,'sourceDataset':'EXAMS','status':'adjacent_subject_not_counted_toward_nine','sourceGrade':source_grade,'sourceSubject':info.get('subject'),'language':info.get('language'),'license':'CC-BY-SA-4.0','sourceRecord':row,'sourceRefs':refs});continue
        question=row.get('question',{});choices=question.get('choices',[]);answer=row.get('answerKey');stem=question.get('stem','')
        labels=[o.get('label') for o in choices]
        issues=[]
        if not stem.strip():issues.append('empty stem')
        if not 2<=len(choices)<=6 or len(set(labels))!=len(labels):issues.append('invalid choice structure')
        if not answer or answer not in labels:issues.append('missing or invalid answer key')
        if any(not c.get('text','').strip() for c in choices):issues.append('empty option')
        if len(set(norm(c.get('text','')) for c in choices))!=len(choices):issues.append('duplicate option text')
        assets=[]
        for match in re.findall(r'https?://[^\s\"<>]+\.(?:png|jpg|jpeg|svg|gif)',json.dumps(question,ensure_ascii=False),re.I):assets.append({'url':match,'downloaded':False})
        if assets:issues.append('referenced image not collected')
        if issues:hold.append({'id':ident,'reason':issues,'sourceRecord':row,'sourceRefs':refs});continue
        ready.append({'schema':'high-school-source-question/1','id':'exams:'+ident,'sourceDataset':'EXAMS','subject':subject,'subjectZh':SUBJECT_ZH[subject],'sourceSubject':info['subject'],'stage':'high_school','sourceGrade':source_grade,'gradeEvidence':'EXAMS per-record info.grade','language':info['language'],'type':'single_choice','stem':stem,'sharedMaterials':[],'sharedMaterialNote':'Original complete question.stem retained without splitting its embedded context.','options':choices,'answer':{'key':answer,'text':next(o['text'] for o in choices if o['label']==answer)},'explanation':None,'assets':assets,'source':{'publisher':'Momchil Hardalov et al.','url':f'https://github.com/mhardalov/exams-qa/tree/{EXAMS_REV}','revision':EXAMS_REV,'license':'CC-BY-SA-4.0','licenseEvidence':'licenses/EXAMS-LICENSE.txt','attribution':'Hardalov et al., EXAMS (EMNLP 2020), https://aclanthology.org/2020.emnlp-main.438/','adaptationLicense':'CC-BY-SA-4.0','codeLicense':'Apache-2.0 (README distinguishes code from data)','sourceRefs':refs},'rawSourceRecord':row,'readiness':{'permissionChecked':True,'sourceHighSchoolExplicit':True,'schemaAndAnswerKeyChecked':True,'teacherReviewed':False,'chinaCurriculumMapped':False,'translatedToChinese':False,'eligibleForDefaultAssembly':False},'status':'ready_for_source_import_not_classroom_reviewed','dedupGroup':'exams-parallel:'+find(ident)})
    stats['examsNineSubjectValidRowsBeforeDedup']=len(ready)
    groups=collections.defaultdict(list)
    for q in ready:groups[q['dedupGroup']].append(q)
    preferred=['English','Vietnamese','French','German','Portuguese','Italian','Bulgarian','Croatian']
    collapsed=[]
    for group,variants in sorted(groups.items()):
        variants.sort(key=lambda q:(preferred.index(q['language']) if q['language'] in preferred else len(preferred),q['id']))
        q=variants[0];q['equivalentVariantCount']=len(variants)
        q['equivalentVariants']=[{'id':v['id'],'language':v['language'],'sourceSubject':v['sourceSubject'],'sourceRefs':v['source']['sourceRefs'],'rawSourceRecord':v['rawSourceRecord']} for v in variants[1:]]
        if len({v['subject'] for v in variants})>1:
            hold.append({'id':q['id'],'reason':'official parallel group crosses mapped subjects','variants':variants});continue
        collapsed.append(q)
    stats['examsAfterOfficialParallelCollapse']=len(collapsed)
    # MMLU: only high_school named files, never auxiliary_train or college/professional tasks.
    mmlu=[];mmlu_adjacent=[]
    for file in sorted((ROOT/'raw/mmlu-highschool').rglob('*.csv')):
        config=file.name.rsplit('_',1)[0];subject=MMLU_MAP.get(config);split=file.parent.name
        for line_no,row in enumerate(csv.reader(io.StringIO(file.read_text())),1):
            stats['mmluHighSchoolRawRows']+=1
            if subject is None:mmlu_adjacent.append({'config':config,'row':row,'sourceRef':source_ref(file,row,line_no)});continue
            if len(row)!=6 or row[-1].strip() not in 'ABCD' or len(row[-1].strip())!=1 or any(not cell.strip() for cell in row):
                hold.append({'id':f'mmlu:{config}:{split}:{line_no}','reason':'invalid source CSV/answer','row':row});continue
            stem,*texts,answer=row;options=[{'label':chr(65+i),'text':v} for i,v in enumerate(texts)]
            # Do not call dangling visual references complete classroom questions.
            if re.search(r'(?:diagram|figure|graph|table)\s+(?:below|above)|shown\s+in\s+(?:the\s+)?(?:diagram|figure|graph)',stem,re.I):
                hold.append({'id':f'mmlu:{config}:{split}:{line_no}','reason':'possible missing visual/shared material','row':row,'sourceRef':source_ref(file,row,line_no)});continue
            if len({norm(t) for t in texts})!=len(texts):
                hold.append({'id':f'mmlu:{config}:{split}:{line_no}','reason':'duplicate option text','row':row});continue
            q={'schema':'high-school-source-question/1','id':f'mmlu:{config}:{split}:{line_no}','sourceDataset':'MMLU','subject':subject,'subjectZh':SUBJECT_ZH[subject],'sourceSubject':config,'stage':'high_school','sourceGrade':None,'gradeEvidence':'Original task filename explicitly high_school_; exact school year unspecified','language':'English','type':'single_choice','stem':stem,'sharedMaterials':[],'sharedMaterialNote':'Original complete CSV question field retained with all embedded passages.','options':options,'answer':{'key':answer,'text':texts[ord(answer)-65]},'explanation':None,'assets':[],'source':{'publisher':'Dan Hendrycks et al.','url':'https://people.eecs.berkeley.edu/~hendrycks/data.tar','repository':'https://github.com/hendrycks/test','revision':MMLU_REV,'license':'MIT','licenseEvidence':'licenses/MMLU-LICENSE.txt','attribution':'Hendrycks et al., Measuring Massive Multitask Language Understanding, ICLR 2021','datasetCard':'https://huggingface.co/datasets/cais/mmlu','sourceRefs':[source_ref(file,row,line_no)],'archivePath':str(file.relative_to(ROOT/'raw/mmlu-highschool'))},'rawSourceRecord':row,'readiness':{'permissionChecked':True,'sourceHighSchoolExplicit':True,'schemaAndAnswerKeyChecked':True,'teacherReviewed':False,'chinaCurriculumMapped':False,'translatedToChinese':False,'eligibleForDefaultAssembly':False},'status':'ready_for_source_import_not_classroom_reviewed'}
            mmlu.append(q)
    stats['mmluNineSubjectValidRowsBeforeDedup']=len(mmlu)
    existing,baseline_counts=existing_fingerprints();buckets=collections.defaultdict(list);dedup=[];final=[]
    for q in collapsed+mmlu:
        fp=fingerprint(q);q['contentFingerprint']=fp
        if full_fingerprint(q['stem'],q['options']) in existing:
            dedup.append({'discardedId':q['id'],'reason':'matches existing GAOKAO/Siyavula full-question fingerprint'});continue
        buckets[fp].append(q)
    for fp,variants in buckets.items():
        if len({norm(q['answer']['text']) for q in variants})>1:
            for q in variants:hold.append({'id':q['id'],'reason':'duplicate content has conflicting answer; entire group withheld','conflictIds':[v['id'] for v in variants],'record':q})
            continue
        previous=variants[0];final.append(previous)
        for q in variants[1:]:dedup.append({'discardedId':q['id'],'keptId':previous['id'],'reason':'normalized stem and unordered option text match'})
    for subject in SUBJECT_ZH:jsonl(f'ready/highschool-{subject}.jsonl',[q for q in final if q['subject']==subject])
    jsonl('ready/combined-highschool-nine-subjects.jsonl',final)
    jsonl('research-only/adjacent-highschool-subjects.jsonl',adjacent+[{'sourceDataset':'MMLU','status':'adjacent_subject_not_counted_toward_nine','license':'MIT',**x} for x in mmlu_adjacent])
    jsonl('research-only/schema-or-material-hold.jsonl',hold);jsonl('dedup-discarded.jsonl',dedup)
    final_subjects=collections.Counter(q['subject'] for q in final);dataset_counts=collections.Counter(q['sourceDataset'] for q in final)
    manifest={'schema':'source-expansion-manifest/1','collectedAt':'2026-11-02','scope':'New source data only; app untouched; no publishing','readyMeaning':'High-school scope, declared data permission and machine-readable answer structure checked. Not teacher-reviewed, translated, China-curriculum-mapped or enabled for default assembly.','targetPerSubject':20000,'readyRecords':len(final),'bySubject':{s:{'name':SUBJECT_ZH[s],'ready':final_subjects[s],'gapTo20000':max(0,20000-final_subjects[s]),'file':f'ready/highschool-{s}.jsonl'} for s in SUBJECT_ZH},'byDataset':dict(dataset_counts),'examsRawSubjects':dict(raw_subjects),'pipelineCounts':dict(stats),'adjacentSubjectRecordsNotCounted':len(adjacent)+len(mmlu_adjacent),'schemaOrMaterialHoldRecords':len(hold),'exactDedupDiscarded':len(dedup),'baselineChecked':baseline_counts,'dedupMethods':['same EXAMS source ID','official EXAMS parallel-translation mapping, one canonical item per group','NFKC+casefold+whitespace stem and unordered option text SHA256','existing GAOKAO2776 and Siyavula7406 full-question text fingerprint'],'semanticDedupComplete':False,'explanationProvided':False,'allSourceLanguagesAreNonChinese':True,'defaultAssemblyEnabled':False,'warnings':['No subject has reached20000 under these filters','Translation variants are not counted as additional questions','Foreign government/citizenship/history content does not imply equivalence to Chinese political/history curriculum','C-Eval/CMMLU remain noncommercial research-only; GeoCQA unavailable/permission not established','Shared material embedded in original stem is kept intact; no standalone subquestion reconstruction is claimed']}
    dump('manifest.json',manifest)
    print(json.dumps({'ready':len(final),'bySubject':final_subjects,'byDataset':dataset_counts,'counts':stats,'hold':len(hold),'exactDedup':len(dedup),'adjacent':manifest['adjacentSubjectRecordsNotCounted']},ensure_ascii=False))

if __name__=='__main__':normalize()
