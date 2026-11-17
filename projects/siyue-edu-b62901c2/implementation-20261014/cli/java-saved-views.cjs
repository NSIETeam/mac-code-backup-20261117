'use strict';
const auth = require('./auth-client.cjs');
const business = require('./business-client.cjs');
const orders = require('./java-orders.cjs');
const permissions = {
    customers: 'crm.customer.read', leads: 'crm.lead.read', tasks: 'crm.task.read', taskBatches: 'crm.task.read',
    opportunities: 'crm.opportunity.read', serviceTickets: 'crm.ticket.read',
};
const operations = [
    { id: 'crm.view.create', name: 'create', method: 'POST', endpoint: 'crm/saved-views', permissionGroups: [Object.values(permissions)] },
    { id: 'crm.view.update', name: 'update', method: 'PUT', endpoint: 'crm/saved-views/{viewId}', permissionGroups: [Object.values(permissions)] },
    { id: 'crm.view.archive', name: 'archive', method: 'POST', endpoint: 'crm/saved-views/{viewId}/delete', permissionGroups: [Object.values(permissions)] },
];
function fail(message) { throw auth.failure('INVALID_SAVED_VIEW_INPUT', message); }
function isPlainObject(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value))
        return false;
    const prototype = Object.getPrototypeOf(value);
    return prototype === Object.prototype || prototype === null;
}
function allowed(operation, granted) {
    return operation.permissionGroups.every(group => group.some(permission => granted?.includes(permission)));
}
function validate(name, input) {
    if (!isPlainObject(input))
        fail('输入必须为JSON对象');
    const fields = name === 'create' ? ['resourceType', 'name', 'filters']
        : name === 'update' ? ['name', 'filters', 'expectedVersion']
            : name === 'archive' ? ['expectedVersion'] : [];
    if (fields.length === 0 || Object.keys(input).some(key => !fields.includes(key))
        || fields.some(key => !Object.hasOwn(input, key)))
        fail('字段缺失或包含未支持字段');
    if (name === 'create' && (typeof input.resourceType !== 'string' || !Object.hasOwn(permissions, input.resourceType)))
        fail('队列类型无效');
    if (name !== 'archive') {
        if (typeof input.name !== 'string' || !input.name.trim() || input.name.trim().length > 80 || /[\x00-\x1f\x7f-\x9f]/.test(input.name))
            fail('名称无效');
        if (!isPlainObject(input.filters) || Object.keys(input.filters).length > 8)
            fail('筛选项必须为对象且不超过8项');
        for (const [key, value] of Object.entries(input.filters)) {
            if (!key.trim() || key.length > 32 || typeof value !== 'string' || !value.trim() || value.length > 120 || /[\x00-\x1f\x7f-\x9f]/.test(value))
                fail('筛选字段或值无效');
        }
    }
    if (name !== 'create' && (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1))
        fail('必须提供正整数expectedVersion');
    return input;
}
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'list') {
        const resource = rest[0];
        if (rest.length !== 1 || typeof resource !== 'string' || !Object.hasOwn(permissions, resource))
            fail('用法：saved-views list <队列类型>');
        const permission = permissions[resource];
        if (!identity.permissions.includes(permission))
            throw auth.failure('FORBIDDEN', '缺少对应队列读取权限');
        return client.request('crm/saved-views?' + new URLSearchParams({ resourceType: resource }).toString());
    }
    const operation = operations.find(item => item.name === name);
    if (!operation)
        fail('用法：saved-views list|create|update|archive');
    if (!allowed(operation, identity.permissions))
        throw auth.failure('FORBIDDEN', '缺少队列读取权限');
    let tail = rest;
    let target = '';
    if (name !== 'create') {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(target))
            fail('视图编号无效');
    }
    const flags = {};
    for (let index = 0; index < tail.length; index += 2) {
        const flag = tail[index];
        const value = tail[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(flags, flag))
            fail('需要--input和--key，不能重复');
        flags[flag] = value;
    }
    const inputFile = flags['--input'];
    const idempotencyKey = flags['--key'];
    if (!inputFile || !idempotencyKey)
        fail('需要--input和--key');
    const key = orders.parseKey(idempotencyKey);
    const input = validate(name, business.read(inputFile));
    if (name === 'create') {
        const resource = input.resourceType;
        if (typeof resource !== 'string' || !Object.hasOwn(permissions, resource)
            || !identity.permissions.includes(permissions[resource]))
            throw auth.failure('FORBIDDEN', '缺少对应队列读取权限');
    }
    // The server resolves the existing view and rechecks ownership, queue permission and version.
    const endpoint = operation.endpoint.replace('{viewId}', encodeURIComponent(target));
    return client.request(endpoint, { method: operation.method, idempotencyKey: key, body: input });
}
module.exports = { operations, allowed, validate, run };
