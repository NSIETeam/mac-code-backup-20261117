'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [
    ['create', 'serviceTicket.create', 'POST', 'crm/service-tickets', ['crm.ticket.write', 'crm.customer.read'], ['customerId', 'typeCode', 'title', 'description'], ['ownerId', 'enrollmentId', 'classId', 'dueAt']],
    ['assign', 'serviceTicket.transfer', 'PUT', 'crm/service-tickets/{id}/assign', ['crm.ticket.assign'], ['ownerId', 'reason', 'expectedVersion'], []],
    ['followup', 'serviceTicket.followup', 'POST', 'crm/service-tickets/{id}/followups', ['crm.ticket.write'], ['detail', 'expectedVersion'], ['nextFollowAt']],
    ['close', 'serviceTicket.close', 'PUT', 'crm/service-tickets/{id}/close', ['crm.ticket.write'], ['resolution', 'expectedVersion'], []],
    ['link-refund', 'serviceTicket.linkRefund', 'PUT', 'crm/service-tickets/{id}/refund-request', ['crm.ticket.write', 'crm.refund.read', 'crm.order.read'], ['refundRequestId', 'expectedVersion'], []],
    ['type-create', 'serviceTicket.type.create', 'POST', 'crm/service-ticket-types', ['crm.ticket.type.manage'], ['typeCode', 'name', 'active'], ['campusId']],
    ['type-update', 'serviceTicket.type.update', 'PUT', 'crm/service-ticket-types/{id}', ['crm.ticket.type.manage'], ['name', 'active', 'expectedVersion'], []]
].map(([name, id, method, endpoint, permissions, required, optional]) => ({ name, id, method, endpoint, permissions, required, fields: [...required, ...optional] }));
operations.push({ name: 'types-registry', id: 'serviceTicket.types.registry.read', method: 'GET', endpoint: 'crm/service-ticket-types/registry', permissions: ['lead.assign'], scopes: ['all'], fields: [], required: [] }, { name: 'types-update', id: 'serviceTicket.types.update', method: 'PUT', endpoint: 'crm/service-ticket-types/registry', permissions: ['lead.assign'], scopes: ['all'], fields: ['types', 'expectedVersion'], required: ['types', 'expectedVersion'] });
const fail = (message) => { throw authClient.failure('INVALID_SERVICE_TICKET_INPUT', message); };
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) {
    const m = typeof value === 'string' && /^(\d{4}-\d\d-\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?(Z|[+-](\d\d):(\d\d))$/.exec(value);
    if (!m || +m[2] > 23 || +m[3] > 59 || +m[4] > 59 || +(m[6] || 0) > 18 || +(m[7] || 0) > 59 || (+m[6] === 18 && +m[7] !== 0))
        return false;
    const d = new Date(m[1] + 'T00:00:00Z');
    return Number.isFinite(d.getTime()) && d.toISOString().slice(0, 10) === m[1] && Number.isFinite(Date.parse(value));
}
function validate(name, value) {
    const op = operations.find(item => item.name === name);
    if (!op || !object(value))
        return fail('操作或JSON对象无效');
    if (Object.keys(value).some(key => !op.fields.includes(key)) || op.required.some(key => !Object.hasOwn(value, key)))
        return fail('字段缺失或包含未支持字段');
    if (name === 'types-update') {
        if (!Number.isSafeInteger(value.expectedVersion) || value.expectedVersion < 1 || !Array.isArray(value.types) || value.types.length < 1 || value.types.length > 30)
            return fail('全局工单类型目录版本或数量无效');
        const seen = new Set();
        for (const raw of value.types) {
            if (!object(raw) || Object.keys(raw).some(key => !['id', 'name', 'active'].includes(key)) || !['id', 'name', 'active'].every(key => Object.hasOwn(raw, key))
                || typeof raw.id !== 'string' || !/^[a-z][a-z0-9_]{1,39}$/.test(raw.id) || seen.has(raw.id) || typeof raw.name !== 'string' || !raw.name.trim() || raw.name.length > 100 || typeof raw.active !== 'boolean')
                return fail('工单类型编号重复或字段无效');
            seen.add(raw.id);
        }
        return value;
    }
    for (const key of Object.keys(value)) {
        const item = value[key];
        if (key === 'expectedVersion') {
            if (!Number.isSafeInteger(item) || item < 1)
                return fail('expectedVersion须为正整数');
            continue;
        }
        if (key === 'active') {
            if (typeof item !== 'boolean')
                return fail('active须为布尔值');
            continue;
        }
        if (key === 'dueAt' || key === 'nextFollowAt') {
            if (item !== null && !instant(item))
                return fail(`${key}须为带时区有效时间`);
            continue;
        }
        if (!op.required.includes(key) && (item === null || item === ''))
            continue;
        if (key.endsWith('Id')) {
            if (!validId(item))
                return fail(`${key}编号无效`);
            continue;
        }
        const max = { name: 100, typeCode: 40, title: 200, description: 5000, detail: 5000, resolution: 5000, reason: 1000 };
        if (typeof item !== 'string' || !item.trim() || item.length > max[key])
            return fail(`${key}内容无效`);
        if (key === 'typeCode' && name === 'type-create' && !/^[a-z][a-z0-9_]{1,39}$/.test(item))
            return fail('typeCode格式无效');
    }
    return value;
}
function requirePermissions(identity, permissions) { for (const p of permissions)
    if (!identity.permissions.includes(p))
        throw authClient.failure('FORBIDDEN', `缺少权限 ${p}`); }
function flags(args, allowed) { const result = {}; for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1];
    if (!allowed.includes(key) || !value || Object.hasOwn(result, key))
        return fail('参数缺失、重复或不受支持');
    result[key] = value;
} return result; }
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'events') {
        requirePermissions(identity, ['crm.ticket.read']);
        if (rest.length !== 1 || !validId(rest[0]))
            return fail('请提供工单编号');
        return client.request(`crm/service-tickets/${encodeURIComponent(rest[0])}/events`);
    }
    if (name === 'types') {
        requirePermissions(identity, ['crm.ticket.read']);
        const f = flags(rest, ['--campus', '--include-inactive']), params = new URLSearchParams();
        if (f['--campus']) {
            if (!validId(f['--campus']))
                return fail('校区编号无效');
            params.set('campusId', f['--campus']);
        }
        if (f['--include-inactive'] !== undefined) {
            if (!['true', 'false'].includes(f['--include-inactive']))
                return fail('include-inactive须为true或false');
            if (f['--include-inactive'] === 'true')
                requirePermissions(identity, ['crm.ticket.type.manage']);
            params.set('includeInactive', f['--include-inactive']);
        }
        return client.request('crm/service-ticket-types' + (params.size ? '?' + params : ''));
    }
    if (name === 'types-registry') {
        requirePermissions(identity, ['lead.assign']);
        if (identity.scope !== 'all')
            throw authClient.failure('FORBIDDEN', '工单类型全局目录仅限全部数据范围');
        if (rest.length)
            return fail('types-registry不接受参数');
        return client.request('crm/service-ticket-types/registry');
    }
    const op = operations.find(item => item.name === name);
    if (!op)
        return fail('用法：tickets create|assign|followup|close|link-refund|type-create|type-update|types|types-registry|types-update|events');
    requirePermissions(identity, op.permissions);
    let target = '', tail = rest;
    if (op.scopes && !op.scopes.includes(identity.scope))
        throw authClient.failure('FORBIDDEN', '工单操作数据范围不足');
    if (op.endpoint.includes('{id}')) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('目标编号无效');
    }
    const f = flags(tail, ['--input', '--key']);
    if (!f['--input'] || !f['--key'])
        return fail('必须提供--input与--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(f['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(name || '', businessClient.read(f['--input']));
    if (name === 'create') {
        if (typeof body.enrollmentId === 'string' && body.enrollmentId)
            requirePermissions(identity, ['enrollment.manage', 'class.read']);
        if (typeof body.classId === 'string' && body.classId)
            requirePermissions(identity, ['class.read']);
        if (typeof body.ownerId === 'string' && body.ownerId && body.ownerId !== identity.principalId)
            requirePermissions(identity, ['crm.ticket.assign']);
    }
    return client.request(op.endpoint.replace('{id}', encodeURIComponent(target)), { method: op.method, idempotencyKey: f['--key'], body });
}
module.exports = { operations, validate, run };
