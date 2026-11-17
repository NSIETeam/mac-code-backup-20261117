#!/usr/bin/env python3
import concurrent.futures,datetime,json,pathlib,urllib.request,urllib.parse
ROOT=pathlib.Path(__file__).resolve().parent
WORKS=[('quanxue','劝学','荀子','荀子/勸學篇'),('shishuo','师说','韩愈','師說'),('chibifu','赤壁赋','苏轼','前赤壁賦'),('epanggongfu','阿房宫赋','杜牧','阿房宮賦'),('pipaxing','琵琶行','白居易','琵琶行'),('shudaonan','蜀道难','李白','蜀道難 (李白)'),('qiangjinjiu','将进酒','李白','將進酒 (李白)'),('lisao','离骚','屈原','離騷'),('lantingjixu','兰亭集序','王羲之','蘭亭集序'),('guiqulaixi','归去来兮辞','陶渊明','歸去來辭'),('chenqingbiao','陈情表','李密','陳情表 (西晉)'),('denggao','登高','杜甫','登高'),('jinse','锦瑟','李商隐','錦瑟'),('chunjianghuayueye','春江花月夜','张若虚','春江花月夜 (張若虛)')]
def fetch(item):
 slug,title,author,page=item;p=ROOT/'source-cache'/(slug+'.parse.json')
 url='https://zh.wikisource.org/w/api.php?'+urllib.parse.urlencode({'action':'parse','page':page,'prop':'text|revid','variant':'zh-hans','format':'json','redirects':'1'})
 try:
  if p.exists():d=json.loads(p.read_text())
  else:
   d=json.load(urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'SiyueQuestionBankResearch/1.0 (public classical text provenance)'}),timeout=30))
   p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
  if 'error' in d:return {'slug':slug,'title':title,'author':author,'requested_page':page,'error':d['error']}
  r=d['parse'];row={'slug':slug,'title':title,'author':author,'requested_page':page,'resolved_page':r['title'],'revision_id':r.get('revid'),'source_url':'https://zh.wikisource.org/zh-hans/'+urllib.parse.quote(r['title']),'api_url':url,'raw_file':'source-cache/'+p.name,'retrieved_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
  print(json.dumps(row,ensure_ascii=False),flush=True);return row
 except Exception as e:return {'slug':slug,'title':title,'author':author,'requested_page':page,'error':str(e)}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:rows=list(pool.map(fetch,WORKS))
(ROOT/'source-index.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
for r in rows:
 if 'error' in r:print('ERROR',json.dumps(r,ensure_ascii=False),flush=True)
