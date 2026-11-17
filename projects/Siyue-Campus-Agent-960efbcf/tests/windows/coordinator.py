"""Loopback-only acceptance fixture; never contacts a production service."""
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json, hashlib, uuid, datetime
root = Path('diagnostics'); root.mkdir(exist_ok=True)
def test_pdf():
    stream = b'BT /F1 24 Tf 72 720 Td (Siyue automated print test) Tj ET'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>', b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    out = b'%PDF-1.4\n'; offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out)); out += f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
    start = len(out); out += b'xref\n0 6\n0000000000 65535 f \n'
    for offset in offsets[1:]: out += f'{offset:010d} 00000 n \n'.encode()
    return out + f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
pdf = test_pdf()
reports, heartbeats, begins, enrollments = [], [], [], 0
profiles, registrations = [], []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, body=None, code=200):
        data = json.dumps(body).encode() if body is not None else b''
        self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def save(self):
        (root/'coordinator-state.json').write_text(json.dumps({'reports':reports,'heartbeats':heartbeats[-30:],'begins':begins,'enrollments':enrollments,'profiles':profiles,'registrations':registrations}),encoding='utf-8')
    def do_GET(self):
        if self.path == '/agent/pairings/status': return self.respond({'state':'connected','id':'c75cbf83-f0b4-4cd6-9103-9abfb5cfe663'})
        if self.path == '/files/fixture.pdf':
            self.send_response(200); self.send_header('Content-Length',str(len(pdf))); self.end_headers(); self.wfile.write(pdf); return
        if self.path == '/state': return self.respond({'reports':reports,'heartbeats':heartbeats,'begins':begins,'enrollments':enrollments,'profiles':profiles,'registrations':registrations})
        return self.respond({},404)
    def do_POST(self):
        global enrollments
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            parts=[]
            while True:
                size=int(self.rfile.readline().split(b';')[0].strip(),16)
                if size == 0:
                    while self.rfile.readline().strip(): pass
                    break
                parts.append(self.rfile.read(size)); self.rfile.read(2)
            raw=b''.join(parts)
        else:
            raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
        body=json.loads(raw or b'{}')
        if self.path == '/agent/enrollments':
            enrollments+=1; registrations.append(body); self.save(); return self.respond({'pollToken':'local-fixture-poll','deviceToken':'local-fixture-device'})
        if self.path == '/agent/installation-profile':
            profiles.append(body); self.save(); return self.respond({'name':body.get('printerAlias'),'enabled':True})
        if self.path == '/agent/heartbeat':
            heartbeats.append({'version':body.get('agentVersion'),'complete':body.get('complete'),'printers':body.get('printers'),'executions':body.get('executions',[]),'monitorVersion':body.get('monitorVersion'),'monitorError':body.get('monitorError')}); self.save(); return self.respond({})
        if self.path == '/agent/operations/claim': return self.respond(code=204)
        if self.path == '/agent/claim':
            marker=root/'next-job.json'
            if not marker.exists(): return self.respond(code=204)
            job=json.loads(marker.read_text(encoding='utf-8-sig')); marker.unlink()
            job.update(claimToken='local-fixture-claim',leaseUntil=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=5)).isoformat(),queueName='Siyue Acceptance PDF',copies=1,duplex=False,pdfUrl='http://localhost:8765/files/fixture.pdf',sha256=hashlib.sha256(pdf).hexdigest())
            return self.respond(job)
        if self.path.endswith('/begin'):
            begins.append(self.path.split('/')[3]); self.save(); return self.respond({})
        if self.path.endswith('/report'):
            reports.append({'id':self.path.split('/')[3],'state':body.get('state'),'detail':body.get('detail')}); self.save(); return self.respond({})
        return self.respond({},404)
HTTPServer(('127.0.0.1',8765),Handler).serve_forever()
