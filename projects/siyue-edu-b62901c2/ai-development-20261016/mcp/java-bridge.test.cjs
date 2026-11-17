'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const auth = require('../cli/auth-client.cjs');
const { createBridge } = require('./bridge.cjs');

test('Java v2 MCP previews task batches and commits only the reviewed plan', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-java-mcp-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') };
  const originalFetch = global.fetch;
  const calls = [];
  t.after(() => { global.fetch = originalFetch; fs.rmSync(root, { recursive: true, force: true }); });
  auth.writeSession({ server: 'https://java.example/crm-api/v2/', token: 'access-token', apiVersion: 'v2' }, env);
  global.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    const data = String(url).endsWith('/identity/me')
      ? { principalId: 'EMP_1', campusIds: ['CAMP_1'], permissions: ['crm.customer.read', 'crm.task.write'] }
      : String(url).endsWith('/crm/tasks/batches/preview')
        ? { selectedCount: 1, eligibleCount: 1, skippedCount: 0, assignmentCounts: { EMP_1: 1 },
          customerAssignments: { CUS_1: 'EMP_1' }, eligibleCustomerIds: ['CUS_1'], skippedCustomerIds: [] }
        : String(url).endsWith('/crm/tasks/batches/commit-preview') ? { batch: { id: 'TB_1' } }
          : { items: [{ id: 'CUS_1', campusId: 'CAMP_1' }], page: 1, size: 50, total: 1 };
    return { ok: true, status: 200, json: async () => ({ code: 'OK', data }) };
  };
  const bridge = createBridge({ env });
  const available = await bridge.tools();
  assert.deepEqual(available.map(tool => tool.name), ['siyue_status', 'siyue_queries', 'siyue_query',
    'siyue_preview_task_batch', 'siyue_execute_task_batch']);
  assert(available.slice(0, 3).every(tool => tool.annotations.readOnlyHint === true));
  const status = await bridge.call('siyue_status');
  assert.equal(status.structuredContent.identity.principalId, 'EMP_1');
  const queried = await bridge.call('siyue_query', { query: 'customers', input: { page: 1, size: 50 } });
  assert.equal(queried.structuredContent.items[0].id, 'CUS_1');
  const draft = { title: '初筛任务', taskType: 'marketing', customerIds: ['CUS_1'], subject: '数学', term: '2026秋',
    ownerIds: ['EMP_1'], strategy: 'even', dueAt: new Date(Date.now() + 3600_000).toISOString() };
  const preview = await bridge.call('siyue_preview_task_batch', { input: draft });
  assert.equal(preview.structuredContent.preview.customerAssignments.CUS_1, 'EMP_1');
  const planId = preview.structuredContent.planId;
  assert(!calls.some(call => call.url.endsWith('/crm/tasks/batches/commit-preview')));
  const committed = await bridge.call('siyue_execute_task_batch', { planId });
  assert.equal(committed.structuredContent.batch.id, 'TB_1');
  await bridge.call('siyue_execute_task_batch', { planId });
  const writes = calls.filter(call => call.url.endsWith('/crm/tasks/batches/commit-preview'));
  assert.equal(writes.length, 2);
  assert.equal(writes[0].options.headers['Idempotency-Key'], writes[1].options.headers['Idempotency-Key']);
  assert(calls.filter(call => call.url.includes('/crm/')).every(call => call.options.method === 'GET'
    || call.url.endsWith('/crm/tasks/batches/preview') || call.url.endsWith('/crm/tasks/batches/commit-preview')));
  assert(calls.some(call => call.url === 'https://java.example/crm-api/v2/crm/customers?page=1&size=50'));
  assert(calls.every(call => call.options.headers.Authorization === 'Bearer access-token'));
});

