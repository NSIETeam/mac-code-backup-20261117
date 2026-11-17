'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const api=require('./java-settings.cjs');
test('settings reads and global replacement use fixed routes and idempotency',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'settings-cli-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'in.json'),calls=[],client={request:async(...args)=>calls.push(args)},identity={scope:'all',permissions:['settings.update']};
 const input={section:'attendance',values:{presentConsume:false}};fs.writeFileSync(file,JSON.stringify(input));
 await api.run(['get'],client,identity);assert.deepEqual(calls.pop(),['settings']);
 await api.run(['update','--input',file,'--key','settings-cli-key-0001'],client,identity);
 assert.deepEqual(calls.pop(),['settings',{method:'PUT',idempotencyKey:'settings-cli-key-0001',body:input}]);
 await assert.rejects(api.run(['update','--input',file,'--key','settings-cli-key-0001'],client,{...identity,scope:'campus'}),{code:'FORBIDDEN'});
 assert.ok(!require('./java-feature-operations.cjs').list({...identity,scope:'campus'}).some(op=>op.id==='settings.update'));
});
test('settings rejects unknown fields and unsafe relaxation',()=>{
 for(const input of [{section:'bad',values:{x:true}},{section:'attendance',values:{}},{section:'attendance',values:{presentConsume:'false'}},{section:'move',values:{sameGrade:false}},{section:'move',values:{maxDays:1.5}},{section:'enrollment',values:{allowOverCapacity:true}},{section:'organization',values:JSON.parse('{"__proto__":"x"}')}])assert.throws(()=>api.validate(input));
 assert.deepEqual(api.validate({section:'move',values:{maxDays:30}}),{section:'move',values:{maxDays:30}});
});
