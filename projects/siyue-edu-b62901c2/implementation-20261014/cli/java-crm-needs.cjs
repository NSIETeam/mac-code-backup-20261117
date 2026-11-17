'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const java = require("./java-query-client.cjs");
const path = require("node:path");
const crypto = require("node:crypto");
const kinds = ['department', 'project', 'grade', 'class_type', 'level', 'fiscal_year', 'quarter', 'judgment', 'judgment_reason'];
const profileFields = ['gradeId', 'preferredCampusId', 'classTypeId', 'levelId', 'fiscalYearId', 'quarterId'];
const actions = ['create', 'reuse', 'classify_existing', 'create_distinct'];
const operations = [
    { name: 'need-intake', id: 'crm.opportunity.intake', label: '接待需求核对', permission: 'crm.opportunity.intake', method: 'POST', suffix: 'commit-preview' },
    { name: 'need-profile', id: 'crm.opportunity.profile.update', label: '更新需求资料', permission: 'crm.opportunity.write', method: 'PUT', suffix: 'profile', target: true },
    { name: 'need-judgment', id: 'crm.opportunity.judgment.record', label: '记录商机判定', permission: 'crm.opportunity.write', method: 'POST', suffix: 'judgments', target: true },
    { name: 'need-assign', id: 'crm.opportunity.assign', label: '分派商机', permission: 'crm.opportunity.assign', method: 'PUT', suffix: 'assignment', target: true },
    { name: 'need-option-create', id: 'crm.opportunity.config.create', label: '新增需求配置', permission: 'crm.opportunity.config.manage', method: 'POST', config: true },
    { name: 'need-option-update', id: 'crm.opportunity.config.update', label: '编辑需求配置', permission: 'crm.opportunity.config.manage', method: 'PUT', target: true, config: true },
    { name: 'need-notification-read', id: 'crm.opportunity.notification.read', label: '标记本人提醒已读', permission: 'crm.opportunity.read', method: 'PUT', notification: true, suffix: 'read', target: true },
];
function fail(code, message) { throw auth.failure(code, message); }
function isObj(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function record(value, keys) { if (!isObj(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length || Object.keys(value).some(key => !keys.includes(key)))
    fail('INVALID_NEED_INPUT', '需求输入包含无效对象或字段'); }
function id(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value); }
function positive(value) { return Number.isSafeInteger(value) && Number(value) > 0; }
function keyValid(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{16,100}$/.test(value); }
function text(value, max, required = false, multiline = false) { if (value == null && !required)
    return; if (typeof value !== 'string' || value.length > max || required && !value.trim() || (multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/ : /[\u0000-\u001f\u007f]/).test(String(value)))
    fail('INVALID_NEED_INPUT', '需求文字为空、过长或包含控制字符'); }
function identity(value) { record(value, ['departmentId', 'projectId']); if (!id(value.departmentId) || value.projectId != null && !id(value.projectId))
    fail('INVALID_NEED_INPUT', '需求部门或项目编号无效'); }
function profile(value) { record(value, profileFields); for (const key of profileFields)
    if (value[key] != null && !id(value[key]))
        fail('INVALID_NEED_INPUT', '需求资料编号无效'); }
function decision(value) { record(value, ['action', 'targetRef', 'reason']); if (!actions.includes(String(value.action)))
    fail('INVALID_DECISION', '请选择明确的需求处理方式'); if (['reuse', 'classify_existing'].includes(String(value.action))) {
    if (!id(value.targetRef))
        fail('INVALID_DECISION', '必须指定现有商机');
}
else if (value.targetRef != null)
    fail('INVALID_DECISION', '新建决定不能指定旧目标'); text(value.reason, 1000, ['classify_existing', 'create_distinct'].includes(String(value.action)), true); return value; }
function validateInput(op, value) { if (op.name === 'need-intake') {
    record(value, ['customerId', 'studentId', 'sourceLeadId', 'activityId', 'title', 'subject', 'term', 'identity', 'profile', 'detail', 'ownerId']);
    if (!id(value.customerId))
        fail('INVALID_NEED_INPUT', '客户编号无效');
    for (const key of ['studentId', 'sourceLeadId', 'activityId', 'ownerId'])
        if (value[key] != null && !id(value[key]))
            fail('INVALID_NEED_INPUT', '需求关联编号无效');
    text(value.title, 200, true);
    text(value.subject, 100, true);
    text(value.term, 100, true);
    identity(value.identity);
    profile(value.profile);
    text(value.detail, 5000, true, true);
}
else if (op.name === 'need-profile') {
    record(value, ['profile', 'expectedVersion', 'expectedCatalogVersion']);
    profile(value.profile);
    if (!positive(value.expectedVersion) || !positive(value.expectedCatalogVersion))
        fail('INVALID_NEED_INPUT', '需求版本无效');
}
else if (op.name === 'need-judgment') {
    record(value, ['judgmentId', 'reasonId', 'detail', 'expectedVersion', 'expectedCatalogVersion']);
    if (!id(value.judgmentId) || value.reasonId != null && !id(value.reasonId))
        fail('INVALID_NEED_INPUT', '判定编号无效');
    text(value.detail, 1000, true, true);
    if (!positive(value.expectedVersion) || !positive(value.expectedCatalogVersion))
        fail('INVALID_NEED_INPUT', '需求版本无效');
}
else if (op.name === 'need-assign') {
    record(value, ['ownerId', 'reason', 'expectedVersion']);
    if (!id(value.ownerId))
        fail('INVALID_NEED_INPUT', '负责人编号无效');
    text(value.reason, 1000, true, true);
    if (!positive(value.expectedVersion))
        fail('INVALID_NEED_INPUT', '需求版本无效');
}
else if (op.name === 'need-option-create') {
    record(value, ['kind', 'code', 'label', 'parentId', 'campusId', 'projectMode', 'effect']);
    if (!kinds.includes(String(value.kind)) || typeof value.code !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,59}$/.test(value.code))
        fail('INVALID_NEED_INPUT', '配置分类或代码无效');
    text(value.label, 100, true);
    for (const key of ['parentId', 'campusId'])
        if (value[key] != null && !id(value[key]))
            fail('INVALID_NEED_INPUT', '配置关联编号无效');
    if (value.kind === 'department' ? !['required', 'not_used'].includes(String(value.projectMode)) : value.projectMode != null)
        fail('INVALID_NEED_INPUT', '只有部门必须设置项目使用规则');
    if (value.kind === 'judgment' ? !['continue', 'close'].includes(String(value.effect)) : value.effect != null)
        fail('INVALID_NEED_INPUT', '只有判定配置必须设置继续或关闭效果');
    if (['project', 'judgment_reason'].includes(String(value.kind)) && !id(value.parentId))
        fail('INVALID_NEED_INPUT', '此配置父项编号无效，必须指定父项');
    else if (value.kind !== 'quarter' && !['project', 'judgment_reason'].includes(String(value.kind)) && value.parentId != null)
        fail('INVALID_NEED_INPUT', '此分类不能指定父项');
}
else if (op.name === 'need-option-update') {
    record(value, ['label', 'active', 'expectedVersion']);
    text(value.label, 100, true);
    if (typeof value.active !== 'boolean' || !positive(value.expectedVersion))
        fail('INVALID_NEED_INPUT', '配置状态或版本无效');
}
else {
    record(value, ['expectedVersion']);
    if (!positive(value.expectedVersion))
        fail('INVALID_NEED_INPUT', '需求版本无效');
} return value; }
function definition(name) { const op = operations.find(item => item.name === name || item.id === name); if (!op)
    fail('JAVA_V2_UNAVAILABLE', '未开放此 Java 需求操作'); return op; }
