'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const java = require("./java-query-client.cjs");
const path = require("node:path");
const crypto = require("node:crypto");
const operations = [{ id: 'crm.task-batch.create', name: 'task-batch-create', label: '创建任务批次', permission: 'crm.task.write', method: 'POST', endpoint: 'crm/tasks/batches/preview' }];
function fail(code, message) { throw auth.failure(code, message); }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function exact(value, allowed) {
    if (!object(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length || Object.keys(value).some(k => !allowed.includes(k)))
        fail('INVALID_TASK_BATCH', '任务批次输入含无效字段');
}
function identifier(value, max = 120) { return typeof value === 'string' && value.trim().length > 0 && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value); }
function keyValid(value) { return typeof value === 'string' && /^[A-Za-z0-9._:-]{16,128}$/.test(value); }
function normalizeSources(value) {
    if (value == null)
        return [];
    if (!Array.isArray(value) || value.length > 100)
        fail('INVALID_TASK_BATCH', '每个需求最多 100 项来源');
    const seen = new Set();
    const result = value.map((entry) => {
        exact(entry, ['type', 'id', 'version']);
        if ((entry.type !== 'lead' && entry.type !== 'touchpoint') || typeof entry.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(entry.id) ||
            (entry.type === 'lead' ? !Number.isSafeInteger(entry.version) || Number(entry.version) < 1 : entry.version != null))
            fail('INVALID_TASK_BATCH', '来源类型、编号或版本无效');
        const key = `${entry.type}:${entry.id}`;
        if (seen.has(key))
            fail('INVALID_TASK_BATCH', '来源不能重复');
        seen.add(key);
        return { type: entry.type, id: entry.id, version: entry.version == null ? null : Number(entry.version) };
    });
    return result.sort((a, b) => a.type.localeCompare(b.type) || a.id.localeCompare(b.id));
}
function normalizeIdentity(value) {
    exact(value, ['departmentId', 'projectId']);
    if (typeof value.departmentId !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(value.departmentId) || value.projectId != null && (typeof value.projectId !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(value.projectId)))
        fail('INVALID_TASK_BATCH', '需求部门与项目编号无效');
    return { departmentId: value.departmentId, projectId: value.projectId ?? null };
}
function normalizedTargets(input) {
    const explicit = input.targets != null, legacy = input.customerIds != null;
    if (explicit === legacy)
        fail('INVALID_TASK_BATCH', 'customerIds 与 targets 必须且只能提供一个');
    const raw = explicit ? input.targets : input.customerIds.map(customerId => ({ customerId }));
    if (raw.length < 1 || raw.length > 500)
        fail('INVALID_TASK_BATCH', '任务目标须为 1–500 个需求');
    const seen = new Set();
    return raw.map(entry => {
        exact(entry, ['customerId', 'studentId', 'subject', 'term', 'sources', 'needIdentity']);
        if (!identifier(entry.customerId) || entry.studentId != null && !identifier(entry.studentId))
            fail('INVALID_TASK_BATCH', '需求客户/学生编号无效');
        const optionalText = (v, limit) => v == null ? null : typeof v === 'string' && v.length <= limit && !/[\u0000-\u001f\u007f]/.test(v) ? v.trim() || null : fail('INVALID_TASK_BATCH', '需求文字无效');
        const row = { customerId: entry.customerId, studentId: entry.studentId == null ? null : entry.studentId,
            subject: optionalText(entry.subject ?? input.subject, 100), term: optionalText(entry.term ?? input.term, 100) };
        if (entry.needIdentity != null)
            row.needIdentity = normalizeIdentity(entry.needIdentity);
        const sources = normalizeSources(entry.sources);
        if (sources.length)
            row.sources = sources;
        if (input.taskType === 'marketing' && (!row.subject || !row.term))
            fail('INVALID_TASK_BATCH', '每个营销需求必须填写学科和学期');
        const identity = JSON.stringify([row.customerId, row.studentId ?? null, row.subject?.toLowerCase() ?? null, row.term?.toLowerCase() ?? null,
            row.needIdentity?.departmentId ?? null, row.needIdentity?.projectId ?? null]);
        if (seen.has(identity))
            fail('INVALID_TASK_BATCH', '同一学生和购买需求不能在批次中重复');
        seen.add(identity);
        return row;
    });
}
function validateInput(input, options = {}) {
    exact(input, ['title', 'taskType', 'serviceType', 'customerIds', 'activityId', 'subject', 'term', 'ownerIds', 'strategy', 'quotas', 'dueAt', 'note', 'targets']);
    const text = (name, max, required = false) => { const v = input[name]; if (required && (typeof v !== 'string' || !v.trim()) || v != null && (typeof v !== 'string' || v.length > max || /[\u0000-\u001f\u007f]/.test(v)))
        fail('INVALID_TASK_BATCH', `${name} 无效`); };
    text('title', 200, true);
    text('taskType', 16, true);
    text('serviceType', 100);
    text('activityId', 120);
    text('subject', 100);
    text('term', 100);
    text('note', 5000);
    if (input.taskType !== 'marketing' && input.taskType !== 'service' || input.taskType === 'service' && (typeof input.serviceType !== 'string' || !input.serviceType.trim()))
        fail('INVALID_TASK_BATCH', 'taskType 或服务事项无效');
    const owners = input.ownerIds;
    if (!Array.isArray(owners) || owners.length < 1 || owners.length > 100 || owners.some(v => !identifier(v)) || new Set(owners).size !== owners.length)
        fail('INVALID_TASK_BATCH', 'ownerIds 须为 1–100 个互不重复员工编号');
    if (!['even', 'quota', 'owner_first'].includes(String(input.strategy)))
        fail('INVALID_TASK_BATCH', 'strategy 无效');
    if (input.quotas != null) {
        if (!Array.isArray(input.quotas) || input.quotas.length > 100)
            fail('INVALID_TASK_BATCH', 'quotas 格式无效');
        const seen = new Set();
        for (const q of input.quotas) {
            exact(q, ['ownerId', 'count']);
            if (!identifier(q.ownerId) || !Number.isSafeInteger(q.count) || Number(q.count) < 0 || Number(q.count) > 10000 || seen.has(String(q.ownerId)))
                fail('INVALID_TASK_BATCH', 'quotas 格式无效');
            seen.add(String(q.ownerId));
        }
    }
    if (input.strategy === 'quota' ? !Array.isArray(input.quotas) || input.quotas.length !== owners.length : Array.isArray(input.quotas) && input.quotas.length > 0)
        fail('INVALID_TASK_BATCH', '名额须只用于 quota 并覆盖所有负责人');
    const dueAt = input.dueAt;
    const match = typeof dueAt === 'string' ? /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|([+-])(\d{2}):(\d{2}))$/.exec(dueAt) : null;
    const date = match ? new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))) : null;
    const oh = Number(match?.[9] ?? 0), om = Number(match?.[10] ?? 0);
    const due = typeof dueAt === 'string' ? Date.parse(dueAt) : NaN;
    if (!match || !date || date.getUTCFullYear() !== Number(match[1]) || date.getUTCMonth() + 1 !== Number(match[2])
        || date.getUTCDate() !== Number(match[3]) || Number(match[4]) > 23 || Number(match[5]) > 59 || Number(match[6]) > 59
        || oh > 18 || om > 59 || oh === 18 && om !== 0 || !Number.isFinite(due)
        || options.requireFuture !== false && due <= Date.now())
        fail('INVALID_TASK_BATCH', 'dueAt 须为有效的未来带时区时间');
    const targets = normalizedTargets(input);
    if (targets.some(t => t.sources?.length) && !(typeof input.activityId === 'string' && input.activityId.trim()) || options.requireFuture !== false && !!input.activityId && targets.some(t => !t.sources?.length))
        fail('INVALID_TASK_BATCH', '活动任务须提供真实活动来源');
    return input;
}
function targetKey(target) { const base = [target.customerId, target.studentId ?? null, typeof target.subject === 'string' ? target.subject.toLowerCase() : null, typeof target.term === 'string' ? target.term.toLowerCase() : null]; if (target.needIdentity != null) {
    const identity = normalizeIdentity(target.needIdentity);
    base.push(identity.departmentId, identity.projectId ?? null);
} return JSON.stringify(base); }
function validatePreview(input, preview) {
    const targets = normalizedTargets(input), bad = () => fail('INVALID_PREVIEW', '服务端任务预览明细不一致');
    if (!preview || preview.selectedCount !== targets.length || !Number.isSafeInteger(preview.eligibleCount) || !Number.isSafeInteger(preview.skippedCount) || preview.eligibleCount < 0 || preview.skippedCount < 0 || preview.eligibleCount + preview.skippedCount !== targets.length || !object(preview.assignmentCounts))
        bad();
    const assigned = [];
    if (input.targets != null) {
        const eligibleValue = preview.eligibleTargets ?? [], skippedValue = preview.skippedTargets ?? [];
        if (!Array.isArray(eligibleValue) || !Array.isArray(skippedValue) || eligibleValue.length !== preview.eligibleCount || skippedValue.length !== preview.skippedCount)
            bad();
        const eligible = eligibleValue, skipped = skippedValue;
        const expected = new Map(targets.map(t => [targetKey(t), JSON.stringify(t.sources ?? [])]));
        const keys = new Set();
        for (const row of [...eligible, ...skipped]) {
            const key = targetKey(row);
            if (!expected.has(key) || !/^[a-f0-9]{64}$/.test(String(row.targetKey)) || keys.has(String(row.targetKey)) || JSON.stringify(normalizeSources(row.sources)) !== expected.get(key))
                bad();
            expected.delete(key);
            keys.add(String(row.targetKey));
        }
        if (expected.size || skipped.some(row => !['open_marketing_task', 'identity_review_required'].includes(String(row.reason))))
            bad();
        assigned.push(...eligible.map(row => row.ownerId));
    }
    else {
        const eligibleValue = preview.eligibleCustomerIds, skippedValue = preview.skippedCustomerIds, ownersValue = preview.customerAssignments;
        if (!Array.isArray(eligibleValue) || !Array.isArray(skippedValue) || !object(ownersValue) || eligibleValue.length !== preview.eligibleCount || skippedValue.length !== preview.skippedCount || Object.keys(ownersValue).length !== eligibleValue.length)
            bad();
        const eligible = eligibleValue, skipped = skippedValue, owners = ownersValue;
        const ids = [...eligible, ...skipped];
        if (new Set(ids).size !== targets.length || ids.some(id => !input.customerIds?.includes(String(id))) || eligible.some(id => !Object.hasOwn(owners, String(id))))
            bad();
        assigned.push(...eligible.map(id => owners[String(id)]));
    }
    const expected = Object.fromEntries(input.ownerIds.map(id => [id, 0]));
    for (const owner of assigned) {
        const ownerId = typeof owner === 'string' ? owner : bad();
        if (!Object.hasOwn(expected, ownerId))
            bad();
        expected[ownerId]++;
    }
    if (Object.keys(preview.assignmentCounts).length !== input.ownerIds.length || Object.entries(expected).some(([owner, n]) => preview.assignmentCounts[owner] !== n))
        bad();
    return preview;
}
function buildPlan(args) {
    const input = validateInput(args.input);
    const key = args.key ?? crypto.randomUUID();
    if (!keyValid(key))
        fail('INVALID_IDEMPOTENCY_KEY', '任务批次幂等键无效');
    if (args.client.apiVersion !== 'v2' || !args.me.principalId)
        fail('INVALID_JAVA_SESSION', '需要有效的 Java v2 员工会话');
    validatePreview(input, args.preview);
    return { format: 'siyue-java-plan-v1', apiVersion: 'v2', operationId: 'crm.task-batch.create', server: args.client.server ?? '', principalId: args.me.principalId, input, preview: args.preview, idempotencyKey: key };
}
async function fetchPreview(input, client) { const checked = validateInput(input); if (client.apiVersion !== 'v2')
    fail('INVALID_JAVA_SESSION', '需要有效的 Java v2 员工会话'); return java.unwrap(await client.request('crm/tasks/batches/preview', { method: 'POST', body: checked })); }
