'use strict';
const crypto = require('node:crypto');
const auth = require('./auth-client.cjs');
const legacy = require('./annotations.cjs');
const business = require('./business-client.cjs');
const adminInterfaces = [
    { id: 'java.ui-annotations.list', version: 'v2', method: 'GET', path: '/v2/admin/ui-annotations', label: 'Java 待修改清单', authorization: 'system.access.manage and global scope', readOnly: true, cli: 'annotations list' },
    { id: 'java.ui-annotations.create', version: 'v2', method: 'POST', path: '/v2/admin/ui-annotations', label: 'Java 创建界面修改建议', authorization: 'system.access.manage and global scope', audit: 'ui.annotation.create', idempotency: 'required', cli: 'annotations create --input <JSON>' },
    { id: 'java.ui-annotations.update', version: 'v2', method: 'PATCH', path: '/v2/admin/ui-annotations/{id}', label: 'Java 更新建议状态', authorization: 'system.access.manage and global scope', audit: 'ui.annotation.update', idempotency: 'required', requiresExpectedVersion: true, cli: 'annotations resolve|reopen <UUID>' },
];
function fail(code, message) { throw auth.failure(code, message); }
function administrator(identity) {
    if (!identity.permissions.includes('system.access.manage') || identity.scope !== 'all')
        fail('FORBIDDEN', '仅具有全局 system.access.manage 权限的 Java 管理员可管理待修改建议');
}
async function list(status, client, unwrap) {
    const results = [];
    let page = 1;
    let total = Number.POSITIVE_INFINITY;
    while (results.length < total) {
        const data = unwrap(await client.request('admin/ui-annotations?status=' + status + '&page=' + page + '&size=100'));
        if (!Array.isArray(data.annotations) || !Number.isSafeInteger(data.total) || data.page !== page || data.size !== 100)
            fail('INVALID_RESPONSE', 'Java 标注清单分页响应无效');
        results.push(...data.annotations);
        total = data.total;
        if (results.length >= total)
            break;
        if (data.annotations.length === 0 || ++page > 100000)
            fail('INVALID_RESPONSE', 'Java 标注清单分页不完整');
    }
    return results;
}
async function run(command, args, client, identity, options) {
    administrator(identity);
    if (command === 'list' && args.length === 0) {
        const unwrapPage = (response) => {
            const value = options.unwrap(response);
            if (value === null || typeof value !== 'object' || Array.isArray(value))
                fail('INVALID_RESPONSE', 'Java 标注清单分页响应无效');
            return value;
        };
        const [open, resolved] = await Promise.all([
            list('open', client, unwrapPage),
            list('resolved', client, unwrapPage),
        ]);
        return { annotations: [...open, ...resolved], total: open.length + resolved.length };
    }
    if (command === 'create' && args.length === 2 && args[0] === '--input' && args[1]) {
        const payload = legacy.validate(business.read(args[1]));
        return options.unwrap(await client.request('admin/ui-annotations', { method: 'POST', idempotencyKey: crypto.randomUUID(), body: payload }));
    }
    const [id, ...rest] = args;
    if ((command === 'resolve' || command === 'reopen') && rest.length === (command === 'resolve' ? 1 : 0)
        && (!rest.length || rest[0] === '--verified') && /^[a-f0-9-]{36}$/i.test(id || '')) {
        if (command === 'resolve' && rest[0] !== '--verified')
            fail('INVALID_ARGUMENT', '完成前必须确认已实施并验证：使用 --verified');
        const status = command === 'resolve' ? 'open' : 'resolved';
        const unwrapPage = (response) => {
            const value = options.unwrap(response);
            if (value === null || typeof value !== 'object' || Array.isArray(value))
                fail('INVALID_RESPONSE', 'Java 标注清单分页响应无效');
            return value;
        };
        const found = (await list(status, client, unwrapPage)).find(row => row.id === id);
        if (!found)
            fail('NOT_FOUND', 'Java ' + (status === 'open' ? '待修改' : '已完成') + '清单中没有该编号');
        const body = { status: command === 'resolve' ? 'resolved' : 'open', expectedVersion: found.version, verified: command === 'resolve' };
        return options.unwrap(await client.request('admin/ui-annotations/' + encodeURIComponent(id || ''), { method: 'PATCH', idempotencyKey: crypto.randomUUID(), body }));
    }
    fail('INVALID_ARGUMENT', '用法：annotations list | annotations create --input <JSON> | annotations resolve <UUID> --verified | annotations reopen <UUID>');
}
module.exports = { run, list, adminInterfaces };
