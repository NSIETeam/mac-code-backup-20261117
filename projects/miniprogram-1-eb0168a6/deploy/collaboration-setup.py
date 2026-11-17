#!/usr/bin/env python3
"""Install isolated collaboration services; does not replace the teaching API."""
import json,os,pathlib,pwd,secrets,subprocess,textwrap
ROOT=pathlib.Path('/opt/siyue-collaboration')
ROOT.chmod(0o755)
(ROOT/'bin').chmod(0o755)
def run(args,**kw):
 r=subprocess.run(args,capture_output=True,text=True,**kw)
 if r.returncode:
  error=r.stderr[-1500:]
  for secret in globals().get('s',{}).values():
   if isinstance(secret,str) and len(secret)>10:error=error.replace(secret,'[redacted]')
  raise RuntimeError(str(args[0])+' failed: '+error)
 return r.stdout
def write(path,text,mode=0o600,owner=None):
 path=pathlib.Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);path.chmod(mode)
 if owner:info=pwd.getpwnam(owner);os.chown(path,info.pw_uid,info.pw_gid)
def sql(database,script):return run(['runuser','-u','postgres','--','psql','-v','ON_ERROR_STOP=1','-At',database],input=script)
for name in ['siyue-collab','siyue-gitea','siyue-build']:
 try:pwd.getpwnam(name)
 except KeyError:run(['useradd','--system','--create-home','--home-dir','/var/lib/'+name,'--shell','/usr/sbin/nologin',name])
state=ROOT/'secrets.json'
if not state.exists():write(state,json.dumps({key:secrets.token_hex(32) for key in ['db','identity','gitea_db','oidc','hook','delivery','gitea_secret','gitea_internal']},indent=2))
s=json.loads(state.read_text())
# Preserve encryption keys when upgrading an installation created before keys were separated.
s.setdefault('gitea_secret',s['hook']);s.setdefault('gitea_internal',s['delivery']);write(state,json.dumps(s,indent=2))
for role,key in [('siyue_collaboration','db'),('siyue_collaboration_identity','identity'),('siyue_collaboration_git','gitea_db')]:
 if sql('postgres',f"SELECT 1 FROM pg_roles WHERE rolname='{role}';").strip()!='1':sql('postgres',f"CREATE ROLE {role} LOGIN PASSWORD '{s[key]}' NOSUPERUSER NOCREATEDB NOCREATEROLE;")
for database,owner in [('siyue_collaboration','siyue_collaboration'),('siyue_collaboration_git','siyue_collaboration_git')]:
 if sql('postgres',f"SELECT 1 FROM pg_database WHERE datname='{database}';").strip()!='1':run(['runuser','-u','postgres','--','createdb','-O',owner,database])
sql('siyue_miniapp','''
CREATE OR REPLACE VIEW collaboration_people AS SELECT u."Id" AS id,u."Name" AS name,u."Role" AS role,COALESCE(a."Account",CASE WHEN u."OpenId" LIKE 'password:%' THEN substring(u."OpenId" from 10) ELSE '' END) AS account,(u."Enabled" AND NOT u."Archived") AS enabled FROM "Staff" u LEFT JOIN "PasswordAccounts" a ON a."StaffId"=u."Id";
CREATE OR REPLACE VIEW collaboration_sessions AS SELECT u."Id" AS id,u."Name" AS name,u."Role" AS role,COALESCE(a."Account",CASE WHEN u."OpenId" LIKE 'password:%' THEN substring(u."OpenId" from 10) ELSE '' END) AS account,COALESCE(a."MustChangePassword",false) AS must_change_password,s."TokenHash" AS token_hash,s."ExpiresAt" AS expires_at FROM "Sessions" s JOIN "Staff" u ON u."Id"=s."StaffId" LEFT JOIN "PasswordAccounts" a ON a."StaffId"=u."Id" WHERE u."Enabled" AND NOT u."Archived";
GRANT CONNECT ON DATABASE siyue_miniapp TO siyue_collaboration_identity;
GRANT USAGE ON SCHEMA public TO siyue_collaboration_identity;
GRANT SELECT ON collaboration_people,collaboration_sessions TO siyue_collaboration_identity;
''')
for folder,owner in [('gitea','siyue-gitea'),('config','siyue-collab'),('keys','siyue-collab'),('attachments','siyue-collab')]:
 p=ROOT/folder;p.mkdir(exist_ok=True);info=pwd.getpwnam(owner);os.chown(p,info.pw_uid,info.pw_gid);p.chmod(0o700)
