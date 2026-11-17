"""Fetch the pinned signed-off OS image. Does not extract or execute it."""
import hashlib,json,os,re,sys,tempfile,urllib.request
from pathlib import Path

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def open_pinned(request,timeout):return urllib.request.build_opener(NoRedirect()).open(request,timeout=timeout)

def download(config,root,spec):
 sha=spec['sha256'];size=spec['size']
 if not re.fullmatch('[a-f0-9]{64}',sha) or not isinstance(size,int) or not 0<size<=128*1024**2:raise ValueError('invalid image metadata')
 if not config['url'].startswith('https://'):raise ValueError('HTTPS required')
 root.mkdir(parents=True,exist_ok=True);dest=root/('ubuntu-'+sha+'.tar.gz')
 if dest.exists():
  with dest.open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
  if actual!=sha or dest.stat().st_size!=size:raise ValueError('existing image mismatch')
  return dest
 fd,name=tempfile.mkstemp(prefix='.download-',dir=root);temp=Path(name)
 try:
  h=hashlib.sha256();count=0;q=urllib.request.Request(config['url']+'/node/runtime?sha='+sha,headers={'Authorization':'Bearer '+config['token']})
  with os.fdopen(fd,'wb') as output,open_pinned(q,timeout=120) as response:
   while b:=response.read(256*1024):
    count+=len(b)
    if count>size:raise ValueError('image too large')
    h.update(b);output.write(b)
  if count!=size or h.hexdigest()!=sha:raise ValueError('image checksum mismatch')
  os.replace(temp,dest);return dest
 finally:temp.unlink(missing_ok=True)

if __name__=='__main__':
 cfg=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'));spec=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8-sig'))
 print(json.dumps({'path':str(download(cfg,Path(sys.argv[3]),spec))}))
