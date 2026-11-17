'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { activateImportedUser, main, preflightExistingUser, validatePositionView, validatePrincipalView } = require('./apply-migration.cjs');
const { defaultRolePolicies } = require('../backend/src/auth-policy.cjs');
const { buildPlan, writePackage } = require('./plan.cjs');

const root = 'http://127.0.0.1:18443';
const realm = 'synthetic-realm';
const sourceSha = 'a'.repeat(64);
const nativeFetch = global.fetch;
test.afterEach(() => { global.fetch = nativeFetch; });

function representation(overrides = {}) {
  return {
    id: 'synthetic-staff-1', username: 'staff-1', enabled: false,
    attributes: {
      legacy_user_id: ['synthetic-staff-1'], legacy_java_credential_gate_managed: ['true'],
      legacy_role_ids: ['sales'], legacy_scope: ['campus'],
      legacy_must_change_password: ['true'], legacy_effective_permissions: ['crm.lead.read'],
      legacy_target_enabled: ['true'], legacy_migration_state: ['pending'],
      legacy_source_sha256: [sourceSha], legacy_campus_ids: ['CAMPUS_SYNTHETIC'],
      legacy_denied_permissions: [], legacy_permission_mapping_version: ['siyue-permission-map-v1'],
      legacy_source_role_fingerprint: ['1'.repeat(64)], legacy_source_direct_permissions: [],
      legacy_source_denied_permissions: [], legacy_credential_change_ticket: ['synthetic-ticket-' + 't'.repeat(32)],
      legacy_credential_change_generation: ['1'],
    }, requiredActions: ['UPDATE_PASSWORD'], ...overrides,
  };
}
function response(status, body) {
  return { ok: status >= 200 && status < 300, status,
    text: async () => body === undefined ? '' : JSON.stringify(body) };
}

test('preflight accepts an exact disabled staged mapping with the legacy scrypt credential', async () => {
  const rep = representation();
  const current = structuredClone(rep);
  global.fetch = async url => String(url).includes('/credentials')
    ? response(200, [{ type: 'password', credentialData: { algorithm: 'siyue-scrypt-v1' } }])
    : response(200, [current]);
  assert.equal(await preflightExistingUser(root, realm, 'synthetic-admin-token', rep, true), true);
});

test('preflight rejects changed grants and credentials that do not retain the source password', async () => {
  const rep = representation();
  const changed = structuredClone(rep);
  changed.attributes.legacy_effective_permissions = ['crm.lead.read', 'crm.lead.write'];
  global.fetch = async url => String(url).includes('/credentials')
    ? response(200, [{ type: 'password', credentialData: { algorithm: 'siyue-scrypt-v1' } }])
    : response(200, [changed]);
  await assert.rejects(preflightExistingUser(root, realm, 'token', rep, true), /账号与迁移身份映射冲突/);

  global.fetch = async url => String(url).includes('/credentials')
    ? response(200, [{ type: 'password', credentialData: { algorithm: 'argon2' } }])
    : response(200, [structuredClone(rep)]);
  await assert.rejects(preflightExistingUser(root, realm, 'token', rep, true), /未设置密码凭据/);
});

test('preflight rejects a stale completed account after its required password action was cleared', async () => {
  const rep = representation();
  const current = structuredClone(rep);
  current.attributes.legacy_migration_state = ['complete'];
  current.enabled = true;
  current.requiredActions = [];
  global.fetch = async url => String(url).includes('/credentials')
    ? response(200, [{ type: 'password', credentialData: { algorithm: 'argon2' } }])
    : response(200, [current]);
  await assert.rejects(preflightExistingUser(root, realm, 'token', rep, true), /强制改密状态/);
});

test('Keycloak activation happens only after a pending account has a Java bound view', async () => {
  const rep = representation();
  const current = structuredClone(rep);
  const calls = [];
  global.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET' });
    if (options.method === 'PUT') {
      const payload = JSON.parse(options.body);
      assert.equal(payload.enabled, true);
      assert.deepEqual(payload.attributes.legacy_migration_state, ['complete']);
      Object.assign(current, payload);
      return response(204);
    }
    return response(200, current);
  };
  assert.equal(await activateImportedUser(root, realm, 'token', rep), true);
  assert.equal(current.enabled, true);
  assert.deepEqual(current.attributes.legacy_migration_state, ['complete']);
  assert.equal(calls.filter(call => call.method === 'PUT').length, 1);
  assert.equal(await activateImportedUser(root, realm, 'token', rep), false);
});

