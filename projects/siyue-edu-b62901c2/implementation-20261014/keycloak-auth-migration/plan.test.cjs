'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const test = require('node:test');
const { defaultRolePolicies } = require('../backend/src/auth-policy.cjs');
const { buildPlan, inspectSource, readPrivateSource, writePackage } = require('./plan.cjs');
const { auditSource } = require('./audit-source.cjs');

const hash = '9'.repeat(128);
const salt = '1'.repeat(64);
function user(id, extra = {}) {
  return { id, username: `test-${id}`, name: `Synthetic ${id}`, employeeNo: `EMP-${id}`, roleIds: ['auditor'],
    additionalPermissions: [], deniedPermissions: [], scope: { type: 'campus', campusIds: ['CAMPUS_SYNTHETIC'] },
    passwordHash: { algorithm: 'scrypt', salt, hash }, version: 1, mustChangePassword: false, disabled: false,
    ...extra };
}
function source(users = [user('legacy-active'), user('legacy-disabled', { disabled: true, mustChangePassword: true })]) {
  const rolePolicies = defaultRolePolicies();
  rolePolicies.auditor = { version: 7, permissions: ['course.read', 'student.read'] };
  return { schemaVersion: 1, users, sessions: [], audit: [], rolePolicies };
}

function withMissingPermissionMapping(sourceCode, callback) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-auth-map-fixture-'));
  fs.chmodSync(root, 0o700);
  try {
    const migrationDir = path.join(root, 'keycloak-auth-migration');
    const policyDir = path.join(root, 'backend', 'src');
    const contractsDir = path.join(root, 'backend', 'contracts');
    const javaDir = path.join(root, 'backend-java', 'src', 'main', 'java', 'cn', 'siyue', 'core');
    for (const dir of [migrationDir, policyDir, contractsDir, javaDir]) fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    for (const name of ['plan.cjs', 'permission-map.cjs', 'audit-source.cjs', 'permission-map-v1.json']) {
      fs.copyFileSync(path.join(__dirname, name), path.join(migrationDir, name));
    }
    fs.copyFileSync(path.join(__dirname, '../backend/src/auth-policy.cjs'), path.join(policyDir, 'auth-policy.cjs'));
    fs.copyFileSync(path.join(__dirname, '../backend/contracts/operations.json'), path.join(contractsDir, 'operations.json'));
    fs.copyFileSync(path.join(__dirname,
      '../backend-java/src/main/java/cn/siyue/core/AccessPermissionCatalog.java'),
    path.join(javaDir, 'AccessPermissionCatalog.java'));
    const mappingFile = path.join(migrationDir, 'permission-map-v1.json');
    const mapping = JSON.parse(fs.readFileSync(mappingFile, 'utf8'));
    mapping.mappings = mapping.mappings.filter(item => item.source !== sourceCode);
    fs.writeFileSync(mappingFile, JSON.stringify(mapping), { mode: 0o600 });
    const planner = require(path.join(migrationDir, 'plan.cjs'));
    const audit = require(path.join(migrationDir, 'audit-source.cjs'));
    return callback({ ...planner, auditSource: audit.auditSource, policyFile: path.join(policyDir, 'auth-policy.cjs'), root });
  } finally {
    for (const key of Object.keys(require.cache)) {
      if (key.startsWith(root + path.sep)) delete require.cache[key];
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('preserves identity, disabled state, forced password change, exact scope and effective permissions', () => {
  const plan = buildPlan(source(), 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64));
  const active = plan.realmRepresentation.users[0];
  const disabled = plan.realmRepresentation.users[1];
  assert.equal(active.id, 'legacy-active');
  assert.equal(active.enabled, false);
  assert.deepEqual(active.attributes.legacy_target_enabled, ['true']);
  assert.deepEqual(active.attributes.legacy_migration_state, ['pending']);
  assert.deepEqual(active.attributes.legacy_source_sha256, ['a'.repeat(64)]);
  assert.equal(disabled.enabled, false);
  assert.deepEqual(disabled.attributes.legacy_target_enabled, ['false']);
  assert.deepEqual(disabled.requiredActions, ['UPDATE_PASSWORD']);
  assert.equal(disabled.attributes.legacy_credential_change_ticket[0].length >= 32, true);
  assert.equal(plan.principalManifest.principals[1].body.mustChangePassword, true);
  assert.equal(plan.principalManifest.principals[1].body.credentialChangeTicket,
    disabled.attributes.legacy_credential_change_ticket[0]);
  assert.match(plan.trustedManifest.principals['legacy-disabled'].credentialChangeTicketHash, /^[0-9a-f]{64}$/);
  assert.deepEqual(active.attributes.legacy_user_id, ['legacy-active']);
  assert.deepEqual(active.attributes.legacy_java_credential_gate_managed, ['true']);
  assert.deepEqual(active.attributes.legacy_scope, ['campus']);
  assert.deepEqual(active.attributes.legacy_campus_ids, ['CAMPUS_SYNTHETIC']);
  const credential = JSON.parse(active.credentials[0].secretData);
  assert.equal(Buffer.from(credential.salt, 'base64').toString('utf8'), salt);
  assert.equal(credential.value, hash);
  assert.deepEqual(plan.principalManifest.principals[0].body.sourcePermissionCodes, ['course.read', 'student.read']);
  assert.deepEqual(plan.principalManifest.principals[0].body.permissionCodes, []);
  assert.deepEqual(plan.principalManifest.principals[0].body.expectedEffectivePermissionCodes, ['course.read', 'student.read']);
  assert.deepEqual(plan.principalManifest.positions[0].body.permissionCodes, ['course.read', 'student.read']);
  assert.deepEqual(plan.principalManifest.principals[0].body.positionIds, ['LEGACY_ROLE_auditor']);
  assert.equal(plan.principalManifest.principals[0].body.permissionMappingVersion, 'siyue-permission-map-v1');
  assert.equal(plan.principalManifest.principals[1].body.active, false);
  assert.equal(plan.principalManifest.principals[0].body.expectedVersion, null);
  assert.equal(plan.principalManifest.principals[0].expectedOidcSubject, 'legacy-active');
  assert.equal(plan.principalManifest.principals[0].prepareBody.legacyUserId, 'legacy-active');
  assert.equal(plan.principalManifest.principals[0].preparePath.endsWith('/legacy-active/prepare'), true);
  assert.deepEqual(plan.trustedManifest.principals['legacy-active'], {
    issuer: 'https://auth.example.invalid/realms/siyue', subject: 'legacy-active', scope: 'campus', active: true,
    sourcePermissionCodes: ['course.read', 'student.read'], permissionCodes: [],
    expectedEffectivePermissionCodes: ['course.read', 'student.read'], sourceDirectPermissionCodes: [],
    sourceDeniedPermissionCodes: [], campusIds: ['CAMPUS_SYNTHETIC'],
    legacyRoleIds: ['auditor'], positionIds: ['LEGACY_ROLE_auditor'], deniedPermissionCodes: [],
    permissionMappingVersion: 'siyue-permission-map-v1',
    sourceRoleFingerprint: plan.principalManifest.principals[0].body.sourceRoleFingerprint,
    mustChangePassword: false, credentialChangeTicketHash: null,
    directory: { displayName: 'Synthetic legacy-active', employeeNo: 'EMP-legacy-active', archivedAt: null, archivedBy: null, archiveReason: null, username: 'test-legacy-active' },
  });
});

test('rejects unsupported permissions and identity collisions before building output', () => {
  const unsupported = source(); unsupported.rolePolicies.auditor.permissions.push('leadPolicy.preview');
  withMissingPermissionMapping('leadPolicy.preview', ({ buildPlan: fixtureBuildPlan }) => {
    assert.throws(() => fixtureBuildPlan(unsupported, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64)),
      /Java 尚未支持权限 leadPolicy.preview/);
  });
  const collision = source([user('same'), user('same')]);
  assert.throws(() => buildPlan(collision, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64)),
    /身份或登录名碰撞/);
});

