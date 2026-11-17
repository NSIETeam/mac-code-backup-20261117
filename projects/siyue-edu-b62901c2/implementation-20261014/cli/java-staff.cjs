'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const fields = ['phone', 'employmentStart', 'employmentEnd', 'title', 'note', 'campusId'];
const operations = [{ id: 'staff.profile.update', name: 'profile', method: 'PUT', endpoint: 'system/staff/{employeeId}/profile', permissions: ['account.update'], scopes: ['all'], fields }];
const fail = (message) => { throw authClient.failure('INVALID_STAFF_PROFILE_INPUT', message); };
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function exact(value, allowed) {
    if (value === null || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value))
        || Object.getOwnPropertySymbols(value).length > 0)
        return fail('输入缺失或包含不支持字段');
    const input = value;
    if (Object.keys(input).some(key => !allowed.includes(key)))
        return fail('输入缺失或包含不支持字段');
    return input;
}
function validate(value) {
    const input = exact(value, ['patch', 'expectedVersion']);
    const patch = exact(input.patch, fields);
    const safePatch = {};
    if (!Object.keys(patch).length)
        return fail('patch至少需要一个字段');
    if (input.expectedVersion !== undefined && (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 0))
        return fail('expectedVersion须为非负整数');
    for (const [key, item] of Object.entries(patch)) {
        if (item === null) {
            if (['employmentStart', 'employmentEnd', 'title', 'note', 'campusId'].includes(key)) {
                safePatch[key] = null;
                continue;
            }
            return fail(`${key}不能为null`);
        }
        if (['phone', 'title', 'note', 'campusId'].includes(key)) {
            const max = { phone: 50, title: 100, note: 5000, campusId: 120 }[key];
            if (typeof item !== 'string' || item.length > max || key === 'campusId' && item !== '' && !validId(item))
                return fail(`${key}内容无效`);
            if (key === 'phone' && (!item || !/^(?:(?:\+86|0086))?1[3-9]\d{9}$/.test(item.replace(/[\s()-]/g, ''))))
                return fail('phone格式无效');
        }
        else if (key === 'employmentStart' || key === 'employmentEnd') {
            if (item !== '' && (typeof item !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(item)
                || Number.isNaN(Date.parse(`${item}T00:00:00Z`)) || new Date(`${item}T00:00:00Z`).toISOString().slice(0, 10) !== item))
                return fail(`${key}须为有效日期`);
        }
        if (item !== null && typeof item !== 'string')
            return fail(`${key}内容无效`);
        safePatch[key] = item;
    }
    return { patch: safePatch, ...(input.expectedVersion === undefined ? {} : { expectedVersion: input.expectedVersion }) };
}
function authorize(identity) {
    if (identity.scope !== 'all' || !identity.permissions.includes('account.update'))
        throw authClient.failure('FORBIDDEN', '职工档案操作需要account.update和全部数据范围');
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
    authorize(identity);
    const [sub, employeeId, ...rest] = args;
    if (sub !== 'profile' || !validId(employeeId))
        return fail('用法：staff profile <职工编号> [--input <JSON文件> --key <幂等键>]');
    const endpoint = `system/staff/${encodeURIComponent(employeeId)}/profile`;
    if (!rest.length)
        return client.request(endpoint);
    const flags = parseFlags(rest);
    if (!flags['--input'] || !flags['--key'])
        return fail('更新档案必须提供--input与--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(flags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(businessClient.read(flags['--input']));
    return client.request(endpoint, { method: 'PUT', idempotencyKey: flags['--key'], body });
}
module.exports = { operations, validate, run, authorize };
