'use strict';
const crypto = require('node:crypto');
const path = require('node:path');
const auth = require('./auth-client.cjs');
const business = require('./business-client.cjs');
const java = require('./java-query-client.cjs');
const failure = (code, message) => Object.assign(new Error(message), { code });
const clone = value => JSON.parse(JSON.stringify(value));
const id = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
const count = value => Number.isSafeInteger(value) && value >= 0;
const version = value => count(value) && value > 0;
const states = ['unassigned', 'pending', 'in_progress', 'completed', 'cancelled'];
const labels = { create: '创建主管或员工任务批次', allocation: '分派所选任务', recall: '回收所选任务', transfer: '转交任务主管', schedule: '调整任务日期', cancel: '取消批次未完任务' };
const operations = Object.freeze(Object.entries(labels).map(([action, label]) => ({
  name: `task-routing-${action}`, id: `crm.task.routing.${action}`, action, label,
  permissions: action === 'create' ? ['crm.task.write', 'crm.task.assign'] : ['crm.task.read', 'crm.task.manage', 'crm.task.write', 'crm.task.assign'],
})));
function exact(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length
    || Object.keys(value).some(key => !keys.includes(key))) throw failure('INVALID_ROUTING_INPUT', '任务分派含无效对象或字段');
}
function text(value, max, optional = false) {
  if (optional && value == null) return;
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value))
    throw failure('INVALID_ROUTING_INPUT', '任务内容为空、过长或含控制字符');
}
function instant(value) {
  if (typeof value !== 'string') return null;
  const found = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!found) return null;
  const milliseconds = Date.parse(found[1] + 'Z');
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 19) !== found[1]) return null;
  return BigInt(milliseconds) * 1000000n + BigInt((found[2] || '').padEnd(9, '0'));
}
function sameTime(a, b) { return instant(a) !== null && instant(a) === instant(b); }
function period(input) {
  const start = instant(input.startsAt), due = instant(input.dueAt);
  if (start === null || due === null || due <= start) throw failure('INVALID_ROUTING_INPUT', '开始/截止须为有效UTC时间，截止晚于开始');
  // Future-time and already-started rules are server decisions. Never re-check time before a successful receipt replay.
}
function optionalId(value) { return value == null || id(value); }
function planOwners(input) {
  if (!['even', 'quota'].includes(input.strategy) || !Array.isArray(input.ownerIds)
    || input.ownerIds.length < 1 || input.ownerIds.length > 100 || input.ownerIds.some(value => !id(value))
    || new Set(input.ownerIds).size !== input.ownerIds.length) throw failure('INVALID_ROUTING_INPUT', '分配策略或候选员工无效');
  if (input.strategy === 'even') {
    if (input.quotas != null && (!Array.isArray(input.quotas) || input.quotas.length)) throw failure('INVALID_ROUTING_INPUT', '平均分配不接受名额');
  } else {
    if (!Array.isArray(input.quotas) || input.quotas.length !== input.ownerIds.length
      || new Set(input.quotas.map(item => item?.ownerId)).size !== input.ownerIds.length) throw failure('INVALID_ROUTING_INPUT', '每名员工须有一个名额');
    for (const quota of input.quotas) {
      exact(quota, ['ownerId', 'count']);
      if (!input.ownerIds.includes(quota.ownerId) || !count(quota.count) || quota.count > 500)
        throw failure('INVALID_ROUTING_INPUT', '分配名额无效');
    }
  }
}
function target(value) {
  exact(value, ['customerId', 'studentId', 'subject', 'term', 'needIdentity', 'sources']);
  if (!id(value.customerId) || !optionalId(value.studentId)) throw failure('INVALID_ROUTING_INPUT', '客户/学生编号无效');
  text(value.subject, 100, true); text(value.term, 100, true);
  if (value.needIdentity != null) {
    exact(value.needIdentity, ['departmentId', 'projectId']);
    if (!id(value.needIdentity.departmentId) || !optionalId(value.needIdentity.projectId)) throw failure('INVALID_ROUTING_INPUT', '需求身份无效');
  }
  if (value.sources != null) {
    if (!Array.isArray(value.sources) || value.sources.length > 100) throw failure('INVALID_ROUTING_INPUT', '来源证据数量无效');
    const keys = new Set();
    for (const source of value.sources) {
      exact(source, ['type', 'id', 'version']);
      if (!['lead', 'touchpoint'].includes(source.type) || !id(source.id)
        || (source.type === 'lead' ? !version(source.version) : source.version != null)
        || keys.has(source.type + ':' + source.id)) throw failure('INVALID_ROUTING_INPUT', '来源证据无效或重复');
      keys.add(source.type + ':' + source.id);
    }
  }
}
function definition(name) {
  const operation = operations.find(item => item.name === name || item.id === name);
  if (!operation) throw failure('JAVA_V2_UNAVAILABLE', '未开放此任务分派操作');
  return operation;
}
function validateRequest(name, request) {
  const operation = definition(name), { action } = operation;
  exact(request, action === 'create' ? ['input'] : ['batchId', 'input']);
  const input = request.input;
  if (action === 'create') {
    exact(input, ['title', 'taskType', 'campusId', 'activityId', 'startsAt', 'dueAt', 'delivery', 'targets', 'note']);
    text(input.title, 200); text(input.note, 5000, true); period(input);
    if (input.taskType !== 'marketing' || !id(input.campusId) || !optionalId(input.activityId)) throw failure('INVALID_ROUTING_INPUT', '仅支持真实校区的营销任务');
    if (!Array.isArray(input.targets) || input.targets.length < 1 || input.targets.length > 500) throw failure('INVALID_ROUTING_INPUT', '需求目标须为1至500个');
    input.targets.forEach(target);
    if (input.targets.some(item => item.sources?.length) && !input.activityId
      || input.activityId && input.targets.some(item => !item.sources?.length)) throw failure('INVALID_ROUTING_INPUT', '活动与来源证据须同时提供');
    exact(input.delivery, ['mode', 'supervisorId', 'employeePlan']);
    if (input.delivery.mode === 'supervisor') {
      if (!id(input.delivery.supervisorId) || input.delivery.employeePlan != null) throw failure('INVALID_ROUTING_INPUT', '主管模式须且只选一位主管');
    } else if (input.delivery.mode === 'direct') {
      if (input.delivery.supervisorId != null) throw failure('INVALID_ROUTING_INPUT', '员工模式不可指定主管');
      exact(input.delivery.employeePlan, ['strategy', 'ownerIds', 'quotas']); planOwners(input.delivery.employeePlan);
    } else throw failure('INVALID_ROUTING_INPUT', '分派模式无效');
  } else {
    if (!id(request.batchId)) throw failure('INVALID_ROUTING_INPUT', '批次编号无效');
    const fields = { allocation: ['selection', 'strategy', 'ownerIds', 'quotas', 'sourceOwnerPolicy'], recall: ['selection', 'reason'], transfer: ['supervisorId', 'reason'], schedule: ['startsAt', 'dueAt', 'reason'], cancel: ['reason'] };
    exact(input, ['expectedManagementVersion', ...fields[action]]);
    if (!version(input.expectedManagementVersion)) throw failure('INVALID_ROUTING_INPUT', '批次管理版本无效');
    if (['allocation', 'recall'].includes(action)) {
      if (!Array.isArray(input.selection) || input.selection.length < 1 || input.selection.length > 500
        || new Set(input.selection.map(item => item?.taskId)).size !== input.selection.length) throw failure('INVALID_ROUTING_INPUT', '须选择1至500个不同任务');
      for (const item of input.selection) {
        exact(item, ['taskId', 'expectedVersion']);
        if (!id(item.taskId) || !version(item.expectedVersion)) throw failure('INVALID_ROUTING_INPUT', '任务编号/版本无效');
      }
    }
    if (action === 'allocation') {
      planOwners(input);
      if (!['none', 'source_lead_owner_first'].includes(input.sourceOwnerPolicy)
        || input.sourceOwnerPolicy === 'source_lead_owner_first' && input.strategy !== 'even') throw failure('INVALID_ROUTING_INPUT', '线索负责人优先只可用于平均分派');
    } else text(input.reason, 1000);
    if (action === 'transfer' && !id(input.supervisorId)) throw failure('INVALID_ROUTING_INPUT', '新主管编号无效');
    if (action === 'schedule') period(input);
  }
  return operation;
}
const normalizedTarget = value => ({ customerId: value.customerId, studentId: value.studentId || null,
  subject: value.subject?.trim() || null, term: value.term?.trim() || null, needIdentity: value.needIdentity ? { departmentId: value.needIdentity.departmentId, projectId: value.needIdentity.projectId ?? null } : null,
  sources: (value.sources || []).map(item => ({ type: item.type, id: item.id, version: item.version ?? null })).sort((a, b) => (a.type + a.id).localeCompare(b.type + b.id)) });
