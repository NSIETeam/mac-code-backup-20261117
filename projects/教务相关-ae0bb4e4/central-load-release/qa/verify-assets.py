from pathlib import Path
import hashlib, json, shutil, urllib.request
base='https://www.siyuejn.com/miniapp/workbench/'
files=json.loads(Path('frontend/web-manifest.json').read_text())
origin=Path('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/central-resolve-20261105/web')
sha=lambda b:hashlib.sha256(b).hexdigest()
for name, value in files['after'].items():
 req=urllib.request.Request(base+('' if name=='index.html' else name),headers={'Accept-Encoding':'identity','Cache-Control':'no-cache'})
 with urllib.request.urlopen(req,timeout=12) as f: assert sha(f.read())==value,name
 assert sha((origin/name).read_bytes()) in [value, files['before'][name]]
for name in files['after']: shutil.copy2(Path('web')/name,origin/name)
print('public assets verified')
