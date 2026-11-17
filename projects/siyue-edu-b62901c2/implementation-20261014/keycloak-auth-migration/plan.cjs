'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { effectivePermissions, validateRolePolicies, permissions: legacyPermissionCatalog, roles: legacyRoles } = require('../backend/src/auth-policy.cjs');
const { PERMISSION_MAPPING_VERSION, mapPermissionCodes, readPermissionMap } = require('./permission-map.cjs');

const JAVA_ACCESS_SOURCE = path.resolve(__dirname, '../backend-java/src/main/java/cn/siyue/core/AccessPermissionCatalog.java');
const HASH_ID = 'siyue-scrypt-v1';
const ADMIN_ROLE = 'superadmin';
const ADMIN_SOURCES = new Set(['employee.create', 'role.grant']);

function fail(message) { const error = new Error(message); error.code = 'IDENTITY_MIGRATION_REJECTED'; throw error; }

function javaPermissionCatalog(filename = JAVA_ACCESS_SOURCE) {
  const source = fs.readFileSync(filename, 'utf8');
  const match = source.match(/private static final Set<String> PERMISSIONS = Set\.of\(([\s\S]*?)\);/);
  if (!match) fail('无法读取 Java 服务端权限白名单');
  const values = [...match[1].matchAll(/"([a-zA-Z0-9._-]+)"/g)].map(item => item[1]);
  if (!values.length || new Set(values).size !== values.length) fail('Java 服务端权限白名单格式无效');
  return new Set(values);
}

function validateCredential(hash) {
  if (!hash || hash.algorithm !== 'scrypt' || !/^[0-9a-f]{64}$/.test(hash.salt || '')
      || !/^[0-9a-f]{128}$/.test(hash.hash || '')) fail('存在格式无效的scrypt凭据');
}

function directoryProfile(user, users) {
  const text = (value, max, optional) => {
    if (optional && (value == null || value === '')) return null;
    if (typeof value !== 'string' || !value.trim() || value !== value.trim()
        || value.length > max || /[\u0000-\u001f\u007f]/.test(value)) fail('员工目录字段格式无效');
    return value;
  };
  const displayName = text(user.name, 80, false), employeeNo = text(user.employeeNo, 40, true);
  const archivedAt = text(user.archivedAt, 40, true), archivedBy = text(user.archivedBy, 120, true);
  const archiveReason = text(user.archiveReason, 200, true);
  if (Boolean(archivedAt) !== Boolean(archivedBy) || !archivedAt && archiveReason
      || archivedAt && (!user.disabled || !Number.isFinite(Date.parse(archivedAt))
        || !users.some(other => other.id === archivedBy))) fail('员工归档信息与可信身份集合不一致');
  return { displayName, employeeNo, archivedAt, archivedBy, archiveReason, username: text(user.username, 48, false) };
}

