'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth, businessClient = business;
const permissions = ['crm.lead.read', 'crm.lead.write', 'crm.lead.follow'];
const operations = [
    { id: 'crm.consultation.register', name: 'register', method: 'POST', endpoint: 'crm/consultations', permissions: ['crm.lead.write', 'crm.customer.read'], permissionGroups: [['crm.lead.write'], ['crm.customer.read']] },
    { id: 'crm.consultation.notice.read', name: 'read', method: 'PUT', endpoint: 'crm/consultations/notices/{noticeId}/read', permissions, permissionGroups: [permissions] }
];
const fail = (code, message) => { throw authClient.failure(code, message); };
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
const id = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) { const match = typeof value === 'string' && /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?(Z|([+-])(\d\d):(\d\d))$/.exec(value); if (!match)
    return false; const date = value.slice(0, 10), oh = Number(match[9] || 0), om = Number(match[10] || 0); return Number.isFinite(Date.parse(`${date}T00:00:00Z`)) && new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date && Number(match[4]) < 24 && Number(match[5]) < 60 && Number(match[6]) < 60 && oh <= 18 && om < 60 && (oh !== 18 || om === 0) && Number.isFinite(Date.parse(value)); }
function page(input, key, max) { const value = input[key]; if (value !== undefined && (!Number.isSafeInteger(value) || value < 1 || value > max))
    fail('INVALID_CONSULTATION_INPUT', `${key}范围无效`); }
function validate(kind, value) {
    if (!object(value))
        return fail('INVALID_CONSULTATION_INPUT', '输入必须为JSON对象');
    const fields = { register: ['customerId', 'channel', 'note', 'campusId', 'campaignId', 'opportunityId', 'nextFollowAt'], query: ['scope', 'customerId', 'campaignId', 'ownerId', 'status', 'from', 'to', 'page', 'pageSize'], notices: ['status', 'page', 'pageSize'], read: ['expectedVersion'] };
    const allowed = fields[kind];
    if (!allowed || Object.keys(value).some(key => !allowed.includes(key)))
        return fail('INVALID_CONSULTATION_INPUT', '字段缺失或包含未支持字段');
    if (kind === 'register') {
        for (const key of ['customerId', 'channel', 'note'])
            if (typeof value[key] !== 'string' || !value[key].trim())
                return fail('INVALID_CONSULTATION_INPUT', `缺少或无效字段 ${key}`);
        if (!id(value.customerId) || !['phone', 'wechat', 'visit', 'online', 'other'].includes(value.channel) || value.note.length > 5000)
            return fail('INVALID_CONSULTATION_INPUT', '客户编号、咨询方式或内容无效');
        for (const key of ['campusId', 'campaignId', 'opportunityId'])
            if (value[key] !== undefined && !id(value[key]))
                return fail('INVALID_CONSULTATION_INPUT', `${key}编号无效`);
        if (value.nextFollowAt !== undefined && (!instant(value.nextFollowAt) || Date.parse(value.nextFollowAt) <= Date.now()))
            return fail('INVALID_CONSULTATION_INPUT', 'nextFollowAt须为未来带时区时间');
    }
    if (kind === 'query') {
        if (value.scope !== undefined && !['mine', 'team'].includes(value.scope))
            return fail('INVALID_CONSULTATION_INPUT', 'scope须为mine或team');
        for (const key of ['customerId', 'campaignId', 'ownerId'])
            if (value[key] !== undefined && !id(value[key]))
                return fail('INVALID_CONSULTATION_INPUT', `${key}编号无效`);
        if (value.status !== undefined && value.status !== 'recorded')
            return fail('INVALID_CONSULTATION_INPUT', 'status须为recorded');
        if (value.from !== undefined && !instant(value.from) || value.to !== undefined && !instant(value.to))
            return fail('INVALID_CONSULTATION_INPUT', '日期须为带时区时间');
        if (typeof value.from === 'string' && typeof value.to === 'string' && Date.parse(value.from) > Date.parse(value.to))
            return fail('INVALID_CONSULTATION_INPUT', 'from不能晚于to');
        page(value, 'page', 100000);
        page(value, 'pageSize', 100);
    }
    if (kind === 'notices') {
        if (value.status !== undefined && !['unread', 'read', 'all'].includes(value.status))
            return fail('INVALID_CONSULTATION_INPUT', '提醒状态无效');
        page(value, 'page', 100000);
        page(value, 'pageSize', 100);
    }
    if (kind === 'read' && (!Number.isSafeInteger(value.expectedVersion) || value.expectedVersion < 1))
        return fail('INVALID_CONSULTATION_INPUT', 'expectedVersion须为正整数');
    return value;
}
function requireAny(identity) { if (!permissions.some(permission => identity.permissions.includes(permission)))
    fail('FORBIDDEN', '当前员工缺少咨询读取权限'); }
