import pathlib,urllib.request,hashlib,json,datetime,time
root=pathlib.Path(__file__).resolve().parent
url='http://www.cs.cmu.edu/~glai1/data/race/RACE.tar.gz'
p=root/'raw/RACE.tar.gz'
if not p.exists():
 req=urllib.request.Request(url,headers={'User-Agent':'question-bank-research/1.0'})
 with urllib.request.urlopen(req,timeout=45) as response,(p.with_suffix('.gz.part')).open('wb') as output:
  while True:
   b=response.read(1024*1024)
   if not b:break
   output.write(b)
 p.with_suffix('.gz.part').rename(p)
b=p.read_bytes();m={'source_url':url,'retrieved_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'usage_gate':'staging_noncommercial_research_only','note':'Original mixed-level archive preserved for provenance; normalizer accepts /high/ only and never emits /middle/.'}
(root/'raw/RACE.download.json').write_text(json.dumps(m,indent=2)+'\n')
print(json.dumps(m))
