'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const views=require('./java-saved-views.cjs');
test('saved view list enforces queue permission',async()=>{
 const requests=[],client={request:async(...args)=>requests.push(args)};
 await views.run(['list','leads'],client,{permissions:['crm.lead.read']});
 assert.equal(requests[0][0],'crm/saved-views?resourceType=leads');
 await assert.rejects(views.run(['list','tasks'],client,{permissions:['crm.lead.read']}),{code:'FORBIDDEN'});
 assert.equal(requests.length,1);
});
test('saved view mutations retain methods, versions and idempotency',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'saved-view-test-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'input.json'),requests=[],client={request:async(...args)=>requests.push(args)},identity={permissions:['crm.lead.read']};
 for(const [name,input,method,endpoint] of [
  ['create',{resourceType:'leads',name:'待跟进',filters:{status:'new'}},'POST','crm/saved-views'],
  ['update',{name:'新名称',filters:{},expectedVersion:2},'PUT','crm/saved-views/VIEW_1'],
  ['archive',{expectedVersion:3},'POST','crm/saved-views/VIEW_1/delete']]) {
   fs.writeFileSync(file,JSON.stringify(input),{mode:0o600});
   await views.run([name,...(name==='create'?[]:['VIEW_1']),'--input',file,'--key','saved-view-test-key-001'],client,identity);
   const [url,options]=requests.at(-1);assert.equal(url,endpoint);assert.equal(options.method,method);assert.deepEqual(options.body,input);assert.equal(options.idempotencyKey,'saved-view-test-key-001');
 }
 fs.writeFileSync(file,JSON.stringify({resourceType:'tasks',name:'无权队列',filters:{}}));
 await assert.rejects(views.run(['create','--input',file,'--key','saved-view-test-key-001'],client,identity),{code:'FORBIDDEN'});
 assert.equal(requests.length,3);
});
test('invalid versions and unsupported fields fail closed',()=>{
 for(const body of [{expectedVersion:0},{expectedVersion:1,principalId:'other'},{expectedVersion:'1'}]) assert.throws(()=>views.validate('archive',body));
 assert.throws(()=>views.validate('create',{resourceType:'__proto__',name:'x',filters:{}}));
});
test('registry exposes saved-view permission alternatives',()=>{
 const op=require('./java-feature-operations.cjs').list({permissions:['crm.lead.read']}).find(op=>op.id==='crm.view.archive');
 assert.equal(op.cli.command,'saved-views archive');assert.equal(op.preview.required,false);assert.ok(op.permissionGroups[0].includes('crm.ticket.read'));
});
test('saved-views CLI entrypoint is enabled only for Java sessions',t=>{
 const {spawnSync}=require('node:child_process'),auth=require('./auth-client.cjs');
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'saved-view-cli-')));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const env={...process.env,SIYUE_CONFIG_DIR:path.join(root,'config')},preload=path.join(root,'fetch.cjs');
 fs.writeFileSync(preload,`global.fetch=async(url)=>{if(String(url).endsWith('/crm-api/v2/identity/me'))return {ok:true,status:200,json:async()=>({code:'OK',data:{principalId:'synthetic',permissions:['crm.lead.read']}})};if(String(url).endsWith('/crm-api/v2/crm/saved-views?resourceType=leads'))return {ok:true,status:200,json:async()=>({code:'OK',data:[]})};throw Error('Unexpected endpoint');};`);
 auth.writeSession({server:'https://synthetic.example/crm-api/v2/',token:'synthetic',apiVersion:'v2'},env);
 const run=()=>spawnSync(process.execPath,[path.join(__dirname,'siyue-cli.cjs'),'saved-views','list','leads'],{encoding:'utf8',env:{...env,NODE_OPTIONS:`--require=${preload}`}});
 const ok=run();assert.equal(ok.status,0,ok.stderr);assert.match(ok.stdout,/\[\]/);
 auth.writeSession({server:'https://synthetic.example/crm-api/',token:'synthetic',apiVersion:'v1'},env);
 const refused=run();assert.notEqual(refused.status,0);assert.match(refused.stderr,/JAVA_V2_REQUIRED/);
});


test('customer and lead views preserve their Java resource permission boundaries',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'saved-view-legacy-'));
 t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'input.json'),requests=[],client={request:async(...args)=>requests.push(args)};
 for(const [resource,grant,filters] of [['customers','crm.customer.read',{searchType:'name',query:'模拟'}],['leads','crm.lead.read',{status:'new'}]]){
  const identity={permissions:[grant]};await views.run(['list',resource],client,identity);
  fs.writeFileSync(file,JSON.stringify({resourceType:resource,name:'模拟筛选',filters}),{mode:0o600});
  await views.run(['create','--input',file,'--key','saved-view-test-key-001'],client,identity);
  await assert.rejects(views.run(['list','tasks'],client,identity),{code:'FORBIDDEN'});
 }
 assert.equal(requests.length,4);
 await assert.rejects(views.run(['list','customers'],client,{permissions:['lead.read']}),{code:'FORBIDDEN'});
 await assert.rejects(views.run(['list','leads'],client,{permissions:['lead.read']}),{code:'FORBIDDEN'});
 const op=require('./java-feature-operations.cjs').list({permissions:['crm.lead.read']}).find(op=>op.id==='crm.view.create');
 assert.ok(op);
});