const signature = value => JSON.stringify(normalizedTarget(value));
function distribution(actual, owners) {
  exact(actual, Object.keys(actual || {}));
  const expected = Object.create(null);
  owners.filter(Boolean).forEach(owner => { expected[owner] = (expected[owner] || 0) + 1; });
  if (Object.entries(actual).some(([owner, amount]) => !id(owner) || !count(amount) || amount !== (expected[owner] || 0))
    || Object.keys(expected).some(owner => actual[owner] !== expected[owner])) throw failure('INVALID_PREVIEW', '实际员工分布与逐条结果不一致');
}
function validatePreview(operation, request, preview) {
  const invalid = () => { throw failure('INVALID_PREVIEW', '服务端任务预览与请求不一致'); }, input = request.input;
  if (!preview || preview.action !== operation.action) invalid();
  if (operation.action === 'create') {
    if (preview.campusId !== input.campusId || preview.deliveryMode !== input.delivery.mode
      || (preview.supervisorId ?? null) !== (input.delivery.supervisorId ?? null)
      || !sameTime(preview.startsAt, input.startsAt) || !sameTime(preview.dueAt, input.dueAt)
      || ![preview.selectedCount, preview.reservedCount, preview.skippedCount].every(count)
      || preview.selectedCount !== input.targets.length || preview.selectedCount !== preview.reservedCount + preview.skippedCount
      || !Array.isArray(preview.targets) || !Array.isArray(preview.skippedTargets)
      || preview.targets.length !== preview.reservedCount || preview.skippedTargets.length !== preview.skippedCount) invalid();
    const remaining = input.targets.map(signature), keys = new Set();
    for (const item of [...preview.targets, ...preview.skippedTargets]) {
      if (typeof item.targetKey !== 'string' || !item.targetKey || keys.has(item.targetKey)) invalid();
      keys.add(item.targetKey); target(item.target);
      const found = remaining.indexOf(signature(item.target)); if (found < 0) invalid(); remaining.splice(found, 1);
    }
    for (const item of preview.targets) {
      if (input.delivery.mode === 'supervisor' ? item.ownerId != null || item.status !== 'unassigned'
        : !input.delivery.employeePlan.ownerIds.includes(item.ownerId) || item.status !== 'pending') invalid();
    }
    if (preview.skippedTargets.some(item => !['open_marketing_task', 'identity_review_required'].includes(item.reason))) invalid();
    distribution(preview.assignmentCounts, preview.targets.map(item => item.ownerId));
  } else {
    if (preview.batchId !== request.batchId || preview.managementVersion !== input.expectedManagementVersion
      || ![preview.selectedCount, preview.assignableCount, preview.blockedCount, preview.remainingUnassignedCount].every(count)
      || !Array.isArray(preview.assignments) || !Array.isArray(preview.blocked)
      || preview.assignments.length !== preview.assignableCount || preview.blocked.length !== preview.blockedCount) invalid();
    const keys = new Set();
    for (const item of [...preview.assignments, ...preview.blocked]) {
      if (!id(item.taskId) || keys.has(item.taskId)) invalid(); keys.add(item.taskId);
    }
    for (const item of preview.assignments) {
      if (!version(item.expectedVersion) || !optionalId(item.fromOwnerId) || !optionalId(item.toOwnerId)
        || !states.includes(item.fromStatus) || !states.includes(item.toStatus)
        || instant(item.fromDueAt) === null || instant(item.toDueAt) === null || typeof item.basis !== 'string') invalid();
      if (input.selection && !input.selection.some(selected => selected.taskId === item.taskId && selected.expectedVersion === item.expectedVersion)) invalid();
    }
    if (input.selection && (preview.selectedCount !== input.selection.length
      || preview.assignableCount + preview.blockedCount !== input.selection.length
      || preview.blocked.some(item => !input.selection.some(selected => selected.taskId === item.taskId)))) invalid();
    if (preview.blocked.some(item => typeof item.reason !== 'string' || !item.reason)) invalid();
    if (operation.action === 'allocation') {
      if (preview.assignments.some(item => !id(item.toOwnerId) || item.toStatus !== 'pending')) invalid();
      distribution(preview.assignmentCounts, preview.assignments.map(item => item.toOwnerId));
      // Source-first may legitimately select a source lead owner OUTSIDE ownerIds; preserve that server evidence.
      if (input.sourceOwnerPolicy === 'none' && preview.assignments.some(item => !input.ownerIds.includes(item.toOwnerId))) invalid();
    } else distribution(preview.assignmentCounts, []);
    if (operation.action === 'recall' && preview.assignments.some(item => item.toOwnerId != null || item.toStatus !== 'unassigned')) invalid();
    if (operation.action === 'cancel' && preview.assignments.some(item => item.toStatus !== 'cancelled')) invalid();
    if (operation.action === 'transfer' && (preview.toSupervisorId !== input.supervisorId || preview.assignments.length)) invalid();
    if (operation.action === 'schedule' && (!sameTime(preview.startsAt, input.startsAt) || !sameTime(preview.dueAt, input.dueAt)
      || preview.assignments.some(item => !sameTime(item.toDueAt, input.dueAt)))) invalid();
  }
  return preview;
}
function authorized(operation, client, me) {
  if (client?.apiVersion !== 'v2' || !id(me?.principalId)) throw failure('INVALID_JAVA_SESSION', '需要Java v2员工会话');
  if (!operation.permissions.every(permission => me.permissions?.includes(permission))) throw failure('FORBIDDEN', '当前账号无此任务分派权限');
}
function endpoint(operation, request, stage) {
  return `crm/task-routing/batches${operation.action === 'create' ? '' : '/' + request.batchId + '/' + operation.action}/${stage}`;
}
async function prepare(name, request, { client, me } = {}) {
  const operation = validateRequest(name, request); request = clone(request);
  client ||= auth.sessionClient(); me ||= await java.identity(client); authorized(operation, client, me);
  const preview = validatePreview(operation, request, java.unwrap(await client.request(endpoint(operation, request, 'preview'), { method: 'POST', body: request.input })));
  if (operation.action === 'create' && preview.reservedCount === 0) return { plan: null, preview: clone(preview), next: '全部需求已跳过，没有可创建任务；当前不能提交。' };
  if (preview.blockedCount > 0) return { plan: null, preview: clone(preview), next: '存在阻止项；先处理来源或选择其他明确策略，再重新预览。当前不能提交。' };
  const plan = { format: 'siyue-java-task-routing-plan-v1', apiVersion: 'v2', operationId: operation.id,
    server: client.server, principalId: me.principalId, request, expectedPreview: clone(preview), idempotencyKey: crypto.randomUUID() };
  return { plan, preview: clone(preview), next: '核对逐条需求、来源依据和实际接收人后提交原计划；未知结果只重试原计划。' };
}
function validatePlan(plan) {
  exact(plan, ['format', 'apiVersion', 'operationId', 'server', 'principalId', 'request', 'expectedPreview', 'idempotencyKey']);
  if (plan.format !== 'siyue-java-task-routing-plan-v1' || plan.apiVersion !== 'v2' || typeof plan.server !== 'string'
    || !id(plan.principalId) || typeof plan.idempotencyKey !== 'string' || !/^[A-Za-z0-9._:-]{16,128}$/.test(plan.idempotencyKey)) throw failure('INVALID_PLAN', '任务分派计划无效');
  const operation = validateRequest(plan.operationId, plan.request);
  validatePreview(operation, plan.request, plan.expectedPreview);
  if (plan.expectedPreview.blockedCount > 0 || operation.action === 'create' && plan.expectedPreview.reservedCount === 0) throw failure('INVALID_PLAN', '有阻止项的预览不可提交');
  return operation;
}
function validateReceipt(operation, plan, receipt) {
  const invalid = () => { throw failure('INVALID_RESPONSE', '任务回执与原计划不符，请保留原计划和幂等键核对或重试'); };
  if (!receipt || receipt.committed !== true || !id(receipt.batchId) || !id(receipt.operationId)
    || !['visible', 'left_scope'].includes(receipt.scopeStatus) || !Array.isArray(receipt.tasks)
    || operation.action !== 'create' && receipt.batchId !== plan.request.batchId) invalid();
  if (receipt.scopeStatus === 'left_scope') {
    if (receipt.batch !== null || receipt.tasks.length) invalid();
    // Explicitly project the safe success acknowledgement; no stale batch or employee data escapes.
    return { committed: true, batchId: receipt.batchId, operationId: receipt.operationId, scopeStatus: 'left_scope', batch: null, tasks: [] };
  }
  const batch = receipt.batch, preview = plan.expectedPreview, counts = batch?.counts;
  if (!batch || batch.id !== receipt.batchId || !version(batch.managementVersion) || !version(batch.version) || !counts
    || ['totalCount', 'unassignedCount', 'pendingCount', 'inProgressCount', 'completedCount', 'cancelledCount', 'allocatedCount'].some(key => !count(counts[key]))
    || counts.totalCount !== counts.unassignedCount + counts.pendingCount + counts.inProgressCount + counts.completedCount + counts.cancelledCount
    || counts.allocatedCount !== counts.pendingCount + counts.inProgressCount + counts.completedCount
    || new Set(receipt.tasks.map(item => item.taskId)).size !== receipt.tasks.length) invalid();
  if (receipt.tasks.some(item => !id(item.taskId) || !version(item.version) || !optionalId(item.ownerId)
    || !states.includes(item.status) || instant(item.dueAt) === null)) invalid();
  if (operation.action === 'create') {
    const input = plan.request.input;
    if (batch.managementVersion !== 1 || batch.campusId !== input.campusId || batch.deliveryMode !== input.delivery.mode
      || (batch.supervisorId ?? null) !== (input.delivery.supervisorId ?? null) || !sameTime(batch.startsAt, input.startsAt)
      || !sameTime(batch.dueAt, input.dueAt) || receipt.tasks.length !== preview.reservedCount || counts.totalCount !== preview.reservedCount) invalid();
    const remaining = preview.targets.map(item => JSON.stringify([item.ownerId ?? null, item.status]));
    for (const item of receipt.tasks) {
      const at = remaining.indexOf(JSON.stringify([item.ownerId ?? null, item.status]));
      if (item.version !== 1 || !sameTime(item.dueAt, input.dueAt) || at < 0) invalid(); remaining.splice(at, 1);
    }
  } else {
    if (batch.managementVersion !== preview.managementVersion + 1 || receipt.tasks.length !== preview.assignments.length) invalid();
    for (const item of receipt.tasks) {
      const row = preview.assignments.find(value => value.taskId === item.taskId);
      if (!row || item.version !== row.expectedVersion + 1 || (item.ownerId ?? null) !== (row.toOwnerId ?? null)
        || item.status !== row.toStatus || !sameTime(item.dueAt, row.toDueAt)) invalid();
    }
    if (operation.action === 'transfer' && batch.supervisorId !== preview.toSupervisorId) invalid();
    if (operation.action === 'schedule' && (!sameTime(batch.startsAt, preview.startsAt) || !sameTime(batch.dueAt, preview.dueAt))) invalid();
  }
  return receipt;
}
async function executePlan(plan, { client, me } = {}) {
  const operation = validatePlan(plan);
  client ||= auth.sessionClient(); me ||= await java.identity(client); authorized(operation, client, me);
  if (auth.serverURL(plan.server) !== client.server) throw failure('INVALID_PLAN', '任务计划不属于当前服务端');
  if (plan.principalId !== me.principalId) throw failure('WRONG_ACTOR', '须由原预览员工提交');
  // No scope/version/time GET here: a committed transfer may already have removed the caller's scope.
  return validateReceipt(operation, plan, java.unwrap(await client.request(endpoint(operation, plan.request, 'commit'), {
    method: 'POST', body: { input: plan.request.input, expectedPreview: plan.expectedPreview }, idempotencyKey: plan.idempotencyKey,
  })));
}
async function createPlan(name, request, output, options) {
  const prepared = await prepare(name, request, options);
  if (!prepared.plan) return { preview: prepared.preview, planFile: null, commit: false, next: prepared.next };
  const file = path.resolve(output); business.write(file, prepared.plan);
  return { operationId: prepared.plan.operationId, preview: prepared.preview, planFile: file, commit: false, next: prepared.next };
}
module.exports = { operations, definition, validateRequest, validatePreview, validatePlan, validateReceipt, prepare, executePlan, createPlan,
  execute: (file, options) => executePlan(business.read(file), options) };
