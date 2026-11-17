'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [{ id: 'dictionary.update', name: 'update', method: 'PUT', endpoint: 'settings/dictionary', permissions: ['settings.update'], scopes: ['all'], fields: ['key', 'values'], required: ['key', 'values'] }];
const fail = (message) => { throw authClient.failure('INVALID_DICTIONARY_INPUT', message); };
function validate(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)
        || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length > 0)
        return fail('输入含无效字段');
    const input = value;
    if (Object.keys(input).some(key => !['key', 'values'].includes(key)))
        return fail('输入含无效字段');
    if (typeof input.key !== 'string' || !['subjects', 'grades', 'terms', 'levels', 'classTypes'].includes(input.key)
        || !Array.isArray(input.values) || input.values.length < 1 || input.values.length > 200)
        return fail('字典分类或项数无效');
    const values = [];
    for (const valueItem of input.values) {
        if (typeof valueItem !== 'string' || !valueItem.trim() || valueItem.trim().length > 100)
            return fail('字典项无效或重复');
        values.push(valueItem);
    }
    if (new Set(values.map(item => item.trim())).size !== values.length)
        return fail('字典项无效或重复');
    return { key: input.key, values };
}
function authorize(identity, write) {
    if (!identity.permissions.includes('settings.update') || write && identity.scope !== 'all')
        throw authClient.failure('FORBIDDEN', '教学字典权限或数据范围不足');
}
function parseFlags(args) {
    const result = {};
    for (let index = 0; index < args.length; index += 2) {
        const flag = args[index], value = args[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(result, flag))
            return fail('参数缺失或重复');
        result[flag] = value;
    }
    return result;
}
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'get') {
        authorize(identity, false);
        if (rest.length)
            return fail('get不接受参数');
        return client.request('settings/dictionary');
    }
    if (name !== 'update')
        return fail('用法：dictionary get|update --input <JSON> --key <幂等键>');
    authorize(identity, true);
    const flags = parseFlags(rest);
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供--input和--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(flags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(businessClient.read(flags['--input']));
    return client.request('settings/dictionary', { method: 'PUT', idempotencyKey: flags['--key'], body });
}
module.exports = { operations, validate, run };
