'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const needs = require('./java-crm-needs.cjs');
const me = { principalId: 'EMP_1', permissions: ['crm.opportunity.intake', 'crm.opportunity.write'] };
const input = () => ({ customerId: 'CUS_1', studentId: 'STU_1', title: '合成数学需求', subject: '数学', term: '2026秋',
  identity: { departmentId: 'DEP_1', projectId: 'PRO_1' }, profile: { fiscalYearId: 'FY_1', quarterId: 'QU_1' }, detail: '合成咨询' });
const preview = (overrides = {}) => ({ catalogVersion: 4, inputHash: 'a'.repeat(64), customerVersion: 2,
  sourceLeadVersion: null, activityVersion: null, scopeCampusId: 'CAMP_1', candidates: [],
  allowedActions: ['create'], selectedOwnerId: 'EMP_1', differences: [], ...overrides });
const candidate = (match = 'exact') => ({ targetRef: 'OPP_1', version: 3, subject: '数学', term: '2026秋', studentId: 'STU_1',
  identity: match === 'exact' ? input().identity : null, match, readable: false });
const receipt = (overrides = {}) => ({ outcome: 'created', opportunityId: 'OPP_1', consultationId: 'CONS_1', ownerChanged: false,
  notificationState: 'not_required', opportunity: null, identityStatus: 'classified', ...overrides });
function client(handler) {
  return { apiVersion: 'v2', server: 'https://java.example/crm-api/v2/', async request(endpoint, options) {
    return { code: 'OK', data: await handler(endpoint, options) };
  } };
}

test('intake without a decision reads the real preview but cannot create a plan', async () => {
  const calls = [], api = client(async (endpoint, options) => { calls.push({ endpoint, options }); return preview(); });
  const result = await needs.prepare('need-intake', { input: input() }, { client: api, me });
  assert.equal(result.plan, null); assert.equal(calls.length, 1);
  assert.equal(calls[0].endpoint, 'crm/opportunity-needs/preview');
  assert.equal(calls[0].options.idempotencyKey, undefined);
});

test('reviewed intake retries the exact body/key without re-previewing and stays isolated from caller mutation', async () => {
  const calls = [], api = client(async (endpoint, options) => {
    calls.push({ endpoint, options }); return endpoint.endsWith('/preview') ? preview() : receipt();
  });
  const request = { input: input(), decision: { action: 'create' } };
  const prepared = await needs.prepare('need-intake', request, { client: api, me });
  request.input.title = 'Changed caller';
  await needs.executePlan(prepared.plan, { client: api, me });
  await needs.executePlan(prepared.plan, { client: api, me });
  const writes = calls.filter(call => call.endpoint.endsWith('/commit-preview'));
  assert.equal(calls.length, 3); assert.equal(writes.length, 2);
  assert.equal(writes[0].options.idempotencyKey, writes[1].options.idempotencyKey);
  assert.deepEqual(writes[0].options.body, writes[1].options.body);
  assert.equal(writes[0].options.body.input.title, '合成数学需求');
  assert.deepEqual(writes[0].options.body.expectedPreview, preview());
});

test('duplicate consultation explicitly targets the exact demand and accepts a redacted success receipt', async () => {
  const expected = preview({ candidates: [candidate()], allowedActions: ['reuse'] });
  const api = client(async endpoint => endpoint.endsWith('/preview') ? expected : receipt({ outcome: 'reused', notificationState: 'recorded' }));
  const prepared = await needs.prepare('need-intake', { input: input(), decision: { action: 'reuse', targetRef: 'OPP_1' } }, { client: api, me });
  assert.equal((await needs.executePlan(prepared.plan, { client: api, me })).opportunity, null);
  assert.throws(() => needs.validatePreview(input(), expected, { action: 'create' }), /不允许/);
  assert.throws(() => needs.validatePreview(input(), expected, { action: 'reuse', targetRef: 'OPP_OTHER' }), /不一致/);
});

