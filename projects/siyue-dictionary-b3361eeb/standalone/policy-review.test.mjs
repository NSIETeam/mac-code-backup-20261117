import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createPolicyReviewStore} from './policy-review.mjs';
test('policy review: fixed identity, version conflicts, source changes, immutable history and revocation',()=>{
 const db=new DatabaseSync(':memory:');db.exec("PRAGMA foreign_keys=ON;CREATE TABLE employees(id TEXT PRIMARY KEY);INSERT INTO employees VALUES('reviewer'),('other');");
 const catalog={policies:[{id:'policy-one',title:'制度甲',documentId:'doc',sections:[{title:'范围',paragraphs:['正文一'],refs:['source']}]}],policyDocuments:[{id:'doc',versionLabel:'版本一'}],policySources:[{id:'source',title:'原件'}]};
 let active=true;const store=createPolicyReviewStore(db,{getCatalog:()=>catalog,requireActive:()=>{if(!active)throw Object.assign(new Error('disabled'),{status:403});}});
 const reviewer={id:'reviewer',name:'审核员'};
 try{
  assert.throws(()=>store.list('other'),e=>e.status===403);db.prepare('INSERT INTO policy_reviewers VALUES(?,?)').run('reviewer','now');
  let d=store.detail('reviewer','policy-one');assert.equal(d.state,'pending');
  const input={status:'changes_requested',comment:'',version:0,contentHash:d.contentHash};assert.throws(()=>store.save(reviewer,'policy-one',input),e=>e.status===400);
  input.comment='请核对';d=store.save(reviewer,'policy-one',input);assert.equal(d.review.version,1);
  assert.throws(()=>store.save(reviewer,'policy-one',input),e=>e.status===409);
  catalog.policyDocuments[0].versionLabel='版本二';assert.equal(store.list('reviewer').items[0].state,'stale');
  assert.throws(()=>store.save(reviewer,'policy-one',{...input,version:1}),e=>e.status===409);
  d=store.detail('reviewer','policy-one');store.save(reviewer,'policy-one',{status:'approved',comment:'',version:1,contentHash:d.contentHash});
  assert.equal(store.detail('reviewer','policy-one').history.length,2);
  const original=JSON.parse(db.prepare('SELECT snapshot_json FROM policy_review_history WHERE version=1').get().snapshot_json);assert.equal(original.document.versionLabel,'版本一');
  catalog.policySources[0].title='资料已更正';assert.equal(store.list('reviewer').items[0].state,'stale');
  active=false;assert.throws(()=>store.detail('reviewer','policy-one'),e=>e.status===403);active=true;
  db.prepare('DELETE FROM policy_reviewers').run();assert.throws(()=>store.save(reviewer,'policy-one',input),e=>e.status===403);
  assert.equal(db.prepare('SELECT count(*) n FROM policy_review_history').get().n,2);
 }finally{db.close();}
});
