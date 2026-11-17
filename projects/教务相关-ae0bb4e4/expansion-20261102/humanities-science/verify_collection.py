#!/usr/bin/env python3
import collections,hashlib,json,pathlib,shutil
R=pathlib.Path(__file__).resolve().parent
def sha(b):return hashlib.sha256(b).hexdigest()
def blob(b):return hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
def save(name,x):(R/name).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
originals=[]
tree=json.loads((R/'provenance/exams-qa-tree.json').read_text())['tree'];expected={p['path']:p.get('sha') for p in tree}
pairs=[(p,'data/exams/cross-lingual/'+p.name.removeprefix('exams-cross-')) for p in sorted((R/'raw').glob('exams-cross-*.tar.gz'))]
pairs.extend((R/'raw'/a,b) for a,b in [('exams-test-complete.jsonl.tar.gz','data/exams/multilingual/test.jsonl.tar.gz'),('exams-parallel_questions.jsonl','data/exams/parallel_questions.jsonl'),('exams-README.md','README.md'),('exams-LICENSE','LICENSE'),('exams-dev.jsonl.tar.gz','data/exams/multilingual/dev.jsonl.tar.gz')])
for p,remote in pairs:
    b=p.read_bytes();assert blob(b)==expected[remote],p.name
    originals.append({'file':str(p.relative_to(R)),'url':f'https://github.com/mhardalov/exams-qa/blob/f859e665de6c370f6214ca5f36a34ace36ada6cb/{remote}','bytes':len(b),'sha256':sha(b),'gitBlobSha':blob(b),'eligibility':'EXAMS CC-BY-SA-4.0'})
p=R/'raw/mmlu-data.tar';b=p.read_bytes();assert sha(b)=='bec563ba4bac1d6aaf04141cd7d1605d7a5ca833e38f994051e818489592989b'
originals.append({'file':str(p.relative_to(R)),'url':'https://people.eecs.berkeley.edu/~hendrycks/data.tar','bytes':len(b),'sha256':sha(b),'eligibility':'MMLU MIT; only high_school_ tasks selected, not auxiliary_train'})
for key,repo,local in [('test','hendrycks/test','mmlu'),('ceval','hkust-nlp/ceval','ceval'),('cmmlu','haonan-li/CMMLU','cmmlu')]:
    mapping={x['path']:x.get('sha') for x in json.loads((R/f'provenance/{key}-tree.json').read_text())['tree']};rev=json.loads((R/f'provenance/{key}-commit.json').read_text())['sha']
    files=[(f'{local}-README.md','README.md')]+([('mmlu-LICENSE','LICENSE')] if key=='test' else [('ceval-LICENSE-DATA','LICENSE-DATA')] if key=='ceval' else [])
    for leaf,remote in files:
        p=R/'raw'/leaf;b=p.read_bytes();assert blob(b)==mapping[remote],leaf
        originals.append({'file':str(p.relative_to(R)),'url':f'https://github.com/{repo}/blob/{rev}/{remote}','bytes':len(b),'sha256':sha(b),'gitBlobSha':blob(b),'kind':'license/readme evidence, not question data'})
cmmlu=json.loads((R/'cmmlu-research-manifest.json').read_text())
for q in cmmlu['files']:
    assert sha((R/q['file']).read_bytes())==q['sha256'];originals.append(dict(q,eligibility='research-only NONCOMMERCIAL, not packaged into app'))
save('original-files-manifest.json',{'files':originals,'totalFiles':len(originals),'totalBytes':sum(x['bytes'] for x in originals),'note':'Only complete originals counted. Eligible GitHub bytes match pinned Git blob SHA. No TLS verification was disabled. Connector base64 used where appropriate.'})
for a,b in [('raw/exams-LICENSE','EXAMS-LICENSE.txt'),('raw/mmlu-LICENSE','MMLU-LICENSE.txt'),('raw/ceval-LICENSE-DATA','C-Eval-DATA-NONCOMMERCIAL.txt'),('raw/cmmlu-README.md','CMMLU-README-NONCOMMERCIAL.md')]:shutil.copy2(R/a,R/'licenses'/b)
rows=[json.loads(line) for line in (R/'ready/combined-highschool-nine-subjects.jsonl').read_text().splitlines()];manifest=json.loads((R/'manifest.json').read_text());assert len(rows)==12205==manifest['readyRecords']
assert len({q['id'] for q in rows})==12205;assert len({q['contentFingerprint'] for q in rows})==12205
for q in rows:
    assert q['stage']=='high_school' and q['source']['license'] in ['MIT','CC-BY-SA-4.0']
    assert q['readiness']['permissionChecked'] and q['readiness']['sourceHighSchoolExplicit']
    assert not q['readiness']['teacherReviewed'] and not q['readiness']['eligibleForDefaultAssembly']
    assert next(o['text'] for o in q['options'] if o['label']==q['answer']['key'])==q['answer']['text']
    assert q['explanation'] is None
    if q['sourceDataset']=='EXAMS':assert q['sourceGrade'] in [10,11,12] and q['stem']==q['rawSourceRecord']['question']['stem']
    else:assert q['sourceSubject'].startswith('high_school_') and q['stem']==q['rawSourceRecord'][0] and q['sourceGrade'] is None
    for ref in q['source']['sourceRefs']:assert (R/ref['rawFile']).exists()
report={'status':'passed','readyRecords':12205,'byDataset':dict(collections.Counter(q['sourceDataset'] for q in rows)),'bySubject':dict(collections.Counter(q['subjectZh'] for q in rows)),'rawOriginalFilesVerified':len(originals),'examsExplicitGrades':dict(collections.Counter(q['sourceGrade'] for q in rows if q['sourceDataset']=='EXAMS')),'cmmluResearchOnlyRecords':sum(cmmlu['counts'].values()),'noncommercialQuestionDataPackaged':0,'newQuestionTypeCounts':{'single_choice':12205},'allReadyExplanationsMissingAndNotInvented':True,'allRowsHaveValidReferenceAnswerOption':True,'allCanonicalFingerprintsUnique':True,'conflictingAnswerGroupsWithheld':True,'sourceStemAndEmbeddedContextPreserved':True,'existingBaselineRecordsChecked':10182,'teacherReviewClaimed':False,'chinaCurriculumMappingClaimed':False,'semanticDedupComplete':False,'defaultAssemblyEnabled':False,'notVerified':['Full semantic deduplication','Manual teacher answer review','Chinese translation and Chinese curriculum equivalence','Each underlying exam copyright independently re-cleared; rights basis is publisher dataset declaration']}
save('validation-report.json',report)
print(json.dumps({'ready':12205,'verifiedOriginals':len(originals),'originalBytes':sum(x['bytes'] for x in originals)},ensure_ascii=False))
