'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const api=require('./java-staff.cjs');
test('staff profile requires all-scope account.update and only lists exact write mapping',()=>{
 assert.equal(api.operations[0].id,'staff.profile.update');
 assert.equal(api.operations[0].permissions.join(','),'account.update');
 assert.deepEqual(api.operations[0].scopes,['all']);
 assert.equal(require('./java-feature-operations.cjs').list({scope:'campus',permissions:['account.update']}).some(op=>op.id==='staff.profile.update'),false);
 assert.ok(require('./java-feature-operations.cjs').list({scope:'all',permissions:['account.update']}).some(op=>op.id==='staff.profile.update'));
 assert.throws(()=>api.authorize({scope:'all',permissions:[]}));
 assert.throws(()=>api.authorize({scope:'campus',permissions:['account.update']}));
});
test('staff profile rejects unknown fields, malformed ids, invalid dates and invalid phone numbers',()=>{
 assert.throws(()=>api.validate({patch:{roleIds:['admin']}}));
 assert.throws(()=>api.validate({patch:{phone:'123'}}));
 assert.throws(()=>api.validate({patch:{employmentStart:'2026-02-30'}}));
 assert.throws(()=>api.validate({patch:{campusId:'../other'}}));
 assert.throws(()=>api.validate({patch:{title:'x'},expectedVersion:-1}));
 assert.doesNotThrow(()=>api.validate({patch:{phone:'+86 139-0000-0000',employmentStart:'2020-02-29',employmentEnd:null,title:'老师',note:'备注',campusId:'campus-1'},expectedVersion:0}));
});
test('staff profile CLI sends reads and writes only to Java v2 and requires idempotency key for writes',async()=>{
 const calls=[],client={request:async(path,options)=>{calls.push({path,options});return {ok:true};}},identity={scope:'all',permissions:['account.update']};
 await api.run(['profile','employee-1'],client,identity);
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),dir=fs.mkdtempSync(path.join(os.tmpdir(),'staff-profile-cli-')),input=path.join(dir,'input.json');
 try{fs.writeFileSync(input,JSON.stringify({patch:{title:'讲师'},expectedVersion:0}));
 await api.run(['profile','employee-1','--input',input,'--key','staff-profile-idem-0001'],client,identity);
 assert.equal(calls[0].path,'system/staff/employee-1/profile');
 assert.equal(calls[1].options.method,'PUT');assert.equal(calls[1].options.idempotencyKey,'staff-profile-idem-0001');
 assert.equal(calls[1].options.body.patch.title,'讲师');
 await assert.rejects(()=>api.run(['profile','employee-1','--input',input],client,identity));
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
