'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const orders = require("./java-orders.cjs");
const authClient = auth, businessClient = business;
const id = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
const fail = (code, message) => { throw authClient.failure(code, message); };
const operations = [
    { name: 'order-attribution-request', id: 'order.attribution.request', method: 'POST', endpoint: 'crm/orders/{id}/attribution-requests', permissions: ['crm.attribution.correct.request', 'crm.order.read'], scopes: ['all', 'campus', 'own', 'assigned'] },
    { name: 'order-attribution-review', id: 'order.attribution.review', method: 'PUT', endpoint: 'crm/attribution-requests/{id}/review', permissions: ['crm.attribution.correct.review', 'crm.order.read'], scopes: ['all', 'campus'] },
    { name: 'routing-rule-save', id: 'crm.routingRule.save', endpoint: 'crm/lead-routing/rules', permissions: ['crm.lead.assign'], scopes: ['all', 'campus'] },
    { name: 'routing-simulate', id: 'crm.routing.simulate', method: 'GET', endpoint: 'crm/lead-routing/leads/{id}/simulate', permissions: ['crm.lead.assign'], scopes: ['all', 'campus'] },
    { name: 'routing-apply', id: 'crm.routing.apply', endpoint: 'crm/lead-routing/apply', permissions: ['crm.lead.assign'], scopes: ['all', 'campus'] },
    { name: 'import-commit', id: 'crm.import.commit', method: 'POST', endpoint: 'crm/imports', permissions: ['crm.customer.read', 'crm.customer.write', 'crm.lead.read', 'crm.lead.write', 'crm.touchpoint.write'] }
];
const ruleCreate = ['name', 'campusId', 'activityId', 'subject', 'source', 'ownerId', 'priority', 'active'], ruleUpdate = ['name', 'activityId', 'subject', 'source', 'ownerId', 'priority', 'active', 'expectedVersion'];
const importFields = ['format', 'content', 'mapping', 'campusId', 'activityId', 'source', 'fileName'], importMapFields = ['name', 'phone', 'guardian', 'grade', 'subject', 'term', 'source', 'note'];
function object(value, allowed, required = []) { if (value === null || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length || Object.keys(value).some(key => !allowed.includes(key)) || required.some(key => !Object.hasOwn(value, key)))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '字段缺失或包含未支持字段'); return value; }
function text(value, max, required = false) { if (required && (typeof value !== 'string' || !value.trim()) || value !== undefined && value !== null && (typeof value !== 'string' || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value)))
    fail('INVALID_CRM_WORKFLOW_INPUT', '文本字段为空、过长或包含控制字符'); }
const version = (value) => Number.isSafeInteger(value) && value >= 1;
function validateAttribution(name, value) { const review = name === 'order-attribution-review', input = object(value, review ? ['decision', 'note', 'expectedVersion'] : ['proposedOwnerId', 'reason', 'evidence'], review ? ['decision', 'note', 'expectedVersion'] : ['proposedOwnerId', 'reason']); if (review) {
    if (!['approve', 'reject'].includes(input.decision) || !version(input.expectedVersion))
        return fail('INVALID_CRM_WORKFLOW_INPUT', '审批结论或版本无效');
    text(input.note, 2000, true);
}
else {
    if (!id(input.proposedOwnerId))
        return fail('INVALID_CRM_WORKFLOW_INPUT', '拟归属员工编号无效');
    text(input.reason, 2000, true);
    text(input.evidence, 2000);
} return input; }
function validateRule(value, updating) { const input = object(value, updating ? ruleUpdate : ruleCreate, updating ? ['name', 'ownerId', 'priority', 'active', 'expectedVersion'] : ['name', 'campusId', 'ownerId', 'priority', 'active']); text(input.name, 120, true); text(input.subject, 100); text(input.source, 40); text(input.activityId, 120); if (!id(input.campusId) && !updating || !id(input.ownerId) || !Number.isSafeInteger(input.priority) || input.priority < 0 || input.priority > 10000 || typeof input.active !== 'boolean' || updating && !version(input.expectedVersion))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '分配规则字段或版本无效'); if (input.source != null && !['online_form', 'front_desk', 'phone', 'referral', 'import', 'other'].includes(input.source))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '来源类型无效'); if (input.activityId != null && !id(input.activityId))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '活动编号无效'); if (updating && Object.hasOwn(input, 'campusId'))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '更新规则不可更改校区'); return input; }
function validateLeadSelection(value) { const input = object(value, ['leads'], ['leads']); if (!Array.isArray(input.leads) || input.leads.length < 1 || input.leads.length > 100)
    return fail('INVALID_CRM_WORKFLOW_INPUT', '须选择1至100条线索'); const seen = new Set(); for (const raw of input.leads) {
    const lead = object(raw, ['leadId', 'expectedVersion'], ['leadId', 'expectedVersion']);
    if (!id(lead.leadId) || !version(lead.expectedVersion) || seen.has(lead.leadId))
        return fail('INVALID_CRM_WORKFLOW_INPUT', '线索编号重复或版本无效');
    seen.add(lead.leadId);
} return input; }
function validateImport(value) { const input = object(value, importFields, ['format', 'content', 'mapping', 'campusId']); if (!['csv', 'xlsx'].includes(input.format) || typeof input.content !== 'string' || !input.content.trim() || input.content.length > 950000 || !id(input.campusId))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '导入格式、内容或校区无效'); const mapping = object(input.mapping, importMapFields, ['name', 'phone']); for (const mapped of Object.values(mapping))
    text(mapped, 200, true); text(input.activityId, 120); text(input.source, 100); text(input.fileName, 200); return input; }
