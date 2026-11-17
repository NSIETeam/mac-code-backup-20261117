from pathlib import Path
import json,subprocess,getpass,os
root=Path(__file__).resolve().parent
os.umask(0o077)
secret=getpass.getpass('Initial password for verification: ')
assert secret
remote=r'''
import os,json,subprocess,hashlib,base64,hmac
from pathlib import Path
root=Path('/opt/siyue-miniapp/acceptance/employee-list-init-20261104')
batch=json.loads((root/'initialization-payload.json').read_text())['accounts']
ins=json.loads(subprocess.check_output(['docker','inspect','siyue-miniapp-api-1']))[0]
env=dict(x.split('=',1) for x in ins['Config']['Env'] if '=' in x)
conn=dict(x.strip().split('=',1) for x in env['ConnectionStrings__Database'].split(';') if '=' in x)
dbenv=os.environ.copy()
for key,target in [('Host','PGHOST'),('Port','PGPORT'),('Database','PGDATABASE'),('Username','PGUSER'),('Password','PGPASSWORD')]:
 if key in conn:dbenv[target]=conn[key]
ids=','.join("'"+r['credentialId']+"'" for r in batch)
q='SELECT row_to_json(p) FROM "PasswordAccounts" p WHERE "Id" IN ('+ids+');'
p=subprocess.run(['psql','-X','-A','-t','-q','-v','ON_ERROR_STOP=1'],input=q,text=True,capture_output=True,env=dbenv)
assert p.returncode==0,'Credential query failed'
rows=[json.loads(x) for x in p.stdout.splitlines() if x]
assert len(rows)==len(batch)
salts=set()
for row in rows:
 version,salt,digest=row['PasswordHash'].split(':')
 assert version=='v1' and row['MustChangePassword']
 actual=hashlib.pbkdf2_hmac('sha256',SECRET.encode(),base64.b64decode(salt),600000,32)
 assert hmac.compare_digest(actual,base64.b64decode(digest))
 salts.add(salt)
assert len(salts)==len(rows)
print(json.dumps({'verifiedHashes':len(rows),'uniqueSalts':len(salts),'mustChangePassword':True}))
'''
remote=remote.replace('SECRET',repr(secret))
ssh=['ssh','-i','/Users/king/Desktop/思跃测试服务器-SSH登录密钥.pem','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','root@47.113.200.248','python3 -']
p=subprocess.run(ssh,input=remote,text=True,capture_output=True)
assert p.returncode==0,'Remote hash verification failed'
d=json.loads(p.stdout)
(root/'hash-verification.json').write_text(json.dumps(d,ensure_ascii=False,indent=2))
print(json.dumps(d))
p=subprocess.run(['python3',str(root/'verify_logins.py')],input=secret+'\n',text=True,capture_output=True)
assert p.returncode==0,p.stderr
print(p.stdout)