function validateRequest(name, value) { const op = definition(name); record(value, ['input', 'targetId', 'decision']); validateInput(op, value.input); if (op.target) {
    if (!id(value.targetId))
        fail('INVALID_NEED_INPUT', '需求目标编号无效');
}
else if (value.targetId != null)
    fail('INVALID_NEED_INPUT', '该操作不能指定目标编号'); if (value.decision != null) {
    if (op.name !== 'need-intake')
        fail('INVALID_NEED_INPUT', '只有接待操作需要需求决定');
    decision(value.decision);
} return op; }
function endpoint(op, targetId) { const base = op.config ? 'crm/opportunity-config' : op.notification ? 'crm/opportunity-notifications' : 'crm/opportunity-needs'; return base + (op.target ? `/${targetId}` : '') + (op.suffix ? `/${op.suffix}` : ''); }
function validatePreview(input, preview, selected) { if (!preview || !Number.isSafeInteger(preview.catalogVersion) || Number(preview.catalogVersion) < 0 || !positive(preview.customerVersion) || preview.scopeCampusId !== null && !id(preview.scopeCampusId) || typeof preview.inputHash !== 'string' || !/^[a-f0-9]{64}$/.test(preview.inputHash) || !Array.isArray(preview.candidates) || !Array.isArray(preview.allowedActions) || preview.allowedActions.some((v) => !actions.includes(String(v))) || !Array.isArray(preview.differences))
    fail('INVALID_PREVIEW', '需求预览格式无效'); const seen = new Set(), fold = (v) => typeof v === 'string' ? v.trim().toLowerCase() : v ?? null; for (const row of preview.candidates) {
    if (!id(row.targetRef) || seen.has(String(row.targetRef)) || !positive(row.version) || !['exact', 'legacy_unknown', 'different_need'].includes(String(row.match)) || fold(row.subject) !== fold(input.subject) || fold(row.term) !== fold(input.term) || row.studentVisibility !== 'restricted' && (row.studentId ?? null) !== (input.studentId ?? null) || row.match === 'exact' && (!isObj(row.identity) || row.identity.departmentId !== input.identity.departmentId || (row.identity.projectId ?? null) !== (input.identity.projectId ?? null)))
        fail('INVALID_PREVIEW', '需求预览候选无效');
    seen.add(String(row.targetRef));
} if (selected) {
    const d = decision(selected);
    if (!preview.allowedActions.includes(d.action))
        fail('INVALID_DECISION', '当前需求预览不允许所选处理方式');
    const match = preview.candidates.find(row => row.targetRef === d.targetRef);
    if (d.action === 'reuse' && match?.match !== 'exact' || d.action === 'classify_existing' && match?.match !== 'legacy_unknown')
        fail('INVALID_DECISION', '指定目标与需求处理方式不一致');
} return preview; }
async function readAll(pathname, client) { let page = 1, rows = [], total; do {
    const sep = pathname.includes('?') ? '&' : '?', value = java.unwrap(await client.request(`${pathname}${sep}page=${page}&size=100`));
    if (!Array.isArray(value.items) || !Number.isSafeInteger(value.total) || Number(value.total) < 0 || value.page !== page || value.size !== 100 || page > 1000 || total !== undefined && total !== value.total)
        fail('INVALID_PREVIEW', '办理目录在读取期间变化或格式无效');
    total = Number(value.total);
    rows.push(...value.items);
    if (!value.items.length && rows.length < total)
        fail('INVALID_PREVIEW', '办理目录分页不完整');
    page++;
} while (rows.length < (total ?? 0)); return rows; }
async function fetchReview(op, request, client) { if (op.name === 'need-intake')
    return validatePreview(request.input, java.unwrap(await client.request('crm/opportunity-needs/preview', { method: 'POST', body: request.input })), request.decision); if (op.config) {
    const items = await readAll('crm/opportunity-config?status=all', client), current = op.target ? items.find(row => row.id === request.targetId) : null;
    if (op.target && (!current || current.version !== request.input.expectedVersion))
        fail('VERSION_CONFLICT', '配置不存在或版本已改变，请重新核对');
    return { current, requested: request.input, kind: 'client_review', serverPreview: false };
} if (op.notification) {
    const current = (await readAll('crm/opportunity-notifications?status=all', client)).find(row => row.id === request.targetId);
    if (!current || current.version !== request.input.expectedVersion)
        fail('VERSION_CONFLICT', '提醒不存在或版本已改变');
    return { current, requested: request.input, kind: 'client_review', serverPreview: false };
} const current = java.unwrap(await client.request(`crm/opportunity-needs/${request.targetId}`)); if (!isObj(current.opportunity) || current.opportunity.id !== request.targetId || current.opportunity.version !== request.input.expectedVersion)
    fail('VERSION_CONFLICT', '商机已改变，请读取最新资料后重新核对'); let options = null; if (['need-profile', 'need-judgment'].includes(op.name)) {
    options = java.unwrap(await client.request(`crm/opportunity-needs/${request.targetId}/options`));
    if (options.catalogVersion !== request.input.expectedCatalogVersion)
        fail('VERSION_CONFLICT', '需求配置版本已改变');
} if (op.name === 'need-assign') {
    const candidates = await readAll(`crm/opportunity-needs/assignees?opportunityId=${request.targetId}`, client);
    if (!candidates.some(row => row.id === request.input.ownerId))
        fail('INVALID_ASSIGNEE', '所选员工不是当前商机候选负责人');
} return { current, requested: request.input, options, kind: 'client_review', serverPreview: false }; }
function context(client, me) { if (client.apiVersion !== 'v2' || !id(me?.principalId))
    fail('INVALID_JAVA_SESSION', '需要 Java v2 员工会话'); }
