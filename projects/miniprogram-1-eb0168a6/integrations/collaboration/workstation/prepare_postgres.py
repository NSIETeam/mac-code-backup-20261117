#!/usr/bin/env python3
"""Prepare a same-version PostgreSQL standby; never switch live applications."""
import hashlib,io,json,os,pathlib,pwd,secrets,subprocess,sys,tarfile,time
STATE=pathlib.Path('/opt/siyue-auxiliary/database-migration-20261117')
NODE='/opt/siyue-workstation/databases';ROLE='siyue_workplace_replication'
def run(args,**kw):return subprocess.run(args,check=True,capture_output=True,timeout=120,**kw).stdout
def sql(statement):return run(['runuser','-u','postgres','--','psql','-X','-v','ON_ERROR_STOP=1','-At'],input=statement,text=True)
def save(path,value):
 tmp=path.with_suffix('.pending');tmp.write_text(json.dumps(value,indent=2));tmp.chmod(0o600);tmp.replace(path)
def main():
 os.umask(0o077);STATE.mkdir(mode=0o700,exist_ok=True)
 if (STATE/'postgres-prepared.json').exists():raise ValueError('Already prepared; inspect existing standby')
 sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d,workstation_client as w
 ssh=w.command(d.settings()['workstation'])[:-1]
 version=sql('SHOW server_version;').strip()
 if not version.startswith('16.15 '):raise ValueError('Source PostgreSQL version changed')
 if sql("SELECT count(*) FROM pg_tablespace WHERE pg_tablespace_location(oid)<>'';").strip()!='0':raise ValueError('External tablespaces need explicit mapping')
 auto=pathlib.Path('/var/lib/postgresql/16/main/postgresql.auto.conf')
 before=STATE/'postgresql.auto.conf.before'
 if not before.exists():before.write_bytes(auto.read_bytes());before.chmod(0o600)
 sql("ALTER SYSTEM SET max_slot_wal_keep_size = '256MB';")
 sql('SELECT pg_reload_conf();')
 credential=STATE/'postgres-private.json'
 if not credential.exists():
  if sql("SELECT rolpassword IS NULL FROM pg_authid WHERE rolname='postgres';").strip()!='t':raise ValueError('Existing administrator credential requires reconciliation')
  if sql("SELECT count(*) FROM pg_roles WHERE rolname='"+ROLE+"';").strip()!='0':raise ValueError('Unexpected existing replication role')
  save(credential,dict(replication=secrets.token_hex(32),administrator=secrets.token_hex(32)))
 private=json.loads(credential.read_text())
 if sql("SELECT count(*) FROM pg_roles WHERE rolname='"+ROLE+"';").strip()=='0':
  sql("CREATE ROLE "+ROLE+" LOGIN REPLICATION PASSWORD '"+private['replication']+"'; ALTER ROLE postgres PASSWORD '"+private['administrator']+"';")
 user='siyue-ws-database'
 try:account=pwd.getpwnam(user)
 except KeyError:run(['useradd','--system','--create-home','--shell','/usr/sbin/nologin',user]);account=pwd.getpwnam(user)
 key=STATE/'tunnel_key'
 if not key.exists():run(['ssh-keygen','-t','ed25519','-N','','-C','siyue-workplace-database','-f',str(key)])
 folder=pathlib.Path(account.pw_dir)/'.ssh';folder.mkdir(mode=0o700,exist_ok=True)
 auth=folder/'authorized_keys';expected='restrict,port-forwarding,permitopen="127.0.0.1:5432",permitlisten="127.0.0.1:15432" '+key.with_suffix('.pub').read_text()
 if auth.exists() and auth.read_text()!=expected:raise ValueError('Unexpected database tunnel identity')
 auth.write_text(expected);auth.chmod(0o600)
 for p in [folder,auth]:os.chown(p,account.pw_uid,account.pw_gid)
 policy='''Match User siyue-ws-database
    ClientAliveInterval 10
    ClientAliveCountMax 2
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding yes
    PermitOpen 127.0.0.1:5432
    PermitListen 127.0.0.1:15432
    GatewayPorts no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTTY no
    MaxSessions 0
Match all
'''
 conf=pathlib.Path('/etc/ssh/sshd_config.d/94-siyue-workstation-database.conf')
 if conf.exists() and conf.read_text()!=policy:raise ValueError('Unexpected database SSH policy')
 conf.write_text(policy);run(['/usr/sbin/sshd','-t'])
 effective=run(['/usr/sbin/sshd','-T','-C','user='+user+',host=localhost,addr=127.0.0.1']).decode().splitlines()
 for line in ['permitopen 127.0.0.1:5432','permitlisten 127.0.0.1:15432','maxsessions 0','gatewayports no']:
  if line not in effective:raise ValueError('Database forwarding restriction not effective')
 run(['systemctl','reload','ssh'])
 host=pathlib.Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
 config=pathlib.Path('/etc/postgresql/16/main/postgresql.conf').read_text()
 # Original settings remain, with only host-specific paths and resource bounds overridden.
 config+='\n# Workplace PostgreSQL overrides\n'+f"data_directory = '{NODE}/postgres/data'\nhba_file = '{NODE}/postgres/pg_hba.conf'\nident_file = '{NODE}/postgres/pg_ident.conf'\nexternal_pid_file = '{NODE}/postgres/postgres.pid'\nunix_socket_directories = '{NODE}/postgres/run'\nlisten_addresses = '127.0.0.1'\nport = 25432\nssl_cert_file = '{NODE}/postgres/server.crt'\nssl_key_file = '{NODE}/postgres/server.key'\nshared_buffers = 256MB\nhot_standby = on\n"
 assets={'supervisor.py':(STATE/'postgres_supervisor.py').read_bytes(),'tunnel_key':key.read_bytes(),'known_hosts':('47.113.200.248 '+host[0]+' '+host[1]+'\n').encode(),'postgres/postgresql.conf':config.encode(),'postgres/pg_hba.conf':pathlib.Path('/etc/postgresql/16/main/pg_hba.conf').read_bytes(),'postgres/pg_ident.conf':pathlib.Path('/etc/postgresql/16/main/pg_ident.conf').read_bytes(),'postgres/server.crt':pathlib.Path('/etc/ssl/certs/ssl-cert-snakeoil.pem').read_bytes(),'postgres/server.key':pathlib.Path('/etc/ssl/private/ssl-cert-snakeoil.key').read_bytes(),'postgres/replication.pgpass':('127.0.0.1:15433:*:'+ROLE+':'+private['replication']+'\n').encode()}
 for p in pathlib.Path('/etc/postgresql/16/main/conf.d').glob('*'):
  if not p.is_file() or p.is_symlink():raise ValueError('Unexpected PostgreSQL include')
  assets['postgres/conf.d/'+p.name]=p.read_bytes()
 stream=io.BytesIO()
 with tarfile.open(fileobj=stream,mode='w') as tar:
  for name,body in assets.items():
   i=tarfile.TarInfo(name);i.size=len(body);i.mode=0o600;tar.addfile(i,io.BytesIO(body))
 subprocess.run(ssh+['umask 077; mkdir -p '+NODE+'; tar -xf - -C '+NODE],input=stream.getvalue(),check=True,timeout=60)
 script='''import pathlib,pwd,os,subprocess,json,time
r=pathlib.Path('/opt/siyue-workstation/databases');r.chmod(0o711);pg=r/'postgres';u=pwd.getpwnam('postgres')
# Packages must already be pinned to the source version; apt's default empty
# cluster is not this custom instance and must not auto-start on a future boot.
default=pathlib.Path('/etc/postgresql/16/main/start.conf')
if default.exists():
 if subprocess.run(['pg_ctlcluster','16','main','status'],capture_output=True).returncode==0:raise ValueError('Unexpected running default PostgreSQL cluster')
 if not (r/'default-cluster-start.before').exists():(r/'default-cluster-start.before').write_bytes(default.read_bytes())
 default.write_text('disabled\\n')
locales=subprocess.check_output(['locale','-a'],text=True).lower().splitlines()
if 'en_us.utf8' not in locales and 'en_us.utf-8' not in locales:
 with pathlib.Path('/etc/locale.gen').open('a') as f:f.write('\\nen_US.UTF-8 UTF-8\\n')
 subprocess.run(['locale-gen'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['setfacl','-m','u:postgres:--x','/opt/siyue-workstation'],check=True)
for p in [pg,pg/'conf.d',pg/'run']:
 p.mkdir(mode=0o700,exist_ok=True);os.chown(p,u.pw_uid,u.pw_gid);p.chmod(0o700)
for p in pg.rglob('*'):
 if p.is_file():os.chown(p,u.pw_uid,u.pw_gid);p.chmod(0o600)
if not (r/'mode.json').exists():(r/'mode.json').write_text('{"mode":"standby"}')
launcher=pathlib.Path('/opt/siyue-workstation/services-start.sh');line='nohup python3 '+str(r/'supervisor.py')+' </dev/null 8>&- 9>&- >>'+str(r/'supervisor.log')+' 2>&1 &\\n'
content=launcher.read_text()
if line not in content:
 launcher.with_suffix('.before-postgres').write_text(content);launcher.write_text(content+line);launcher.chmod(0o700)
with (r/'supervisor.log').open('ab') as log:subprocess.Popen(['python3',str(r/'supervisor.py')],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True,close_fds=True)
print(json.dumps(dict(tunnelSupervisorStarted=True,dataExists=(pg/'data/PG_VERSION').exists())))
'''
 result=subprocess.run(ssh+['python3 -'],input=script,text=True,capture_output=True,timeout=30)
 if result.returncode:raise RuntimeError('Database preparation failed: '+result.stderr[-500:])
 save(STATE/'postgres-prepared.json',dict(at=time.time(),sourceVersion=version,node=NODE,replicationRole=ROLE,sourcePort=5432,nodePort=25432,returnPort=15432,applicationsSwitched=False))
 print(result.stdout.strip(),flush=True)
if __name__=='__main__':main()
