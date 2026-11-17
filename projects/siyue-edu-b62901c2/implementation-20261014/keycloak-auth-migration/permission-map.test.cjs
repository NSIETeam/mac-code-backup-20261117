'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { PERMISSION_MAPPING_VERSION, mapPermissionCodes, readPermissionMap } = require('./permission-map.cjs');

test('versioned permission mapping is one-to-one and preserves deny mappings', () => {
  const mapping = readPermissionMap();
  assert.equal(mapping.version, PERMISSION_MAPPING_VERSION);
  assert.deepEqual(mapPermissionCodes(['student.read', 'course.read'], mapping), ['course.read', 'student.read']);
  assert.deepEqual(mapPermissionCodes(['student.read'], mapping), ['student.read']);
  assert.deepEqual(mapPermissionCodes(['session.attendance', 'session.reschedule.request'], mapping),
    ['session.attendance', 'session.reschedule.request']);
  assert.deepEqual(mapPermissionCodes(['lead.read', 'student.archive', 'schedule.read', 'order.read',
    'order.confirmReceipt', 'refund.read', 'refund.approve', 'refund.submit'], mapping),
  ['lead.read', 'order.confirmReceipt', 'order.read', 'refund.approve', 'refund.read', 'refund.submit',
    'schedule.read', 'student.archive']);
  assert.deepEqual(mapPermissionCodes(['dashboard.read', 'staff.read', 'staff.archive', 'teacher.read', 'teacher.archive'], mapping),
    ['dashboard.read', 'staff.archive', 'staff.read', 'teacher.archive', 'teacher.read']);
});

test('exact Java academic grants are mapped without borrowing broader permissions', () => {
  const codes = ['class.relocate', 'course.publish', 'product.publish', 'dashboard.read', 'enrollment.batchCreate', 'enrollment.withdraw',
    'session.record', 'session.substitute', 'store.design', 'lead.read', 'schedule.read', 'student.archive',
    'order.read', 'order.confirmReceipt', 'refund.read', 'refund.approve', 'refund.submit',
    'staff.read', 'staff.archive', 'teacher.read', 'teacher.archive'];
  assert.deepEqual(mapPermissionCodes(codes), [...codes].sort());
  const fs = require('node:fs');
  const path = require('node:path');
  const catalogSource = fs.readFileSync(path.resolve(__dirname,
    '../backend-java/src/main/java/cn/siyue/core/AccessPermissionCatalog.java'), 'utf8');
  const permissions = catalogSource.match(/Set\.of\(([\s\S]*?)\);/)[1];
  for (const code of codes) assert.match(permissions, new RegExp(`\\"${code}\\"`));
  assert.throws(() => mapPermissionCodes(['employee.create']), /尚未覆盖旧权限/);
  assert.throws(() => mapPermissionCodes(['role.grant']), /尚未覆盖旧权限/);
});

test('legacy metrics, policy, audit, settings, and storefront grants retain distinct Java capabilities', () => {
  const codes = ['audit.query', 'leadPolicy.preview', 'leadPolicy.publish', 'settings.read', 'store.read', 'teacher.metrics.read'];
  assert.deepEqual(mapPermissionCodes(codes), [...codes].sort());
  const fs = require('node:fs');
  const path = require('node:path');
  const catalogSource = fs.readFileSync(path.resolve(__dirname,
    '../backend-java/src/main/java/cn/siyue/core/AccessPermissionCatalog.java'), 'utf8');
  const permissions = catalogSource.match(/Set\.of\(([\s\S]*?)\);/)[1];
  for (const code of codes) assert.match(permissions, new RegExp(`\\"${code}\\"`));
});

test('mapping validation rejects many-to-one permission collisions', () => {
  const fs = require('node:fs');
  const os = require('node:os');
  const path = require('node:path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'permission-map-'));
  const file = path.join(dir, 'map.json');
  try {
    fs.writeFileSync(file, JSON.stringify({ version: PERMISSION_MAPPING_VERSION, mappings: [
      { source: 'legacy.read', target: 'student.read', evidence: 'source evidence; target evidence' },
      { source: 'legacy.view', target: 'student.read', evidence: 'source evidence; target evidence' },
    ] }));
    assert.throws(() => readPermissionMap(file), /多对一冲突/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('Java runtime registry has the same versioned one-to-one mapping as the migration planner', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const source = fs.readFileSync(path.resolve(__dirname,
    '../backend-java/src/main/java/cn/siyue/core/LegacyPermissionMappingCatalog.java'), 'utf8');
  const entries = [...source.matchAll(/Map\.entry\("([^"]+)",\s*"([^"]+)"\)/g)]
    .map(([, from, to]) => [from, to]).sort(([a], [b]) => a.localeCompare(b));
  const planned = readPermissionMap();
  assert.deepEqual(entries, [...planned.bySource.values()].map(item => [item.source, item.target])
    .sort(([a], [b]) => a.localeCompare(b)));
});