test('keeps source role grants inherited, maps direct grants and denials separately, and audits a role fingerprint', () => {
  const input = source([user('legacy-denied', {
    additionalPermissions: ['course.read'], deniedPermissions: ['student.read'],
  })]);
  const plan = buildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'c'.repeat(64));
  const step = plan.principalManifest.principals[0];
  assert.deepEqual(step.body.sourcePermissionCodes, ['course.read']);
  assert.deepEqual(step.body.sourceDirectPermissionCodes, ['course.read']);
  assert.deepEqual(step.body.permissionCodes, ['course.read']);
  assert.deepEqual(step.body.sourceDeniedPermissionCodes, ['student.read']);
  assert.deepEqual(step.body.deniedPermissionCodes, ['student.read']);
  assert.deepEqual(step.body.expectedEffectivePermissionCodes, ['course.read']);
  assert.notEqual(step.body.sourceRoleFingerprint, '0'.repeat(64));
  assert.deepEqual(plan.principalManifest.positions[0].body.permissionCodes, ['course.read', 'student.read']);
});

test('source audit reports only aggregate status and exact unsupported grant counts', () => {
  const input = source([user('synthetic-audit', { disabled: true, mustChangePassword: true })]);
  input.rolePolicies.auditor = { version: 4, permissions: ['course.read', 'leadPolicy.preview'] };
  withMissingPermissionMapping('leadPolicy.preview', ({ buildPlan: fixtureBuildPlan,
    inspectSource: fixtureInspectSource, auditSource: fixtureAuditSource, policyFile, root }) => {
    const inspected = fixtureInspectSource(input).summary;
    assert.equal(inspected.accountCount, 1);
    assert.equal(inspected.disabledCount, 1);
    assert.equal(inspected.mustChangePasswordCount, 1);
    assert.deepEqual(inspected.unsupportedPermissionCounts, { 'leadPolicy.preview': 1 });
    assert.equal(JSON.stringify(inspected).includes('synthetic-audit'), false);
    assert.throws(() => fixtureBuildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64)),
      /Java 尚未支持权限 leadPolicy.preview/);

    const sourceDir = path.join(root, 'source'); fs.mkdirSync(sourceDir, { mode: 0o700 });
    const sourceFile = path.join(sourceDir, 'auth-store.json');
    fs.writeFileSync(sourceFile, JSON.stringify(input), { mode: 0o600 });
    const policySha = crypto.createHash('sha256').update(fs.readFileSync(policyFile)).digest('hex');
    const report = fixtureAuditSource(sourceFile, policySha, 1);
    assert.equal(report.migrationReady, false);
    assert.deepEqual(report.unsupportedPermissionCounts, { 'leadPolicy.preview': 1 });
    assert.deepEqual(report.credentialFormats, { scrypt: 1 });
    assert.equal(JSON.stringify(report).includes('9'.repeat(128)), false);
    assert.throws(() => fixtureAuditSource(sourceFile, 'f'.repeat(64), 1), /权限策略不匹配/);
  });
});

