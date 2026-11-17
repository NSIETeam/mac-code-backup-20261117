'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth, businessClient = business;
const readPermissions = ['commerce.read', 'crm.order.read', 'order.read', 'order.confirmReceipt', 'enrollment.create', 'enrollment.manage', 'crm.receipt.record'];
const operations = [
    { id: 'commerce.checkout.list', name: 'list', label: '查询商城报名申请', method: 'GET', endpoint: 'commerce/checkouts', permissions: ['commerce.read'], permissionGroups: [readPermissions], paged: true },
    { id: 'commerce.checkout.detail', name: 'detail', label: '查看商城申请详情', method: 'GET', endpoint: 'commerce/checkouts/{id}', permissions: ['commerce.read'], permissionGroups: [readPermissions], pathParameter: 'id' },
    { id: 'commerce.checkout.intake', name: 'intake', label: '读取待核对申请身份', method: 'GET', endpoint: 'commerce/checkouts/{id}/intake', permissions: ['enrollment.create'], permissionGroups: [['enrollment.create', 'enrollment.manage']], pathParameter: 'id' },
    { id: 'commerce.checkout.create', name: 'create', label: '创建商城结账申请', method: 'POST', endpoint: 'commerce/checkouts', permissions: ['enrollment.create'], permissionGroups: [['enrollment.create', 'enrollment.manage']], required: ['type', 'buyers', 'items'], fields: ['type', 'promotionId', 'buyers', 'items', 'expectedQuoteHash'], input: true },
    { id: 'commerce.checkout.accept', name: 'accept', label: '核对商城申请身份', method: 'POST', endpoint: 'commerce/checkouts/{id}/accept', permissions: ['enrollment.create'], permissionGroups: [['enrollment.create', 'enrollment.manage']], required: ['expectedVersion'], fields: ['students', 'expectedVersion'], input: true, pathParameter: 'id' },
    { id: 'commerce.checkout.receipt', name: 'confirm-receipt', label: '核对商城线下收款', method: 'POST', endpoint: 'commerce/checkouts/{id}/receipt', permissions: ['crm.receipt.record'], permissionAlternatives: [['crm.receipt.record'], ['order.confirmReceipt']], required: ['amount', 'method', 'reference', 'expectedVersion'], fields: ['amount', 'method', 'reference', 'expectedVersion'], input: true, pathParameter: 'id' },
    { id: 'commerce.checkout.paymentLink', name: 'payment-link', label: '获取商城在线收款入口', method: 'POST', endpoint: 'commerce/checkouts/{id}/payment-link', permissions: ['order.paymentLink'], fields: [], pathParameter: 'id' }
];
const fail = (code, message) => { throw authClient.failure(code, message); };
function plain(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function object(value, allowed, required, label) { if (!plain(value))
    return fail('INVALID_CHECKOUT_INPUT', `${label} 必须是 JSON 对象`); for (const key of Object.keys(value))
    if (!allowed.includes(key))
        fail('INVALID_CHECKOUT_INPUT', `${label} 不支持字段 ${key}`); for (const key of required)
    if (!Object.hasOwn(value, key))
        fail('INVALID_CHECKOUT_INPUT', `${label} 缺少字段 ${key}`); return value; }
function money(value) { return (typeof value === 'number' || typeof value === 'string') && /^(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/.test(String(value)) && Number.isFinite(Number(value)) && Number(value) >= 0; }
function validate(name, value) {
    if (name === 'payment-link')
        return value;
    const operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('INVALID_COMMAND', '不支持的商城结账命令');
    const input = object(value, operation.fields || [], operation.required || [], '请求');
    if (name === 'create') {
        if (!['standard', 'group', 'joint'].includes(input.type))
            return fail('INVALID_CHECKOUT_INPUT', 'type 无效');
        if (!Array.isArray(input.buyers) || input.buyers.length < 1 || input.buyers.length > 50)
            return fail('INVALID_CHECKOUT_INPUT', 'buyers 数量无效');
        for (const buyerValue of input.buyers) {
            const buyer = object(buyerValue, ['studentId', 'name', 'phone', 'grade'], [], 'buyer');
            if (buyer.studentId != null && !validId(buyer.studentId))
                return fail('INVALID_CHECKOUT_INPUT', 'studentId 无效');
            if (buyer.name != null && (typeof buyer.name !== 'string' || buyer.name.length > 80))
                return fail('INVALID_CHECKOUT_INPUT', 'name 无效');
            if (buyer.phone != null && (typeof buyer.phone !== 'string' || buyer.phone.length > 30))
                return fail('INVALID_CHECKOUT_INPUT', 'phone 无效');
        }
        if (!Array.isArray(input.items) || input.items.length < 1 || input.items.length > 100)
            return fail('INVALID_CHECKOUT_INPUT', 'items 数量无效');
        for (const itemValue of input.items) {
            const item = object(itemValue, ['buyerIndex', 'classId', 'couponId'], ['buyerIndex', 'classId'], 'item');
            if (!Number.isInteger(item.buyerIndex) || item.buyerIndex < 0 || item.buyerIndex >= input.buyers.length || !validId(item.classId) || item.couponId != null && !validId(item.couponId))
                return fail('INVALID_CHECKOUT_INPUT', 'item 无效');
        }
        if (input.expectedQuoteHash != null && (typeof input.expectedQuoteHash !== 'string' || !/^[a-f0-9]{64}$/.test(input.expectedQuoteHash)))
            return fail('INVALID_CHECKOUT_INPUT', 'expectedQuoteHash 无效');
    }
    if (name === 'accept') {
        if (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1)
            return fail('INVALID_CHECKOUT_INPUT', 'expectedVersion 须为正整数');
        if (input.students !== undefined) {
            if (!Array.isArray(input.students) || input.students.length > 50)
                return fail('INVALID_CHECKOUT_INPUT', 'students 无效');
            for (const studentValue of input.students) {
                const student = object(studentValue, ['buyerIndex', 'studentId'], ['buyerIndex', 'studentId'], 'student');
                if (!Number.isInteger(student.buyerIndex) || student.buyerIndex < 0 || student.buyerIndex > 49 || !validId(student.studentId))
                    return fail('INVALID_CHECKOUT_INPUT', 'student 无效');
            }
        }
    }
    if (name === 'confirm-receipt' && (!money(input.amount) || !['cash', 'bank'].includes(input.method) || typeof input.reference !== 'string' || !input.reference.trim() || input.reference.length > 150 || !Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1))
        return fail('INVALID_CHECKOUT_INPUT', '收款核对参数无效');
    return input;
}
function parseKey(value) { if (!/^[A-Za-z0-9._:-]{16,128}$/.test(value))
    return fail('INVALID_ARGUMENT', '幂等键无效'); return value; }
function flags(args, needsInput) { const result = {}; for (let index = 0; index < args.length; index += 2) {
    const flag = args[index], value = args[index + 1];
    if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(result, flag))
        fail('INVALID_ARGUMENT', '参数无效或重复');
    result[flag] = value;
} if (needsInput && !result['--input'])
    fail('INVALID_ARGUMENT', '必须提供 --input <JSON文件>'); if (needsInput && !result['--key'])
    fail('INVALID_ARGUMENT', '必须提供 --key <幂等键>'); if (!needsInput && Object.keys(result).length)
    fail('INVALID_ARGUMENT', 'payment-link 不接受输入或幂等键'); return result; }
async function run(args, client, identity) {
    const [name, ...rest] = args, operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('INVALID_ARGUMENT', '用法：checkouts list|detail|intake|create|accept|confirm-receipt|payment-link [编号]');
    if (!allowed(operation, identity.permissions))
        fail('FORBIDDEN', `当前员工缺少所需权限 ${operation.permissions.join(' / ')}`);
    let target = '', tail = rest;
    if (operation.pathParameter) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('INVALID_ARGUMENT', `请提供有效 ${operation.pathParameter}`);
    }
    if (operation.method === 'GET') {
        const query = {};
        for (let index = 0; index < tail.length; index += 2) {
            const flag = tail[index], value = tail[index + 1];
            if (!operation.paged || !['--page', '--size'].includes(flag) || !value || Object.hasOwn(query, flag) || !(/^[1-9][0-9]*$/).test(value))
                fail('INVALID_ARGUMENT', '分页参数无效或重复');
            query[flag] = value;
        }
        let endpoint = operation.endpoint.replace('{id}', encodeURIComponent(target));
        if (operation.paged) {
            const page = Number(query['--page'] || 1), size = Number(query['--size'] || 50);
            if (!Number.isSafeInteger(page) || page > 100000 || !Number.isSafeInteger(size) || size > 100)
                return fail('INVALID_ARGUMENT', '分页超出范围');
            endpoint += '?' + new URLSearchParams({ page: String(page), size: String(size) });
        }
        return client.request(endpoint, { method: 'GET' });
    }
    const parsed = flags(tail, operation.input === true), body = operation.input ? validate(name || '', businessClient.read(parsed['--input'])) : undefined, key = operation.input ? parseKey(parsed['--key']) : undefined;
    return client.request(operation.endpoint.replace('{id}', encodeURIComponent(target)), { method: operation.method, ...(key ? { idempotencyKey: key } : {}), ...(body ? { body } : {}) });
}
function allowed(operation, permissions) { if (operation.permissionAlternatives)
    return operation.permissionAlternatives.some(alternative => alternative.every(permission => permissions.includes(permission))); return (operation.permissionGroups || operation.permissions.map(permission => [permission])).every(group => group.some(permission => permissions.includes(permission))); }
module.exports = { operations, validate, allowed, run };
