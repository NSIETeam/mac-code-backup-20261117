from pathlib import Path
import hashlib,json,re,tarfile,shutil
from datetime import datetime
root=Path.cwd()
source=Path('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/central-resolve-20261105')
tag='20261107-paper-tray-review'
baseline=json.loads((root/'live-baseline.json').read_text())
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,data):path.write_text(json.dumps(data,indent=2))
def archive(folder):
 with tarfile.open(root/(folder.name+'.tar.gz'),'w:gz') as tar:
  for p in folder.iterdir():tar.add(p,arcname=p.name)
old=source/'backend-deploy-material-bank'
expected={'server/Printing.cs','server/MaterialQuestionBank.cs','tests/MaterialQuestionBankTests.cs'}
for item in json.loads((old/'candidate.manifest.json').read_text())['sourceDelta']:
 if item['path'] not in expected:assert sha(source/'src'/item['path'])==item['sha256'],item['path']
assert baseline['baseApi']['serverSha256']==sha(old/'artifacts/Siyue.Server.dll')==baseline['baseWorker']['serverSha256']
assert '失败:     0，通过:    41' in (root/'qa/backend.log').read_text()
checks=json.loads((root/'qa/browser-results.json').read_text());assert len(checks)==3 and all(c['singleReview'] and c['batchReview'] and c['confirmation'] for c in checks)
bundle=root/'backend';artifacts=bundle/'artifacts';artifacts.mkdir(parents=True,exist_ok=True)
out=source/'src/tests/bin/Release/net10.0'
tested=json.loads((out/'Siyue.Server.deps.json').read_text())['libraries']
live=json.loads((source/'material-bank-20261107/live-server.deps.json').read_text())['libraries'];assert all(tested.get(k)==v for k,v in live.items())
files=[]
for name in ['Siyue.Server.dll','Siyue.Server.pdb']:
 shutil.copy2(out/name,artifacts/name);files.append({'path':name,'bytes':(artifacts/name).stat().st_size,'sha256':sha(artifacts/name)})
dll=files[0]['sha256'];write(bundle/'candidate.acceptance.json',{'ok':True,'candidateServerSha256':dll,'backendTestsPassed':41,'browserChecks':checks})
delta=[{'path':p,'sha256':sha(source/'src'/p)} for p in sorted(expected)]
write(bundle/'candidate.manifest.json',{**baseline,'releaseTag':tag,'schemaMigration':False,'sourceDelta':delta,'artifacts':files,'acceptanceSha256':sha(bundle/'candidate.acceptance.json')})
dockerfile=(old/'Dockerfile').read_text().replace('FROM siyue-miniapp:20261107-class-filter','FROM '+baseline['baseApi']['imageTag'])
(bundle/'Dockerfile').write_text(dockerfile)
script=(old/'promote.py').read_text()
for key,value in [('TAG',tag),('CANARY','siyue-paper-tray-review-canary'),('BASE_API',baseline['baseApi']),('BASE_WORKER',baseline['baseWorker']),('SOURCE_DELTA',expected),('DOCKERFILE',dockerfile)]:
 script=re.sub('^'+key+' = .*$',lambda _:key+' = '+repr(value),script,flags=re.M)
(bundle/'promote.py').write_text(script);archive(bundle)
static=root/'frontend';(static/'web').mkdir(parents=True,exist_ok=True)
base=baseline['staticBaseline'];changed=[f for f,h in base['hashes'].items() if sha(root/'web'/f)!=h]
assert set(changed)=={'app.js','index.html','print-drop.mjs','material-question-bank.mjs','material-question-bank.css','material-edition.mjs'},changed
for f in changed:shutil.copy2(root/'web'/f,static/'web'/f)
write(static/'web-manifest.json',{'previous':base['target'],'before':{f:base['hashes'][f] for f in changed},'after':{f:sha(static/'web'/f) for f in changed}})
publisher=(source/'web-material-bank/publish-web.py').read_text().replace("TAG='20261107-material-bank-ui'","TAG='"+tag+"-ui'").replace(baseline['baseApi']['serverSha256'],dll)
(static/'publish-web.py').write_text(publisher);archive(static)
print('Candidate package validated:',tag,dll,len(changed),'website files')