function inspectSource(state) {
  if (!state || state.schemaVersion !== 1 || !Array.isArray(state.users)
      || !Array.isArray(state.sessions) || !Array.isArray(state.audit)) fail('认证存储结构无效');
  validateRolePolicies(state.rolePolicies || {});
  const knownRoles = new Set(['superadmin', 'principal', 'academic', 'sales', 'finance', 'teacher', 'operations', 'auditor']);
  const id = new Set(), username = new Set(), employeeNo = new Set();
  for (const user of state.users) {
    if (!user || typeof user.id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(user.id)) fail('存在无效身份编号');
    if (typeof user.mustChangePassword !== 'boolean') fail('强制改密状态必须来自显式布尔值');
    if (!user.username || typeof user.username !== 'string' || id.has(user.id) || username.has(user.username.toLowerCase())) fail('存在身份或登录名碰撞');
    if (user.employeeNo && employeeNo.has(user.employeeNo)) fail('存在重复员工编号');
    id.add(user.id); username.add(user.username.toLowerCase()); if (user.employeeNo) employeeNo.add(user.employeeNo);
    if (!Number.isInteger(user.version) || user.version < 1 || !Array.isArray(user.roleIds) || !user.roleIds.length
        || new Set(user.roleIds).size !== user.roleIds.length || user.roleIds.some(role => !knownRoles.has(role))) fail('账号角色或版本无效');
    if (user.roleIds.includes(ADMIN_ROLE) && user.deniedPermissions?.length) fail('超级管理员禁止权限配置无效');
    if (user.additionalPermissions?.some(code => ['employee.create', 'account.update', 'role.grant'].includes(code))) fail('保留权限不能作为直接追加权限迁移');
    directoryProfile(user, state.users);
    validateCredential(user.passwordHash);
    if (!user.scope || !['all', 'campus', 'own', 'assigned'].includes(user.scope.type)
        || !Array.isArray(user.scope.campusIds) || new Set(user.scope.campusIds).size !== user.scope.campusIds.length
        || user.scope.campusIds.some(campus => typeof campus !== 'string' || !campus.trim() || campus.length > 120)
        || (user.scope.type === 'campus' && !user.scope.campusIds.length)
        || (user.scope.type !== 'campus' && user.scope.campusIds.length)) fail('账号数据范围无效');
  }
  const permissions = javaPermissionCatalog();
  const permissionMap = readPermissionMap();
  const oldPermissions = new Set(legacyPermissionCatalog.map(item => item.id));
  const unsupportedPermissions = new Set();
  const permissionCounts = new Map();
  const roleCounts = new Map();
  const scopeCounts = new Map();
  const assignedRoleIds = new Set(state.users.flatMap(user => user.roleIds));
  const roleTemplates = [...assignedRoleIds].sort().map(roleId => {
    const role = legacyRoles.find(item => item.id === roleId);
    const policy = roleId === ADMIN_ROLE ? null : state.rolePolicies?.[roleId];
    const codes = [...(Array.isArray(policy?.permissions) ? policy.permissions : role.permissions)].sort();
    for (const code of codes) {
      if (!oldPermissions.has(code)) fail(`旧角色 ${roleId} 包含未知权限 ${code}`);
      const mapping = permissionMap.bySource.get(code);
      if (!(roleId === ADMIN_ROLE && ADMIN_SOURCES.has(code)) && (!mapping || !permissions.has(mapping.target))) unsupportedPermissions.add(code);
    }
    const mappedCodes = codes.filter(code => permissionMap.bySource.has(code)).map(code => permissionMap.bySource.get(code).target).sort();
    if (roleId === ADMIN_ROLE) mappedCodes.push('system.access.manage');
    mappedCodes.sort();
    const sourceVersion = Number.isInteger(policy?.version) ? policy.version : 1;
    const sourceFingerprint = crypto.createHash('sha256').update(JSON.stringify({ roleId, sourceVersion,
      sourcePermissionCodes: codes })).digest('hex');
    return { roleId, positionId: roleId === ADMIN_ROLE ? null : `LEGACY_ROLE_${roleId}`, name: role.label, description: role.description,
      sourcePermissionCodes: codes, permissionCodes: mappedCodes, sourceVersion, sourceFingerprint };
  });
  let disabledCount = 0, archivedCount = 0, mustChangePasswordCount = 0;
  const planned = state.users.map(user => {
    const grants = effectivePermissions(user, state.rolePolicies || {});
    for (const grant of grants) {
      if (!oldPermissions.has(grant)) fail(`旧权限策略包含未知权限 ${grant}`);
      permissionCounts.set(grant, (permissionCounts.get(grant) || 0) + 1);
      const mapping = permissionMap.bySource.get(grant);
      if (!(user.roleIds.includes(ADMIN_ROLE) && ADMIN_SOURCES.has(grant)) && (!mapping || !permissions.has(mapping.target))) unsupportedPermissions.add(grant);
    }
    if (user.additionalPermissions?.some(code => !oldPermissions.has(code))
        || user.deniedPermissions?.some(code => !oldPermissions.has(code))) fail('账号存在未知权限覆盖');
    const disabled = user.disabled === true || Boolean(user.archivedAt);
    if (disabled) disabledCount++;
    if (user.archivedAt) archivedCount++;
    if (user.mustChangePassword) mustChangePasswordCount++;
    for (const role of user.roleIds) roleCounts.set(role, (roleCounts.get(role) || 0) + 1);
    const scope = user.roleIds.includes('superadmin') ? { type: 'all', campusIds: [] } : user.scope;
    for (const code of [...(user.additionalPermissions || []), ...(user.deniedPermissions || [])]) {
      const mapping = permissionMap.bySource.get(code);
      if (!oldPermissions.has(code)) fail(`账号存在未知权限覆盖 ${code}`);
      if (!mapping || !permissions.has(mapping.target)) unsupportedPermissions.add(code);
    }
    scopeCounts.set(scope.type, (scopeCounts.get(scope.type) || 0) + 1);
    const roleIds = [...user.roleIds].sort();
    const positionIds = roleIds.filter(roleId => roleId !== 'superadmin')
      .map(roleId => `LEGACY_ROLE_${roleId}`).sort();
    const sourceRoleFingerprint = crypto.createHash('sha256').update(JSON.stringify(roleIds.map(roleId => {
      const role = roleTemplates.find(item => item.roleId === roleId);
      return { roleId, sourceVersion: role.sourceVersion, sourceFingerprint: role.sourceFingerprint };
    }))).digest('hex');
    const mapOptional = values => values.map(code => permissionMap.bySource.get(code)?.target).filter(Boolean).sort();
    const directPermissionCodes = user.roleIds.includes(ADMIN_ROLE)
      ? [...new Set([...mapOptional(grants.filter(code => !ADMIN_SOURCES.has(code))), 'system.access.manage'])].sort()
      : mapOptional(user.additionalPermissions || []);
    const deniedPermissionCodes = mapOptional(user.deniedPermissions || []);
    const expectedEffectivePermissionCodes = user.roleIds.includes(ADMIN_ROLE) ? directPermissionCodes : mapOptional(grants);
    const computed = new Set(roleIds.filter(roleId => roleId !== 'superadmin')
      .flatMap(roleId => roleTemplates.find(item => item.roleId === roleId)?.permissionCodes || []));
    for (const code of directPermissionCodes) computed.add(code);
    for (const code of deniedPermissionCodes) computed.delete(code);
    if ([...computed].sort().join('\0') !== expectedEffectivePermissionCodes.join('\0')) {
      fail(`角色/直接权限/拒权经 ${PERMISSION_MAPPING_VERSION} 转换后无法保持旧有效权限 ${user.id}`);
    }
    const credentialChangeTicket = user.mustChangePassword ? crypto.randomBytes(32).toString('base64url') : null;
    return { user, grants, disabled, scope, roleIds, positionIds, sourceRoleFingerprint, credentialChangeTicket,
      sourceDirectPermissionCodes: [...(user.additionalPermissions || [])].sort(), directPermissionCodes,
      sourceDeniedPermissionCodes: [...(user.deniedPermissions || [])].sort(), deniedPermissionCodes,
      expectedEffectivePermissionCodes };
  });
  return {
    planned,
    summary: {
      accountCount: planned.length,
      disabledCount,
      archivedCount,
      mustChangePasswordCount,
      roleCounts: Object.fromEntries([...roleCounts].sort(([a], [b]) => a.localeCompare(b))),
      positionTemplateCount: roleTemplates.filter(role => role.roleId !== ADMIN_ROLE).length,
      scopeCounts: Object.fromEntries([...scopeCounts].sort(([a], [b]) => a.localeCompare(b))),
      permissionCounts: Object.fromEntries([...permissionCounts].sort(([a], [b]) => a.localeCompare(b))),
      unsupportedPermissionCounts: Object.fromEntries([...unsupportedPermissions].sort()
        .map(code => [code, permissionCounts.get(code)])),
      roleTemplates,
      permissionMappingVersion: PERMISSION_MAPPING_VERSION,
    },
  };
}

