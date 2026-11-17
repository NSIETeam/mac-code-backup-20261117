#!/usr/bin/env python3
"""NCR author originals -> CC BY-SA 4.0 candidates; no app/dist writes."""
import collections,datetime,hashlib,json,pathlib,re,unicodedata
ROOT=pathlib.Path(__file__).resolve().parent
OUT=ROOT/'ready/ncr';OUT.mkdir(parents=True,exist_ok=True)
SOURCE='Native Chinese Reader (NCR)'
SOURCE_URL='https://sites.google.com/view/native-chinese-reader/'
LICENSE_URL='https://creativecommons.org/licenses/by-sa/4.0/'
AUTHORS='Shusheng Xu, Yichen Liu, Xiaoyu Yi, Siyuan Zhou, Huizi Li, Yi Wu'
ATTRIBUTION=f'Native Chinese Reader (NCR), {AUTHORS}, 2021, Haihua Institute for Frontier Information Technology; CC BY-SA 4.0. Source: {SOURCE_URL} Changes: shared-material IDs, explicit option fields, content deduplication and structured JSONL formatting.'
STYLE={'00':'现代文阅读','11':'文言文阅读','22':'古代诗歌阅读','33':'现代诗歌阅读'}
def sha(b):return hashlib.sha256(b).hexdigest()
def canonical(s):return re.sub(r'\s+','',unicodedata.normalize('NFKC',s))
def key(article,stem,opts):
 # Include complete shared material and ordered options. Do not merge distinct passages.
 return sha(json.dumps([canonical(s) for s in [article,stem,*opts]],ensure_ascii=False,separators=(',',':')).encode())
def clean_option(option,index):
 s=option.strip();label=chr(65+index)
 # Recognize source's explicit A/B/C/D labels, including compact Chinese labels.
 m=re.match(r'^([A-D])(?:\s*[.．、:：)）]|\s+|(?=[\u3400-\u9fff“”‘’（(「《]))',s)
 if m:
  if m[1]!=label:raise ValueError('source option label does not match position')
  return s[m.end():].strip()
 return s

def possible_flags(question,article,opts):
 joined='\n'.join([question,article,*opts]);flags=[]
 if re.search(r'加点|画线|划线|加粗|下划线|着重号|横线|波浪线|虚线',joined):flags.append('formatting_reference_unverified')
 if re.search(r'如图|下图|上图|图中|示意图|图示|下表|表格|表中|图[一二三四五六七八九1-9]|<img\b|!\[',joined,re.I):flags.append('image_or_table_reference_unverified')
 if re.search(r'上下文|联系上文|联系下文|前文|后文|上文|下文',question):flags.append('context_reference_requires_material')
 return flags

