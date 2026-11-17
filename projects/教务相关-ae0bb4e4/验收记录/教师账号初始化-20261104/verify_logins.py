"""Exercise normal sign-in only; password comes from stdin and tokens stay in memory."""
from pathlib import Path
import json,sys,urllib.request,urllib.error,datetime
ROOT=Path(__file__).resolve().parent
secret=sys.stdin.readline().rstrip('\n')
assert secret
rows=json.loads((ROOT/'initialization-payload.json').read_text())['accounts']
base='https://www.siyuejn.com/miniapp/api'
def call(path,method='GET',data=None,token=None):
 headers={'Accept':'application/json'}
 body=None
 if data is not None:body=json.dumps(data,ensure_ascii=False).encode();headers['Content-Type']='application/json'
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+path,data=body,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=30) as r:
   raw=r.read();return r.status,json.loads(raw) if raw else None
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:obj=json.loads(raw) if raw else None
  except ValueError:obj=None
  return e.code,obj
results=[]
for row in [rows[0],rows[49],rows[99]]:
 token=None
 try:
  status,r=call('/auth/password','POST',{'account':row['account'],'password':secret})
  assert status==200,(row['name'],'login status',status)
  token=r['token'];u=r['user']
  assert u and u['id']==row['staffId'] and u['name']==row['name'] and u['role']=='teacher' and u['mustChangePassword'] is True
  mstatus,me=call('/me',token=token);assert mstatus==200 and me['mustChangePassword'] is True
  wstatus,_=call('/workspace',token=token);assert wstatus==428,(row['name'],'workspace must require password change',wstatus)
  pstatus,_=call('/account/password','POST',{'currentPassword':secret,'newPassword':secret,'confirmPassword':secret},token)
  assert pstatus==400,(row['name'],'unchanged initial password must be rejected',pstatus)
  result={'name':row['name'],'login':status,'role':u['role'],'mustChangePassword':u['mustChangePassword'],'businessBeforeChange':wstatus,'reuseInitialPassword':pstatus}
 finally:
  if token:
   ls,_=call('/logout','POST',token=token)
   assert ls in (200,204),'Logout did not succeed'
 if token:
  after,_=call('/me',token=token);assert after==401
  result.update({'logout':ls,'sessionAfterLogout':after})
 results.append(result)
out={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'normalLoginSamples':results,'passwordsChanged':False,'tokensPersisted':False}
(ROOT/'public-login-verification.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
print(json.dumps(out,ensure_ascii=False,indent=2))
