const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const apiSource = fs.readFileSync(new URL('../java-sales-api.js', `file://${__filename}`), 'utf8');
const workspaceSource = fs.readFileSync(new URL('../java-sales-workspace.js', `file://${__filename}`), 'utf8');

function setup(permissions = ['report.read', 'sales.activity.read'], scope = 'all') {
  const requests = [], listeners = {};
  const apiRoot = {};
  vm.runInNewContext(apiSource, { window: apiRoot, Object, String, TypeError, Number, Math, Date });
  const salesWorkspace = apiRoot.siyueJavaSalesApi.create({ request: async (...args) => {
    requests.push(args);
    if (args[1] === 'crm/reports/sales') return { data: { scope: '授权校区', cohortDescription: '首笔实收冻结归属', items: [
      { employeeId: 'STAFF-1', assignedUnique: 8, convertedUnique: 2, lowSample: true, minimumSample: 10,
        receivedAmount: '125.50', refundedAmount: '10.00', netReceivedAmount: '115.50' }
    ] } };
    if (args[1] === 'crm/sales/activity') return { data: { items: [], hasMore: false, nextCursor: null } };
    return { data: { items: [], hasMore: false, nextCursor: null } };
  } });
  const root = {};
  vm.runInNewContext(workspaceSource, { window: root, Object, String, TypeError, Number, Math, Date, FormData });
  let html = '';
  const workspace = root.siyueJavaSales.create({ api: { salesWorkspace }, identity: {
    principalId: 'STAFF-1', scope, permissions
  }, render: value => { html = value; }, now: () => new Date('2026-10-17T10:00:00Z') });
  const container = { addEventListener: (name, handler) => { listeners[name] = handler; } };
  workspace.bind(container);
  return { workspace, requests, listeners, get html() { return html; } };
}

async function click(app, action) {
  const target = { dataset: { salesAction: action }, closest: () => target };
  app.listeners.click({ target });
  await new Promise(resolve => setTimeout(resolve, 0));
}

test('sales adapter restricts paths, date fields, paging and passes opaque cursors unchanged', async () => {
  const calls = [], root = {};
  vm.runInNewContext(apiSource, { window: root, Object, String, TypeError, Number, Math, Date });
  const api = root.siyueJavaSalesApi.create({ request: async (...args) => { calls.push(args); return {}; } });
  await api.activity({ employeeId: 'STAFF-1', from: '2026-10-01', to: '2026-10-17', cursor: 'ACTIVITY opaque/id' });
  assert.equal(calls[0][1], 'crm/sales/activity');
  assert.equal(calls[0][2].query.cursor, 'ACTIVITY opaque/id');
  assert.throws(() => api.report({ from: 'not-a-date' }), /date/);
  assert.throws(() => api.followups({ limit: 201 }), /page size/);
  assert.equal(typeof api.request, 'undefined');
});

test('sales metrics show exact amounts, assignment cohort and low-sample suppression', async () => {
  const app = setup();
  await app.workspace.open('salesMetrics');
  assert.match(app.html, /首笔实收冻结归属/);
  assert.match(app.html, /样本不足 · 不参与排名/);
  assert.match(app.html, /125\.50/);
  assert.match(app.html, /115\.50/);
  assert.equal(app.requests[0][1], 'crm/reports/sales');
  assert.equal(app.requests[0][2].query.from, '2026-10-01');
});

test('activity-only authorization can open historical records but cannot request report data', async () => {
  const app = setup(['sales.activity.read'], 'own');
  await app.workspace.open('salesActivity');
  assert.match(app.html, /销售活动/);
  assert.equal(app.requests[0][1], 'crm/sales/activity');
  assert.equal(app.requests[0][2].query.employeeId, 'STAFF-1');
  await assert.rejects(app.workspace.open('salesMetrics'), /销售统计权限/);
  await click(app, 'followups');
  assert.equal(app.requests.at(-1)[1], 'crm/sales/followups');
});