test('bound Java view must exactly match the manifest permission, scope, campus, status and stable ID', () => {
  const step = { principalId: 'synthetic-staff-1', body: {
    scope: 'campus', active: true, permissionCodes: [], expectedEffectivePermissionCodes: ['crm.lead.read'],
    deniedPermissionCodes: [], campusIds: ['CAMPUS_SYNTHETIC'], mustChangePassword: true,
  } };
  const view = { principalId: step.principalId, scope: 'campus', active: true, version: 2,
    credentialChangeRequired: true,
    permissionCodes: [], effectivePermissionCodes: ['crm.lead.read'], deniedPermissionCodes: [],
    campusIds: ['CAMPUS_SYNTHETIC'] };
  assert.doesNotThrow(() => validatePrincipalView(view, step, true));
  assert.doesNotThrow(() => validatePrincipalView({ ...view, scope: 'own', active: false, version: 1,
    credentialChangeRequired: false,
    permissionCodes: [], effectivePermissionCodes: [], deniedPermissionCodes: [], campusIds: [] }, step, false));
  assert.throws(() => validatePrincipalView({ ...view, permissionCodes: ['crm.lead.write'] }, step, true), /与可信迁移清单不一致/);
  assert.throws(() => validatePrincipalView({ ...view, active: false }, step, true), /与可信迁移清单不一致/);
});

