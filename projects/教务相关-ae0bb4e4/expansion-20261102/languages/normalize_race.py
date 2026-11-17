#!/usr/bin/env python3
"""Normalize strictly RACE/high into LOCAL RESEARCH STAGING. Never writes dist."""
from pathlib import Path
import collections,datetime,hashlib,json,re,tarfile,unicodedata
ROOT=Path(__file__).resolve().parent
STAGE=ROOT/'staging/race-high';STAGE.mkdir(parents=True,exist_ok=True)
SOURCE='http://www.cs.cmu.edu/~glai1/data/race/RACE.tar.gz'
def sha(b):return hashlib.sha256(b).hexdigest()
def text(v):return unicodedata.normalize('NFKC',v).strip()
def fingerprint(article,stem,options):return sha(json.dumps([re.sub(r'\s+',' ',text(x)) for x in [article,stem,*options]],ensure_ascii=False,separators=(',',':')).encode())
archive=ROOT/'raw/RACE.tar.gz'
raw_sha=sha(archive.read_bytes())
materials={};questions={};blocked=set();rejects=[];duplicates=0;conflicts=0;raw_count=0;files=0;skipped_middle=0;splits=collections.Counter();raw_splits=collections.Counter();archive_paths=[]
with tarfile.open(archive,'r:gz') as tar:
 for member in tar:
  if not member.isfile() or not member.name.endswith('.txt'):continue
  match=re.search(r'(?:^|/)(train|dev|test)/high/[^/]+\.txt$',member.name)
  if not match:
   if '/middle/' in member.name:skipped_middle+=1
   continue
  files+=1;split=match.group(1);f=tar.extractfile(member)
  if not f:continue
  d=json.load(f);article=d.get('article','');qs=d.get('questions',[]);answers=d.get('answers',[]);opts=d.get('options',[])
  if not isinstance(article,str) or not article.strip() or not(len(qs)==len(answers)==len(opts)):
   rejects.append({'file':member.name,'reason':'invalid_article_or_array_alignment'});continue
  article=text(article);material_id='race-high-material-'+sha(article.encode())[:24]
  materials.setdefault(material_id,{'id':material_id,'dataset':'RACE-high','language':'en','stage':'高中','content':article,'source_files':[],'usage_status':'staging_noncommercial_research_only'})
  materials[material_id]['source_files'].append(member.name)
  for i,(stem,answer,options) in enumerate(zip(qs,answers,opts)):
   raw_count+=1;raw_splits[split]+=1
   ref={'dataset':'RACE','level':'high','split':split,'archive_member':member.name,'original_id':str(d.get('id','')),'question_index':i,'raw_archive_sha256':raw_sha}
   if not isinstance(stem,str) or not stem.strip() or not isinstance(options,list) or len(options)!=4 or any(not isinstance(o,str) or not o.strip() for o in options) or answer not in ['A','B','C','D']:
    rejects.append({'source':ref,'reason':'invalid_stem_options_or_answer'});continue
   stem=text(stem);options=[text(o) for o in options];key=fingerprint(article,stem,options)
   if key in blocked:
    rejects.append({'source':ref,'reason':'conflicting_answer_cluster'});continue
   if key in questions:
    if questions[key]['answer']!=answer:
     conflicts+=1;blocked.add(key)
     rejects.append({'sources':questions[key]['source_records']+[ref],'reason':'same_content_different_answers'});del questions[key]
    else:
     duplicates+=1;questions[key]['source_records'].append(ref)
    continue
   questions[key]={'id':'race-high-'+key[:24],'subject':'英语','stage':'高中','language':'en','type':'单选题','knowledge_path':['英语','阅读理解'],'material_id':material_id,'stem':stem,'options':[{'label':chr(65+j),'text':o} for j,o in enumerate(options)],'answer':answer,'answer_text':options[ord(answer)-65],'explanation':None,'explanation_status':'not_provided_by_source','source_records':[ref],'content_sha256':key,'license':'RACE custom non-commercial research only','usage_status':'staging_noncommercial_research_only','teaching_redistribution_ready':False}
used={q['material_id'] for q in questions.values()}
materials={k:v for k,v in materials.items() if k in used}
for q in questions.values():splits[q['source_records'][0]['split']]+=1

def jsonl(path,rows):
 with path.open('w') as f:
  for row in rows:f.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
jsonl(STAGE/'questions.jsonl',questions.values());jsonl(STAGE/'materials.jsonl',materials.values());jsonl(STAGE/'rejected.jsonl',rejects)
files_out=[]
for p in [STAGE/'questions.jsonl',STAGE/'materials.jsonl',STAGE/'rejected.jsonl']:
 files_out.append({'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())})
manifest={'schema_version':'1.0','dataset':'RACE-high','retrieved_normalized_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'subject':'英语','stage_filter':'ONLY archive paths /train|dev|test/high/*.txt; middle never normalized','raw_source':{'url':SOURCE,'path':'raw/RACE.tar.gz','bytes':archive.stat().st_size,'sha256':raw_sha},'counts':{'raw_high_passage_files':files,'raw_high_questions':raw_count,'raw_high_by_split':dict(raw_splits),'unique_valid_questions':len(questions),'unique_materials':len(materials),'duplicate_question_rows_merged':duplicates,'conflicting_answer_clusters_quarantined':conflicts,'rejected_records':len(rejects),'middle_files_ignored':skipped_middle,'ready_for_teaching_redistribution':0},'unique_by_first_split':dict(splits),'license':{'status':'noncommercial_research_only','ready':False,'official_evidence_url':'https://www.cs.cmu.edu/~glai1/data/race/','evidence_file':'evidence/race-site.js','note':'Author website expressly limits use to non-commercial research and does not own source Internet passages. Not permitted for deployment as a teaching question bank under current evidence.'},'outputs':files_out,'normalization':['NFKC and trim text; preserve original source options and answer','All records require nonempty shared material, stem, exactly four nonempty options and answer in A-D','Exact normalized content dedup includes article, stem and ordered options; conflicting answers quarantined','No generated answers or explanations; explanation is null','Question-to-material references validated; source archive member and question index preserved']}
(STAGE/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(manifest['counts'],ensure_ascii=False))
assert len({q['id'] for q in questions.values()})==len(questions)
assert all(q['material_id'] in materials for q in questions.values())
assert all(not q['teaching_redistribution_ready'] for q in questions.values())
