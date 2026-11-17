"use strict";
const contracts_cjs_1 = require("../contracts.cjs");
const fs = require("node:fs");
const path = require("node:path");
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const text = (title, maxLength, pattern) => ({ type: 'string', title, minLength: 1, maxLength, ...(pattern ? { pattern } : {}) });
const permissionCodes = { type: 'array', title: '权限编号', items: text('权限编号', 100), maxItems: 100, uniqueItems: true };
const deniedPermissionCodes = { type: 'array', title: '禁止权限编号', items: text('权限编号', 100), maxItems: 100, uniqueItems: true };
const campusIds = { type: 'array', title: '校区编号', items: text('校区编号', 120), maxItems: 100, uniqueItems: true };
const scope = { type: 'string', title: '数据范围', enum: ['all', 'campus', 'assigned', 'own'] };
const createInput = object({ principalId: text('外部身份编号', 120, '^[A-Za-z0-9_-]+$'), issuer: text('OIDC 身份提供方 HTTPS 地址', 1024), subject: text('OIDC subject', 512), scope, permissionCodes, deniedPermissionCodes, campusIds }, ['principalId', 'issuer', 'subject', 'scope', 'permissionCodes', 'deniedPermissionCodes', 'campusIds']);
const updateInput = object({ expectedVersion: { type: 'integer', minimum: 1, title: '当前授权版本' }, scope, active: { type: 'boolean', title: '账号启用状态' }, permissionCodes, deniedPermissionCodes, campusIds }, ['expectedVersion', 'scope', 'active', 'permissionCodes', 'deniedPermissionCodes', 'campusIds']);
const resetInput = object({}, []);
const adminInterfaces = [
    { id: 'java.access.principals.list', version: 'v2', method: 'GET', path: '/v2/system/access/principals', label: 'Java 员工授权目录', description: '按页读取员工授权摘要；不返回 OIDC issuer 或 subject。页码 1–100000，页大小 1–100。', authorization: 'system.access.manage and global scope', inputSchema: object({ page: { type: 'integer', minimum: 1, maximum: 100000, title: '页码' }, size: { type: 'integer', minimum: 1, maximum: 100, title: '每页条数' } }), response: { data: 'PrincipalView[]' }, audit: '不写审计事件', cli: 'access-admin list [--page 1] [--size 50]', readOnly: true },
    { id: 'java.access.principals.create', version: 'v2', method: 'POST', path: '/v2/system/access/principals', label: 'Java 创建员工授权映射', description: '为已经由 OIDC 身份提供方验证的 issuer+subject 建立员工授权；显式保存直接权限、拒权、职位继承权限和校区范围。要求 Idempotency-Key。', authorization: 'system.access.manage and global scope', inputSchema: createInput, response: { data: 'PrincipalView' }, audit: 'system.access.create', cli: 'access-admin create --input <JSON> --key <幂等键>', idempotency: '同一操作者、幂等键和输入安全重放' },
    { id: 'java.access.principals.update', version: 'v2', method: 'PUT', path: '/v2/system/access/principals/{principalId}', label: 'Java 修改员工授权', description: '以 expectedVersion 更新现有员工的数据范围、启停、直接权限、拒权和校区；拒权优先于职位继承权限。不能通过此接口修改管理员自身授权。要求 Idempotency-Key。', authorization: 'system.access.manage and global scope', pathParameters: { principalId: text('员工授权编号', 120, '^[A-Za-z0-9_-]+$') }, inputSchema: updateInput, response: { data: 'PrincipalView' }, audit: 'system.access.update', cli: 'access-admin update <编号> --input <JSON> --key <幂等键>', idempotency: '同一操作者、幂等键和输入安全重放' },
    { id: 'java.access.principals.resetPassword', version: 'v2', method: 'POST', path: '/v2/system/access/principals/{principalId}/reset-password', label: 'Java 重置员工密码', description: '仅可信旧超级管理员记录且当前具有全局 system.access.manage 的账号可用。数据库先启用强制改密门禁，再由受限realm客户端重置临时密码并注销会话；确认身份提供方状态及数据库门禁后才返回一次性凭据。要求幂等键和新的私有凭据文件；同key可安全恢复。', authorization: 'trusted legacy superadmin + system.access.manage + global scope', pathParameters: { principalId: text('员工授权编号', 120, '^[A-Za-z0-9_-]+$') }, inputSchema: resetInput, response: { data: 'ResetReceipt' }, audit: 'account.resetPassword', cli: 'access-admin reset-password <编号> --key <幂等键> --out <新私有凭据文件>', idempotency: '同一管理员、幂等键和目标安全重放' }
];
function validate(input, schema, label = 'input') {
    if (!(0, contracts_cjs_1.isObject)(input))
        throw Error(label + ' 必须是 JSON 对象');
    if (Object.keys(input).some(k => !Object.hasOwn(schema.properties, k)) || schema.required.some(k => !Object.hasOwn(input, k)))
        throw Error(label + ' 字段不符合注册契约');
    for (const [k, v] of Object.entries(input)) {
        const s = schema.properties[k];
        if (s.type === 'string' && (typeof v !== 'string' || v.length < (s.minLength || 0) || v.length > s.maxLength || s.pattern && !new RegExp(s.pattern).test(v)))
            throw Error(label + '.' + k + ' 无效');
        if (s.type === 'integer' && (typeof v !== 'number' || !Number.isSafeInteger(v) || v < (s.minimum ?? -Infinity) || v > (s.maximum ?? Infinity)))
            throw Error(label + '.' + k + ' 无效');
        if (s.type === 'boolean' && typeof v !== 'boolean')
            throw Error(label + '.' + k + ' 无效');
        if (s.enum && !s.enum.includes(v))
            throw Error(label + '.' + k + ' 无效');
        if (s.type === 'array' && (!Array.isArray(v) || v.length > s.maxItems || new Set(v).size !== v.length || v.some(x => typeof x !== 'string' || !x.trim() || x.length > s.items.maxLength)))
            throw Error(label + '.' + k + ' 无效');
    }
    return input;
}
async function run(args, client, identity, { read, unwrap }) {
    const fail = (message) => { throw Object.assign(new Error(message), { code: 'INVALID_ARGUMENT' }); };
    if (!identity?.permissions?.includes('system.access.manage') || identity.scope !== 'all')
        throw Object.assign(new Error('仅具有全局 system.access.manage 权限的 Java 管理员可管理员工授权'), { code: 'FORBIDDEN' });
    const [action, ...rest] = args;
    if (action === 'list') {
        const options = { page: 1, size: 50 };
        for (let i = 0; i < rest.length; i += 2) {
            const key = rest[i], value = rest[i + 1];
            if (!value || !['--page', '--size'].includes(key) || !/^\d+$/.test(value))
                fail('用法：access-admin list [--page 1] [--size 50]');
            options[key.slice(2)] = Number(value);
        }
        if (options.page < 1 || options.page > 100000 || options.size < 1 || options.size > 100)
            fail('分页范围：page 1–100000，size 1–100');
        return unwrap(await client.request('system/access/principals?' + new URLSearchParams(Object.entries(options).map(([key, value]) => [key, String(value)]))));
    }
    if (action === 'reset-password') {
        const id = rest.shift();
        let key, out;
        for (let i = 0; i < rest.length; i += 2) {
            if (rest[i] === '--key' && !key)
                key = rest[i + 1];
            else if (rest[i] === '--out' && !out)
                out = rest[i + 1];
            else
                fail('用法：access-admin reset-password <编号> --key <幂等键> --out <新私有凭据文件>');
        }
        if (!id || !/^[A-Za-z0-9_-]{1,120}$/.test(id) || typeof key !== 'string' || !/^[A-Za-z0-9_-]{16,100}$/.test(key) || typeof out !== 'string')
            fail('需要有效员工编号、16–100字符幂等键和--out新私有凭据文件');
        const principalId = requiredText(id), requestKey = requiredText(key), output = requiredText(out);
        const file = reservePrivateCredentialFile(output, principalId, requestKey);
        const result = unwrap(await client.request('system/access/principals/' + encodeURIComponent(principalId) + '/reset-password', { method: 'POST', body: {}, idempotencyKey: requestKey }));
        if (!(0, contracts_cjs_1.isObject)(result) || !(0, contracts_cjs_1.isObject)(result.user) || result.user.principalId !== principalId || result.user.credentialChangeRequired !== true || typeof result.initialPassword !== 'string' || !result.initialPassword)
            throw Object.assign(new Error('服务端未确认一次性凭据，请使用相同幂等键重试'), { code: 'CREDENTIAL_DELIVERY_UNCERTAIN' });
        const credential = { principalId, requestKey, initialPassword: result.initialPassword, mustChangePassword: true, user: result.user };
        writePrivateCredentialFile(file, credential, principalId, requestKey);
        return { reset: true, user: result.user, credentialFile: file };
    }
    if (action === 'create' || action === 'update') {
        const update = action === 'update', id = update ? rest.shift() : null;
        let file, key;
        for (let i = 0; i < rest.length; i += 2) {
            if (rest[i] === '--input' && !file)
                file = rest[i + 1];
            else if (rest[i] === '--key' && !key)
                key = rest[i + 1];
            else
                fail('用法：access-admin ' + (update ? 'update <编号>' : 'create') + ' --input <JSON> --key <幂等键>');
        }
        if (update && (!id || !/^[A-Za-z0-9_-]{1,120}$/.test(id)) || !file || !key || !/^[A-Za-z0-9_-]{16,100}$/.test(key))
            fail('需要有效编号、--input JSON 文件和 16–100 字符 --key 幂等键');
        const input = read(file), body = validate(input, update ? updateInput : createInput, action);
        const path = update ? 'system/access/principals/' + encodeURIComponent(id) : 'system/access/principals';
        return unwrap(await client.request(path, { method: update ? 'PUT' : 'POST', body, idempotencyKey: key }));
    }
    fail('用法：access-admin list [--page 1] [--size 50] | create --input <JSON> --key <幂等键> | update <编号> --input <JSON> --key <幂等键> | reset-password <编号> --key <幂等键> --out <新私有凭据文件>');
}
function requiredText(value) { if (typeof value !== 'string')
    throw Object.assign(new Error('必填参数缺失'), { code: 'INVALID_ARGUMENT' }); return value; }