test('legacy unknown requires an explicit classification or distinction reason', () => {
  const expected = preview({ candidates: [candidate('legacy_unknown')], allowedActions: ['classify_existing', 'create_distinct'] });
  assert.throws(() => needs.validateDecision({ action: 'create_distinct' }), /文字/);
  assert.throws(() => needs.validateDecision({ action: 'classify_existing', targetRef: 'OPP_1' }), /文字/);
  assert.doesNotThrow(() => needs.validatePreview(input(), expected,
    { action: 'classify_existing', targetRef: 'OPP_1', reason: '核对原咨询后明确部门项目' }));
  assert.throws(() => needs.validatePreview(input(), expected, { action: 'reuse', targetRef: 'OPP_1' }), /不允许/);
});

test('preview rejects wrong child, subject, identity, repeated candidates and unknown action', () => {
  for (const changed of [{ studentId: 'STU_2' }, { subject: '英语' }, { identity: { departmentId: 'DEP_2', projectId: 'PRO_1' } }])
    assert.throws(() => needs.validatePreview(input(), preview({ candidates: [{ ...candidate(), ...changed }] })), /候选/);
  assert.throws(() => needs.validatePreview(input(), preview({ candidates: [candidate(), candidate()] })), /候选/);
  assert.throws(() => needs.validatePreview(input(), preview({ allowedActions: ['force_won'] })), /格式/);
});

test('need input rejects unknown fields, path escapes, manual won and ambiguous dictionary rules', () => {
  for (const bad of [{ ...input(), stage: 'won' }, { ...input(), customerId: '../CUS' },
    { ...input(), identity: { departmentId: 'DEP_1', ownerId: 'EMP_OTHER' } },
    { ...input(), profile: { academicYear: '2026' } }])
    assert.throws(() => needs.validateRequest('need-intake', { input: bad }));
  assert.throws(() => needs.validateRequest('need-option-create', { input: { kind: 'judgment', code: 'won', label: '成交', effect: 'won' } }), /关闭/);
  assert.throws(() => needs.validateRequest('need-option-create', { input: { kind: 'department', code: 'dept', label: '部门' } }), /项目/);
  assert.throws(() => needs.validateRequest('need-option-create', { input: { kind: 'project', code: 'p', label: '项目' } }), /编号/);
});

test('same file cannot cross employee/server or mutate reviewed input', async () => {
  const api = client(async () => preview());
  const { plan } = await needs.prepare('need-intake', { input: input(), decision: { action: 'create' } }, { client: api, me });
  await assert.rejects(needs.executePlan(plan, { client: api, me: { ...me, principalId: 'EMP_2' } }), { code: 'WRONG_ACTOR' });
  await assert.rejects(needs.executePlan({ ...plan, server: 'https://other.example/crm-api/v2/' }, { client: api, me }), { code: 'INVALID_PLAN' });
  await assert.rejects(needs.executePlan({ ...plan, operationId: '../../admin' }, { client: api, me }), { code: 'JAVA_V2_UNAVAILABLE' });
});

test('profile review preserves all independent fiscal/quarter fields and verifies versions only before the first plan', async () => {
  const calls = [], body = { profile: { fiscalYearId: 'FY_1', quarterId: 'QU_1', preferredCampusId: 'CAMP_2' }, expectedVersion: 3, expectedCatalogVersion: 4 };
  const api = client(async (endpoint, options) => {
    calls.push({ endpoint, options });
    if (options?.method === 'PUT') return { opportunity: { id: 'OPP_1', version: 4 }, profile: body.profile };
    return endpoint.endsWith('/options') ? { catalogVersion: 4, items: [] } : { opportunity: { id: 'OPP_1', version: 3 } };
  });
  const { plan } = await needs.prepare('need-profile', { targetId: 'OPP_1', input: body }, { client: api, me });
  assert.equal(plan.review.serverPreview, false);
  await needs.executePlan(plan, { client: api, me }); await needs.executePlan(plan, { client: api, me });
  assert.equal(calls.length, 4); assert.deepEqual(calls[2].options.body, body);
  assert.equal(calls[2].options.idempotencyKey, calls[3].options.idempotencyKey);
  plan.request.input.profile.levelId = 'LEVEL_2';
  assert.throws(() => needs.validatePlan(plan), /核对资料不一致/);
});

