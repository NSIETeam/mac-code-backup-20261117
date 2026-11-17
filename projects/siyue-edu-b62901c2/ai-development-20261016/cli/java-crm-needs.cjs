'use strict';

const crypto = require('node:crypto');
const path = require('node:path');
const auth = require('./auth-client.cjs');
const business = require('./business-client.cjs');
const java = require('./java-query-client.cjs');
const failure = (code, message) => Object.assign(new Error(message), { code });
const kinds = ['department', 'project', 'grade', 'class_type', 'level', 'fiscal_year', 'quarter', 'judgment', 'judgment_reason'];
const profileFields = ['gradeId', 'preferredCampusId', 'classTypeId', 'levelId', 'fiscalYearId', 'quarterId'];
const actions = ['create', 'reuse', 'classify_existing', 'create_distinct'];
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value);
const positive = value => Number.isSafeInteger(value) && value > 0;
const keyValid = value => typeof value === 'string' && /^[A-Za-z0-9_-]{16,100}$/.test(value);
const operations = Object.freeze([
  { name: 'need-intake', id: 'crm.opportunity.intake', label: '接待需求核对', permission: 'crm.opportunity.intake', method: 'POST', suffix: 'commit-preview' },
  { name: 'need-profile', id: 'crm.opportunity.profile.update', label: '更新需求资料', permission: 'crm.opportunity.write', method: 'PUT', suffix: 'profile', target: true },
  { name: 'need-judgment', id: 'crm.opportunity.judgment.record', label: '记录商机判定', permission: 'crm.opportunity.write', method: 'POST', suffix: 'judgments', target: true },
  { name: 'need-assign', id: 'crm.opportunity.assign', label: '分派商机', permission: 'crm.opportunity.assign', method: 'PUT', suffix: 'assignment', target: true },
  { name: 'need-option-create', id: 'crm.opportunity.config.create', label: '新增需求配置', permission: 'crm.opportunity.config.manage', method: 'POST', config: true },
  { name: 'need-option-update', id: 'crm.opportunity.config.update', label: '编辑需求配置', permission: 'crm.opportunity.config.manage', method: 'PUT', config: true, target: true },
  { name: 'need-notification-read', id: 'crm.opportunity.notification.read', label: '标记本人提醒已读', permission: 'crm.opportunity.read', method: 'PUT', notification: true, suffix: 'read', target: true },
]);

