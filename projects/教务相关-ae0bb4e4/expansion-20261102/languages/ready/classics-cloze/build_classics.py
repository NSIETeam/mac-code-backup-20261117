#!/usr/bin/env python3
"""Create adjacent-clause cloze exercises from cached public-domain classical works."""
from html.parser import HTMLParser
from pathlib import Path
import collections,datetime,hashlib,json,re,urllib.parse
ROOT=Path(__file__).resolve().parent
PUNCT='，。；！？?!;,.'
IGNORE={'君不见','岑夫子','丹丘生','将进酒','杯莫停','噫吁嚱','噫吁戏','呜呼','嗟乎','悲夫','已矣乎','归去来兮'}
CONFIG={
 'quanxue':('君子曰','用心躁也',18),
 'shishuo':('古之学者必有师','作《师说》以贻之',18),
 'chibifu':('壬戌之秋','不知东方之既白',18),
 'epanggongfu':('六王毕','亦使后人而复哀后人也',20),
 'pipaxing':('浔阳江头夜送客','江州司马青衫湿',24),
 'shudaonan':('噫吁嚱','侧身西望长咨嗟',14),
 'qiangjinjiu':('君不见','同销万古愁',10),
 'lisao':('帝高阳之苗裔兮','吾将从彭咸之所居',14),
 'lantingjixu':('永和九年','亦将有感于斯文',16),
 'guiqulaixi':('归去来兮，田园将芜胡不归','乐夫天命复奚疑',16),
 'chenqingbiao':('臣密言','谨拜表以闻',18),
 'denggao':('风急天高猿啸哀','潦倒新停浊酒杯',4),
 'jinse':('锦瑟无端五十弦','只是当时已惘然',4),
 'chunjianghuayueye':('春江潮水连海平','落月摇情满江树',14),
}
class PlainOriginal(HTMLParser):
 def __init__(self):super().__init__(convert_charrefs=True);self.stack=[];self.buf=[]
 def handle_starttag(self,tag,attrs):
  attrs=dict(attrs);classes=set(attrs.get('class','').split());skip=(self.stack[-1][1] if self.stack else False) or tag in {'script','style','sup'} or bool(classes & {'variant-tooltip','reference','reflist','mw-editsection'})
  if tag in {'br','hr'} and not skip:self.buf.append('\n')
  if tag not in {'br','hr','img','link','meta','input','wbr','source'}:
   self.stack.append((tag,skip))
   if tag in {'p','div','tr','li','h2','h3'} and not skip:self.buf.append('\n')
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:
    skip=self.stack[i][1];self.stack=self.stack[:i]
    if tag in {'p','div','tr','li','h2','h3'} and not skip:self.buf.append('\n')
    break
 def handle_data(self,data):
  if not self.stack or not self.stack[-1][1]:self.buf.append(data)
def sha(b):return hashlib.sha256(b).hexdigest()
def clean(html):
 p=PlainOriginal();p.feed(html);text=''.join(p.buf)
 # Bracketed variant readings/annotations are not part of the exercise source.
 text=re.sub(r'〈[^〈〉]*〉','',text)
 return re.sub(r'\s+','',text).replace('[编辑]','')
def extract(full,start,end):
 a=full.find(start)
 if a<0:raise ValueError('start anchor missing: '+start)
 b=full.find(end,a)
 if b<0:raise ValueError('end anchor missing: '+end)
 b+=len(end)
 while b<len(full) and full[b] in PUNCT:b+=1
 return full[a:b]
def clauses(text,base=0):
 result=[]
 for m in re.finditer(r'[^，。；！？?!;,.]+[，。；！？?!;,.]*',text):
  raw=m.group();clause=raw.rstrip(PUNCT)
  result.append({'text':clause,'start':base+m.start(),'end':base+m.start()+len(clause),'punctuation':raw[len(clause):]})
 return result
def valid(c):
 t=c['text'];chars=re.findall(r'[\u3400-\u9fff]',t)
 return 3<=len(chars)<=40 and t not in IGNORE and not re.search(r'[“”‘’「」『』"\[\]〈〉（）()\n]',t) and not re.search(r'一作|又作|注释|^第[一二三四五六七八九十]+',t)
