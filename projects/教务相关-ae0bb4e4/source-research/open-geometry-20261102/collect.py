#!/usr/bin/env python3
"""Public author sources only. Re-run is cached; never executes downloaded data."""
import concurrent.futures, hashlib, json, pathlib, urllib.request, time

ROOT = pathlib.Path(__file__).resolve().parent
LOG = []
def fetch(url, name):
    path = ROOT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        for attempt in range(3):
            try:
                req=urllib.request.Request(url,headers={'User-Agent':'Siyue-public-source-research/1.0'})
                with urllib.request.urlopen(req,timeout=75) as response:
                    data=response.read()
                path.write_bytes(data)
                break
            except Exception:
                if attempt==2: raise
                time.sleep(1+attempt)
    data=path.read_bytes()
    LOG.append({'file':name,'source_url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    return data

def jsfetch(url,name): return json.loads(fetch(url,name))
def dump(name,value):
    (ROOT/name).parent.mkdir(parents=True,exist_ok=True)
    (ROOT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

stats={}
for repo,key in [('BitSecret/FormalGeo7K','formalgeo'),('lupantech/InterGPS','geometry3k')]:
    commit=jsfetch(f'https://api.github.com/repos/{repo}/commits/main',f'provenance/{key}-commit.json')['sha']
    tree=jsfetch(f'https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1',f'provenance/{key}-tree.json')['tree']
    raw=f'https://raw.githubusercontent.com/{repo}/{commit}/'
    fetch(raw+'LICENSE',f'licenses/{key}-LICENSE.txt')
    fetch(raw+'README.md',f'provenance/{key}-README.txt')
    if key=='formalgeo':
        qs=[t for t in tree if t['path'].startswith('projects/formalgeo7k/problems/') and t['path'].endswith('.json')]
        imos=[t for t in tree if t['path'].startswith('projects/formalgeo-imo/problems/') and t['path'].endswith('.json')]
        qs.sort(key=lambda t:int(pathlib.Path(t['path']).stem)); imos.sort(key=lambda t:int(pathlib.Path(t['path']).stem))
        for project in ['formalgeo7k','formalgeo-imo']:
            fetch(raw+f'projects/{project}/LICENSE',f'licenses/{project}-project-LICENSE.txt')
        index=[dict(id=int(pathlib.Path(t['path']).stem),path=t['path'],bytes=t['size'],git_blob_sha=t['sha'],url=raw+t['path'],dataset='formalgeo7k' if t in qs else 'formalgeo-imo') for t in qs+imos]
        dump('formalgeo-question-index.json',{'repository':repo,'revision':commit,'count':len(index),'items':index})
        selected=[qs[round(i*(len(qs)-1)/95)] for i in range(96)]+imos
        jobs=[]
        paths={t['path']:t for t in tree}
        for t in selected:
            p=t['path']; project=p.split('/')[1]; ident=pathlib.Path(p).stem
            jobs.append((raw+p,f'samples/{project}/problems/{ident}.json'))
            for ext in ['png','ggb']:
                fig=f'projects/{project}/diagrams/{ident}.{ext}'
                if fig in paths:jobs.append((raw+fig,f'samples/{project}/diagrams/{ident}.{ext}'))
        stats[key]={'revision':commit,'indexed_questions':len(qs),'indexed_imo_questions':len(imos),'indexed_question_json_bytes':sum(t['size'] for t in qs+imos),'whole_repository_blob_bytes':sum(t.get('size',0) for t in tree),'sample_questions':len(selected)}
    else:
        jobs=[]
        examples=[t for t in tree if t['path'].startswith('annotation_tool/data_collection/data_examples/') and t['type']=='blob']
        for t in examples:
            p=t['path']; rel=p.split('data_examples/',1)[1]
            jobs.append((raw+p,'samples/geometry3k/'+rel))
        stats[key]={'revision':commit,'claimed_full_questions':3002,'sample_questions':sum(t['path'].endswith('/data.json') for t in examples),'sample_files':len(examples),'sample_bytes':sum(t.get('size',0) for t in examples),'whole_repository_blob_bytes':sum(t.get('size',0) for t in tree)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results=list(pool.map(lambda j:fetch(*j),jobs))
    print(key, json.dumps(stats[key]), flush=True)

# Author book main text is BY-SA; deliberately excludes detailed solutions (BY-NC-SA).
fetch('https://www.opentextbookstore.com/precalc/','provenance/lippman-author-page.html')
for chapter in ['front','5','8','9','answers']:
    fetch(f'https://www.opentextbookstore.com/precalc/current2.php?chapter={chapter}&format=docx',f'downloads/lippman-precalculus/chapter-{chapter}.docx')
print('lippman main-text chapter downloads complete',flush=True)
fetch('https://www.siyavula.com/read','provenance/siyavula-read.html')
for grade in [10,11,12]:
    fetch(f'https://www.siyavula.com/downloads/books/maths/Gr{grade}_Mathematics_Learner_Eng_CC-BY.epub',f'downloads/siyavula/grade-{grade}-unbranded-CC-BY.epub')
print('siyavula unbranded CC-BY downloads complete',flush=True)
for repo,key in [('chen-judge/GeoQA','geoqa'),('SCNU203/GeoQA-Plus','geoqa-plus')]:
    fetch(f'https://raw.githubusercontent.com/{repo}/main/README.md',f'provenance/{key}-README.txt')
dump('collection-manifest.json',{'collected_at':'2026-11-02','scope':'research-only; no application import or public publication','stats':stats,'files':sorted(LOG,key=lambda x:x['file']),'downloaded_bytes':sum(f['bytes'] for f in LOG)})
print(json.dumps({'stats':stats,'files':len(LOG),'bytes':sum(f['bytes'] for f in LOG)}),flush=True)
