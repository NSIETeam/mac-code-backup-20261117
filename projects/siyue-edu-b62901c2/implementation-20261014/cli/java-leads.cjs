'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const consultations = require("./java-consultations.cjs");
const orders = require("./java-orders.cjs");
const authClient = auth, businessClient = business;
const operations = [
    ['create', 'lead.create', 'POST', 'crm/leads', ['crm.lead.write', 'crm.lead.follow'], ['customerId', 'source', 'title'], ['activityId', 'studentId', 'subject', 'term', 'detail', 'nextFollowAt']],
    ['assign', 'lead.assign', 'POST', 'crm/leads/{id}/assign', ['crm.lead.assign'], ['ownerId', 'reason', 'expectedVersion'], []],
    ['transfer', 'lead.transfer', 'POST', 'crm/leads/{id}/transfer', ['crm.lead.assign'], ['ownerId', 'reason', 'expectedVersion'], []],
    ['release', 'lead.release', 'POST', 'crm/leads/{id}/release', ['crm.lead.assign'], ['reason', 'expectedVersion'], []],
    ['sales-stage', 'lead.sales-stage.update', 'PUT', 'crm/leads/{id}/sales-stage', ['lead.follow'], ['stage', 'expectedVersion'], ['reason']],
    ['status', 'lead.status.update', 'PUT', 'crm/leads/{id}/status', ['crm.lead.write', 'crm.customer.read'], ['status', 'expectedVersion'], ['reason', 'nextFollowAt']],
    ['followup', 'lead.followup', 'POST', 'crm/leads/{id}/followups', ['crm.lead.write'], ['channel', 'summary', 'expectedVersion'], ['nextFollowAt']],
    ['batch-assign', 'lead.batch.assign', 'POST', 'crm/leads/batch-assign', ['lead.assign'], ['leadIds', 'expectedVersions', 'ownerIds', 'strategy', 'reason'], ['quotas']]
].map(([name, id, method, endpoint, permissions, required, optional]) => ({ name, id, method, endpoint, permissions, required, fields: [...required, ...optional], permissionGroups: [] }));
const groups = { create: [['lead.create'], ['crm.lead.write', 'crm.lead.follow']], assign: [['lead.assign'], ['crm.lead.assign']], transfer: [['lead.transfer'], ['crm.lead.assign']], release: [['lead.release'], ['crm.lead.assign']], followup: [['lead.follow'], ['crm.lead.write']], 'sales-stage': [['lead.follow']], 'batch-assign': [['lead.assign']] };
for (const operation of operations)
    operation.permissionGroups = groups[operation.name] || [operation.permissions];
