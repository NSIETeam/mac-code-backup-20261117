import json,re,hashlib,pathlib,html,urllib.parse,xml.etree.ElementTree as ET,collections
from html.parser import HTMLParser
ROOT=pathlib.Path(__file__).parent;RAW=ROOT/'raw';OUT=ROOT/'normalized';OUT.mkdir(exist_ok=True)
class Text(HTMLParser):
 def __init__(self):super().__init__();self.parts=[]
 def handle_data(self,data):self.parts.append(data)
 def handle_starttag(self,tag,attrs):
  if tag in ['div','p','br','li']:self.parts.append('\n')
  if tag=='img':self.parts.append('[图像]')
def plaintext(value):
 t=Text();t.feed(value);return re.sub(r'[ \t]+',' ',html.unescape(''.join(t.parts))).strip()
def mathtex(xml):
 def node(x):
  name=x.tag.split('}')[-1];c=[node(v) for v in x];text=(x.text or '').strip()
  if name=='mfrac' and len(c)==2:return '\\frac{'+c[0]+'}{'+c[1]+'}'
  if name=='msup' and len(c)==2:return '{'+c[0]+'}^{'+c[1]+'}'
  if name=='msub' and len(c)==2:return '{'+c[0]+'}_{'+c[1]+'}'
  if name=='msubsup' and len(c)==3:return '{'+c[0]+'}_{'+c[1]+'}^{'+c[2]+'}'
  if name=='msqrt':return '\\sqrt{'+''.join(c)+'}'
  if name=='mroot' and len(c)==2:return '\\sqrt['+c[1]+']{'+c[0]+'}'
  if name=='mfenced':return x.get('open','(')+x.get('separators',',').join(c)+x.get('close',')')
  if name=='mtr':return '&'.join(c)
  if name=='mtable':return '\\begin{matrix}'+r'\\'.join(c)+'\\end{matrix}'
  if name in ['munder','mover','munderover','menclose','mmultiscripts']:raise ValueError('requires MathML review: '+name)
  return text+''.join(c)
 xml=re.sub(r'&([A-Za-z][A-Za-z0-9]+);',lambda m: ''.join('&#'+str(ord(c))+';' for c in html.unescape(m[0])) if html.unescape(m[0])!=m[0] else m[0],xml)
 return node(ET.fromstring(xml))
def normalizehtml(value):
 flags=[];blocks=[]
 def replace(m):
  original=m[0];blocks.append(original)
  try:return '$'+mathtex(original)+'$'
  except Exception:flags.append('mathml_conversion_needs_review');return '[MathML '+str(len(blocks))+']'
 body=re.split(r'<div class="exam-foot"',value)[0]
 body=re.sub(r'<math\b.*?</math>',replace,body,flags=re.S)
 return plaintext(body),blocks,flags

def fp(subject,stem):
 norm=re.sub(r'\s+','',re.sub(r'^\s*\d+[、.．]\s*','',stem));return hashlib.sha256((subject+'|'+norm).encode()).hexdigest()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(name,rows):
 seen=set();unique=[];duplicates=0
 for row in rows:
  row['contentHash']=fp(row['subject'],row['stem'])
  if row['contentHash'] in seen:duplicates+=1;continue
  seen.add(row['contentHash']);unique.append(row)
 dest=OUT/(name+'.jsonl')
 with dest.open('w') as f:
  for row in unique:f.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
 return {'path':str(dest.relative_to(ROOT)),'rawRows':len(rows),'deduplicatedRows':len(unique),'duplicatesRemoved':duplicates,'sha256':sha(dest),'subjects':dict(collections.Counter(x['subject'] for x in unique)),'stages':dict(collections.Counter(x['stage'] for x in unique)),'readiness':dict(collections.Counter(x['readiness'] for x in unique)),'qualityFlags':dict(collections.Counter(flag for x in unique for flag in x['qualityFlags']))}
manifest={'schema':'siyue.source-expansion/v1','date':'2026-11-02','scope':'Math/Physics/Chemistry; source corpus, not generated variants','targetPerSubject':20000,'publicDeployments':0,'datasets':[]}
# MathAutoTag: all high-school per official repository, but only problem HTML and knowledge tags.
knowledge=json.loads((RAW/'mathdata-knowledge.json').read_text());byid={x['id']:x for x in knowledge};byuuid={x['uuid']:x for x in knowledge};tags=collections.defaultdict(list)
def path(x):
 out=[];seen=set()
 while x and x['uuid'] not in seen:
  seen.add(x['uuid']);out.append(x['name']);x=byuuid.get(x.get('parent_uuid'))
 return list(reversed(out))
for x in json.loads((RAW/'mathdata-tags.json').read_text()):
 if x['label_id'] in byid:tags[x['qid']].append(path(byid[x['label_id']]))
