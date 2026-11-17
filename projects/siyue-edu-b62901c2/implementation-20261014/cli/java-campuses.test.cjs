'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const api=require('./java-campuses.cjs');
const all={scope:'all',permissions:['settings.update']};
test('campus list detail writes retain Java routes version and idempotency',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'campus-cli-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'in.json'),calls=[],client={request:async(...args)=>calls.push(args)};
 await api.run(['list','--page','2','--size','20'],client,all);assert.deepEqual(calls.pop(),['settings/campuses?page=2&size=20']);
 await api.run(['detail','CP_1'],client,all);assert.deepEqual(calls.pop(),['settings/campuses/CP_1']);
 const input={name:'合成校区',rooms:[{name:'教室一',capacity:20,virtualSeats:2}],subjects:['数学'],grades:['小学一年级']};
 fs.writeFileSync(file,JSON.stringify(input));await api.run(['create','--input',file,'--key','campus-cli-key-0001'],client,all);
 assert.deepEqual(calls.pop(),['settings/campuses',{method:'POST',idempotencyKey:'campus-cli-key-0001',body:input}]);
 const patch={patch:{active:false},expectedVersion:3};fs.writeFileSync(file,JSON.stringify(patch));
 await api.run(['update','CP_1','--input',file,'--key','campus-cli-key-0002'],client,{...all,scope:'campus'});
 assert.deepEqual(calls.pop(),['settings/campuses/CP_1',{method:'PUT',idempotencyKey:'campus-cli-key-0002',body:patch}]);
});
test('campus permission and scope are checked before network',async()=>{
 const client={request:()=>assert.fail('must not request')};
 for(const identity of [{scope:'all',permissions:[]},{...all,scope:'own'},{...all,scope:'assigned'}])await assert.rejects(api.run(['list'],client,identity),{code:'FORBIDDEN'});
 await assert.rejects(api.run(['create'],client,{...all,scope:'campus'}),{code:'FORBIDDEN'});
 const registry=require('./java-feature-operations.cjs');
 assert.deepEqual(registry.list({...all,scope:'campus'}).filter(op=>op.id.startsWith('campus.')).map(op=>op.id),['campus.update']);
 assert.equal(registry.list(all).filter(op=>op.id.startsWith('campus.')).length,2);
});
test('campus validation rejects injected fields malformed rooms and unversioned changes',async()=>{
 for(const input of [{name:'x',ownerId:'E_1'},{name:''},{name:'x',rooms:[{capacity:0}]},{name:'x',rooms:[{capacity:20,virtualSeats:-1}]},{name:'x',subjects:['数学','数学']},{name:'x',active:'yes'}])assert.throws(()=>api.validate('create',input));
 for(const input of [{patch:{name:'x'}},{patch:{foo:1},expectedVersion:1},{patch:null,expectedVersion:1}])assert.throws(()=>api.validate('update',input));
 const client={request:()=>assert.fail('must not request')};
 for(const args of [['list','--size','101'],['list','--page','1','--page','2'],['detail','../x'],['list','--key','x']])await assert.rejects(api.run(args,client,all));
});
test('real entrypoint exposes campus adapter and help',t=>{
 const {spawnSync}=require('node:child_process'),auth=require('./auth-client.cjs');
 const dir=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'campus-entry-')));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const env={...process.env,SIYUE_CONFIG_DIR:path.join(dir,'config')},preload=path.join(dir,'fetch.cjs');
 auth.writeSession({server:'https://synthetic.example/crm-api/v2/',token:'synthetic',apiVersion:'v2'},env);
 fs.writeFileSync(preload,`global.fetch=async url=>{const path=String(url);if(path.endsWith('/identity/me'))return {ok:true,status:200,json:async()=>({code:'OK',data:${JSON.stringify(all)}})};if(path.endsWith('/settings/campuses?page=1&size=50'))return {ok:true,status:200,json:async()=>({code:'OK',data:{items:[],total:0,page:1,size:50}})};throw Error('Unexpected endpoint');};`);
 const result=spawnSync(process.execPath,[path.join(__dirname,'siyue-cli.cjs'),'campuses','list'],{encoding:'utf8',env:{...env,NODE_OPTIONS:`--require=${preload}`}});
 assert.equal(result.status,0,result.stderr);assert.equal(JSON.parse(result.stdout).data.total,0);
});
