'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [{ id: 'settings.update', name: 'update', method: 'PUT', endpoint: 'settings', permissions: ['settings.update'], scopes: ['all'], fields: ['section', 'values'], required: ['section', 'values'] }];
const fields = {
    organization: { name: 'text', address: 'text', phone: 'text', contact: 'text' },
    attendance: { presentConsume: 'bool', absentConsume: 'bool', leaveConsume: 'bool', allowMakeup: 'bool' },
    move: { sameGrade: 'true', sameSubject: 'true', sameCourse: 'bool', maxDays: 'integer', requireReason: 'true' },
    transfer: { sameGrade: 'true', sameSubject: 'true', requireReason: 'true' },
    enrollment: { allowOverCapacity: 'false', requirePhone: 'true' },
    receipts: { accountName: 'text', bankName: 'text', accountNumber: 'text', receiptNote: 'text' }
};
const fail = (message) => { throw authClient.failure('INVALID_SETTINGS_INPUT', message); };
function plain(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
        && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0;
}
function validate(value) {
    if (!plain(value) || Object.keys(value).some(key => !['section', 'values'].includes(key))
        || typeof value.section !== 'string' || !Object.hasOwn(fields, value.section) || !plain(value.values) || Object.keys(value.values).length === 0)
        return fail('设置分类或字段无效');
    const sectionFields = fields[value.section];
    const values = {};
    for (const [key, fieldValue] of Object.entries(value.values)) {
        if (!Object.hasOwn(sectionFields, key))
            return fail('未知设置字段');
        const kind = sectionFields[key];
        if (kind === 'text') {
            if (typeof fieldValue !== 'string' || fieldValue.trim().length > 500)
                return fail('文本设置无效');
        }
        else if (kind === 'integer') {
            if (!Number.isInteger(fieldValue) || fieldValue < 1 || fieldValue > 365)
                return fail('设置数字须为1至365的整数');
        }
        else if (typeof fieldValue !== 'boolean' || kind !== 'bool' && fieldValue !== (kind === 'true'))
            return fail('安全规则不允许该值');
        values[key] = fieldValue;
    }
    return { section: value.section, values };
}
function authorize(identity) {
    if (!identity.permissions.includes('settings.update') || identity.scope !== 'all')
        throw authClient.failure('FORBIDDEN', '系统设置修改需要全局权限');
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
        if (!identity.permissions.some(permission => ['settings.read', 'settings.update'].includes(permission)))
            throw authClient.failure('FORBIDDEN', '没有读取系统设置权限');
        if (rest.length)
            return fail('get不接受参数');
        return client.request('settings');
    }
    if (name !== 'update')
        return fail('用法：settings get|update --input <JSON> --key <幂等键>');
    authorize(identity);
    const inputFlags = parseFlags(rest);
    if (!inputFlags['--input'] || !inputFlags['--key'])
        return fail('必须提供--input和--key');
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(inputFlags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '幂等键格式无效');
    const body = validate(businessClient.read(inputFlags['--input']));
    return client.request('settings', { method: 'PUT', idempotencyKey: inputFlags['--key'], body });
}
module.exports = { operations, validate, run };