test('Java v2 MCP accepts per-student targets and submits the exact reviewed demand snapshot', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-java-target-mcp-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') };
  const originalFetch = global.fetch, calls = [];
  t.after(() => { global.fetch = originalFetch; fs.rmSync(root, { recursive: true, force: true }); });
  auth.writeSession({ server: 'https://java.example/crm-api/v2/', token: 'access-token', apiVersion: 'v2' }, env);
  const input = { title: '同家庭两位孩子初筛', taskType: 'marketing', targets: [
    { customerId: 'CUS_1', studentId: 'STU_1' }, { customerId: 'CUS_1', studentId: 'STU_2' },
  ], subject: '数学', term: '2026秋', ownerIds: ['EMP_1'], strategy: 'owner_first',
  dueAt: new Date(Date.now() + 3600_000).toISOString() };
  const reviewed = { selectedCount: 2, eligibleCount: 2, skippedCount: 0,
    assignmentCounts: { EMP_1: 2 }, customerAssignments: {}, eligibleCustomerIds: [], skippedCustomerIds: [],
    eligibleTargets: input.targets.map((target, index) => ({ ...target, targetKey: String(index + 1).repeat(64),
      subject: '数学', term: '2026秋', ownerId: 'EMP_1' })) };
  global.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    const data = String(url).endsWith('/identity/me') ? { principalId: 'EMP_1', permissions: ['crm.task.write'] }
      : String(url).endsWith('/preview') ? reviewed : { batch: { id: 'TB_siblings', createdCount: 2 } };
    return { ok: true, status: 200, json: async () => ({ code: 'OK', data }) };
  };
  const bridge = createBridge({ env });
  const schema = (await bridge.tools()).find(tool => tool.name === 'siyue_preview_task_batch').inputSchema.properties.input;
  assert.deepEqual(schema.properties.targets.items.required, ['customerId']);
  assert.equal(schema.properties.targets.items.additionalProperties, false);
  assert(!schema.required.includes('customerIds'));
  assert.deepEqual(schema.oneOf.map(option => option.required), [['customerIds'], ['targets']]);
  const preview = await bridge.call('siyue_preview_task_batch', { input });
  assert.deepEqual(preview.structuredContent.preview, reviewed);
  await bridge.call('siyue_execute_task_batch', { planId: preview.structuredContent.planId });
  const sent = calls.find(call => call.url.endsWith('/commit-preview'));
  assert.deepEqual(JSON.parse(sent.options.body), { input, expectedPreview: reviewed });
});

test('MCP activity audience exposes source filters and preserves mixed evidence through preview and retries', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-java-source-mcp-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') }, calls = [];
  const originalFetch = global.fetch;
  t.after(() => { global.fetch = originalFetch; fs.rmSync(root, { recursive: true, force: true }); });
  auth.writeSession({ server: 'https://java.example/crm-api/v2/', token: 'access-token', apiVersion: 'v2' }, env);
  const input = { title: '活动需求初筛', taskType: 'marketing', activityId: 'ACT_1', targets: [
    { customerId: 'CUS_1', studentId: 'STU_1', subject: '数学', term: '2030春', sources: [
      { type: 'touchpoint', id: 'TP_1' }, { type: 'lead', id: 'LEAD_1', version: 3 },
    ] },
  ], ownerIds: ['EMP_1'], strategy: 'even', dueAt: new Date(Date.now() + 3600_000).toISOString() };
  const reviewed = { selectedCount: 1, eligibleCount: 1, skippedCount: 0, assignmentCounts: { EMP_1: 1 },
    eligibleTargets: [{ ...input.targets[0], targetKey: 'a'.repeat(64), ownerId: 'EMP_1', sources: [
      { type: 'lead', id: 'LEAD_1', version: 3 }, { type: 'touchpoint', id: 'TP_1', version: null },
    ] }], skippedTargets: [] };
  global.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    const data = String(url).endsWith('/identity/me') ? { principalId: 'EMP_1', permissions: ['crm.task.write'] }
      : String(url).endsWith('/preview') ? reviewed
        : String(url).endsWith('/commit-preview') ? { batch: { id: 'TB_sources' } } : { items: [], unit: 'source' };
    return { ok: true, status: 200, json: async () => ({ code: 'OK', data }) };
  };
  const bridge = createBridge({ env });
  const querySchema = (await bridge.call('siyue_queries')).structuredContent.queries
    .find(query => query.id === 'activityAudience').inputSchema;
  assert.deepEqual(querySchema.properties.sourceType.enum, ['lead', 'touchpoint']);
  const schema = (await bridge.tools()).find(tool => tool.name === 'siyue_preview_task_batch')
    .inputSchema.properties.input.properties.targets.items.properties.sources.items;
  assert.deepEqual(schema.required, ['type', 'id']);
  assert.deepEqual(schema.oneOf[0].required, ['version']);
  assert.equal(schema.oneOf[0].properties.type.const, 'lead');
  assert.equal(schema.oneOf[1].properties.version.type, 'null');
  await bridge.call('siyue_query', { query: 'activityAudience', input: { activityId: 'ACT_1', sourceType: 'lead', page: 2, size: 10 } });
  assert(calls.some(call => call.url.endsWith('/crm/activities/ACT_1/audience?sourceType=lead&page=2&size=10')));
  const plan = await bridge.call('siyue_preview_task_batch', { input });
  const planId = plan.structuredContent.planId;
  await bridge.call('siyue_execute_task_batch', { planId });
  await bridge.call('siyue_execute_task_batch', { planId });
  const writes = calls.filter(call => call.url.endsWith('/commit-preview'));
  assert.equal(writes.length, 2);
  assert.deepEqual(JSON.parse(writes[0].options.body), { input, expectedPreview: reviewed });
  assert.equal(writes[0].options.body, writes[1].options.body);
  assert.equal(writes[0].options.headers['Idempotency-Key'], writes[1].options.headers['Idempotency-Key']);
  const before = calls.length;
  await assert.rejects(bridge.call('siyue_query', { query: 'taskContext', input: { taskId: '../TASK_1' } }), { code: 'INVALID_QUERY_INPUT' });
  assert(calls.slice(before).every(call => call.url.endsWith('/identity/me')));
  await assert.rejects(bridge.call('siyue_preview_task_batch', { input: { ...input, targets: [
    { ...input.targets[0], sources: [{ type: 'lead', id: 'LEAD_1' }] },
  ] } }), { code: 'INVALID_TASK_BATCH' });
});

