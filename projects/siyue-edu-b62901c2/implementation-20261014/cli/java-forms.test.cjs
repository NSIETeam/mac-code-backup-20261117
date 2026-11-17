'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const forms=require('./java-forms.cjs');
const draft=()=>({campusId:'synthetic-campus',title:'合成问卷',formKind:'survey',collectLead:false,fields:[{id:'answer',title:'回答',type:'text',required:true}]});
test('form CLI uses fixed endpoints, query version and same idempotency key',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'java-forms-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'input.json'),calls=[],client={request:async(...args)=>calls.push(args)},identity={permissions:forms.operations.flatMap(o=>o.permissions)};
 const payloads={create:draft(),update:{...draft(),expectedVersion:2},publish:{expectedVersion:3},unpublish:{expectedVersion:4},'trial-update':{trial:null,expectedVersion:1},'reward-update':{reward:null,expectedVersion:1},link:{formId:'form-a'},review:{decision:'reject',note:'合成验证',expectedVersion:1}};
 for(const op of forms.operations.filter(o=>o.method!=='GET')){
   fs.writeFileSync(file,JSON.stringify(payloads[op.name]));
   await forms.run([op.name,...(op.target?['target-a']:[]),'--input',file,'--key','form-cli-key-0001'],client,identity);
   const [url,request]=calls.pop();assert.equal(url,op.endpoint.replace('{id}','target-a')+(op.name==='update'?'?expectedVersion=2':''));
   assert.equal(request.method,op.method);assert.equal(request.idempotencyKey,'form-cli-key-0001');
   assert.deepEqual(request.body,op.name==='update'?draft():payloads[op.name]);
 }
 await assert.rejects(forms.run(['create','--input',file,'--key','form-cli-key-0001'],client,{permissions:[]}),{code:'FORBIDDEN'});
 await assert.rejects(forms.run(['publish','../../other','--input',file,'--key','form-cli-key-0001'],client,identity));
 assert.equal(calls.length,0);
});
test('form validator rejects invalid fields, mapping, version and survey lead creation',()=>{
 const op=forms.operations.find(o=>o.name==='create');
 for(const input of [{...draft(),extra:true},{...draft(),collectLead:true},{...draft(),fields:[]},{...draft(),fields:[...draft().fields,...draft().fields]},{...draft(),fields:[{...draft().fields[0],options:['unexpected']}]},{...draft(),formKind:'lead',collectLead:true}])assert.throws(()=>forms.validate(op,input));
 assert.throws(()=>forms.validate(forms.operations.find(o=>o.name==='publish'),{expectedVersion:0}));
 assert.throws(()=>forms.validate(forms.operations.find(o=>o.name==='review'),{decision:'approve',note:'',expectedVersion:1}));
 const lead={...draft(),formKind:'lead',collectLead:true,fields:[{id:'name',title:'姓名',type:'name'},{id:'phone',title:'电话',type:'phone'}],leadFieldMapping:{studentName:'name',guardianPhone:'phone'}};
 assert.deepEqual(forms.validate(op,lead),lead);
 const custom={...draft(),formKind:'custom'};assert.deepEqual(forms.validate(op,custom),custom);
});
test('registry exposes only permitted form commands',()=>{
 const registry=require('./java-feature-operations.cjs');
 assert.deepEqual(registry.list({scope:'all',permissions:['crm.form.publish']}).filter(o=>o.id.startsWith('form.')).map(o=>o.id),['form.publish','form.unpublish']);
 assert.ok(!registry.list({scope:'all',permissions:['crm.form.submission.review']}).some(o=>o.id==='form.submission.review'));
});

test('trial CLI reads without mutation flags and validates explicit disable or complete configuration',async()=>{
 const calls=[],client={request:async(...args)=>calls.push(args)};
 await forms.run(['trial-get','form-a'],client,{permissions:['crm.form.read']});
 assert.deepEqual(calls,[['crm/forms/form-a/trial',{method:'GET'}]]);
 await assert.rejects(forms.run(['trial-get','form-a','--key','unexpected'],client,{permissions:['crm.form.read']}));
 const op=forms.operations.find(o=>o.name==='trial-update');
 const slot={id:'slot-a',classId:'class-a',sessionId:'session-a',capacity:2};
 const trial={enabled:true,gradeFieldId:'grade',subjectFieldId:'subject',nameFieldId:'name',phoneFieldId:'phone',slots:[slot]};
 assert.deepEqual(forms.validate(op,{trial,expectedVersion:1}),{trial,expectedVersion:1});
 assert.deepEqual(forms.validate(op,{trial:null,expectedVersion:1}),{trial:null,expectedVersion:1});
 for(const input of [{expectedVersion:1},{trial:{...trial,enabled:false},expectedVersion:1},{trial:{...trial,slots:[slot,slot]},expectedVersion:1},{trial:{...trial,slots:[{...slot,capacity:0}]},expectedVersion:1}])assert.throws(()=>forms.validate(op,input));
});

test('reward configuration supports explicit removal and validates coupon or material links',async()=>{
 const op=forms.operations.find(o=>o.name==='reward-update');
 for(const reward of [null,{type:'coupon',promotionId:'coupon-a'},{type:'material_link',title:'资料',url:'https://example.test/material'}])assert.deepEqual(forms.validate(op,{reward,expectedVersion:1}),{reward,expectedVersion:1});
 for(const reward of [{type:'coupon'},{type:'material_link',title:'资料',url:'javascript:alert(1)'},{type:'unknown'}])assert.throws(()=>forms.validate(op,{reward,expectedVersion:1}));
 const calls=[];await forms.run(['reward-get','form-a'],{request:async(...args)=>calls.push(args)},{permissions:['crm.form.read']});
 assert.deepEqual(calls,[['crm/forms/form-a/reward',{method:'GET'}]]);
});
