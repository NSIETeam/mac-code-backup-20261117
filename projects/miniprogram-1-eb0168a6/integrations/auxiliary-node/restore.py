"""Local admin recovery from auxiliary replica. Never overwrites an existing destination."""
import argparse,hashlib,json,shutil,sqlite3
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--data',default=r'E:\SiyueAuxNode');p.add_argument('--key',required=True);p.add_argument('--sha');p.add_argument('--output',required=True);a=p.parse_args()
root=Path(a.data);db=sqlite3.connect(root/'mirror.sqlite')
try:
 row=db.execute('SELECT sha,size,verified FROM replicas WHERE key=?'+(' AND sha=?' if a.sha else '')+' ORDER BY verified DESC LIMIT 1', (a.key,a.sha) if a.sha else (a.key,)).fetchone()
finally: db.close()
if not row:raise SystemExit('File has not yet synchronized; retrieve original from primary.')
sha,size,at=row;source=root/'blobs'/sha[:2]/sha;dest=Path(a.output)
with source.open('rb') as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
if actual!=sha or source.stat().st_size!=size:raise SystemExit('Replica integrity check failed; recovery stopped.')
dest.parent.mkdir(parents=True,exist_ok=True)
with source.open('rb') as stream,dest.open('xb') as out:shutil.copyfileobj(stream,out)
print(json.dumps(dict(restored=True,sha256=sha,size=size,verified=at)))
