'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const java = require("./java-query-client.cjs");
const path = require("node:path");
const crypto = require("node:crypto");
const labels = { create: '创建主管或员工任务批次', allocation: '分派所选任务', recall: '回收所选任务', transfer: '转交任务主管', schedule: '调整任务日期', cancel: '取消批次未完任务' };
const operations = Object.entries(labels).map(([action, label]) => ({ name: `task-routing-${action}`, id: `crm.task.routing.${action}`, action, label, permissions: action === 'create' ? ['crm.task.write', 'crm.task.assign'] : ['crm.task.read', 'crm.task.manage', 'crm.task.write', 'crm.task.assign'] }));
function fail(code, message) { throw auth.failure(code, message); }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function exact(value, keys) { if (!object(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length || Object.keys(value).some(k => !keys.includes(k)))
    fail('INVALID_ROUTING_INPUT', '任务分派含无效对象或字段'); }
function id(value) { return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value); }
function count(value) { return Number.isSafeInteger(value) && Number(value) >= 0; }
function version(value) { return count(value) && Number(value) > 0; }
function text(value, max, optional = false) { if (optional && value == null)
    return; if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value))
    fail('INVALID_ROUTING_INPUT', '任务内容为空、过长或含控制字符'); }
function instant(value) { if (typeof value !== 'string')
    return null; const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value); if (!m)
    return null; const ms = Date.parse(m[1] + 'Z'); if (!Number.isFinite(ms) || new Date(ms).toISOString().slice(0, 19) !== m[1])
    return null; return BigInt(ms) * 1000000n + BigInt((m[2] ?? '').padEnd(9, '0')); }
function sameTime(a, b) { return instant(a) !== null && instant(a) === instant(b); }
function period(input) { const start = instant(input.startsAt), due = instant(input.dueAt); if (start === null || due === null || due <= start)
    fail('INVALID_ROUTING_INPUT', '开始/截止须为有效UTC时间，截止晚于开始'); }
function optionalId(value) { return value == null || id(value); }
function planOwners(input) { if (!['even', 'quota'].includes(String(input.strategy)) || !Array.isArray(input.ownerIds) || input.ownerIds.length < 1 || input.ownerIds.length > 100 || input.ownerIds.some(v => !id(v)) || new Set(input.ownerIds).size !== input.ownerIds.length)
    fail('INVALID_ROUTING_INPUT', '分配策略或候选员工无效'); if (input.strategy === 'quota') {
    if (!Array.isArray(input.quotas) || input.quotas.length !== input.ownerIds.length || new Set(input.quotas.map((q) => object(q) ? q.ownerId : null)).size !== input.ownerIds.length)
        fail('INVALID_ROUTING_INPUT', '每名员工须有一个名额');
    for (const q of input.quotas) {
        exact(q, ['ownerId', 'count']);
        if (!input.ownerIds.includes(q.ownerId) || !count(q.count) || q.count > 500)
            fail('INVALID_ROUTING_INPUT', '分配名额无效');
    }
}
else if (input.quotas != null && (!Array.isArray(input.quotas) || input.quotas.length))
    fail('INVALID_ROUTING_INPUT', '平均分配不接受名额'); }
function validateTarget(value) { exact(value, ['customerId', 'studentId', 'subject', 'term', 'needIdentity', 'sources']); if (!id(value.customerId) || !optionalId(value.studentId))
    fail('INVALID_ROUTING_INPUT', '客户/学生编号无效'); text(value.subject, 100, true); text(value.term, 100, true); if (value.needIdentity != null) {
    exact(value.needIdentity, ['departmentId', 'projectId']);
    if (!id(value.needIdentity.departmentId) || !optionalId(value.needIdentity.projectId))
        fail('INVALID_ROUTING_INPUT', '需求身份无效');
} if (value.sources != null) {
    if (!Array.isArray(value.sources) || value.sources.length > 100)
        fail('INVALID_ROUTING_INPUT', '来源证据数量无效');
    const seen = new Set();
    for (const source of value.sources) {
        exact(source, ['type', 'id', 'version']);
        if (!['lead', 'touchpoint'].includes(String(source.type)) || !id(source.id) || (source.type === 'lead' ? !version(source.version) : source.version != null) || seen.has(`${source.type}:${source.id}`))
            fail('INVALID_ROUTING_INPUT', '来源证据无效或重复');
        seen.add(`${source.type}:${source.id}`);
    }
} return value; }
function definition(name) { const op = operations.find(x => x.name === name || x.id === name); if (!op)
    fail('JAVA_V2_UNAVAILABLE', '未开放此任务分派操作'); return op; }
