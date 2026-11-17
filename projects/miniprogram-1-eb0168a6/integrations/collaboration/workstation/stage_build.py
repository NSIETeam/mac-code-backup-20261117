#!/usr/bin/env python3
"""Stage an immutable, already-checked project build for workstation migration.

Only an existing controller job ID is accepted. This tool neither executes repository
code nor changes a project, preview, production route, or credentials.
"""
import argparse, hashlib, json, os, re, shutil, sqlite3, tarfile, tempfile, uuid
from pathlib import Path
from contextlib import closing

MAX_BYTES=150*1024*1024

def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def stage(root: Path, output: Path, job_id: str):
    job_id=str(uuid.UUID(job_id));root=root.resolve();output=output.resolve()
    with closing(sqlite3.connect((root/'delivery.db').as_uri()+'?mode=ro',uri=True)) as db:
        row=db.execute('SELECT kind,state,request,result FROM jobs WHERE id=?',(job_id,)).fetchone()
    if not row or row[0]!='build' or row[1]!='succeeded':raise ValueError('build has not succeeded')
    request,result=map(json.loads,row[2:]);key=request.get('key','');commit=request.get('sha','');expected=result.get('digest','')
    if not re.fullmatch(r'[a-z][a-z0-9-]{2,47}',key):raise ValueError('invalid project key')
    if not re.fullmatch(r'[a-f0-9]{40,64}',commit) or result.get('sha')!=commit:raise ValueError('build commit mismatch')
    if not re.fullmatch(r'[a-f0-9]{64}',expected):raise ValueError('invalid build digest')
    artifact=root/'artifacts'/f'{job_id}.tar'
    if not artifact.is_file() or artifact.is_symlink() or artifact.stat().st_size>MAX_BYTES or digest(artifact)!=expected:raise ValueError('artifact verification failed')
    total=0
    with tarfile.open(artifact,'r:*') as archive:
        members=archive.getmembers()
        if len(members)>15000:raise ValueError('too many entries')
        for m in members:
            p=Path(m.name)
            if p.is_absolute() or '..' in p.parts or '\\' in m.name or not (m.isfile() or m.isdir()):raise ValueError('unsafe artifact entry')
            total+=m.size
            if total>MAX_BYTES:raise ValueError('artifact expansion limit')
    output.mkdir(mode=0o700,parents=True,exist_ok=True)
    dest=output/job_id
    if dest.exists():
        old=json.loads((dest/'manifest.json').read_text())
        if old['sha256']!=expected or digest(dest/'artifact.tar')!=expected:raise ValueError('existing snapshot mismatch')
        return old
    manifest=dict(schema=1,project=key,buildId=job_id,commit=commit,sha256=expected,size=artifact.stat().st_size,expandedBytes=total,entries=len(members),origin='collaboration-ci',testOnly=True)
    with tempfile.TemporaryDirectory(prefix='.stage-',dir=output) as tmp:
        d=Path(tmp);shutil.copyfile(artifact,d/'artifact.tar')
        if digest(d/'artifact.tar')!=expected:raise ValueError('copy mismatch')
        (d/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        for f in d.iterdir():f.chmod(0o600)
        os.rename(d,dest)
    return manifest

def main():
    p=argparse.ArgumentParser();p.add_argument('build_id');p.add_argument('--root',type=Path,default=Path('/opt/siyue-collaboration'));p.add_argument('--output',type=Path,default=Path('/opt/siyue-auxiliary/pilot-20261116/project-builds'))
    a=p.parse_args();print(json.dumps(stage(a.root,a.output,a.build_id),ensure_ascii=False))
if __name__=='__main__':main()