test('MCP historical activity plans replay their original body and key without invented source evidence', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-java-legacy-source-mcp-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') }, calls = [];
  const originalFetch = global.fetch;
  t.after(() => { global.fetch = originalFetch; fs.rmSync(root, { recursive: true, force: true }); });
  const server = 'https://java.example/crm-api/v2/';
  auth.writeSession({ server, token: 'access-token', apiVersion: 'v2' }, env);
  const input = { title: '历史活动初筛', taskType: 'marketing', activityId: 'ACT_OLD', customerIds: ['CUS_1'],
    subject: '数学', term: '2020春', ownerIds: ['EMP_1'], strategy: 'even', dueAt: '2020-01-01T00:00:00Z' };
  const preview = { selectedCount: 1, eligibleCount: 1, skippedCount: 0, assignmentCounts: { EMP_1: 1 },
    customerAssignments: { CUS_1: 'EMP_1' }, eligibleCustomerIds: ['CUS_1'], skippedCustomerIds: [] };
  const plan = { format: 'siyue-java-plan-v1', apiVersion: 'v2', operationId: 'crm.task-batch.create', server,
    principalId: 'EMP_1', input, preview, idempotencyKey: 'original-history-plan-key' };
  const planId = require('node:crypto').randomUUID();
  const directory = auth.configDirectory({ SIYUE_CONFIG_DIR: path.join(env.SIYUE_CONFIG_DIR, 'mcp-java-plans') });
  const filename = path.join(directory, `${planId}.json`);
  require('../cli/business-client.cjs').write(filename, plan);
  const originalFile = fs.readFileSync(filename, 'utf8');
  global.fetch = async (url, options) => {
    calls.push({ url: String(url), options });
    const data = String(url).endsWith('/identity/me') ? { principalId: 'EMP_1', permissions: ['crm.task.write'] }
      : { batch: { id: 'TB_ALREADY_COMMITTED' } };
    return { ok: true, status: 200, json: async () => ({ code: 'OK', data }) };
  };
  const bridge = createBridge({ env });
  for (let attempt = 0; attempt < 2; attempt++)
    assert.equal((await bridge.call('siyue_execute_task_batch', { planId })).structuredContent.batch.id, 'TB_ALREADY_COMMITTED');
  const writes = calls.filter(call => call.url.endsWith('/commit-preview'));
  assert.equal(writes.length, 2);
  for (const write of writes) {
    assert.deepEqual(JSON.parse(write.options.body), { input, expectedPreview: preview });
    assert.equal(write.options.headers['Idempotency-Key'], plan.idempotencyKey);
    assert(!write.options.body.includes('sources'));
    assert(!write.options.body.includes('targets'));
  }
  assert.equal(fs.readFileSync(filename, 'utf8'), originalFile);
  assert(!calls.some(call => call.url.endsWith('/preview')));
});

