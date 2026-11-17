"""Outbound HTTPS worker and content-addressed auxiliary replica, no listening ports."""
import base64, hashlib, http.client, json, logging, logging.handlers, os, shutil, sqlite3, ssl, subprocess, sys, threading, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit

class Node:
    def __init__(self,config):
        if os.name=='nt':
            import ctypes
            ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(),0x4000)
            cores=max(1,min(int(config.get('maxCpuCores',2)),os.cpu_count() or 1))
            ctypes.windll.kernel32.SetProcessAffinityMask(ctypes.windll.kernel32.GetCurrentProcess(),(1<<cores)-1)
        self.c=config; self.root=Path(config['data']); self.root.mkdir(parents=True,exist_ok=True)
        self.capacity=max(1,min(2,int(config.get('maxConcurrency',1 if config.get('nodeId') else 2))))
        self.pause_reason='checking-resources' if config.get('printPriority',False) else None
        self.resource_status={'cpuCount':os.cpu_count() or 1}
        self.script=Path(__file__).with_name('pdf_pixels.local.py')
        self.sha=hashlib.sha256(self.script.read_bytes()).hexdigest()
        if self.sha != config['scriptHash']: raise ValueError('Local script does not match configured contract')
        self.connections=threading.local()
        self.busy=False; self.stats={'objects':0,'bytes':0,'lastSync':None,'error':None,'skipped':0}; self.stop=threading.Event()
        self.active=set();self.active_started={};self.active_lock=threading.Lock();self.native_locks={};self.native_prefetch={}
        from concurrent.futures import ThreadPoolExecutor
        self.prefetch_pool=ThreadPoolExecutor(max_workers=1)
        self.dbpath=self.root/'mirror.sqlite'
        with sqlite3.connect(self.dbpath) as db:
            db.execute('CREATE TABLE IF NOT EXISTS replicas(key TEXT,sha TEXT,size INTEGER,verified REAL,PRIMARY KEY(key,sha))')
        (self.root/'blobs').mkdir(exist_ok=True); (self.root/'work').mkdir(exist_ok=True); (self.root/'logs').mkdir(exist_ok=True)
        handler=logging.handlers.RotatingFileHandler(self.root/'logs'/'node.log',maxBytes=2*1024**2,backupCount=3,encoding='utf8')
        logging.basicConfig(level=logging.INFO,handlers=[handler],format='%(asctime)s %(levelname)s %(message)s')
        # Interrupted pure computations are not resumed. Master leases decide local fallback.
        for directory in (self.root/'work').iterdir():
            if directory.is_dir(): shutil.rmtree(directory)
    def request(self,path,value=None,timeout=20):
        base=urlsplit(self.c['url'])
        if base.scheme!='https': raise ValueError('HTTPS required')
        for attempt in range(2):
            try:
                conn=getattr(self.connections,'http',None)
                if conn is None:
                    conn=http.client.HTTPSConnection(base.hostname,base.port or 443,timeout=timeout,context=ssl.create_default_context()); self.connections.http=conn
                conn.timeout=timeout
                if conn.sock: conn.sock.settimeout(timeout)
                conn.request('GET' if value is None else 'POST',base.path+path,body=None if value is None else json.dumps(value).encode(),headers={'Authorization':'Bearer '+self.c['token'],'Content-Type':'application/json','User-Agent':'SiyueAuxNode/1.0'})
                response=conn.getresponse()
                if response.status>=400:
                    response.read();response.close();raise HTTPError(self.c['url']+path,response.status,response.reason,response.headers,None)
                return response
            except (http.client.HTTPException,OSError):
                if getattr(self.connections,'http',None): self.connections.http.close()
                self.connections.http=None
                if attempt: raise
    def json(self,path,value=None,timeout=20):
        with self.request(path,value,timeout) as response: return json.load(response)
    def heartbeat_once(self):
        from native_contract import VERSION
        with self.active_lock:active=len(self.active)
        self.json('/node/heartbeat',dict(name=self.c.get('name','main-workplace'),host=os.environ.get('COMPUTERNAME','unknown'),busy=bool(active),active=active,capacity=self.capacity,pauseReason=self.pause_reason,nativeVersion=VERSION if self.c.get('nativeFingerprint') else None,nativeFingerprint=self.c.get('nativeFingerprint'),scriptHash=self.sha,freeBytes=shutil.disk_usage(self.root).free,mirror=self.stats.copy(),version='3.0',pid=os.getpid(),resources=self.resource_status.copy(),nativeTools=self.c.get('nativeTools',['pdftoppm','pdftotext','rapid'])))
    def resources(self):
        """Admission-only check: never stop a print service or kill an active task."""
        while not self.stop.is_set():
            reason=None
            try:
                if os.name=='nt':
                    import ctypes
                    class Memory(ctypes.Structure):
                        _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ('total','available','totalPage','availablePage','totalVirtual','availableVirtual','extended')]
                    m=Memory();m.length=ctypes.sizeof(m)
                    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):raise OSError('Memory probe')
                    self.resource_status['availableMemoryMb']=m.available//1024**2
                    if m.available<self.c.get('reserveMemoryMb',1536)*1024**2:reason='memory'
                    if self.c.get('printPriority',False):
                        r=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',"$ErrorActionPreference='Stop'; @(Get-CimInstance Win32_PrintJob -ErrorAction Stop).Count"],capture_output=True,text=True,timeout=8,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                        if r.returncode or not r.stdout.strip().isdigit():raise OSError('Print queue probe')
                        self.resource_status['printQueueJobs']=int(r.stdout.strip())
                        if int(r.stdout.strip()):reason='printing'
                if shutil.disk_usage(self.root).free<15*1024**3:reason='disk-space'
            except Exception:reason='resource-probe-unavailable'
            self.pause_reason=reason
            self.stop.wait(5)
    def heartbeat(self):
        while not self.stop.is_set():
            try: self.heartbeat_once()
            except Exception as e: logging.warning('Heartbeat %s',type(e).__name__)
            self.stop.wait(2)
    def execute(self,job):
        jid=job['id']
        if len(jid)!=32 or any(c not in '0123456789abcdef' for c in jid): raise ValueError('Invalid job id')
        with self.active_lock:self.active.add(jid);self.active_started[jid]=time.monotonic();self.busy=True
        directory=self.root/'work'/jid
        try:
            directory.mkdir()
            p=job['payload']; mode=p['mode']
            if mode=='native':
                from native_engine import execute
                result=execute(self,p,directory)
                self.json('/node/result',dict(id=jid,claim=job['claim'],ok=True,result=result),timeout=30)
                logging.info('Completed native %s %s %.3fs',jid,p['tool'],result['seconds']);return
            if mode not in ('audit','layout','ocr') or p['scriptHash']!=self.sha: raise ValueError('Unsupported task')
            for name in ('image','data'):
                content=base64.b64decode(p[name],validate=True)
                if len(content)>24*1024**2 or hashlib.sha256(content).hexdigest()!=p[name+'Hash']: raise ValueError('Input mismatch')
                (directory/name).write_bytes(content)
            output=directory/'result.json'
            command=[sys.executable,str(self.script),mode,str(directory/'image'),str(directory/'data'),str(output)]
            run=subprocess.run(command,cwd=directory,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)|getattr(subprocess,'BELOW_NORMAL_PRIORITY_CLASS',0))
            if run.returncode or not output.exists() or output.stat().st_size>4*1024**2: raise ValueError('Computation failed')
            result=json.loads(output.read_text(encoding='utf8')); self.json('/node/result',dict(id=jid,claim=job['claim'],ok=True,result=result))
            logging.info('Completed %s %s',jid,mode)
        except Exception as e:
            logging.warning('Task %s %s: %s',jid,type(e).__name__,str(e)[:350])
            try: self.json('/node/result',dict(id=jid,claim=job['claim'],ok=False))
            except Exception: pass
        finally:
            shutil.rmtree(directory,ignore_errors=True)
            with self.active_lock:self.active.discard(jid);self.active_started.pop(jid,None);self.busy=bool(self.active)
            try: self.heartbeat_once()
            except Exception: pass
    def replicate(self,item,db):
        sha=item['sha256']; size=item['size']
        if len(sha)!=64 or any(c not in '0123456789abcdef' for c in sha) or not 0<=size<=256*1024**2: raise ValueError('Invalid manifest')
        blob=self.root/'blobs'/sha[:2]/sha
        exists=db.execute('SELECT verified FROM replicas WHERE key=? AND sha=?',(item['key'],sha)).fetchone()
        if exists and blob.exists() and blob.stat().st_size==size and exists[0]>time.time()-86400: return
        if blob.exists():
            with blob.open('rb') as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
            if actual!=sha or blob.stat().st_size!=size:
                # Preserve the corrupt cache for diagnosis and redownload the same source version.
                blob.replace(blob.with_suffix('.corrupt-'+str(int(time.time()))))
                logging.warning('Repairing corrupt replica %s',sha)
        if shutil.disk_usage(self.root).free<15*1024**3 or self.stats['bytes']+size>self.c.get('quotaBytes',100*1024**3): raise OSError('Storage reserve reached')
        if not blob.exists():
            blob.parent.mkdir(exist_ok=True); temp=blob.with_suffix('.partial'); total=0; checksum=hashlib.sha256()
            try:
                with self.request('/node/object?'+urlencode(dict(key=item['key'],sha256=sha)),timeout=90) as response, temp.open('wb') as output:
                    while block:=response.read(256*1024):
                        total+=len(block)
                        if total>size: raise ValueError('Download size mismatch')
                        output.write(block); checksum.update(block)
                if total!=size or checksum.hexdigest()!=sha: raise ValueError('Download hash mismatch')
                os.replace(temp,blob); self.stats['bytes']+=size
            finally: temp.unlink(missing_ok=True)
        # Every restored/reused blob is verified, not trusted by filename alone.
        with blob.open('rb') as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual!=sha: raise ValueError('Replica corrupt')
        db.execute('INSERT OR REPLACE INTO replicas VALUES(?,?,?,?)',(item['key'],sha,size,time.time())); db.commit(); self.stats['objects']+=1
        self.stop.wait(.03)
    def sync(self):
        while not self.stop.is_set():
            try:
                offset=0
                with sqlite3.connect(self.dbpath,timeout=20) as db:
                    rows=db.execute('SELECT count(*),coalesce(sum(size),0) FROM (SELECT sha,max(size) size FROM replicas GROUP BY sha)').fetchone()
                    self.stats.update(objects=rows[0],bytes=rows[1],error=None,skipped=0)
                    while not self.stop.is_set():
                        manifest=self.json('/node/manifest?offset='+str(offset))
                        for item in manifest['entries']:
                            while self.busy and not self.stop.wait(1): pass
                            try: self.replicate(item,db)
                            except (ValueError, HTTPError) as e:
                                self.stats['skipped']+=1
                                logging.warning('Skipped changed replica %s: %s',item['key'],str(e)[:100])
                        offset=manifest['next']
                        if offset>=manifest['total']: break
                self.stats.update(lastSync=time.time(),error=('partial-sync:'+str(self.stats['skipped'])) if self.stats['skipped'] else None)
                logging.info('Replica scan complete: %s objects',self.stats['objects'])
            except Exception as e:
                self.stats['error']=type(e).__name__; logging.warning('Replica sync %s: %s',type(e).__name__,str(e)[:200])
            self.stop.wait(300)
    def run(self):
        from concurrent.futures import ThreadPoolExecutor
        threading.Thread(target=self.resources,daemon=True).start()
        threading.Thread(target=self.heartbeat,daemon=True).start()
        if self.c.get('mirrorEnabled',not bool(self.c.get('nodeId'))):threading.Thread(target=self.sync,daemon=True).start()
        pool=ThreadPoolExecutor(max_workers=self.capacity)
        while not self.stop.is_set():
            with self.active_lock: overdue=any(time.monotonic()-t>90 for t in self.active_started.values())
            if overdue:
                logging.error('Computation watchdog restarting the node');os._exit(2)
            try:
                if not self.pause_reason and len(self.active)<self.capacity:
                    job=self.json('/node/claim',{})
                    if job:
                        # Reserve before submitting; a slow thread start must not over-claim.
                        with self.active_lock:self.active.add(job['id']);self.active_started[job['id']]=time.monotonic();self.busy=True
                        pool.submit(self.execute,job)
            except Exception as e: logging.warning('Poll %s',type(e).__name__); self.stop.wait(5)
            self.stop.wait(.1)
if __name__=='__main__':
    Node(json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))).run()
