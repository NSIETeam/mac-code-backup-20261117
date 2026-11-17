'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const authClient = auth, businessClient = business;
const designFields = ['campusId', 'title', 'description', 'formKind', 'collectLead', 'fields', 'leadFieldMapping'];
const operations = [
    { id: 'form.create', name: 'create', method: 'POST', endpoint: 'crm/forms', permissions: ['crm.form.write'], fields: designFields },
    { id: 'form.update', name: 'update', method: 'PUT', endpoint: 'crm/forms/{id}', permissions: ['crm.form.write'], fields: [...designFields, 'expectedVersion'], target: true },
    { id: 'form.publish', name: 'publish', method: 'POST', endpoint: 'crm/forms/{id}/publish', permissions: ['crm.form.publish'], fields: ['expectedVersion'], target: true },
    { id: 'form.unpublish', name: 'unpublish', method: 'POST', endpoint: 'crm/forms/{id}/unpublish', permissions: ['crm.form.publish'], fields: ['expectedVersion'], target: true },
    { id: 'form.activity.link', name: 'link', method: 'POST', endpoint: 'crm/activities/{id}/forms', permissions: ['crm.activity.write', 'crm.form.write'], fields: ['formId'], target: true },
    { id: 'form.reward.read', name: 'reward-get', method: 'GET', endpoint: 'crm/forms/{id}/reward', permissions: ['crm.form.read'], fields: [], target: true },
    { id: 'form.reward.update', name: 'reward-update', method: 'PUT', endpoint: 'crm/forms/{id}/reward', permissions: ['crm.form.write'], fields: ['reward', 'expectedVersion'], target: true },
    { id: 'form.trial.read', name: 'trial-get', method: 'GET', endpoint: 'crm/forms/{id}/trial', permissions: ['crm.form.read'], fields: [], target: true },
    { id: 'form.trial.update', name: 'trial-update', method: 'PUT', endpoint: 'crm/forms/{id}/trial', permissions: ['crm.form.write'], fields: ['trial', 'expectedVersion'], target: true },
    { id: 'form.submission.review', name: 'review', method: 'POST', endpoint: 'crm/form-submissions/{id}/review', permissions: ['crm.form.read', 'crm.form.submission.read', 'crm.form.submission.review'], fields: ['decision', 'note', 'customerId', 'confirmSeparateHousehold', 'expectedVersion'], target: true }
];
const fail = (message) => { throw authClient.failure('INVALID_FORM_INPUT', message); };
function plain(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
function exact(value, keys) { if (!plain(value) || Object.keys(value).some(key => !keys.includes(key)))
    return fail('包含未知字段或无效对象'); return value; }
function text(value, max, required = false) { if (value == null && !required)
    return; if (typeof value !== 'string' || value.length > max || required && !value.trim())
    fail('文本为空或超过长度限制'); }
function id(value) { if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value))
    return fail('记录编号无效'); return value; }
function version(value) { if (!Number.isSafeInteger(value) || value < 1)
    fail('expectedVersion必须为正整数'); }
function boolean(value) { if (value !== undefined && typeof value !== 'boolean')
    fail('布尔字段必须为true或false'); }
