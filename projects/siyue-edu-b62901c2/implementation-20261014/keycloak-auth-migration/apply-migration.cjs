#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`缺少环境配置 ${name}`);
  return value;
}
function privateJson(file) {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || (stat.mode & 0o077)) throw new Error('迁移包文件必须为0600普通文件');
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function localUrl(name) {
  const url = new URL(required(name));
  if (!['http:', 'https:'].includes(url.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
    throw new Error(`${name} 仅允许指向本机回环地址`);
  }
  return url.toString().replace(/\/$/, '');
}
async function request(url, token, options = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', headers: {
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers || {}),
  } });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { /* keep response content out of logs */ }
  if (!response.ok) throw new Error(`远端请求失败: HTTP ${response.status}`);
  return { status: response.status, body };
}
function sameList(actual, expected) {
  return Array.isArray(actual) && actual.length === expected.length
    && [...actual].sort().every((item, index) => item === [...expected].sort()[index]);
}
function attributeMatches(user, name, expected) {
  return sameList(user.attributes?.[name], expected);
}
function credentialAlgorithm(credential) {
  let data = credential?.credentialData;
  if (typeof data === 'string') {
    try { data = JSON.parse(data); } catch { return null; }
  }
  return data?.algorithm || null;
}
async function userByUsername(kc, realm, token, username) {
  const url = new URL(`${kc}/admin/realms/${encodeURIComponent(realm)}/users`);
  url.searchParams.set('username', username); url.searchParams.set('exact', 'true');
  const { body } = await request(url, token);
  if (!Array.isArray(body)) throw new Error('Keycloak用户查询响应格式无效');
  if (body.length > 1) throw new Error('Keycloak登录名存在碰撞');
  return body[0] || null;
}
async function preflightExistingUser(kc, realm, token, rep, mustChange) {
  const current = await userByUsername(kc, realm, token, rep.username);
  if (!current) {
    const byId = await fetch(`${kc}/admin/realms/${encodeURIComponent(realm)}/users/${encodeURIComponent(rep.id)}`,
      { headers: { Authorization: `Bearer ${token}` }, redirect: 'error' });
    if (byId.status !== 404) {
      if (!byId.ok) throw new Error(`Keycloak身份碰撞检查失败: HTTP ${byId.status}`);
      throw new Error('Keycloak稳定身份编号已被其他用户占用');
    }
    return false;
  }
  const targetEnabled = rep.attributes.legacy_target_enabled?.[0] === 'true';
  const migrationState = current.attributes?.legacy_migration_state?.[0];
  const expectedEnabled = migrationState === 'complete' ? targetEnabled : false;
  const expectedAttributes = ['legacy_user_id', 'legacy_java_credential_gate_managed', 'legacy_role_ids', 'legacy_scope',
    'legacy_must_change_password', 'legacy_effective_permissions', 'legacy_target_enabled',
    'legacy_source_sha256', 'legacy_campus_ids', 'legacy_denied_permissions',
    'legacy_permission_mapping_version', 'legacy_source_role_fingerprint',
    'legacy_source_direct_permissions', 'legacy_source_denied_permissions',
    'legacy_credential_change_ticket', 'legacy_credential_change_generation'];
  if (current.id !== rep.id || current.username !== rep.username
      || !['pending', 'complete'].includes(migrationState) || Boolean(current.enabled) !== expectedEnabled
      || expectedAttributes.some(name => !sameList(current.attributes?.[name] || [], rep.attributes?.[name] || []))) {
    throw new Error('Keycloak现有账号与迁移身份映射冲突');
  }
  if (mustChange && !(current.requiredActions || []).includes('UPDATE_PASSWORD')) {
    throw new Error('Keycloak现有账号缺少强制改密状态');
  }
  const { body: credentials } = await request(`${kc}/admin/realms/${encodeURIComponent(realm)}/users/${encodeURIComponent(rep.id)}/credentials`, token);
  if (!Array.isArray(credentials) || !credentials.some(credential => credential.type === 'password'
      && (migrationState === 'pending' ? credentialAlgorithm(credential) === 'siyue-scrypt-v1'
        : ['siyue-scrypt-v1', 'argon2'].includes(credentialAlgorithm(credential))))) {
    throw new Error('Keycloak现有映射账号未设置密码凭据');
  }
  return true;
}
function validatePrincipalView(view, step, bound) {
  const validView = bound
    ? view?.scope === step.body.scope && view.active === step.body.active
      && sameList(view.permissionCodes, step.body.permissionCodes) && sameList(view.campusIds, step.body.campusIds)
      && sameList(view.effectivePermissionCodes, step.body.expectedEffectivePermissionCodes)
      && sameList(view.deniedPermissionCodes, step.body.deniedPermissionCodes)
      && view.credentialChangeRequired === Boolean(step.body.mustChangePassword)
      && view.version >= 2
    : view?.scope === 'own' && view.active === false && view.credentialChangeRequired === false
      && sameList(view.permissionCodes, [])
      && sameList(view.effectivePermissionCodes, []) && sameList(view.deniedPermissionCodes, [])
      && sameList(view.campusIds, []) && view.version === 1;
  if (!view || view.principalId !== step.principalId || !validView || !Number.isInteger(view.version)) {
    throw new Error('Java身份授权回读与可信迁移清单不一致');
  }
}
function validatePositionView(view, step) {
  if (!view || view.id !== step.positionId || view.name !== step.body.name
      || view.description !== step.body.description || view.active !== true
      || view.directoryKind !== (step.body.directoryKind || 'staff')
      || !Number.isInteger(view.version) || view.version < 1
      || !sameList(view.permissionCodes, step.body.permissionCodes)) {
    throw new Error('Java职位回读与可信迁移清单不一致');
  }
}
async function activateImportedUser(kc, realm, token, rep) {
  const url = `${kc}/admin/realms/${encodeURIComponent(realm)}/users/${encodeURIComponent(rep.id)}`;
  const { body: current } = await request(url, token);
  const targetEnabled = rep.attributes.legacy_target_enabled?.[0] === 'true';
  if (current.id !== rep.id || current.attributes?.legacy_source_sha256?.[0]
      !== rep.attributes.legacy_source_sha256?.[0]) throw new Error('激活Keycloak身份的来源校验失败');
  const state = current.attributes?.legacy_migration_state?.[0];
  if (state === 'complete') {
    if (Boolean(current.enabled) !== targetEnabled) throw new Error('Keycloak已完成身份的启停状态不一致');
    return false;
  }
  if (state !== 'pending' || current.enabled !== false) throw new Error('Keycloak账号未处于禁用迁移待完成状态');
  current.enabled = targetEnabled;
  current.attributes = { ...current.attributes, legacy_migration_state: ['complete'] };
  await request(url, token, { method: 'PUT', body: JSON.stringify(current) });
  const { body: verified } = await request(url, token);
  if (verified.id !== rep.id || Boolean(verified.enabled) !== targetEnabled
      || verified.attributes?.legacy_migration_state?.[0] !== 'complete') {
    throw new Error('Keycloak身份激活回读失败');
  }
  return true;
}

