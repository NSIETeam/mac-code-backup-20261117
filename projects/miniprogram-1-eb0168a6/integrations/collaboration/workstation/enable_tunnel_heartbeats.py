#!/usr/bin/env python3
"""Enable server-side liveness detection only for the three fixed tunnel users."""
import json,pathlib,subprocess,time

USERS={'90-siyue-workstation.conf':'siyue-ws-main','91-siyue-workstation-idp.conf':'siyue-ws-idp','92-siyue-workstation-crm.conf':'siyue-ws-crm'}

def main():
    root=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/tunnel-heartbeat');root.mkdir(mode=0o700,exist_ok=True)
    originals={};proof={}
    try:
        for name,user in USERS.items():
            p=pathlib.Path('/etc/ssh/sshd_config.d')/name;s=p.read_text();marker='Match User '+user+'\n'
            if not s.startswith(marker):raise ValueError('Unexpected SSH scope')
            if 'ClientAlive' in s:
                if '    ClientAliveInterval 10\n    ClientAliveCountMax 2\n' not in s:raise ValueError('Existing liveness policy differs')
                continue
            backup=root/(name+'.'+str(time.time_ns()));backup.write_text(s);backup.chmod(0o600);originals[p]=s
            pending=p.with_suffix('.pending');pending.write_text(s.replace(marker,marker+'    ClientAliveInterval 10\n    ClientAliveCountMax 2\n',1));pending.chmod(0o644);pending.replace(p)
        subprocess.run(['/usr/sbin/sshd','-t'],check=True)
        for user in USERS.values():
            text=subprocess.check_output(['/usr/sbin/sshd','-T','-C','user='+user+',host=localhost,addr=127.0.0.1'],text=True)
            kv=dict(x.split(' ',1) for x in text.splitlines())
            assert kv['clientaliveinterval']=='10' and kv['clientalivecountmax']=='2' and kv['maxsessions']=='0'
            proof[user]={k:kv[k] for k in ['clientaliveinterval','clientalivecountmax','maxsessions','permitlisten','permitopen']}
        subprocess.run(['systemctl','reload','ssh'],check=True)
    except Exception:
        for p,s in originals.items():p.write_text(s)
        subprocess.run(['/usr/sbin/sshd','-t'],check=True);raise
    (root/'effective.json').write_text(json.dumps(dict(at=time.time(),users=proof),indent=2));print(json.dumps(proof))

if __name__=='__main__':main()
