#!/usr/bin/env python3
"""Copy an existing configured rootless build image, preserving its exact ID."""
import hashlib,json,pathlib,subprocess,sys,shutil
import transfer_image

def main():
    sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
    key=d.validate_key(sys.argv[1]);cfg=d.settings();image=d.sha(cfg['projects'][key]['check_image'])
    root=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/build-images');root.mkdir(mode=0o700,exist_ok=True)
    source=json.loads(subprocess.check_output(d.builder(['image','inspect',image],cfg),cwd='/tmp'))[0]
    assert source['Id'].removeprefix('sha256:')==image
    archive=root/(image+'.tar.gz');manifest=root/(image+'.json')
    if not manifest.exists():
        if shutil.disk_usage(root).free<source['Size']+5*1024**3:raise ValueError('Insufficient export disk reserve')
        partial=archive.with_suffix('.partial')
        with partial.open('wb') as out:
            save=subprocess.Popen(d.builder(['save','--format','docker-archive',image],cfg),cwd='/tmp',stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            compressed=subprocess.run(['nice','-n','15','gzip','-1'],stdin=save.stdout,stdout=out)
            save.stdout.close();error=save.stderr.read();code=save.wait()
        if code or compressed.returncode:raise RuntimeError('Image export failed: '+error.decode(errors='replace')[-500:])
        partial.chmod(0o600);partial.replace(archive)
        meta=dict(image=image,sha256=d.file_digest(archive),size=archive.stat().st_size,file=str(archive))
        manifest.write_text(json.dumps(meta));manifest.chmod(0o600)
    meta=json.loads(manifest.read_text());assert meta['image']==image and d.file_digest(archive)==meta['sha256']
    print(json.dumps(dict(state='exported',**meta)),flush=True)
    subprocess.run([sys.executable,str(pathlib.Path(transfer_image.__file__)),str(manifest)],check=True)
    script='''import json,pathlib,subprocess,sys,fcntl,hashlib
sys.path.insert(0,'/opt/siyue-workstation');from build_sandbox import builder
p=pathlib.Path('/opt/siyue-workstation/images')/(META['sha256']+'.tar.gz')
assert p.stat().st_size==META['size'] and hashlib.file_digest(p.open('rb'),'sha256').hexdigest()==META['sha256']
with pathlib.Path('/var/lib/siyue-workstation-build/build.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 probe=subprocess.run(builder(['image','inspect','--format','{{.Id}}',META['image']]),cwd='/home/siyue-build',capture_output=True,text=True)
 if probe.returncode:
  with p.open('rb') as source:subprocess.run(builder(['load']),stdin=source,cwd='/home/siyue-build',check=True,stdout=subprocess.DEVNULL)
 actual=subprocess.check_output(builder(['image','inspect','--format','{{.Id}}',META['image']]),cwd='/home/siyue-build',text=True).strip().removeprefix('sha256:')
 assert actual==META['image']
 print(json.dumps(dict(state='node-image-verified',image=actual,archiveSha256=META['sha256'])))
'''
    result=subprocess.run(w.command(cfg['workstation'])[:-1]+['python3 -'],input='META='+repr(meta)+'\n'+script,capture_output=True,text=True,timeout=3600)
    if result.returncode:raise RuntimeError(result.stderr[-1000:])
    proof=json.loads(result.stdout);(root/(image+'.node.json')).write_text(json.dumps(proof));print(result.stdout,flush=True)

if __name__=='__main__':main()
