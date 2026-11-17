#!/usr/bin/env python3
"""Provision a single loopback-only return tunnel, separate from project credentials."""
import json,os,pathlib,pwd,subprocess
root=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/workstation-access')
def run(*a):return subprocess.run(a,check=True,capture_output=True,text=True).stdout
def main():
 if os.geteuid()!=0:raise PermissionError('controller provisioning requires administrator')
 root.mkdir(parents=True,exist_ok=True,mode=0o700);root.chmod(0o700)
 user='siyue-ws-main'
 try:account=pwd.getpwnam(user)
 except KeyError:
  run('useradd','--system','--create-home','--shell','/usr/sbin/nologin',user);account=pwd.getpwnam(user)
 for name in ('controller','tunnel'):
  if not (root/name).exists():run('ssh-keygen','-t','ed25519','-N','','-C','siyue-workstation-'+name,'-f',str(root/name))
 directory=pathlib.Path(account.pw_dir)/'.ssh';directory.mkdir(mode=0o700,exist_ok=True)
 auth=directory/'authorized_keys';expected='restrict,port-forwarding,permitlisten="127.0.0.1:5399" '+(root/'tunnel.pub').read_text()
 if auth.exists() and auth.read_text()!=expected:raise ValueError('unexpected preexisting tunnel key; inspect before replacing')
 auth.write_text(expected);auth.chmod(0o600)
 for p in (directory,auth):os.chown(p,account.pw_uid,account.pw_gid)
 conf=pathlib.Path('/etc/ssh/sshd_config.d/90-siyue-workstation.conf')
 contents='''Match User siyue-ws-main
    ClientAliveInterval 10
    ClientAliveCountMax 2
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding remote
    PermitListen 127.0.0.1:5399
    PermitOpen none
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    MaxSessions 0
Match all
'''
 if conf.exists() and conf.read_text()!=contents:raise ValueError('unexpected existing SSH policy')
 conf.write_text(contents);conf.chmod(0o644)
 run('/usr/sbin/sshd','-t')
 observed=run('/usr/sbin/sshd','-T','-C','user=siyue-ws-main,host=localhost,addr=127.0.0.1')
 for line in ('allowtcpforwarding remote','permitlisten 127.0.0.1:5399','maxsessions 0','gatewayports no'):
  if line not in observed.splitlines():raise ValueError('SSH policy not effective: '+line)
 run('systemctl','reload','ssh')
 host=pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
 transport=dict(tunnelKey=(root/'tunnel').read_text(),controllerKey=(root/'controller.pub').read_text(),knownHosts='47.113.200.248 '+host[0]+' '+host[1]+'\n')
 private=root/'transport.json';private.write_text(json.dumps(transport));private.chmod(0o600)
 print(json.dumps(dict(prepared=True,user=user,listen='127.0.0.1:5399',shellAccess=False,transport=str(private))))
if __name__=='__main__':main()