records={};materials={};blocked=set();rejected=[];duplicates=0;conflicts=0;raw_questions=0;raw_docs=0;by_split=collections.Counter();downloads=json.loads((ROOT/'raw/NCR.download.json').read_text());sourcefiles=[]
for split,filename in [('train','train_2'),('validation','dev_2'),('test','test_2')]:
 p=ROOT/'raw'/('NCR-'+filename+'.json');raw=p.read_bytes();docs=json.loads(raw);raw_docs+=len(docs)
 sourcefile=next(x for x in downloads['files'] if x.get('file')==str(p.relative_to(ROOT)))
 raw_sha=sha(raw)
 assert sourcefile['sha256']==raw_sha
 sourcefiles.append(sourcefile)
 for doc_index,doc in enumerate(docs):
  content=doc.get('Content','')
  if not isinstance(content,str) or not content.strip():
   rejected.append({'split':split,'document_index':doc_index,'reason':'missing_shared_material'});continue
  content=content.strip();mid='ncr-material-'+sha(content.encode())[:24]
  style=STYLE.get(str(doc.get('Type','')),'阅读理解（原件未标注文体）')
  materials.setdefault(mid,{'id':mid,'materialId':mid,'subject':'语文','stage':'高中','materialText':content,'source':SOURCE,'sourceUrl':SOURCE_URL,'license':'CC-BY-SA-4.0','licenseUrl':LICENSE_URL,'attribution':ATTRIBUTION,'sourceDocumentIds':[],'sourceStyle':doc.get('Type'),'sourceStyleLabel':style})
  materials[mid]['sourceDocumentIds'].append(f"{split}/{doc.get('ID',doc_index)}")
  for question_index,q in enumerate(doc.get('Questions',[])):
   raw_questions+=1;by_split[split]+=1
   ref={'split':split,'document_id':str(doc.get('ID',doc_index)),'source_q_id':str(q.get('Q_id',question_index)),'document_index':doc_index,'question_index':question_index,'raw_file':str(p.relative_to(ROOT)),'raw_sha256':raw_sha}
   try:
    stem=q['Question'].strip();choices=q['Choices'];answer=q['Answer'].strip().upper()
    if not stem or not isinstance(choices,list) or not 2<=len(choices)<=4 or any(not isinstance(x,str) or not x.strip() for x in choices):raise ValueError('invalid stem or 2-to-4 option array')
    opts=[clean_option(s,i) for i,s in enumerate(choices)]
    labels=[chr(65+i) for i in range(len(opts))]
    if answer not in labels or any(not s for s in opts):raise ValueError('answer outside original options or blank option')
   except (KeyError,AttributeError,TypeError,ValueError) as e:
    rejected.append({'source':ref,'reason':str(e)});continue
   fingerprint=key(content,stem,opts)
   if fingerprint in blocked:
    rejected.append({'source':ref,'reason':'conflicting_answer_cluster'});continue
   if fingerprint in records:
    if records[fingerprint]['answerKey']!=answer:
     conflicts+=1;blocked.add(fingerprint);rejected.append({'sources':records[fingerprint]['sourceRecords']+[ref],'reason':'same_content_different_answers'});del records[fingerprint]
    else:duplicates+=1;records[fingerprint]['sourceRecords'].append(ref)
    continue
   option_objects=[{'label':label,'text':s,'sourceText':choices[i]} for i,(label,s) in enumerate(zip(labels,opts))]
   formatted_stem=stem+'\n'+'\n'.join(label+'. '+s for label,s in zip(labels,opts))
   sourceqid=f"NCR/{split}/{doc.get('ID',doc_index)}/{q.get('Q_id',question_index)}"
   flags=possible_flags(stem,content,opts)
   records[fingerprint]={'id':'ncr-'+fingerprint[:24],'subject':'语文','stage':'高中','type':'选择题','stem':formatted_stem,'question':stem,'options':option_objects,'answer':answer+'. '+opts[labels.index(answer)],'answerKey':answer,'analysis':'','analysisStatus':'source_provides_no_explanation','materialId':mid,'materialText':content,'source':SOURCE,'sourceUrl':SOURCE_URL,'license':'CC-BY-SA-4.0','attribution':ATTRIBUTION,'licenseUrl':LICENSE_URL,'sourceQuestionId':sourceqid,'sourceRecords':[ref],'sourceStyle':doc.get('Type'),'sourceStyleLabel':style,'knowledgePath':['语文','阅读理解',style],'contentSha256':fingerprint,'requiresSharedMaterial':True,'reviewFlags':flags,'teacherReviewed':False,'validationStatus':'structure_and_answer_label_checked','licenseQualificationOriginal':'for general research purposes','releaseStatus':'ready_candidate_attribution_sharealike_required'}
used={r['materialId'] for r in records.values()};materials={k:v for k,v in materials.items() if k in used}
flags=collections.Counter(f for r in records.values() for f in r['reviewFlags']);optioncounts=collections.Counter(len(r['options']) for r in records.values());uniquesplits=collections.Counter(r['sourceRecords'][0]['split'] for r in records.values())
def jsonl(p,items):
 with p.open('w') as out:
  for item in items:out.write(json.dumps(item,ensure_ascii=False,separators=(',',':'))+'\n')
