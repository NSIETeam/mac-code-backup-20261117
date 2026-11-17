'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const orders = require("./java-orders.cjs");
const authClient = auth, businessClient = business;
const productFields = ['courseId', 'name', 'classIds', 'published', 'expectedVersion'];
const promotionFields = ['type', 'name', 'description', 'startDate', 'endDate', 'discountAmount', 'groupSize', 'validHours', 'stackCoupon', 'participants', 'selections', 'published', 'expectedVersion'];
const couponFields = ['name', 'description', 'template', 'campusId', 'startDate', 'endDate', 'discountAmount', 'thresholdAmount', 'total', 'maxPerUser', 'courseIds', 'expectedVersion'];
const contentFields = ['title', 'grade', 'subject', 'courseType', 'price', 'image', 'description', 'expectedVersion'];
const read = (name, id, label, endpoint, permissions, extra = {}) => ({ name, id, label, method: 'GET', endpoint, permissions, ...extra });
const write = (name, id, label, method, endpoint, permissions, fields, required, extra = {}) => ({ name, id, label, method, endpoint, permissions, fields, required, input: true, ...extra });
const operations = [
    read('products', 'commerce.product.list', '查询商品上下架', 'commerce/products', ['commerce.read'], { permissionGroups: [['commerce.read', 'commerce.manage', 'product.publish', 'store.read']], paged: true }),
    read('course-classes', 'commerce.course.classes', '选择商品班级', 'commerce/courses/{id}/classes', ['commerce.read'], { paged: true, pathParameter: 'id' }),
    write('product-publish', 'commerce.product.publish', '发布课程商品', 'POST', 'commerce/courses/{id}/publish', ['product.publish'], ['classIds', 'expectedVersion'], ['expectedVersion'], { permissionGroups: [['product.publish', 'commerce.manage']], pathParameter: 'id' }),
    write('product-unpublish', 'commerce.product.unpublish', '下架课程商品', 'POST', 'commerce/courses/{id}/unpublish', ['product.publish'], ['classIds', 'expectedVersion'], ['expectedVersion'], { permissionGroups: [['product.publish', 'commerce.manage']], pathParameter: 'id' }),
    write('product-create', 'commerce.product.create', '创建商品及上架设置', 'POST', 'commerce/products', ['commerce.manage'], productFields, ['courseId', 'name', 'classIds', 'published']),
    write('product-update', 'commerce.product.update', '更新商品及上下架', 'PUT', 'commerce/products/{id}', ['commerce.manage'], productFields, productFields, { pathParameter: 'id' }),
    read('promotions', 'commerce.promotion.list', '查询拼团联报活动', 'commerce/promotions', ['commerce.read'], { paged: true, typeFilter: true }),
    write('promotion-create', 'commerce.promotion.create', '创建拼团联报活动', 'POST', 'commerce/promotions', ['commerce.manage'], promotionFields, ['type', 'name', 'startDate', 'endDate', 'discountAmount', 'stackCoupon', 'participants', 'selections', 'published']),
    write('promotion-update', 'commerce.promotion.update', '更新拼团联报活动及发布', 'PUT', 'commerce/promotions/{id}', ['commerce.manage'], promotionFields, ['type', 'name', 'startDate', 'endDate', 'discountAmount', 'stackCoupon', 'participants', 'selections', 'published', 'expectedVersion'], { pathParameter: 'id' }),
    read('content', 'commerce.storefront.content.read', '读取商城商品展示', 'commerce/storefront/products/{id}', ['commerce.read'], { pathParameter: 'id' }),
    write('content-update', 'commerce.storefront.content.update', '更新商城商品展示', 'PUT', 'commerce/storefront/products/{id}', ['commerce.manage'], contentFields, ['title', 'grade', 'subject', 'courseType', 'price', 'expectedVersion'], { pathParameter: 'id' }),
    read('decor', 'commerce.storefront.decor.read', '读取商城装修草稿和发布版', 'commerce/storefront/decor', ['store.design'], { scopes: ['all'] }),
    write('decor-save', 'commerce.storefront.decor.update', '保存商城装修草稿', 'PUT', 'commerce/storefront/decor', ['store.design'], ['decor', 'expectedVersion'], ['decor', 'expectedVersion'], { scopes: ['all'] }),
    write('decor-publish', 'commerce.storefront.decor.publish', '发布已核对商城装修', 'POST', 'commerce/storefront/decor/publish', ['store.design'], ['decor', 'expectedVersion'], ['expectedVersion'], { scopes: ['all'] }),
    read('coupons', 'commerce.coupon.list', '查询优惠券', 'commerce/coupons', ['commerce.read'], { permissionGroups: [['promotion.create', 'promotion.publish', 'commerce.read']], paged: true }),
    read('coupon-options', 'commerce.coupon.options', '查询优惠券适用课程校区', 'commerce/coupons/options', ['commerce.read'], { permissionGroups: [['promotion.create', 'promotion.publish', 'commerce.read', 'store.design']], paged: true, pageSizeKey: 'size' }),
    write('coupon-create', 'commerce.coupon.create', '创建优惠券草稿', 'POST', 'commerce/coupons', ['promotion.create'], couponFields, ['name', 'template', 'startDate', 'endDate', 'discountAmount', 'thresholdAmount', 'total', 'maxPerUser', 'courseIds']),
    write('coupon-update', 'commerce.coupon.update', '更新优惠券草稿', 'PUT', 'commerce/coupons/{id}', ['promotion.create'], couponFields, ['name', 'template', 'startDate', 'endDate', 'discountAmount', 'thresholdAmount', 'total', 'maxPerUser', 'courseIds', 'expectedVersion'], { pathParameter: 'id' }),
    write('coupon-publish', 'commerce.coupon.publish', '发布优惠券', 'POST', 'commerce/coupons/{id}/publish', ['promotion.publish'], ['expectedVersion'], ['expectedVersion'], { pathParameter: 'id' }),
    write('coupon-unpublish', 'commerce.coupon.unpublish', '停止发布优惠券', 'POST', 'commerce/coupons/{id}/unpublish', ['promotion.publish'], ['expectedVersion'], ['expectedVersion'], { pathParameter: 'id' })
];
const fail = (message, code = 'INVALID_COMMERCE_INPUT') => { throw authClient.failure(code, message); };
function plain(value) { return value !== null && typeof value === 'object' && !Array.isArray(value) && [Object.prototype, null].includes(Object.getPrototypeOf(value)) && Object.getOwnPropertySymbols(value).length === 0; }
const validId = (value) => typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value);
function exact(value, keys, required = []) { if (!plain(value) || Object.keys(value).some(key => !keys.includes(key)))
    return fail('输入包含无效字段'); for (const key of required)
    if (!Object.hasOwn(value, key))
        return fail(`缺少字段 ${key}`); return value; }