rows=[]
for x in json.loads((RAW/'mathdata-content.json').read_text()):
 stem,blocks,flags=normalizehtml(x['text']);links=re.findall(r'<a[^>]+href="([^"]+)"',x['text']);images=re.findall(r'<img[^>]+src="([^"]+)"',x['text']);remote=[urllib.parse.urlunsplit((*urllib.parse.urlsplit(u)[:3],'','')) for u in images]
 flags+=['missing_answer','missing_analysis','question_rights_unconfirmed']
 if images or re.search('如图|下图|图中|图示',stem):flags.append('diagram_not_downloaded')
 rows.append({'schema':'siyue.source-question/v1','id':'mathdata-'+str(x['id']),'subject':'数学','stage':'高中','stageEvidence':'Official repository description K12 High School Math Problems Dataset','stem':stem,'answer':'','analysis':'','knowledgePaths':tags[x['id']],'mathML':blocks,'assets':{'remoteImages':remote,'localImages':[]},'source':{'repository':'https://github.com/MathAutoTag/mathdata','revision':'e72248b3547ffda0bde23dca66660ba136d8fd06','originalId':x['id'],'questionUrl':links[0] if links else ''},'rights':{'license':'NOASSERTION','decision':'research-staging-only','evidence':'raw/mathdata-readme.txt; official tree has no LICENSE; problem HTML links to zujuan.com'},'qualityFlags':sorted(set(flags)),'readiness':'research-staging'} )
manifest['datasets'].append({'name':'MathAutoTag/mathdata','evidence':'raw/mathdata-readme.txt',**write('mathdata-highschool-staging',rows)})
# TAL: explicit original dataset_name prefix, not keyword guessing.
rows=[];excluded=collections.Counter()
for filename in ['TAL-cn-train.jsonl','TAL-cn-test.jsonl']:
 for line in (RAW/filename).open():
  x=json.loads(line);prefix=x.get('dataset_name','')
  if not prefix.startswith('high_math_'):excluded[prefix]+=1;continue
  options=[{'label':str(o.get('aoVal','')),'content':str(o.get('content',''))} for group in x.get('answer_option_list',[]) for o in group]
  stem=x.get('problem','').strip()+'\n'+'\n'.join(o['label']+'. '+o['content'] for o in options)
  analysis='\n'.join(x.get('answer_analysis') or []);answer=str(x.get('answer_value','')).strip();images=re.findall(r'<img[^>]+src=[\"\']([^\"\']+)',stem+analysis);flags=[]
  if not answer:flags.append('missing_answer')
  if not analysis:flags.append('missing_analysis')
  if images or re.search(r'如图|下图|图中|图示|\\includegraphics|<img',stem+analysis):flags.append('diagram_not_downloaded')
  if '[图片]' in stem or '[图像]' in stem:flags.append('diagram_not_downloaded')
  flags.append('not_independently_answer_verified')
  blocked=any(f!='not_independently_answer_verified' for f in flags)
  rows.append({'schema':'siyue.source-question/v1','id':'tal-highschool-'+x['queId'],'subject':'数学','stage':'高中','stageEvidence':'dataset_name begins high_math_competition; '+prefix,'stem':stem,'answer':answer,'analysis':analysis,'options':options,'knowledgePaths':[r.split('->') for r in x.get('knowledge_point_routes',[])],'assets':{'remoteImages':images,'localImages':[]},'source':{'repository':'https://github.com/math-eval/TAL-SCQ5K','revision':'8a66d6e14c65ba002beda39747e6b7c43bd2d472','file':filename,'originalId':x['queId'],'competitionSources':x.get('competition_source_list',[])},'rights':{'license':'MIT','decision':'candidate-after-quality-review' if not blocked else 'hold-missing-content','evidence':'raw/TAL-readme.txt Licensing Information explicitly licenses dataset MIT; original competition provenance retained'},'qualityFlags':sorted(set(flags)),'readiness':'licensed-text-candidate' if not blocked else 'content-incomplete'})
manifest['datasets'].append({'name':'TAL-SCQ5K-CN high-school subset','evidence':'raw/TAL-readme.txt','excludedNonHighschool':dict(excluded),**write('tal-highschool',rows)})
# CMMaTH publicly released sample: grade tags explicit, images unavailable in repository.
rows=[];excluded=0
for x in json.loads((RAW/'CMMaTH-sample.json').read_text()):
 if x.get('grade_id') not in [10,11,12]:excluded+=1;continue
 flags=['question_rights_unconfirmed','not_independently_answer_verified'];images=x.get('image') or []
 if images:flags.append('missing_image_files')
 if not str(x.get('analysis','')).strip():flags.append('missing_analysis')
 rows.append({'schema':'siyue.source-question/v1','id':'cmmath-'+x['problem_id'],'subject':'数学','stage':'高中','grade':x['grade_id'],'stageEvidence':'grade_id 10/11/12; grade_group 3','stem':x['question'].strip(),'answer':str(x.get('answer','')).strip(),'analysis':str(x.get('analysis','')).strip(),'knowledgePaths':[[x.get('knowledge_point','')]],'assets':{'imageFilenames':images,'localImages':[]},'source':{'repository':'https://github.com/zzli2022/CMMaTH','revision':'ecd558feab7906cfee4154647febdba73a286a05','originalId':x['problem_id']},'rights':{'license':'NOASSERTION','decision':'research-staging-only','evidence':'raw/CMMaTH-readme.txt; repository has no LICENSE and only released sample'},'qualityFlags':flags,'readiness':'research-staging'})