function validateRequest(name, value) { const op = definition(name), action = op.action; exact(value, action === 'create' ? ['input'] : ['batchId', 'input']); const request = value, input = request.input; if (action === 'create') {
    exact(input, ['title', 'taskType', 'campusId', 'activityId', 'startsAt', 'dueAt', 'delivery', 'targets', 'note']);
    text(input.title, 200);
    text(input.note, 5000, true);
    period(input);
    if (input.taskType !== 'marketing' || !id(input.campusId) || !optionalId(input.activityId))
        fail('INVALID_ROUTING_INPUT', '仅支持真实校区的营销任务');
    if (!Array.isArray(input.targets) || input.targets.length < 1 || input.targets.length > 500)
        fail('INVALID_ROUTING_INPUT', '需求目标须为1至500个');
    input.targets.forEach(validateTarget);
    if (input.targets.some((t) => Array.isArray(t.sources) && t.sources.length > 0) && !input.activityId || input.activityId && input.targets.some((t) => !Array.isArray(t.sources) || !t.sources.length))
        fail('INVALID_ROUTING_INPUT', '活动与来源证据须同时提供');
    exact(input.delivery, ['mode', 'supervisorId', 'employeePlan']);
    const delivery = input.delivery;
    if (delivery.mode === 'supervisor') {
        if (!id(delivery.supervisorId) || delivery.employeePlan != null)
            fail('INVALID_ROUTING_INPUT', '主管模式须且只选一位主管');
    }
    else if (delivery.mode === 'direct') {
        if (delivery.supervisorId != null)
            fail('INVALID_ROUTING_INPUT', '员工模式不可指定主管');
        exact(delivery.employeePlan, ['strategy', 'ownerIds', 'quotas']);
        planOwners(delivery.employeePlan);
    }
    else
        fail('INVALID_ROUTING_INPUT', '分派模式无效');
}
else {
    if (!id(request.batchId))
        fail('INVALID_ROUTING_INPUT', '批次编号无效');
    const fields = { allocation: ['selection', 'strategy', 'ownerIds', 'quotas', 'sourceOwnerPolicy'], recall: ['selection', 'reason'], transfer: ['supervisorId', 'reason'], schedule: ['startsAt', 'dueAt', 'reason'], cancel: ['reason'] };
    exact(input, ['expectedManagementVersion', ...(fields[action] ?? [])]);
    if (!version(input.expectedManagementVersion))
        fail('INVALID_ROUTING_INPUT', '批次管理版本无效');
    if (action === 'allocation' || action === 'recall') {
        if (!Array.isArray(input.selection) || input.selection.length < 1 || input.selection.length > 500 || new Set(input.selection.map((x) => object(x) ? x.taskId : null)).size !== input.selection.length)
            fail('INVALID_ROUTING_INPUT', '须选择1至500个不同任务');
        for (const row of input.selection) {
            exact(row, ['taskId', 'expectedVersion']);
            if (!id(row.taskId) || !version(row.expectedVersion))
                fail('INVALID_ROUTING_INPUT', '任务编号/版本无效');
        }
    }
    if (action === 'allocation') {
        planOwners(input);
        if (!['none', 'source_lead_owner_first'].includes(String(input.sourceOwnerPolicy)) || input.sourceOwnerPolicy === 'source_lead_owner_first' && input.strategy !== 'even')
            fail('INVALID_ROUTING_INPUT', '线索负责人优先只可用于平均分派');
    }
    else if (action !== 'allocation')
        text(input.reason, 1000);
    if (action === 'transfer' && !id(input.supervisorId))
        fail('INVALID_ROUTING_INPUT', '新主管编号无效');
    if (action === 'schedule')
        period(input);
} return op; }
function normalizedTarget(value) { return { customerId: value.customerId, studentId: value.studentId || null, subject: typeof value.subject === 'string' ? value.subject.trim() || null : null, term: typeof value.term === 'string' ? value.term.trim() || null : null, needIdentity: object(value.needIdentity) ? { departmentId: value.needIdentity.departmentId, projectId: value.needIdentity.projectId ?? null } : null, sources: (Array.isArray(value.sources) ? value.sources : []).map((s) => ({ type: s.type, id: s.id, version: s.version ?? null })).sort((a, b) => (String(a.type) + String(a.id)).localeCompare(String(b.type) + String(b.id))) }; }
function signature(value) { return JSON.stringify(normalizedTarget(value)); }
function distribution(actual, owners) { if (!object(actual))
    fail('INVALID_PREVIEW', '实际员工分布无效'); const expected = {}; owners.filter((v) => typeof v === 'string' && !!v).forEach(owner => expected[owner] = (expected[owner] ?? 0) + 1); if (Object.entries(actual).some(([owner, n]) => !id(owner) || !count(n) || n !== (expected[owner] ?? 0)) || Object.keys(expected).some(owner => actual[owner] !== expected[owner]))
    fail('INVALID_PREVIEW', '实际员工分布与逐条结果不一致'); }