function text(value, max, required = true) { if (value == null && !required)
    return; if (typeof value !== 'string' || value.length > max || required && !value.trim())
    fail('文字字段长度或内容无效'); }
function integer(value, min, max = Number.MAX_SAFE_INTEGER) { if (!Number.isSafeInteger(value) || value < min || value > max)
    fail('整数或版本超出范围'); }
function ids(value, min, max) { if (!Array.isArray(value) || value.length < min || value.length > max || value.some(item => !validId(item)) || new Set(value).size !== value.length)
    fail('编号列表为空、重复或无效'); }
function bool(value) { if (typeof value !== 'boolean')
    fail('状态字段必须为布尔值'); }
function decimal(value, digits, min, max = null) { if ((typeof value !== 'number' && typeof value !== 'string') || !new RegExp(`^(?:0|[1-9][0-9]{0,${digits - 1}})(?:\\.[0-9]{1,2})?$`).test(String(value)))
    return fail('金额格式或精度无效'); const [whole, fraction = ''] = String(value).split('.'); const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0')); if (cents < BigInt(min) || max !== null && cents > max)
    return fail('金额超出范围'); return cents; }
function date(value) { if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value)
    fail('日期须为有效YYYY-MM-DD'); }
function dates(input) { date(input.startDate); date(input.endDate); if (input.endDate < input.startDate)
    fail('结束日期不能早于开始日期'); }
