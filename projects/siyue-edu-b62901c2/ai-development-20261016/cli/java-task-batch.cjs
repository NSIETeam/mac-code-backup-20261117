'use strict';

const crypto = require('node:crypto');
const path = require('node:path');
const auth = require('./auth-client.cjs');
const business = require('./business-client.cjs');
const java = require('./java-query-client.cjs');
const failure = (code, message) => Object.assign(new Error(message), { code });
const allowed = new Set(['title', 'taskType', 'serviceType', 'customerIds', 'activityId', 'subject', 'term',
  'ownerIds', 'strategy', 'quotas', 'dueAt', 'note', 'targets']);
const idempotencyKey = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{16,128}$/.test(value);

function validateInput(input, { requireFuture = true } = {}) {
  if (!input || typeof input !== 'object' || Array.isArray(input)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(input)) || Object.getOwnPropertySymbols(input).length)
    throw failure('INVALID_TASK_BATCH', '任务批次输入必须为 JSON 对象');
  for (const key of Object.keys(input)) if (!allowed.has(key)) throw failure('INVALID_TASK_BATCH', `不支持任务字段 ${key}`);
  const text = (key, max, required = false) => {
    const value = input[key];
    if (required && (typeof value !== 'string' || !value.trim())
      || value !== undefined && value !== null && (typeof value !== 'string' || value.length > max
        || /[\u0000-\u001f\u007f]/.test(value))) throw failure('INVALID_TASK_BATCH', `${key} 无效`);
  };
  text('title', 200, true); text('taskType', 16, true); text('serviceType', 100);
  text('activityId', 120); text('subject', 100); text('term', 100); text('note', 5000);
  if (!['marketing', 'service'].includes(input.taskType)) throw failure('INVALID_TASK_BATCH', 'taskType 无效');
  if (input.taskType === 'service' && !input.serviceType?.trim())
    throw failure('INVALID_TASK_BATCH', '服务任务必须填写服务事项');
  const targets = normalizedTargets(input);
  if (targets.some(target => target.sources?.length) && !input.activityId?.trim())
    throw failure('INVALID_TASK_BATCH', '来源证据必须关联活动');
  if (requireFuture && input.activityId?.trim() && targets.some(target => !target.sources?.length))
    throw failure('INVALID_TASK_BATCH', '活动任务每个需求必须提供真实活动来源');
  if (!Array.isArray(input.ownerIds) || input.ownerIds.length < 1 || input.ownerIds.length > 100
    || input.ownerIds.some(value => typeof value !== 'string' || !value.trim() || value.length > 120)
    || new Set(input.ownerIds).size !== input.ownerIds.length)
    throw failure('INVALID_TASK_BATCH', 'ownerIds 须为 1–100 个互不重复的员工编号');
  if (!['even', 'quota', 'owner_first'].includes(input.strategy))
    throw failure('INVALID_TASK_BATCH', 'strategy 无效');
  if (input.quotas !== undefined && input.quotas !== null) {
    if (!Array.isArray(input.quotas) || input.quotas.length > 100
      || input.quotas.some(item => !item || typeof item !== 'object' || Array.isArray(item)
        || Object.keys(item).some(key => !['ownerId', 'count'].includes(key))
        || typeof item.ownerId !== 'string' || !item.ownerId.trim() || item.ownerId.length > 120
        || !Number.isSafeInteger(item.count) || item.count < 0 || item.count > 10000)
      || new Set(input.quotas.map(item => item.ownerId)).size !== input.quotas.length)
      throw failure('INVALID_TASK_BATCH', 'quotas 格式无效');
  }
  if (input.strategy === 'quota' && (!Array.isArray(input.quotas) || input.quotas.length !== input.ownerIds.length)
    || input.strategy !== 'quota' && input.quotas?.length)
    throw failure('INVALID_TASK_BATCH', '名额只可配合 quota 策略，并须覆盖全部负责人');
  const dueAt = input.dueAt;
  const dueMatch = typeof dueAt === 'string'
    && /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|([+-])(\d{2}):(\d{2}))$/.exec(dueAt);
  const date = dueMatch && new Date(Date.UTC(Number(dueMatch[1]), Number(dueMatch[2]) - 1, Number(dueMatch[3])));
  const offsetHours = dueMatch?.[9] ? Number(dueMatch[9]) : 0;
  const offsetMinutes = dueMatch?.[10] ? Number(dueMatch[10]) : 0;
  if (!dueMatch || date.getUTCFullYear() !== Number(dueMatch[1]) || date.getUTCMonth() + 1 !== Number(dueMatch[2])
    || date.getUTCDate() !== Number(dueMatch[3]) || Number(dueMatch[4]) > 23
    || Number(dueMatch[5]) > 59 || Number(dueMatch[6]) > 59 || offsetHours > 18 || offsetMinutes > 59
    || offsetHours === 18 && offsetMinutes !== 0
    || !Number.isFinite(Date.parse(dueAt)) || requireFuture && Date.parse(dueAt) <= Date.now())
    throw failure('INVALID_TASK_BATCH', 'dueAt 须为有效的未来带时区时间');
  return input;
}

