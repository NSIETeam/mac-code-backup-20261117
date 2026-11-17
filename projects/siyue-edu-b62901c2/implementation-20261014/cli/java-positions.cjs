'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth, businessClient = business;
const identifier = { type: 'string', minLength: 1, maxLength: 120, pattern: '^[A-Za-z0-9_-]+$' };
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const text = (title, maxLength) => ({ type: 'string', title, minLength: 1, maxLength });
const codes = { type: 'array', title: '可委派权限', items: { type: 'string', minLength: 1, maxLength: 100 }, maxItems: 100, uniqueItems: true };
const staffIds = { type: 'array', title: '员工编号', items: identifier, maxItems: 500, uniqueItems: true };
const createSchema = object({ positionId: { ...identifier, title: '职位编号' }, name: text('职位名称', 100), description: { type: 'string', maxLength: 500 }, permissionCodes: codes }, ['name', 'permissionCodes']);
const updateSchema = object({ expectedVersion: { type: 'integer', minimum: 1 }, name: text('职位名称', 100), description: { type: 'string', maxLength: 500 }, active: { type: 'boolean' }, permissionCodes: codes }, ['expectedVersion', 'name', 'active', 'permissionCodes']);
const membersSchema = object({ expectedVersion: { type: 'integer', minimum: 1 }, principalIds: staffIds }, ['expectedVersion', 'principalIds']);
const adminInterfaces = [
    { id: 'java.access.positions.list', version: 'v2', method: 'GET', path: '/v2/system/positions', label: 'Java 职位权限模板目录', description: '读取职位模板、权限模板和员工关联；受全局 system.access.manage 权限保护。', authorization: 'system.access.manage and global scope', inputSchema: object({ page: { type: 'integer', minimum: 1, maximum: 100000 }, size: { type: 'integer', minimum: 1, maximum: 100 } }), response: { data: 'PositionPage' }, audit: '不写审计事件', cli: 'positions list [--page 1] [--size 50]', readOnly: true },
    { id: 'java.access.positions.create', version: 'v2', method: 'POST', path: '/v2/system/positions', label: 'Java 创建职位模板', description: '创建带明确权限清单的职位模板；system.access.manage 不可被委派。', authorization: 'system.access.manage and global scope', inputSchema: createSchema, response: { data: 'Position' }, audit: 'system.position.create', cli: 'positions create --input <JSON> --key <幂等键>', idempotency: '同一操作者、幂等键和输入安全重放' },
    { id: 'java.access.positions.update', version: 'v2', method: 'PUT', path: '/v2/system/positions/{positionId}', label: 'Java 更新职位模板', description: '以 expectedVersion 修改名称、启用状态和权限清单；停用后职位继承权限立即失效。', authorization: 'system.access.manage and global scope', pathParameters: { positionId: identifier }, inputSchema: updateSchema, response: { data: 'Position' }, audit: 'system.position.update', cli: 'positions update <编号> --input <JSON> --key <幂等键>', idempotency: '同一操作者、幂等键和输入安全重放' },
    { id: 'java.access.positions.members', version: 'v2', method: 'PUT', path: '/v2/system/positions/{positionId}/members', label: 'Java 设置职位员工关联', description: '以 expectedVersion 替换完整员工关联集；更新时锁定现有及新加入的员工，关联撤销后立即重新读取有效权限。', authorization: 'system.access.manage and global scope', pathParameters: { positionId: identifier }, inputSchema: membersSchema, response: { data: 'Position' }, audit: 'system.position.update', cli: 'positions members <编号> --input <JSON> --key <幂等键>', idempotency: '同一操作者、幂等键和输入安全重放' }
];
const fail = (message) => { throw authClient.failure('INVALID_POSITION_INPUT', message); };
function plain(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
function exact(value, keys) { if (!plain(value) || Object.keys(value).some(key => !keys.includes(key)))
    return fail('输入含不支持字段或不是 JSON 对象'); return value; }
function validateCodes(value) { if (!Array.isArray(value) || value.length > 100 || value.some(code => typeof code !== 'string' || !code.trim() || code.length > 100) || new Set(value).size !== value.length || value.includes('system.access.manage'))
    fail('权限列表无效或含不可委派的管理权限'); }
function validate(name, value) {
    if (name === 'create') {
        const input = exact(value, ['positionId', 'name', 'description', 'permissionCodes']);
        if (typeof input.name !== 'string' || !input.name.trim() || input.name.trim().length > 100 || input.positionId != null && (typeof input.positionId !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(input.positionId)) || input.description != null && (typeof input.description !== 'string' || input.description.length > 500))
            fail('职位资料无效');
        validateCodes(input.permissionCodes);
        return input;
    }
    if (name === 'update') {
        const input = exact(value, ['expectedVersion', 'name', 'description', 'active', 'permissionCodes']);
        if (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1 || typeof input.name !== 'string' || !input.name.trim() || input.name.trim().length > 100 || typeof input.active !== 'boolean' || input.description != null && (typeof input.description !== 'string' || input.description.length > 500))
            fail('职位版本或资料无效');
        validateCodes(input.permissionCodes);
        return input;
    }
    if (name === 'members') {
        const input = exact(value, ['expectedVersion', 'principalIds']);
        if (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1 || !Array.isArray(input.principalIds) || input.principalIds.length > 500 || input.principalIds.some(id => typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(id)) || new Set(input.principalIds).size !== input.principalIds.length)
            fail('员工关联版本或编号列表无效');
        return input;
    }
    return fail('未知职位操作');
}
function authorize(identity) { if (!identity.permissions.includes('system.access.manage') || identity.scope !== 'all')
    throw authClient.failure('FORBIDDEN', '职位模板需要 system.access.manage 和全部数据范围'); }
function parseKey(value) { if (!/^[A-Za-z0-9._:-]{16,100}$/.test(value))
    return fail('幂等键长度须为16至100字符'); return value; }
async function run(args, client, identity) {
    authorize(identity);
    const [name, ...rest] = args;
    if (name === 'list') {
        const query = { page: 1, size: 50 }, seen = new Set();
        for (let index = 0; index < rest.length; index += 2) {
            const flag = rest[index], value = rest[index + 1];
            if (!['--page', '--size'].includes(flag) || seen.has(flag) || !/^\d+$/.test(value || ''))
                return fail('分页参数无效');
            seen.add(flag);
            if (flag === '--page')
                query.page = Number(value);
            else
                query.size = Number(value);
        }
        if (!Number.isSafeInteger(query.page) || query.page < 1 || query.page > 100000 || !Number.isSafeInteger(query.size) || query.size < 1 || query.size > 100)
            return fail('分页范围无效');
        return client.request(`system/positions?page=${query.page}&size=${query.size}`);
    }
    if (name !== 'create' && name !== 'update' && name !== 'members')
        return fail('用法：positions list|create|update|members');
    const update = name !== 'create', target = update ? rest.shift() : undefined;
    if (update && (typeof target !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(target)))
        return fail('职位编号无效');
    const flags = {};
    for (let index = 0; index < rest.length; index += 2) {
        const flag = rest[index], value = rest[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(flags, flag))
            return fail('参数缺失、重复或不支持');
        flags[flag] = value;
    }
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供 --input 和 --key');
    const input = validate(name, businessClient.read(flags['--input'])), key = parseKey(flags['--key']), endpoint = name === 'create' ? 'system/positions' : `system/positions/${encodeURIComponent(target || '')}${name === 'members' ? '/members' : ''}`, method = name === 'create' ? 'POST' : 'PUT';
    return client.request(endpoint, { method, body: input, idempotencyKey: key });
}
module.exports = { adminInterfaces, createSchema, updateSchema, membersSchema, validate, run };
