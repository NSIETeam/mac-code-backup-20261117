from pathlib import Path
import json,re,hashlib,shutil,tarfile
root=Path.cwd();src=Path('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/central-resolve-20261105');prev=root.parent/'paper-tray-release/backend'
baseline=json.loads(Path('live-baseline.json').read_text());tag='20261107-label-save'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,obj):p.write_text(json.dumps(obj,indent=2))
def archive(p):
 with tarfile.open(p.name+'.tar.gz','w:gz') as t:
  for f in p.iterdir():t.add(f,arcname=f.name)
assert baseline['baseApi']['serverSha256']==sha(prev/'artifacts/Siyue.Server.dll')==baseline['baseWorker']['serverSha256']
assert all(sha(src/'src'/it['path'])==it['sha256'] for it in json.loads((prev/'candidate.manifest.json').read_text())['sourceDelta'] if it['path']!='tests/MaterialQuestionBankTests.cs')
assert sha(Path('before/MaterialQuestionBankTests.cs'))==next(i['sha256'] for i in json.loads((prev/'candidate.manifest.json').read_text())['sourceDelta'] if i['path']=='tests/MaterialQuestionBankTests.cs')
checks=json.loads(Path('qa/browser.json').read_text());assert len(checks)==2 and all(x['samePreviewNode'] and x['staleWriteRejected'] and x['noHandoutOrHistoryReload'] for x in checks)
result=re.search(r'已通过!.*失败:\s*0，通过:\s*(\d+)',Path('qa/backend.log').read_text());assert result
out=src/'src/tests/bin/Release/net10.0';d=Path('backend');(d/'artifacts').mkdir(parents=True,exist_ok=True)
files=[]
for name in ['Siyue.Server.dll','Siyue.Server.pdb']:
 p=d/'artifacts'/name;shutil.copy2(out/name,p);files.append({'path':name,'sha256':sha(p),'bytes':p.stat().st_size})
dll=files[0]['sha256'];write(d/'candidate.acceptance.json',{'ok':True,'candidateServerSha256':dll,'backendTestsPassed':int(result[1]),'browserChecks':checks})
expected={'server/WordDocuments.cs','tests/MaterialQuestionBankTests.cs','server/Learning.cs'}
write(d/'candidate.manifest.json',{**baseline,'schemaMigration':False,'releaseTag':tag,'sourceDelta':[{'path':p,'sha256':sha(src/'src'/p)} for p in sorted(expected)],'artifacts':files,'acceptanceSha256':sha(d/'candidate.acceptance.json')})
tested=json.loads((out/'Siyue.Server.deps.json').read_text())['libraries'];live=json.loads((src/'material-bank-20261107/live-server.deps.json').read_text())['libraries'];assert all(tested.get(k)==v for k,v in live.items())
docker=re.sub(r'^FROM .*','FROM '+baseline['baseApi']['imageTag'],(prev/'Dockerfile').read_text());(d/'Dockerfile').write_text(docker)
script=(prev/'promote.py').read_text()
for k,v in {'TAG':tag,'CANARY':'siyue-label-save-canary','BASE_API':baseline['baseApi'],'BASE_WORKER':baseline['baseWorker'],'SOURCE_DELTA':expected,'DOCKERFILE':docker}.items():script=re.sub(r'^'+k+r' = .*$',lambda _:k+' = '+repr(v),script,flags=re.M)
(d/'promote.py').write_text(script);archive(d)
w=Path('frontend');(w/'web').mkdir(parents=True,exist_ok=True)
old=baseline['staticBaseline']['hashes'];changed=['app.js','index.html']
for f in changed:
 assert sha(src/'web'/f)==old[f] and sha(Path('web')/f)!=old[f]
 shutil.copy2(Path('web')/f,w/'web'/f)
write(w/'web-manifest.json',{'previous':baseline['staticBaseline']['target'],'before':{f:old[f] for f in changed},'after':{f:sha(w/'web'/f) for f in changed}})
publisher=(root.parent/'paper-tray-release/review-r2/release/publish-web.py').read_text().replace("TAG='20261107-workbench-classic'","TAG='20261107-label-save-ui'").replace(baseline['baseApi']['serverSha256'],dll)
assert dll in publisher;(w/'publish-web.py').write_text(publisher);archive(w)
print(tag,dll,result[1])
