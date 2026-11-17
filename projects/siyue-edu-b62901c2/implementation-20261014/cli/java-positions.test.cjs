'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const api=require('./java-positions.cjs');
const admin={scope:'all',permissions:['system.access.manage']};

test('position CLI exposes only global access administration and named Java v2 contracts',()=>{
 assert.equal(api.adminInterfaces.length,4);
 assert(api.adminInterfaces.every(item=>item.authorization==='system.access.manage and global scope'));
 assert.deepEqual(api.adminInterfaces.map(item=>item.method),['GET','POST','PUT','PUT']);
 assert.throws(()=>api.authorize({scope:'campus',permissions:['system.access.manage']}));
 assert.throws(()=>api.authorize({scope:'all',permissions:[]}));
});

test('position payload validation rejects delegated superadmin, unknown fields, duplicate members and stale versions',()=>{
 assert.throws(()=>api.validate('create',{name:'老师',permissionCodes:['system.access.manage']}));
 assert.throws(()=>api.validate('create',{name:'老师',permissionCodes:[],active:true}));
 assert.throws(()=>api.validate('update',{expectedVersion:0,name:'老师',active:false,permissionCodes:[]}));
 assert.throws(()=>api.validate('members',{expectedVersion:1,principalIds:['staff-1','staff-1']}));
 assert.throws(()=>api.validate('members',{expectedVersion:1,principalIds:['bad/id']}));
 assert.deepEqual(api.validate('members',{expectedVersion:2,principalIds:['staff-1']}),{expectedVersion:2,principalIds:['staff-1']});
});

test('position CLI reads, creates, edits and replaces membership through fixed endpoint shapes',async()=>{
 const calls=[],client={request:async(path,options)=>{calls.push({path,options});return {ok:true};}};
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),dir=fs.mkdtempSync(path.join(os.tmpdir(),'position-cli-'));
 const files={};
 for(const [name,value] of Object.entries({create:{name:'校区教务',description:'只读教务权限',permissionCodes:['student.read']},update:{expectedVersion:3,name:'校区教务',active:false,permissionCodes:['student.read']},members:{expectedVersion:4,principalIds:['employee-1','employee-2']}})){
  const file=path.join(dir,`${name}.json`);fs.writeFileSync(file,JSON.stringify(value));files[name]=file;
 }
 try{
  await api.run(['list','--page','2','--size','10'],client,admin);
  await api.run(['create','--input',files.create,'--key','position-create-idem-0001'],client,admin);
  await api.run(['update','position-1','--input',files.update,'--key','position-update-idem-0001'],client,admin);
  await api.run(['members','position-1','--input',files.members,'--key','position-members-idem-0001'],client,admin);
  assert.equal(calls[0].path,'system/positions?page=2&size=10');
  assert.equal(calls[1].path,'system/positions');assert.equal(calls[1].options.method,'POST');
  assert.equal(calls[2].path,'system/positions/position-1');assert.equal(calls[2].options.method,'PUT');
  assert.equal(calls[3].path,'system/positions/position-1/members');assert.equal(calls[3].options.body.expectedVersion,4);
  assert.equal(calls[3].options.idempotencyKey,'position-members-idem-0001');
  await assert.rejects(()=>api.run(['create','--input',files.create],client,admin));
  await assert.rejects(()=>api.run(['list'],client,{scope:'campus',permissions:['system.access.manage']}));
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
