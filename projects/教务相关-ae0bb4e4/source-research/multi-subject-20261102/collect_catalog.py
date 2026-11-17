#!/usr/bin/env python3
"""Public lesson titles/folder indices only. Does not fetch videos, subtitles, PDFs, or exercises."""
import collections, concurrent.futures, datetime, json, pathlib, re, urllib.request
ROOT=pathlib.Path(__file__).resolve().parent
DIST=ROOT.parent.parent/'free-question-bank-highschool/dist/data/source-catalog.json'
ACCOUNTS=[
 {'id':'yihua','name':'一化儿','subject':'化学','mid':1526560679,'seeds':['BV1Qi4y1R7tW','BV1RkK6zqE5D'],'evidence':'https://www.bilibili.com/video/BV1RkK6zqE5D/'},
 {'id':'yisheng','name':'一生儿','subject':'生物','mid':2036187097,'seeds':['BV1YC4y1o7E3','BV1Mn9aBqE4v'],'evidence':'https://www.bilibili.com/video/BV1YC4y1o7E3/'},
 {'id':'yiying','name':'一英儿','subject':'英语','mid':632207543,'seeds':['BV13DQbBYERE'],'evidence':'https://www.bilibili.com/video/BV13DQbBYERE/'},
 {'id':'chinese-candidate','name':'学过石油的语文老师（“一语”候选）','subject':'语文','mid':39737405,'seeds':['BV1HT411x7NZ'],'evidence':'https://www.bilibili.com/video/BV1HT411x7NZ/','intent_pending':True},
]
HEADERS={'User-Agent':'Mozilla/5.0'}
errors=[]

def get(endpoint):
 u='https://api.bilibili.com/'+endpoint
 try:
  with urllib.request.urlopen(urllib.request.Request(u,headers=HEADERS),timeout=25) as f:d=json.load(f)
  if d.get('code')!=0:raise ValueError('API code '+str(d.get('code')))
  return d.get('data')
 except Exception as exc:
  errors.append({'endpoint':endpoint,'error':str(exc)});return None

def batch(fn,items):
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:return list(pool.map(fn,items))

def view(b):return get('x/web-interface/view?bvid='+b)

def qtypes(title):
 out=[]
 if re.search('(?<!非)选择题|选择填空|单选题|多选题',title):out.append('选择题')
 if re.search('填空题|选择填空|语法填空',title):out.append('填空题')
 if re.search('解答题|非选择题|大题',title):out.append('解答题')
 # Keep English cloze as its explicit directory type; do not assume choice vs blank from its name.
 return out

TOPICS={
 '化学': [('有机化学','有机|烃|醇|酚|醛|羧酸|酯|高分子'),('化学反应原理','平衡|反应速率|反应能量|热化学|焓|反应热|熵'),('电化学','电化学|原电池|电解池|电极'),('溶液与离子平衡','电离|水解|溶度积|沉淀|溶液|pH|酸碱'),('氧化还原','氧化还原|配平|氧化性|还原性'),('物质的量','物质的量|阿伏|摩尔|化学计量'),('物质结构','结构|晶体|化学键|周期|电子排布'),('元素与化合物','钠|铁|铝|硅|氯|氮|硫|金属|元素'),('实验与工艺','实验|工艺|制备|滴定|探究'),('化学综合题','综合|真题|模拟|高考|试卷|陪刷')],
 '生物':[('遗传与进化','遗传|基因|DNA|RNA|染色体|减数|孟德尔|伴性|进化|变异'),('细胞代谢','光合|呼吸|代谢|酶|ATP|物质运输|输入|输出'),('细胞结构与分子','细胞|蛋白质|核酸|糖|脂质|元素|分子|显微镜|水和无机盐'),('稳态与调节','稳态|神经|体液|激素|免疫|内环境|调节'),('生态与环境','种群|群落|生态|环境|生物多样性'),('生物工程与实验','工程|发酵|实验|培养|克隆|胚胎'),('生物综合题','综合|真题|模拟|高考|试卷|大题')],
 '英语':[('语法填空','语法填空'),('完形填空','完形填空'),('七选五阅读','七选五'),('阅读理解','阅读|长难句'),('读后续写','读后续写|续写'),('应用文写作','应用文|书信|写作|作文'),('词汇与短语','词汇|单词|3500|短语'),('语法','语法|从句|时态|非谓语|虚拟|主谓'),('听力口语','听力|口语|语音'),('英语综合题','试卷|真题|模拟|讲评|实战')],
 '语文':[('作文与素材','作文|素材|议论文'),('文言文','文言|实词|虚词|断句|翻译'),('古代诗歌','诗歌|古诗|诗词'),('小说散文','小说|散文|文学类'),('信息类文本阅读','信息类|实用类|论述类'),('语言文字运用','语言文字|语用|病句|成语|修辞|词语'),('古代文化常识','文化常识'),('语文综合题','试卷|真题|模拟|新题型|抱佛脚')],
}
def topic(subject,title,folders):
 for name,pattern in TOPICS.get(subject,[]):
  if re.search(pattern,title,re.I):return name
 # Folder descriptions provide provenance-backed topic hints, never question-body facts.
 context=' '.join(folders)
 for name,pattern in TOPICS.get(subject,[]):
  if re.search(pattern,context,re.I):return name
 return '综合与未分类'

