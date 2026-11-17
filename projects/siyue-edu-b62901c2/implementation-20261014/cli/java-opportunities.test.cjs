'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const api=require('./java-opportunities.cjs');
test('opportunity writes map DTO method version and idempotency exactly',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'opportunity-cli-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'input.json'),calls=[],client={request:async(...args)=>calls.push(args)},identity={permissions:['crm.opportunity.write','crm.customer.read']};
 const samples={create:{customerId:'C_1',title:'合成需求',subject:'数学',term:'秋季'},followup:{channel:'phone',detail:'合成记录',expectedVersion:2,nextFollowAt:'2099-01-01T08:00:00+08:00'},advance:{stage:'appointment',expectedVersion:3,reason:'预约'}};
 for(const op of api.operations){fs.writeFileSync(file,JSON.stringify(samples[op.name]));await api.run([op.name,...(op.name==='create'?[]:['O_1']),'--input',file,'--key','opportunity-test-key-0001'],client,identity);assert.deepEqual(calls.at(-1),[op.endpoint.replace('{id}','O_1'),{method:op.method,idempotencyKey:'opportunity-test-key-0001',body:samples[op.name]}]);}
});
test('opportunity validation reserves won for receipts and validates future followups',()=>{
 for(const stage of ['won','contacting'])assert.throws(()=>api.validate('advance',{stage,expectedVersion:1}));
 for(const nextFollowAt of ['2026-02-30T00:00:00Z','2000-01-01T00:00:00Z','2099-01-01'])assert.throws(()=>api.validate('followup',{channel:'phone',detail:'x',expectedVersion:1,nextFollowAt}));
 assert.throws(()=>api.validate('advance',{stage:'closed',expectedVersion:0}));
 assert.throws(()=>api.validate('create',{customerId:'C',title:'x',subject:'x',term:'x',ownerId:'EMP'}));
 assert.doesNotThrow(()=>api.validate('advance',{stage:'closed',reason:'关闭',expectedVersion:1,nextFollowAt:null}));
});
test('opportunity linked-record permissions and server errors never fall back',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'opportunity-permission-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'input.json'),calls=[],identity={permissions:['crm.opportunity.write','crm.customer.read']};
 const error=Object.assign(new Error('请通过需求核对入口选择意向部门和项目'),{code:'INVALID_INPUT'}),client={request:async(...args)=>{calls.push(args);throw error;}};
 for(const extra of [{sourceLeadId:'L_1'},{activityId:'A_1'},{studentId:'S_1'}]){fs.writeFileSync(file,JSON.stringify({customerId:'C',title:'x',subject:'x',term:'x',...extra}));await assert.rejects(api.run(['create','--input',file,'--key','opportunity-test-key-0001'],client,identity),{code:'FORBIDDEN'});}
 assert.equal(calls.length,0);fs.writeFileSync(file,JSON.stringify({customerId:'C',title:'x',subject:'x',term:'x'}));
 await assert.rejects(api.run(['create','--input',file,'--key','opportunity-test-key-0001'],client,identity),e=>e===error);assert.equal(calls.length,1);
});
test('opportunity CLI entrypoint and registry are accessible only with their grants',t=>{
 const {spawnSync}=require('node:child_process'),auth=require('./auth-client.cjs');
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'opportunity-entry-')));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const env={...process.env,SIYUE_CONFIG_DIR:path.join(root,'config')},preload=path.join(root,'fetch.cjs'),file=path.join(root,'input.json');
 auth.writeSession({server:'https://synthetic.example/crm-api/v2/',token:'synthetic',apiVersion:'v2'},env);
 fs.writeFileSync(file,JSON.stringify({stage:'trial',expectedVersion:2}));
 fs.writeFileSync(preload,`global.fetch=async(url,o)=>{if(String(url).endsWith('/crm-api/v2/identity/me'))return {ok:true,status:200,json:async()=>({code:'OK',data:{permissions:['crm.opportunity.write']}})};if(String(url).endsWith('/crm-api/v2/crm/opportunities/O_1/stage')&&o.method==='PUT'&&o.headers['Idempotency-Key']==='opportunity-entry-key-0001')return {ok:true,status:200,json:async()=>({code:'OK',data:{stage:'trial'}})};throw Error('Unexpected endpoint');};`);
 const run=args=>spawnSync(process.execPath,[path.join(__dirname,'siyue-cli.cjs'),...args],{encoding:'utf8',env:{...env,NODE_OPTIONS:`--require=${preload}`}});
 const result=run(['opportunities','advance','O_1','--input',file,'--key','opportunity-entry-key-0001']);assert.equal(result.status,0,result.stderr);assert.equal(JSON.parse(result.stdout).data.stage,'trial');
 const result2=run(['registry','operations','opportunity.']);assert.equal(result2.status,0,result2.stderr);const ops=JSON.parse(result2.stdout).businessOperations;assert.ok(ops.some(op=>op.id==='opportunity.advance'&&op.cli.command==='opportunities advance'));assert.ok(!ops.some(op=>op.id==='opportunity.create'));
});
