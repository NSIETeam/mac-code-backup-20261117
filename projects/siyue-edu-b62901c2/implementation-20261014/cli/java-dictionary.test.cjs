'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const api=require('./java-dictionary.cjs');
test('dictionary reads and global replacement use fixed routes and idempotency',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'dictionary-cli-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'in.json'),calls=[],client={request:async(...args)=>calls.push(args)},identity={scope:'all',permissions:['settings.update']};
 const input={key:'subjects',values:[' 数学 ','自定义']};fs.writeFileSync(file,JSON.stringify(input));
 await api.run(['get'],client,identity);assert.deepEqual(calls.pop(),['settings/dictionary']);
 await api.run(['update','--input',file,'--key','dictionary-cli-key-0001'],client,identity);
 assert.deepEqual(calls.pop(),['settings/dictionary',{method:'PUT',idempotencyKey:'dictionary-cli-key-0001',body:input}]);
 await assert.rejects(api.run(['update','--input',file,'--key','dictionary-cli-key-0001'],client,{...identity,scope:'campus'}),{code:'FORBIDDEN'});
 assert.ok(!require('./java-feature-operations.cjs').list({...identity,scope:'campus'}).some(op=>op.id==='dictionary.update'));
});
test('dictionary rejects unknown fields duplicate trimmed values and invalid size',()=>{
 for(const input of [{key:'bad',values:['x']},{key:'subjects',values:[]},{key:'subjects',values:['x',' x ']},{key:'subjects',values:[null]},{key:'subjects',values:['x'],actorId:'E1'}])assert.throws(()=>api.validate(input));
 assert.deepEqual(api.validate({key:'grades',values:['历史年级']}),{key:'grades',values:['历史年级']});
});
