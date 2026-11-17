'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [
    { name: 'update', id: 'lead.policy.update', method: 'PUT', endpoint: 'crm/lead-policy', permissions: ['crm.lead.assign', 'crm.lead.policy.manage'], scopes: ['all'] },
    { name: 'apply', id: 'lead.policy.run', method: 'POST', endpoint: 'crm/lead-policy/apply', permissions: ['crm.lead.assign', 'crm.customer.read', 'crm.lead.policy.manage'], scopes: ['all', 'campus'] }
];
const fail = (message) => { throw authClient.failure('INVALID_LEAD_POLICY_INPUT', message); };
const positive = (value, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(value) && value >= 1 && value <= max;
function object(value, fields, required = fields) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)
        || ![Object.prototype, null].includes(Object.getPrototypeOf(value))
        || Object.getOwnPropertySymbols(value).length > 0)
        return fail('字段缺失或包含未支持字段');
    const input = value;
    if (Object.keys(input).some(key => !fields.includes(key)) || required.some(key => !Object.hasOwn(input, key)))
        fail('字段缺失或包含未支持字段');
    return input;
}
function validId(value) { return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value); }
function validate(name, value) {
    if (name === 'update') {
        const input = object(value, ['reserveDays', 'reclaimDays', 'firstFollowHours', 'decisionHours', 'expectedVersion'], ['reserveDays', 'reclaimDays', 'firstFollowHours', 'expectedVersion']);
        if (!positive(input.reserveDays, 3650) || !positive(input.reclaimDays, 3650)
            || !positive(input.firstFollowHours, 8760) || !positive(input.expectedVersion)
            || input.decisionHours !== undefined && !positive(input.decisionHours, 720))
            fail('策略阈值或版本无效');
        return input;
    }
    if (name === 'apply') {
        const input = object(value, ['policyVersion', 'leads']);
        if (!positive(input.policyVersion) || !Array.isArray(input.leads) || input.leads.length < 1 || input.leads.length > 100)
            fail('请选择1至100条候选并提供策略版本');
        const leads = input.leads;
        const seen = new Set();
        for (const raw of leads) {
            const item = object(raw, ['leadId', 'expectedVersion', 'kind'], ['leadId', 'expectedVersion']);
            const leadId = item.leadId;
            if (!validId(leadId) || !positive(item.expectedVersion) || seen.has(leadId))
                return fail('线索编号、版本无效或选择重复');
            if (item.kind !== undefined && item.kind !== null && item.kind !== 'warning' && item.kind !== 'recycle')
                fail('候选类型无效');
            seen.add(leadId);
        }
        return input;
    }
    return fail('不支持的策略操作');
}
function allowed(operation, permissions) { return operation.permissions.every(permission => permissions.includes(permission)); }
function authorize(identity, permissions, scopes) {
    if (!permissions.every(permission => identity.permissions.includes(permission)) || scopes && !scopes.includes(identity.scope)) {
        throw authClient.failure('FORBIDDEN', '缺少线索策略权限或不在授权管理范围');
    }
}
function flags(args, allowedFlags) {
    const result = {};
    for (let i = 0; i < args.length; i += 2) {
        const key = args[i], value = args[i + 1];
        if (!allowedFlags.includes(key) || !value || Object.hasOwn(result, key))
            fail('参数缺失、重复或不受支持');
        result[key] = value;
    }
    return result;
}
async function run(args, client, identity) {
    const [name, ...rest] = args;
    if (name === 'get') {
        authorize(identity, ['crm.lead.read']);
        if (rest.length)
            fail('get不接受参数');
        return client.request('crm/lead-policy');
    }
    if (name === 'preview') {
        authorize(identity, ['crm.lead.assign', 'crm.customer.read'], ['all', 'campus']);
        const input = flags(rest, ['--page', '--size']);
        const page = input['--page'] === undefined ? 1 : Number(input['--page']);
        const size = input['--size'] === undefined ? 50 : Number(input['--size']);
        if (!positive(page, 100000) || !positive(size, 100))
            fail('分页范围无效');
        return client.request(`crm/lead-policy/preview?page=${page}&size=${size}`);
    }
    const operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('用法：lead-policy get|preview|update|apply');
    authorize(identity, operation.permissions, operation.scopes);
    const inputFlags = flags(rest, ['--input', '--key']);
    if (!inputFlags['--input'] || !inputFlags['--key'])
        fail('必须提供--input与--key');
    const input = validate(name, businessClient.read(inputFlags['--input']));
    if (!/^[A-Za-z0-9._:-]{16,128}$/.test(inputFlags['--key']))
        throw authClient.failure('INVALID_ARGUMENT', '必须提供有效 --key 幂等键');
    return client.request(operation.endpoint, { method: operation.method, idempotencyKey: inputFlags['--key'], body: input });
}
module.exports = { operations, validate, allowed, run };