function record(value, allowed) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length
    || Object.keys(value).some(key => !allowed.includes(key))) throw failure('INVALID_NEED_INPUT', '需求输入包含无效对象或字段');
  return value;
}
function text(value, max, required = false, multiline = false) {
  if (value == null && !required) return;
  if (typeof value !== 'string' || value.length > max || required && !value.trim()
    || (multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/ : /[\u0000-\u001f\u007f]/).test(value))
    throw failure('INVALID_NEED_INPUT', '需求文字为空、过长或包含控制字符');
}
function identifier(value, required = false) {
  if (value == null && !required) return;
  if (!id(value)) throw failure('INVALID_NEED_INPUT', '需求目标编号无效');
}
function version(value, allowZero = false) {
  if (!(positive(value) || allowZero && value === 0)) throw failure('INVALID_NEED_INPUT', '需求版本无效');
}
function identity(value) {
  record(value, ['departmentId', 'projectId']); identifier(value.departmentId, true); identifier(value.projectId);
}
function profile(value) {
  record(value, profileFields); for (const field of profileFields) identifier(value[field]);
}
function validateDecision(value) {
  record(value, ['action', 'targetRef', 'reason']);
  if (!actions.includes(value.action)) throw failure('INVALID_NEED_INPUT', '请选择明确的需求处理方式');
  if (['reuse', 'classify_existing'].includes(value.action)) identifier(value.targetRef, true);
  else if (value.targetRef != null) throw failure('INVALID_NEED_INPUT', '新建决定不能指定旧目标');
  text(value.reason, 1000, ['classify_existing', 'create_distinct'].includes(value.action), true);
  return value;
}
function validateInput(operation, input) {
  if (operation.name === 'need-intake') {
    record(input, ['customerId', 'studentId', 'sourceLeadId', 'activityId', 'title', 'subject', 'term', 'identity', 'profile', 'detail', 'ownerId']);
    identifier(input.customerId, true);
    for (const key of ['studentId', 'sourceLeadId', 'activityId', 'ownerId']) identifier(input[key]);
    text(input.title, 200, true); text(input.subject, 100, true); text(input.term, 100, true);
    identity(input.identity); profile(input.profile); text(input.detail, 5000, true, true);
  } else if (operation.name === 'need-profile') {
    record(input, ['profile', 'expectedVersion', 'expectedCatalogVersion']); profile(input.profile);
    version(input.expectedVersion); version(input.expectedCatalogVersion);
  } else if (operation.name === 'need-judgment') {
    record(input, ['judgmentId', 'reasonId', 'detail', 'expectedVersion', 'expectedCatalogVersion']);
    identifier(input.judgmentId, true); identifier(input.reasonId); text(input.detail, 1000, true, true);
    version(input.expectedVersion); version(input.expectedCatalogVersion);
  } else if (operation.name === 'need-assign') {
    record(input, ['ownerId', 'reason', 'expectedVersion']); identifier(input.ownerId, true);
    text(input.reason, 1000, true, true); version(input.expectedVersion);
  } else if (operation.name === 'need-option-create') {
    record(input, ['kind', 'code', 'label', 'parentId', 'campusId', 'projectMode', 'effect']);
    if (!kinds.includes(input.kind) || typeof input.code !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,59}$/.test(input.code))
      throw failure('INVALID_NEED_INPUT', '配置分类或代码无效');
    text(input.label, 100, true); identifier(input.parentId); identifier(input.campusId);
    if (input.kind === 'department' ? !['required', 'not_used'].includes(input.projectMode) : input.projectMode != null)
      throw failure('INVALID_NEED_INPUT', '只有部门必须设置项目使用规则');
    if (input.kind === 'judgment' ? !['continue', 'close'].includes(input.effect) : input.effect != null)
      throw failure('INVALID_NEED_INPUT', '只有判定配置必须设置继续或关闭效果');
    if (['project', 'judgment_reason'].includes(input.kind)) identifier(input.parentId, true);
    else if (input.kind !== 'quarter' && input.parentId != null) throw failure('INVALID_NEED_INPUT', '此分类不能指定父项');
  } else if (operation.name === 'need-option-update') {
    record(input, ['label', 'active', 'expectedVersion']); text(input.label, 100, true); version(input.expectedVersion);
    if (typeof input.active !== 'boolean') throw failure('INVALID_NEED_INPUT', 'active 必须是布尔值');
  } else {
    record(input, ['expectedVersion']); version(input.expectedVersion);
  }
  return input;
}
function definition(name) {
  const operation = operations.find(item => item.name === name || item.id === name);
  if (!operation) throw failure('JAVA_V2_UNAVAILABLE', '未开放此 Java 需求操作');
  return operation;
}
function validateRequest(name, request) {
  const operation = definition(name); record(request, ['input', 'targetId', 'decision']);
  validateInput(operation, request.input);
  if (operation.target) identifier(request.targetId, true);
  else if (request.targetId != null) throw failure('INVALID_NEED_INPUT', '该操作不能指定目标编号');
  if (request.decision != null) {
    if (operation.name !== 'need-intake') throw failure('INVALID_NEED_INPUT', '只有接待操作需要需求决定');
    validateDecision(request.decision);
  }
  return operation;
}
function endpoint(operation, targetId) {
  const base = operation.config ? 'crm/opportunity-config'
    : operation.notification ? 'crm/opportunity-notifications' : 'crm/opportunity-needs';
  return base + (operation.target ? `/${targetId}` : '') + (operation.suffix ? `/${operation.suffix}` : '');
}
function validatePreview(input, preview, decision) {
  if (!preview || !Number.isSafeInteger(preview.catalogVersion) || preview.catalogVersion < 0
    || !positive(preview.customerVersion) || preview.scopeCampusId !== null && !id(preview.scopeCampusId)
    || typeof preview.inputHash !== 'string' || !/^[a-f0-9]{64}$/.test(preview.inputHash)
    || !Array.isArray(preview.candidates) || !Array.isArray(preview.allowedActions)
    || preview.allowedActions.some(value => !actions.includes(value)) || !Array.isArray(preview.differences))
    throw failure('INVALID_PREVIEW', '需求预览格式无效');
  const seen = new Set();
  const fold = value => typeof value === 'string' ? value.trim().toLowerCase() : value ?? null;
  for (const row of preview.candidates) {
    if (!id(row.targetRef) || seen.has(row.targetRef) || !positive(row.version)
      || !['exact', 'legacy_unknown', 'different_need'].includes(row.match)
      || fold(row.subject) !== fold(input.subject) || fold(row.term) !== fold(input.term)
      || row.studentVisibility !== 'restricted' && (row.studentId ?? null) !== (input.studentId ?? null)
      || row.match === 'exact' && (row.identity?.departmentId !== input.identity.departmentId
        || (row.identity?.projectId ?? null) !== (input.identity.projectId ?? null)))
      throw failure('INVALID_PREVIEW', '需求预览候选无效');
    seen.add(row.targetRef);
  }
  if (decision) {
    validateDecision(decision);
    if (!preview.allowedActions.includes(decision.action)) throw failure('INVALID_DECISION', '当前需求预览不允许所选处理方式');
    const candidate = preview.candidates.find(row => row.targetRef === decision.targetRef);
    if (decision.action === 'reuse' && candidate?.match !== 'exact'
      || decision.action === 'classify_existing' && candidate?.match !== 'legacy_unknown')
      throw failure('INVALID_DECISION', '指定目标与需求处理方式不一致');
  }
  return preview;
}
async function readAll(endpointPath, client) {
  let page = 1, rows = [], total;
  do {
    const separator = endpointPath.includes('?') ? '&' : '?';
    const value = java.unwrap(await client.request(`${endpointPath}${separator}page=${page}&size=100`));
    if (!Array.isArray(value?.items) || !Number.isSafeInteger(value.total) || value.total < 0 || value.page !== page
      || value.size !== 100 || page > 1000 || total !== undefined && total !== value.total)
      throw failure('INVALID_PREVIEW', '办理目录在读取期间变化或格式无效，请重新核对');
    total = value.total; rows.push(...value.items);
    if (!value.items.length && rows.length < total) throw failure('INVALID_PREVIEW', '办理目录分页不完整');
    page++;
  } while (rows.length < total);
  return rows;
}
async function fetchReview(operation, request, client) {
  if (operation.name === 'need-intake') return validatePreview(request.input,
    java.unwrap(await client.request('crm/opportunity-needs/preview', { method: 'POST', body: request.input })), request.decision);
  if (operation.config) {
    const items = await readAll('crm/opportunity-config?status=all', client);
    const current = operation.target ? items.find(row => row.id === request.targetId) : null;
    if (operation.target && (!current || current.version !== request.input.expectedVersion))
      throw failure('VERSION_CONFLICT', '配置不存在或版本已改变，请重新核对');
    return { current, requested: request.input, kind: 'client_review', serverPreview: false };
  }
  if (operation.notification) {
    const current = (await readAll('crm/opportunity-notifications?status=all', client)).find(row => row.id === request.targetId);
    if (!current || current.version !== request.input.expectedVersion) throw failure('VERSION_CONFLICT', '提醒不存在或版本已改变');
    return { current, requested: request.input, kind: 'client_review', serverPreview: false };
  }
  const current = java.unwrap(await client.request(`crm/opportunity-needs/${request.targetId}`));
  if (current?.opportunity?.id !== request.targetId || current.opportunity.version !== request.input.expectedVersion)
    throw failure('VERSION_CONFLICT', '商机已改变，请读取最新资料后重新核对');
  let options = null;
  if (['need-profile', 'need-judgment'].includes(operation.name)) {
    options = java.unwrap(await client.request(`crm/opportunity-needs/${request.targetId}/options`));
    if (options?.catalogVersion !== request.input.expectedCatalogVersion) throw failure('VERSION_CONFLICT', '需求配置版本已改变');
  }
  if (operation.name === 'need-assign') {
    const candidates = await readAll(`crm/opportunity-needs/assignees?opportunityId=${request.targetId}`, client);
    if (!candidates.some(row => row.id === request.input.ownerId)) throw failure('INVALID_ASSIGNEE', '所选员工不是当前商机的候选负责人');
  }
  return { current, requested: request.input, options, kind: 'client_review', serverPreview: false };
}
function context(client, me) {
  if (client?.apiVersion !== 'v2' || !id(me?.principalId)) throw failure('INVALID_JAVA_SESSION', '需要 Java v2 员工会话');
}
async function prepare(name, request, { client, me } = {}) {
  const operation = validateRequest(name, request);
  request = JSON.parse(JSON.stringify(request));
  client ||= auth.sessionClient(); me ||= await java.identity(client); context(client, me);
  const review = await fetchReview(operation, request, client);
  if (operation.name === 'need-intake' && !request.decision) return { preview: review, plan: null,
    next: '核对候选与差异，再在输入文件的 decision 中明确 action、targetRef 和理由，重新生成计划。' };
  const plan = { format: 'siyue-java-need-plan-v1', apiVersion: 'v2', operationId: operation.id,
    server: client.server, principalId: me.principalId, request, review: JSON.parse(JSON.stringify(review)), idempotencyKey: crypto.randomUUID() };
  return { preview: review, plan, next: '核对资料和处理方式后提交同一计划；未知结果只重试原计划。' };
}
function validatePlan(plan) {
  record(plan, ['format', 'apiVersion', 'operationId', 'server', 'principalId', 'request', 'review', 'idempotencyKey']);
  if (plan.format !== 'siyue-java-need-plan-v1' || plan.apiVersion !== 'v2' || !id(plan.principalId)
    || typeof plan.server !== 'string' || !keyValid(plan.idempotencyKey)) throw failure('INVALID_PLAN', 'Java 需求计划无效');
  const operation = validateRequest(plan.operationId, plan.request);
  if (operation.name === 'need-intake') {
    if (!plan.request.decision) throw failure('INVALID_PLAN', '计划缺少明确需求决定');
    validatePreview(plan.request.input, plan.review, plan.request.decision);
  } else if (!plan.review || plan.review.kind !== 'client_review' || plan.review.serverPreview !== false
    || JSON.stringify(plan.review.requested) !== JSON.stringify(plan.request.input))
    throw failure('INVALID_PLAN', '计划输入与已核对资料不一致');
  return operation;
}
function validateReceipt(operation, request, value) {
  const invalid = () => { throw failure('INVALID_RESPONSE', '需求回执与原请求不一致；请保留原计划并核对或重试，勿新建计划重复提交'); };
  if (!value || typeof value !== 'object') invalid();
  if (operation.name === 'need-intake') {
    const outcomes = { create: 'created', reuse: 'reused', classify_existing: 'classified', create_distinct: 'created_distinct' };
    if (value.outcome !== outcomes[request.decision.action] || !id(value.opportunityId) || !id(value.consultationId)
      || value.ownerChanged !== false || value.identityStatus !== 'classified'
      || !['not_required', 'recorded'].includes(value.notificationState)
      || request.decision.targetRef && value.opportunityId !== request.decision.targetRef
      || value.opportunity && value.opportunity.id !== value.opportunityId) invalid();
  } else if (operation.name === 'need-assign') {
    if (value.opportunityId !== request.targetId || value.ownerId !== request.input.ownerId
      || value.version !== request.input.expectedVersion + 1 || !['visible', 'left_scope'].includes(value.scopeStatus)) invalid();
  } else if (operation.config) {
    const item = value.item;
    if (!id(item?.id) || !Number.isSafeInteger(value.catalogVersion) || value.catalogVersion < 1
      || item.label !== request.input.label) invalid();
    if (operation.target ? item.id !== request.targetId || item.active !== request.input.active
        || item.version !== request.input.expectedVersion + 1
      : item.kind !== request.input.kind || item.code !== request.input.code || item.version !== 1 || item.active !== true) invalid();
  } else if (operation.notification) {
    if (value.id !== request.targetId || !value.readAt || !positive(value.version)
      || value.version < request.input.expectedVersion) invalid();
  } else if (value.opportunity?.id !== request.targetId
    || value.opportunity.version !== request.input.expectedVersion + 1) invalid();
  return value;
}
async function executePlan(plan, { client, me } = {}) {
  const operation = validatePlan(plan);
  client ||= auth.sessionClient(); me ||= await java.identity(client); context(client, me);
  if (auth.serverURL(plan.server) !== client.server) throw failure('INVALID_PLAN', '计划不属于当前 Java 服务端');
  if (plan.principalId !== me.principalId) throw failure('WRONG_ACTOR', '请使用核对时的员工身份提交');
  // Never re-preview a successful-but-unacknowledged operation: the server reauthorizes and replays the original key.
  const body = operation.name === 'need-intake'
    ? { input: plan.request.input, expectedPreview: plan.review, decision: plan.request.decision } : plan.request.input;
  return validateReceipt(operation, plan.request, java.unwrap(await client.request(endpoint(operation, plan.request.targetId), {
    method: operation.method, idempotencyKey: plan.idempotencyKey, body,
  })));
}
async function createPlan(name, request, outputFile, contextValue) {
  const result = await prepare(name, request, contextValue);
  if (!result.plan) return { preview: result.preview, commit: false, planFile: null, next: result.next };
  const file = path.resolve(outputFile); business.write(file, result.plan);
  return { operationId: result.plan.operationId, preview: result.preview, decision: request.decision || null,
    planFile: file, commit: false, next: `核对后运行 siyue-cli execute --plan ${JSON.stringify(file)}` };
}
module.exports = { operations, kinds, profileFields, definition, validateRequest, validateDecision, validatePreview,
  prepare, createPlan, validatePlan, validateReceipt, executePlan, execute: (file, contextValue) => executePlan(business.read(file), contextValue) };
