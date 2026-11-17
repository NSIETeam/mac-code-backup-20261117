'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const fields = ['name', 'address', 'phone', 'rooms', 'active', 'subjects', 'grades'];
const operations = [
    { id: 'campus.create', name: 'create', method: 'POST', endpoint: 'settings/campuses', permissions: ['settings.update'], scopes: ['all'], fields, required: ['name'] },
    { id: 'campus.update', name: 'update', method: 'PUT', endpoint: 'settings/campuses/{id}', permissions: ['settings.update'], scopes: ['all', 'campus'], fields: ['patch', 'expectedVersion'], required: ['patch', 'expectedVersion'] }
];
const fail = (message) => { throw authClient.failure('INVALID_CAMPUS_INPUT', message); };
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function exact(value, keys) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)
        || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length > 0)
        return fail('输入含无效字段');
    const record = value;
    if (Object.keys(record).some(key => !keys.includes(key)))
        return fail('输入含无效字段');
    return record;
}
function integer(value, min, max) {
    if (!Number.isSafeInteger(value) || value < min || value > max)
        fail('整数超出范围');
}
function validate(name, value) {
    if (name === 'update') {
        const input = exact(value, ['patch', 'expectedVersion']);
        integer(input.expectedVersion, 1, Number.MAX_SAFE_INTEGER);
        const patch = validate('patch', input.patch);
        return { patch, expectedVersion: input.expectedVersion };
    }
    if (name !== 'create' && name !== 'patch')
        return fail('未知校区操作');
    const input = exact(value, fields);
    if (name === 'create' && (typeof input.name !== 'string' || !input.name.trim()))
        return fail('必须填写校区名称');
    for (const [key, max] of [['name', 100], ['address', 500], ['phone', 50]]) {
        const item = input[key];
        if (item != null && (typeof item !== 'string' || item.length > max || key === 'name' && !item.trim()))
            return fail(`${key}内容无效`);
    }
    if (input.active != null && typeof input.active !== 'boolean')
        return fail('active须为布尔值');
    for (const [key, max] of [['subjects', 100], ['grades', 12]]) {
        const list = input[key];
        if (list != null) {
            if (!Array.isArray(list) || list.length > max || list.some(item => typeof item !== 'string' || !item.trim() || item.length > 100)
                || new Set(list).size !== list.length)
                return fail(`${key}列表无效`);
        }
    }
    if (input.rooms != null) {
        if (!Array.isArray(input.rooms) || input.rooms.length > 200)
            return fail('教室列表无效');
        for (const raw of input.rooms) {
            const room = exact(raw, ['id', 'name', 'capacity', 'virtualSeats']);
            integer(room.capacity, 1, 10000);
            if (room.virtualSeats != null)
                integer(room.virtualSeats, 0, 10000);
            if (room.id != null && !validId(room.id))
                return fail('教室编号无效');
            if (room.name != null && (typeof room.name !== 'string' || room.name.length > 100))
                return fail('教室名称无效');
        }
    }
    return input;
}
function authorize(identity, create = false) {
    if (!identity.permissions.includes('settings.update') || !(create ? ['all'] : ['all', 'campus']).includes(identity.scope))
        throw authClient.failure('FORBIDDEN', '校区设置权限或数据范围不足');
}
function parseFlags(args) {
    const result = {};
    for (let index = 0; index < args.length; index += 2) {
        const flag = args[index], value = args[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(result, flag))
            return fail('参数缺失、重复或不支持');
        result[flag] = value;
    }
    return result;
}
async function run(args, client, identity) {
    const [name, ...rest] = args;
    authorize(identity, name === 'create');
    if (name === 'detail') {
        if (rest.length !== 1 || !validId(rest[0]))
            return fail('请提供校区编号');
        return client.request(`settings/campuses/${encodeURIComponent(rest[0])}`);
    }
    if (name === 'list') {
        const values = { page: 1, size: 50 };
        const seen = new Set();
        for (let index = 0; index < rest.length; index += 2) {
            const flag = rest[index], value = rest[index + 1];
            if (!['--page', '--size'].includes(flag) || seen.has(flag) || !/^\d+$/.test(value || ''))
                return fail('分页参数无效');
            seen.add(flag);
            if (flag === '--page')
                values.page = Number(value);
            else
                values.size = Number(value);
        }
        integer(values.page, 1, 100000);
        integer(values.size, 1, 100);
        return client.request(`settings/campuses?page=${values.page}&size=${values.size}`);
    }
    const operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('用法：campuses list|detail|create|update');
    let target = '', tail = rest;
    if (name === 'update') {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('校区编号无效');
    }
    const flags = parseFlags(tail);
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供--input和--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(flags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(name, businessClient.read(flags['--input']));
    return client.request(operation.endpoint.replace('{id}', encodeURIComponent(target)), { method: operation.method, idempotencyKey: flags['--key'], body });
}
module.exports = { operations, validate, run };
