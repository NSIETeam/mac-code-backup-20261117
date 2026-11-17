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
const operations = Object.freeze([
  { name: 'student-identifier-create', id: 'crm.customer.student.identifier.create', label: '备案学生学号', permission: 'crm.customer.identity.write', method: 'POST' },
  { name: 'student-identifier-deactivate', id: 'crm.customer.student.identifier.deactivate', label: '停用学生学号', permission: 'crm.customer.identity.write', method: 'PUT', target: true },
  { name: 'student-name-index-rebuild', id: 'crm.customer.search.names.rebuild', label: '分批重建学生姓名检索', permission: 'crm.customer.search.manage', method: 'POST', index: 'name' },
  { name: 'student-number-index-rebuild', id: 'crm.customer.search.numbers.rebuild', label: '分批重建学生学号检索', permission: 'crm.customer.search.manage', method: 'POST', index: 'number' },
]);
function exact(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length
    || Object.keys(value).some(key => !keys.includes(key))) throw failure('INVALID_IDENTITY_INPUT', '身份办理含无效对象或字段');
}
function text(value, max, raw = false) {
  const controls = raw ? /[\u0000-\u001f\u007f-\u009f]/ : /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/;
  if (typeof value !== 'string' || !value.trim() || value.length > max || controls.test(value)
    || raw && value !== value.trim()) throw failure('INVALID_IDENTITY_INPUT', raw ? '学号及来源须原样填写，不含首尾空白或控制字符' : '办理内容为空、过长或含控制字符');
}
function definition(name) {
  const result = operations.find(item => item.name === name || item.id === name);
  if (!result) throw failure('JAVA_V2_UNAVAILABLE', '未开放此 Java 身份办理');
  return result;
}
function validateRequest(name, request) {
  const operation = definition(name);
  exact(request, operation.index ? ['input'] : ['customerId', 'studentId', 'identifierId', 'input']);
  if (!operation.index && (!id(request.customerId) || !id(request.studentId)
    || (operation.target ? !id(request.identifierId) : request.identifierId !== undefined)))
    throw failure('INVALID_IDENTITY_INPUT', '客户、孩子或学号备案记录编号无效');
  if (operation.index) {
    exact(request.input, ['afterId', 'limit']);
    if (!count(request.input.afterId) || !version(request.input.limit) || request.input.limit > 200)
      throw failure('INVALID_IDENTITY_INPUT', '重建游标须为非负整数，每批须为1至200条');
  } else if (operation.target) {
    exact(request.input, ['expectedVersion', 'reason']); text(request.input.reason, 1000);
    if (!version(request.input.expectedVersion)) throw failure('INVALID_IDENTITY_INPUT', '学号版本无效');
  } else {
    exact(request.input, ['namespace', 'value', 'expectedStudentVersion']);
    text(request.input.namespace, 100, true); text(request.input.value, 120, true);
    if (!version(request.input.expectedStudentVersion)) throw failure('INVALID_IDENTITY_INPUT', '学生版本无效');
  }
  return operation;
}
function indexStatus(kind, value) {
  const names = kind === 'name' ? ['totalStudents', 'indexedStudents', 'pendingStudents'] : ['totalIdentifiers', 'indexedIdentifiers', 'pendingIdentifiers'];
  if (!value || names.some(key => !count(value[key])) || value[names[1]] + value[names[2]] !== value[names[0]]
    || (kind === 'name' ? !version(value.keyVersionCount) || value.indexVersion !== 1
      : !count(value.missingSearchable) || value.missingSearchable > value.pendingIdentifiers)) throw failure('INVALID_RESPONSE', '检索维护状态无效');
  return value;
}
function endpoint(operation, request, read = false) {
  if (operation.index) return `crm/customer-search/student-${operation.index}-index/${read ? 'status' : 'rebuild'}`;
  const base = `crm/customers/${request.customerId}/students/${request.studentId}/identifiers`;
  return !read && operation.target ? `${base}/${request.identifierId}/deactivate` : base;
}
function context(operation, client, me) {
  if (client?.apiVersion !== 'v2' || !id(me?.principalId)) throw failure('INVALID_JAVA_SESSION', '需要 Java v2 员工会话');
  if (!me.permissions?.includes(operation.permission) || operation.index && me.scope !== 'all')
    throw failure('FORBIDDEN', '当前账号无此身份办理权限或数据范围');
  if (!operation.index && !me.permissions.includes('crm.customer.read')) throw failure('FORBIDDEN', '需要客户读取权限');
}
function identifier(value, studentId) {
  if (!id(value?.id) || value.studentId !== studentId || typeof value.active !== 'boolean' || !version(value.version)
    || typeof value.createdAt !== 'string' || !Number.isFinite(Date.parse(value.createdAt))
    || (value.active ? value.deactivatedAt != null : typeof value.deactivatedAt !== 'string' || !Number.isFinite(Date.parse(value.deactivatedAt))))
    throw failure('INVALID_RESPONSE', '学号备案回执无效');
  text(value.namespace, 100, true); text(value.value, 120, true); return value;
}
async function directory(operation, request, client) {
  const rows = []; let page = 1, total, studentVersion;
  do {
    const current = java.unwrap(await client.request(`${endpoint(operation, request, true)}?status=all&page=${page}&size=100`));
    if (!Array.isArray(current?.items) || !count(current.total) || current.page !== page || current.size !== 100 || page > 1000
      || !version(current.studentVersion) || total !== undefined && total !== current.total
      || studentVersion !== undefined && studentVersion !== current.studentVersion)
      throw failure('INVALID_PREVIEW', '学号目录读取中发生变化，请重新核对');
    total = current.total; studentVersion = current.studentVersion;
    current.items.forEach(value => identifier(value, request.studentId)); rows.push(...current.items);
    if (rows.length > total || !current.items.length && rows.length < total || new Set(rows.map(item => item.id)).size !== rows.length)
      throw failure('INVALID_PREVIEW', '学号目录分页不完整或重复');
    page++;
  } while (rows.length < total);
  return { items: rows, studentVersion };
}
async function prepare(name, request, { client, me } = {}) {
  const operation = validateRequest(name, request); request = clone(request);
  client ||= auth.sessionClient(); me ||= await java.identity(client); context(operation, client, me);
  let current;
  if (operation.index) current = indexStatus(operation.index, java.unwrap(await client.request(endpoint(operation, request, true))));
  else {
    const found = await directory(operation, request, client);
    if (operation.target) {
      current = found.items.find(item => item.id === request.identifierId);
      if (!current || current.version !== request.input.expectedVersion) throw failure('VERSION_CONFLICT', '学号记录已变，请重新核对');
    } else {
      if (found.studentVersion !== request.input.expectedStudentVersion) throw failure('VERSION_CONFLICT', '学生资料已变，请重新核对');
      if (found.items.some(item => item.active && item.namespace === request.input.namespace && item.value === request.input.value))
        throw failure('ALREADY_EXISTS', '此孩子已备案相同来源的学号');
      current = found;
    }
  }
  const review = { kind: 'client_review', serverPreview: false, current: clone(current), requested: clone(request.input) };
  const plan = { format: 'siyue-java-identity-plan-v1', apiVersion: 'v2', operationId: operation.id,
    server: client.server, principalId: me.principalId, request, review, idempotencyKey: crypto.randomUUID() };
  return { plan, preview: clone(review), next: '核对后提交原计划；服务端复核当前权限、范围与版本。未知结果只重试原计划。' };
}
function validatePlan(plan) {
  exact(plan, ['format', 'apiVersion', 'operationId', 'server', 'principalId', 'request', 'review', 'idempotencyKey']);
  if (plan.format !== 'siyue-java-identity-plan-v1' || plan.apiVersion !== 'v2' || typeof plan.server !== 'string' || !id(plan.principalId)
    || typeof plan.idempotencyKey !== 'string' || !/^[A-Za-z0-9_-]{16,100}$/.test(plan.idempotencyKey)) throw failure('INVALID_PLAN', '身份办理计划无效');
  const operation = validateRequest(plan.operationId, plan.request);
  if (plan.review?.kind !== 'client_review' || plan.review.serverPreview !== false
    || JSON.stringify(plan.review.requested) !== JSON.stringify(plan.request.input)) throw failure('INVALID_PLAN', '计划内容与已核对请求不符');
  if (operation.index) indexStatus(operation.index, plan.review.current);
  else if (operation.target) {
    const current = identifier(plan.review.current, plan.request.studentId);
    if (current.id !== plan.request.identifierId || current.version !== plan.request.input.expectedVersion) throw failure('INVALID_PLAN', '计划目标与原核对版本不符');
  } else if (plan.review.current?.studentVersion !== plan.request.input.expectedStudentVersion) throw failure('INVALID_PLAN', '计划学生版本不符');
  return operation;
}
function validateReceipt(operation, plan, result) {
  const request = plan.request, invalid = () => { throw failure('INVALID_RESPONSE', '回执与原请求不符，请保留原计划核对或重试'); };
  if (operation.index) {
    if (!result || !count(result.processed) || result.processed > request.input.limit || !count(result.nextAfterId)
      || result.nextAfterId < request.input.afterId || typeof result.hasMore !== 'boolean'
      || result.processed === 0 && (result.hasMore || result.nextAfterId !== request.input.afterId)
      || result.processed > 0 && result.nextAfterId === request.input.afterId) invalid();
    indexStatus(operation.index, result.status); return result;
  }
  identifier(result, request.studentId);
  if (operation.target) {
    const current = plan.review.current;
    if (result.id !== request.identifierId || result.active || result.namespace !== current.namespace || result.value !== current.value
      || result.version !== request.input.expectedVersion + (current.active ? 1 : 0)) invalid();
  } else if (!result.active || result.namespace !== request.input.namespace || result.value !== request.input.value || result.version !== 1) invalid();
  return result;
}
async function executePlan(plan, { client, me } = {}) {
  const operation = validatePlan(plan);
  client ||= auth.sessionClient(); me ||= await java.identity(client); context(operation, client, me);
  if (auth.serverURL(plan.server) !== client.server) throw failure('INVALID_PLAN', '计划不属于当前 Java 服务端');
  if (plan.principalId !== me.principalId) throw failure('WRONG_ACTOR', '请使用原核对员工身份提交');
  // Replay the exact frozen request. Re-reading current data here would discard a successful-but-unacknowledged write.
  return validateReceipt(operation, plan, java.unwrap(await client.request(endpoint(operation, plan.request), {
    method: operation.method, body: plan.request.input, idempotencyKey: plan.idempotencyKey,
  })));
}
async function createPlan(name, request, output, options) {
  const prepared = await prepare(name, request, options), file = path.resolve(output);
  business.write(file, prepared.plan);
  return { operationId: prepared.plan.operationId, preview: prepared.preview, planFile: file, commit: false, next: prepared.next };
}
module.exports = { operations, definition, validateRequest, indexStatus, prepare, validatePlan, validateReceipt, executePlan,
  createPlan, execute: (file, options) => executePlan(business.read(file), options) };