async function createPlan(input, output, context = {}) {
    const client = context.client ?? auth.sessionClient();
    const me = context.me ?? await java.identity(client);
    const preview = validatePreview(validateInput(input), await fetchPreview(input, client));
    const plan = buildPlan({ input, preview, client, me });
    const file = path.resolve(output);
    business.write(file, plan);
    return { operationId: plan.operationId, preview: preview, planFile: file, commit: false, next: `核对逐学生需求分配后运行 siyue-cli execute --plan ${JSON.stringify(file)}` };
}
function validatePlan(value) { if (!object(value) || value.format !== 'siyue-java-plan-v1' || value.apiVersion !== 'v2' || value.operationId !== 'crm.task-batch.create' || typeof value.server !== 'string' || typeof value.principalId !== 'string' || !keyValid(value.idempotencyKey))
    fail('INVALID_PLAN', 'Java 任务批次计划无效'); const plan = value; validateInput(plan.input, { requireFuture: false }); validatePreview(plan.input, plan.preview); return plan; }
async function executePlan(value, context = {}) { const plan = validatePlan(value); const client = context.client ?? auth.sessionClient(); if (client.apiVersion !== 'v2' || client.server !== plan.server)
    fail('INVALID_PLAN', '计划不属于当前 Java API 会话'); const me = context.me ?? await java.identity(client); if (me.principalId !== plan.principalId)
    fail('WRONG_ACTOR', '必须使用预览时员工身份提交'); if (!plan.preview.eligibleCount)
    fail('EMPTY_PREVIEW', '预览中没有可创建任务'); return java.unwrap(await client.request('crm/tasks/batches/commit-preview', { method: 'POST', idempotencyKey: plan.idempotencyKey, body: { input: plan.input, expectedPreview: plan.preview } })); }
async function execute(file, context = {}) { return executePlan(business.read(file), context); }
module.exports = { operations, validateInput, normalizedTargets, validatePreview, buildPlan, fetchPreview, createPlan, validatePlan, executePlan, execute };
