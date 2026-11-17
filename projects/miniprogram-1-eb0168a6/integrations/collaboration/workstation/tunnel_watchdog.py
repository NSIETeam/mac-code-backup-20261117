#!/usr/bin/env python3
"""Reap only fixed return-tunnel sshd children with verified silent transports.

OpenSSH ClientAlive checks can be delayed by local forwarding activity. This
guard requires both TCP receive inactivity and unchanged received-byte counts
over 45 seconds, then rechecks the process identity and owned listening port.
It never targets application processes or arbitrary SSH users.
"""
import json,os,pathlib,pwd,re,signal,subprocess,time

USERS={'siyue-ws-main':5399,'siyue-ws-idp':19449,'siyue-ws-crm':19086,'siyue-ws-homework':19592,'siyue-ws-database':15432,'siyue-ws-miniapp':15082}
GRACE=45
ROOT=pathlib.Path('/run/siyue-workstation-tunnels')

def tcp_rows(text):
    rows=[];header=None
    for line in text.splitlines():
        if line.startswith('ESTAB '):header=line;continue
        if not header:continue
        fields=dict(re.findall(r'\b(bytes_received|lastrcv):(\d+)',line))
        if len(fields)==2:
            rows.append(dict(pids=[int(x) for x in re.findall(r'pid=(\d+)',header)],
                peer=header.split()[4],received=int(fields['bytes_received']),idleMs=int(fields['lastrcv'])))
        header=None
    return rows

def identity(pid,user):
    p=pathlib.Path('/proc')/str(pid)
    if p.stat().st_uid!=pwd.getpwnam(user).pw_uid:return None
    cmd=(p/'cmdline').read_bytes().rstrip(b'\0').decode(errors='replace')
    if cmd!='sshd: '+user:return None
    # Field 22 is the kernel process start time; PID equality alone is unsafe.
    start=(p/'stat').read_text().rsplit(')',1)[1].split()[19]
    return (pid,start,user)

def snapshot():
    text=subprocess.check_output(['ss','-Htinp','sport = :22'],text=True,timeout=5)
    listeners=subprocess.check_output(['ss','-Hltnp'],text=True,timeout=5).splitlines();found={}
    for row in tcp_rows(text):
        for user,port in USERS.items():
            for pid in row['pids']:
                try:ident=identity(pid,user)
                except (OSError,KeyError,IndexError):continue
                if not ident:continue
                expected='127.0.0.1:'+str(port)
                matches=[s for s in listeners if len(s.split())>=4 and s.split()[3]==expected and 'pid='+str(pid)+',' in s]
                if len(matches)==1:found[ident]={**row,'user':user,'pid':pid,'port':port}
    return found

def observe(previous,current,now):
    state={};expired=[]
    for ident,row in current.items():
        old=previous.get(ident)
        since=old['since'] if old and old['received']==row['received'] and old['peer']==row['peer'] else now
        state[ident]=dict(row,since=since)
        if now-since>=GRACE and row['idleMs']>=GRACE*1000:expired.append((ident,row))
    return state,expired

def still_stale(ident,before,current):
    row=current.get(ident)
    return bool(row and row['received']==before['received'] and row['peer']==before['peer'] and row['idleMs']>=GRACE*1000)

def main():
    if os.geteuid()!=0:raise PermissionError('Root required')
    ROOT.mkdir(mode=0o700,exist_ok=True);previous={}
    while True:
        try:
            current=snapshot();previous,expired=observe(previous,current,time.monotonic());actions=[]
            for ident,row in expired:
                if still_stale(ident,row,snapshot()) and identity(row['pid'],row['user'])==ident:
                    fd=os.pidfd_open(row['pid'])
                    try:
                        if identity(row['pid'],row['user'])!=ident:continue
                        signal.pidfd_send_signal(fd,signal.SIGTERM)
                    finally:os.close(fd)
                    action=dict(user=row['user'],pid=row['pid'],port=row['port'],idleMs=row['idleMs'])
                    actions.append(action);print(json.dumps(dict(action='reaped-silent-tunnel',**action)),flush=True)
                    previous.pop(ident,None)
            state=dict(at=time.time(),connections=[dict(user=x['user'],pid=x['pid'],port=x['port'],idleMs=x['idleMs']) for x in current.values()],actions=actions)
        except Exception as e:
            # Observation errors never justify terminating a connection.
            previous={};state=dict(at=time.time(),error=type(e).__name__)
        p=ROOT/'state.pending';p.write_text(json.dumps(state));p.chmod(0o600);p.replace(ROOT/'state.json')
        time.sleep(5)

if __name__=='__main__':main()
