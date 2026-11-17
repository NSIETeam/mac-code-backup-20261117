#!/usr/bin/env python3
"""Download author-published public NCR data. No login or access bypass."""
import concurrent.futures,datetime,hashlib,json,pathlib,urllib.request
ROOT=pathlib.Path(__file__).resolve().parent
FILES=[('dev_2','1_VjYJI12DiL8-xfWgrl9_YMs9gMUiJX7'),('test_2','1Ctns2w0YzEvE1qYIBZISc-5Rg4x-vcTI'),('train_2','1AccWPFMyw_VSG_n76ZT0KWa6qJI_MgFh')]
def download(item):
 name,fid=item;p=ROOT/'raw'/('NCR-'+name+'.json')
 url='https://drive.google.com/uc?export=download&id='+fid
 try:
  if not p.exists():
   req=urllib.request.Request(url,headers={'User-Agent':'question-bank-research/1.0'})
   with urllib.request.urlopen(req,timeout=40) as response:
    first=response.read(2048)
    if first.lstrip().startswith(b'<'):raise ValueError('HTML response requires manual review; no access bypass attempted')
    tmp=p.with_suffix('.json.part')
    with tmp.open('wb') as out:
     out.write(first)
     while True:
      b=response.read(1024*1024)
      if not b:break
      out.write(b)
    tmp.rename(p)
  b=p.read_bytes();data=json.loads(b)
  row={'file':str(p.relative_to(ROOT)),'source_kind':'author_published_google_drive','source_url':'https://drive.google.com/file/d/'+fid+'/view','download_url':url,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'documents':len(data),'questions':sum(len(x.get('Questions',[])) for x in data)}
  print(json.dumps(row,ensure_ascii=False),flush=True);return row
 except Exception as e:
  row={'file':name,'source_url':'https://drive.google.com/file/d/'+fid+'/view','error':str(e)};print(json.dumps(row),flush=True);return row
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:rows=list(pool.map(download,FILES))
(ROOT/'raw/NCR.download.json').write_text(json.dumps({'retrieved_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'author_project':'https://sites.google.com/view/native-chinese-reader/','author_folder':'https://drive.google.com/drive/folders/1Ci-KLHKk-yP-y5fWX4_cU8bA2fL_q76e','file_index_evidence':'evidence/ncr-drive-data-index.html','files':rows},ensure_ascii=False,indent=2)+'\n')
