"""Run after activation. Test real wrapper execution, cached offline output, and fallback."""
import json,subprocess,time,sys
from pathlib import Path
root=Path('/opt/siyue-auxiliary');w='/work/native-acceptance';host=Path('/opt/siyue-miniapp/data/worker-scratch/native-acceptance');mode=sys.argv[1]
opts=['-f','1','-l','1','-singlefile','-r','180','-x','17' if mode=='offline' else '50','-y','19' if mode=='offline' else '160','-W','517' if mode=='offline' else '1300','-H','733' if mode=='offline' else '900','-png']
def run(args):return subprocess.run(['docker','exec','siyue-miniapp-worker-1']+args,check=True,capture_output=True,text=True).stdout
run(['/usr/bin/pdftoppm']+opts+[w+'/source0.pdf',w+'/bridge-local'])
started=time.monotonic();run(['env','SIYUE_AUX_NATIVE_FORCE=1','/auxiliary-tools/pdftoppm']+opts+[w+'/source0.pdf',w+'/bridge-remote']);seconds=time.monotonic()-started
result=json.loads(run(['python3','-c',f'''from PIL import Image,ImageChops
import json
a=Image.open('{w}/bridge-local.png').convert('RGB');b=Image.open('{w}/bridge-remote.png').convert('RGB');print(json.dumps(dict(equal=a.size==b.size and ImageChops.difference(a,b).getbbox() is None)))''']))
assert result['equal'],result
if mode=='offline':assert seconds<9,seconds
if mode=='cached-offline':assert seconds<1,seconds
summary=dict(mode=mode,seconds=round(seconds,4),pixelsIdentical=True)
(root/('native-bridge-'+mode+'.json')).write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