function normalizedTargets(input) {
  const explicit = input.targets !== undefined && input.targets !== null;
  const legacy = input.customerIds !== undefined && input.customerIds !== null;
  if (explicit === legacy) throw failure('INVALID_TASK_BATCH', 'customerIds 与 targets 必须且只能提供一个');
  const source = explicit ? input.targets : Array.isArray(input.customerIds)
    ? input.customerIds.map(customerId => ({ customerId })) : null;
  if (!Array.isArray(source) || source.length < 1 || source.length > 500)
    throw failure('INVALID_TASK_BATCH', '任务目标须为 1–500 个需求');
  const optional = value => value === undefined || value === null ? null : value.trim() || null;
  const known = new Set();
  return source.map(target => {
    if (!target || typeof target !== 'object' || Array.isArray(target)
      || ![Object.prototype, null].includes(Object.getPrototypeOf(target))
      || Object.getOwnPropertySymbols(target).length
      || Object.keys(target).some(key => !['customerId', 'studentId', 'subject', 'term', 'sources', 'needIdentity'].includes(key)))
      throw failure('INVALID_TASK_BATCH', '任务需求格式无效');
    for (const key of ['customerId', 'studentId', 'subject', 'term']) {
      const value = target[key], limit = key.endsWith('Id') ? 120 : 100;
      if (key === 'customerId' && (typeof value !== 'string' || !value.trim())
        || value !== undefined && value !== null && (typeof value !== 'string' || value.length > limit
          || /[\u0000-\u001f\u007f]/.test(value)))
        throw failure('INVALID_TASK_BATCH', `需求 ${key} 无效`);
    }
    const row = { customerId: target.customerId, studentId: optional(target.studentId),
      subject: optional(target.subject ?? input.subject), term: optional(target.term ?? input.term) };
    if (target.needIdentity != null) row.needIdentity = normalizedNeedIdentity(target.needIdentity);
    const sources = normalizedSources(target.sources);
    if (sources.length) row.sources = sources;
    if (input.taskType === 'marketing' && (!row.subject || !row.term))
      throw failure('INVALID_TASK_BATCH', '每个营销需求必须填写学科和学期');
    const key = targetIdentity(row);
    if (known.has(key)) throw failure('INVALID_TASK_BATCH', '同一学生和购买需求不能在批次中重复');
    known.add(key);
    return row;
  });
}

function normalizedNeedIdentity(value) {
  const id = input => typeof input === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(input);
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length
    || Object.keys(value).some(key => !['departmentId', 'projectId'].includes(key)) || !id(value.departmentId)
    || value.projectId != null && !id(value.projectId))
    throw failure('INVALID_TASK_BATCH', '需求部门与项目编号无效');
  return { departmentId: value.departmentId, projectId: value.projectId ?? null };
}

