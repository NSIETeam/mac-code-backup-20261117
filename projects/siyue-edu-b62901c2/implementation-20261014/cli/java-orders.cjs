'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth;
const businessClient = business;
const operations = [
    { name: 'create', id: 'crm.order.create', method: 'POST', endpoint: 'crm/orders', permissions: ['crm.order.write', 'crm.opportunity.read'], required: ['opportunityId', 'totalAmount', 'productSnapshot', 'expectedOpportunityVersion'], fields: ['opportunityId', 'totalAmount', 'productSnapshot', 'expectedOpportunityVersion', 'classBinding'] },
    { name: 'receive', id: 'crm.receipt.record', method: 'POST', endpoint: 'crm/orders/{orderId}/receipts', permissions: ['crm.receipt.record', 'crm.order.read'], permissionAlternatives: [['crm.receipt.record', 'crm.order.read'], ['order.confirmReceipt']], required: ['amount', 'method', 'receivedAt', 'expectedOrderVersion'], fields: ['amount', 'method', 'reference', 'receivedAt', 'expectedOrderVersion'], pathParameter: 'orderId' },
    { name: 'refund-request', id: 'crm.refund.request', method: 'POST', endpoint: 'crm/orders/{orderId}/refund-requests', permissions: ['crm.refund.request', 'crm.order.read'], required: ['amount', 'reason'], fields: ['amount', 'reason', 'evidence'], pathParameter: 'orderId' },
    { name: 'refund-review', id: 'crm.refund.review', method: 'PUT', endpoint: 'crm/refund-requests/{requestId}/review', permissions: ['crm.refund.approve', 'crm.order.read'], permissionAlternatives: [['crm.refund.approve', 'crm.order.read'], ['refund.approve']], required: ['decision', 'note', 'expectedVersion'], fields: ['decision', 'note', 'expectedVersion'], pathParameter: 'requestId' },
    { name: 'refund-record', id: 'crm.refund.record', method: 'POST', endpoint: 'crm/refund-requests/{requestId}/refunds', permissions: ['crm.refund.record', 'crm.order.read'], permissionAlternatives: [['crm.refund.record', 'crm.order.read'], ['refund.submit']], required: ['method', 'refundedAt', 'expectedVersion'], fields: ['method', 'reference', 'refundedAt', 'expectedVersion'], pathParameter: 'requestId' }
];
const fail = (code, message) => { throw authClient.failure(code, message); };
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) {
    const m = typeof value === 'string' && /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?(Z|([+-])(\d\d):(\d\d))$/.exec(value);
    if (!m)
        return false;
    const day = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])), oh = +(m[9] || 0), om = +(m[10] || 0);
    return day.toISOString().slice(0, 10) === value.slice(0, 10) && +m[4] < 24 && +m[5] < 60 && +m[6] < 60 && oh <= 18 && om < 60 && (oh !== 18 || om === 0) && Number.isFinite(Date.parse(value));
}
function money(value) { return (typeof value === 'number' || typeof value === 'string') && /^(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/.test(String(value)) && Number(value) > 0; }
function validate(name, value) {
    const op = operations.find(item => item.name === name);
    if (!op)
        return fail('INVALID_ORDER_INPUT', '输入必须是 JSON 对象');
    if (value === null || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length > 0)
        fail('INVALID_ORDER_INPUT', '输入必须是 JSON 对象');
    const input = value;
    for (const key of Object.keys(input))
        if (!op.fields.includes(key))
            fail('INVALID_ORDER_INPUT', `不支持字段 ${key}`);
    for (const key of op.required)
        if (!Object.hasOwn(input, key))
            fail('INVALID_ORDER_INPUT', `缺少必填字段 ${key}`);
    if ((Object.hasOwn(input, 'amount') || Object.hasOwn(input, 'totalAmount')) && !money(input.amount ?? input.totalAmount))
        fail('INVALID_ORDER_INPUT', '金额必须大于0且精确到分');
    for (const key of ['opportunityId', 'productSnapshot', 'reason', 'note', 'method', 'decision']) {
        const item = input[key];
        if (item !== undefined && (typeof item !== 'string' || !item.trim() || item.length > (key === 'productSnapshot' ? 300 : key === 'reason' || key === 'note' ? 2000 : 100)))
            fail('INVALID_ORDER_INPUT', `${key} 无效`);
    }
    for (const key of ['expectedOpportunityVersion', 'expectedOrderVersion', 'expectedVersion']) {
        const item = input[key];
        if (item !== undefined && (!Number.isSafeInteger(item) || item < 1))
            fail('INVALID_ORDER_INPUT', `${key} 须为正整数`);
    }
    if (input.opportunityId !== undefined && !validId(input.opportunityId))
        fail('INVALID_ORDER_INPUT', 'opportunityId 无效');
    if (input.method !== undefined && !['bank_transfer', 'pos', 'cash', 'online', 'other'].includes(input.method))
        fail('INVALID_ORDER_INPUT', 'method 无效');
    if (input.decision !== undefined && !['approve', 'reject'].includes(input.decision))
        fail('INVALID_ORDER_INPUT', 'decision 须为 approve 或 reject');
    if (input.receivedAt !== undefined && (!instant(input.receivedAt) || Date.parse(input.receivedAt) > Date.now()))
        fail('INVALID_ORDER_INPUT', 'receivedAt 须为有效且不晚于当前时间的带时区时刻');
    if (input.refundedAt !== undefined && (!instant(input.refundedAt) || Date.parse(input.refundedAt) > Date.now()))
        fail('INVALID_ORDER_INPUT', 'refundedAt 须为有效且不晚于当前时间的带时区时刻');
    if (input.classBinding !== undefined) {
        const b = input.classBinding;
        if (b === null || typeof b !== 'object' || Array.isArray(b))
            fail('INVALID_ORDER_INPUT', 'classBinding 须包含 classId 和正整数 expectedVersion');
        const binding = b;
        if (Object.keys(binding).some(key => !['classId', 'expectedVersion'].includes(key)) || !validId(binding.classId) || !Number.isSafeInteger(binding.expectedVersion) || binding.expectedVersion < 1)
            fail('INVALID_ORDER_INPUT', 'classBinding 须包含 classId 和正整数 expectedVersion');
    }
    if (input.reference !== undefined && (typeof input.reference !== 'string' || input.reference.length > 1000) || input.evidence !== undefined && (typeof input.evidence !== 'string' || input.evidence.length > 2000))
        fail('INVALID_ORDER_INPUT', 'reference 或 evidence 超出长度限制');
    return input;
}
function parseKey(value) { if (typeof value !== 'string' || !/^[A-Za-z0-9._:-]{16,128}$/.test(value))
    return fail('INVALID_ARGUMENT', '必须提供有效 --key 幂等键（16–128 个安全字符）'); return value; }
async function run(args, client, identity) {
    const [name, ...rest] = args, op = operations.find(item => item.name === name);
    if (!op)
        return fail('INVALID_ARGUMENT', '用法：orders create|receive|refund-request|refund-review|refund-record [编号] --input <JSON> --key <幂等键>');
    if (!allowed(op, identity.permissions))
        fail('FORBIDDEN', `当前员工缺少所需权限 ${op.permissions.join(' + ')}`);
    let target = '', tail = rest;
    if (op.pathParameter) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            fail('INVALID_ARGUMENT', `请提供有效 ${op.pathParameter}`);
    }
    const flags = {};
    for (let index = 0; index < tail.length; index += 2) {
        const flag = tail[index], item = tail[index + 1];
        if (!['--input', '--key'].includes(flag) || !item || Object.hasOwn(flags, flag))
            return fail('INVALID_ARGUMENT', '命令需要 --input <JSON文件> 和 --key <幂等键>');
        flags[flag] = item;
    }
    if (!flags['--input'] || !flags['--key'])
        fail('INVALID_ARGUMENT', '命令需要 --input <JSON文件> 和 --key <幂等键>');
    const input = validate(name || '', businessClient.read(flags['--input'])), key = parseKey(flags['--key']);
    const endpoint = op.endpoint.replace(/\{[^}]+\}/, encodeURIComponent(target));
    return client.request(endpoint, { method: op.method, idempotencyKey: key, body: input });
}
function allowed(operation, permissions) { return operation.permissionAlternatives ? operation.permissionAlternatives.some(alternative => alternative.every(permission => permissions.includes(permission))) : operation.permissions.every(permission => permissions.includes(permission)); }
module.exports = { operations, validate, parseKey, allowed, run };