function validateSource(state) {
  const { planned, summary } = inspectSource(state);
  const unsupported = Object.keys(summary.unsupportedPermissionCounts);
  if (unsupported.length) fail(`Java 尚未支持权限 ${unsupported.join(', ')}`);
  return { principals: planned, roleTemplates: summary.roleTemplates };
}

function safeWrite(file, bytes) {
  const temp = `${file}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  const fd = fs.openSync(temp, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
  try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  fs.renameSync(temp, file);
}

function buildPlan(state, issuer, realm, sourceSha256) {
  let issuerUrl;
  try { issuerUrl = new URL(issuer); } catch { fail('OIDC issuer 无效'); }
  if (issuerUrl.protocol !== 'https:' && !(issuerUrl.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(issuerUrl.hostname))) fail('OIDC issuer 必须使用 HTTPS');
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(realm)) fail('Keycloak realm 名称无效');
  if (!/^[0-9a-f]{64}$/.test(sourceSha256 || '')) fail('必须提供原认证存储源文件SHA-256');
  const validated = validateSource(state);
  const planned = validated.principals;
  const roleSteps = validated.roleTemplates.filter(role => role.roleId !== ADMIN_ROLE).map(({ roleId, sourcePermissionCodes, permissionCodes, sourceVersion, sourceFingerprint }) => {
    const role = legacyRoles.find(item => item.id === roleId), policy = state.rolePolicies?.[roleId];
    const positionId = `LEGACY_ROLE_${roleId}`;
    const body = { positionId, name: role.label, description: role.description, permissionCodes,
      directoryKind: roleId === 'teacher' ? 'teacher' : 'staff' };
    const suffix = crypto.createHash('sha256').update(`siyue-legacy-role-v1:${roleId}`).digest('hex').slice(0, 40);
    return { roleId, positionId, path: '/api/v2/system/positions', idempotencyKey: `legacy-role-${suffix}`,
      sourcePermissionCodes, permissionMappingVersion: PERMISSION_MAPPING_VERSION,
      sourceVersion, sourceFingerprint, body };
  });
  const rolesManifest = Object.fromEntries(roleSteps.map(step => [step.roleId, {
    roleId: step.roleId, positionId: step.positionId, name: step.body.name, description: step.body.description,
    directoryKind: step.body.directoryKind,
    sourcePermissionCodes: step.sourcePermissionCodes, permissionCodes: step.body.permissionCodes,
    permissionMappingVersion: step.permissionMappingVersion, sourceVersion: step.sourceVersion,
    sourceFingerprint: step.sourceFingerprint,
  }]));
  const administratorRole = validated.roleTemplates.find(role => role.roleId === ADMIN_ROLE);
  if (administratorRole) rolesManifest[ADMIN_ROLE] = { ...administratorRole, permissionMappingVersion: PERMISSION_MAPPING_VERSION };
  const userRepresentations = planned.map(({ user, grants, disabled, scope, roleIds, sourceRoleFingerprint,
    sourceDirectPermissionCodes, sourceDeniedPermissionCodes, credentialChangeTicket }) => {
    const credential = user.passwordHash;
    const credentialData = { algorithm: HASH_ID, hashIterations: 1, additionalParameters: {} };
    const secretData = { value: credential.hash, salt: Buffer.from(credential.salt, 'utf8').toString('base64'), additionalParameters: {} };
    const migrationAttrs = {
      legacy_user_id: [user.id], legacy_role_ids: roleIds,
      legacy_java_credential_gate_managed: ['true'],
      legacy_scope: [scope.type], legacy_must_change_password: [String(Boolean(user.mustChangePassword))],
      legacy_effective_permissions: grants, legacy_target_enabled: [String(!disabled)],
      legacy_denied_permissions: [...(user.deniedPermissions || [])].sort(),
      legacy_permission_mapping_version: [PERMISSION_MAPPING_VERSION],
      legacy_source_role_fingerprint: [sourceRoleFingerprint],
      legacy_source_direct_permissions: sourceDirectPermissionCodes,
      legacy_source_denied_permissions: sourceDeniedPermissionCodes,
      legacy_migration_state: ['pending'], legacy_source_sha256: [sourceSha256],
    };
    if (credentialChangeTicket) {
      migrationAttrs.legacy_credential_change_ticket = [credentialChangeTicket];
      migrationAttrs.legacy_credential_change_generation = ['1'];
    }
    if (user.employeeNo) migrationAttrs.legacy_employee_no = [user.employeeNo];
    if (scope.campusIds.length) migrationAttrs.legacy_campus_ids = [...scope.campusIds].sort();
    const result = {
      // Keep every newly imported identity disabled until Java commits its trusted grant mapping.
      id: user.id, username: user.username, enabled: false,
      firstName: user.name, lastName: '员工账号', attributes: migrationAttrs,
      credentials: [{ type: 'password', credentialData: JSON.stringify(credentialData), secretData: JSON.stringify(secretData) }],
      requiredActions: user.mustChangePassword ? ['UPDATE_PASSWORD'] : [],
      emailVerified: true,
    };
    return result;
  });
  const principalSteps = planned.map(({ user, grants, disabled, scope, roleIds, positionIds, sourceRoleFingerprint,
    sourceDirectPermissionCodes, directPermissionCodes, sourceDeniedPermissionCodes, deniedPermissionCodes,
    expectedEffectivePermissionCodes, credentialChangeTicket }) => {
    const input = { legacyUserId: user.id, expectedVersion: null, sourceSha256, directory: directoryProfile(user, state.users),
      issuer: issuerUrl.origin + issuerUrl.pathname.replace(/\/$/, ''), subject: user.id,
      scope: scope.type, sourcePermissionCodes: [...grants].sort(),
      permissionCodes: directPermissionCodes, campusIds: [...scope.campusIds].sort(), legacyRoleIds: roleIds,
      positionIds, deniedPermissionCodes, sourceDirectPermissionCodes, sourceDeniedPermissionCodes,
      expectedEffectivePermissionCodes, permissionMappingVersion: PERMISSION_MAPPING_VERSION, sourceRoleFingerprint,
      mustChangePassword: Boolean(user.mustChangePassword), credentialChangeTicket };
    const suffix = crypto.createHash('sha256').update(`siyue-legacy-identity-v1:${user.id}`).digest('hex').slice(0, 40);
    return { principalId: user.id, expectedOidcSubject: user.id, disabled, mustChangePassword: Boolean(user.mustChangePassword),
      path: `/api/v2/system/legacy-identity-migration/principals/${encodeURIComponent(user.id)}/bind`,
      preparePath: `/api/v2/system/legacy-identity-migration/principals/${encodeURIComponent(user.id)}/prepare`,
      prepareIdempotencyKey: `legacy-prepare-${suffix}`, prepareBody: { legacyUserId: user.id, sourceSha256 },
      idempotencyKey: `legacy-bind-${suffix}`, checkpointHeader: 'X-Migration-Checkpoint', body: { ...input, active: !disabled } };
  });
  const trustedPrincipals = Object.fromEntries(principalSteps.map(step => [step.principalId, {
    issuer: planIssuer(issuer), subject: step.body.subject, scope: step.body.scope, directory: step.body.directory,
    active: step.body.active, sourcePermissionCodes: step.body.sourcePermissionCodes,
    permissionCodes: step.body.permissionCodes, expectedEffectivePermissionCodes: step.body.expectedEffectivePermissionCodes,
    sourceDirectPermissionCodes: step.body.sourceDirectPermissionCodes,
    campusIds: step.body.campusIds,
    legacyRoleIds: step.body.legacyRoleIds, positionIds: step.body.positionIds,
    deniedPermissionCodes: step.body.deniedPermissionCodes,
    sourceDeniedPermissionCodes: step.body.sourceDeniedPermissionCodes,
    permissionMappingVersion: step.body.permissionMappingVersion,
    sourceRoleFingerprint: step.body.sourceRoleFingerprint,
    mustChangePassword: step.body.mustChangePassword,
    credentialChangeTicketHash: step.body.credentialChangeTicket
      ? crypto.createHash('sha256').update(step.body.credentialChangeTicket).digest('hex') : null,
  }]));
  return { realmRepresentation: { realm, users: userRepresentations },
    keycloakPartialImport: { ifResourceExists: 'FAIL', users: userRepresentations },
    principalManifest: { issuer: planIssuer(issuer), sourceSha256,
      permissionMappingVersion: PERMISSION_MAPPING_VERSION, positions: roleSteps, principals: principalSteps },
    trustedManifest: { sourceSha256, permissionMappingVersion: PERMISSION_MAPPING_VERSION,
      principals: trustedPrincipals, roles: rolesManifest } };
}

function planIssuer(issuer) { const value = new URL(issuer); return value.origin + value.pathname.replace(/\/$/, ''); }

function readPrivateSource(filename) {
  if (!path.isAbsolute(filename)) fail('源认证存储路径必须为绝对路径');
  const dir = path.dirname(filename); const dirStat = fs.lstatSync(dir); const fileStat = fs.lstatSync(filename);
  if (!dirStat.isDirectory() || dirStat.isSymbolicLink() || (dirStat.mode & 0o077)) fail('源认证存储目录必须为0700独立目录');
  if (!fileStat.isFile() || fileStat.isSymbolicLink() || (fileStat.mode & 0o077) || fileStat.size > 32 * 1024 * 1024) fail('源认证存储必须是0600普通文件且不超过32MiB');
  return JSON.parse(fs.readFileSync(filename, 'utf8'));
}

function writePackage(outDir, plan) {
  if (!path.isAbsolute(outDir)) fail('输出目录必须为绝对路径');
  fs.mkdirSync(outDir, { recursive: true, mode: 0o700 });
  const stat = fs.lstatSync(outDir);
  if (!stat.isDirectory() || stat.isSymbolicLink() || (stat.mode & 0o077)) fail('输出目录必须为0700独立目录');
  const root = fs.realpathSync(outDir);
  safeWrite(path.join(root, 'keycloak-partial-import.json'), JSON.stringify(plan.keycloakPartialImport, null, 2) + '\n');
  safeWrite(path.join(root, 'java-principal-steps.json'), JSON.stringify(plan.principalManifest, null, 2) + '\n');
  safeWrite(path.join(root, 'trusted-manifest.json'), JSON.stringify(plan.trustedManifest, null, 2) + '\n');
}

module.exports = { HASH_ID, buildPlan, inspectSource, javaPermissionCatalog, readPrivateSource, validateSource, writePackage };
