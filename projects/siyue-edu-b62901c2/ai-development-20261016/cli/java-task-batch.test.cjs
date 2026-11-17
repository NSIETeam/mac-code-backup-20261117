'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const taskBatch = require('./java-task-batch.cjs');

function draft() {
  return { title: '秋季数学初筛', taskType: 'marketing', customerIds: ['CUS_1'], subject: '数学', term: '2026秋',
    ownerIds: ['EMP_1'], strategy: 'even', dueAt: new Date(Date.now() + 3_600_000).toISOString() };
}

function preview() {
  return { selectedCount: 1, eligibleCount: 1, skippedCount: 0, assignmentCounts: { EMP_1: 1 },
    customerAssignments: { CUS_1: 'EMP_1' }, eligibleCustomerIds: ['CUS_1'], skippedCustomerIds: [] };
}

function targetPreview(input) {
  const rows = taskBatch.normalizedTargets(input).map((target, index) => ({ ...target,
    targetKey: crypto.createHash('sha256').update(JSON.stringify(target)).digest('hex'),
    ownerId: input.ownerIds[index % input.ownerIds.length] }));
  const counts = Object.fromEntries(input.ownerIds.map(id => [id, 0]));
  rows.forEach(row => counts[row.ownerId]++);
  return { selectedCount: rows.length, eligibleCount: rows.length, skippedCount: 0,
    assignmentCounts: counts, customerAssignments: {}, eligibleCustomerIds: [], skippedCustomerIds: [],
    eligibleTargets: rows, skippedTargets: [] };
}

test('Java task batch plans validate the reviewed per-customer assignment and idempotency key', () => {
  const client = { apiVersion: 'v2', server: 'https://java.example/crm-api/v2/' };
  assert.equal(taskBatch.buildPlan({ input: draft(), preview: preview(), client, me: { principalId: 'EMP_1' } })
    .preview.customerAssignments.CUS_1, 'EMP_1');
  assert.throws(() => taskBatch.buildPlan({ input: draft(), preview: { ...preview(), eligibleCount: 2 },
    client, me: { principalId: 'EMP_1' } }), { code: 'INVALID_PREVIEW' });
  assert.throws(() => taskBatch.buildPlan({ input: draft(), preview: preview(), client,
    me: { principalId: 'EMP_1' }, key: 'short' }), { code: 'INVALID_IDEMPOTENCY_KEY' });
  assert.throws(() => taskBatch.validateInput({ ...draft(), dueAt: '2026-02-30T12:00:00Z' }),
    { code: 'INVALID_TASK_BATCH' });
});

test('Java task batch execution binds server, principal, exact preview and stable idempotency key', async () => {
  const calls = [];
  const client = { apiVersion: 'v2', server: 'https://java.example/crm-api/v2/', async request(path, options) {
    calls.push({ path, options }); return { code: 'OK', data: { batch: { id: 'TB_1' } } };
  } };
  const plan = taskBatch.buildPlan({ input: draft(), preview: preview(), client, me: { principalId: 'EMP_1' },
    key: 'task-batch-idempotency-key-1' });
  const result = await taskBatch.executePlan(plan, { client, me: { principalId: 'EMP_1' } });
  assert.deepEqual(result, { batch: { id: 'TB_1' } });
  assert.equal(calls[0].path, 'crm/tasks/batches/commit-preview');
  assert.equal(calls[0].options.idempotencyKey, plan.idempotencyKey);
  assert.deepEqual(calls[0].options.body, { input: plan.input, expectedPreview: plan.preview });
  await assert.rejects(taskBatch.executePlan(plan, { client, me: { principalId: 'EMP_2' } }), { code: 'WRONG_ACTOR' });
  await assert.rejects(taskBatch.executePlan({ ...plan, server: 'https://other.example/' },
    { client, me: { principalId: 'EMP_1' } }), { code: 'INVALID_PLAN' });
});

