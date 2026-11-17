#!/usr/bin/env python3
"""Resumable verified image transfer through the workstation's pinned SSH tunnel."""
import json,pathlib,shlex,subprocess,sys,re,time,fcntl,tempfile
ACCESS=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/workstation-access')
SSH=['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(ACCESS/'workstation_known_hosts'),'-i',str(ACCESS/'controller'),'-p','5399','root@127.0.0.1']
def run(script,**kwargs):return subprocess.run([*SSH,'python3 -c '+shlex.quote(script)],check=True,**kwargs)
def transfer():
 receipt=json.loads(pathlib.Path(sys.argv[1]).read_text());sha=receipt['sha256'];size=receipt['size']
 if not re.fullmatch('[a-f0-9]{64}',sha) or not 0<size<4*1024**3:raise ValueError('Invalid image manifest')
 path='/opt/siyue-workstation/images/'+sha+'.tar.gz'
 probe=f"import pathlib,json; p=pathlib.Path({path!r}); q=p.with_suffix('.part'); print(json.dumps(dict(complete=p.exists(),offset=q.stat().st_size if q.exists() else 0)))"
 state=json.loads(run(probe,capture_output=True,text=True).stdout)
 if state['complete']:print(json.dumps(dict(state='present',sha256=sha)));return
 offset=state['offset']
 if not 0<=offset<=size:raise ValueError('Unexpected partial image size')
 script=f'''import pathlib,sys,hashlib,os,fcntl,json
p=pathlib.Path({path!r});p.parent.mkdir(parents=True,exist_ok=True);q=p.with_suffix('.part')
with q.open('ab') as f:
 fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
 if f.tell()!={offset}:raise RuntimeError('Concurrent transfer detected')
 while True:
  b=sys.stdin.buffer.read(256*1024)
  if not b:break
  if f.tell()+len(b)>{size}:raise RuntimeError('Image exceeds pinned size')
  f.write(b)
 f.flush();os.fsync(f.fileno())
if q.stat().st_size!={size}:raise RuntimeError('Image transfer incomplete; partial retained')
with q.open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
if actual!={sha!r}:raise RuntimeError('Image checksum mismatch')
q.replace(p);print(json.dumps(dict(state='verified',sha256=actual,size=p.stat().st_size)))
'''
 print(json.dumps(dict(state='transferring',offset=offset,size=size)),flush=True)
 # Bulk images share the return link with control traffic. Keep a bounded,
 # single stream and retain partial data after a transport failure.
 rate=512*1024
 with pathlib.Path(receipt['file']).open('rb') as f,tempfile.TemporaryFile() as out,tempfile.TemporaryFile() as err:
  f.seek(offset);p=subprocess.Popen([*SSH,'python3 -c '+shlex.quote(script)],stdin=subprocess.PIPE,stdout=out,stderr=err)
  started=time.monotonic();sent=0
  try:
   while True:
    chunk=f.read(64*1024)
    if not chunk:break
    delay=started+(sent+len(chunk))/rate-time.monotonic()
    if delay>0:time.sleep(delay)
    p.stdin.write(chunk);p.stdin.flush();sent+=len(chunk)
   p.stdin.close();code=p.wait(timeout=120)
   out.seek(0);err.seek(0)
   if code:raise RuntimeError('Image transfer failed; partial retained: '+err.read(2048).decode(errors='replace'))
   print(out.read(2048).decode(),flush=True)
  finally:
   if p.poll() is None:p.terminate();p.wait(timeout=15)

def main():
 lock=ACCESS/'image-transfer.lock'
 with lock.open('a') as guard:
  fcntl.flock(guard,fcntl.LOCK_EX)
  transfer()
if __name__=='__main__':main()
