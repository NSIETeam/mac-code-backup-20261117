#!/usr/bin/env python3
"""Collect public author-published metadata only. Never fetch videos or question bodies."""
import concurrent.futures, datetime, json, pathlib, re, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parent
OWNER=14229967
SEEDS=['BV1VD4y1D7UB','BV147411K7xu','BV1Nf4y197aY','BV1Gp4y1Q7K5','BV1mJ4m1e7FQ','BV1eC411W7L3','BV1nw4m1k7ox','BV1xdby6yEob','BV1J2rUB6EYj','BV1ySRNBdE5p','BV1vTQMB3EYh','BV1hDdJBLE7u','BV1YgzTBTEGf']
PATTERNS={
 'solid_geometry':r'立体几何|几何体|空间角|二面角|外接球|内切球|线面|面面|三视图|直观图|表面积|体积|空间向量|点线面|棱锥|棱柱|正方体|长方体|直线与平面|平面与平面',
 'analytic_geometry':r'解析几何|圆锥曲线|椭圆|双曲线|抛物线|直线和圆|直线与圆|圆的方程|直线的方程|斜率|弦长|焦点|焦半径|圆与圆|圆的标准方程|圆的一般方程',
 'plane_vectors':r'平面向量|向量的|向量|数量积|三点共线|等和线',
 'triangle_geometry':r'解三角形|射影定理|三角形的|正弦定理|余弦定理|化边|化角',
}
def tags(text):
 return [k for k,p in PATTERNS.items() if re.search(p,text)]
def fetch(bvid):
 url='https://api.bilibili.com/x/web-interface/view?bvid='+bvid
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  with urllib.request.urlopen(req,timeout=25) as f: result=json.load(f)
  if result.get('code')!=0:return {'bvid':bvid,'error':'API code '+str(result.get('code'))}
  d=result['data']
  if d.get('owner',{}).get('mid')!=OWNER:return {'bvid':bvid,'error':'owner did not match verified author'}
  return d
 except Exception as exc:return {'bvid':bvid,'error':str(exc)}
def batch(ids):
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  return list(pool.map(fetch,ids))
def sanitize(d):
 b=d['bvid']
 pages=[]
 for p in d.get('pages',[]):
  title=p.get('part','');page=p['page']
  pages.append({'page':page,'title':title,'cid':p.get('cid'),'duration_seconds':p.get('duration'),
    'url':f'https://www.bilibili.com/video/{b}/?p={page}',
    'geometry_tags':tags(title+' '+d['title'])})
 sections=[]
 for s in d.get('ugc_season',{}).get('sections',[]):
  # Only retain mathematics sections, never the nine-subject course metadata.
  if '最后十课' in s.get('title','') and '数学' not in s['title']:continue
  eps=[]
  for e in s.get('episodes',[]):
   if not e.get('bvid'):continue
   eps.append({'bvid':e['bvid'],'title':e.get('title',''),'url':f"https://www.bilibili.com/video/{e['bvid']}/",'geometry_tags':tags(s.get('title','')+' '+e.get('title',''))})
  sections.append({'title':s.get('title',''),'episodes':eps})
 return {'bvid':b,'aid':d['aid'],'title':d['title'],'owner':{'mid':OWNER,'name':d['owner']['name']},
   'url':f'https://www.bilibili.com/video/{b}/','published_utc':datetime.datetime.fromtimestamp(d['pubdate'],datetime.timezone.utc).isoformat(),
   'metadata_api':'https://api.bilibili.com/x/web-interface/view?bvid='+b,
   'page_count':len(pages),'pages':pages,'linked_sections':sections,
   'question_body_collected':False,'reuse_status':'permission_not_verified_do_not_bulk_ingest_question_bodies'}
raw=batch(SEEDS)
# Expand only the 17 directly linked published book/course collections, not all recommendations.
book_seed=next((d for d in raw if d.get('bvid')=='BV1J2rUB6EYj' and 'error' not in d),None)
extra=[]
if book_seed:
 for s in book_seed.get('ugc_season',{}).get('sections',[]):
  for e in s.get('episodes',[]):
   b=e.get('bvid')
   if b and b not in SEEDS and b not in extra:extra.append(b)
raw+=batch(extra)
errors=[d for d in raw if 'error' in d]
records=[sanitize(d) for d in raw if 'error' not in d]
page_keys={p.get('cid') or (r['bvid'],p['page']) for r in records for p in r['pages']}
geo_keys={p.get('cid') or (r['bvid'],p['page']) for r in records for p in r['pages'] if p['geometry_tags']}
linked={e['bvid']:e for r in records for s in r['linked_sections'] for e in s['episodes']}
result={
 'schema_version':'1.0','retrieved_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'publisher':{'name':'一数','bilibili_mid':OWNER,'channel_url':'https://space.bilibili.com/14229967','wechat_public_account':'一数儿','wechat_direct_download_url_verified':False},
 'scope':'Official public video and lesson metadata only; no videos, transcripts, PDFs, or question bodies downloaded.',
 'counts':{'verified_official_video_collections':len(records),'lesson_page_records':sum(r['page_count'] for r in records),
  'distinct_lesson_media_ids':len(page_keys),'geometry_tagged_lesson_media_ids':len(geo_keys),'linked_video_catalog_entries':len(linked),
  'actual_question_bodies_imported':0,'pdf_files_downloaded':0,'errors':len(errors)},
 'rights':{'public_access':'Public official video metadata successfully read without account or paid access.',
  'reuse_authorization':'Not verified. Public viewing or free handouts do not establish bulk question-bank ingestion or redistribution permission.',
  'official_notice':'未经作者授权，禁止转载',
  'notice_evidence_url':'https://www.bilibili.com/video/BV1Nf4y197aY/',
  'safe_current_integration':['External canonical lesson links','Locally indexed titles and knowledge-point metadata','Manual author-approved resource import'],
  'requires_author_permission':['Download and republish handout PDFs','Bulk copy question text, solutions, or figures','Use author material as an openly redistributable seed corpus']},
 'classification_note':'Geometry tags are keyword-based catalog hints. Lesson counts are not question counts; repeated editions may repeat exercises.',
 'linked_index_note':'Linked season titles may lag author edits. Prefer fetched collections titles. Linked video entries are discovery metadata, not question bodies or independently owner-verified collections.',
 'collections':records,'linked_video_index':list(linked.values()),'errors':errors}
(ROOT/'source-catalog.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
geometry=[]
for r in records:
 for p in r['pages']:
  if p['geometry_tags']:geometry.append({'collection_title':r['title'],'bvid':r['bvid'],**p})
(ROOT/'geometry-lessons.json').write_text(json.dumps({'retrieved_at_utc':result['retrieved_at_utc'],'count':len(geometry),'note':'Metadata only; not question bodies or a redistribution license.','lessons':geometry},ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result['counts'],ensure_ascii=False))
print('ERRORS',errors)
for r in records:print(r['bvid'],r['page_count'],r['title'])
