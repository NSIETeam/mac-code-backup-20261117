'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const orders = require("./java-orders.cjs");
const parseKey = (value) => orders.parseKey(value);
const id = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) {
    if (typeof value !== 'string')
        return false;
    const match = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,9}))?(?:Z|[+-]\d\d:\d\d)$/.exec(value);
    if (!match || !Number.isFinite(Date.parse(value)))
        return false;
    const [, year, month, day, hour, minute, second] = match.map(Number);
    const calendar = new Date(Date.UTC(year, month - 1, day, hour, minute, second));
    return calendar.getUTCFullYear() === year && calendar.getUTCMonth() === month - 1 && calendar.getUTCDate() === day
        && hour <= 23 && minute <= 59 && second <= 59;
}
function epochNanos(value) {
    const fraction = /\.(\d{1,9})(?:Z|[+-]\d\d:\d\d)$/.exec(value)?.[1] || '';
    const remainder = (fraction.padEnd(9, '0').slice(3) || '0').padEnd(6, '0');
    return BigInt(Date.parse(value)) * 1000000n + BigInt(remainder);
}
const operations = [
    { name: 'customer-create', id: 'crm.customer.create', method: 'POST', endpoint: 'crm/customers', permissions: ['crm.customer.write'], fields: ['campusId', 'displayName', 'contacts'], required: ['campusId', 'displayName'] },
    { name: 'customer-from-lead', id: 'customer.fromLead', method: 'POST', endpoint: 'crm/customers/from-lead', permissions: ['lead.follow'], fields: ['leadId', 'customerId', 'expectedVersion'], required: ['leadId', 'expectedVersion'] },
    { name: 'customer-profile', id: 'crm.customer.profile.read', method: 'GET', endpoint: 'crm/customers/{id}/profile', permissions: ['lead.follow'], fields: [], required: [], readOnly: true },
    { name: 'lead-profile', id: 'crm.lead.profile.read', method: 'GET', endpoint: 'crm/customers/from-lead/{id}', permissions: ['lead.follow'], fields: [], required: [], readOnly: true },
    { name: 'campaign-profile', id: 'crm.campaign.profile.read', method: 'GET', endpoint: 'crm/activities/{id}/profile', permissions: ['lead.create'], fields: [], required: [], readOnly: true },
    { name: 'profile-options', id: 'crm.profile.options', method: 'GET', endpoint: 'crm/profile-options', permissions: [], permissionGroups: [['lead.create', 'lead.follow']], fields: ['kind', 'campusId', 'page', 'size'], required: [], readOnly: true },
    { name: 'customer-update', id: 'customer.update', method: 'PUT', endpoint: 'crm/customers/{id}', permissions: ['lead.follow'], fields: ['patch', 'expectedVersion'], required: ['patch', 'expectedVersion'] },
    { name: 'customer-contact-add', id: 'crm.customer.contact.add', method: 'POST', endpoint: 'crm/customers/{id}/contacts', permissions: ['crm.customer.write'], fields: ['personRole', 'channel', 'value', 'primary'], required: ['personRole', 'channel', 'value', 'primary'] },
    { name: 'customer-link-student', id: 'crm.customer.student.link', method: 'POST', endpoint: 'crm/customers/{id}/students', permissions: ['crm.customer.write', 'student.read'], fields: ['studentId', 'relationship'], required: ['studentId', 'relationship'] },
    { name: 'touchpoint-create', id: 'crm.customer.touchpoint.create', method: 'POST', endpoint: 'crm/customers/{id}/touchpoints', permissions: ['crm.touchpoint.write', 'crm.customer.read'], fields: ['activityId', 'studentId', 'source', 'channel', 'summary', 'occurredAt'], required: ['source', 'channel', 'summary', 'occurredAt'] },
    { name: 'campaign-create', id: 'crm.activity.create', method: 'POST', endpoint: 'crm/activities', permissions: ['crm.activity.write'], fields: ['campusId', 'title', 'activityType', 'startsAt', 'endsAt', 'description', 'participantIds', 'promoterIds'], required: ['campusId', 'title', 'activityType', 'startsAt', 'endsAt'] },
    { name: 'campaign-close', id: 'crm.activity.close', method: 'PUT', endpoint: 'crm/activities/{id}/close', permissions: ['crm.activity.write'], fields: ['expectedVersion'], required: ['expectedVersion'] },
    { name: 'campaign-update', id: 'campaign.update', method: 'PUT', endpoint: 'crm/activities/{id}', permissions: ['lead.create'], fields: ['patch', 'expectedVersion'], required: ['patch', 'expectedVersion'] },
    { name: 'campaign-draft-create', id: 'campaign.create', method: 'POST', endpoint: 'crm/activities/campaigns', permissions: ['lead.create'], fields: ['name', 'campusId', 'channel', 'description', 'start', 'end', 'budgetMinor', 'targetCount', 'formIds', 'participantIds', 'promoterIds'], required: ['name'] },
    { name: 'campaign-publish', id: 'campaign.publish', method: 'POST', endpoint: 'crm/activities/{id}/publish', permissions: ['lead.create'], fields: ['expectedVersion'], required: ['expectedVersion'] },
    { name: 'campaign-promotion-links', id: 'campaign.promotionLink.read', method: 'GET', endpoint: 'crm/activities/{id}/promotion-links', permissions: ['lead.create'], fields: [], required: [], readOnly: true },
    { name: 'campaign-promotion-link-create', id: 'campaign.promotionLink.create', method: 'POST', endpoint: 'crm/activities/{id}/promotion-links', permissions: ['lead.create'], fields: ['formId', 'promoterId', 'name', 'expectedVersion'], required: ['formId', 'promoterId', 'name', 'expectedVersion'] },
    { name: 'campaign-members', id: 'crm.activity.members.replace', method: 'PUT', endpoint: 'crm/activities/{id}/members', permissions: ['crm.activity.write'], fields: ['participantIds', 'promoterIds', 'expectedVersion'], required: ['participantIds', 'promoterIds', 'expectedVersion'] },
    { name: 'task-update', id: 'crm.task.update', method: 'PUT', endpoint: 'crm/tasks/{id}', permissions: ['crm.task.write'], fields: ['ownerId', 'status', 'dueAt', 'reason', 'expectedVersion'], required: ['expectedVersion'] },
    { name: 'task-complete', id: 'crm.task.complete', method: 'POST', endpoint: 'crm/tasks/{id}/complete', permissions: ['crm.task.write'], fields: ['result', 'note', 'opportunityId', 'expectedVersion', 'needResolution'], required: ['result', 'expectedVersion'] },
    { name: 'order-payment-link', id: 'order.paymentLink', method: 'POST', endpoint: 'crm/orders/{id}/payment-link', permissions: ['order.paymentLink'], fields: [], required: [] },
];
function operation(name) { const found = operations.find(item => item.name === name); if (!found)
    return fail('不支持的CRM操作'); return found; }
