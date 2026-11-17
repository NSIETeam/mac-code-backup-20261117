"""Stage public-prefix assets and source code from the explicit web distribution list."""
from pathlib import Path
import re
import shutil
import sys

ROOT=Path(__file__).resolve().parents[1]
PREFIX='/ip-workbench'
def stage(directory):
    dest=directory/'operations'
    dest.mkdir(parents=True)
    for p in (ROOT/'operations').glob('*.py'):
        shutil.copy2(p,dest/p.name)
    for p in (ROOT/'operations').glob('*.json'):
        if p.name.endswith('schema.json') or p.name=='service.openapi.json':shutil.copy2(p,dest/p.name)
    for group in ('static','templates','douyin_public'):
        shutil.copytree(ROOT/'operations'/group,dest/group,ignore=shutil.ignore_patterns('*.pyc','__pycache__','.DS_Store'))
    shutil.copy2(ROOT/'operations/运行与接入说明.md',dest/'运行与接入说明.md')
    shutil.copy2(ROOT/'webdeploy/server.py',directory/'web_server.py')
    for p in (dest/'static').rglob('*'):
        if p.suffix in ('.html','.css','.js') and p.name!='fabric.min.js':
            text=p.read_text()
            text=re.sub(r'''(?<=[\x22\x27\x60(])/(?=operations(?:-static|/|[\x22\x27\x60#?])|api/(?:local-session|operations))''',
                        PREFIX+'/',text)
            p.write_text(text)
if __name__=='__main__':stage(Path(sys.argv[1]))