function requireAll(identity, list) { for (const permission of list)
    if (!identity.permissions.includes(permission))
        fail('FORBIDDEN', `当前员工缺少所需权限 ${permission}`); }
function allowed(operation, granted) { const permissionsGiven = Array.isArray(granted) ? granted.filter((value) => typeof value === 'string') : []; return operation.permissionGroups.every(group => group.some(permission => permissionsGiven.includes(permission))); }
function parseFlags(args, allowedFlags) { const out = {}; for (let index = 0; index < args.length; index += 2) {
    const key = args[index], value = args[index + 1];
    if (!allowedFlags.includes(key) || !value || Object.hasOwn(out, key))
        fail('INVALID_ARGUMENT', `命令须使用${allowedFlags.join(' / ')}`);
    out[key] = value;
} return out; }
function parseKey(value) { if (!/^[A-Za-z0-9._:-]{16,128}$/.test(value))
    return fail('INVALID_ARGUMENT', '幂等键无效'); return value; }
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'list' || name === 'notices') {
        requireAny(identity);
        const flags = parseFlags(rest, ['--input']);
        const query = flags['--input'] ? businessClient.read(flags['--input']) : {};
        validate(name === 'list' ? 'query' : 'notices', query);
        if (!object(query))
            return fail('INVALID_CONSULTATION_INPUT', '查询条件须为JSON对象');
        const params = new URLSearchParams();
        for (const [key, value] of Object.entries(query))
            params.set(key, String(value));
        const endpoint = name === 'list' ? 'crm/consultations' : 'crm/consultations/notices';
        return client.request(endpoint + (params.size ? `?${params}` : ''));
    }
    if (name === 'detail') {
        requireAny(identity);
        if (rest.length !== 1 || !id(rest[0]))
            return fail('INVALID_ARGUMENT', '用法：consultations detail <咨询编号>');
        return client.request(`crm/consultations/${encodeURIComponent(rest[0])}`);
    }
    const operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('INVALID_ARGUMENT', '用法：consultations list|detail|register|notices|read');
    if (!allowed(operation, identity.permissions))
        throw authClient.failure('FORBIDDEN', '当前员工缺少咨询操作权限');
    let target = '', tail = rest;
    if (name === 'read') {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!id(target))
            return fail('INVALID_ARGUMENT', '提醒编号无效');
    }
    const flags = parseFlags(tail, ['--input', '--key']);
    if (!flags['--input'])
        return fail('INVALID_ARGUMENT', '需要 --input <JSON文件>');
    const body = validate(name || '', businessClient.read(flags['--input']));
    const endpoint = operation.endpoint.replace('{noticeId}', encodeURIComponent(target));
    if (name === 'register')
        requireAll(identity, ['crm.lead.write', 'crm.customer.read']);
    if (!flags['--key'])
        return fail('INVALID_ARGUMENT', name === 'register' ? '登记咨询需要 --key 幂等键' : '标记提醒已读需要 --key 幂等键');
    return client.request(endpoint, { method: operation.method, idempotencyKey: parseKey(flags['--key']), body });
}
module.exports = { operations, validate, allowed, run };