async function main() {
  const packageDir = path.resolve(process.argv[2] || '');
  if (!path.isAbsolute(process.argv[2] || '')) throw new Error('必须提供绝对路径迁移包目录');
  const dirStat = fs.lstatSync(packageDir);
  if (!dirStat.isDirectory() || dirStat.isSymbolicLink() || (dirStat.mode & 0o077)) throw new Error('迁移包目录必须为0700独立目录');
  const manifest = privateJson(path.join(packageDir, 'migration-manifest.json'));
  const keycloakPlan = privateJson(path.join(packageDir, manifest.keycloakArtifact));
  const javaPlan = privateJson(path.join(packageDir, manifest.javaArtifact));
  const trustedManifest = privateJson(path.join(packageDir, manifest.trustedManifestArtifact));
  if (!manifest.sourceSha256 || manifest.sourceSha256 !== javaPlan.sourceSha256
      || keycloakPlan.users?.length !== javaPlan.principals?.length
      || trustedManifest.sourceSha256 !== manifest.sourceSha256
      || !manifest.realm || !manifest.issuer) throw new Error('迁移包校验失败');
  if (trustedManifest.permissionMappingVersion !== javaPlan.permissionMappingVersion) {
    throw new Error('权限映射版本与可信清单不一致');
  }
  const roleSteps = javaPlan.positions || [];
  if (roleSteps.length !== Object.keys(trustedManifest.roles || {}).filter(role => role !== 'superadmin').length) throw new Error('旧角色职位清单数量不一致');
  const reservedRole = trustedManifest.roles?.superadmin;
  if (reservedRole && (reservedRole.roleId !== 'superadmin' || reservedRole.positionId !== null
      || reservedRole.sourceVersion !== 1 || !reservedRole.sourcePermissionCodes?.includes('role.grant')
      || !reservedRole.sourcePermissionCodes?.includes('employee.create')
      || !reservedRole.permissionCodes?.includes('system.access.manage'))) throw new Error('可信管理员角色无效');
  for (const step of roleSteps) {
    if (step.roleId === 'superadmin') throw new Error('管理员角色不得创建普通职位');
    const expected = trustedManifest.roles?.[step.roleId];
    if (!expected || expected.positionId !== step.positionId || expected.name !== step.body.name
        || expected.description !== step.body.description || expected.sourceVersion !== step.sourceVersion
        || expected.directoryKind !== step.body.directoryKind
        || expected.sourceFingerprint !== step.sourceFingerprint
        || expected.permissionMappingVersion !== step.permissionMappingVersion
        || !sameList(expected.sourcePermissionCodes, step.sourcePermissionCodes)
        || !sameList(expected.permissionCodes, step.body.permissionCodes)) {
      throw new Error('旧角色职位与可信迁移清单不一致');
    }
  }
  for (const step of javaPlan.principals) {
    const expected = trustedManifest.principals?.[step.principalId];
    if (!expected || expected.issuer !== step.body.issuer || expected.subject !== step.body.subject
        || expected.scope !== step.body.scope || expected.active !== step.body.active
        || JSON.stringify(expected.directory) !== JSON.stringify(step.body.directory)
        || !sameList(expected.sourcePermissionCodes, step.body.sourcePermissionCodes)
        || !sameList(expected.permissionCodes, step.body.permissionCodes)
        || !sameList(expected.expectedEffectivePermissionCodes, step.body.expectedEffectivePermissionCodes)
        || !sameList(expected.sourceDirectPermissionCodes, step.body.sourceDirectPermissionCodes)
        || !sameList(expected.sourceDeniedPermissionCodes, step.body.sourceDeniedPermissionCodes)
        || !sameList(expected.deniedPermissionCodes, step.body.deniedPermissionCodes)
        || expected.permissionMappingVersion !== step.body.permissionMappingVersion
        || expected.sourceRoleFingerprint !== step.body.sourceRoleFingerprint
        || expected.mustChangePassword !== step.body.mustChangePassword
        || expected.credentialChangeTicketHash !== (step.body.credentialChangeTicket
          ? crypto.createHash('sha256').update(step.body.credentialChangeTicket).digest('hex') : null)
        || !sameList(expected.legacyRoleIds, step.body.legacyRoleIds)
        || !sameList(expected.positionIds, step.body.positionIds)
        || !sameList(expected.campusIds, step.body.campusIds)) throw new Error('迁移主体与可信清单不一致');
  }
  const kc = localUrl('SIYUE_MIGRATION_KEYCLOAK_ADMIN_URL');
  const api = localUrl('SIYUE_MIGRATION_JAVA_API_URL');
  const kcToken = required('SIYUE_MIGRATION_KEYCLOAK_ADMIN_TOKEN');
  const javaToken = required('SIYUE_MIGRATION_JAVA_ADMIN_TOKEN');
  const checkpoint = required('SIYUE_MIGRATION_CHECKPOINT');
  if (required('SIYUE_MIGRATION_SOURCE_SHA256') !== manifest.sourceSha256) throw new Error('迁移来源摘要与服务端运行配置不一致');

  const existing = [];
  for (let i = 0; i < keycloakPlan.users.length; i++) {
    existing.push(await preflightExistingUser(kc, manifest.realm, kcToken, keycloakPlan.users[i],
      javaPlan.principals[i].mustChangePassword));
  }

  for (const step of roleSteps) {
    const { body: result } = await request(`${api}${step.path}`, javaToken, { method: 'POST', headers: {
      'Idempotency-Key': step.idempotencyKey,
    }, body: JSON.stringify(step.body) });
    validatePositionView(result?.data, step);
  }
  for (let i = 0; i < keycloakPlan.users.length; i++) {
    if (existing[i]) continue;
    const { body } = await request(`${kc}/admin/realms/${encodeURIComponent(manifest.realm)}/partialImport`, kcToken,
      { method: 'POST', body: JSON.stringify({ ifResourceExists: 'FAIL', users: [keycloakPlan.users[i]] }) });
    if (body && body.error) throw new Error('Keycloak用户导入失败');
    const imported = await userByUsername(kc, manifest.realm, kcToken, keycloakPlan.users[i].username);
    if (imported?.id !== keycloakPlan.users[i].id) throw new Error('Keycloak导入未保留稳定旧账号编号');
  }

  const preparedVersions = new Map();
  for (const step of javaPlan.principals) {
    const { body: prepareResponse } = await request(`${api}${step.preparePath}`, javaToken, { method: 'POST', headers: {
      'Idempotency-Key': step.prepareIdempotencyKey, 'X-Migration-Checkpoint': checkpoint,
    }, body: JSON.stringify(step.prepareBody) });
    const prepared = prepareResponse?.data;
    validatePrincipalView(prepared, step, false);
    preparedVersions.set(step.principalId, prepared.version);
  }
  for (const step of javaPlan.principals) {
    const { body: bindResponse } = await request(`${api}${step.path}`, javaToken, { method: 'POST', headers: {
      'Idempotency-Key': step.idempotencyKey, 'X-Migration-Checkpoint': checkpoint,
    }, body: JSON.stringify({ ...step.body, expectedVersion: preparedVersions.get(step.principalId) }) });
    validatePrincipalView(bindResponse?.data, step, true);
  }
  let activated = 0;
  for (const user of keycloakPlan.users) if (await activateImportedUser(kc, manifest.realm, kcToken, user)) activated++;
  process.stdout.write(JSON.stringify({ status: 'applied', accountCount: keycloakPlan.users.length,
    keycloakAlreadyPresent: existing.filter(Boolean).length, javaPrincipalsBound: javaPlan.principals.length,
    keycloakAccountsFinalized: activated,
    sourceSha256: manifest.sourceSha256 }) + '\n');
}

module.exports = { activateImportedUser, main, preflightExistingUser, validatePositionView, validatePrincipalView };
if (require.main === module) {
  main().catch(error => {
    process.stderr.write(`IDENTITY_MIGRATION_FAILED: ${error.message}\n`);
    process.exitCode = 1;
  });
}
