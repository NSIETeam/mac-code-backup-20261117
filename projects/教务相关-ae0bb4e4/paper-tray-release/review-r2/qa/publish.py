from pathlib import Path
import json,hashlib,tarfile,shutil,subprocess
root=Path.cwd();base=json.loads(Path('live-baseline.json').read_text());before=base['staticBaseline'];tag='20261107-workbench-classic';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert json.loads(Path('qa/routes.log').read_text())=={'checked':84,'bad':[],'unhandled':[]}
assert json.loads(Path('qa/entries.log').read_text())['ok']
assert all(row['oneLineCourse'] for row in json.loads(Path('qa/browser-results.json').read_text()))
out=Path('release');(out/'web').mkdir(parents=True,exist_ok=True)
after={str(p.relative_to('web')):sha(p) for p in Path('web').rglob('*') if p.is_file()}
changes={p:v for p,v in after.items() if before['hashes'].get(p)!=v};assert set(changes)=={'app.js','index.html','material-edition.mjs','central-research.mjs','handout-review-desk.mjs','review-desk.css','workbench-classic.css'}
assert set(before['hashes'])<=set(after)
for p in changes:shutil.copy2(Path('web')/p,out/'web'/p)
m={'previous':before['target'],'before':{p:before['hashes'][p] for p in changes if p in before['hashes']},'after':changes}
(out/'web-manifest.json').write_text(json.dumps(m,indent=2))
publisher=Path('../frontend/publish-web.py').read_text().replace("TAG='20261107-paper-tray-review-ui'","TAG='"+tag+"'")
assert tag in publisher and base['baseApi']['serverSha256'] in publisher and base['baseApi']==base['baseWorker']
(out/'publish-web.py').write_text(publisher)
with tarfile.open('release.tar.gz','w:gz') as tar:
 for p in out.iterdir():tar.add(p,arcname=p.name)
key='/Users/king/Desktop/思跃测试服务器-SSH登录密钥.pem'
ssh=['ssh','-i',key,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','root@47.113.200.248']
target='/opt/siyue-miniapp/release-workbench-classic-20261107'
subprocess.run(ssh+['mkdir -p '+target],check=True)
subprocess.run(['scp','-i',key,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','release.tar.gz','root@47.113.200.248:'+target+'/'],check=True)
code="from pathlib import Path\nimport subprocess,tarfile\np=Path("+repr(target)+")\nwith tarfile.open(p/'release.tar.gz') as t:t.extractall(p,filter='data')\nsubprocess.run(['python3',str(p/'publish-web.py')],check=True)\n"
proc=subprocess.run(ssh+['python3 -'],input=code,text=True,capture_output=True);print(proc.stdout+proc.stderr);proc.check_returncode()
receipt=json.loads(proc.stdout);assert receipt['ok'] and receipt['publicAssetsVerified'];Path('qa/deployment.json').write_text(json.dumps(receipt,indent=2))