base=json.loads((ROOT/'yishu-ui-base.json').read_text())
for row in base['lessons']:row['question_types']=qtypes(row['title'])
research_sources=[]
for account in ACCOUNTS:
 mid=account['mid'];provenance=collections.defaultdict(list)
 folder_data=get('x/v3/fav/folder/created/list-all?up_mid='+str(mid)) or {}
 folders=[]
 for f in folder_data.get('list',[]):
  # Exclude talk-show/lifestyle and general live archives from the high-school academic catalog.
  if f['title'] in ['佳油奇说','直播回放']:continue
  folders.append({'id':f['id'],'title':f['title'],'declared_media_count':f.get('media_count',0)})
 tasks=[]
 for f in folders:
  for page in range(1,(f['declared_media_count']+19)//20+1):tasks.append((f,page))
 def folder_page(task):
  folder,page=task
  return folder,get(f"x/v3/fav/resource/list?media_id={folder['id']}&pn={page}&ps=20")
 rejected=0
 for folder,result in batch(folder_page,tasks):
  for media in (result or {}).get('medias') or []:
   if media.get('upper',{}).get('mid')!=mid or not media.get('bvid'):
    rejected+=1;continue
   b=media['bvid'];provenance[b].append({'kind':'author_public_favorite_folder','folder_title':folder['title'],'url':f"https://www.bilibili.com/medialist/detail/ml{folder['id']}"})
 seeds=batch(view,account['seeds'])
 seed_views={d['bvid']:d for d in seeds if d and d.get('owner',{}).get('mid')==mid}
 for b,d in seed_views.items():
  provenance[b].append({'kind':'verified_seed','url':f'https://www.bilibili.com/video/{b}/'})
  for s in d.get('ugc_season',{}).get('sections',[]):
   for e in s.get('episodes',[]):
    b2=e.get('bvid')
    if b2:provenance[b2].append({'kind':'author_video_season','section_title':s.get('title',''),'url':f'https://www.bilibili.com/video/{b}/'})
 requested=[b for b in provenance if b not in seed_views]
 details=list(seed_views.values())+[d for d in batch(view,requested) if d]
 official=[d for d in details if d.get('owner',{}).get('mid')==mid]
 rejected+=sum(d.get('owner',{}).get('mid')!=mid for d in details)
 source={'id':account['id'],'name':account['name'],'subject':account['subject'],'owner_mid':mid,
  'url':f'https://space.bilibili.com/{mid}','identity_status':'官方作者已核验；是否指用户“一语”待确认' if account.get('intent_pending') else '已核验官方作者',
  'rights_status':'未取得题库再分发授权','rights_notice':'未经作者授权，禁止转载','rights_evidence_url':account['evidence'],
  'identity_evidence_url':'https://www.bilibili.com/video/BV1ySRNBdE5p/','intent_pending':bool(account.get('intent_pending'))}
 base['sources'].append(source)
 research_collections=[]
 for d in official:
  b=d['bvid'];cid=account['id']+'-'+b
  ps=d.get('pages',[])
  contexts=[p.get('folder_title',p.get('section_title','')) for p in provenance[b]]
  c={'id':cid,'source_id':account['id'],'subject':account['subject'],'title':d['title'],'url':f'https://www.bilibili.com/video/{b}/','lesson_count':len(ps),'geometry_lesson_count':0}
  base['collections'].append(c)
  pages=[]
  for p in ps:
   title=p.get('part','');number=p['page']
   # A one-page title often is just the filename; collection title supplies the published context.
   display=title if len(ps)>1 and title else d['title']
   lesson={'id':f'{cid}-p{number}','source_id':account['id'],'collection_id':cid,'subject':account['subject'],'title':display,'url':f'https://www.bilibili.com/video/{b}/?p={number}',
    'topic':topic(account['subject'],display,contexts),'geometry':False,'question_types':qtypes(display)}
   base['lessons'].append(lesson)
   pages.append({'page':number,'title':title,'display_title':display,'cid':p.get('cid'),'duration_seconds':p.get('duration'),'url':lesson['url'],'topic':lesson['topic'],'question_types':lesson['question_types']})
  research_collections.append({'bvid':b,'aid':d['aid'],'title':d['title'],'owner':{'mid':mid,'name':d['owner']['name']},'url':c['url'],'pages':pages,'discovery_provenance':provenance[b]})
 research_sources.append({'source':source,'public_folders':folders,'collections':research_collections,'other_owner_or_unavailable_entries_excluded':rejected})
 print(json.dumps({'source':account['name'],'official_collections':len(official),'lesson_pages':sum(len(d.get('pages',[])) for d in official),'excluded':rejected},ensure_ascii=False),flush=True)

base['retrieved_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
base['notice']='仅提供一数、一化儿、一生儿、一英儿及语文候选作者的公开课程目录和官方入口，不是已入库试题。没有收录题干、答案、图形或讲义正文；题型仅按目录明确标注，语文作者是否对应“一语”待确认。'
base['counts']={'lessons':len(base['lessons']),'collections':len(base['collections']),'geometry_lessons':sum(bool(x.get('geometry')) for x in base['lessons']),'question_bodies':0,'by_subject':dict(collections.Counter(x['subject'] for x in base['lessons']))}
base['question_type_note']='Only explicit directory title labels. English cloze is not assumed to be multiple-choice or blank-filling. No exercise counts are inferred.'
DIST.write_text(json.dumps(base,ensure_ascii=False,separators=(',',':'))+'\n')
(ROOT/'source-catalog.json').write_text(json.dumps({'retrieved_at':base['retrieved_at'],'scope':'Public metadata only; no exercise body imported.','sources':research_sources,'counts':base['counts'],'errors':errors},ensure_ascii=False,indent=2)+'\n')
print('TOTAL',json.dumps(base['counts'],ensure_ascii=False),flush=True)
print('ERRORS',len(errors),flush=True)
