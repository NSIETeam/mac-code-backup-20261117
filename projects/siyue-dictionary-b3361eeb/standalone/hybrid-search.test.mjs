import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {randomUUID} from 'node:crypto';
import {mkdtemp, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {searchCatalog} from './catalog.mjs';
import {createKnowledgeStore} from './knowledge.mjs';
import {prepareSemanticQuery, prepareSemanticEntry, semanticMatch, mergeSearchResults, SEMANTIC_TEXT_LIMIT} from './domain-search.mjs';

const cases = [
  ['我昨天忘记打卡怎么办','policy:policy-leave-correction'],
  ['漏签需要什么材料','policy:policy-leave-correction'],
  ['有事不能来上班找谁负责','policy:policy-leave-correction'],
  ['家长不想继续上了怎么办','policy:policy-refund-processing'],
  ['退剩下的学费如何办理','policy:policy-refund-processing'],
  ['学生想换上课时间怎么办','policy:policy-hours-exceptions'],
  ['每天的工作汇报怎么写','policy:policy-work-reports'],
  ['本周工作总结有哪些要求','policy:policy-work-reports'],
  ['报完名接下来怎么办','policy:policy-enrollment-handoff'],
  ['第一次课后联系家长有什么要求','policy:policy-enrollment-handoff'],
  ['下班关门有什么要求','policy:policy-campus-environment'],
  ['教室清洁有哪些要求','policy:policy-campus-environment'],
  ['老师准备教案有什么要求','policy:policy-teaching-preparation'],
];
test('business questions retrieve supporting articles in the top three, with original excerpts',()=>{
  for (const [query, key] of cases) {
    assert.ok(searchCatalog(query).slice(0,3).some(item=>item.key===key),query);
    assert.ok(searchCatalog(query).some(item=>item.matchKind==='semantic'),query);
    for (const result of searchCatalog(query)) assert.ok(result.excerpt.length<=147);
  }
});
test('literal ranking, unknown qualifiers, negation and feature-off preserve precise search',()=>{
  for (const q of ['教务','校长','AI部 净释放工时','24:00','补卡']) {
    const exact=searchCatalog(q,false), hybrid=searchCatalog(q);
    assert.deepEqual(hybrid.filter(item=>!item.matchKind),exact,q);
  }
  for (const q of ['退费 火星殖民条例','日报 不存在条款随机标记zz','不是退费而是调课','忘打卡 2026-12-01','报销 ZX829','天文学','[.*+?']) assert.equal(prepareSemanticQuery(q),null,q);
  assert.deepEqual(searchCatalog('忘记打卡怎么办',false),[]);
  assert.equal(prepareSemanticQuery('补卡'.repeat(101)),null);
  const merged=mergeSearchResults(searchCatalog('补卡'),[{key:'document:exact',title:'补卡'}]);
  assert.ok(merged.findIndex(item=>item.key==='document:exact')<merged.findIndex(item=>item.matchKind==='semantic') || !merged.some(item=>item.matchKind==='semantic'));
  assert.ok(merged.every(item=>!('semanticScore' in item)));
  assert.ok(merged.filter(item=>item.matchKind==='semantic').length<=8);
});
test('extra semantic work is bounded and snippets come from matching original text',()=>{
  const long='甲'.repeat(SEMANTIC_TEXT_LIMIT)+'退费';
  const entry=prepareSemanticEntry('普通资料',[],[{text:long}]);
  assert.equal(semanticMatch(entry,prepareSemanticQuery('家长不想继续上了怎么办')),null);
  const matched=semanticMatch(prepareSemanticEntry('材料清单',[],[{text:'前言'.repeat(400)+'补卡请提供考勤材料。'}]),prepareSemanticQuery('忘打卡需要什么材料'));
  assert.match(matched.excerpt,/补卡/);
});
test('semantic knowledge search checks current owner, membership, sharing and suspended state every time',async()=>{
  const root=await mkdtemp(join(tmpdir(),'siyue-semantic-acl-'));const db=new DatabaseSync(':memory:');
  try {
    db.exec('PRAGMA foreign_keys=ON; CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL); CREATE TABLE employees(id TEXT PRIMARY KEY,name TEXT,role_ids TEXT,department TEXT,campus TEXT,grade TEXT)');
    const [admin,owner,reader,pending]=Array.from({length:4},()=>randomUUID());
    for (const id of [admin,owner,reader,pending]) db.prepare('INSERT INTO employees VALUES(?,?,?,?,?,?)').run(id,'测试员工','["academic"]','教务','集团总部','');
    await mkdir(join(root,'public'));
    const store=createKnowledgeStore(db,{directory:join(root,'private'),staticDirectory:join(root,'public'),administratorIds:new Set([admin])});
    const unit=store.context(admin).units.find(item=>item.kind==='department'&&item.name==='教务').id;
    const approve=(id,status,unitIds=[])=>store.updateEmployee(admin,id,{status,unitIds,version:store.access(id).version});
    approve(owner,'active',[unit]);approve(reader,'active',[unit]);
    const privateSharing={mode:'private',userIds:[],unitIds:[],match:'any'};
    const input={requestId:randomUUID(),title:'补卡私有材料',body:'补卡原始材料与内部审核记录。',attachmentIds:[],sharing:privateSharing};
    let doc=store.saveDocument(owner,input).document;
    const query='忘记打卡需要什么材料';
    assert.equal(store.search(owner,query)[0]?.id,doc.id);
    for (const id of [admin,reader,pending]) assert.deepEqual(store.search(id,query),[]);
    doc=store.saveDocument(owner,{...input,version:doc.version,sharing:{...privateSharing,mode:'selected',unitIds:[unit]}},doc.id).document;
    assert.equal(store.search(reader,query)[0]?.id,doc.id);
    assert.deepEqual(store.search(admin,query),[]);
    approve(reader,'active',[]);assert.deepEqual(store.search(reader,query),[]);
    approve(reader,'active',[unit]);assert.equal(store.search(reader,query)[0]?.id,doc.id);
    doc=store.saveDocument(owner,{...input,version:doc.version,sharing:{...privateSharing,mode:'employees'}},doc.id).document;
    assert.equal(store.search(reader,query)[0]?.id,doc.id);
    assert.deepEqual(store.search(pending,query),[]);
    approve(reader,'suspended');assert.throws(()=>store.search(reader,query),error=>error.status===403);
    doc=store.saveDocument(owner,{...input,version:doc.version},doc.id).document;
    approve(reader,'active',[unit]);assert.deepEqual(store.search(reader,query),[]);
    assert.deepEqual(store.search(owner,query,false),[]);
    doc=store.saveDocument(owner,{...input,version:doc.version,title:'普通资料',body:'与考勤无关的资料'},doc.id).document;
    assert.deepEqual(store.search(owner,query),[]);
    assert.equal(store.search(owner,'普通资料')[0]?.id,doc.id);
  } finally {db.close();await rm(root,{recursive:true,force:true});}
});