function image(value, max, embedded = false) { text(value, max, false); if (value == null || value === '')
    return; if (typeof value !== 'string')
    return fail('图片地址无效'); if (embedded && /^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(value))
    return; let url; try {
    url = new URL(value);
}
catch {
    return fail('图片地址无效');
} if (url.protocol !== 'https:' || !url.hostname || url.username || url.password)
    fail('图片须为无凭据HTTPS地址'); }
function version(name, input) { if (name.endsWith('-create')) {
    if (input.expectedVersion != null)
        fail('新建操作不得携带expectedVersion');
}
else
    integer(input.expectedVersion, name.startsWith('decor-') ? 0 : 1); }
function selection(value) { const item = exact(value, ['courseId', 'classIds'], ['courseId', 'classIds']); if (!validId(item.courseId))
    fail('课程编号无效'); ids(item.classIds, 1, 5000); }
function decor(value) {
    const input = exact(value, ['title', 'subtitle', 'color', 'modules', 'blocks'], ['title', 'modules', 'blocks']);
    text(input.title, 100);
    text(input.subtitle, 300, false);
    if (input.color != null && (typeof input.color !== 'string' || !/^#[a-fA-F0-9]{6}$/.test(input.color)))
        fail('主题颜色无效');
    const moduleKey = (key) => { text(key, 100); if (typeof key !== 'string' || ['__proto__', 'prototype', 'constructor'].includes(key))
        fail('模块名称无效'); };
    if (!Array.isArray(input.modules) || input.modules.length > 20 || new Set(input.modules).size !== input.modules.length)
        return fail('装修模块列表无效');
    for (const key of input.modules)
        moduleKey(key);
    const blocks = input.blocks;
    if (!plain(blocks) || Object.keys(blocks).length > 40 || input.modules.some(key => typeof key !== 'string' || !Object.hasOwn(blocks, key)))
        return fail('装修模块缺少内容');
    for (const [key, raw] of Object.entries(blocks)) {
        moduleKey(key);
        const block = exact(raw, ['title', 'body', 'image', 'cta', 'courseIds'], ['title']);
        text(block.title, 200);
        text(block.body, 5000, false);
        text(block.cta, 100, false);
        image(block.image, 700000, true);
        if (block.courseIds !== undefined)
            ids(block.courseIds, 0, 100);
    }
}
function validate(name, value) {
    const op = operations.find(item => item.name === name && item.input);
    if (!op)
        return fail('不支持的商城写入命令');
    const input = exact(value, op.fields || [], op.required || []);
    version(name, input);
    if (name === 'product-publish' || name === 'product-unpublish') {
        if (input.classIds !== undefined)
            ids(input.classIds, 1, 1000);
    }
    else if (name.startsWith('product-')) {
        selection({ courseId: input.courseId, classIds: input.classIds });
        text(input.name, 100);
        bool(input.published);
    }
    else if (name.startsWith('promotion-')) {
        if (!['group', 'joint'].includes(input.type))
            return fail('活动类型无效');
        text(input.name, 100);
        text(input.description, 2000, false);
        dates(input);
        decimal(input.discountAmount, 10, 1);
        bool(input.stackCoupon);
        bool(input.published);
        if (!Array.isArray(input.participants) || input.participants.length < 1 || input.participants.length > 3 || input.participants.some(item => !['在读学员', '潜在学员', '历史学员'].includes(item)) || new Set(input.participants).size !== input.participants.length)
            return fail('参与人群无效');
        if (!Array.isArray(input.selections) || input.selections.length < 1 || input.selections.length > 20)
            return fail('活动课程数量无效');
        for (const item of input.selections)
            selection(item);
        if (new Set(input.selections.map(item => plain(item) ? item.courseId : null)).size !== input.selections.length)
            return fail('活动课程重复');
        if (input.type === 'group') {
            integer(input.groupSize, 2, 1000);
            integer(input.validHours, 1, 8760);
        }
        else if (input.selections.length < 2 || input.groupSize != null || input.validHours != null)
            return fail('联报须至少两课程且不能指定拼团配置');
    }
    else if (name === 'content-update') {
        text(input.title, 100);
        text(input.grade, 40);
        text(input.subject, 40);
        if (!['onsite', 'live'].includes(input.courseType))
            return fail('课程类型无效');
        decimal(input.price, 12, 0);
        image(input.image, 2000);
        text(input.description, 2000, false);
    }
    else if (name.startsWith('decor-')) {
        if (input.decor != null)
            decor(input.decor);
        else if (name === 'decor-save')
            return fail('保存草稿须提供装修内容');
    }
    else if (name === 'coupon-create' || name === 'coupon-update') {
        text(input.name, 100);
        text(input.description, 3000, false);
        if (!['通用优惠', '续费优惠', '老带新优惠', '双人同行优惠'].includes(input.template))
            return fail('优惠券类型无效');
        if (input.campusId != null && !validId(input.campusId))
            return fail('校区编号无效');
        dates(input);
        const discount = decimal(input.discountAmount, 10, 1, 100000000000n), threshold = decimal(input.thresholdAmount, 10, 0, 100000000000n);
        if (threshold > 0n && threshold <= discount)
            return fail('满减门槛须大于优惠金额');
        integer(input.total, 1, 1000000);
        integer(input.maxPerUser, 1, 1000);
        if (input.maxPerUser > input.total)
            return fail('个人上限超过发行量');
        ids(input.courseIds, 0, 100);
    }
    return input;
}
function allowed(operation, permissions) { return (operation.permissionGroups || operation.permissions.map(permission => [permission])).every(group => group.some(permission => permissions.includes(permission))); }
async function run(args, client, identity) {
    const [name, ...rest] = args, op = operations.find(item => item.name === name);
    if (!op)
        return fail('不支持的商城命令', 'INVALID_ARGUMENT');
    if (!allowed(op, identity.permissions) || (op.scopes && !op.scopes.includes(identity.scope)))
        return fail('商城操作权限或数据范围不足', 'FORBIDDEN');
    let target = '', tail = rest;
    if (op.pathParameter) {
        target = rest[0] || '';
        tail = rest.slice(1);
        if (!validId(target))
            return fail('请提供有效编号', 'INVALID_ARGUMENT');
    }
    const flags = {}, keys = op.input ? ['--input', '--key'] : op.paged ? ['--page', '--size', ...(op.typeFilter ? ['--type'] : [])] : [];
    for (let index = 0; index < tail.length; index += 2) {
        const key = tail[index], item = tail[index + 1];
        if (!keys.includes(key) || !item || Object.hasOwn(flags, key))
            return fail('参数缺失、重复或不支持', 'INVALID_ARGUMENT');
        flags[key] = item;
    }
    let endpoint = op.endpoint.replace('{id}', encodeURIComponent(target));
    if (op.input) {
        if (!flags['--input'] || !flags['--key'])
            return fail('必须提供--input和--key', 'INVALID_ARGUMENT');
        const body = validate(name || '', businessClient.read(flags['--input']));
        return client.request(endpoint, { method: op.method, idempotencyKey: orders.parseKey(flags['--key']), body });
    }
    if (op.paged) {
        for (const flag of ['--page', '--size'])
            if (flags[flag] !== undefined && !/^[1-9][0-9]*$/.test(flags[flag]))
                return fail('分页参数无效');
        const page = Number(flags['--page'] || 1), size = Number(flags['--size'] || 50);
        integer(page, 1, 100000);
        integer(size, 1, 100);
        const query = new URLSearchParams({ page: String(page), [op.pageSizeKey || 'pageSize']: String(size) });
        if (flags['--type']) {
            if (!['group', 'joint'].includes(flags['--type']))
                return fail('活动类型无效');
            query.set('type', flags['--type']);
        }
        endpoint += '?' + query;
    }
    return client.request(endpoint, { method: 'GET' });
}
module.exports = { operations, validate, allowed, run };
