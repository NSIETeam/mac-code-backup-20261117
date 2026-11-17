import test from 'node:test';
import assert from 'node:assert/strict';
import { SiyueAgent } from '../integrations/agent/client.mjs';
import { agentTools } from '../integrations/agent/tools.mjs';
const read = { id:'workspace', method:'GET', path:'/api/workspace', retry:'read' };
const print = { id:'submit_print', method:'POST', path:'/api/print-jobs', retry:'idempotent', key:'requestKey' };
const mark = { id:'mark_student', method:'PUT', path:'/api/class-sessions/{id}/students/{studentId}', retry:'revision' };
const upload = { id:'upload_handout', method:'POST', path:'/api/lectures/{id}/handouts', retry:'never' };
const json = (value, status=200, headers={}) => new Response(JSON.stringify(value), { status, headers });
function client(fetchImpl, extra={}) {
  const client = new SiyueAgent({ baseUrl:'https://example.test/miniapp', token:'sya_test', fetchImpl, sleep:async()=>{}, random:()=>0, ...extra });
  client.operations=new Map([read,print,mark,upload].map(x=>[x.id,x]));return client;
}
test('reads retry with bounded attempts and preserve reverse-proxy prefix', async()=>{
  let count=0;const c=client(async(url)=>{assert.equal(String(url),'https://example.test/miniapp/api/workspace');if(++count<3)return json({message:'busy'},503);return json({ok:true});});
  assert.deepEqual(await c.call('workspace'),{ok:true});assert.equal(count,3);
});
test('print retries retain exactly the same request key and serialized body',async()=>{
  const bodies=[];const c=client(async(_,options)=>{bodies.push(options.body);if(bodies.length===1)throw new Error('lost reply after commit');return json([{id:'original-job'}]);});
  await c.call('submit_print',{body:{documentIds:['d'],requestKey:'saved-before-submit'}});assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);
  await assert.rejects(()=>c.call('submit_print',{body:{documentIds:['d']}}),/requestKey/);
});
test('revision conflicts, authorization errors and unsafe writes never auto-retry',async()=>{
  for (const status of [400,401,403,409]) {let count=0;const c=client(async()=>{count++;return json({code:'CONFLICT',traceId:'trace'},status);});await assert.rejects(()=>c.call('submit_print',{body:{requestKey:'saved-key'}}),e=>e.status===status&&e.traceId==='trace');assert.equal(count,1);}
  for (const operation of ['mark_student','upload_handout']) {let count=0;const c=client(async()=>{count++;throw new Error('timeout');});await assert.rejects(()=>c.call(operation,{path:{id:'a',studentId:'b'},body:{revision:0}}));assert.equal(count,1);}
});
test('Retry-After is honored, oversized delays are returned without early retry',async()=>{
  const waits=[];let count=0;const c=client(async()=>++count===1?json({},429,{'Retry-After':'5'}):json({ok:true}),{sleep:async ms=>waits.push(ms)});
  await c.call('workspace');assert.deepEqual(waits,[5000]);
  count=0;const slow=client(async()=>{count++;return json({},429,{'Retry-After':'120'});});await assert.rejects(()=>slow.call('workspace'));assert.equal(count,1);
});
test('concurrent calls are capped and pending queue is bounded',async()=>{
  let active=0,peak=0;const c=client(async()=>{active++;peak=Math.max(active,peak);await new Promise(r=>setTimeout(r,5));active--;return json({});},{concurrency:3});
  await Promise.all(Array.from({length:20},()=>c.call('workspace')));assert.equal(peak,3);
  let finish;const blocked=client(()=>new Promise(r=>finish=()=>r(json({}))),{concurrency:1,maxPending:0});const first=blocked.call('workspace');await new Promise(r=>setTimeout(r,0));await assert.rejects(()=>blocked.call('workspace'),e=>e.code==='CLIENT_BUSY');finish();await first;
});
test('cancelled queued requests release their slot without an extra network call',async()=>{
  let calls=0,finish;const c=client(async()=>{calls++;if(calls===1)return new Promise(r=>finish=()=>r(json({})));return json({});},{concurrency:1});
  const first=c.call('workspace');await new Promise(r=>setTimeout(r,0));const abort=new AbortController();const queued=c.call('workspace',{signal:abort.signal});abort.abort(new Error('stop'));await assert.rejects(()=>queued,/stop/);finish();await first;await c.call('workspace');assert.equal(calls,2);
});
test('polling stops on failed/review/uncertain and returns the actual state',async()=>{
  for(const state of ['failed','review','uncertain']) {const c=client(async()=>json({state,terminal:true,needsReview:state!=='failed'}));c.operations.set('task_status',{id:'task_status',method:'GET',path:'/api/automation/v1/tasks/{kind}/{id}',retry:'read'});assert.equal((await c.waitTask('print','x')).state,state);}
});
test('tokens are not serialized and insecure endpoints/path traversal are rejected',async()=>{
  assert.throws(()=>new SiyueAgent({baseUrl:'http://evil.test',token:'sya_test'}));const c=client(async()=>json({}));assert.ok(!JSON.stringify(c).includes('sya_test'));
  await assert.rejects(()=>c.call('mark_student',{path:{id:'../foo',studentId:'b'}}),/路径/);
});
test('tool list respects capability scopes and resolves only explicitly supplied files',async()=>{
  let observed;const c=client(async(_,options)=>{observed=options;return json({id:'upload'});});c.operations=new Map([[upload.id,upload]]);
  const adapter=agentTools(c,{attachments:new Map([['approved',{blob:new Blob(['pdf']),fileName:'讲义.pdf'}]])});assert.equal(adapter.list().length,1);
  await assert.rejects(()=>adapter.execute('submit_print',{}),/未授权/);await assert.rejects(()=>adapter.execute('upload_handout',{path:{id:'a'},fileRef:'unknown'}),/附件/);
  await adapter.execute('upload_handout',{path:{id:'a'},fileRef:'approved'});assert.equal(observed.body.get('file').name,'讲义.pdf');assert.equal(observed.headers['Content-Type'],undefined);
});