function validatePreview(op, request, preview) { const bad = () => fail('INVALID_PREVIEW', '服务端任务预览与请求不一致'), input = request.input; if (!preview || preview.action !== op.action)
    bad(); if (op.action === 'create') {
    const delivery = input.delivery;
    if (preview.campusId !== input.campusId || preview.deliveryMode !== delivery.mode || (preview.supervisorId ?? null) !== (delivery.supervisorId ?? null) || !sameTime(preview.startsAt, input.startsAt) || !sameTime(preview.dueAt, input.dueAt) || ![preview.selectedCount, preview.reservedCount, preview.skippedCount].every(count) || preview.selectedCount !== input.targets.length || preview.selectedCount !== Number(preview.reservedCount) + Number(preview.skippedCount) || !Array.isArray(preview.targets) || !Array.isArray(preview.skippedTargets) || preview.targets.length !== preview.reservedCount || preview.skippedTargets.length !== preview.skippedCount)
        bad();
    const remaining = input.targets.map(signature), keys = new Set();
    for (const row of [...preview.targets, ...preview.skippedTargets]) {
        if (typeof row.targetKey !== 'string' || !row.targetKey || keys.has(String(row.targetKey)))
            bad();
        keys.add(String(row.targetKey));
        validateTarget(row.target);
        const at = remaining.indexOf(signature(row.target));
        if (at < 0)
            bad();
        remaining.splice(at, 1);
    }
    for (const row of preview.targets) {
        if (delivery.mode === 'supervisor' ? (row.ownerId != null || row.status !== 'unassigned') : !delivery.employeePlan.ownerIds.includes(String(row.ownerId)) || row.status !== 'pending')
            bad();
    }
    if (preview.skippedTargets.some(row => !['open_marketing_task', 'identity_review_required'].includes(String(row.reason))))
        bad();
    distribution(preview.assignmentCounts, preview.targets.map(row => row.ownerId));
}
else {
    if (preview.batchId !== request.batchId || preview.managementVersion !== input.expectedManagementVersion || ![preview.selectedCount, preview.assignableCount, preview.blockedCount, preview.remainingUnassignedCount].every(count) || !Array.isArray(preview.assignments) || !Array.isArray(preview.blocked) || preview.assignments.length !== preview.assignableCount || preview.blocked.length !== preview.blockedCount)
        bad();
    const selected = Array.isArray(input.selection) ? input.selection : null;
    for (const row of [...preview.assignments, ...preview.blocked])
        if (!id(row.taskId))
            bad();
    for (const row of preview.assignments) {
        if (!version(row.expectedVersion) || !optionalId(row.fromOwnerId) || !optionalId(row.toOwnerId) || !['unassigned', 'pending', 'in_progress', 'completed', 'cancelled'].includes(String(row.fromStatus)) || !['unassigned', 'pending', 'in_progress', 'completed', 'cancelled'].includes(String(row.toStatus)) || instant(row.fromDueAt) === null || instant(row.toDueAt) === null || typeof row.basis !== 'string')
            bad();
        if (selected && !selected.some(s => s.taskId === row.taskId && s.expectedVersion === row.expectedVersion))
            bad();
    }
    if (selected && (preview.selectedCount !== selected.length || Number(preview.assignableCount) + Number(preview.blockedCount) !== selected.length || preview.blocked.some(row => !selected.some(s => s.taskId === row.taskId))))
        bad();
    if (preview.blocked.some(row => typeof row.reason !== 'string' || !row.reason))
        bad();
    if (op.action === 'allocation') {
        if (preview.assignments.some(row => !id(row.toOwnerId) || row.toStatus !== 'pending'))
            bad();
        distribution(preview.assignmentCounts, preview.assignments.map(row => row.toOwnerId));
        if (input.sourceOwnerPolicy === 'none' && preview.assignments.some(row => !input.ownerIds.includes(String(row.toOwnerId))))
            bad();
    }
    else
        distribution(preview.assignmentCounts, []);
    if (op.action === 'recall' && preview.assignments.some(row => row.toOwnerId != null || row.toStatus !== 'unassigned'))
        bad();
    if (op.action === 'cancel' && preview.assignments.some(row => row.toStatus !== 'cancelled'))
        bad();
    if (op.action === 'transfer' && (preview.toSupervisorId !== input.supervisorId || preview.assignments.length))
        bad();
    if (op.action === 'schedule' && (!sameTime(preview.startsAt, input.startsAt) || !sameTime(preview.dueAt, input.dueAt) || preview.assignments.some(row => !sameTime(row.toDueAt, input.dueAt))))
        bad();
} return preview; }
function authorize(op, client, me) { if (client.apiVersion !== 'v2' || !id(me?.principalId))
    fail('INVALID_JAVA_SESSION', '需要Java v2员工会话'); if (!op.permissions.every(p => me.permissions.includes(p)))
    fail('FORBIDDEN', '当前账号无此任务分派权限'); }
