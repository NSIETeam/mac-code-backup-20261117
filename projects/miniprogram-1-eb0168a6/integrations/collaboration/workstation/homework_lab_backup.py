"""Pull verified private backups from the current lab writer to the main server."""
import datetime,fcntl,json,os,pathlib,re,shutil,subprocess,tarfile,time
import homework_lab_common as c
import homework_lab_snapshot as s
ROOT=pathlib.Path('/opt/siyue-workstation-backups/homework-lab')
NAME=re.compile(r'^\d{8}T\d{6}Z-[a-f0-9]{8}$')
KEEP=28

def unpack(archive,target):
    target.mkdir(mode=0o700)
    with tarfile.open(archive,'r:gz') as tar:
        seen=set();size=0
        for member in tar.getmembers():
            path=pathlib.PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or member.name in seen or not (member.isfile() or member.isdir()):raise ValueError('Invalid backup archive')
            if path.parts[0] not in ('data','manifest.json','service.env'):raise ValueError('Unreviewed backup member')
            seen.add(member.name);size+=member.size
            if size>s.MAX_BYTES:raise ValueError('Expanded archive too large')
        tar.extractall(target,filter='data')
    expected=json.loads((target/'manifest.json').read_text())
    if s.files(target/'data')!=expected['files']:raise ValueError('Backup file checksum mismatch')
    if s.rows(target/'data/isolated.db')!=expected['database']:raise ValueError('Backup database mismatch')
    if s.digest(target/'service.env')!=expected['environmentSha256']:raise ValueError('Backup environment mismatch')
    return expected

def completed(root):
    return sorted(p for p in root.iterdir() if NAME.fullmatch(p.name) and not p.is_symlink() and p.is_dir() and (p/'COMPLETE').is_file())

def commit(partial,name):
    if not NAME.fullmatch(name):raise ValueError('Invalid snapshot name')
    target=ROOT/name
    if target.exists():raise ValueError('Snapshot already exists')
    (partial/'COMPLETE').write_text(datetime.datetime.now(datetime.timezone.utc).isoformat()+'\n')
    partial.rename(target)
    pointer=ROOT/'latest.pending';pointer.unlink(missing_ok=True);pointer.symlink_to(name,target_is_directory=True);pointer.replace(ROOT/'latest')
    for old in completed(ROOT)[:-KEEP]:shutil.rmtree(old)
    return target

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    os.umask(0o077);ROOT.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (ROOT/'backup.lock').open('a') as lock,(c.STATE/'operation.lock').open('a') as operation_lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);fcntl.flock(operation_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if shutil.disk_usage(ROOT).free<5*1024**3:raise ValueError('Insufficient main-server backup reserve')
        op=json.loads((c.STATE/'operation.json').read_text());c.ensure_identity();source,env=c.source()
        if op['state']=='succeeded' and op['activeData']=='node' and not source['State']['Running']:
            expected=json.loads((c.STATE/'prepared.json').read_text())['node']['containerId']
            code=pathlib.Path(s.__file__).read_text()+'\nprint(json.dumps(node_snapshot('+','.join(repr(x) for x in (c.NODE,expected,c.IMAGE,c.DLL))+')))\n'
            process=subprocess.run(c.ssh()+['python3 -'],input=code,text=True,capture_output=True,timeout=150)
            if process.returncode:
                c.save('backup-last-error.json',{'at':time.time(),'stage':'node-snapshot','stderr':process.stderr[-4000:]})
                raise RuntimeError('Node snapshot failed; see private backup-last-error.json')
            receipt=json.loads(process.stdout);remote=True
        elif op['state']=='rolled-back' and op['activeData']=='source' and source['State']['Running']:
            receipt=s.snapshot(c.SOURCE/'data',ROOT/'source-staging',('\n'.join(source['Config']['Env'])+'\n').encode(),{'location':'main-server','containerId':source['Id'],'image':c.SOURCE_IMAGE,'dllSha256':c.DLL,'workingDirectory':source['Config']['WorkingDir']});remote=False
        else:raise ValueError('Data ownership uncertain; retain existing backups')
        if not NAME.fullmatch(receipt['name']) or not 0<receipt['size']<=s.MAX_BYTES:raise ValueError('Unexpected snapshot receipt')
        if not 0<receipt['expandedBytes']<=s.MAX_BYTES:raise ValueError('Unexpected expanded size')
        if shutil.disk_usage(ROOT).free<5*1024**3+receipt['size']+receipt['expandedBytes']:raise ValueError('Insufficient receive/extract reserve')
        partial=ROOT/(receipt['name']+'.partial');partial.mkdir(mode=0o700)
        archive=partial/'snapshot.tar.gz'
        try:
            if remote:
                expected_path=c.NODE+'/snapshots/'+receipt['name']+'.tar.gz'
                if receipt['file']!=expected_path:raise ValueError('Unexpected remote snapshot path')
                with archive.open('xb') as out:
                    result=subprocess.run(c.ssh()+['cat '+expected_path],stdout=out,stderr=subprocess.PIPE,timeout=180)
                if result.returncode:raise RuntimeError('Snapshot transfer failed; existing backups retained')
            else:shutil.copyfile(receipt['file'],archive)
            if archive.stat().st_size!=receipt['size'] or s.digest(archive)!=receipt['sha256']:raise ValueError('Snapshot transfer checksum mismatch')
            proof=unpack(archive,partial/'contents')
            (partial/'receipt.json').write_text(json.dumps(receipt,indent=2))
            target=commit(partial,receipt['name'])
            result={'at':time.time(),'snapshot':target.name,'location':proof['identity']['location'],'sha256':receipt['sha256'],'bytes':receipt['size'],'tables':len(proof['database']),'retained':len(completed(ROOT))}
            c.save('backup-last-success.json',result)
            # Delete only this positively verified staging archive, never older good backups.
            if remote:c.node('import pathlib,json\np=pathlib.Path('+repr(receipt['file'])+');p.unlink();print(json.dumps({"removed":True}))')
            else:pathlib.Path(receipt['file']).unlink()
            print(json.dumps(result),flush=True)
        except Exception:
            # Failed staging remains private for inspection; latest/retention remain unchanged.
            raise

if __name__=='__main__':main()
