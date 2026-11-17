'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const api = require('./java-task-routing.cjs');
const server = 'https://java.example/crm-api/v2/';
const me = { principalId: 'EMP_1', permissions: ['crm.task.read', 'crm.task.write', 'crm.task.assign', 'crm.task.manage'] };
const startsAt = '2000-01-01T08:00:00.123456789Z', dueAt = '2000-01-02T08:00:00.987654321Z';
const target = { customerId: 'CUS_1', studentId: 'STU_1', subject: '数学', term: '秋', needIdentity: { departmentId: 'DEP_1' }, sources: [{ type: 'lead', id: 'LEAD_1', version: 2 }] };
const request = { input: { title: '初筛', taskType: 'marketing', campusId: 'CAMP_1', activityId: 'ACT_1', startsAt, dueAt,
  delivery: { mode: 'supervisor', supervisorId: 'EMP_2' }, targets: [target] } };
const createPreview = { action: 'create', campusId: 'CAMP_1', deliveryMode: 'supervisor', supervisorId: 'EMP_2', startsAt, dueAt,
  selectedCount: 1, reservedCount: 1, skippedCount: 0, assignmentCounts: {}, targets: [{ targetKey: 'K1', target, ownerId: null, status: 'unassigned' }], skippedTargets: [] };
const counts = { totalCount: 1, unassignedCount: 1, pendingCount: 0, inProgressCount: 0, completedCount: 0, cancelledCount: 0, allocatedCount: 0 };
const batch = { id: 'TB_1', title: '初筛', campusId: 'CAMP_1', deliveryMode: 'supervisor', supervisorId: 'EMP_2', startsAt, dueAt,
  managementVersion: 1, version: 1, counts };
const receipt = { committed: true, batchId: 'TB_1', operationId: 'ROUTE_EVT_1', scopeStatus: 'visible', batch,
  tasks: [{ taskId: 'TASK_1', version: 1, ownerId: null, status: 'unassigned', dueAt }] };
const change = { batchId: 'TB_1', input: { expectedManagementVersion: 1, selection: [{ taskId: 'TASK_1', expectedVersion: 1 }],
  strategy: 'even', ownerIds: ['EMP_3'], sourceOwnerPolicy: 'source_lead_owner_first' } };
const assignment = { taskId: 'TASK_1', expectedVersion: 1, fromOwnerId: null, toOwnerId: 'EMP_9', fromStatus: 'unassigned', toStatus: 'pending', fromDueAt: dueAt, toDueAt: dueAt, basis: 'source_lead_owner' };
const changePreview = { action: 'allocation', batchId: 'TB_1', managementVersion: 1, selectedCount: 1, assignableCount: 1, blockedCount: 0,
  assignments: [assignment], blocked: [], assignmentCounts: { EMP_9: 1 }, remainingUnassignedCount: 0, fromSupervisorId: 'EMP_2', toSupervisorId: 'EMP_2', startsAt, dueAt };