const fail = (message) => { throw authClient.failure('INVALID_LEAD_INPUT', message); };
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function isObject(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
function validate(name, value) {
    const op = operations.find(item => item.name === name);
    if (!op || !isObject(value))
        return fail('输入必须是JSON对象');
    if (Object.keys(value).some(key => !op.fields.includes(key)) || op.required.some(key => !Object.hasOwn(value, key)))
        return fail('字段缺失或包含未支持字段');
    if (name === 'batch-assign') {
        const leadIds = value.leadIds, ownerIds = value.ownerIds, versions = value.expectedVersions;
        const validIds = (ids, min, max) => Array.isArray(ids) && ids.length >= min && ids.length <= max && ids.every(validId) && new Set(ids).size === ids.length;
        if (!validIds(leadIds, 1, 498) || !validIds(ownerIds, 1, 100))
            return fail('批量分配最多498条线索、100名员工，且编号须唯一有效');
        if (!isObject(versions) || Object.keys(versions).length !== leadIds.length || Object.keys(versions).some(id => !leadIds.includes(id) || !Number.isSafeInteger(versions[id]) || versions[id] < 1))
            return fail('每条所选线索都须提供当前expectedVersion');
        if (!['even', 'quota', 'owner_first'].includes(value.strategy))
            return fail('strategy无效');
        if (typeof value.reason !== 'string' || !value.reason.trim() || value.reason.length > 1000)
            return fail('reason内容无效');
        if (value.strategy === 'quota') {
            if (!Array.isArray(value.quotas) || value.quotas.length !== ownerIds.length)
                return fail('quota策略须为每位员工提供名额');
            const seen = new Set();
            let total = 0;
            for (const raw of value.quotas) {
                if (!isObject(raw) || Object.keys(raw).some(key => !['ownerId', 'count'].includes(key)) || !ownerIds.includes(raw.ownerId) || seen.has(raw.ownerId) || !Number.isInteger(raw.count) || raw.count < 0 || raw.count > 10000)
                    return fail('quotas配置无效');
                seen.add(raw.ownerId);
                total += raw.count;
            }
            if (total !== leadIds.length)
                return fail('quotas总和须等于线索数');
        }
        else if (value.quotas !== undefined)
            return fail('只有quota策略可以设置quotas');
        return value;
    }
    for (const [key, item] of Object.entries(value)) {
        if (!op.required.includes(key) && item === null)
            continue;
        if (key === 'expectedVersion') {
            if (!Number.isSafeInteger(item) || item < 1)
                return fail('expectedVersion须为正整数');
            continue;
        }
        if (key === 'nextFollowAt') {
            try {
                consultations.validate('query', { from: item });
            }
            catch {
                return fail('nextFollowAt须为有效带时区时间');
            }
            continue;
        }
        if (key.endsWith('Id')) {
            if (!validId(item))
                return fail(`${key}编号无效`);
            continue;
        }
        const max = { source: 40, title: 200, subject: 100, term: 100, detail: 5000, reason: 1000, stage: 24, status: 24, channel: 30, summary: 2000 };
        if (typeof item !== 'string' || item.length > max[key] || op.required.includes(key) && !item.trim())
            return fail(`${key}内容无效`);
        const enums = { stage: ['待报名', '已流失', '跟进中'], source: ['online_form', 'front_desk', 'phone', 'referral', 'import', 'other'], status: ['new', 'contacting', 'qualified', 'no_need', 'unreachable', 'invalid_contact', 'archived'], channel: ['phone', 'wecom', 'in_person', 'online', 'other'] };
        if (enums[key] && !enums[key].includes(item))
            return fail(`${key}选项无效`);
    }
    return value;
}
function allowed(operation, permissions) { return operation.permissionGroups.some(group => group.every(permission => permissions.includes(permission))); }
function requirePermissions(identity, permissions) { for (const permission of permissions)
    if (!identity.permissions.includes(permission))
        throw authClient.failure('FORBIDDEN', `缺少权限 ${permission}`); }
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'sales-stage-get') {
        if (!['crm.lead.read', 'lead.read', 'lead.create', 'lead.follow', 'lead.assign'].some(p => identity.permissions.includes(p)))
            throw authClient.failure('FORBIDDEN', '缺少线索读取权限');
        if (rest.length !== 1 || !validId(rest[0]))
            return fail('请提供线索编号');
        return client.request(`crm/leads/${encodeURIComponent(rest[0])}/sales-stage`);
    }
    if (name === 'events') {
        requirePermissions(identity, ['crm.lead.read']);
        if (rest.length !== 1 || !validId(rest[0]))
            return fail('请提供线索编号');
        return client.request(`crm/leads/${encodeURIComponent(rest[0])}/events`);
    }
    const op = operations.find(item => item.name === name);
    if (!op)
        return fail('用法：leads create|assign|transfer|release|status|sales-stage|sales-stage-get|followup|batch-assign|events');
    if (!allowed(op, identity.permissions))
        throw authClient.failure('FORBIDDEN', '缺少该线索操作所需权限');
    let target = '', tail = rest;
    if (op.endpoint.includes('{id}')) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('线索编号无效');
    }
    const flags = {};
    for (let i = 0; i < tail.length; i += 2) {
        const flag = tail[i], item = tail[i + 1];
        if (!['--input', '--key'].includes(flag) || !item || Object.hasOwn(flags, flag))
            return fail('参数缺失、重复或不受支持');
        flags[flag] = item;
    }
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供--input与--key');
    const body = validate(name || '', businessClient.read(flags['--input'])), key = orders.parseKey(flags['--key']);
    if (name === 'create') {
        if (typeof body.activityId === 'string' && body.activityId)
            requirePermissions(identity, ['crm.activity.read']);
        if (typeof body.studentId === 'string' && body.studentId)
            requirePermissions(identity, ['student.read']);
    }
    return client.request(op.endpoint.replace('{id}', encodeURIComponent(target)), { method: op.method, idempotencyKey: key, body });
}
module.exports = { operations, validate, run, allowed };