test('MCP needs read a server preview, require explicit decision and retry only a private exact plan', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-needs-mcp-')));
  const env = {SIYUE_CONFIG_DIR:path.join(root,'config')}, calls = [], previousFetch=global.fetch;
  t.after(()=>{global.fetch=previousFetch;fs.rmSync(root,{recursive:true,force:true});});
  auth.writeSession({server:'https://java.example/crm-api/v2/',token:'synthetic-token',apiVersion:'v2'},env);
  const preview={catalogVersion:2,inputHash:'a'.repeat(64),customerVersion:1,scopeCampusId:'CAMP_1',
    candidates:[],allowedActions:['create'],differences:[],selectedOwnerId:'EMP_1'};
  global.fetch=async(url,options)=>{calls.push({url:String(url),options});const data=String(url).endsWith('/identity/me')
    ?{principalId:'EMP_1',permissions:['crm.opportunity.intake','crm.opportunity.write','crm.customer.read']}
    :String(url).endsWith('/preview')?preview:{outcome:'created',opportunityId:'OPP_1',consultationId:'CONS_1',
      identityStatus:'classified',ownerChanged:false,notificationState:'not_required',opportunity:null};
    return{ok:true,status:200,json:async()=>({code:'OK',data})};};
  const bridge=createBridge({env});const tools=await bridge.tools();
  assert(tools.some(row=>row.name==='siyue_preview_need_operation'));
  const input={customerId:'CUS_1',title:'合成需求',subject:'数学',term:'2026秋',identity:{departmentId:'DEP_1',projectId:null},profile:{},detail:'合成咨询'};
  const initial=await bridge.call('siyue_preview_need_operation',{operation:'need-intake',input});
  assert.equal(initial.structuredContent.planId,null);
  const planned=await bridge.call('siyue_preview_need_operation',{operation:'need-intake',input,decision:{action:'create'}});
  const planId=planned.structuredContent.planId;
  assert.equal(fs.statSync(path.join(env.SIYUE_CONFIG_DIR,'mcp-java-need-plans',planId+'.json')).mode&0o777,0o600);
  await bridge.call('siyue_execute_need_operation',{planId});await bridge.call('siyue_execute_need_operation',{planId});
  const writes=calls.filter(row=>row.url.endsWith('/commit-preview'));
  assert.equal(writes.length,2);assert.equal(writes[0].options.body,writes[1].options.body);
  assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
  assert.deepEqual(JSON.parse(writes[0].options.body),{input,expectedPreview:preview,decision:{action:'create'}});
  await assert.rejects(bridge.call('siyue_execute_need_operation',{planId:'../bad'}),{code:'INVALID_PLAN'});
});

test('MCP identity tools retain private exact plans, role-bound maintenance and typed query contracts', async t => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-java-identity-mcp-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') }, calls = [], originalFetch = global.fetch;
  t.after(() => { global.fetch = originalFetch; fs.rmSync(root, { recursive: true, force: true }); });
  auth.writeSession({ server:'https://java.example/crm-api/v2/',token:'access-token',apiVersion:'v2' },env);
  let identity={principalId:'EMP_1',scope:'all',permissions:['crm.customer.read','crm.customer.identity.write','crm.customer.search.manage']};
  const item={id:'SID_1',studentId:'STU_1',namespace:'旧系统',value:'000Aa1',active:true,version:1,createdAt:'2026-10-14T08:00:00Z',deactivatedAt:null};
  global.fetch=async(url,options)=>{
    calls.push({url:String(url),options});
    const data=String(url).endsWith('/identity/me')?identity:options.method==='POST'?item:
      {items:[],total:0,page:1,size:100,studentVersion:3};
    return {ok:true,status:200,json:async()=>({code:'OK',data})};
  };
  const bridge=createBridge({env});
  const tool=(await bridge.tools()).find(tool=>tool.name==='siyue_preview_identity_operation');
  assert.equal(tool.inputSchema.oneOf.length,4);
  const prepared=await bridge.call('siyue_preview_identity_operation',{operation:'student-identifier-create',customerId:'CUS_1',studentId:'STU_1',
    input:{namespace:'旧系统',value:'000Aa1',expectedStudentVersion:3}});
  const planId=prepared.structuredContent.planId, file=path.join(env.SIYUE_CONFIG_DIR,'mcp-java-identity-plans',planId+'.json');
  assert.equal(fs.statSync(file).mode&0o777,0o600);assert.equal(prepared.structuredContent.preview.serverPreview,false);
  await bridge.call('siyue_execute_identity_operation',{planId});await bridge.call('siyue_execute_identity_operation',{planId});
  const writes=calls.filter(call=>call.options.method==='POST');assert.equal(writes.length,2);
  assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
  assert.equal(JSON.parse(writes[0].options.body).value,'000Aa1');
  identity={...identity,scope:'campus'};
  const scoped=(await bridge.tools()).find(tool=>tool.name==='siyue_preview_identity_operation');assert.equal(scoped.inputSchema.oneOf.length,2);
  await assert.rejects(bridge.call('siyue_preview_identity_operation',{operation:'student-name-index-rebuild',input:{afterId:0,limit:100}}),{code:'FORBIDDEN'});
  identity={...identity,permissions:['crm.customer.read']};
  assert(!(await bridge.tools()).some(tool=>tool.name==='siyue_preview_identity_operation'));
  await assert.rejects(bridge.call('siyue_execute_identity_operation',{planId}),{code:'FORBIDDEN'});
  const query=(await bridge.call('siyue_queries')).structuredContent.queries.find(query=>query.id==='customers');
  assert(query.inputSchema.properties.searchType.enum.includes('student_number'));
  await bridge.call('siyue_query',{query:'customers',input:{searchType:'student_number',query:'000Aa1',page:1,size:20}});
  assert(calls.at(-1).url.endsWith('/crm/customers?searchType=student_number&query=000Aa1&page=1&size=20'));
});
