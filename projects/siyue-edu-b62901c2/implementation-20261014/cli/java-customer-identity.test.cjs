'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const api = require('./java-customer-identity.cjs');
const server = 'https://java.example/crm-api/v2/';
const me = { principalId: 'EMP_1', scope: 'all', permissions: ['crm.customer.read', 'crm.customer.identity.write', 'crm.customer.search.manage'] };
const now = '2026-10-14T08:00:00Z';
const item = { id: 'SID_1', studentId: 'STU_1', namespace: '旧系统', value: '000Aa1', active: true, version: 1, createdAt: now, deactivatedAt: null };
const request = { customerId: 'CUS_1', studentId: 'STU_1', input: { namespace: '旧系统', value: '000Aa1', expectedStudentVersion: 3 } };
const status = { totalStudents: 5, indexedStudents: 0, pendingStudents: 5, keyVersionCount: 2, indexVersion: 1 };
function harness(readRows = [], reply = item) {
  const calls = [];
  const client = { apiVersion: 'v2', server, request: async (endpoint, options) => {
    calls.push({ endpoint, options });
    return { code: 'OK', data: options ? reply : { items: readRows, total: readRows.length, page: 1, size: 100, studentVersion: 3 } };
  } };
  return { client, me, calls };
}
test('identifier plan preserves exact number and source; retries never re-read or replace the original key', async () => {
  const h = harness(), input = structuredClone(request);
  const { plan } = await api.prepare('student-identifier-create', input, h);
  input.input.value = 'modified';
  assert.equal(plan.request.input.value, '000Aa1');
  assert.equal(plan.review.serverPreview, false);
  await api.executePlan(plan, h); await api.executePlan(plan, h);
  assert.equal(h.calls.length, 3);
  assert.equal(h.calls[0].endpoint, 'crm/customers/CUS_1/students/STU_1/identifiers?status=all&page=1&size=100');
  assert.deepEqual(h.calls[1], h.calls[2]);
  assert.equal(h.calls[1].options.body.value, '000Aa1');
  assert.equal(h.calls[1].options.idempotencyKey, plan.idempotencyKey);
});
test('exact values reject normalization, controls, missing versions, extra fields and unsafe resource IDs', () => {
  for (const value of [' 000Aa1', '000Aa1 ', '', '\t12', 'x\n'])
    assert.throws(() => api.validateRequest('student-identifier-create', { ...request, input: { ...request.input, value } }), { code: 'INVALID_IDENTITY_INPUT' });
  for (const patch of [{ studentId: '../STU' }, { identifierId: 'SID_1' }, { endpoint: 'arbitrary' }])
    assert.throws(() => api.validateRequest('student-identifier-create', { ...request, ...patch }), { code: 'INVALID_IDENTITY_INPUT' });
  assert.throws(() => api.validateRequest('student-identifier-create', { ...request, input: { ...request.input, expectedStudentVersion: 0 } }));
  for (const input of [{ afterId: -1, limit: 100 }, { afterId: 0, limit: 201 }, { afterId: true, limit: 1 }])
    assert.throws(() => api.validateRequest('student-name-index-rebuild', { input }));
});
test('public IDs match the Java contract including dots, without accepting path segments', async () => {
  const input = { ...request, customerId: 'CUS.legacy-1', studentId: 'STU.legacy-1' };
  const reply = { ...item, id: 'SID.legacy-1', studentId: input.studentId };
  const h = harness([], reply), { plan } = await api.prepare('student-identifier-create', input, h);
  assert.deepEqual(await api.executePlan(plan, h), reply);
  assert.equal(h.calls[1].endpoint, 'crm/customers/CUS.legacy-1/students/STU.legacy-1/identifiers');
  for (const customerId of ['.', '..', '_CUS', '-CUS', '../CUS', 'CUS/1', 'CUS%2f1', 'CUS?1', 'CUS#1'])
    assert.throws(() => api.validateRequest('student-identifier-create', { ...request, customerId }), { code: 'INVALID_IDENTITY_INPUT' });
  const schema = require('../mcp/java-identity-tools.cjs').tools(me)[0].inputSchema.oneOf
    .find(branch => branch.properties.operation.const === 'student-identifier-create');
  assert.equal(schema.properties.customerId.pattern, '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$');
});
test('plans require matching student version, no existing active exact mapping and complete distinct pages', async () => {
  await assert.rejects(api.prepare('student-identifier-create', request, harness([item])), { code: 'ALREADY_EXISTS' });
  await assert.rejects(api.prepare('student-identifier-create', { ...request, input: { ...request.input, expectedStudentVersion: 2 } }, harness()), { code: 'VERSION_CONFLICT' });
  await assert.rejects(api.prepare('student-identifier-create', request, harness([item, item])), { code: 'INVALID_PREVIEW' });
  await assert.rejects(api.prepare('student-identifier-create', request, harness([{ ...item, studentId: 'STU_2' }])), { code: 'INVALID_RESPONSE' });
});
test('deactivation uses the scoped original mapping and validates both version and exact value', async () => {
  const input = { customerId: 'CUS_1', studentId: 'STU_1', identifierId: 'SID_1', input: { expectedVersion: 1, reason: '核对后停用旧备案' } };
  const receipt = { ...item, active: false, version: 2, deactivatedAt: now }, h = harness([item], receipt);
  const { plan } = await api.prepare('student-identifier-deactivate', input, h);
  assert.deepEqual(await api.executePlan(plan, h), receipt);
  assert.equal(h.calls[1].endpoint, 'crm/customers/CUS_1/students/STU_1/identifiers/SID_1/deactivate');
  for (const patch of [{ active: true }, { value: '000aa1' }, { id: 'SID_2' }, { version: 3 }])
    assert.throws(() => api.validateReceipt(api.definition(plan.operationId), plan, { ...receipt, ...patch }), { code: 'INVALID_RESPONSE' });
  const inactive = harness([receipt], receipt);
  const repeated = await api.prepare('student-identifier-deactivate', { ...input, input: { ...input.input, expectedVersion: 2 } }, inactive);
  assert.deepEqual(await api.executePlan(repeated.plan, inactive), receipt);
});
test('maintenance plans use separate cursor types, retain original request and accept only coherent counts', async () => {
  const calls = [], client = { apiVersion: 'v2', server, request: async (endpoint, options) => {
    calls.push({ endpoint, options });
    return { code: 'OK', data: options ? { processed: 2, nextAfterId: 5, hasMore: true, status: { ...status, indexedStudents: 2, pendingStudents: 3 } } : status };
  } };
  const { plan } = await api.prepare('student-name-index-rebuild', { input: { afterId: 0, limit: 2 } }, { client, me });
  await api.executePlan(plan, { client, me }); await api.executePlan(plan, { client, me });
  assert.equal(calls[0].endpoint, 'crm/customer-search/student-name-index/status');
  assert.equal(calls[1].endpoint, 'crm/customer-search/student-name-index/rebuild'); assert.deepEqual(calls[1], calls[2]);
  assert.throws(() => api.indexStatus('name', { ...status, indexedStudents: 6 }), { code: 'INVALID_RESPONSE' });
  assert.deepEqual(api.indexStatus('number', { totalIdentifiers: 5, indexedIdentifiers: 2, pendingIdentifiers: 3, missingSearchable: 1 }),
    { totalIdentifiers: 5, indexedIdentifiers: 2, pendingIdentifiers: 3, missingSearchable: 1 });
  assert.throws(() => api.indexStatus('number', { totalIdentifiers: 5, indexedIdentifiers: 5, pendingIdentifiers: 0, missingSearchable: 1 }));
  assert.throws(() => api.validateReceipt(api.definition(plan.operationId), plan, { processed: 0, nextAfterId: 0, hasMore: true, status }));
});
test('identity plans bind server, current principal and specific permissions including all-only maintenance', async () => {
  const h = harness(), { plan } = await api.prepare('student-identifier-create', request, h);
  for (const actor of [{ ...me, principalId: 'EMP_2' }, { ...me, permissions: ['crm.customer.read'] }])
    await assert.rejects(api.executePlan(plan, { client: h.client, me: actor }));
  await assert.rejects(api.executePlan(plan, { client: { ...h.client, server: 'https://elsewhere.example/' }, me }), { code: 'INVALID_PLAN' });
  await assert.rejects(api.prepare('student-name-index-rebuild', { input: { afterId: 0, limit: 1 } }, { client: h.client, me: { ...me, scope: 'campus' } }), { code: 'FORBIDDEN' });
  assert.equal(h.calls.length, 1);
  assert.throws(() => api.validatePlan({ ...plan, request: { ...plan.request, input: { ...plan.request.input, value: 'changed' } } }), { code: 'INVALID_PLAN' });
});
test('private persisted plan remains usable after unknown response and rejects wrong receipts', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-identity-plan-')); t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'plan.json'), h = harness();
  await api.createPlan('student-identifier-create', request, file, h);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  const original = fs.readFileSync(file);
  await assert.rejects(api.execute(file, { client: { ...h.client, request: async () => { throw new Error('response lost'); } }, me }), /response lost/);
  assert.deepEqual(fs.readFileSync(file), original);
  assert.deepEqual(await api.execute(file, h), item);
  const plan = JSON.parse(original);
  for (const patch of [{ value: '000aa1' }, { namespace: '不同来源' }, { active: false }, { version: 2 }])
    assert.throws(() => api.validateReceipt(api.definition(plan.operationId), plan, { ...item, ...patch }));
});
