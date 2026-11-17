import subprocess,time,json
from pathlib import Path
url='https://www.siyuejn.com/miniapp/workbench/'
def safari(expr):
 return subprocess.run(['osascript','-e','tell application "Safari" to '+expr],check=True,text=True,capture_output=True,timeout=8).stdout.strip()
def check(suffix,expected,expected2,first=False):
 dest=url+('?v=central-load' if first else '?v=central-load')+'#central-research'+suffix
 start=time.monotonic()
 safari('set URL of current tab of front window to "'+dest+'"')
 for _ in range(100):
  time.sleep(.25)
  txt=safari('get text of current tab of front window')
  if expected in txt and expected2 in txt:
   return {'tab':suffix or 'problems','seconds':round(time.monotonic()-start,2),'visible':expected}
 raise RuntimeError('did not render '+suffix+' ; error visible='+str('暂时无法加载' in txt))
results=[check('','份问题讲义 / 教材','问题类型',True),check('?tab=pending','份待审核','主任'),check('?tab=courses','门可制作课程','制作课程'),check('?tab=problems&category=review','份待校对','问题类型'),check('','份问题讲义 / 教材','问题类型')]
print(json.dumps(results,ensure_ascii=False));Path('qa/safari.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