function validate(operation, value) {
    const input = exact(value, operation.fields);
    if (['update', 'publish', 'unpublish', 'review', 'trial-update', 'reward-update'].includes(operation.name))
        version(input.expectedVersion);
    if (operation.name === 'trial-get' || operation.name === 'reward-get')
        return input;
    if (operation.name === 'reward-update') {
        if (!Object.hasOwn(input, 'reward'))
            return fail('必须明确提供reward配置或null');
        if (input.reward === null)
            return input;
        const reward = exact(input.reward, ['type', 'promotionId', 'title', 'url']);
        if (!['coupon', 'material_link'].includes(reward.type))
            return fail('奖励类型无效');
        text(reward.title, 120);
        text(reward.url, 2048);
        if (reward.type === 'coupon')
            id(reward.promotionId);
        else {
            text(reward.title, 120, true);
            text(reward.url, 2048, true);
            let url;
            try {
                url = new URL(reward.url);
            }
            catch {
                return fail('资料链接无效');
            }
            if (!['https:', 'http:'].includes(url.protocol))
                return fail('资料链接协议无效');
        }
        return input;
    }
    if (operation.name === 'trial-update') {
        if (!Object.hasOwn(input, 'trial'))
            return fail('必须明确提供trial配置或null');
        if (input.trial === null)
            return input;
        const trial = exact(input.trial, ['enabled', 'campusFieldId', 'gradeFieldId', 'subjectFieldId', 'nameFieldId', 'phoneFieldId', 'slots']);
        if (trial.enabled !== true)
            return fail('停用试课请使用trial:null');
        text(trial.campusFieldId, 80);
        for (const key of ['gradeFieldId', 'subjectFieldId', 'nameFieldId', 'phoneFieldId'])
            text(trial[key], 80, true);
        if (!Array.isArray(trial.slots) || !trial.slots.length || trial.slots.length > 100)
            return fail('试课时段须为1至100项');
        const seen = new Set();
        for (const raw of trial.slots) {
            const slot = exact(raw, ['id', 'classId', 'sessionId', 'capacity']);
            if (typeof slot.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(slot.id) || seen.has(slot.id))
                return fail('试课时段编号无效或重复');
            seen.add(slot.id);
            id(slot.classId);
            id(slot.sessionId);
            if (!Number.isSafeInteger(slot.capacity) || slot.capacity < 1 || slot.capacity > 1000)
                return fail('试课容量须为1至1000');
        }
        return input;
    }
    if (operation.name === 'link') {
        id(input.formId);
        return input;
    }
    if (operation.name === 'publish' || operation.name === 'unpublish')
        return input;
    if (operation.name === 'review') {
        if (!['approve', 'reject'].includes(input.decision))
            return fail('审批结果无效');
        text(input.note, 2000, true);
        if (input.customerId != null)
            id(input.customerId);
        boolean(input.confirmSeparateHousehold);
        return input;
    }
    id(input.campusId);
    text(input.title, 160, true);
    text(input.description, 2000);
    boolean(input.collectLead);
    if (!['lead', 'survey', 'custom'].includes(input.formKind) || input.formKind === 'survey' && input.collectLead)
        return fail('表单类型与线索收集配置不一致');
    if (!Array.isArray(input.fields) || input.fields.length < 1 || input.fields.length > 100)
        return fail('字段数量须为1至100');
    const fields = new Map(), types = ['name', 'phone', 'email', 'text', 'textarea', 'single', 'multiple', 'dropdown', 'gender', 'date', 'time', 'description', 'divider', 'image'];
    for (const raw of input.fields) {
        const field = exact(raw, ['id', 'title', 'type', 'description', 'required', 'options']);
        if (typeof field.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(field.id) || fields.has(field.id) || !types.includes(field.type))
            return fail('字段编号重复或类型无效');
        text(field.title, 120, true);
        text(field.description, 500);
        boolean(field.required);
        fields.set(field.id, field);
        const choice = ['single', 'multiple', 'dropdown', 'gender'].includes(field.type), options = field.options ?? [];
        if (!Array.isArray(options) || options.length > 100 || choice && (!options.length || new Set(options).size !== options.length) || !choice && options.length)
            return fail('选项配置无效');
        for (const option of options)
            text(option, 120, true);
    }
    const mapping = exact(input.leadFieldMapping ?? {}, ['studentName', 'guardianPhone', 'subject', 'term', 'detail']);
    for (const mapped of Object.values(mapping)) {
        text(mapped, 80, true);
        if (typeof mapped !== 'string' || !fields.has(mapped))
            return fail('映射的字段不存在');
    }
    if (input.collectLead && (!['name', 'text'].includes(fields.get(mapping.studentName)?.type) || fields.get(mapping.guardianPhone)?.type !== 'phone'))
        return fail('收集线索必须映射姓名和电话');
    return input;
}
function parseKey(value) { if (!/^[A-Za-z0-9._:-]{16,128}$/.test(value))
    return fail('幂等键无效'); return value; }
async function run(args, client, identity) {
    const [name, ...rest] = args, operation = operations.find(item => item.name === name);
    if (!operation)
        return fail('用法：forms create|update|publish|unpublish|link|review|trial-get|trial-update|reward-get|reward-update [编号] --input <JSON> --key <幂等键>');
    if (!operation.permissions.every(permission => identity.permissions.includes(permission)))
        throw authClient.failure('FORBIDDEN', '缺少表单操作权限');
    const target = operation.target ? id(rest.shift()) : '', flags = {};
    for (let index = 0; index < rest.length; index += 2) {
        const flag = rest[index], value = rest[index + 1];
        if (!['--input', '--key'].includes(flag) || !value || Object.hasOwn(flags, flag))
            return fail('参数缺失、重复或未知');
        flags[flag] = value;
    }
    if (operation.method === 'GET') {
        if (Object.keys(flags).length)
            return fail('读取配置不接受写入参数');
        return client.request(operation.endpoint.replace('{id}', encodeURIComponent(target)), { method: 'GET' });
    }
    if (!flags['--input'] || !flags['--key'])
        return fail('必须提供输入文件与幂等键');
    const body = validate(operation, businessClient.read(flags['--input']));
    let endpoint = operation.endpoint.replace('{id}', encodeURIComponent(target));
    let requestBody = body;
    if (name === 'update') {
        const { expectedVersion, ...draft } = body;
        endpoint += `?expectedVersion=${encodeURIComponent(String(expectedVersion))}`;
        requestBody = draft;
    }
    return client.request(endpoint, { method: operation.method, idempotencyKey: parseKey(flags['--key']), body: requestBody });
}
module.exports = { operations, validate, run };
