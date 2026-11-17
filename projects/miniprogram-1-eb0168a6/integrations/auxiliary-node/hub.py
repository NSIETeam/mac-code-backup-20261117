"""Isolated pull-only auxiliary queue. No access to business DB or arbitrary execution."""
import base64, hashlib, hmac, json, os, re, secrets, sqlite3, threading, time, uuid
import shutil
from contextlib import contextmanager
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from native_contract import VERSION, SHA, validate, cache_key

MAX_BODY = 36 * 1024 * 1024

def digest(data): return hashlib.sha256(data).hexdigest()
def jsonbytes(value): return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()

class Store:
    def __init__(self, config):
        self.c = config; self.root = Path(config['state']); self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / 'queue.sqlite'; self.lock = threading.Lock(); self.node = {}; self.nodes = {'legacy':self.node}; self.index = []
        self.warm = {}; self.input_grants = {}; self.node_reports = {}
        self.notification=threading.Condition()
        (self.root/'inputs').mkdir(exist_ok=True); (self.root/'native-results').mkdir(exist_ok=True)
        with self.db() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,state TEXT,payload TEXT,result TEXT,claim TEXT,expires REAL,created REAL);
            CREATE TABLE IF NOT EXISTS objects(key TEXT PRIMARY KEY,size INTEGER,mtime INTEGER,hash TEXT);
            CREATE TABLE IF NOT EXISTS events(at REAL,kind TEXT,detail TEXT);''')
            columns={r[1] for r in db.execute('PRAGMA table_info(jobs)')}
            for name,kind in [('node_id','TEXT'),('started','REAL'),('finished','REAL'),('attempts','INTEGER DEFAULT 0')]:
                if name not in columns:db.execute('ALTER TABLE jobs ADD COLUMN '+name+' '+kind)
            db.execute("UPDATE jobs SET state='cancelled' WHERE state IN ('queued','running')")
    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15); db.execute('PRAGMA busy_timeout=15000')
        try:
            with db: yield db
        finally: db.close()
    def event(self, kind, detail):
        with self.db() as db: db.execute('INSERT INTO events VALUES(?,?,?)', (time.time(), kind, detail[:1000]))
    def node_config(self,node_id):
        if node_id=='legacy':return {'enabled':True,'maxConcurrency':2,'mirror':True,**self.c.get('legacyNode',{})}
        return self.c.get('nodes',{}).get(node_id,{})
    def heartbeat(self, value, node_id='legacy'):
        if not isinstance(value,dict):raise ValueError('Invalid heartbeat')
        c=self.node_config(node_id)
        if not c or not c.get('enabled',True):raise ValueError('Unknown node')
        n={k:value[k] for k in ('name','host','busy','active','nativeVersion','nativeFingerprint','scriptHash','freeBytes','mirror','version','pid','pauseReason','capacity') if k in value}
        r=value.get('resources',{})
        if isinstance(r,dict):n['resources']={k:v for k,v in r.items() if k in ('cpuCount','availableMemoryMb','printQueueJobs') and isinstance(v,int) and 0<=v<=10**9}
        if 'nativeTools' in value:
            if not isinstance(value['nativeTools'],list) or any(t not in ('pdftoppm','pdftotext','rapid') for t in value['nativeTools']):raise ValueError('Invalid tools')
            n['nativeTools']=value['nativeTools']
        for k in ('active','freeBytes','capacity'):
            if k in n and (not isinstance(n[k],int) or isinstance(n[k],bool) or n[k]<0):raise ValueError('Invalid node counters')
        n.update(nodeId=node_id,seen=time.time())
        with self.lock:
            self.nodes[node_id]=n
            if node_id=='legacy':self.node=n
        with self.notification:self.notification.notify_all()
    def compatible(self,node_id,payload,fresh=15):
        c=self.node_config(node_id)
        with self.lock:n=self.nodes.get(node_id,{}).copy()
        if not c or not c.get('enabled',True) or n.get('seen',0)<time.time()-fresh or n.get('pauseReason') or n.get('freeBytes',0)<15*1024**3:return False
        if payload.get('mode')=='native':
            allowed=set(c.get('nativeTools',('pdftoppm','pdftotext','rapid')))&set(n.get('nativeTools',('pdftoppm','pdftotext','rapid')))
            if not allowed or payload.get('tool') and payload['tool'] not in allowed:return False
            return n.get('nativeVersion')==VERSION and bool(self.c.get('nativeFingerprint')) and n.get('nativeFingerprint')==self.c.get('nativeFingerprint')
        return n.get('scriptHash')==self.c['scriptHash']
    def capacity(self,node_id):
        c=self.node_config(node_id)
        with self.lock:n=self.nodes.get(node_id,{})
        return max(0,min(2,int(c.get('maxConcurrency',1)),int(n.get('capacity',2 if node_id=='legacy' else 1))))
    def choose(self,db,payload,exclude=()):
        with self.lock:ids=list(self.nodes)
        candidates=[]
        for node_id in ids:
            if node_id in exclude or not self.compatible(node_id,payload):continue
            count=db.execute("SELECT count(*) FROM jobs WHERE node_id=? AND state IN ('queued','running') AND expires>?",(node_id,time.time())).fetchone()[0]
            with self.lock:n=self.nodes[node_id].copy()
            occupied=max(count,n.get('active',0),int(n.get('busy',False)))
            cap=self.capacity(node_id)
            if occupied<cap:
                candidates.append((payload.get('inputSha') not in self.warm.get(node_id,()),occupied/max(cap,1),node_id))
        return min(candidates)[2] if candidates else None
    def available(self,payload):
        with self.db() as db:return self.choose(db,payload) is not None
    def ready(self):
        return self.available({'mode':'audit'})
    def native_ready(self):
        return self.available({'mode':'native'})
    def grant_input(self,node_id,payload,deadline):
        sha=payload.get('inputSha')
        if sha:
            with self.lock:
                self.input_grants={k:v for k,v in self.input_grants.items() if v>time.time()}
                self.input_grants[(node_id,sha)]=deadline+180
    def input_allowed(self,node_id,sha):
        if self.node_config(node_id).get('mirror',False):return True
        with self.lock:return self.input_grants.get((node_id,sha),0)>time.time()
    def recover(self,db):
        now=time.time()
        db.execute("UPDATE jobs SET state='cancelled',payload='{}',finished=? WHERE expires<? AND state IN ('queued','running')",(now,now))
        for jid,p,nid,attempts,deadline,state in db.execute("SELECT id,payload,node_id,attempts,expires,state FROM jobs WHERE state IN ('queued','running')").fetchall():
            with self.lock:seen=self.nodes.get(nid,{}).get('seen',0)
            payload=json.loads(p)
            if seen>=now-self.c.get('offlineSeconds',6) and (state=='running' or self.compatible(nid,payload)):continue
            other=self.choose(db,payload,exclude=(nid,)) if attempts<3 else None
            if other:
                db.execute("UPDATE jobs SET state='queued',node_id=?,claim=NULL,started=NULL WHERE id=?",(other,jid))
                self.grant_input(other,payload,deadline)
                db.execute('INSERT INTO events VALUES(?,?,?)',(now,'reassigned',jid+':'+str(nid)+'->'+other))
            else:
                db.execute("UPDATE jobs SET state='failed',claim=NULL,payload='{}',finished=? WHERE id=?",(now,jid))
                db.execute('INSERT INTO events VALUES(?,?,?)',(now,'node-offline',jid+':'+str(nid)))
    def native_input(self,sha):
        if not SHA.fullmatch(sha): raise ValueError('Invalid checksum')
        return self.root/'inputs'/sha
    def native_submit(self,payload):
        validate(payload)
        if payload['tool'] not in self.c.get('nativeTools',['pdftoppm','pdftotext','rapid']):return 503,{'error':'kept-on-primary'}
        key=cache_key(payload,self.c.get('nativeFingerprint',''))
        cached=self.root/'native-results'/key
        if cached.exists():
            try:
                result=json.loads(cached.read_text()); result['cacheHit']=True;os.utime(cached,None)
                return 200,dict(result=result)
            except (ValueError,OSError):cached.unlink(missing_ok=True)
        if payload.get('cacheOnly'):return 404,{'error':'cache-miss'}
        if not self.native_ready():return 503,{'error':'node-unavailable'}
        if not self.native_input(payload['inputSha']).is_file():return 409,{'error':'input-not-staged'}
        wait=60 if payload['tool'] in ('tesseract','rapid') else 20
        code,value=self.submit(payload,wait=wait,native=True)
        if code==200 and value['result'].get('cold'):return 503,{'error':'source-prefetching'}
        if code==200:
            tmp=cached.with_suffix('.tmp-'+uuid.uuid4().hex);tmp.write_text(json.dumps(value['result']));os.replace(tmp,cached)
            # Keep queue history, but retain large bytes only once in the bounded cache.
            with self.db() as db:db.execute("UPDATE jobs SET result=NULL WHERE id=? AND state='done'",(value['id'],))
        return code,value
    def submit(self, payload, wait=10, native=False):
        if not native and (payload.get('mode') not in ('audit','layout','ocr') or payload.get('scriptHash') != self.c['scriptHash']): return 400, {'error':'unsupported-contract'}
        # Validate before storing. Node receives fixed filenames, never caller paths or commands.
        for name in (() if native else ('image','data')):
            value = base64.b64decode(payload[name], validate=True)
            if len(value) > 24*1024**2: return 413, {'error':'input-too-large'}
            if digest(value) != payload[name+'Hash']: return 400, {'error':'input-hash'}
        if not (self.native_ready() if native else self.ready()): return 503, {'error':'node-unavailable'}
        jid = uuid.uuid4().hex; deadline = time.time()+wait
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self.recover(db)
            if db.execute("SELECT COUNT(*) FROM jobs WHERE state IN ('queued','running') AND expires>?", (time.time(),)).fetchone()[0] >= self.c.get('maxInFlight',6): return 503, {'error':'node-busy'}
            node_id=self.choose(db,payload)
            if node_id is None:return 503,{'error':'node-busy'}
            db.execute('INSERT INTO jobs(id,state,payload,result,claim,expires,created,node_id) VALUES(?,?,?,?,?,?,?,?)',(jid,'queued',json.dumps(payload),None,None,deadline,time.time(),node_id))
            self.grant_input(node_id,payload,deadline)
        if native:self.event('native.request.'+payload['tool'],payload['inputSha']+':'+jid)
        with self.notification:self.notification.notify_all()
        while time.time() < deadline:
            with self.db() as db:
                db.execute('BEGIN IMMEDIATE');self.recover(db)
                row = db.execute('SELECT state,result FROM jobs WHERE id=?',(jid,)).fetchone()
            if row[0] == 'done': return 200, {'id':jid,'result':json.loads(row[1])}
            if row[0] == 'failed': return 503, {'error':'node-failed'}
            time.sleep(.1)
        with self.db() as db: db.execute("UPDATE jobs SET state='cancelled',payload='{}',finished=? WHERE id=? AND state IN ('queued','running')",(time.time(),jid))
        self.event('fallback', jid); return 504, {'error':'node-timeout'}
    def claim(self,node_id='legacy'):
        now=time.time()
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self.recover(db)
            if db.execute("SELECT count(*) FROM jobs WHERE state='running' AND node_id=? AND expires>?",(node_id,now)).fetchone()[0]>=self.capacity(node_id): return None
            row=db.execute("SELECT id,payload,expires FROM jobs WHERE state='queued' AND node_id=? AND expires>? ORDER BY created LIMIT 1",(node_id,now)).fetchone()
            if not row: return None
            payload=json.loads(row[1])
            if not self.compatible(node_id,payload):return None
            claim=secrets.token_hex(32)
            db.execute("UPDATE jobs SET state='running',claim=?,started=?,attempts=attempts+1 WHERE id=?",(digest(claim.encode()),now,row[0]))
            return dict(id=row[0],payload=payload,claim=claim)
    def finish(self, value,node_id='legacy'):
        claim=digest(value.get('claim','').encode()); jid=value.get('id','')
        result=value.get('result'); state='done' if value.get('ok') and isinstance(result,dict) else 'failed'
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT payload FROM jobs WHERE id=?',(jid,)).fetchone()
            original=json.loads(row[0]) if row else {}
            count=db.execute("UPDATE jobs SET state=?,result=?,payload='{}',finished=? WHERE id=? AND state='running' AND node_id=? AND claim=? AND expires>?",(state,json.dumps(result),time.time(),jid,node_id,claim,time.time())).rowcount
        if count:
            self.event(state,jid)
            if state=='done' and original.get('inputSha') and not result.get('cold'):
                with self.lock:
                    warm=self.warm.setdefault(node_id,[]);sha=original['inputSha']
                    if sha in warm:warm.remove(sha)
                    warm.append(sha);del warm[:-128]
            if state=='done' and original.get('mode')=='native' and not result.get('cold'):self.event('native.'+original['tool'],jid)
        return count == 1
    def scan(self):
        root=Path(self.c['objects']).resolve(); entries=[]
        with self.db() as db:
            for p in root.rglob('*'):
                if not p.is_file() or p.is_symlink() or '.tmp-' in p.name: continue
                key=p.relative_to(root).as_posix()
                if key.startswith('system/'): continue  # Live queue telemetry is not durable business content.
                st=p.stat()
                if st.st_size>256*1024**2: continue
                old=db.execute('SELECT size,mtime,hash FROM objects WHERE key=?',(key,)).fetchone()
                if old and old[:2] == (st.st_size,st.st_mtime_ns): sha=old[2]
                else:
                    with p.open('rb') as stream: sha=hashlib.file_digest(stream,'sha256').hexdigest()
                    if p.stat().st_mtime_ns != st.st_mtime_ns: continue
                    db.execute('INSERT OR REPLACE INTO objects VALUES(?,?,?,?)',(key,st.st_size,st.st_mtime_ns,sha))
                entries.append(dict(key=key,size=st.st_size,sha256=sha))
                if len(entries)%40==0: db.commit()
        with self.lock: self.index=entries
        self.event('mirror-index',str(len(entries)))
    def object(self,key,sha):
        with self.lock: entry=next((x for x in self.index if x['key']==key and x['sha256']==sha),None)
        if not entry: raise FileNotFoundError()
        root=Path(self.c['objects']).resolve(); path=(root/key).resolve()
        if not path.is_relative_to(root) or path.is_symlink(): raise FileNotFoundError()
        return path
    def maintenance(self):
        while True:
            try:
                self.scan()
                with self.db() as db:
                    db.execute('DELETE FROM jobs WHERE created<?',(time.time()-86400,))
                    db.execute('DELETE FROM events WHERE at<?',(time.time()-30*86400,))
                # Reclaim only staged inputs and derived results. Keep originals/replicas untouched.
                for directory in ('inputs','native-results'):
                    files=[p for p in (self.root/directory).iterdir() if p.is_file()]
                    total=sum(p.stat().st_size for p in files)
                    for p in sorted(files,key=lambda p:p.stat().st_mtime):
                        if p.stat().st_mtime>time.time()-3600 and (total<2*1024**3 or p.stat().st_mtime>time.time()-300):continue
                        size=p.stat().st_size;p.unlink(missing_ok=True);total-=size
            except Exception as e: self.event('maintenance-error',type(e).__name__)
            time.sleep(600)

class BoundedServer(ThreadingHTTPServer):
    daemon_threads=True
    slots=threading.BoundedSemaphore(32)
    def process_request(self,request,address):
        if not self.slots.acquire(blocking=False):
            request.close(); return
        request.settimeout(20)
        try: super().process_request(request,address)
        except Exception:
            self.slots.release(); raise
    def process_request_thread(self,request,address):
        try: super().process_request_thread(request,address)
        finally: self.slots.release()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def respond(self,code,value):
        data=jsonbytes(value); self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def auth(self):
        internal=urlsplit(self.path).path.startswith('/internal/')
        c=self.server.store.c;provided=self.headers.get('Authorization','')
        if internal:return hmac.compare_digest(provided,'Bearer '+c['dispatchToken'])
        candidates=[('legacy',c.get('nodeToken',''))]+[(nid,n.get('token','')) for nid,n in c.get('nodes',{}).items() if n.get('enabled',True)]
        for nid,token in candidates:
            if token and hmac.compare_digest(provided,'Bearer '+token) and self.server.store.node_config(nid).get('enabled',True):self.node_id=nid;return True
        return False
    def body(self):
        n=int(self.headers.get('Content-Length','0'))
        if not 0 < n <= MAX_BODY: raise ValueError('size')
        return json.loads(self.rfile.read(n))
    def do_PUT(self):
        if not self.auth():return self.respond(401,{'error':'unauthorized'})
        parts=urlsplit(self.path)
        if parts.path=='/node/runtime':return self.runtime_upload(parts)
        if parts.path!='/internal/input':return self.respond(404,{'error':'not-found'})
        temp=None
        try:
            sha=parse_qs(parts.query)['sha'][0];s=self.server.store;path=s.native_input(sha)
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=128*1024**2 or shutil.disk_usage(s.root).free<4*1024**3:raise ValueError('Input limit')
            temp=path.with_suffix('.tmp-'+uuid.uuid4().hex);checksum=hashlib.sha256();left=length
            with temp.open('wb') as f:
                while left:
                    b=self.rfile.read(min(left,256*1024))
                    if not b:raise ValueError('Truncated input')
                    f.write(b);checksum.update(b);left-=len(b)
            if checksum.hexdigest()!=sha:raise ValueError('Input checksum')
            os.replace(temp,path);return self.respond(200,{'staged':True})
        except (ValueError,KeyError):return self.respond(400,{'error':'invalid-input'})
        finally:
            if temp:temp.unlink(missing_ok=True)
    def runtime_upload(self,parts):
        """Temporary pilot export: only the explicitly enabled existing node can upload.

        This archive is never executed on the hub. An administrator must inspect it
        and pin its hash in each recipient's installer before any download is allowed.
        """
        s=self.server.store;temp=None
        if self.node_id!='legacy' or not s.c.get('runtimeExportEnabled',False):return self.respond(403,{'error':'export-disabled'})
        try:
            sha=parse_qs(parts.query)['sha'][0]
            if not SHA.fullmatch(sha):raise ValueError('checksum')
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=768*1024**2 or shutil.disk_usage(s.root).free<4*1024**3:raise ValueError('size')
            folder=s.root/'runtime';folder.mkdir(exist_ok=True)
            target=folder/sha;temp=folder/(sha+'.tmp-'+uuid.uuid4().hex)
            checksum=hashlib.sha256();left=length
            with temp.open('wb') as f:
                while left:
                    b=self.rfile.read(min(left,256*1024))
                    if not b:raise ValueError('truncated')
                    checksum.update(b);f.write(b);left-=len(b)
            if checksum.hexdigest()!=sha:raise ValueError('checksum')
            os.replace(temp,target);s.event('runtime-export',sha+':'+str(length))
            return self.respond(200,{'sha256':sha,'size':length})
        except (KeyError,ValueError):return self.respond(400,{'error':'invalid-runtime'})
        finally:
            if temp:temp.unlink(missing_ok=True)
    def do_POST(self):
        if not self.auth(): return self.respond(401,{'error':'unauthorized'})
        try:
            s=self.server.store; path=urlsplit(self.path).path; value=self.body()
            if path=='/node/report':
                if not isinstance(value,dict) or len(jsonbytes(value))>16384:raise ValueError('Diagnostic size')
                with s.lock:s.node_reports[self.node_id]={'at':time.time(),**value}
                return self.respond(200,{'ok':True})
            if path=='/node/heartbeat': s.heartbeat(value,self.node_id); return self.respond(200,{'ok':True})
            if path=='/node/claim':
                with s.notification:
                    job=s.claim(self.node_id)
                    if job is None:s.notification.wait(5);job=s.claim(self.node_id)
                return self.respond(200,job)
            if path=='/node/result': return self.respond(200 if s.finish(value,self.node_id) else 409,{'ok':True})
            if path=='/internal/pixels':
                code,data=s.submit(value,wait=s.c.get('taskTimeout',3)); return self.respond(code,data)
            if path=='/internal/native':
                code,data=s.native_submit(value);return self.respond(code,data)
            return self.respond(404,{'error':'not-found'})
        except (ValueError,KeyError): return self.respond(400,{'error':'invalid-request'})
        except (BrokenPipeError, ConnectionResetError): return
        except Exception as e:
            self.server.store.event('request-error',type(e).__name__); return self.respond(500,{'error':'internal'})
    def do_GET(self):
        if not self.auth(): return self.respond(401,{'error':'unauthorized'})
        s=self.server.store; parts=urlsplit(self.path)
        try:
            if parts.path=='/node/runtime':
                sha=parse_qs(parts.query)['sha'][0]
                if not SHA.fullmatch(sha) or sha!=s.node_config(self.node_id).get('runtimeSha'):return self.respond(403,{'error':'runtime-not-assigned'})
                with (s.root/'runtime'/sha).open('rb') as f:
                    self.send_response(200);self.send_header('Content-Length',str(os.fstat(f.fileno()).st_size));self.end_headers()
                    while b:=f.read(256*1024):self.wfile.write(b)
                return
            if parts.path=='/internal/status':
                with s.lock: node=s.node.copy(); count=len(s.index); nodes={k:v.copy() for k,v in s.nodes.items()}
                with s.db() as db:
                    jobs=dict(db.execute('SELECT state,count(*) FROM jobs GROUP BY state').fetchall())
                    events=db.execute('SELECT at,kind,detail FROM events ORDER BY at DESC LIMIT 20').fetchall()
                    per_node=db.execute("SELECT node_id,state,count(*),avg(finished-started) FROM jobs GROUP BY node_id,state").fetchall()
                return self.respond(200,dict(version='3.0',node=node,nodes=nodes,nodeReports=s.node_reports.copy(),ready=s.ready(),nativeReady=s.native_ready(),indexedObjects=count,jobs=jobs,perNode=per_node,events=events))
            if parts.path=='/internal/input':
                path=s.native_input(parse_qs(parts.query)['sha'][0]);return self.respond(200 if path.is_file() else 404,{'exists':path.is_file()})
            if parts.path=='/node/input':
                sha=parse_qs(parts.query)['sha'][0]
                if not s.input_allowed(self.node_id,sha):return self.respond(403,{'error':'not-assigned'})
                path=s.native_input(sha);f=path.open('rb');os.utime(path,None)
                with f:
                    self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Content-Length',str(os.fstat(f.fileno()).st_size));self.end_headers()
                    while b:=f.read(256*1024):self.wfile.write(b)
                return
            if parts.path=='/node/manifest':
                if not s.node_config(self.node_id).get('mirror',False):return self.respond(200,dict(entries=[],total=0,next=0))
                offset=max(0,int(parse_qs(parts.query).get('offset',['0'])[0]))
                with s.lock: rows=s.index[offset:offset+500]; total=len(s.index)
                return self.respond(200,dict(entries=rows,total=total,next=offset+len(rows)))
            if parts.path=='/node/object':
                if not s.node_config(self.node_id).get('mirror',False):return self.respond(403,{'error':'replica-disabled'})
                q=parse_qs(parts.query); path=s.object(q['key'][0],q['sha256'][0]); f=path.open('rb')
                with f:
                    self.send_response(200); self.send_header('Content-Length',str(os.fstat(f.fileno()).st_size)); self.end_headers()
                    while block:=f.read(256*1024): self.wfile.write(block)
                return
            return self.respond(404,{'error':'not-found'})
        except (FileNotFoundError,KeyError,ValueError): return self.respond(404,{'error':'not-found'})
        except (BrokenPipeError, ConnectionResetError): return

def main():
    import sys
    config=json.loads(Path(sys.argv[1]).read_text()); store=Store(config)
    server=BoundedServer(('127.0.0.1',config.get('port',5096)),Handler); server.store=store
    threading.Thread(target=store.maintenance,daemon=True).start(); server.serve_forever()
if __name__=='__main__': main()
