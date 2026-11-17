'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crm = require('./java-crm-operations.cjs');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const tmp = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-crm-cli-')));
test.after(() => fs.rmSync(tmp, { recursive: true, force: true }));
let fileIndex = 0;
function input(value) { const filename = path.join(tmp, `input-${++fileIndex}.json`); fs.writeFileSync(filename, JSON.stringify(value), { mode: 0o600 }); return filename; }
test('customer, campaign, touchpoint and task DTO validators reject unknown or invalid fields', () => {
  assert.deepEqual(crm.validate('customer-create', { campusId: 'C1', displayName: 'Synthetic' }), { campusId: 'C1', displayName: 'Synthetic' });
  assert.throws(() => crm.validate('customer-create', { campusId: 'C1', displayName: 'Synthetic', ownerId: 'P1' }), { code: 'INVALID_CRM_INPUT' });
  assert.throws(() => crm.validate('campaign-create', { campusId: 'C1', title: 'X', activityType: 'event', startsAt: '2026-01-01T00:00:00Z', endsAt: '2026-01-02T00:00:00Z' }), { code: 'INVALID_CRM_INPUT' });
  assert.throws(() => crm.validate('task-update', { status: 'completed', expectedVersion: 1 }), { code: 'INVALID_CRM_INPUT' });
  assert.throws(() => crm.validate('touchpoint-create', { source: 'phone', channel: 'phone', summary: 'x', occurredAt: '2026-01-01T00:00:00Z', studentId: '' }), { code: 'INVALID_CRM_INPUT' });
  assert.throws(() => crm.validate('campaign-create', { campusId: 'C1', title: 'X', activityType: 'campaign', startsAt: '2026-02-30T00:00:00Z', endsAt: '2026-03-01T00:00:00Z' }), { code: 'INVALID_CRM_INPUT' });
});
test('CRM writes use fixed v2 endpoints, explicit idempotency and conditional permissions', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { id: 'result' }; } };
  const identity = { permissions: ['crm.customer.write', 'crm.touchpoint.write', 'crm.customer.read', 'crm.activity.write', 'crm.task.write', 'crm.task.assign', 'crm.opportunity.read', 'student.read'] };
  await crm.run(['customer-create', '--input', input({ campusId: 'C1', displayName: 'Synthetic' }), '--key', 'crm-test-key-0001'], client, identity);
  await crm.run(['campaign-close', 'A1', '--input', input({ expectedVersion: 3 }), '--key', 'crm-test-key-0002'], client, identity);
  await crm.run(['task-complete', 'T1', '--input', input({ result: 'qualified', expectedVersion: 2 }), '--key', 'crm-test-key-0003'], client, identity);
  assert.deepEqual(calls.map(call => [call.path, call.options.method, call.options.idempotencyKey]), [
    ['crm/customers', 'POST', 'crm-test-key-0001'], ['crm/activities/A1/close', 'PUT', 'crm-test-key-0002'], ['crm/tasks/T1/complete', 'POST', 'crm-test-key-0003'],
  ]);
  await assert.rejects(crm.run(['task-update', 'T1', '--input', input({ status: 'cancelled', reason: 'synthetic', expectedVersion: 2 }), '--key', 'crm-test-key-0004'], client, { permissions: ['crm.task.write'] }), { code: 'FORBIDDEN' });
});
test('explicit lead batch assignment is not aliased to rule-driven public-pool routing', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return {}; } };
  await assert.rejects(crm.run(['lead-batch-assign', '--input', input({
    leadIds: ['L1'], ownerIds: ['P1'], strategy: 'even', reason: 'synthetic',
  }), '--key', 'crm-test-key-0005'], client, { permissions: ['lead.assign', 'crm.lead.assign'] }), { code: 'INVALID_CRM_INPUT' });
  assert.equal(calls.length, 0);
});
test('CRM order payment-link uses the fixed Java route and exact permission without claiming provider availability', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { code: 'PAYMENT_PROVIDER_UNAVAILABLE' }; } };
  await crm.run(['order-payment-link', 'ORD_SYNTH_1'], client, { permissions: ['order.paymentLink'] });
  assert.deepEqual(calls, [{ path: 'crm/orders/ORD_SYNTH_1/payment-link', options: { method: 'POST' } }]);
  await assert.rejects(crm.run(['order-payment-link', 'ORD_SYNTH_1'], client, { permissions: ['crm.order.write'] }), { code: 'FORBIDDEN' });
  await assert.rejects(crm.run(['order-payment-link', 'ORD/1'], client, { permissions: ['order.paymentLink'] }), { code: 'INVALID_CRM_INPUT' });
  assert.equal(calls.length, 1);
});
test('customer conversion, customer patch and draft campaign patch use exact Java DTOs and permissions', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { id: 'synthetic' }; } };
  const identity = { permissions: ['lead.follow', 'lead.create'] };
  await crm.run(['customer-from-lead', '--input', input({ leadId: 'LEAD_1', expectedVersion: 4 }), '--key', 'crm-from-lead-key-001'], client, identity);
  const customerPatch = { patch: { note: 'synthetic note', contacts: [{ phone: '+8613900000000', primary: true }] }, expectedVersion: 2 };
  await crm.run(['customer-update', 'CUS_1', '--input', input(customerPatch), '--key', 'crm-customer-update-001'], client, identity);
  await assert.rejects(crm.run(['customer-update', 'CUS_1', '--input', input({ patch: { campusId: 'CAMPUS_1' }, expectedVersion: 2 }), '--key', 'crm-customer-campus-001'], client, identity), { code: 'FORBIDDEN' });
  const campaignPatch = { patch: { description: 'synthetic draft edit', budgetMinor: 1000 }, expectedVersion: 3 };
  await crm.run(['campaign-update', 'ACT_1', '--input', input(campaignPatch), '--key', 'crm-campaign-update-001'], client, identity);
  assert.deepEqual(calls.map(call => [call.path, call.options.method, call.options.idempotencyKey, call.options.body]), [
    ['crm/customers/from-lead', 'POST', 'crm-from-lead-key-001', { leadId: 'LEAD_1', expectedVersion: 4 }],
    ['crm/customers/CUS_1', 'PUT', 'crm-customer-update-001', customerPatch],
    ['crm/activities/ACT_1', 'PUT', 'crm-campaign-update-001', campaignPatch],
  ]);
  await assert.rejects(crm.run(['customer-update', 'CUS_1', '--input', input({ patch: { ownerId: 'P2' }, expectedVersion: 1 }), '--key', 'crm-customer-update-002'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['campaign-update', 'ACT_1', '--input', input({ patch: { status: 'active' }, expectedVersion: 1 }), '--key', 'crm-campaign-update-002'], client, identity), { code: 'INVALID_CRM_INPUT' });
});
test('CRM profile reads use fixed scoped routes and profile option permissions', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { items: [] }; } };
  const identity = { permissions: ['lead.follow', 'lead.create'] };
  await crm.run(['customer-profile', 'CUS_1'], client, identity);
  await crm.run(['lead-profile', 'LEAD_1'], client, identity);
  await crm.run(['campaign-profile', 'ACT_1'], client, identity);
  await crm.run(['profile-options', 'campuses', '--page', '2', '--size', '25'], client, identity);
  await crm.run(['profile-options', 'forms', '--campus', 'CAMPUS_1'], client, identity);
  await crm.run(['profile-options', 'employees', '--campus', 'CAMPUS_1', '--size', '10'], client, identity);
  assert.deepEqual(calls, [
    { path: 'crm/customers/CUS_1/profile', options: { method: 'GET' } },
    { path: 'crm/customers/from-lead/LEAD_1', options: { method: 'GET' } },
    { path: 'crm/activities/ACT_1/profile', options: { method: 'GET' } },
    { path: 'crm/profile-options?kind=campuses&page=2&size=25', options: { method: 'GET' } },
    { path: 'crm/profile-options?kind=forms&campusId=CAMPUS_1', options: { method: 'GET' } },
    { path: 'crm/profile-options?kind=employees&campusId=CAMPUS_1&size=10', options: { method: 'GET' } },
  ]);
  await assert.rejects(crm.run(['customer-profile', 'CUS_1'], client, { permissions: ['crm.customer.read'] }), { code: 'FORBIDDEN' });
  await assert.rejects(crm.run(['profile-options', 'forms'], client, { permissions: ['lead.follow'] }), { code: 'FORBIDDEN' });
  await assert.rejects(crm.run(['profile-options', 'employees'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['customer-profile', 'bad/id'], client, identity), { code: 'INVALID_CRM_INPUT' });
  assert.equal(calls.length, 6);
});
test('CRM profile descriptors are registry-visible only under their exact read grants', () => {
  const descriptor = name => crm.operations.find(item => item.name === name);
  assert.equal(crm.allowed(descriptor('customer-profile'), ['lead.follow']), true);
  assert.equal(crm.allowed(descriptor('customer-profile'), ['crm.customer.read']), false);
  assert.equal(crm.allowed(descriptor('campaign-profile'), ['lead.follow']), false);
  assert.equal(crm.allowed(descriptor('campaign-profile'), ['lead.create']), true);
  assert.equal(crm.allowed(descriptor('profile-options'), ['lead.follow']), true);
  assert.equal(crm.allowed(descriptor('profile-options'), ['crm.profile.read']), false);
});
test('campaign draft lifecycle uses versioned fixed Java commands', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { id: 'ACT_SYNTH' }; } };
  const identity = { permissions: ['lead.create'] };
  const draft = { name: 'Synthetic campaign', campusId: 'CAMPUS_1', budgetMinor: 10000, participantIds: ['P1'] };
  await crm.run(['campaign-draft-create', '--input', input(draft), '--key', 'crm-campaign-draft-001'], client, identity);
  await crm.run(['campaign-update', 'ACT_SYNTH', '--input', input({ patch: { description: 'updated' }, expectedVersion: 1 }), '--key', 'crm-campaign-edit-001'], client, identity);
  await crm.run(['campaign-publish', 'ACT_SYNTH', '--input', input({ expectedVersion: 2 }), '--key', 'crm-campaign-publish-001'], client, identity);
  assert.deepEqual(calls.map(call => [call.path, call.options.method, call.options.idempotencyKey, call.options.body]), [
    ['crm/activities/campaigns', 'POST', 'crm-campaign-draft-001', draft],
    ['crm/activities/ACT_SYNTH', 'PUT', 'crm-campaign-edit-001', { patch: { description: 'updated' }, expectedVersion: 1 }],
    ['crm/activities/ACT_SYNTH/publish', 'POST', 'crm-campaign-publish-001', { expectedVersion: 2 }],
  ]);
  await assert.rejects(crm.run(['campaign-draft-create', '--input', input({ name: 'bad', status: 'active' }), '--key', 'crm-campaign-draft-002'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['campaign-publish', 'ACT_SYNTH', '--input', input({ expectedVersion: 0 }), '--key', 'crm-campaign-publish-002'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['campaign-publish', 'ACT_SYNTH', '--input', input({ expectedVersion: 2 }), '--key', 'crm-campaign-publish-003'], client, { permissions: ['crm.activity.write'] }), { code: 'FORBIDDEN' });
  assert.equal(calls.length, 3);
});
test('campaign promotion-link list and create use fixed scoped Java routes and exact DTOs', async () => {
  const calls = [], client = { request: async (path, options) => { calls.push({ path, options }); return { items: [], version: 4 }; } };
  const identity = { permissions: ['lead.create'] };
  await crm.run(['campaign-promotion-links', 'ACT_1'], client, identity);
  const body = { formId: 'FORM_1', promoterId: 'STAFF_1', name: 'Synthetic link', expectedVersion: 4 };
  await crm.run(['campaign-promotion-link-create', 'ACT_1', '--input', input(body), '--key', 'crm-promotion-link-001'], client, identity);
  assert.deepEqual(calls, [
    { path: 'crm/activities/ACT_1/promotion-links', options: { method: 'GET' } },
    { path: 'crm/activities/ACT_1/promotion-links', options: { method: 'POST', idempotencyKey: 'crm-promotion-link-001', body } },
  ]);
  const descriptor = name => crm.operations.find(item => item.name === name);
  assert.equal(crm.allowed(descriptor('campaign-promotion-link-create'), ['lead.create']), true);
  assert.equal(crm.allowed(descriptor('campaign-promotion-link-create'), ['crm.activity.write']), false);
  await assert.rejects(crm.run(['campaign-promotion-link-create', 'ACT_1', '--input', input({ ...body, expectedVersion: 0 }), '--key', 'crm-promotion-link-002'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['campaign-promotion-link-create', 'ACT_1', '--input', input({ ...body, extra: true }), '--key', 'crm-promotion-link-003'], client, identity), { code: 'INVALID_CRM_INPUT' });
  await assert.rejects(crm.run(['campaign-promotion-link-create', 'ACT_1', '--input', input(body), '--key', 'crm-promotion-link-004'], client, { permissions: ['crm.activity.write'] }), { code: 'FORBIDDEN' });
  assert.equal(calls.length, 2);
});
test('real CLI dispatch executes CRM writes only through authenticated Java v2', t => {
  const { spawnSync } = require('node:child_process');
  const config = path.join(tmp, 'cli-config'), log = path.join(tmp, 'cli-requests.json'), preload = path.join(tmp, 'cli-fetch.cjs');
  const env = { ...process.env, SIYUE_CONFIG_DIR: config };
  require('./auth-client.cjs').writeSession({ server: 'https://synthetic.example/crm-api/v2/', token: 'synthetic', apiVersion: 'v2' }, env);
  fs.writeFileSync(preload, `const fs=require('node:fs');global.fetch=async(url,init={})=>{const p=new URL(url).pathname;const calls=fs.existsSync(${JSON.stringify(log)})?JSON.parse(fs.readFileSync(${JSON.stringify(log)})):[];calls.push({path:p,method:init.method||'GET',headers:init.headers||{},body:init.body||null});fs.writeFileSync(${JSON.stringify(log)},JSON.stringify(calls));const data=p.endsWith('/identity/me')?{principalId:'P1',scope:'own',permissions:['crm.customer.write']}:{id:'CU_SYNTH'};return {ok:true,status:200,json:async()=>({code:'OK',data})};};`);
  const payload = input({ campusId: 'C1', displayName: 'Synthetic' });
  const result = spawnSync(process.execPath, [path.join(__dirname, 'siyue-cli.cjs'), 'crm', 'customer-create', '--input', payload, '--key', 'crm-entry-key-0001'], { encoding: 'utf8', env: { ...env, NODE_OPTIONS: `--require=${preload}` } });
  assert.equal(result.status, 0, result.stderr);
  const calls = JSON.parse(fs.readFileSync(log));
  assert.deepEqual(calls.map(call => [call.path, call.method]), [['/crm-api/v2/identity/me', 'GET'], ['/crm-api/v2/crm/customers', 'POST']]);
  assert.equal(calls[1].headers['Idempotency-Key'], 'crm-entry-key-0001');
});
