#!/usr/bin/env python3
"""Fetch a pinned, already-checked build to a restricted workstation staging area.

This script never launches repository code or switches a service route.
"""
import hashlib,json,os,re,shutil,sys,tarfile,tempfile,urllib.request,uuid
from pathlib import Path,PurePosixPath
MAX=150*1024*1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def open_pinned(request,timeout):return urllib.request.build_opener(NoRedirect()).open(request,timeout=timeout)

def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def unpack(archive,output):
 total=0
 with tarfile.open(archive,'r:*') as t:
  entries=t.getmembers()
  if len(entries)>15000:raise ValueError('too many entries')
  for m in entries:
   p=PurePosixPath(m.name)
   if p.is_absolute() or '..' in p.parts or '\\' in m.name or ':' in m.name or not(m.isfile() or m.isdir()):raise ValueError('unsafe archive path')
   total+=m.size
   if total>MAX:raise ValueError('archive expansion exceeds quota')
  # Every member was verified before extraction; no symlinks or special files allowed.
  t.extractall(output,filter='data')
 return total

def receive(config,manifest,root):
 expected=manifest['sha256'];bid=str(uuid.UUID(manifest['buildId']));commit=manifest['commit'];project=manifest['project']
 if not re.fullmatch('[a-f0-9]{64}',expected) or not re.fullmatch('[a-f0-9]{40,64}',commit) or not re.fullmatch('[a-z][a-z0-9-]{2,47}',project):raise ValueError('invalid build identity')
 if manifest.get('testOnly') is not True or not 0<manifest['size']<=MAX:raise ValueError('invalid build limit')
 if not config['url'].startswith('https://'):raise ValueError('HTTPS required')
 root.mkdir(parents=True,exist_ok=True);dest=root/bid
 if dest.exists():
  old=json.loads((dest/'manifest.json').read_text(encoding='utf-8'))
  if old!=manifest or sha(dest/'artifact.tar')!=expected:raise ValueError('existing snapshot mismatch')
  return old
 with tempfile.TemporaryDirectory(prefix='.incoming-',dir=root) as td:
  stage=Path(td);archive=stage/'artifact.tar';request=urllib.request.Request(config['url']+'/node/runtime?sha='+expected,headers={'Authorization':'Bearer '+config['token']})
  with open_pinned(request,timeout=120) as response,archive.open('wb') as out:
   size=0
   while b:=response.read(256*1024):
    size+=len(b)
    if size>manifest['size']:raise ValueError('archive size exceeded')
    out.write(b)
  if size!=manifest['size'] or sha(archive)!=expected:raise ValueError('archive checksum mismatch')
  unpack(archive,stage/'files');(stage/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False),encoding='utf-8')
  os.replace(stage,dest)
 return manifest

def main():
 config=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
 manifest=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8-sig'))
 print(json.dumps(receive(config,manifest,Path(sys.argv[3])),ensure_ascii=False))
if __name__=='__main__':main()