function endpoint(op, request, stage) { return `crm/task-routing/batches${op.action === 'create' ? '' : '/' + request.batchId + '/' + op.action}/${stage}`; }
async function prepare(name, value, context = {}) { const op = validateRequest(name, value), request = JSON.parse(JSON.stringify(value)), client = context.client ?? auth.sessionClient(), me = context.me ?? await java.identity(client); authorize(op, client, me); const preview = validatePreview(op, request, java.unwrap(await client.request(endpoint(op, request, 'preview'), { method: 'POST', body: request.input }))); if (op.action === 'create' && preview.reservedCount === 0)
    return { plan: null, preview, next: '全部需求已跳过，没有可创建任务；当前不能提交。' }; if (Number(preview.blockedCount) > 0)
    return { plan: null, preview, next: '存在阻止项；先处理来源或选择其他明确策略，再重新预览。当前不能提交。' }; return { plan: { format: 'siyue-java-task-routing-plan-v1', apiVersion: 'v2', operationId: op.id, server: client.server ?? '', principalId: me.principalId, request, expectedPreview: JSON.parse(JSON.stringify(preview)), idempotencyKey: crypto.randomUUID() }, preview, next: '核对逐条需求、来源依据和实际接收人后提交原计划；未知结果只重试原计划。' }; }
function validatePlan(value) { exact(value, ['format', 'apiVersion', 'operationId', 'server', 'principalId', 'request', 'expectedPreview', 'idempotencyKey']); if (value.format !== 'siyue-java-task-routing-plan-v1' || value.apiVersion !== 'v2' || typeof value.server !== 'string' || !id(value.principalId) || typeof value.idempotencyKey !== 'string' || !/^[A-Za-z0-9._:-]{16,128}$/.test(value.idempotencyKey))
    fail('INVALID_PLAN', '任务分派计划无效'); const plan = value, op = validateRequest(plan.operationId, plan.request); validatePreview(op, plan.request, plan.expectedPreview); if (Number(plan.expectedPreview.blockedCount) > 0 || op.action === 'create' && Number(plan.expectedPreview.reservedCount) === 0)
    fail('INVALID_PLAN', '有阻止项的预览不可提交'); return { operation: op, plan }; }