test('assignment uses paginated opportunity candidates and accepts leaving the original scope', async () => {
  const calls = [], api = client(async (endpoint, options) => {
    calls.push({ endpoint, options });
    if (endpoint.includes('/assignees?')) return { items: [{ id: 'EMP_2', name: '合成员工' }], total: 1, page: 1, size: 100 };
    if (options?.method === 'PUT') return { opportunityId: 'OPP_1', ownerId: 'EMP_2', version: 4, scopeStatus: 'left_scope' };
    return { opportunity: { id: 'OPP_1', version: 3 } };
  });
  const { plan } = await needs.prepare('need-assign', { targetId: 'OPP_1', input: { ownerId: 'EMP_2', reason: '接待交接', expectedVersion: 3 } }, { client: api, me });
  assert.equal((await needs.executePlan(plan, { client: api, me })).scopeStatus, 'left_scope');
  assert(calls.some(call => call.endpoint === 'crm/opportunity-needs/assignees?opportunityId=OPP_1&page=1&size=100'));
});

test('configuration create/update read current configuration and retain inactive options', async () => {
  const api = client(async (endpoint, options) => options?.method
    ? { item: { id: 'DEP_1', kind: 'department', code: 'sales', label: options.body.label, active: options.body.active ?? true,
      version: options.method === 'POST' ? 1 : 3 }, catalogVersion: 5 }
    : { items: [{ id: 'DEP_1', version: 2, active: false }], total: 1, page: 1, size: 100 });
  const { plan } = await needs.prepare('need-option-create', { input: { kind: 'department', code: 'sales', label: '合成部门', projectMode: 'not_used' } }, { client: api, me });
  assert.equal((await needs.executePlan(plan, { client: api, me })).item.version, 1);
  const update = await needs.prepare('need-option-update', { targetId: 'DEP_1', input: { label: '合成部门2', active: false, expectedVersion: 2 } }, { client: api, me });
  assert.equal(update.plan.review.current.active, false);
  await needs.executePlan(update.plan, { client: api, me });
});

test('mark read stays on the current recipient directory and cannot accept an unrelated receipt', async () => {
  const api = client(async (endpoint, options) => options?.method
    ? { id: 'NOTE_1', version: 2, readAt: '2026-10-14T01:00:00Z', visibility: 'restricted', opportunityId: null }
    : { items: [{ id: 'NOTE_1', version: 1 }], total: 1, page: 1, size: 100 });
  const { plan } = await needs.prepare('need-notification-read', { targetId: 'NOTE_1', input: { expectedVersion: 1 } }, { client: api, me });
  await needs.executePlan(plan, { client: api, me });
  assert.throws(() => needs.validateReceipt(needs.definition('need-notification-read'), plan.request,
    { id: 'NOTE_OTHER', version: 2, readAt: 'now' }), { code: 'INVALID_RESPONSE' });
});

test('unknown outcomes keep the private original plan available for safe retry', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-need-plan-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'plan.json'); let writes = 0;
  const api = client(async endpoint => {
    if (endpoint.endsWith('/preview')) return preview();
    writes++; if (writes === 1) throw Object.assign(new Error('response lost'), { code: 'NETWORK_ERROR' });
    return receipt();
  });
  await needs.createPlan('need-intake', { input: input(), decision: { action: 'create' } }, file, { client: api, me });
  const original = fs.readFileSync(file, 'utf8'); assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  await assert.rejects(needs.execute(file, { client: api, me }), { code: 'NETWORK_ERROR' });
  await needs.execute(file, { client: api, me }); assert.equal(fs.readFileSync(file, 'utf8'), original);
});
