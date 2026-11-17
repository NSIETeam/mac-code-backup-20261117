from pathlib import Path
import os,json,hashlib,base64,uuid,re,getpass

os.umask(0o077)
root=Path(__file__).resolve().parent
comparison=json.loads((root/'comparison.json').read_text())
assert not comparison['conflicts']
names=comparison['missing']
assert len(names)==140 and len(set(names))==140
secret=getpass.getpass('Initial password: ')
assert secret
accounts=[]
for name in names:
    salt=os.urandom(16)
    digest=hashlib.pbkdf2_hmac('sha256',secret.encode(),salt,600000,32)
    accounts.append(dict(name=name,account=name,staffId=str(uuid.uuid4()),credentialId=str(uuid.uuid4()),auditId=str(uuid.uuid4()),hash='v1:'+base64.b64encode(salt).decode()+':'+base64.b64encode(digest).decode()))
payload=root/'initialization-payload.json'
assert not payload.exists()
payload.write_text(json.dumps({'accounts':accounts},ensure_ascii=False,indent=2))
(root/'payload-sha256.txt').write_text(hashlib.sha256(payload.read_bytes()).hexdigest())
(root/'account-manifest.json').write_text(json.dumps([{k:v for k,v in row.items() if k!='hash'} for row in accounts],ensure_ascii=False,indent=2))
template=(root.parent/'教师账号初始化-20261104'/'apply_accounts.py').read_text()
template=re.sub(r'\b100\b',str(len(accounts)),template)
template=template.replace('teacher-init-20261104','employee-list-init-20261104').replace('maintenance.teacher-account.create.ssh-root.20261104','maintenance.teacher-account.create.employee-list.20261104')
(root/'apply_accounts.py').write_text(template)
verify=(root.parent/'教师账号初始化-20261104'/'verify_logins.py').read_text()
verify=verify.replace("rows=json.loads((ROOT/'initialization-payload.json').read_text())['accounts']", "rows=json.loads((ROOT/'account-manifest.json').read_text())")
verify=verify.replace('for row in [rows[0],rows[49],rows[99]]:', 'for row in [rows[0],rows[len(rows)//2],rows[-1]]:')
(root/'verify_logins.py').write_text(verify)
print(json.dumps({'prepared':len(accounts),'existingPreserved':len(comparison['existing'])}))
