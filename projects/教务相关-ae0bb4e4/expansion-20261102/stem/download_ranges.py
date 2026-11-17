import urllib.request,concurrent.futures,pathlib,time,json,hashlib
ROOT=pathlib.Path(__file__).parent; RAW=ROOT/'raw';PARTS=RAW/'parts';PARTS.mkdir(exist_ok=True)
JOBS=[
('mathdata-content.json','https://raw.githubusercontent.com/MathAutoTag/mathdata/e72248b3547ffda0bde23dca66660ba136d8fd06/math_questions_content.json',47768466),
('mathdata-tags.json','https://raw.githubusercontent.com/MathAutoTag/mathdata/e72248b3547ffda0bde23dca66660ba136d8fd06/math_questions_knowledgetag.json',1690345),
('TAL-cn-train.jsonl','https://raw.githubusercontent.com/math-eval/TAL-SCQ5K/8a66d6e14c65ba002beda39747e6b7c43bd2d472/ch_single_choice_constructed_5K/ch_single_choice_train_3K.jsonl',3899222),
('TAL-cn-test.jsonl','https://raw.githubusercontent.com/math-eval/TAL-SCQ5K/8a66d6e14c65ba002beda39747e6b7c43bd2d472/ch_single_choice_constructed_5K/ch_single_choice_test_2K.jsonl',2701829),
('CMMaTH-sample.json','https://raw.githubusercontent.com/zzli2022/CMMaTH/ecd558feab7906cfee4154647febdba73a286a05/cmmath.json',2327587),
]
CHUNK=196608
work=[(name,url,size,start,min(size-1,start+CHUNK-1)) for name,url,size in JOBS for start in range(0,size,CHUNK)]
def chunk(job):
 name,url,size,start,end=job;part=PARTS/(name+'.'+str(start))
 if part.exists() and part.stat().st_size==end-start+1:return name,start,None
 for attempt in range(5):
  try:
   request=urllib.request.Request(url+f'?chunk={start}',headers={'Range':f'bytes={start}-{end}'})
   with urllib.request.urlopen(request,timeout=25) as response:
    if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{end}/{size}':raise ValueError('Unexpected range response')
    data=response.read()
   if len(data)!=end-start+1:raise ValueError('Partial range response')
   part.write_bytes(data);return name,start,None
  except Exception as e:
   if attempt==4:return name,start,str(e)
   time.sleep(.2*(attempt+1))
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
 for i,(name,start,error) in enumerate(pool.map(chunk,work),1):
  if error:print('ERROR',name,start,error,flush=True)
  elif i%25==0:print('chunks',i,'/',len(work),flush=True)
results=[]
for name,url,size in JOBS:
 parts=[PARTS/(name+'.'+str(start)) for start in range(0,size,CHUNK)]
 if all(p.exists() for p in parts):
  destination=RAW/name
  with destination.open('wb') as out:
   for p in parts:out.write(p.read_bytes())
  result={'name':name,'url':url,'bytes':destination.stat().st_size,'sha256':hashlib.sha256(destination.read_bytes()).hexdigest()};results.append(result);print('DONE',result,flush=True)
 else:print('INCOMPLETE',name,flush=True)
(ROOT/'downloads-manifest.json').write_text(json.dumps(results,indent=2))