test('student targets preserve siblings and multiple demands within one household', () => {
  const input = { ...draft(), customerIds: undefined, ownerIds: ['EMP_1', 'EMP_2'], targets: [
    { customerId: 'CUS_1', studentId: 'STU_1' },
    { customerId: 'CUS_1', studentId: 'STU_2' },
    { customerId: 'CUS_1', studentId: 'STU_1', subject: '英语', term: '2027春' },
    { customerId: 'CUS_1', studentId: null },
  ] };
  assert.equal(taskBatch.validateInput(input), input);
  const rows = taskBatch.normalizedTargets(input);
  assert.deepEqual(rows.map(row => row.studentId), ['STU_1', 'STU_2', 'STU_1', null]);
  assert.deepEqual(rows.map(row => row.subject), ['数学', '数学', '英语', '数学']);
  assert.equal(rows[2].term, '2027春');
  const reviewed = targetPreview(input);
  assert.equal(taskBatch.validatePreview(input, reviewed), reviewed);
  assert.deepEqual(reviewed.assignmentCounts, { EMP_1: 2, EMP_2: 2 });
  assert.throws(() => taskBatch.validateInput({ ...input, targets: [...input.targets, input.targets[0]] }),
    { code: 'INVALID_TASK_BATCH' });
  assert.throws(() => taskBatch.validateInput({ ...input, targets: [
    { customerId: 'CUS_1', studentId: 'STU_1', subject: 'Math', term: 'Autumn' },
    { customerId: 'CUS_1', studentId: 'STU_1', subject: 'math', term: 'AUTUMN' },
  ] }), { code: 'INVALID_TASK_BATCH' });
  assert.throws(() => taskBatch.validateInput({ ...input, customerIds: ['CUS_1'] }),
    { code: 'INVALID_TASK_BATCH' });
  assert.throws(() => taskBatch.validateInput({ ...input, targets: undefined }), { code: 'INVALID_TASK_BATCH' });
});

test('student target preview checks each identity, skip reason and exact assignee totals', () => {
  const input = { ...draft(), customerIds: undefined, ownerIds: ['EMP_1', 'EMP_2'], targets: [
    { customerId: 'CUS_1', studentId: 'STU_1' }, { customerId: 'CUS_1', studentId: 'STU_2' },
  ] };
  const reviewed = targetPreview(input);
  for (const bad of [
    { ...reviewed, eligibleTargets: [reviewed.eligibleTargets[0], { ...reviewed.eligibleTargets[1], studentId: 'STU_3' }] },
    { ...reviewed, eligibleTargets: [reviewed.eligibleTargets[0], { ...reviewed.eligibleTargets[1], subject: 42 }] },
    { ...reviewed, eligibleTargets: [reviewed.eligibleTargets[0], reviewed.eligibleTargets[0]] },
    { ...reviewed, assignmentCounts: { EMP_1: 2, EMP_2: 0 } },
    { ...reviewed, eligibleTargets: [reviewed.eligibleTargets[0], { ...reviewed.eligibleTargets[1], ownerId: 'EMP_3' }] },
  ]) assert.throws(() => taskBatch.validatePreview(input, bad), { code: 'INVALID_PREVIEW' });
  const { ownerId, ...skipped } = reviewed.eligibleTargets[1];
  const partial = { ...reviewed, eligibleCount: 1, skippedCount: 1, assignmentCounts: { EMP_1: 1, EMP_2: 0 },
    eligibleTargets: reviewed.eligibleTargets.slice(0, 1), skippedTargets: [{ ...skipped, reason: 'open_marketing_task' }] };
  assert.equal(taskBatch.validatePreview(input, partial), partial);
  assert.throws(() => taskBatch.validatePreview(input, { ...partial, skippedTargets: [{ ...skipped, reason: 'unknown' }] }),
    { code: 'INVALID_PREVIEW' });
});

