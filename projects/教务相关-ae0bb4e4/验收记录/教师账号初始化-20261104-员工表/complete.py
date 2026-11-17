from pathlib import Path
import json,datetime,os
root=Path(__file__).resolve().parent
os.umask(0o077)
before=json.loads((root/'inventory.json').read_text())
after=json.loads((root/'inventory-after.json').read_text())
names=json.loads((root/'roster.json').read_text())
created=json.loads((root/'account-manifest.json').read_text())
newids={r['staffId'] for r in created}
rows=after['matches']
assert len(rows)==len(names)==237
assert {r['Name'] for r in rows}==set(names)
assert all(r['Name']==r['Account']==r['passwordIdentity'] and r['Role']=='teacher' and r['Enabled'] and not r['Archived'] for r in rows)
new=[r for r in rows if r['Id'] in newids]
assert len(new)==140
assert all(r['MustChangePassword'] and not r['CanSelectAnyCampus'] and r['assignmentCount']==0 and r['sessionCount']==0 for r in new)
old={r['Id']:r for r in before['matches']}
for row in rows:
    if row['Id'] in old:
        assert {k:v for k,v in row.items() if k!='sessionCount'}=={k:v for k,v in old[row['Id']].items() if k!='sessionCount'}
hashes=json.loads((root/'hash-verification.json').read_text())
assert hashes['verifiedHashes']==140
login=json.loads((root/'public-login-verification.json').read_text())
assert len(login['normalLoginSamples'])==3
receipt=json.loads((root/'created.json').read_text())
assert receipt['existingStaffUnchanged'] and receipt['existingAccountsUnchanged']
result={'requested':237,'created':140,'existingPreserved':97,'allEnabledTeachers':True,'newAccountsMustChangePassword':True,'newPasswordHashesVerified':140,'publicLoginSamplesPassed':3,'verificationSessionsClosed':True,'existingPasswordsAndProfilesUnchanged':True,'verifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(root/'completion.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
(root/'initialization-payload.json').unlink()
root.chmod(0o700)
print(json.dumps(result,ensure_ascii=False,indent=2))