function reservePrivateCredentialFile(output, principalId, requestKey) {
    const file = path.resolve(output), directory = path.dirname(file), dir = fs.lstatSync(directory);
    if (dir.isSymbolicLink() || !dir.isDirectory() || (dir.mode & 0o077) !== 0 || process.getuid && dir.uid !== process.getuid())
        throw Object.assign(new Error('--out目录必须是当前用户的私有目录'), { code: 'UNSAFE_OUTPUT' });
    try {
        const stat = fs.lstatSync(file);
        if (stat.isSymbolicLink() || !stat.isFile() || (stat.mode & 0o077) !== 0 || process.getuid && stat.uid !== process.getuid())
            throw Object.assign(new Error('--out必须是当前用户私有普通文件'), { code: 'UNSAFE_OUTPUT' });
        const content = fs.readFileSync(file, 'utf8');
        if (content) {
            let saved;
            try {
                saved = JSON.parse(content);
            }
            catch {
                throw Object.assign(new Error('凭据输出文件已存在且内容无效'), { code: 'OUTPUT_EXISTS' });
            }
            if (!(0, contracts_cjs_1.isObject)(saved) || saved.principalId !== principalId || saved.requestKey !== requestKey || typeof saved.initialPassword !== 'string')
                throw Object.assign(new Error('输出文件与目标或幂等键不匹配'), { code: 'OUTPUT_EXISTS' });
        }
        return file;
    }
    catch (error) {
        if ((0, contracts_cjs_1.isObject)(error) && error.code !== 'ENOENT')
            throw error;
    }
    try {
        const descriptor = fs.openSync(file, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
        fs.closeSync(descriptor);
    }
    catch {
        throw Object.assign(new Error('无法保留新的私有凭据文件'), { code: 'UNSAFE_OUTPUT' });
    }
    return file;
}
function writePrivateCredentialFile(file, value, principalId, requestKey) {
    const descriptor = fs.openSync(file, fs.constants.O_RDWR | fs.constants.O_NOFOLLOW);
    try {
        const stat = fs.fstatSync(descriptor);
        if (!stat.isFile() || (stat.mode & 0o077) !== 0 || process.getuid && stat.uid !== process.getuid())
            throw Object.assign(new Error('凭据输出文件权限不安全'), { code: 'UNSAFE_OUTPUT' });
        const existing = fs.readFileSync(descriptor, 'utf8');
        if (existing) {
            const saved = JSON.parse(existing);
            if (!(0, contracts_cjs_1.isObject)(saved) || saved.principalId !== principalId || saved.requestKey !== requestKey || saved.initialPassword !== value.initialPassword)
                throw Object.assign(new Error('重放凭据与私有文件内容不一致'), { code: 'CREDENTIAL_DELIVERY_UNCERTAIN' });
        }
        fs.ftruncateSync(descriptor, 0);
        fs.writeSync(descriptor, JSON.stringify(value, null, 2) + '\n', 0, 'utf8');
        fs.fsyncSync(descriptor);
    }
    finally {
        fs.closeSync(descriptor);
    }
}
module.exports = { adminInterfaces, createInput, updateInput, resetInput, validate, run };