function fail(message) { throw auth.failure('INVALID_CRM_INPUT', message); }
function integer(value) { return typeof value === 'number' && Number.isSafeInteger(value); }
function oneOf(values, value) { return values.includes(value); }
function exactObject(value, allowed, required = []) {
    if (!value || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length
        || Object.keys(value).some(key => !allowed.includes(key)) || required.some(key => !Object.hasOwn(value, key)))
        fail('输入必须是字段精确匹配的JSON对象');
}
function validate(name, input) {
    const op = operation(name);
    exactObject(input, op.fields, op.required);
    if (name === 'profile-options') {
        if (!oneOf(['campuses', 'forms', 'employees'], input.kind))
            fail('kind须为campuses、forms或employees');
        if (input.campusId != null && !id(input.campusId))
            fail('campusId无效');
        if (input.kind === 'campuses' && input.campusId != null)
            fail('campuses选项不接受campusId筛选');
        if (input.kind === 'employees' && !input.campusId)
            fail('employees选项必须指定campusId');
        for (const key of ['page', 'size'])
            if (input[key] != null && (!integer(input[key]) || input[key] < 1 || input[key] > (key === 'page' ? 100000 : 100)))
                fail(`${key}超出范围`);
        return input;
    }
    if (name === 'customer-update' || name === 'campaign-update') {
        if (!integer(input.expectedVersion) || input.expectedVersion < 1)
            fail('expectedVersion须为正整数');
        const patch = input.patch;
        const allowed = name === 'customer-update'
            ? ['name', 'grade', 'note', 'campusId', 'contacts']
            : ['name', 'campusId', 'channel', 'description', 'start', 'end', 'budgetMinor', 'targetCount', 'formIds', 'participantIds', 'promoterIds'];
        exactObject(patch, allowed);
        if (Object.keys(patch).length === 0)
            fail('patch至少需要一个支持的字段');
        if (name === 'customer-update')
            validateCustomerPatch(patch);
        else
            validateCampaignPatch(patch);
        return input;
    }
    if (name === 'customer-from-lead') {
        if (!id(input.leadId) || input.customerId != null && !id(input.customerId)
            || !integer(input.expectedVersion) || input.expectedVersion < 1)
            fail('线索编号、客户编号或版本无效');
        return input;
    }
    if (name === 'campaign-draft-create') {
        validateCampaignPatch(input);
        if (input.name == null || typeof input.name !== 'string' || !input.name.trim() || input.name.length > 200)
            fail('name内容无效');
        return input;
    }
    if (name === 'campaign-publish') {
        if (!integer(input.expectedVersion) || input.expectedVersion < 1)
            fail('expectedVersion须为正整数');
        return input;
    }
    if (name === 'campaign-promotion-link-create') {
        if (!id(input.formId) || !id(input.promoterId))
            fail('formId或promoterId无效');
        if (typeof input.name !== 'string' || !input.name.trim() || input.name.length > 200)
            fail('name内容无效');
        if (!integer(input.expectedVersion) || input.expectedVersion < 1)
            fail('expectedVersion须为正整数');
        return input;
    }
    for (const [key, value] of Object.entries(input)) {
        if (value == null && !op.required.includes(key))
            continue;
        if (key === 'expectedVersion') {
            if (!integer(value) || value < 1)
                fail('expectedVersion须为正整数');
            continue;
        }
        if (key.endsWith('Id') && key !== 'activityType') {
            if (!id(value))
                fail(`${key}无效`);
            continue;
        }
        if (['startsAt', 'endsAt', 'occurredAt', 'dueAt'].includes(key)) {
            if (!instant(value))
                fail(`${key}须为有效带时区时间`);
            continue;
        }
        if (['participantIds', 'promoterIds'].includes(key)) {
            if (!Array.isArray(value) || value.length > 200 || value.some(item => !id(item)) || new Set(value).size !== value.length)
                fail(`${key}须为去重编号数组`);
            continue;
        }
        if (key === 'contacts') {
            if (!Array.isArray(value) || value.length > 20)
                fail('contacts最多20项');
            value.forEach(contact => {
                exactObject(contact, ['personRole', 'channel', 'value', 'primary'], ['personRole', 'channel', 'value', 'primary']);
                if (!oneOf(['guardian', 'student', 'other'], contact.personRole) || !oneOf(['phone', 'email'], contact.channel)
                    || typeof contact.value !== 'string' || !contact.value.trim() || contact.value.length > 254 || typeof contact.primary !== 'boolean')
                    fail('联系人信息无效');
            });
            continue;
        }
        if (key === 'needResolution') {
            exactObject(value, ['departmentId', 'projectId', 'expectedOpportunityVersion', 'reason'], ['departmentId', 'expectedOpportunityVersion', 'reason']);
            if (!id(value.departmentId) || value.projectId != null && !id(value.projectId) || !integer(value.expectedOpportunityVersion) || value.expectedOpportunityVersion < 1 || typeof value.reason !== 'string' || !value.reason.trim() || value.reason.length > 1000)
                fail('needResolution内容无效');
            continue;
        }
        const max = { campusId: 120, displayName: 120, relationship: 20, source: 30, channel: 20, summary: 2000, title: 120, activityType: 20, description: 2000, status: 20, reason: 1000, result: 30, note: 5000 }[key];
        if (typeof value !== 'string' || (max !== undefined && value.length > max) || op.required.includes(key) && !value.trim())
            fail(`${key}内容无效`);
        const enums = { relationship: ['child', 'ward', 'self', 'other'], source: ['front_desk', 'online_form', 'phone', 'wecom', 'referral', 'import', 'other'], channel: ['phone', 'wecom', 'text', 'in_person', 'form', 'import'], activityType: ['campaign', 'event', 'referral', 'other'], status: ['pending', 'in_progress', 'cancelled'], result: ['qualified', 'no_need', 'unreachable', 'invalid_contact', 'completed', 'refused'] };
        if (enums[key] && !enums[key].includes(value))
            fail(`${key}选项无效`);
    }
    if (name === 'campaign-create' && (!instant(input.endsAt) || !instant(input.startsAt) || epochNanos(input.endsAt) <= epochNanos(input.startsAt)))
        fail('endsAt必须晚于startsAt');
    if (name === 'campaign-create' && input.activityType !== 'campaign')
        fail('campaign-create仅允许activityType=campaign');
    if (name === 'task-complete' && input.needResolution != null && input.result !== 'qualified')
        fail('只有qualified结果可提供needResolution');
    if (name === 'task-update') {
        if (!['ownerId', 'status', 'dueAt'].some(key => input[key] != null))
            fail('任务安排没有变化');
        if ((input.ownerId != null || input.dueAt != null || input.status === 'cancelled') && (typeof input.reason !== 'string' || !input.reason.trim()))
            fail('改派、延期或取消任务必须填写原因');
    }
    return input;
}
function validateCustomerPatch(patch) {
    for (const key of ['name', 'grade', 'note']) {
        if (!Object.hasOwn(patch, key) || patch[key] == null && key !== 'name')
            continue;
        const max = { name: 100, grade: 50, note: 5000 }[key];
        if (typeof patch[key] !== 'string' || (max !== undefined && patch[key].length > max) || key === 'name' && !patch[key].trim())
            fail(`${key}内容无效`);
    }
    if (Object.hasOwn(patch, 'campusId') && patch.campusId != null && !id(patch.campusId))
        fail('campusId无效');
    if (Object.hasOwn(patch, 'contacts')) {
        if (!Array.isArray(patch.contacts) || patch.contacts.length < 1 || patch.contacts.length > 10)
            fail('contacts须为1至10项');
        const phones = new Set(), contactIds = new Set();
        patch.contacts.forEach(contact => {
            exactObject(contact, ['id', 'name', 'relationship', 'phone', 'primary'], ['phone']);
            if (contact.id != null && !id(contact.id) || typeof contact.phone !== 'string'
                || !/^\+?[0-9]{6,20}$/.test(contact.phone.replace(/[\s()\-]/g, ''))
                || contact.name != null && (typeof contact.name !== 'string' || contact.name.length > 100)
                || contact.relationship != null && (typeof contact.relationship !== 'string' || contact.relationship.length > 50)
                || contact.primary != null && typeof contact.primary !== 'boolean')
                fail('联系方式字段无效');
            const phone = contact.phone.replace(/[\s()\-]/g, '');
            if (phones.has(phone) || contact.id != null && contactIds.has(contact.id))
                fail('联系方式手机号或编号重复');
            phones.add(phone);
            if (contact.id != null)
                contactIds.add(contact.id);
        });
        if (patch.contacts.filter(contact => contact.primary === true).length > 1)
            fail('只能设置一个主要联系方式');
    }
}
function validateCampaignPatch(patch) {
    if (Object.hasOwn(patch, 'name') && (typeof patch.name !== 'string' || !patch.name.trim() || patch.name.length > 200))
        fail('name内容无效');
    if (Object.hasOwn(patch, 'campusId') && patch.campusId != null && !id(patch.campusId))
        fail('campusId无效');
    for (const key of ['channel', 'description'])
        if (Object.hasOwn(patch, key) && patch[key] != null
            && (typeof patch[key] !== 'string' || patch[key].length > (key === 'channel' ? 100 : 3000)))
            fail(`${key}内容无效`);
    for (const key of ['start', 'end'])
        if (Object.hasOwn(patch, key) && patch[key] != null && !instant(patch[key]))
            fail(`${key}须为有效带时区时间`);
    for (const [key, max] of [['budgetMinor', 100000000000], ['targetCount', 1000000]]) {
        if (Object.hasOwn(patch, key) && patch[key] != null && (!integer(patch[key]) || patch[key] < 0 || patch[key] > max))
            fail(`${key}超出范围`);
    }
    for (const key of ['formIds', 'participantIds', 'promoterIds'])
        if (Object.hasOwn(patch, key)) {
            if (!Array.isArray(patch[key]) || patch[key].length > 100 || patch[key].some(item => !id(item))
                || new Set(patch[key]).size !== patch[key].length)
                fail(`${key}须为去重编号数组`);
        }
}
function permissionCheck(identity, op, input) {
    const permissions = identity?.permissions || [];
    const required = [...op.permissions];
    if (op.name === 'customer-link-student' || input.studentId)
        required.push('student.read');
    if (op.name === 'customer-update' && input.patch != null && typeof input.patch === 'object' && Object.hasOwn(input.patch, 'campusId'))
        required.push('lead.assign');
    if (op.name === 'touchpoint-create' && input.activityId)
        required.push('crm.activity.read');
    if (op.name === 'task-complete' && input.result === 'qualified')
        required.push('crm.opportunity.read');
    if (op.name === 'task-update' && (input.ownerId || input.dueAt || input.status === 'cancelled'))
        required.push('crm.task.assign');
    for (const permission of new Set(required))
        if (!permissions.includes(permission))
            throw auth.failure('FORBIDDEN', `缺少权限 ${permission}`);
}
function allowed(op, permissions = []) {
    const granted = new Set(permissions);
    if (op.permissionGroups)
        return op.permissionGroups.every(group => group.some(code => granted.has(code)));
    return (op.permissions || []).every(code => granted.has(code));
}
async function run(args, client, identity) {
    const [name, ...tail] = args;
    if (name === 'order-payment-link') {
        if (tail.length !== 1 || !id(tail[0]))
            fail('用法：crm order-payment-link <订单编号>');
        if (!identity?.permissions?.includes('order.paymentLink'))
            throw auth.failure('FORBIDDEN', '缺少权限 order.paymentLink');
        return client.request(`crm/orders/${encodeURIComponent(tail[0])}/payment-link`, { method: 'POST' });
    }
    const op = operation(name || '');
    if (name === 'profile-options') {
        const [kind, ...options] = tail;
        const parsed = {};
        for (let index = 0; index < options.length; index += 2) {
            const key = options[index], value = options[index + 1];
            if (!['--campus', '--page', '--size'].includes(key) || !value || Object.hasOwn(parsed, key))
                fail('用法：crm profile-options <campuses|forms|employees> [--campus ID] [--page N] [--size N]');
            parsed[key] = value;
        }
        const input = { kind, ...(parsed['--campus'] ? { campusId: parsed['--campus'] } : {}),
            ...(parsed['--page'] ? { page: Number(parsed['--page']) } : {}), ...(parsed['--size'] ? { size: Number(parsed['--size']) } : {}) };
        validate(name, input);
        const permissions = identity?.permissions || [];
        const allowed = kind === 'campuses' ? permissions.includes('lead.create') || permissions.includes('lead.follow') : permissions.includes('lead.create');
        if (!allowed)
            throw auth.failure('FORBIDDEN', kind === 'campuses' ? '缺少权限 lead.create 或 lead.follow' : '缺少权限 lead.create');
        const query = new URLSearchParams();
        for (const [key, value] of Object.entries(input))
            query.set(key, String(value));
        return client.request(`${op.endpoint}?${query}`, { method: 'GET' });
    }
    if (op.readOnly) {
        if (tail.length !== 1 || !id(tail[0]))
            fail(`用法：crm ${name} <编号>`);
        if (!identity?.permissions?.includes(op.permissions[0]))
            throw auth.failure('FORBIDDEN', `缺少权限 ${op.permissions[0]}`);
        return client.request(op.endpoint.replace('{id}', encodeURIComponent(tail[0])), { method: 'GET' });
    }
    let target = '', rest = tail;
    if (op.endpoint.includes('{id}')) {
        target = rest.shift() || '';
        if (!id(target))
            fail('资源编号无效');
    }
    const flags = {};
    for (let i = 0; i < rest.length; i += 2) {
        if (!['--input', '--key'].includes(rest[i]) || !rest[i + 1] || Object.hasOwn(flags, rest[i]))
            fail('参数缺失、重复或不受支持');
        flags[rest[i]] = rest[i + 1];
    }
    if (!flags['--input'] || !flags['--key'])
        fail('必须提供--input与--key');
    const input = validate(name, business.read(flags['--input']));
    permissionCheck(identity, op, input);
    const key = parseKey(flags['--key']);
    return client.request(op.endpoint.replace('{id}', encodeURIComponent(target)), { method: op.method, idempotencyKey: key, body: input });
}
module.exports = { operations, validate, run, permissionCheck, allowed };