function harness(preview = createPreview, response = receipt) {
  const calls = [];
  return { me, calls, client: { apiVersion: 'v2', server, request: async (endpoint, options) => {
    calls.push({ endpoint, options: structuredClone(options) });
    if (endpoint === 'identity/me') return { code: 'OK', data: me };
    return { code: 'OK', data: endpoint.endsWith('/preview') ? structuredClone(preview) : structuredClone(response) };
  } } };
}
test('server preview original input and nanosecond dates survive plan, successful retry and elapsed deadline', async () => {
  const h = harness(), input = structuredClone(request), { plan } = await api.prepare('task-routing-create', input, h);
  input.input.title = 'modified'; createPreview.targets[0].target.needIdentity.projectId = null;
  assert.equal(plan.request.input.title, '初筛'); assert.equal(plan.expectedPreview.dueAt, dueAt);
  await api.executePlan(plan, h); await api.executePlan(plan, h);
  assert.equal(h.calls.length, 3); assert.deepEqual(h.calls[1], h.calls[2]);
  assert.deepEqual(h.calls[1].options.body, { input: plan.request.input, expectedPreview: plan.expectedPreview });
  assert.equal(h.calls[1].options.idempotencyKey, plan.idempotencyKey);
  assert.equal(h.calls[1].endpoint, 'crm/task-routing/batches/commit');
});
test('source owner outside selected employees is visible and committed exactly as previewed', async () => {
  const response = { ...receipt, batch: { ...batch, managementVersion: 2, version: 2, counts: { ...counts, unassignedCount: 0, pendingCount: 1, allocatedCount: 1 } },
    tasks: [{ taskId: 'TASK_1', version: 2, ownerId: 'EMP_9', status: 'pending', dueAt }] };
  const h = harness(changePreview, response), { plan, preview } = await api.prepare('task-routing-allocation', change, h);
  assert.equal(preview.assignmentCounts.EMP_9, 1); assert.equal(preview.assignments[0].basis, 'source_lead_owner');
  await api.executePlan(plan, h); assert.deepEqual(h.calls[1].options.body.input.ownerIds, ['EMP_3']);
  assert.equal(h.calls[1].options.body.expectedPreview.assignments[0].toOwnerId, 'EMP_9');
});
test('blocked allocation and all-skipped creation expose preview without producing a plan', async () => {
  const preview = { ...changePreview, assignableCount: 0, blockedCount: 1, assignments: [], assignmentCounts: {}, blocked: [{ taskId: 'TASK_1', reason: 'source_owner_conflict' }] };
  assert.equal((await api.prepare('task-routing-allocation', change, harness(preview))).plan, null);
  const skipped = { ...createPreview, reservedCount: 0, skippedCount: 1, targets: [], skippedTargets: [{ targetKey: 'K1', target, reason: 'open_marketing_task' }] };
  assert.equal((await api.prepare('task-routing-create', request, harness(skipped))).plan, null);
});
test('left_scope is safe successful acknowledgement and retry never performs a scope preflight', async () => {
  const response = { ...receipt, scopeStatus: 'left_scope', batch: null, tasks: [], contacts: ['must not escape'] };
  const h = harness(createPreview, response), { plan } = await api.prepare('task-routing-create', request, h);
  const result = await api.executePlan(plan, h);
  assert.deepEqual(result, { committed: true, batchId: 'TB_1', operationId: 'ROUTE_EVT_1', scopeStatus: 'left_scope', batch: null, tasks: [] });
  await api.executePlan(plan, h); assert.equal(h.calls.length, 3);
  assert(h.calls.every(call => /\/(preview|commit)$/.test(call.endpoint)));
});
test('invalid 200 and timeout preserve exact plan body/key for recovery', async () => {
  const h = harness(), { plan } = await api.prepare('task-routing-create', request, h), saved = structuredClone(plan);
  const calls = []; let attempt = 0;
  h.client.request = async (endpoint, options) => { calls.push({ endpoint, options }); if (++attempt === 1) throw Object.assign(new Error('unknown'), { code: 'NETWORK_ERROR' });
    return { code: 'OK', data: attempt === 2 ? {} : receipt }; };
  await assert.rejects(api.executePlan(plan, h), { code: 'NETWORK_ERROR' });
  await assert.rejects(api.executePlan(plan, h), { code: 'INVALID_RESPONSE' });
  await api.executePlan(plan, h); assert.deepEqual(plan, saved); assert.deepEqual(calls[0], calls[2]);
});
test('current permissions, actor and server checked before retry but no version/time precheck', async () => {
  const h = harness(), { plan } = await api.prepare('task-routing-create', request, h);
  await assert.rejects(api.executePlan(plan, { ...h, me: { ...me, permissions: ['crm.task.write'] } }), { code: 'FORBIDDEN' });
  await assert.rejects(api.executePlan(plan, { ...h, me: { ...me, principalId: 'EMP_2' } }), { code: 'WRONG_ACTOR' });
  await assert.rejects(api.executePlan({ ...plan, server: 'https://other.example/crm-api/v2/' }, h), { code: 'INVALID_PLAN' });
  assert.equal(h.calls.length, 1);
});
test('strict input disallows route escape, fake legacy identity, duplicate tasks and source tampering', () => {
  for (const patch of [{ batchId: '../x' }, { endpoint: 'orders' }, { batchId: '/x' }])
    assert.throws(() => api.validateRequest('task-routing-allocation', { ...change, ...patch }));
  for (const patch of [{ sourceOwnerPolicy: 'owner_first' }, { strategy: 'quota', quotas: [{ ownerId: 'EMP_3', count: 1 }] },
    { selection: [change.input.selection[0], change.input.selection[0]] }, { expectedManagementVersion: 0 }])
    assert.throws(() => api.validateRequest('task-routing-allocation', { ...change, input: { ...change.input, ...patch } }));
  assert.throws(() => api.validateRequest('task-routing-create', { input: { ...request.input, taskType: 'service' } }));
  assert.throws(() => api.validateRequest('task-routing-create', { input: { ...request.input, dueAt: '2026-02-30T00:00:00Z' } }));
  assert.throws(() => api.validateRequest('task-routing-create', { input: { ...request.input, targets: [{ ...target, sources: [{ type: 'lead', id: 'LEAD_1' }] }] } }));
});
test('preview rejects mismatch in targets, employee counts, versions and nanoseconds', async () => {
  for (const preview of [{ ...createPreview, dueAt: dueAt.replace('321', '322') }, { ...createPreview, reservedCount: 2 },
    { ...createPreview, targets: [{ ...createPreview.targets[0], target: { ...target, studentId: 'STU_2' } }] }])
    await assert.rejects(api.prepare('task-routing-create', request, harness(preview)), { code: 'INVALID_PREVIEW' });
  for (const preview of [{ ...changePreview, assignmentCounts: { EMP_3: 1 } },
    { ...changePreview, assignments: [{ ...assignment, expectedVersion: 2 }] }])
    await assert.rejects(api.prepare('task-routing-allocation', change, harness(preview)), { code: 'INVALID_PREVIEW' });
});
test('receipts reject stale versions, wrong IDs, duplicate tasks and visible data in left_scope', async () => {
  const h = harness(), { plan } = await api.prepare('task-routing-create', request, h), op = api.definition(plan.operationId);
  for (const result of [{ ...receipt, batchId: 'TB_WRONG' }, { ...receipt, batch: { ...batch, managementVersion: 2 } },
    { ...receipt, tasks: [{ ...receipt.tasks[0], version: 2 }] }, { ...receipt, tasks: [receipt.tasks[0], receipt.tasks[0]] },
    { ...receipt, tasks: [{ ...receipt.tasks[0], dueAt: dueAt.replace('321', '322') }] }, { ...receipt, scopeStatus: 'left_scope' }])
    assert.throws(() => api.validateReceipt(op, plan, result), { code: 'INVALID_RESPONSE' });
});
test('recall, transfer, schedule and cancel keep fixed routes and original expanded preview', async () => {
  for (const action of ['recall', 'transfer', 'schedule', 'cancel']) {
    const input = { expectedManagementVersion: 1, reason: '合成核验' };
    if (action === 'recall') input.selection = [{ taskId: 'TASK_1', expectedVersion: 1 }];
    if (action === 'transfer') input.supervisorId = 'EMP_4';
    if (action === 'schedule') Object.assign(input, { startsAt, dueAt });
    const row = { ...assignment, toOwnerId: action === 'recall' ? null : assignment.toOwnerId,
      toStatus: action === 'recall' ? 'unassigned' : action === 'cancel' ? 'cancelled' : 'pending' };
    const preview = { ...changePreview, action, assignments: action === 'transfer' ? [] : [row], assignableCount: action === 'transfer' ? 0 : 1,
      toSupervisorId: action === 'transfer' ? 'EMP_4' : 'EMP_2', assignmentCounts: {} };
    const h = harness(preview, { ...receipt, scopeStatus: 'left_scope', batch: null, tasks: [] });
    const { plan } = await api.prepare('task-routing-' + action, { batchId: 'TB_1', input }, h);
    await api.executePlan(plan, h); assert.equal(h.calls[1].endpoint, `crm/task-routing/batches/TB_1/${action}/commit`);
  }
});
test('private plans are exclusive mode0600, blocked previews never write a plan file', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'routing-plan-')); t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const file = path.join(root, 'plan.json'); await api.createPlan('task-routing-create', request, file, harness());
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  await assert.rejects(api.createPlan('task-routing-create', request, file, harness()));
});
test('MCP schema is dynamic and precise; no task.manage means creation only', () => {
  const schemas = require('../mcp/java-task-routing-tools.cjs');
  assert.deepEqual(schemas.tools({ permissions: ['crm.task.read', 'crm.task.write'] }), []);
  const creator = schemas.tools({ permissions: ['crm.task.write', 'crm.task.assign'] });
  assert.equal(creator[0].inputSchema.oneOf.length, 1);
  assert.equal(schemas.tools(me)[0].inputSchema.oneOf.length, 6);
  const properties = schemas.inputs.allocation.properties;
  assert.deepEqual(properties.sourceOwnerPolicy.enum, ['none', 'source_lead_owner_first']);
  assert.equal(schemas.inputs.create.additionalProperties, false);
});
