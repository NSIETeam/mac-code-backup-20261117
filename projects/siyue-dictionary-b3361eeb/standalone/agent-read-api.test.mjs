import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createAgentReadApi} from './agent-read-api.mjs';
import {getCatalog,searchCatalog} from './catalog.mjs';
const profile={id:'fixture',name:'测试员工'};
const knowledge={requireAvailable:()=>({status:'active'}),requireActive:()=>{},search:()=>[]};
const read=createAgentReadApi({getCatalog,searchCatalog,knowledge});
const call=path=>read(profile,new URL('http://local.invalid'+path));

test('agent reads only one item with resolved provenance and separated proposals',()=>{
 const catalog=getCatalog();
 for(const [type,entries] of [['role',catalog.roles],['policy',catalog.policies],['process',catalog.processes]])for(const entry of entries){
  const result=call(`/api/agent/v1/entries/${type}/${entry.id}`);
  assert.equal(result.key,`${type}:${entry.id}`);assert.equal(result.content,entry);
  assert.ok(result.url.startsWith('/work/?'));assert.ok(result.readUrl.startsWith('/work/api/agent/v1/entries/'));
  if(type!=='role')assert.ok(result.sources.length,result.key);
  assert.equal(result.sourceCoverage,result.sources.length?'linked-sources':'no-linked-source');
  if(!result.sources.length)assert.ok(result.notes.some(note=>note.includes('没有关联资料依据')));
  if(type==='policy'){assert.equal(result.sourceDocument.id,entry.documentId);assert.equal(result.status,entry.status);}
  if(type==='role'){assert.match(result.proposals.status,/非已批准/);assert.equal(result.proposals.content,catalog.roleGuidance[entry.id]);}
  else assert.equal(result.proposals,null);
 }
});
test('agent search pagination preserves keyword-first order and does not return raw reference catalogs',()=>{
 const first=call('/api/agent/v1/search?q='+encodeURIComponent('退费')+'&limit=1');
 assert.equal(first.results.length,1);assert.equal(first.nextOffset,1);
 const second=call('/api/agent/v1/search?q='+encodeURIComponent('退费')+'&limit=1&offset=1');
 assert.notEqual(first.results[0].key,second.results[0].key);
 assert.equal(call('/api/agent/v1/search?q='+encodeURIComponent('退费')+'&offset=10000').nextOffset,null);
 assert.ok(!('content' in first.results[0])&&!('semanticScore' in first.results[0]));
 for(const suffix of ['&limit=0','&limit=21','&offset=-1','&offset=10001','&limit=1.1'])assert.throws(()=>call('/api/agent/v1/search?q=test'+suffix),error=>error.status===400);
 assert.throws(()=>call('/api/agent/v1/entries/policy/does-not-exist'),error=>error.status===404);
});
test('agent adapter rechecks permissions after a prior search and never grants access for names or roles',()=>{
 let active=true;const denied=()=>{const error=new Error('denied');error.status=403;throw error;};
 const guarded={...knowledge,requireAvailable:()=>active?{status:'active'}:denied(),requireActive:()=>active?undefined:denied()};
 const adapter=createAgentReadApi({getCatalog,searchCatalog,knowledge:guarded});
 assert.ok(adapter(profile,new URL('http://test/api/agent/v1/search?q='+encodeURIComponent('补卡'))).results.length);
 active=false;assert.throws(()=>adapter(profile,new URL('http://test/api/agent/v1/entries/policy/policy-leave-correction')),e=>e.status===403);
});
test('OpenAPI exposes only authenticated GET operations with stable operation IDs',async()=>{
 const spec=JSON.parse(await readFile(new URL('../standalone-dist/agent/openapi.json',import.meta.url),'utf8'));
 assert.equal(spec.openapi,'3.1.0');assert.equal(spec.components.securitySchemes.employeeSession.in,'cookie');
 assert.deepEqual(Object.values(spec.paths).flatMap(path=>Object.keys(path)),['get','get','get']);
 assert.deepEqual(Object.values(spec.paths).map(path=>path.get.operationId),['siyue_me','siyue_search','siyue_read']);
 assert.equal(spec.servers[0].url,'https://www.siyuejn.com/work');
});