function allowed(operation, actorOrPermissions) { const permissions = Array.isArray(actorOrPermissions) ? actorOrPermissions : actorOrPermissions.permissions, scopeAllowed = Array.isArray(actorOrPermissions) || !operation.scopes || operation.scopes.includes(actorOrPermissions.scope); return operation.permissions.every(permission => permissions.includes(permission)) && scopeAllowed; }
function authorize(operation, identity, extra = []) { if (!allowed(operation, identity) || extra.some(permission => !identity.permissions.includes(permission)))
    fail('FORBIDDEN', '当前账号没有此操作权限或数据范围'); }
function flags(args, permitted) { const result = {}; for (let index = 0; index < args.length; index += 2) {
    const flag = args[index], value = args[index + 1];
    if (!permitted.includes(flag) || !value || Object.hasOwn(result, flag))
        return fail('INVALID_CRM_WORKFLOW_INPUT', '参数缺失、重复或不受支持');
    result[flag] = value;
} return result; }
function workflowKey(value) { if (!/^[A-Za-z0-9_-]{16,100}$/.test(value))
    return fail('INVALID_ARGUMENT', '必须提供有效的Java CRM幂等键'); return value; }
function validateRoutingPreview(input, value) { if (value === null || typeof value !== 'object' || Array.isArray(value))
    return fail('INVALID_PREVIEW', '路由预览结构不完整'); const preview = value, leads = input.leads; if (!Array.isArray(preview.plans) || preview.plans.length !== leads.length || !Number.isSafeInteger(preview.assignable) || preview.assignable < 0 || preview.assignable > leads.length || preview.unmatched !== leads.length - preview.assignable)
    return fail('INVALID_PREVIEW', '路由预览计数与所选线索不一致'); const ids = new Set(leads.map(row => row.leadId)); for (const raw of preview.plans) {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw))
        return fail('INVALID_PREVIEW', '路由预览项目与请求不匹配');
    const plan = raw;
    if (typeof plan.leadId !== 'string' || !ids.delete(plan.leadId) || plan.ownerId != null && !id(plan.ownerId) || plan.matchedRuleId != null && !id(plan.matchedRuleId) || typeof plan.reason !== 'string' || !Array.isArray(plan.candidates))
        return fail('INVALID_PREVIEW', '路由预览项目与请求不匹配');
} if (ids.size)
    return fail('INVALID_PREVIEW', '路由预览遗漏所选线索'); return preview; }