async function prepare(name, value, ctx = {}) { const op = validateRequest(name, value), request = JSON.parse(JSON.stringify(value)), client = ctx.client ?? auth.sessionClient(), me = ctx.me ?? await java.identity(client); context(client, me); const review = await fetchReview(op, request, client); if (op.name === 'need-intake' && !request.decision)
    return { preview: review, plan: null, next: '核对候选与差异，再在输入文件的 decision 中明确 action、targetRef 和理由，重新生成计划。' }; return { preview: review, plan: { format: 'siyue-java-need-plan-v1', apiVersion: 'v2', operationId: op.id, server: client.server ?? '', principalId: me.principalId, request, review: JSON.parse(JSON.stringify(review)), idempotencyKey: crypto.randomUUID() }, next: '核对资料和处理方式后提交同一计划；未知结果只重试原计划。' }; }
function validatePlan(value) { record(value, ['format', 'apiVersion', 'operationId', 'server', 'principalId', 'request', 'review', 'idempotencyKey']); if (value.format !== 'siyue-java-need-plan-v1' || value.apiVersion !== 'v2' || !id(value.principalId) || typeof value.server !== 'string' || !keyValid(value.idempotencyKey))
    fail('INVALID_PLAN', 'Java 需求计划无效'); const plan = value, op = validateRequest(plan.operationId, plan.request); if (op.name === 'need-intake') {
    if (!plan.request.decision)
        fail('INVALID_PLAN', '计划缺少明确需求决定');
    validatePreview(plan.request.input, plan.review, plan.request.decision);
}
else if (plan.review.kind !== 'client_review' || plan.review.serverPreview !== false || JSON.stringify(plan.review.requested) !== JSON.stringify(plan.request.input))
    fail('INVALID_PLAN', '计划输入与已核对资料不一致'); return { operation: op, plan }; }
