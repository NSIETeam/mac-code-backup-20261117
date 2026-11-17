#!/usr/bin/env python3
"""Seed an inactive Linux service from verified, existing Windows cache blobs.

Must run in a private mount namespace. Windows mounts are read-only, scoped to
the auxiliary configuration/cache folders, and never exposed to build workers.
Originals and cache blobs are not changed. A final source delta is still needed.
"""
import hashlib,json,math,os,pathlib,stat,subprocess,time,uuid

STATE=pathlib.Path('/opt/siyue-workstation/databases')
SERVICE=pathlib.Path('/opt/siyue-workstation/services/miniapp')
RESERVE=18*1024**3

def safe_key(key):
    p=pathlib.PurePosixPath(key)
    if not key or p.is_absolute() or '..' in p.parts or str(p)!=key:raise ValueError('Invalid object path')
    return p

def main():
    os.umask(0o077);os.nice(15)
    if os.geteuid()!=0:raise PermissionError('Root maintenance required')
    if os.readlink('/proc/self/ns/mnt')==os.readlink('/proc/1/ns/mnt'):raise ValueError('Private mount namespace required')
    if (SERVICE/'enabled').exists():raise ValueError('Refusing to seed an enabled service')
    manifest=json.loads((STATE/'object-manifest.json').read_text())
    entries=manifest['entries']
    if manifest['source']!='/opt/siyue-miniapp/data/objects':raise ValueError('Unexpected source tree')
    for item in entries:
        safe_key(item['key'])
        if len(item['sha256'])!=64 or any(c not in '0123456789abcdef' for c in item['sha256']):raise ValueError('Invalid hash')
        if not 0<=item['size']<1024**3 or item['mode']&~0o777:raise ValueError('Unexpected object metadata')
    SERVICE.mkdir(mode=0o700,parents=True,exist_ok=True)
    data=SERVICE/'data';data.mkdir(mode=0o755,exist_ok=True);os.chown(data,1654,1654)
    dest=data/'objects';dest.mkdir(mode=0o755,exist_ok=True);os.chown(dest,1654,1654)
    for p in (data,dest):p.chmod(0o755)
    root=STATE/'mirror-seed';root.mkdir(mode=0o700,exist_ok=True)
    config=root/'config';cache=root/'cache'
    for p in (config,cache):p.mkdir(mode=0o700,exist_ok=True)
    mounts=[];result=dict(startedAt=time.time(),copied=0,reused=0,missing=[],invalid=[],copiedBytes=0)
    try:
        subprocess.run(['mount','-t','drvfs',r'C:\ProgramData\SiyueAuxNode',str(config),'-o','ro,nosuid,nodev,noexec'],check=True,timeout=20);mounts.append(config)
        settings=json.loads((config/'config.json').read_text(encoding='utf-8-sig'))
        if settings['data']!=r'E:\SiyueAuxNode':raise ValueError('Windows cache path changed')
        disk=os.statvfs(config);free=disk.f_bavail*disk.f_frsize
        estimated=sum(math.ceil(i['size']/4096)*4096+512 for i in entries)
        if free<estimated+RESERVE:raise ValueError('Insufficient actual Windows system-drive reserve')
        result['systemDriveFreeBefore']=free
        subprocess.run(['mount','-t','drvfs',settings['data'],str(cache),'-o','ro,nosuid,nodev,noexec'],check=True,timeout=20);mounts.append(cache)
        for index,item in enumerate(entries):
            key=safe_key(item['key']);target=dest/key
            if index%500==0:
                s=os.statvfs(config)
                if s.f_bavail*s.f_frsize<RESERVE:raise ValueError('System-drive reserve reached; partial seed retained')
            if target.is_symlink():raise ValueError('Unexpected destination symlink')
            if target.exists():
                with target.open('rb') as f:old=hashlib.file_digest(f,'sha256').hexdigest()
                if old==item['sha256'] and target.stat().st_size==item['size']:
                    result['reused']+=1;continue
            blob=cache/'blobs'/item['sha256'][:2]/item['sha256']
            if not blob.is_file() or blob.is_symlink() or blob.stat().st_size!=item['size']:
                result['missing'].append(item['key']);continue
            folder=dest
            for part in key.parts[:-1]:
                folder=folder/part
                if folder.is_symlink():raise ValueError('Unexpected destination directory symlink')
                if not folder.exists():folder.mkdir(mode=0o755);os.chown(folder,1654,1654)
            partial=target.with_name('.seed-'+uuid.uuid4().hex)
            try:
                checksum=hashlib.sha256();size=0
                with blob.open('rb') as source,partial.open('xb') as output:
                    while block:=source.read(1024**2):checksum.update(block);output.write(block);size+=len(block)
                if checksum.hexdigest()!=item['sha256'] or size!=item['size']:
                    result['invalid'].append(item['key']);continue
                os.chown(partial,item['uid'],item['gid']);partial.chmod(item['mode']);os.utime(partial,ns=(item['mtimeNs'],item['mtimeNs']))
                partial.replace(target);result['copied']+=1;result['copiedBytes']+=size
            finally:partial.unlink(missing_ok=True)
            if index%2000==0:print(json.dumps(dict(scanned=index+1,copied=result['copied'],bytes=result['copiedBytes'],missing=len(result['missing']))),flush=True)
        result.update(completedAt=time.time(),files=len(entries),sourceManifestAt=manifest['at'])
        (SERVICE/'cache-seed.json').write_text(json.dumps(result,indent=2))
        print(json.dumps({k:v for k,v in result.items() if k not in ('missing','invalid')}|dict(missing=len(result['missing']),invalid=len(result['invalid']))),flush=True)
    finally:
        for p in reversed(mounts):subprocess.run(['umount',str(p)],check=True,timeout=20)

if __name__=='__main__':main()
