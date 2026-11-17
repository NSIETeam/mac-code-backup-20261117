"""Runs only on native GitHub runners. All runtime downloads happen at build time."""
import hashlib,json,os,platform,plistlib,shutil,subprocess,sys,tarfile,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SYSTEM=platform.system();ARCH='aarch64' if platform.machine().lower() in ('arm64','aarch64') else 'x86_64'
TAG={'Windows':'windows','Darwin':'macos','Linux':'linux'}[SYSTEM]+'-'+ARCH
DEST=ROOT/'dist'/('IPWorkbench-'+TAG);OPS=DEST/'operations';CACHE=ROOT/'build-cache';CACHE.mkdir(exist_ok=True)
PROVENANCE=[]
def run(*args,**kw):subprocess.run([str(a) for a in args],check=True,**kw)
def fetch(url,dest,sha=None):
    dest.parent.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(url,timeout=120) as response,dest.open('wb') as out:shutil.copyfileobj(response,out)
    actual=hashlib.sha256(dest.read_bytes()).hexdigest()
    if sha and actual!=sha:raise ValueError('Download digest mismatch: '+dest.name)
    PROVENANCE.append(dict(url=url,sha256=actual));return dest
def release(repo,tag,name):
    request=urllib.request.Request(f'https://api.github.com/repos/{repo}/releases/tags/{tag}',headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN']})
    with urllib.request.urlopen(request) as response:data=json.load(response)
    asset=next(x for x in data['assets'] if x['name']==name)
    digest=asset.get('digest','')
    if not digest.startswith('sha256:'):raise ValueError('Missing release checksum '+name)
    return fetch(asset['browser_download_url'],CACHE/name,digest[7:])
def source(repo,commit,folder):
    archive=fetch(f'https://codeload.github.com/{repo}/tar.gz/{commit}',CACHE/(folder+'.tar.gz'))
    path=CACHE/folder;path.mkdir(exist_ok=True)
    with tarfile.open(archive) as tar:tar.extractall(path,filter='data')
    return next(path.iterdir())
if DEST.exists():raise SystemExit('Use a clean build directory')
OPS.mkdir(parents=True)
for p in (ROOT/'operations').glob('*.py'):
    if p.name not in {'install_local.py','bundle_runtime.py','package_desktop.py'}:shutil.copy2(p,OPS/p.name)
for name in ['static','templates','douyin_public']:
    shutil.copytree(ROOT/'operations'/name,OPS/name,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
for p in (ROOT/'operations').glob('*.json'):
    if p.name.endswith('schema.json') or p.name=='service.openapi.json':shutil.copy2(p,OPS/p.name)
shutil.copy2(ROOT/'packaging/安装说明.md',DEST/'安装说明.md')
shutil.copy2(ROOT/'packaging/安装说明.md',OPS/'运行与接入说明.md')
triple=ARCH+{'Windows':'-pc-windows-msvc','Darwin':'-apple-darwin','Linux':'-unknown-linux-gnu'}[SYSTEM]
asset=f'cpython-3.12.16+20261026-{triple}-install_only_stripped.tar.gz'
archive=release('astral-sh/python-build-standalone','20261026',asset)
runtime=OPS/'vendor/runtime';runtime.mkdir(parents=True)
with tarfile.open(archive) as tar:tar.extractall(runtime,filter='data')
python=runtime/('python/python.exe' if SYSTEM=='Windows' else 'python/bin/python3')
if SYSTEM=='Windows':
    # App-local redistributables: OCR/native wheels must not require users to install VC++.
    vswhere=Path(os.environ['ProgramFiles(x86)'])/'Microsoft Visual Studio/Installer/vswhere.exe'
    vs=Path(subprocess.check_output([str(vswhere),'-latest','-property','installationPath'],text=True).strip())
    crts=sorted((vs/'VC/Redist/MSVC').glob('*/x64/Microsoft.VC*.CRT'))
    if not crts:raise RuntimeError('Visual C++ redistributable directory missing')
    crt=crts[-1]
    for dll in crt.glob('*.dll'):shutil.copy2(dll,python.parent/dll.name)
    PROVENANCE.append(dict(component='Microsoft Visual C++ app-local CRT',version=crt.parent.parent.name,
                           files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in crt.glob('*.dll')}))
run(python,'-m','pip','install','--disable-pip-version-check','-r',ROOT/'operations/requirements.lock.txt','-r',ROOT/'packaging/requirements.txt')
# Download isolated Chromium and all OCR models into this release, never a user profile.
browsers=OPS/'vendor/browser';env={**os.environ,'PLAYWRIGHT_BROWSERS_PATH':str(browsers),'PYTHONNOUSERSITE':'1'}
run(python,'-m','playwright','install','chromium',env=env)
exe=subprocess.check_output([str(python),'-c','from playwright.sync_api import sync_playwright\nwith sync_playwright() as p: print(p.chromium.executable_path)'],env=env,text=True).strip()
(browsers/'runtime.json').write_text(json.dumps(dict(system=SYSTEM,machine=platform.machine(),executable=str(Path(exe).relative_to(browsers)),personal_browser_access=False)))
# Preserve the bounded search/comment fixes and use only the bundled Chromium.
xhs=source('xpzouying/xiaohongshu-mcp','badcc26ce23a617783f31847afb730f16594d347','xhs')
for patch in ['search-readiness.patch','bounded-comments.patch']:run('git','apply','--unsafe-paths',ROOT/'packaging'/patch,cwd=xhs)
p=xhs/'browser/browser_download.go';text=p.read_text();needle='func EnsureBrowser() (string, error) {'
assert needle in text
text=text.replace(needle,needle+'\n if bin := os.Getenv("IP_BUNDLED_BROWSER"); bin != "" {\n  if !filepath.IsAbs(bin) { return "", fmt.Errorf("bundled browser must be absolute") }; info, err := os.Stat(bin); if err != nil || info.IsDir() { return "", fmt.Errorf("bundled browser missing") }; return bin, nil\n }',1);p.write_text(text)
xhsout=OPS/'vendor/xiaohongshu';xhsout.mkdir(parents=True)
run('go','build','-trimpath','-o',xhsout/('xiaohongshu-mcp.exe' if SYSTEM=='Windows' else 'xiaohongshu-mcp'),'.',cwd=xhs,env={**os.environ,'CGO_ENABLED':'0'})
shutil.copy2(xhs/'LICENSE',xhsout/'LICENSE')
# Build a fixed CPU-only whisper revision. The model is identical across platforms.
whisper=source('ggml-org/whisper.cpp','4979e04f5dcaccb36057e059bbaed8a2f5288315','whisper');wb=CACHE/'whisper-build'
if SYSTEM=='Windows':
    # Preserve non-ASCII model, input and output paths without changing the system locale.
    shutil.copy2(ROOT/'packaging/utf8.manifest',whisper/'examples/cli/utf8.manifest')
    cmake=whisper/'examples/cli/CMakeLists.txt'
    with cmake.open('a',encoding='utf-8') as out:out.write('\ntarget_sources(whisper-cli PRIVATE utf8.manifest)\n')
run('cmake','-S',whisper,'-B',wb,'-DBUILD_SHARED_LIBS=OFF','-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded','-DGGML_NATIVE=OFF','-DGGML_CUDA=OFF','-DGGML_METAL=OFF','-DGGML_OPENMP=OFF','-DWHISPER_BUILD_TESTS=OFF')
run('cmake','--build',wb,'--config','Release','--target','whisper-cli','--parallel','3')
media=OPS/'vendor/media';media.mkdir(parents=True);filename='whisper-cli.exe' if SYSTEM=='Windows' else 'whisper-cli'
cli=next(wb.rglob(filename));shutil.copy2(cli,media/filename);shutil.copy2(whisper/'LICENSE',media/'WHISPER-LICENSE')
fetch('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin',media/'ggml-large-v3-turbo-q5_0.bin','394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2')
# Importing the OCR runtime must work with only bundled assets, before shipping.
run(python,'-c','from rapidocr_onnxruntime import RapidOCR; RapidOCR(); import av, psutil')
manifest=dict(version='0.5.0-preview',system=SYSTEM,arch=ARCH,build_commit=os.getenv('GITHUB_SHA'),downloads=PROVENANCE,
 capabilities={'workbench':'bundled','xhs':'bundled; first QR login required','douyin':'public discovery; availability not guaranteed','ocr':'bundled','asr':'bundled','channels':'platform connection not yet portable; needs separate setup','publishing':'manual'},signed=False)
(OPS/'vendor/desktop-build.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
run(python,'-m','pip','freeze',stdout=(DEST/'dependencies.txt').open('w'))
if SYSTEM=='Windows':
    (DEST/'启动工作台.cmd').write_text('@echo off\r\ncd /d "%~dp0"\r\nstart "" "operations\\vendor\\runtime\\python\\pythonw.exe" -m operations.desktop_launcher\r\n',encoding='utf-8')
elif SYSTEM=='Darwin':
    from macos_installer import build as build_macos_installer
    build_macos_installer(DEST, ROOT/'dist/installers')
else:
    launcher=DEST/'启动工作台.sh';launcher.write_text('#!/bin/sh\ncd "$(dirname "$0")" || exit 1\nexec operations/vendor/runtime/python/bin/python3 -m operations.desktop_launcher\n');launcher.chmod(0o755)
(ROOT/'dist/build-path.txt').write_text(str(DEST))
print('Built',DEST,flush=True)
