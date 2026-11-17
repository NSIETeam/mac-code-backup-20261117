'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [
    { name: 'create', id: 'opportunity.create', method: 'POST', endpoint: 'crm/opportunities', permissions: ['crm.opportunity.write', 'crm.customer.read'], required: ['customerId', 'title', 'subject', 'term'], fields: ['customerId', 'title', 'subject', 'term', 'sourceLeadId', 'activityId', 'studentId', 'detail'] },
    { name: 'followup', id: 'opportunity.followup', method: 'POST', endpoint: 'crm/opportunities/{id}/followups', permissions: ['crm.opportunity.write'], required: ['channel', 'detail', 'expectedVersion'], fields: ['channel', 'detail', 'expectedVersion', 'nextFollowAt'] },
    { name: 'advance', id: 'opportunity.advance', method: 'PUT', endpoint: 'crm/opportunities/{id}/stage', permissions: ['crm.opportunity.write'], required: ['stage', 'expectedVersion'], fields: ['stage', 'expectedVersion', 'reason', 'nextFollowAt'] }
];
const fail = (message) => { throw authClient.failure('INVALID_OPPORTUNITY_INPUT', message); };
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) {
    const match = typeof value === 'string' && /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?(Z|([+-])(\d\d):(\d\d))$/.exec(value);
    if (!match)
        return false;
    const date = value.slice(0, 10), offsetHour = Number(match[9] || 0), offsetMinute = Number(match[10] || 0);
    return Number.isFinite(Date.parse(`${date}T00:00:00Z`)) && new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) === date
        && Number(match[4]) < 24 && Number(match[5]) < 60 && Number(match[6]) < 60 && offsetHour <= 18 && offsetMinute < 60
        && (offsetHour !== 18 || offsetMinute === 0) && Number.isFinite(Date.parse(value));
}
function validate(name, value) {
    const operation = operations.find(item => item.name === name);
    if (!operation || value === null || typeof value !== 'object' || Array.isArray(value)
        || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length > 0)
        return fail('输入必须是JSON对象');
    const input = value;
    if (Object.keys(input).some(key => !operation.fields.includes(key)) || operation.required.some(key => !Object.hasOwn(input, key)))
        return fail('字段缺失或包含未支持字段');
    for (const [key, item] of Object.entries(input)) {
        if (!operation.required.includes(key) && item === null)
            continue;
        if (key === 'expectedVersion') {
            if (!Number.isSafeInteger(item) || item < 1)
                return fail('expectedVersion须为正整数');
            continue;
        }
        if (key === 'nextFollowAt') {
            if (!instant(item) || Date.parse(item) <= Date.now())
                return fail('nextFollowAt须为有效且晚于当前时间的带时区时间');
            continue;
        }
        if (key.endsWith('Id')) {
            if (!validId(item))
                return fail(`${key}编号无效`);
            continue;
        }
        const max = { title: 200, subject: 100, term: 100, detail: 5000, reason: 1000, stage: 20, channel: 30 };
        if (typeof item !== 'string' || item.length > max[key] || operation.required.includes(key) && !item.trim())
            return fail(`${key}内容无效`);
        const enums = { stage: ['qualified', 'appointment', 'trial', 'negotiation', 'closed'], channel: ['phone', 'wecom', 'in_person', 'online', 'other'] };
        if (enums[key] && !enums[key].includes(item))
            return fail(`${key}选项无效`);
    }
    return input;
}
function requirePermissions(identity, permissions) {
    for (const permission of permissions)
        if (!identity.permissions.includes(permission))
            throw authClient.failure('FORBIDDEN', `缺少权限 ${permission}`);
}
function parseFlags(args) {
    const flags = {};
    for (let index = 0; index < args.length; index += 2) {
        const flag = args[index], value = args[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(flags, flag))
            return fail('参数缺失、重复或不受支持');
        flags[flag] = value;
    }
    return flags;
}
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'events') {
        requirePermissions(identity, ['crm.opportunity.read']);
        if (rest.length !== 1 || !validId(rest[0]))
            return fail('请提供商机编号');
        return client.request(`crm/opportunities/${encodeURIComponent(rest[0])}/events`);
    }
    const operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('用法：opportunities create|followup|advance|events');
    requirePermissions(identity, operation.permissions);
    let target = '', tail = rest;
    if (operation.endpoint.includes('{id}')) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('商机编号无效');
    }
    const flags = parseFlags(tail);
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供--input与--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(flags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(name || '', businessClient.read(flags['--input']));
    if (name === 'create') {
        if (typeof body.sourceLeadId === 'string' && body.sourceLeadId)
            requirePermissions(identity, ['crm.lead.read']);
        if (typeof body.activityId === 'string' && body.activityId)
            requirePermissions(identity, ['crm.activity.read']);
        if (typeof body.studentId === 'string' && body.studentId)
            requirePermissions(identity, ['student.read']);
    }
    return client.request(operation.endpoint.replace('{id}', encodeURIComponent(target)), { method: operation.method, idempotencyKey: flags['--key'], body });
}
module.exports = { operations, validate, run };