ini=f'''APP_NAME = 思跃代码协作
RUN_USER = siyue-gitea
RUN_MODE = prod
WORK_PATH = {ROOT}/gitea
[server]
DOMAIN = www.siyuejn.com
ROOT_URL = https://www.siyuejn.com/code/
HTTP_ADDR = 127.0.0.1
HTTP_PORT = 5300
DISABLE_SSH = true
SSH_ROOT_PATH = {ROOT}/gitea/ssh
OFFLINE_MODE = true
APP_DATA_PATH = {ROOT}/gitea/data
LFS_START_SERVER = false
[database]
DB_TYPE = postgres
HOST = 127.0.0.1:5432
NAME = siyue_collaboration_git
USER = siyue_collaboration_git
PASSWD = {s['gitea_db']}
SSL_MODE = disable
[repository]
ROOT = {ROOT}/gitea/repositories
FORCE_PRIVATE = true
DEFAULT_PRIVATE = private
DEFAULT_BRANCH = main
DISABLE_MIGRATIONS = true
[security]
INSTALL_LOCK = true
SECRET_KEY = {s.get('gitea_secret',s['hook'])}
INTERNAL_TOKEN = {s.get('gitea_internal',s['delivery'])}
ALLOWED_HOST_LIST = 127.0.0.1
EGRESS_MODE = lax
[service]
DISABLE_REGISTRATION = true
REQUIRE_SIGNIN_VIEW = true
ENABLE_PASSWORD_SIGNIN_FORM = false
ENABLE_NOTIFY_MAIL = false
ENABLE_CAPTCHA = false
[oauth2_client]
ENABLE_AUTO_REGISTRATION = false
ACCOUNT_LINKING = disabled
USERNAME = nickname
[actions]
ENABLED = false
[log]
MODE = console
LEVEL = Warn
'''
write(ROOT/'gitea/app.ini',ini,owner='siyue-gitea')
unit=f'''[Unit]
Description=Siyue Gitea collaboration
After=network.target postgresql.service
[Service]
User=siyue-gitea
Group=siyue-gitea
WorkingDirectory={ROOT}/gitea
ExecStart={ROOT}/bin/gitea web --config {ROOT}/gitea/app.ini
Restart=on-failure
MemoryMax=320M
CPUQuota=50%
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths={ROOT}/gitea
[Install]
WantedBy=multi-user.target
'''
write('/etc/systemd/system/siyue-collaboration-gitea.service',unit,0o644)
run(['systemctl','daemon-reload']);run(['systemctl','enable','--now','siyue-collaboration-gitea'])
run(['systemctl','restart','siyue-collaboration-gitea'])
import time,urllib.request
for _ in range(40):
 try:
  with urllib.request.urlopen('http://127.0.0.1:5300/api/healthz',timeout=2) as response:
   if response.status==200:break
 except Exception:time.sleep(1)
else:raise RuntimeError('Gitea did not start')
cli=['runuser','-u','siyue-gitea','--',str(ROOT/'bin/gitea'),'--config',str(ROOT/'gitea/app.ini')]
users=run(cli+['admin','user','list'])
if 'siyue-control' not in users:run(cli+['admin','user','create','--username','siyue-control','--email','control@users.siyuejn.com','--admin','--random-password','--must-change-password=false'])
if not s.get('gitea_token'):
 s['gitea_token']=run(cli+['admin','user','generate-access-token','--username','siyue-control','--token-name','collaboration-controller','--scopes','all','--raw']).strip().splitlines()[-1];write(state,json.dumps(s,indent=2))
sources=run(cli+['admin','auth','list'])
if 'siyue' not in sources:
 try:
  with urllib.request.urlopen('https://www.siyuejn.com/miniapp/api/collaboration/oidc/.well-known/openid-configuration',timeout=5) as response:ready='issuer' in json.load(response)
 except Exception:ready=False
 if ready:run(cli+['admin','auth','add-oauth','--name','siyue','--provider','openidConnect','--key','siyue-gitea','--secret',s['oidc'],'--auto-discover-url','https://www.siyuejn.com/miniapp/api/collaboration/oidc/.well-known/openid-configuration'])
 else:print('OIDC source will be registered after the API route is installed')
source_id=sql('siyue_collaboration_git',"SELECT id FROM login_source WHERE name='siyue';").strip()
cert=ROOT/'config/oidc.pfx'
if not cert.exists():
 run(['openssl','req','-x509','-newkey','rsa:3072','-keyout',str(ROOT/'config/oidc.key'),'-out',str(ROOT/'config/oidc.crt'),'-sha256','-days','730','-nodes','-subj','/CN=Siyue Collaboration OIDC'])
 run(['openssl','pkcs12','-export','-out',str(cert),'-inkey',str(ROOT/'config/oidc.key'),'-in',str(ROOT/'config/oidc.crt'),'-passout','pass:'])
 info=pwd.getpwnam('siyue-collab');os.chown(cert,info.pw_uid,info.pw_gid);cert.chmod(0o600)
settings={'ConnectionStrings':{'Collaboration':f"Host=127.0.0.1;Database=siyue_collaboration;Username=siyue_collaboration;Password={s['db']}",'Identity':f"Host=127.0.0.1;Database=siyue_miniapp;Username=siyue_collaboration_identity;Password={s['identity']}"},'PathBase':'/miniapp','PublicUrl':'https://www.siyuejn.com/miniapp/collaboration/','Keys':{'Directory':'/keys'},'Attachments':{'Directory':'/attachments'},'Oidc':{'Issuer':'https://www.siyuejn.com/miniapp/api/collaboration/oidc/','ClientId':'siyue-gitea','ClientSecret':s['oidc'],'Certificate':'/config/oidc.pfx','CertificatePassword':'','RedirectUri':'https://www.siyuejn.com/code/user/oauth2/siyue/callback'},'Gitea':{'InternalUrl':'http://127.0.0.1:5300','PublicUrl':'https://www.siyuejn.com/code','Owner':'siyue-projects','Token':s['gitea_token'],'AuthSourceId':int(source_id or '0'),'WebhookUrl':'http://127.0.0.1:5301/miniapp/api/collaboration/hooks/gitea','WebhookSecret':s['hook']},'Delivery':{'Url':'http://127.0.0.1:5302','Secret':s['delivery']}}
write(ROOT/'config/settings.json',json.dumps(settings,indent=2),owner='siyue-collab')
print('Gitea and dedicated databases configured; teacher credentials unchanged')