test('accepts newly registered exact legacy lead action permissions without widening them', () => {
  const input = source();
  input.rolePolicies.auditor.permissions.push(
    'lead.create', 'lead.follow', 'lead.assign', 'lead.transfer', 'lead.release', 'settings.update', 'leadPolicy.preview');
  const plan = buildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'b'.repeat(64));
  assert.deepEqual(plan.principalManifest.principals[0].body.expectedEffectivePermissionCodes,
    ['course.read', 'lead.assign', 'lead.create', 'lead.follow', 'lead.release', 'lead.transfer', 'leadPolicy.preview',
      'settings.update', 'student.read']);
});

test('requires private source and output files, and package writes are deterministic', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-auth-migration-'));
  fs.chmodSync(root, 0o700);
  const sourceDir = path.join(root, 'source'); fs.mkdirSync(sourceDir, { mode: 0o700 });
  const sourceFile = path.join(sourceDir, 'auth-store.json');
  fs.writeFileSync(sourceFile, JSON.stringify(source()), { mode: 0o600 });
  assert.equal(readPrivateSource(sourceFile).users.length, 2);
  const sourceSha = crypto.createHash('sha256').update(fs.readFileSync(sourceFile)).digest('hex');
  const plan = buildPlan(source(), 'https://auth.example.invalid/realms/siyue', 'siyue', sourceSha);
  const output = path.join(root, 'package'); fs.mkdirSync(output, { mode: 0o700 });
  writePackage(output, plan); const first = fs.readFileSync(path.join(output, 'keycloak-partial-import.json'));
  writePackage(output, plan); assert.deepEqual(fs.readFileSync(path.join(output, 'keycloak-partial-import.json')), first);
  for (const name of ['keycloak-partial-import.json', 'java-principal-steps.json', 'trusted-manifest.json']) {
    assert.equal(fs.statSync(path.join(output, name)).mode & 0o777, 0o600);
  }
  assert.throws(() => readPrivateSource(sourceFile.replace('auth-store.json', 'missing.json')));
  fs.rmSync(root, { recursive: true, force: true });
});