jsonl(OUT/'questions.jsonl',records.values());jsonl(OUT/'materials.jsonl',materials.values());jsonl(OUT/'rejected.jsonl',rejected)
(OUT/'ATTRIBUTION.txt').write_text(ATTRIBUTION+'\n\nThe dataset authors declare CC BY-SA 4.0 in the official NeurIPS supplementary materials, including the phrase "for general research purposes". This is preserved as evidence, not replaced by a third-party mirror license. The published declaration does not say "only" and states no third-party IP restrictions. Structured adaptations are provided under CC BY-SA 4.0. Retain authorship, license links, and this change notice when redistributing.\n\nThese are source-answered questions, not teacher-reviewed questions. Formatting/image-reference flags are heuristic review prompts.\n')
outputs=[{'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in [OUT/'questions.jsonl',OUT/'materials.jsonl',OUT/'rejected.jsonl',OUT/'ATTRIBUTION.txt']]
manifest={'schema_version':'1.0','dataset':SOURCE,'normalized_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'subject':'语文','stage':'高中','counts':{'raw_documents':raw_docs,'raw_questions':raw_questions,'raw_questions_by_split':dict(by_split),'unique_valid_questions':len(records),'unique_shared_materials':len(materials),'duplicates_merged':duplicates,'conflicting_answer_clusters_quarantined':conflicts,'rejected_records':len(rejected),'ready_candidates':len(records),'without_extra_review_flags':sum(not r['reviewFlags'] for r in records.values()),'review_flag_counts':dict(flags),'original_option_counts':dict(optioncounts),'unique_questions_by_first_split':dict(uniquesplits)},'source_files':sourcefiles,'stage_evidence':{'scope':'All NCR source questions are from Chinese-course examinations in Chinese high schools; no middle/primary-school augmentation data imported.','official_abstract_url':'https://datasets-benchmarks-proceedings.neurips.cc/paper/2021/hash/140f6969d5213fd0ece03148e62e461e-Abstract-round2.html','official_supplement_url':'https://datasets-benchmarks-proceedings.neurips.cc/paper_files/paper/2021/file/140f6969d5213fd0ece03148e62e461e-Supplemental-round2.zip','evidence_files':['evidence/ncr-official-abstract.html','evidence/ncr-official-supplemental.zip','evidence/ncr-official-supplemental.txt'],'source_count_note':'Abstract says 8390 documents; downloaded official split files total 8388 (6315+1000+1073). All three question totals match the published 20477. Actual original files control our counts.'},'license':{'id':'CC-BY-SA-4.0','url':LICENSE_URL,'declared_by':'Dataset authors in official NeurIPS supplementary materials','original_qualification':'for general research purposes','interpretation':'The authors name the standard CC BY-SA 4.0 license, do not say research-only, and explicitly state no third-party IP restrictions. CC official deed permits sharing/adaptation for any purpose subject to attribution and share-alike. Preserve the original qualification and evidence.','obligations':['Retain authors, dataset title, source URL and license URL','Indicate structured-format and deduplication changes','Distribute adaptations under CC BY-SA 4.0','Do not imply author endorsement'],'evidence':['evidence/ncr-official-supplemental.txt lines 20 and 423','evidence/ncr-official-supplemental.txt lines 424-428','evidence/cc-by-sa-4.0-deed.html'],'teacher_review_claim':False},'normalization':['Preserve original material text and original choices; remove recognized option-label prefixes only in display text','Retain original 2-to-4 options, source answer label, full option answer text; do not pad fabricated options','Shared material emitted both by materialId and materialText; material file supports storage without duplication','Dedup by NFKC and whitespace-insensitive complete passage+question+ordered options; this is content dedup, not embedding/LLM paraphrase dedup','All source records retain split/document ID/question ID and original-file hash','Source has no explanations; analysis is empty, no generated teacher rationale','Mark heuristic formatting/image/table/context references; no claim that referenced formatting or diagrams were verified'],'outputs':outputs}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
assert all(r['materialId'] in materials for r in records.values())
assert len({r['id'] for r in records.values()})==len(records)
assert all(2<=len(r['options'])<=4 and r['answerKey'] in [o['label'] for o in r['options']] for r in records.values())
print(json.dumps(manifest['counts'],ensure_ascii=False))