manifest['datasets'].append({'name':'CMMaTH public sample high-school subset','excludedNonHighschool':excluded,'evidence':'raw/CMMaTH-readme.txt',**write('cmmath-highschool-staging',rows)})
# LiveK12Bench uses original Chinese split only, never double-count aligned English translations.
rows=[];biology=0;livefiles=sorted((RAW/'livek12-pages').glob('*.json'));available=0
for file in livefiles:
 page=json.loads(file.read_text());available+=len(page['rows'])
 for item in page['rows']:
  x=item['row'];subject={'math':'数学','physics':'物理','chemistry':'化学'}.get(x['subject'])
  if not subject:biology+=1;continue
  flags=['noncommercial_license','not_independently_answer_verified'];images=x.get('images') or []
  if images:flags.append('referenced_images_not_downloaded')
  if not x.get('answer') or any(a is None or not str(a).strip() for a in x.get('answer',[])):flags.append('missing_answer_or_subanswer')
  if not x.get('solution'):flags.append('missing_analysis')
  if item.get('truncated_cells'):flags.append('truncated_cells')
  if x['id']=='zh_2603_math_0003':flags+=['known_wrong_reference_answer','duplicate_choice_content']
  rows.append({'schema':'siyue.source-question/v1','id':x['id'],'subject':subject,'stage':'高中','stageEvidence':'Official dataset card: real Chinese high-school exams','stem':x['question'],'answer':'；'.join(str(a) if a is not None else '[缺少子问答案]' for a in x['answer']),'analysis':x['solution'],'knowledgePaths':[str(x.get('knowledge_points') or '').split('；')],'assets':{'remoteImages':[{k:v for k,v in i.items() if k!='src'} for i in images],'remoteImageCount':len(images),'localImages':[]},'source':{'repository':'https://huggingface.co/datasets/Shawn-wxh/livek12bench','revision':'66c99535759bd4c470126dd1bfc4f5958fcbe94d','split':file.name.rsplit('-',1)[0],'originalId':x['id'],'row':item['row_idx']},'rights':{'license':'CC-BY-NC-4.0','decision':'research-staging-only','evidence':'raw/LiveK12Bench-readme.txt explicitly academic research only; underlying exam rights remain with owners'},'qualityFlags':flags,'readiness':'research-staging'})
manifest['datasets'].append({'name':'LiveK12Bench Chinese STEM subset','expectedChineseRows':2114,'downloadedChineseRows':available,'excludedBiology':biology,'evidence':'raw/LiveK12Bench-readme.txt',**write('livek12-highschool-staging',rows)})
manifest['notDownloaded']=[{'name':'K12Vista','officialRepository':'https://github.com/lichongod/K12Vista','officialDataset':'https://huggingface.co/datasets/lipku1999/K12-Vista','revision':'1af2bea24aa8f655fa37d9d061840a097bd2d440','reason':'No LICENSE/card; 501280262-byte JSONL download interrupted by transport, dataset viewer rows endpoint HTTP500. Not counted as collected. Evidence raw/K12Vista-api.json and raw/K12Vista-readme.txt.'},{'name':'CSM','reason':'Ambiguous acronym; no unambiguous official high-school question dataset located. Not counted.'}]
# Cross-source textual deduplication; preserve aliases, prefer licensed TAL copy.
combined=[];global_seen={};aliases=0
for filename in ['tal-highschool.jsonl','livek12-highschool-staging.jsonl','cmmath-highschool-staging.jsonl','mathdata-highschool-staging.jsonl']:
 for line in (OUT/filename).open():
  row=json.loads(line);key=row['contentHash']
  if key in global_seen:
   global_seen[key].setdefault('sourceAliases',[]).append(row['source']);aliases+=1
  else:global_seen[key]=row;combined.append(row)
manifest['combined']=write('all-stem-unique',combined)
manifest['combined']['crossSourceDuplicatesRemoved']=aliases
manifest['method']='Exact normalized text/MathML-to-LaTeX fingerprints, not semantic equivalence. Original formulas retained as MathML; conversion failures flagged. No primary/junior-high samples or English parallel translations counted.'
manifest['readinessMeaning']='licensed-text-candidate means dataset author explicitly granted MIT and no missing answer/analysis/figure reference was detected; correctness and underlying third-party question rights have not been independently certified. Research-staging rows must never enter public dist.'
manifest['rawFiles']=[{'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in RAW.glob('*') if p.is_file()]
(ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({d['name']:{k:d[k] for k in ['rawRows','deduplicatedRows','subjects','readiness','qualityFlags']} for d in manifest['datasets']},ensure_ascii=False,indent=2))
