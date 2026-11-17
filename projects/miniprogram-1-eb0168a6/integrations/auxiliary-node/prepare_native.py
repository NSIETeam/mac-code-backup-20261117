"""Administrator bootstrap of server-matched fonts and OCR assets. No credentials in output."""
import hashlib,json,os,subprocess,tarfile,urllib.request
from pathlib import Path
root=Path(r'C:\ProgramData\SiyueAuxNode');cfg=json.loads((root/'config.json').read_text());meta=json.loads((root/'fonts-package.json').read_text())
archive=root/'fonts.tar.gz'
if not archive.exists():
    request=urllib.request.Request(cfg['url']+'/node/input?sha='+meta['sha256'],headers={'Authorization':'Bearer '+cfg['token']})
    with urllib.request.urlopen(request,timeout=180) as response,archive.open('wb') as output:
        while block:=response.read(512*1024):output.write(block)
with archive.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==meta['sha256'],'Font package checksum'
assets=root/'assets';assets.mkdir(exist_ok=True)
marker=assets/'package.sha256'
if not marker.exists() or marker.read_text()!=meta['sha256']:
    with tarfile.open(archive) as t:
        assert sum(x.size for x in t.getmembers())<300*1024**2
        t.extractall(assets,filter='data')
    marker.write_text(meta['sha256'])
fontcfg=root/'fonts.conf';fontcfg.write_text('<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><dir>'+str(assets/'fonts').replace('\\','/')+'</dir><dir>'+str(assets/'siyue').replace('\\','/')+'</dir><cachedir>E:/SiyueAuxNode/font-cache</cachedir></fontconfig>')
cfg.update(nativeRoot=str(root/'native'),tessdata=str(assets/'tessdata'),fontConfig=str(fontcfg),ocrFont=str(assets/'fonts'/'opentype'/'noto'/'NotoSansCJK-Regular.ttc'))
# All native code and assets participate in the accepted engine fingerprint.
paths=[root/'native'/'Library'/'bin'/(x+'.exe') for x in ('pdftoppm','pdftotext','tesseract')]
paths += [root/x for x in ('native_contract.py','native_engine.py','rapid_runner.py','rapid_pool.py')]
paths += sorted((assets/'tessdata').glob('*.traineddata'))
paths += sorted((root/'venv'/'Lib'/'site-packages'/'rapidocr'/'models').glob('*.onnx'))
h=hashlib.sha256();h.update(meta['sha256'].encode())
for p in paths:
    with p.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
    h.update(p.name.encode()+sha.encode())
cfg['nativeFingerprint']=h.hexdigest();(root/'config.json').write_text(json.dumps(cfg),encoding='utf8')
(root/'native-capabilities.json').write_text(json.dumps(dict(nativeFingerprint=cfg['nativeFingerprint'],files=len(paths),fontPackageHash=meta['sha256'])))
print(json.dumps(dict(prepared=True,nativeFingerprint=cfg['nativeFingerprint'],fingerprintedFiles=len(paths))))