test('full apply keeps Keycloak identities disabled until every Java bind commits and supports replay', async () => {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-identity-apply-'));
  fs.chmodSync(rootDir, 0o700);
  const packageDir = path.join(rootDir, 'package');
  fs.mkdirSync(packageDir, { mode: 0o700 });
  const rolePolicies = defaultRolePolicies();
  rolePolicies.auditor = { version: 1, permissions: ['course.read'] };
  const source = {
    schemaVersion: 1,
    users: [
      { id: 'synthetic-active', username: 'active-user', name: 'Synthetic Active', roleIds: ['auditor'],
        additionalPermissions: [], deniedPermissions: [], scope: { type: 'campus', campusIds: ['CAMPUS_SYNTHETIC'] },
        passwordHash: { algorithm: 'scrypt', salt: '1'.repeat(64), hash: '9'.repeat(128) },
        version: 1, mustChangePassword: true, disabled: false },
      { id: 'synthetic-disabled', username: 'disabled-user', name: 'Synthetic Disabled', roleIds: ['auditor'],
        additionalPermissions: [], deniedPermissions: [], scope: { type: 'own', campusIds: [] },
        passwordHash: { algorithm: 'scrypt', salt: '2'.repeat(64), hash: '8'.repeat(128) },
        version: 1, mustChangePassword: false, disabled: true },
    ], sessions: [], audit: [], rolePolicies,
  };
  const sourceSha = 'b'.repeat(64);
  const plan = buildPlan(source, 'https://auth.example.invalid/realms/synthetic', 'synthetic', sourceSha);
  writePackage(packageDir, plan);
  fs.writeFileSync(path.join(packageDir, 'migration-manifest.json'), JSON.stringify({
    sourceSha256: sourceSha, issuer: 'https://auth.example.invalid/realms/synthetic', realm: 'synthetic',
    keycloakArtifact: 'keycloak-partial-import.json', javaArtifact: 'java-principal-steps.json',
    trustedManifestArtifact: 'trusted-manifest.json',
  }), { mode: 0o600 });

  const users = new Map();
  const positions = new Map();
  const views = new Map();
  const events = [];
  const base = 'http://localhost:18443';
  const savedArgv = process.argv;
  const envNames = ['SIYUE_MIGRATION_KEYCLOAK_ADMIN_URL', 'SIYUE_MIGRATION_JAVA_API_URL',
    'SIYUE_MIGRATION_KEYCLOAK_ADMIN_TOKEN', 'SIYUE_MIGRATION_JAVA_ADMIN_TOKEN',
    'SIYUE_MIGRATION_CHECKPOINT', 'SIYUE_MIGRATION_SOURCE_SHA256'];
  const savedEnv = Object.fromEntries(envNames.map(name => [name, process.env[name]]));
  const savedFetch = global.fetch;
  global.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    const payload = options.body ? JSON.parse(options.body) : null;
    const method = options.method || 'GET';
    const response = (status, value) => ({ ok: status >= 200 && status < 300, status,
      text: async () => value === undefined ? '' : JSON.stringify(value) });
    if (url.pathname.endsWith('/partialImport') && method === 'POST') {
      assert.ok(payload.users.every(user => user.enabled === false));
      for (const user of payload.users) users.set(user.id, user);
      events.push('keycloak-staged'); return response(200, { imported: payload.users.length });
    }
    if (url.pathname === '/api/v2/system/positions' && method === 'POST') {
      positions.set(payload.positionId, { id: payload.positionId, name: payload.name, description: payload.description,
        directoryKind: payload.directoryKind, active: true, version: 1, permissionCodes: payload.permissionCodes, principalIds: [] });
      events.push('position-provisioned');
      return response(200, { code: 'OK', data: positions.get(payload.positionId) });
    }
    if (url.pathname.endsWith('/users') && method === 'GET') {
      const username = url.searchParams.get('username');
      return response(200, [...users.values()].filter(user => user.username === username));
    }
    const userMatch = url.pathname.match(/\/users\/([^/]+)(?:\/(credentials))?$/);
    if (userMatch) {
      const userId = decodeURIComponent(userMatch[1]);
      const user = users.get(userId);
      if (!user) return response(404, { error: 'not found' });
      if (userMatch[2] === 'credentials' && method === 'GET') {
        return response(200, [{ type: 'password', credentialData: { algorithm: 'siyue-scrypt-v1' } }]);
      }
      if (method === 'GET') return response(200, user);
      if (method === 'PUT') {
        assert.ok(events.includes('all-java-bound'));
        assert.equal(payload.attributes.legacy_migration_state[0], 'complete');
        users.set(userId, payload); events.push('keycloak-finalized'); return response(204);
      }
    }
    const bindMatch = url.pathname.match(/\/principals\/([^/]+)\/(prepare|bind)$/);
    if (bindMatch && method === 'POST') {
      const id = decodeURIComponent(bindMatch[1]);
      if (bindMatch[2] === 'prepare') {
        if (!events.includes('all-java-bound')) assert.ok([...users.values()].every(user => user.enabled === false));
        events.push('prepared-' + id);
        const view = { principalId: id, scope: 'own', active: false, version: 1, credentialChangeRequired: false, permissionCodes: [],
          effectivePermissionCodes: [], deniedPermissionCodes: [], campusIds: [] };
        return response(200, { code: 'OK', data: view });
      }
      if (!events.includes('all-java-bound')) assert.ok([...users.values()].every(user => user.enabled === false));
      assert.ok(plan.principalManifest.principals.every(step => events.includes('prepared-' + step.principalId)));
      assert.equal(payload.expectedVersion, 1);
      const view = { principalId: id, scope: payload.scope, active: payload.active, version: 2,
        credentialChangeRequired: payload.mustChangePassword,
        permissionCodes: payload.permissionCodes, effectivePermissionCodes: payload.expectedEffectivePermissionCodes,
        deniedPermissionCodes: payload.deniedPermissionCodes, campusIds: payload.campusIds };
      views.set(id, view);
      if (views.size === plan.principalManifest.principals.length) events.push('all-java-bound');
      return response(200, { code: 'OK', data: view });
    }
    return response(404, { error: 'unhandled test route' });
  };
  Object.assign(process.env, {
    SIYUE_MIGRATION_KEYCLOAK_ADMIN_URL: base,
    SIYUE_MIGRATION_JAVA_API_URL: base,
    SIYUE_MIGRATION_KEYCLOAK_ADMIN_TOKEN: 'synthetic-keycloak-token',
    SIYUE_MIGRATION_JAVA_ADMIN_TOKEN: 'synthetic-java-token',
    SIYUE_MIGRATION_CHECKPOINT: 'synthetic-checkpoint-value-123456789',
    SIYUE_MIGRATION_SOURCE_SHA256: sourceSha,
  });
  process.argv = ['node', 'apply-migration.cjs', packageDir];
  try {
    await main();
    assert.deepEqual(events.filter(event => event === 'keycloak-staged').length, 2);
    assert.ok(events.indexOf('all-java-bound') < events.indexOf('keycloak-finalized'));
    assert.equal(users.get('synthetic-active').enabled, true);
    assert.equal(users.get('synthetic-active').attributes.legacy_migration_state[0], 'complete');
    assert.deepEqual(users.get('synthetic-active').requiredActions, ['UPDATE_PASSWORD']);
    assert.equal(users.get('synthetic-disabled').enabled, false);
    assert.equal(users.get('synthetic-disabled').attributes.legacy_migration_state[0], 'complete');
    await main();
    assert.equal(events.filter(event => event === 'keycloak-staged').length, 2);
    assert.equal(events.filter(event => event === 'keycloak-finalized').length, 2);
  } finally {
    process.argv = savedArgv;
    global.fetch = savedFetch;
    for (const name of envNames) {
      if (savedEnv[name] === undefined) delete process.env[name];
      else process.env[name] = savedEnv[name];
    }
    fs.rmSync(rootDir, { recursive: true, force: true });
  }
});