def jsonl(path,rows):
 with path.open('w') as f:
  for row in rows:f.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')

sources=json.loads((ROOT/'source-index.json').read_text());questions=[];source_records=[];failures=[];answer_keys=set();pair_keys=set();by_work={}
for source in sources:
 slug=source['slug']
 if 'error' in source:failures.append({'slug':slug,'error':source['error']});continue
 raw_path=ROOT/source['raw_file'];data=json.loads(raw_path.read_text());html=data['parse']['text']['*'];full=clean(html)
 start,end,quota=CONFIG[slug]
 try:body=extract(full,start,end)
 except ValueError as e:failures.append({'slug':slug,'error':str(e)});continue
 ranges=[(0,len(body))]
 if slug=='lisao':
  ranges=[]
  for a,b in [('帝高阳之苗裔兮','来吾道夫先路'),('长太息以掩涕兮','岂余心之可惩')]:
   try:
    part=extract(body,a,b);offset=body.find(part);ranges.append((offset,offset+len(part)))
   except ValueError as e:failures.append({'slug':slug,'range_error':str(e)})
  if not ranges:continue
 source_path=ROOT/'source-cache'/(slug+'.source.txt');source_path.write_text(body)
 body_hash=sha(body.encode());source_id='classics-'+slug+'-'+body_hash[:12]
 candidates=[]
 for lo,hi in ranges:
  cs=clauses(body[lo:hi],lo);i=0
  while i+1<len(cs):
   left,right=cs[i],cs[i+1]
   between=body[left['end']:right['start']]
   if valid(left) and valid(right) and re.fullmatch('[，；,;]+',between):
    candidates.append((left,right));i+=2
   else:i+=1
 # Evenly sample actual distinct adjacent-clause pairs; no forward/reverse duplication.
 chosen=candidates if len(candidates)<=quota else [candidates[round(i*(len(candidates)-1)/(quota-1))] for i in range(quota)]
 added=0
 for pair_index,(left,right) in enumerate(chosen):
  # Alternate direction while using each pair only once and each answer clause only once.
  side='right' if pair_index%2==0 else 'left';answer=right['text'] if side=='right' else left['text'];given=left['text'] if side=='right' else right['text']
  akey=(slug,answer);pkey=(slug,left['text'],right['text'])
  if akey in answer_keys or pkey in pair_keys:continue
  answer_keys.add(akey);pair_keys.add(pkey)
  pair_text=body[left['start']:right['end']]
  relation='补写紧接的后一个完整分句' if side=='right' else '补写紧邻的前一个完整分句'
  punctuation=body[left['end']:right['start']]
  display=given+punctuation+'________' if side=='right' else '________'+punctuation+given
  version=f"维基文库简体呈现；页面 {source['resolved_page']}；修订 {source['revision_id']}；以本题所附缓存版本为准，异文不混用"
  credit=f"{source['author']}《{source['title']}》古代原文属公版；维基文库贡献者校录，修订 {source['revision_id']}，CC BY-SA 4.0；本工具依原文自编默写，移除注释/异文提示及空白排版。"
  content_key=sha(json.dumps([slug,given,side,answer],ensure_ascii=False).encode())
  q={'id':'classics-cloze-'+content_key[:24],'subject':'语文','stage':'高中','type':'填空题','stem':f"{source['author']}《{source['title']}》：{relation}（按所附原文版本；句末标点无需填写，句内标点依原文）。\n“{display}”",'answer':answer,'analysis':f"所附原文对应相邻分句为：“{pair_text}”。本题空缺为{('后句' if side=='right' else '前句')}“{answer}”。",'source':f"公版原文自编默写 · {source['title']}",'sourceUrl':source['source_url'],'license':'CC-BY-SA-4.0','licenseUrl':'https://creativecommons.org/licenses/by-sa/4.0/','attribution':credit,'sourceQuestionId':f"CLASSICS/{slug}/{left['start']}-{right['end']}/{side}",'sourceMaterialId':source_id,'author':source['author'],'workTitle':source['title'],'sourceText':body,'sourceTextSha256':body_hash,'sourceTextPath':str(source_path.relative_to(ROOT)),'sourceRevisionId':source['revision_id'],'sourceVersion':version,'sourceCopyrightStatus':'public_domain_ancient_original','questionOrigin':'self_authored_from_public_domain_classic','originalExamQuestion':False,'knowledgePath':['语文','古诗文','名句默写',source['title']],'generationTemplate':'adjacent_complete_clause_cloze_v1','givenText':given,'blankSide':side,'leftClause':left['text'],'rightClause':right['text'],'sourceOffsets':{name:len(body[:pos].encode('utf-16-le'))//2 for name,pos in {'leftStart':left['start'],'leftEnd':left['end'],'rightStart':right['start'],'rightEnd':right['end']}.items()},'sourceOffsetUnit':'utf16_code_units','sourceCodePointOffsets':{'leftStart':left['start'],'leftEnd':left['end'],'rightStart':right['start'],'rightEnd':right['end']},'teacherReviewed':False,'reviewFlags':[],'validationStatus':'cached_source_adjacent_clause_and_answer_checked'}
  assert body[q['sourceCodePointOffsets']['leftStart']:q['sourceCodePointOffsets']['leftEnd']]==q['leftClause']
  assert body[q['sourceCodePointOffsets']['rightStart']:q['sourceCodePointOffsets']['rightEnd']]==q['rightClause']
  assert answer in body and answer.strip() and q['answer']!=q['givenText']
  questions.append(q);added+=1
 by_work[source['title']]=added
 source_records.append({**source,'id':source_id,'sourceText':body,'sourceTextSha256':body_hash,'sourceTextPath':str(source_path.relative_to(ROOT)),'rawSha256':sha(raw_path.read_bytes()),'originalPublicDomainNoticePresent':'公有领域' in full,'sourceVersion':version if added else f"Wikisource revision {source['revision_id']}",'selectedRanges':ranges,'candidateDisjointAdjacentPairs':len(candidates),'questionsCreated':added,'license':'CC-BY-SA-4.0','sourceCopyrightStatus':'public_domain_ancient_original'})
jsonl(ROOT/'questions.jsonl',questions);jsonl(ROOT/'sources.jsonl',source_records)
manifest={'schema_version':'1.0','created_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'subject':'语文','stage':'高中','type':'填空题','question_origin':'自编命题，基于古代公版作品；独立于NCR原考试题数量','counts':{'questions':len(questions),'works':len(source_records),'unique_answers_per_work':len(answer_keys),'unique_clause_pairs':len(pair_keys),'by_work':by_work},'scope_note':'优先高中常见古诗文选读作品；不声明覆盖现行全部教材或必背篇目。不采用小学/初中专属篇目或现代版权期作品。','source_policy':'Wikisource zh-hans rendered base text at pinned revision. No modern annotations or variant reading tooltips used. Ancient originals are public domain; retain Wikisource transcription attribution/share-alike and change notices.','version_note':'个别字形、通假字、标点可能与用户教材不同；明确按所附缓存原文版本，不能声称教材唯一标准答案。','validation':['Each pair consists of two immediately adjacent complete clauses joined by a comma/semicolon; no cross-sentence pairing','No pair is repeated with the direction reversed','Within each work each answer clause is used at most once','Answers and both given/answer clause offsets match cached sourceText exactly','No name/serial/option-order substitutions to inflate counts','analysis states only original-text correspondence; no AI literary appreciation','No teacher-review claim'],'failures':failures,'outputs':[]}
for name in ['questions.jsonl','sources.jsonl','source-index.json','source-cache/wikisource-rights.json']:
 p=ROOT/name;manifest['outputs'].append({'path':name,'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())})
(ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(manifest['counts'],ensure_ascii=False));print('FAILURES',json.dumps(failures,ensure_ascii=False))
for s in source_records:print(s['slug'],len(s['sourceText']),s['candidateDisjointAdjacentPairs'],s['questionsCreated'],s['sourceText'][:55],s['sourceText'][-55:])
