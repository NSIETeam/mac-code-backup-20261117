import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SiyueAgent} from '../integrations/agent/client.mjs';
import {agentTools} from '../integrations/agent/tools.mjs';
const read=async name=>JSON.parse(await readFile(new URL('../integrations/agent/'+name,import.meta.url),'utf8'));
const [spec,operations,tools]=await Promise.all(['openapi.json','operations.json','tools.json'].map(read));
const entries=Object.entries(spec.paths).flatMap(([path,methods])=>Object.entries(methods).map(([method,value])=>({path,method,...value})));
test('public schema, operation catalog and executable tool definitions agree',()=>{
 assert.equal(entries.length,65);
 assert.deepEqual(operations.map(x=>x.id).sort(),entries.map(x=>x.operationId).sort());
 assert.deepEqual(tools.map(x=>x.name).sort(),entries.map(x=>x.operationId).sort());
 for(const entry of entries){
  const operation=operations.find(x=>x.id===entry.operationId),tool=tools.find(x=>x.name===entry.operationId);
  assert.equal(operation.path,entry.path);assert.equal(operation.method,entry.method.toUpperCase());
  for(const key of ['scope','retry','key','admin']){assert.equal(operation[key],entry['x-agent'][key]);assert.equal(tool['x-siyue'][key],entry['x-agent'][key]);}
  assert.equal(tool.annotations.readOnlyHint,entry.method==='get');
 }
});
test('new administrative tools remain unavailable without an authorized capability',async()=>{
 for(const name of ['staff_account','guangzhou_binding']){
  const operation=operations.find(x=>x.id===name);assert.equal(operation.scope,'organization:write');assert.equal(operation.admin,true);assert.equal(operation.retry,'never');
 }
 const client=new SiyueAgent({baseUrl:'https://example.test/miniapp',token:'sya_test',fetchImpl:async()=>{throw Error('must not send request');}});
 client.operations=new Map(operations.filter(x=>x.id==='list_classes').map(x=>[x.id,x]));
 const adapter=agentTools(client);assert.deepEqual(adapter.list().map(x=>x.name),['list_classes']);
 for(const name of ['staff_account','guangzhou_binding'])await assert.rejects(()=>adapter.execute(name,{body:{}}),/未授权/);
});