function validateReceipt(op, plan, value) {
    const invalid = () => fail('INVALID_RESPONSE', '任务回执与原计划不符，请保留原计划和幂等键核对或重试');
    if (!object(value) || value.committed !== true || !id(value.batchId) || !id(value.operationId) || !['visible', 'left_scope'].includes(String(value.scopeStatus)) || !Array.isArray(value.tasks) || op.action !== 'create' && value.batchId !== plan.request.batchId)
        invalid();
    const receipt = value, tasks = receipt.tasks;
    if (receipt.scopeStatus === 'left_scope') {
        if (receipt.batch !== null || tasks.length)
            invalid();
        return { committed: true, batchId: String(receipt.batchId), operationId: String(receipt.operationId), scopeStatus: 'left_scope', batch: null, tasks: [] };
    }
    const batchValue = receipt.batch, preview = plan.expectedPreview;
    if (!object(batchValue) || batchValue.id !== receipt.batchId || !version(batchValue.managementVersion) || !version(batchValue.version) || !object(batchValue.counts))
        invalid();
    const batch = batchValue, counts = batch.counts;
    const countNames = ['totalCount', 'unassignedCount', 'pendingCount', 'inProgressCount', 'completedCount', 'cancelledCount', 'allocatedCount'];
    if (countNames.some(key => !count(counts[key])))
        invalid();
    if (Number(counts.totalCount) !== Number(counts.unassignedCount) + Number(counts.pendingCount) + Number(counts.inProgressCount) + Number(counts.completedCount) + Number(counts.cancelledCount) || Number(counts.allocatedCount) !== Number(counts.pendingCount) + Number(counts.inProgressCount) + Number(counts.completedCount))
        invalid();
    if (new Set(tasks.map(item => item.taskId)).size !== tasks.length || tasks.some(item => !id(item.taskId) || !version(item.version) || !optionalId(item.ownerId) || !['unassigned', 'pending', 'in_progress', 'completed', 'cancelled'].includes(String(item.status)) || instant(item.dueAt) === null))
        invalid();
    if (op.action === 'create') {
        const input = plan.request.input, delivery = input.delivery;
        if (batch.managementVersion !== 1 || batch.campusId !== input.campusId || batch.deliveryMode !== delivery.mode || (batch.supervisorId ?? null) !== (delivery.supervisorId ?? null) || !sameTime(batch.startsAt, input.startsAt) || !sameTime(batch.dueAt, input.dueAt) || tasks.length !== preview.reservedCount || counts.totalCount !== preview.reservedCount)
            invalid();
        const expected = preview.targets.map(row => JSON.stringify([row.ownerId ?? null, row.status]));
        for (const task of tasks) {
            const at = expected.indexOf(JSON.stringify([task.ownerId ?? null, task.status]));
            if (task.version !== 1 || !sameTime(task.dueAt, input.dueAt) || at < 0)
                invalid();
            expected.splice(at, 1);
        }
    }
    else {
        if (batch.managementVersion !== Number(preview.managementVersion) + 1 || tasks.length !== preview.assignments.length)
            invalid();
        for (const task of tasks) {
            const row = preview.assignments.find(item => item.taskId === task.taskId);
            if (!row || task.version !== Number(row.expectedVersion) + 1 || (task.ownerId ?? null) !== (row.toOwnerId ?? null) || task.status !== row.toStatus || !sameTime(task.dueAt, row.toDueAt))
                invalid();
        }
        if (op.action === 'transfer' && batch.supervisorId !== preview.toSupervisorId)
            invalid();
        if (op.action === 'schedule' && (!sameTime(batch.startsAt, preview.startsAt) || !sameTime(batch.dueAt, preview.dueAt)))
            invalid();
    }
    return value;
}
async function executePlan(value, context = {}) { const { operation, plan } = validatePlan(value), client = context.client ?? auth.sessionClient(), me = context.me ?? await java.identity(client); authorize(operation, client, me); if (client.server !== plan.server)
    fail('INVALID_PLAN', '任务计划不属于当前服务端'); if (plan.principalId !== me.principalId)
    fail('WRONG_ACTOR', '须由原预览员工提交'); return validateReceipt(operation, plan, java.unwrap(await client.request(endpoint(operation, plan.request, 'commit'), { method: 'POST', body: { input: plan.request.input, expectedPreview: plan.expectedPreview }, idempotencyKey: plan.idempotencyKey }))); }
async function createPlan(name, request, output, context = {}) { const result = await prepare(name, request, context); if (!result.plan)
    return { preview: result.preview, planFile: null, commit: false, next: result.next }; const file = path.resolve(output); business.write(file, result.plan); return { operationId: result.plan.operationId, preview: result.preview, planFile: file, commit: false, next: result.next }; }
async function execute(file, context = {}) { return executePlan(business.read(file), context); }
module.exports = { operations, definition, validateRequest, validatePreview, validatePlan, validateReceipt, prepare, executePlan, createPlan, execute };