test('executing an existing plan after its deadline reaches server replay with the original key and snapshot', async () => {
  const calls = [];
  const client = { apiVersion: 'v2', server: 'https://java.example/crm-api/v2/', async request(path, options) {
    calls.push({ path, options }); return { code: 'OK', data: { batch: { id: 'TB_existing' } } };
  } };
  const input = { ...draft(), dueAt: '2020-01-01T00:00:00Z' };
  const plan = { format: 'siyue-java-plan-v1', apiVersion: 'v2', operationId: 'crm.task-batch.create',
    server: client.server, principalId: 'EMP_1', input, preview: preview(), idempotencyKey: 'stable-old-batch-key-123' };
  assert.throws(() => taskBatch.buildPlan({ input, preview: preview(), client, me: { principalId: 'EMP_1' } }),
    { code: 'INVALID_TASK_BATCH' });
  assert.equal((await taskBatch.executePlan(plan, { client, me: { principalId: 'EMP_1' } })).batch.id, 'TB_existing');
  assert.equal(calls[0].options.idempotencyKey, plan.idempotencyKey);
  assert.deepEqual(calls[0].options.body, { input, expectedPreview: plan.preview });
});


test('activity source evidence survives target normalization and reviewed plan execution', async () => {
  const input = {...draft(),customerIds:undefined,activityId:'ACT_1',targets:[{customerId:'CUS_1',studentId:'STU_1',
    sources:[{type:'touchpoint',id:'TP_1'},{type:'lead',id:'LEAD_1',version:3}]}]};
  assert.equal(taskBatch.validateInput(input), input);
  const reviewed = targetPreview(input);
  assert.deepEqual(reviewed.eligibleTargets[0].sources, [{type:'lead',id:'LEAD_1',version:3},{type:'touchpoint',id:'TP_1',version:null}]);
  assert.equal(taskBatch.validatePreview(input, reviewed), reviewed);
  for (const sources of [[],[{type:'lead',id:'LEAD_1',version:4}], [{type:'lead',id:'LEAD_WRONG',version:3}]]) {
    assert.throws(() => taskBatch.validatePreview(input, {...reviewed,eligibleTargets:[{...reviewed.eligibleTargets[0],sources}]}), {code:'INVALID_PREVIEW'});
  }
  const calls=[], client={apiVersion:'v2',server:'https://java.example/crm-api/v2/',request:async(path,options)=>{
    calls.push({path,options});return {code:'OK',data:{batch:{id:'TB_1'}}};
  }};
  const plan=taskBatch.buildPlan({input,preview:reviewed,client,me:{principalId:'EMP_1'},key:'source-plan-idempotency-1'});
  await taskBatch.executePlan(plan,{client,me:{principalId:'EMP_1'}});
  await taskBatch.executePlan(plan,{client,me:{principalId:'EMP_1'}});
  assert.deepEqual(calls[0].options.body.input.targets[0].sources,input.targets[0].sources);
  assert.deepEqual(calls[1].options,calls[0].options);
});

test('new activity plans reject missing or forged sources but old confirmed plan inputs remain replayable', () => {
  const base={...draft(),customerIds:undefined,activityId:'ACT_1',targets:[{customerId:'CUS_1',sources:[{type:'lead',id:'LEAD_1',version:1}]}]};
  for(const sources of [[],[{type:'lead',id:'LEAD_1'}],[{type:'lead',id:'LEAD_1',version:0}],
    [{type:'touchpoint',id:'TP_1',version:1}],[{type:'lead',id:'../LEAD',version:1}],
    [{type:'lead',id:'LEAD_1',version:1},{type:'lead',id:'LEAD_1',version:2}],
    [{type:'other',id:'X'}],[{type:'lead',id:'LEAD_1',version:1,ownerId:'spoof'}]]) {
    assert.throws(()=>taskBatch.validateInput({...base,targets:[{customerId:'CUS_1',sources}]}),{code:'INVALID_TASK_BATCH'});
  }
  assert.throws(()=>taskBatch.validateInput({...base,activityId:null}),{code:'INVALID_TASK_BATCH'});
  const old={...draft(),activityId:'ACT_1'};
  assert.throws(()=>taskBatch.validateInput(old),{code:'INVALID_TASK_BATCH'});
  assert.equal(taskBatch.validateInput(old,{requireFuture:false}),old);
});