function validateImportPreview(value) { const fields = ['newCustomer', 'existingTouch', 'duplicateDemand', 'needsReview', 'invalid']; if (value === null || typeof value !== 'object' || Array.isArray(value))
    return fail('INVALID_PREVIEW', '导入预览结构不完整'); const preview = value, summary = preview.summary; if (!Array.isArray(preview.columns) || !Array.isArray(preview.fields) || !Array.isArray(preview.items) || !Number.isSafeInteger(preview.total) || preview.total < 1 || preview.items.length !== preview.total || summary === null || typeof summary !== 'object' || Array.isArray(summary))
    return fail('INVALID_PREVIEW', '导入预览结构不完整'); const counts = summary; if (fields.some(key => !Number.isSafeInteger(counts[key]) || counts[key] < 0) || fields.reduce((total, key) => total + counts[key], 0) !== preview.total || preview.items.some(row => row === null || typeof row !== 'object' || Array.isArray(row) || !Number.isSafeInteger(row.rowNumber) || typeof row.phoneMasked !== 'string' || !Array.isArray(row.errors)))
    return fail('INVALID_PREVIEW', '导入预览结构不完整'); return preview; }
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'import-preview') {
        const op = operations.find(item => item.id === 'crm.import.commit');
        authorize(op, identity);
        const f = flags(rest, ['--input']);
        if (!f['--input'])
            return fail('INVALID_CRM_WORKFLOW_INPUT', '需要--input');
        const input = validateImport(businessClient.read(f['--input']));
        return validateImportPreview(await client.request('crm/imports/preview', { method: 'POST', body: input }));
    }
    if (name === 'routing-rule-save')
        return saveRule(rest, client, identity);
    if (name === 'routing-simulate') {
        const op = operations.find(item => item.name === name);
        authorize(op, identity);
        if (rest.length !== 1 || !id(rest[0]))
            return fail('INVALID_CRM_WORKFLOW_INPUT', '请提供未分配线索编号');
        return client.request(`crm/lead-routing/leads/${encodeURIComponent(rest[0])}/simulate`);
    }
    if (name === 'routing-apply')
        return applyRouting(rest, client, identity);
    const op = operations.find(item => item.name === name);
    if (!op)
        return fail('INVALID_CRM_WORKFLOW_INPUT', '用法：crm order-attribution-request|order-attribution-review|routing-rule-save|routing-simulate|routing-apply|import-preview|import-commit');
    authorize(op, identity);
    if (name === 'import-commit')
        return commitImport(rest, client, op);
    const target = rest.shift();
    if (!id(target))
        return fail('INVALID_CRM_WORKFLOW_INPUT', '资源编号无效');
    const f = flags(rest, ['--input', '--key']);
    if (!f['--input'] || !f['--key'])
        return fail('INVALID_CRM_WORKFLOW_INPUT', '必须提供--input与--key');
    const body = validateAttribution(name || '', businessClient.read(f['--input']));
    return client.request(op.endpoint.replace('{id}', encodeURIComponent(target)), { method: op.method, idempotencyKey: orders.parseKey(f['--key']), body });
}
async function saveRule(args, client, identity) { const op = operations.find(item => item.name === 'routing-rule-save'); authorize(op, identity); const updating = Boolean(args[0] && !args[0].startsWith('--')), target = updating ? args.shift() : undefined; if (updating && !id(target))
    return fail('INVALID_CRM_WORKFLOW_INPUT', '规则编号无效'); const f = flags(args, ['--input', '--key']); if (!f['--input'] || !f['--key'])
    return fail('INVALID_CRM_WORKFLOW_INPUT', '必须提供--input与--key'); const body = validateRule(businessClient.read(f['--input']), updating); if (body.activityId && !identity.permissions.includes('crm.activity.read'))
    fail('FORBIDDEN', '关联活动需要crm.activity.read'); const endpoint = updating ? `${op.endpoint}/${encodeURIComponent(target || '')}` : op.endpoint; return client.request(endpoint, { method: updating ? 'PUT' : 'POST', idempotencyKey: workflowKey(f['--key']), body }); }
async function applyRouting(args, client, identity) { const op = operations.find(item => item.name === 'routing-apply'); authorize(op, identity); const f = flags(args, ['--input', '--key']); if (!f['--input'] || !f['--key'])
    return fail('INVALID_CRM_WORKFLOW_INPUT', '必须提供--input与--key'); const input = validateLeadSelection(businessClient.read(f['--input'])), key = workflowKey(f['--key']); validateRoutingPreview(input, await client.request('crm/lead-routing/preview', { method: 'POST', body: input })); return client.request(op.endpoint, { method: 'POST', idempotencyKey: key, body: input }); }
async function commitImport(args, client, operation) { const f = flags(args, ['--input', '--key']); if (!f['--input'] || !f['--key'])
    return fail('INVALID_CRM_WORKFLOW_INPUT', '必须提供--input与--key'); const input = validateImport(businessClient.read(f['--input'])), key = workflowKey(f['--key']), preview = validateImportPreview(await client.request('crm/imports/preview', { method: 'POST', body: input })); if (!Array.isArray(preview.items) || !preview.items.some(item => item !== null && typeof item === 'object' && !Array.isArray(item) && Array.isArray(item.errors) && item.errors.length === 0))
    return fail('INVALID_PREVIEW', '预览没有可导入的有效行'); return client.request(operation.endpoint, { method: 'POST', idempotencyKey: key, body: input }); }
module.exports = { operations, allowed, validateAttribution, validateRule, validateLeadSelection, validateImport, validateRoutingPreview, validateImportPreview, run };
