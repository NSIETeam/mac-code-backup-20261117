from pathlib import Path
import json,hashlib,subprocess,urllib.request,shutil
base='https://www.siyuejn.com/miniapp/workbench/'
for name in ('index.html','app.js','core.mjs','workbench-classic.css'):
 req=urllib.request.Request(base+('' if name=='index.html' else name),headers={'Cache-Control':'no-cache','Accept-Encoding':'identity'})
 with urllib.request.urlopen(req,timeout=12) as response:assert response.read()==(Path('web')/name).read_bytes()
assert json.load(urllib.request.urlopen('https://www.siyuejn.com/miniapp/health',timeout=10))['status']=='ok'
ssh=['ssh','-o','BatchMode=yes','-i','/Users/king/Desktop/思跃测试服务器-SSH登录密钥.pem','root@47.113.200.248']
code="""from pathlib import Path
import json,subprocess,urllib.request
root=Path('/opt/siyue-miniapp')
rep=json.loads((sorted((root/'backups').glob('20261107-label-confirm-*'))[-1]/'deployment.json').read_text())
print(json.dumps({'ok':rep['ok'],'release':rep['release'],'worker':json.load(urllib.request.urlopen('http://127.0.0.1:5083/health'))['status'],'drainCleared':not (root/'data/worker-control/drain').exists(),'static':str(Path('/opt/siyue-workbench/current').resolve())}))
"""
r=json.loads(subprocess.run(ssh+['python3 -'],input=code,text=True,capture_output=True,check=True).stdout)
assert r['ok'] and r['drainCleared'] and r['release']=='20261107-label-confirm' and r['worker']=='ok' and r['static'].endswith('/20261107-label-confirm-ui')
target=Path('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/central-resolve-20261105/web')
before=json.loads(Path('live-baseline.json').read_text())['staticBaseline']['hashes']
for name in ['index.html','app.js','core.mjs','workbench-classic.css']:assert hashlib.sha256((target/name).read_bytes()).hexdigest()==before[name]
for name in ['index.html','app.js','core.mjs','workbench-classic.css']:shutil.copy2(Path('web')/name,target/name)
print(json.dumps(r));Path('qa/deployment.json').write_text(json.dumps(r,indent=2))