function normalizedSources(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > 100) throw failure('INVALID_TASK_BATCH', '每个需求最多 100 项来源');
  const seen = new Set();
  return value.map(ref => {
    if (!ref || typeof ref !== 'object' || Array.isArray(ref)
      || ![Object.prototype, null].includes(Object.getPrototypeOf(ref)) || Object.getOwnPropertySymbols(ref).length
      || Object.keys(ref).some(key => !['type', 'id', 'version'].includes(key))
      || !['lead', 'touchpoint'].includes(ref.type) || typeof ref.id !== 'string'
      || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(ref.id)
      || ref.type === 'lead' && (!Number.isSafeInteger(ref.version) || ref.version < 1)
      || ref.type === 'touchpoint' && ref.version !== undefined && ref.version !== null)
      throw failure('INVALID_TASK_BATCH', '来源类型、编号或版本无效');
    const key = `${ref.type}:${ref.id}`;
    if (seen.has(key)) throw failure('INVALID_TASK_BATCH', '同一需求的来源不能重复');
    seen.add(key);
    return { type: ref.type, id: ref.id, version: ref.version ?? null };
  }).sort((a, b) => a.type < b.type ? -1 : a.type > b.type ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

function targetIdentity(row) {
  const fold = value => typeof value === 'string' ? value.toLowerCase() : value ?? null;
  const base = [row.customerId, row.studentId ?? null, fold(row.subject), fold(row.term)];
  if (row.needIdentity != null) {
    const identity = normalizedNeedIdentity(row.needIdentity);
    base.push(identity.departmentId, identity.projectId);
  }
  return JSON.stringify(base);
}

function validatePreview(input, preview) {
  const targets = normalizedTargets(input), bad = () => { throw failure('INVALID_PREVIEW', '服务端任务预览明细不一致'); };
  if (!preview || preview.selectedCount !== targets.length || !Number.isSafeInteger(preview.eligibleCount)
    || !Number.isSafeInteger(preview.skippedCount) || preview.eligibleCount < 0 || preview.skippedCount < 0
    || preview.eligibleCount + preview.skippedCount !== targets.length
    || !preview.assignmentCounts || typeof preview.assignmentCounts !== 'object' || Array.isArray(preview.assignmentCounts)) bad();
  let assigned;
  if (input.targets != null) {
    const eligible = preview.eligibleTargets || [], skipped = preview.skippedTargets || [];
    if (!Array.isArray(eligible) || !Array.isArray(skipped) || eligible.length !== preview.eligibleCount
      || skipped.length !== preview.skippedCount) bad();
    const expected = new Set(targets.map(targetIdentity)), keys = new Set();
    const sourceByTarget = new Map(targets.map(row => [targetIdentity(row), JSON.stringify(row.sources || [])]));
    for (const row of [...eligible, ...skipped]) {
      if (!row || !expected.delete(targetIdentity(row)) || !/^[a-f0-9]{64}$/.test(row.targetKey)
        || keys.has(row.targetKey)) bad();
      keys.add(row.targetKey);
      try { if (JSON.stringify(normalizedSources(row.sources)) !== sourceByTarget.get(targetIdentity(row))) bad(); }
      catch (_) { bad(); }
    }
    if (expected.size || skipped.some(row => !['open_marketing_task', 'identity_review_required'].includes(row.reason))) bad();
    assigned = eligible.map(row => row.ownerId);
  } else {
    const eligible = preview.eligibleCustomerIds, skipped = preview.skippedCustomerIds;
    const owners = preview.customerAssignments;
    if (!Array.isArray(eligible) || !Array.isArray(skipped) || !owners || typeof owners !== 'object'
      || eligible.length !== preview.eligibleCount || skipped.length !== preview.skippedCount
      || Object.keys(owners).length !== eligible.length) bad();
    const ids = [...eligible, ...skipped];
    if (new Set(ids).size !== targets.length || ids.some(id => !input.customerIds.includes(id))
      || eligible.some(id => !Object.hasOwn(owners, id))) bad();
    assigned = eligible.map(id => owners[id]);
  }
  const counts = Object.fromEntries(input.ownerIds.map(id => [id, 0]));
  for (const owner of assigned) { if (!Object.hasOwn(counts, owner)) bad(); counts[owner]++; }
  if (Object.keys(preview.assignmentCounts).length !== input.ownerIds.length
    || Object.entries(counts).some(([owner, count]) => preview.assignmentCounts[owner] !== count)) bad();
  return preview;
}

function buildPlan({ input, preview, client, me, key = crypto.randomUUID() }) {
  validateInput(input);
  if (!idempotencyKey(key)) throw failure('INVALID_IDEMPOTENCY_KEY', '任务批次幂等键无效');
  if (!client || client.apiVersion !== 'v2' || typeof me?.principalId !== 'string' || !me.principalId)
    throw failure('INVALID_JAVA_SESSION', '需要有效的 Java v2 员工会话');
  validatePreview(input, preview);
  return { format: 'siyue-java-plan-v1', apiVersion: 'v2', operationId: 'crm.task-batch.create',
    server: client.server, principalId: me.principalId, input, preview, idempotencyKey: key };
}

async function fetchPreview(input, client) {
  validateInput(input);
  if (!client || client.apiVersion !== 'v2') throw failure('INVALID_JAVA_SESSION', '需要有效的 Java v2 员工会话');
  return java.unwrap(await client.request('crm/tasks/batches/preview', { method: 'POST', body: input }));
}

async function createPlan(input, outputFile, { client, me } = {}) {
  client ||= auth.sessionClient();
  me ||= await java.identity(client);
  const preview = await fetchPreview(input, client);
  const plan = buildPlan({ input, preview, client, me });
  const file = path.resolve(outputFile);
  business.write(file, plan);
  return { operationId: plan.operationId, preview, planFile: file, commit: false,
    next: `核对逐学生需求分配后运行 siyue-cli execute --plan ${JSON.stringify(file)}` };
}

function validatePlan(plan) {
  if (!plan || typeof plan !== 'object' || Array.isArray(plan) || plan.format !== 'siyue-java-plan-v1'
    || plan.apiVersion !== 'v2' || plan.operationId !== 'crm.task-batch.create'
    || typeof plan.server !== 'string' || typeof plan.principalId !== 'string' || !plan.principalId
    || !idempotencyKey(plan.idempotencyKey)) throw failure('INVALID_PLAN', 'Java 任务批次计划无效');
  validateInput(plan.input, { requireFuture: false });
  validatePreview(plan.input, plan.preview);
  return plan;
}

async function executePlan(rawPlan, { client, me } = {}) {
  const plan = validatePlan(rawPlan);
  client ||= auth.sessionClient();
  if (client.apiVersion !== 'v2' || auth.serverURL(plan.server) !== client.server)
    throw failure('INVALID_PLAN', '计划不属于当前 Java API 会话');
  me ||= await java.identity(client);
  if (me.principalId !== plan.principalId) throw failure('WRONG_ACTOR', '必须使用预览时的员工身份提交');
  if (!plan.preview.eligibleCount) throw failure('EMPTY_PREVIEW', '预览中没有可创建任务；无需提交');
  const envelope = await client.request('crm/tasks/batches/commit-preview', {
    method: 'POST', idempotencyKey: plan.idempotencyKey,
    body: { input: plan.input, expectedPreview: plan.preview },
  });
  return java.unwrap(envelope);
}

async function execute(file, context) {
  return executePlan(business.read(file), context);
}

module.exports = { validateInput, normalizedTargets, validatePreview, buildPlan, fetchPreview, createPlan, validatePlan, executePlan, execute };
