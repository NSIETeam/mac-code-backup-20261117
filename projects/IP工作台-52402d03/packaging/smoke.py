"""Offline clean-install acceptance, executed by the packaged Python itself."""
import json,os,socket,subprocess,sys,tempfile,time,urllib.request,wave
from pathlib import Path
ROOT=Path(sys.argv[1]).resolve();sys.path.insert(0,str(ROOT));os.chdir(ROOT)
OUT=Path(os.environ['SMOKE_OUTPUT']);OUT.mkdir(parents=True,exist_ok=True)
from operations.embedded_runtime import browser_path,python_path
from operations.portable_media import ocr,audio
from PIL import Image,ImageDraw
import av,numpy as np,psutil
from playwright.sync_api import sync_playwright
from workflow_acceptance import verify
checks=[]
with tempfile.TemporaryDirectory(prefix='ip-clean-install-') as tmp:
 data=Path(tmp)/'新用户 数据';sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close()
 env={**os.environ,'IP_DATA_DIR':str(data),'IP_PORT':str(port),'IP_ALLOW_CODEX':'0','PYTHONNOUSERSITE':'1'}
 # Do not let the CI machine's UTF-8 settings mask first-launch Windows defaults.
 env.pop('PYTHONUTF8',None);env.pop('PYTHONIOENCODING',None)
 # Prove that the actual desktop launcher starts its bundled backend and browser.
 entry=python_path().with_name('pythonw.exe') if os.name=='nt' else python_path()
 command=[os.environ['MAC_APP_ENTRY']] if os.getenv('MAC_APP_ENTRY') else [str(entry),'-m','operations.desktop_launcher']
 log=(OUT/'startup.log').open('wb');launcher=subprocess.Popen(command,cwd=ROOT,env=env,stdout=log,stderr=log)
 try:
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));base=f'http://127.0.0.1:{port}'
  for _ in range(120):
   try:
    with opener.open(base+'/api/local-session',timeout=1) as response:
     assert json.load(response)['instance']=='standalone';break
   except OSError:time.sleep(.5)
  else:raise AssertionError('Packaged desktop server failed to start')
  checks.append('desktop launcher starts local server with fresh user data')
  with sync_playwright() as p:
   browser=p.chromium.launch(executable_path=str(browser_path()),headless=True,args=['--no-sandbox'] if sys.platform=='linux' else [])
   page=browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(base+'/operations')
   page.get_by_role('link',name='使用邀请码加入或建立工作区').click()
   page.locator('[name=name]').fill('安装验收');page.locator('[name=username]').fill('build-owner');page.locator('[name=password]').fill('Build-fixture-only-92617!');page.get_by_role('button',name='建立工作区',exact=True).click()
   page.locator('[data-node=xhs]').wait_for();assert page.locator('[data-node]').count()>=9
   guide=page.context.request.get(base+'/operations/guide');assert guide.ok and '工作台' in guide.text()
   page.get_by_role('button',name='思跃 AI 实验室 · 模型配置',exact=True).click();page.locator('#lab-form').wait_for();page.screenshot(path=str(OUT/'installed-ui.png'));assert not errors,errors
   checks.extend(verify(page,base));assert not errors,errors
   browser.close();checks.append('fresh owner setup, workflow nodes and model configuration render')
  image=Path(tmp)/'ocr.png';im=Image.new('RGB',(600,100),'white');ImageDraw.Draw(im).text((15,25),'Siyue mathematics 123',fill='black',font_size=35);im.save(image)
  recognized=ocr(image);assert '123' in recognized['text'],recognized;checks.append('bundled OCR models read image offline')
  video=Path(tmp)/'sample.mp4'
  with av.open(str(video),'w') as container:
   stream=container.add_stream('aac',rate=16000);stream.layout='mono'
   frame=av.AudioFrame.from_ndarray(np.zeros((1,16000),dtype=np.float32),format='flt',layout='mono');frame.sample_rate=16000
   for packet in stream.encode(frame):container.mux(packet)
   for packet in stream.encode(None):container.mux(packet)
  decoded=audio(video,Path(tmp)/'audio.wav');assert .9<decoded['audio_duration']<1.2;checks.append('bundled PyAV decodes full local audio')
  whisper=ROOT/'operations/vendor/media'/('whisper-cli.exe' if os.name=='nt' else 'whisper-cli')
  with (OUT/'whisper.log').open('wb') as whisper_log:
   subprocess.run([str(whisper),'-m',str(whisper.parent/'ggml-large-v3-turbo-q5_0.bin'),'-f',str(Path(tmp)/'audio.wav'),'-l','zh','-t','2','--no-gpu'],check=True,stdout=whisper_log,stderr=whisper_log,timeout=180)
  checks.append('native whisper loads bundled model and transcribes local audio offline')
  worker=subprocess.run([str(python_path()),'-m','operations.douyin_worker','check'],cwd=ROOT,env=env,input='{}',text=True,encoding='utf-8',capture_output=True,timeout=30)
  (OUT/'douyin-worker.log').write_text(worker.stdout+'\n'+worker.stderr,encoding='utf-8')
  assert worker.returncode==0 and json.loads(worker.stdout)['available'],worker.stderr
  checks.append('Douyin subprocess imports (no network collection)')
  # Starting XHS must not download Chromium or ask for login during smoke checks.
  from operations import xiaohongshu_service as xhs
  xhs.private_root=lambda:data/'.private';os.environ['IP_DATA_DIR']=str(data);xhs.ensure_service();checks.append('bundled authenticated XHS service starts without login or collection')
  from operations.core import Store
  from operations.collection_policy import exclusive
  store=Store(data/'lock-test.sqlite3')
  with exclusive(store):
   probe='from operations.core import Store\nfrom operations.collection_policy import exclusive\nfrom pathlib import Path\nwith exclusive(Store(Path('+repr(str(store.path))+'))): pass'
   locked=subprocess.run([str(python_path()),'-c',probe],cwd=ROOT,capture_output=True);assert locked.returncode!=0
  checks.append('cross-process collection lock excludes a second reader')
 finally:
  (OUT/'acceptance.json').write_text(json.dumps(dict(passed=False,checks=checks,real_collection_calls=0,real_model_calls=0),ensure_ascii=False,indent=2),encoding='utf-8')
  # Only processes owned by this temporary acceptance run are terminated.
  for proc in psutil.process_iter(['pid','cmdline']):
   try:
    command=' '.join(proc.info['cmdline'] or [])
    if str(data) in command and proc.pid!=os.getpid():proc.kill()
   except (psutil.Error,OSError):pass
  for name in ['.private/xiaohongshu/service/server.pid']:
   f=data/name
   if f.exists():
    try:psutil.Process(int(f.read_text())).kill()
    except (psutil.Error,ValueError):pass
  # Backend inherits data via environment, so locate it by listening port.
  for proc in psutil.process_iter(['pid','cmdline']):
   try:
    if 'operations.serve' in (proc.info['cmdline'] or []) and proc.environ().get('IP_DATA_DIR')==str(data):proc.kill()
   except psutil.Error:pass
  launcher.wait(timeout=10);log.close()
(OUT/'acceptance.json').write_text(json.dumps(dict(passed=True,checks=checks,real_collection_calls=0,real_model_calls=0),ensure_ascii=False,indent=2))
print('Clean install acceptance passed',flush=True)
