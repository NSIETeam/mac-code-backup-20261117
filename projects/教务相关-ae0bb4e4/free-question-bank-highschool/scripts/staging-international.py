"""Adapt licensed international high-school records without classroom-ready claims."""
import collections,json,pathlib,shutil

SUBJECTS={'mathematics':'数学','chemistry':'化学','physics':'物理','biology':'生物','geography':'地理','history':'历史','politics':'政治','english':'英语','chinese':'语文'}

def adapt(original):
    q=dict(original);dataset=q['sourceDataset'];source_set=dataset.lower()
    assert dataset in ['EXAMS','MMLU']
    assert q['source']['license'] in ['CC-BY-SA-4.0','MIT']
    assert q['readiness']['permissionChecked'] and q['readiness']['sourceHighSchoolExplicit']
    assert not q['readiness']['eligibleForDefaultAssembly']
    refs=q['source'].get('sourceRefs',[]);ref=refs[0] if refs else {}
    source=dict(q['source'])
    source['book']=f"{dataset} · {q['sourceSubject']} · 高中原题"
    source['archiveHtmlPath']=source.get('archivePath') or ' :: '.join(str(x) for x in [ref.get('rawFile'),ref.get('archiveMember'),('record '+str(ref['rawLine'])) if ref.get('rawLine') else None] if x)
    source['licenseEvidence']=f'licenses/{dataset}-LICENSE.txt'
    q.update({'schemaVersion':'question-staging/1','sourceSet':source_set,'needsTranslation':True,
        'sourceGrade':q['sourceGrade'] if q['sourceGrade'] is not None else '高中未细分',
        'educationSystem':'International high-school source; China curriculum mapping pending',
        'chinaGrade':None,'chinaCurriculumMapped':False,'questionType':q['type'],
        'knowledgePath':[SUBJECTS[q['subject']],'外文高中科目（待课标映射）',f"{dataset} · {q['sourceSubject']}"],
        'stemPlainText':q['stem'],'sharedContextPlainText':[m.get('text','') if isinstance(m,dict) else str(m) for m in q.get('sharedMaterials',[])],
        'sourceSolutionPlainText':'','sourceSolutionHtml':None,'hasSourceSolution':False,
        'sourceAnswer':q['answer'],'answer':q['answer']['key'],'answerProvenance':'原数据答案，未作本次教学复核',
        'optionsOriginal':[f"{c['label']}. {c['text']}" for c in q['options']],
        'source':source,'status':'needs_translation_curriculum_review',
        'validation':{'formatParsed':True,'assetReferencesComplete':not q.get('assets'),
            'sourceSchoolLevelChecked':True,'mathematicallyVerified':False,'teacherReviewed':False,
            'chinaCurriculumMapped':False,'eligibleForDefaultAssembly':False}})
    return q

def load_records(path,out):
    if path.exists():
        rows=[json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        if not rows:raise ValueError('国际题源文件为空；拒绝覆盖已有国际资料。')
        return [adapt(q) for q in rows],path.parent.parent
    # A packaged standalone checkout can rebuild from its already-shipped immutable chunks.
    index_path=out/'staging-index.json'
    if index_path.exists():
        index=json.loads(index_path.read_text());wanted=[q for q in index.get('records',[]) if q.get('sourceSet') in ['exams','mmlu']]
        if wanted:
            wanted_ids={q['id'] for q in wanted};rows=[]
            for name in sorted({q['chunk'] for q in wanted}):
                rows.extend(q for q in json.loads((out/'staging-chunks'/name).read_text())['records'] if q['id'] in wanted_ids)
            assert len(rows)==len(wanted_ids),'已有国际资料分片不完整；拒绝重建覆盖。'
            return rows,None
    raise FileNotFoundError(f'缺少国际题源 {path}，且未找到已有国际资料分片；未改写索引。')

def copy_provenance(source_root,out):
    if source_root is None:return
    provenance=out/'staging-provenance';provenance.mkdir(parents=True,exist_ok=True)
    for dataset in ['EXAMS','MMLU']:
        target=provenance/'licenses'/f'{dataset}-LICENSE.txt';target.parent.mkdir(exist_ok=True)
        shutil.copy2(source_root/'licenses'/f'{dataset}-LICENSE.txt',target)
    shutil.copy2(source_root/'manifest.json',provenance/'international-source-manifest.json')
    for name in ['validation-report.json','original-files-manifest.json']:
        if (source_root/name).exists():shutil.copy2(source_root/name,provenance/('international-'+name))
    for name in ['sources.json','README.txt']:
        if (source_root/name).exists():shutil.copy2(source_root/name,provenance/('international-'+name))
    evidence=source_root/'licenses/MMLU-data-card-evidence.txt'
    if evidence.exists():shutil.copy2(evidence,provenance/'licenses'/evidence.name)

def summary(rows):return dict(collections.Counter(q['sourceSet'] for q in rows))
