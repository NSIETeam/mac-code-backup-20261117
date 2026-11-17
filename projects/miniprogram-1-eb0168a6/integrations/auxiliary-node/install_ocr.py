"""One-off administrator task; official hashes permit a faster regional mirror."""
from pathlib import Path
import subprocess,sys,json
root=Path(__file__).resolve().parent
with (root/'ocr-install-mirror.log').open('w',encoding='utf8') as log:
    command=[sys.executable,'-m','pip','--isolated','install','--disable-pip-version-check','--only-binary=:all:','--require-hashes','--index-url','https://pypi.tuna.tsinghua.edu.cn/simple','--find-links',str(root/'wheelhouse'),'-r',str(root/'requirements-ocr-windows.txt')]
    code=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT).returncode
    if code==0:code=subprocess.run([sys.executable,str(root/'prepare_native.py')],stdout=log,stderr=subprocess.STDOUT).returncode
    if code==0:
        subprocess.run(['schtasks','/End','/TN','Siyue-Auxiliary-Node'],stdout=log,stderr=subprocess.STDOUT)
        subprocess.run(['schtasks','/Run','/TN','Siyue-Auxiliary-Node'],stdout=log,stderr=subprocess.STDOUT)
(root/'ocr-install-result.json').write_text(json.dumps(dict(exitCode=code)),encoding='utf8')
sys.exit(code)