test('legacy activity plan executes without adding targets or sources and keeps its original reviewed body', async () => {
  const calls = [], client = { apiVersion: 'v2', server: 'https://java.example/crm-api/v2/', async request(path, options) {
    calls.push({ path, options }); return { code: 'OK', data: { batch: { id: 'TB_OLD' } } };
  } };
  const input = { ...draft(), activityId: 'ACT_OLD', dueAt: '2020-01-01T00:00:00Z' };
  const plan = { format: 'siyue-java-plan-v1', apiVersion: 'v2', operationId: 'crm.task-batch.create',
    server: client.server, principalId: 'EMP_1', input, preview: preview(), idempotencyKey: 'old-activity-replay-key' };
  const before = JSON.stringify(plan);
  for (let attempt = 0; attempt < 2; attempt++) await taskBatch.executePlan(plan, { client, me: { principalId: 'EMP_1' } });
  assert.equal(JSON.stringify(plan), before);
  assert.deepEqual(calls[0].options, calls[1].options);
  assert.deepEqual(calls[0].options.body, { input, expectedPreview: plan.preview });
  assert(!JSON.stringify(calls[0].options.body).includes('sources'));
  assert.equal(calls[0].options.idempotencyKey, plan.idempotencyKey);
});

test('skipped activity demand must retain all reviewed source references as well as its identity', () => {
  const input = { ...draft(), customerIds: undefined, activityId: 'ACT_1', targets: [
    { customerId: 'CUS_1', studentId: 'STU_1', sources: [{ type: 'lead', id: 'LEAD_1', version: 2 }] },
  ] };
  const full = targetPreview(input), { ownerId, ...target } = full.eligibleTargets[0];
  const reviewed = { ...full, eligibleCount: 0, skippedCount: 1, assignmentCounts: { EMP_1: 0 }, eligibleTargets: [],
    skippedTargets: [{ ...target, reason: 'open_marketing_task' }] };
  assert.equal(taskBatch.validatePreview(input, reviewed), reviewed);
  for (const sources of [[], [{ type: 'lead', id: 'LEAD_1', version: 3 }]]) {
    assert.throws(() => taskBatch.validatePreview(input, { ...reviewed, skippedTargets: [
      { ...reviewed.skippedTargets[0], sources },
    ] }), { code: 'INVALID_PREVIEW' });
  }
});

test('task need identity distinguishes departments/projects while legacy ambiguous targets remain explicitly skipped', () => {
  const input={title:'需求任务',taskType:'marketing',targets:[
    {customerId:'CUS_1',studentId:'STU_1',subject:'数学',term:'2026秋',needIdentity:{departmentId:'DEP_1',projectId:'PRO_1'}},
    {customerId:'CUS_1',studentId:'STU_1',subject:'数学',term:'2026秋',needIdentity:{departmentId:'DEP_2',projectId:null}},
  ],ownerIds:['EMP_1'],strategy:'even',dueAt:'2099-01-01T00:00:00Z'};
  taskBatch.validateInput(input);
  const normalized=taskBatch.normalizedTargets(input);
  assert.deepEqual(normalized[1].needIdentity,{departmentId:'DEP_2',projectId:null});
  const preview={selectedCount:2,eligibleCount:1,skippedCount:1,assignmentCounts:{EMP_1:1},
    eligibleTargets:[{...normalized[0],targetKey:'a'.repeat(64),ownerId:'EMP_1'}],
    skippedTargets:[{...normalized[1],targetKey:'b'.repeat(64),reason:'identity_review_required'}]};
  taskBatch.validatePreview(input,preview);
  assert.throws(()=>taskBatch.validatePreview(input,{...preview,eligibleTargets:[{...preview.eligibleTargets[0],needIdentity:{departmentId:'DEP_OTHER',projectId:'PRO_1'}}]}),/预览/);
  assert.throws(()=>taskBatch.validateInput({...input,targets:[input.targets[0],input.targets[0]]}),/重复/);
  assert.throws(()=>taskBatch.validateInput({...input,targets:[{...input.targets[0],needIdentity:{departmentId:'../other'}}]}),/部门/);
  const legacy=taskBatch.normalizedTargets({...input,targets:[{customerId:'CUS_1',subject:'数学',term:'2026秋'}]});
  assert.equal(Object.hasOwn(legacy[0],'needIdentity'),false);
});
