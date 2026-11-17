'use strict';
const auth = require("./auth-client.cjs");
const contracts_cjs_1 = require("../contracts.cjs");
const business = require("./business-client.cjs");
const fields = { page: { type: 'string', minLength: 1, maxLength: 256 }, pageTitle: { type: 'string', minLength: 1, maxLength: 120 }, elementKind: { type: 'string', enum: ['button', 'link', 'input', 'select', 'textarea', 'table-cell', 'table-row', 'table-header', 'tab', 'label', 'card', 'navigation', 'heading', 'other'] }, elementLabel: { type: 'string', minLength: 1, maxLength: 200 }, selector: { type: 'string', minLength: 1, maxLength: 500 }, issue: { type: 'string', minLength: 1, maxLength: 4000 }, category: { type: 'string', enum: ['interface', 'workflow'] }, workflowName: { type: 'string', minLength: 1, maxLength: 120 }, flowDefinition: { type: 'string', minLength: 1, maxLength: 6000 }, desiredFlowDefinition: { type: 'string', minLength: 1, maxLength: 6000 } };
function fail(message) { throw auth.failure('INVALID_ARGUMENT', message); }
function validate(input) {
    if (!(0, contracts_cjs_1.isObject)(input) || Object.getPrototypeOf(input) !== Object.prototype)
        fail('标注文件必须是 JSON 对象');
    const allowed = new Set(Object.keys(fields));
    if (Object.keys(input).some(key => !allowed.has(key)))
        fail('标注文件包含未支持字段');
    for (const key of ['page', 'pageTitle', 'elementKind', 'elementLabel', 'selector', 'issue'])
        if (!Object.hasOwn(input, key))
            fail('缺少必要字段：' + key);
    for (const [key, value] of Object.entries(input)) {
        const spec = fields[key];
        if (typeof value !== 'string' || !value.trim() || value.length > spec.maxLength || /[\u0000-\u0008\u000b\u000c-\u001f\u007f]/.test(value) || spec.enum && !spec.enum.includes(value))
            fail('字段无效：' + key);
    }
    if (!input.page.startsWith('/crm/'))
        fail('page 必须是 /crm/ 页面路径');
    if (input.category === 'workflow' && (!input.workflowName || !input.flowDefinition || !input.desiredFlowDefinition))
        fail('流程建议必须填写流程名称、当前流程定义和目标流程定义');
    return input;
}
async function run(command, args = []) {
    const me = await auth.authenticated('v1/auth/me');
    if (me.user?.mustChangePassword)
        throw auth.failure('PASSWORD_CHANGE_REQUIRED', '请先运行 siyue-cli passwd 修改初始密码');
    if (!me.user?.roleIds?.includes('superadmin'))
        throw auth.failure('FORBIDDEN', '仅超级管理员可以管理待修改建议');
    if (command === 'list' && !args.length)
        return auth.authenticated('v1/admin/ui-annotations');
    if (command === 'create') {
        if (args.length !== 2 || args[0] !== '--input' || !args[1])
            fail('用法：annotations create --input <标注JSON>');
        return auth.authenticated('v1/admin/ui-annotations', { method: 'POST', body: validate(business.read(args[1])) });
    }
    if (command === 'reopen' && args.length === 1 && /^[a-f0-9-]{36}$/i.test(args[0]))
        return auth.authenticated('v1/admin/ui-annotations/' + encodeURIComponent(args[0]), { method: 'PATCH', body: { status: 'open' } });
    if (command === 'resolve' && args.length === 2 && args[1] === '--verified' && /^[a-f0-9-]{36}$/i.test(args[0]))
        return auth.authenticated('v1/admin/ui-annotations/' + encodeURIComponent(args[0]), { method: 'PATCH', body: { status: 'resolved' } });
    fail('用法：annotations list | annotations create --input <JSON> | annotations resolve <UUID> --verified | annotations reopen <UUID>');
}
module.exports = { run, validate, fields };
