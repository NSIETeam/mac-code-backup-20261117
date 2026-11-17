"""Stage the exact source JRE without changing the application's running version."""
import json,pathlib,subprocess,tarfile,time
from crm_candidate_common import *

JAVA='/usr/lib/jvm/java-17-openjdk-amd64'
JAVA_SHA='2912fff3419213a954f47c2f179e5d19b33ff3aacf63ced167617308943993b8'

def main():
    if digest(pathlib.Path(JAVA)/'bin/java')!=JAVA_SHA:raise ValueError('Inventoried source Java changed')
    STATE.mkdir(mode=0o700,parents=True,exist_ok=True);archive=STATE/'jre.tar.gz'
    if (STATE/'jre-node-proof.json').exists():
        proof=json.loads((STATE/'jre-node-proof.json').read_text())
        assert proof['javaSha256']==JAVA_SHA and proof['javaVerified']
        current=node("import pathlib,json,hashlib;p=pathlib.Path("+repr(NODE+'/jre/bin/java')+");print(json.dumps(dict(sha=hashlib.file_digest(p.open('rb'),'sha256').hexdigest())))")
        assert current['sha']==JAVA_SHA
        print(json.dumps(dict(alreadyStaged=True,javaSha256=JAVA_SHA)));return
    if not (STATE/'jre-export.json').exists():
        if archive.exists():raise ValueError('Unverified existing JRE archive')
        jre=pathlib.Path(JAVA)
        missing={str(p.relative_to(jre)) for p in jre.rglob('*') if p.is_symlink() and not p.exists()}
        if missing-{'lib/src.zip'}:raise ValueError('Missing JRE runtime target')
        pending=archive.with_suffix('.pending')
        with tarfile.open(pending,'w:gz',dereference=True) as tar:
            for child in jre.iterdir():
                if child.name=='lib':
                    tar.add(child,arcname='jre/lib',recursive=False)
                    for p in child.iterdir():
                        if str(p.relative_to(jre)) not in missing:tar.add(p,arcname='jre/lib/'+p.name)
                else:tar.add(child,arcname='jre/'+child.name)
        pending.chmod(0o600);pending.rename(archive)
        save('jre-export.json',dict(at=time.time(),size=archive.stat().st_size,sha256=digest(archive),javaSha256=JAVA_SHA,excludedMissingSourceArchive=sorted(missing)))
    meta=json.loads((STATE/'jre-export.json').read_text());assert digest(archive)==meta['sha256'] and meta['javaSha256']==JAVA_SHA
    node("import pathlib,json;p=pathlib.Path("+repr(NODE)+");p.mkdir(mode=0o700);print(json.dumps(dict(created=True)))")
    with archive.open('rb') as source:
        subprocess.run(ssh()+['umask 077; cat > '+NODE+'/jre.tar.gz.pending'],stdin=source,check=True,timeout=600)
    script='''import pathlib,json,hashlib,tarfile,subprocess
r=pathlib.Path(NODE);p=r/'jre.tar.gz.pending'
assert hashlib.file_digest(p.open('rb'),'sha256').hexdigest()==ARCHIVE_SHA
p.rename(r/'jre.tar.gz')
with tarfile.open(r/'jre.tar.gz') as tar:tar.extractall(r,filter='data')
assert hashlib.file_digest((r/'jre/bin/java').open('rb'),'sha256').hexdigest()==JAVA_SHA
(r/'jre').chmod(0o755)
p=subprocess.run(['podman','run','--rm','--network','none','--memory','128m','--cpus','0.5','--pids-limit','64','--user','1000:0',
 '--cap-drop','ALL','--security-opt','no-new-privileges','--read-only','-v',str(r/'jre')+':/opt/java17:ro','--entrypoint','/opt/java17/bin/java',IMAGE,'-version'],capture_output=True,text=True,timeout=30)
assert p.returncode==0,p.stderr
print(json.dumps(dict(javaVerified=True,javaSha256=JAVA_SHA,version=p.stderr.strip())))
'''
    for key,value in {'NODE':NODE,'ARCHIVE_SHA':meta['sha256'],'JAVA_SHA':JAVA_SHA,'IMAGE':IMAGE}.items():script=script.replace(key,repr(value))
    proof=node(script,120);save('jre-node-proof.json',proof);print(json.dumps(proof))

if __name__=='__main__':main()
