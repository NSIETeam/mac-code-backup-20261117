"""Bounded build drain for the explicitly authorized workstation reboot test."""
import fcntl,json,os,pathlib,sys,time,uuid

BASE=pathlib.Path('/var/lib/siyue-workstation-build')
RECEIPT=pathlib.Path('/opt/siyue-workstation/reboot-gate.json')
BOOT=pathlib.Path('/proc/sys/kernel/random/boot_id')

def start_ticks(pid):
    return pathlib.Path('/proc',str(pid),'stat').read_text().rsplit(')',1)[1].split()[19]

def check(nonce):
    row=json.loads(RECEIPT.read_text())
    if row['nonce']!=nonce or row['bootId']!=BOOT.read_text().strip() or row['expires']<=time.time():
        raise ValueError('Reboot gate is stale or belongs to another request')
    if row['startTicks']!=start_ticks(row['pid']):raise ValueError('Gate process changed')
    with (BASE/'build.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return row
        raise ValueError('Build drain is no longer held')

def hold(nonce):
    with (BASE/'build.lock').open('a') as lock:
        # Fail rather than interrupt an active build.
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        row=dict(nonce=nonce,pid=os.getpid(),startTicks=start_ticks(os.getpid()),
                 bootId=BOOT.read_text().strip(),expires=time.time()+240)
        pending=RECEIPT.with_suffix('.pending');pending.write_text(json.dumps(row));pending.chmod(0o600);pending.replace(RECEIPT)
        try:
            print(json.dumps(row),flush=True)
            time.sleep(240)
        finally:
            if RECEIPT.exists() and json.loads(RECEIPT.read_text()).get('nonce')==nonce:RECEIPT.unlink()

if __name__=='__main__':
    if os.getuid()!=0:raise PermissionError('Root required')
    action=sys.argv[1];nonce=str(uuid.UUID(sys.argv[2]))
    if action=='hold':hold(nonce)
    elif action=='check':print(json.dumps(check(nonce)))
    else:raise ValueError('Unsupported action')