test('reserved administrator source is isolated while an unmapped ordinary grant blocks the full plan', () => {
  const input = source([user('synthetic-superadmin', { roleIds: ['superadmin'],
    scope: { type: 'all', campusIds: [] } }), user('synthetic-ordinary')]);
  input.rolePolicies.auditor.permissions.push('leadPolicy.preview');
  withMissingPermissionMapping('leadPolicy.preview', ({ inspectSource: fixtureInspectSource,
    buildPlan: fixtureBuildPlan }) => {
    const audit = fixtureInspectSource(input);
    const admin = audit.planned[0];
    assert.deepEqual(admin.positionIds, []);
    assert.equal(admin.scope.type, 'all');
    assert.equal(admin.directPermissionCodes.includes('system.access.manage'), true);
    assert.equal(audit.planned[1].directPermissionCodes.includes('system.access.manage'), false);
    const role = audit.summary.roleTemplates.find(item => item.roleId === 'superadmin');
    assert.equal(role.positionId, null);
    assert.equal(role.sourcePermissionCodes.includes('role.grant'), true);
    assert.equal(role.sourcePermissionCodes.includes('employee.create'), true);
    assert.equal(audit.summary.positionTemplateCount, 1);
    assert.equal(Object.hasOwn(audit.summary.unsupportedPermissionCounts, 'role.grant'), false);
    assert.equal(Object.hasOwn(audit.summary.unsupportedPermissionCounts, 'employee.create'), false);
    assert.equal(audit.summary.unsupportedPermissionCounts['leadPolicy.preview'], 2);
    assert.throws(() => fixtureBuildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64)),
      /Java 尚未支持权限 leadPolicy.preview/);
  });
});

test('reserved grants cannot be smuggled through direct additions or editable ordinary role policies', () => {
  const direct = source([user('synthetic-invalid', { additionalPermissions: ['role.grant'] })]);
  assert.throws(() => inspectSource(direct), /保留权限/);
  const role = source(); role.rolePolicies.auditor.permissions.push('employee.create');
  assert.throws(() => inspectSource(role), /角色权限存储格式无效/);
});

test('directory facts and inactive historical archiver are preserved only from the trusted source', () => {
  const input = source([user('archived', { disabled: true, archivedAt: '2026-10-02T00:00:00Z',
    archivedBy: 'archiver', archiveReason: 'Synthetic archive' }), user('archiver', { disabled: true })]);
  const plan = buildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64));
  const profile = plan.principalManifest.principals[0].body.directory;
  assert.deepEqual(profile, { displayName: 'Synthetic archived', employeeNo: 'EMP-archived',
    archivedAt: '2026-10-02T00:00:00Z', archivedBy: 'archiver', archiveReason: 'Synthetic archive', username: 'test-archived' });
  assert.deepEqual(plan.trustedManifest.principals.archived.directory, profile);
  assert.equal(plan.principalManifest.principals[1].body.active, false);
  input.users[0].archivedBy = 'missing';
  assert.throws(() => buildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64)), /归档信息/);
});

test('employee identifiers retain exact case and only exact teacher roles classify positions as teachers', () => {
  const input = source([user('one', { employeeNo: 'Aa' }), user('two', { employeeNo: 'aa', roleIds: ['teacher'] })]);
  input.rolePolicies.teacher = { version: 1, permissions: ['course.read'] };
  const plan = buildPlan(input, 'https://auth.example.invalid/realms/siyue', 'siyue', 'a'.repeat(64));
  assert.deepEqual(plan.principalManifest.principals.map(step => step.body.directory.employeeNo), ['Aa', 'aa']);
  assert.equal(plan.principalManifest.positions.find(step => step.roleId === 'teacher').body.directoryKind, 'teacher');
  assert.equal(plan.principalManifest.positions.find(step => step.roleId === 'auditor').body.directoryKind, 'staff');
  input.users[1].employeeNo = 'Aa';
  assert.throws(() => inspectSource(input), /重复员工编号/);
});