function validateReceipt(op, request, value) { if (!isObj(value))
    fail('INVALID_RESPONSE', '需求回执无效'); if (op.name === 'need-intake') {
    const outcomes = { create: 'created', reuse: 'reused', classify_existing: 'classified', create_distinct: 'created_distinct' }, d = request.decision;
    if (value.outcome !== outcomes[String(d.action)] || !id(value.opportunityId) || !id(value.consultationId) || value.ownerChanged !== false || value.identityStatus !== 'classified' || !['not_required', 'recorded'].includes(String(value.notificationState)) || d.targetRef && value.opportunityId !== d.targetRef || isObj(value.opportunity) && value.opportunity.id !== value.opportunityId)
        fail('INVALID_RESPONSE', '需求回执与原请求不一致');
}
else if (op.name === 'need-assign') {
    if (value.opportunityId !== request.targetId || value.ownerId !== request.input.ownerId || value.version !== Number(request.input.expectedVersion) + 1 || !['visible', 'left_scope'].includes(String(value.scopeStatus)))
        fail('INVALID_RESPONSE', '需求回执与原请求不一致');
}
else if (op.config) {
    const item = value.item;
    if (!isObj(item) || !id(item.id) || !Number.isSafeInteger(value.catalogVersion) || Number(value.catalogVersion) < 1 || item.label !== request.input.label || op.target && (item.id !== request.targetId || item.active !== request.input.active || item.version !== Number(request.input.expectedVersion) + 1) || !op.target && (item.kind !== request.input.kind || item.code !== request.input.code || item.version !== 1 || item.active !== true))
        fail('INVALID_RESPONSE', '需求回执与原请求不一致');
}
else if (op.notification) {
    if (value.id !== request.targetId || !value.readAt || !positive(value.version) || Number(value.version) < Number(request.input.expectedVersion))
        fail('INVALID_RESPONSE', '需求回执与原请求不一致');
}
else if (!isObj(value.opportunity) || value.opportunity.id !== request.targetId || value.opportunity.version !== Number(request.input.expectedVersion) + 1)
    fail('INVALID_RESPONSE', '需求回执与原请求不一致'); return value; }
async function executePlan(value, ctx = {}) { const { operation, plan } = validatePlan(value), client = ctx.client ?? auth.sessionClient(), me = ctx.me ?? await java.identity(client); context(client, me); if (client.server !== plan.server)
    fail('INVALID_PLAN', '计划不属于当前 Java 服务端'); if (plan.principalId !== me.principalId)
    fail('WRONG_ACTOR', '请使用核对时的员工身份提交'); const body = operation.name === 'need-intake' ? { input: plan.request.input, expectedPreview: plan.review, decision: plan.request.decision } : plan.request.input; return validateReceipt(operation, plan.request, java.unwrap(await client.request(endpoint(operation, plan.request.targetId), { method: operation.method, idempotencyKey: plan.idempotencyKey, body }))); }
async function createPlan(name, request, output, ctx = {}) { const result = await prepare(name, request, ctx); if (!result.plan)
    return { preview: result.preview, commit: false, planFile: null, next: result.next }; const file = path.resolve(output); business.write(file, result.plan); return { operationId: result.plan.operationId, preview: result.preview, decision: (request.decision ?? null), planFile: file, commit: false, next: `核对后运行 siyue-cli execute --plan ${JSON.stringify(file)}` }; }
async function execute(file, ctx = {}) { return executePlan(business.read(file), ctx); }
module.exports = { operations, kinds, profileFields, definition, validateRequest, validateDecision: decision, validatePreview, prepare, createPlan, validatePlan, validateReceipt, executePlan, execute };
