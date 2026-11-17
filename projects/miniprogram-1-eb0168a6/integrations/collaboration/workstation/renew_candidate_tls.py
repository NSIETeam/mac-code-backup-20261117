#!/usr/bin/env python3
"""Renew only the inventoried v100 candidate TLS identity, retaining its keys.

Run on the controller. Private keys and database credentials never leave it.
This does not change production authentication or disable TLS verification.
"""
import hashlib,json,os,pathlib,shutil,ssl,subprocess,sys,time,urllib.request

ROOT=pathlib.Path('/opt/siyue-idp-candidate/v100')
STATE=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/idp-candidate-migration')
NAME='siyue-idp-candidate-v100'

def run(args):
    p=subprocess.run(args,capture_output=True,check=True,timeout=90)
    return p.stdout

def public_key(path,certificate=True):
    args=['openssl','x509','-in',str(path),'-noout','-pubkey'] if certificate else ['openssl','pkey','-in',str(path),'-pubout']
    return run(args)

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    sys.path.insert(0,str(ROOT))
    from common import load
    from probe import verify
    from dependency_guard import dependent_snapshot
    root,state,private=load(ROOT)
    current=json.loads(run(['docker','inspect',NAME]))[0]
    if current['Image']!=state['imageId']:raise ValueError('Candidate image changed')
    STATE.mkdir(mode=0o700,parents=True,exist_ok=True)
    backup=STATE/'tls-before-renewal'
    if backup.exists():raise ValueError('Previous TLS operation exists; inspect its result before retry')
    backup.mkdir(mode=0o700)
    paths=['ca.crt','tls/server.crt']
    for name in paths:
        out=backup/name;out.parent.mkdir(exist_ok=True);shutil.copy2(ROOT/name,out)
    original_ca=public_key(ROOT/'ca.crt')
    original_server=public_key(ROOT/'tls/server.crt')
    if original_ca!=public_key(ROOT/'ca.key',False) or original_server!=public_key(ROOT/'tls/server.key',False):
        raise ValueError('Existing certificate/key mismatch')
    work=STATE/'tls-renewal';work.mkdir(mode=0o700)
    run(['openssl','req','-new','-x509','-key',str(ROOT/'ca.key'),'-sha256','-days','365',
         '-subj','/CN=Siyue isolated candidate CA','-addext','basicConstraints=critical,CA:TRUE',
         '-addext','keyUsage=critical,keyCertSign,cRLSign','-out',str(work/'ca.crt')])
    run(['openssl','req','-new','-key',str(ROOT/'tls/server.key'),'-subj','/CN=localhost','-out',str(work/'server.csr')])
    run(['openssl','x509','-req','-in',str(work/'server.csr'),'-CA',str(work/'ca.crt'),'-CAkey',str(ROOT/'ca.key'),
         '-set_serial','0x'+os.urandom(16).hex(),'-days','90','-sha256','-extfile',str(ROOT/'tls.ext'),'-out',str(work/'server.crt')])
    if public_key(work/'ca.crt')!=original_ca or public_key(work/'server.crt')!=original_server:
        raise ValueError('TLS identity changed')
    run(['openssl','verify','-CAfile',str(work/'ca.crt'),'-verify_hostname','localhost',str(work/'server.crt')])
    run(['nginx','-t'])
    try:
        for target,source in [('ca.crt','ca.crt'),('tls/server.crt','server.crt')]:
            dest=ROOT/target;old=dest.stat();pending=dest.with_suffix('.pending')
            shutil.copyfile(work/source,pending);os.chown(pending,old.st_uid,old.st_gid);pending.chmod(old.st_mode&0o777);pending.replace(dest)
        run(['docker','restart','--time','30',NAME])
        run(['nginx','-t']);run(['systemctl','reload','nginx'])
        context=ssl.create_default_context(cafile=str(ROOT/'ca.crt'))
        deadline=time.monotonic()+120
        while True:
            try:
                with urllib.request.urlopen('https://localhost:18449/realms/master/.well-known/openid-configuration',context=context,timeout=5) as r:
                    if r.status==200:break
            except OSError:
                if time.monotonic()>deadline:raise
                time.sleep(3)
        result=dict(protocol=verify(root,state,private),dependencies=dependent_snapshot(root,state,private))
        public='https://www.siyuejn.com/java-candidate-v101/auth/realms/siyue-migration-v101/.well-known/openid-configuration'
        with urllib.request.urlopen(public,timeout=20) as r:info=json.load(r)
        if info['issuer']!=state['dependentRealms'][0]['issuer']:raise ValueError('Public issuer changed')
        result.update(publicDiscovery=200,identityKeysPreserved=True,at=time.time(),
                      caSha256=hashlib.sha256((ROOT/'ca.crt').read_bytes()).hexdigest(),
                      leafSha256=hashlib.sha256((ROOT/'tls/server.crt').read_bytes()).hexdigest())
        (STATE/'tls-renewed.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
    except Exception:
        for name in paths:shutil.copy2(backup/name,ROOT/name)
        run(['docker','restart','--time','30',NAME]);run(['nginx','-t']);run(['systemctl','reload','nginx'])
        raise

if __name__=='__main__':main()
