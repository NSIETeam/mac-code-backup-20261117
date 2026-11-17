'use strict';
const needs = require('../cli/java-crm-needs.cjs');
const identifier = { type: 'string', pattern: '^[A-Za-z0-9_-]{1,120}$' };
const optionalId = { anyOf: [identifier, { type: 'null' }] };
const text = maxLength => ({ type: 'string', minLength: 1, maxLength });
const version = { type: 'integer', minimum: 1 };
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const profile = object(Object.fromEntries(needs.profileFields.map(key => [key, optionalId])));
const schemas = {
  'need-intake': object({ customerId: identifier, studentId: optionalId, sourceLeadId: optionalId, activityId: optionalId,
    title: text(200), subject: text(100), term: text(100), identity: object({ departmentId: identifier, projectId: optionalId }, ['departmentId']),
    profile, detail: text(5000), ownerId: optionalId }, ['customerId', 'title', 'subject', 'term', 'identity', 'profile', 'detail']),
  'need-profile': object({ profile, expectedVersion: version, expectedCatalogVersion: { type: 'integer', minimum: 1 } },
    ['profile', 'expectedVersion', 'expectedCatalogVersion']),
  'need-judgment': object({ judgmentId: identifier, reasonId: optionalId, detail: text(1000), expectedVersion: version,
    expectedCatalogVersion: { type: 'integer', minimum: 1 } }, ['judgmentId', 'detail', 'expectedVersion', 'expectedCatalogVersion']),
  'need-assign': object({ ownerId: identifier, reason: text(1000), expectedVersion: version }, ['ownerId', 'reason', 'expectedVersion']),
  'need-option-create': object({ kind: { type: 'string', enum: needs.kinds }, code: { type: 'string', pattern: '^[A-Za-z0-9][A-Za-z0-9_.-]{0,59}$' },
    label: text(100), parentId: optionalId, campusId: optionalId,
    projectMode: { type: ['string', 'null'], enum: ['required', 'not_used', null] },
    effect: { type: ['string', 'null'], enum: ['continue', 'close', null] } }, ['kind', 'code', 'label']),
  'need-option-update': object({ label: text(100), active: { type: 'boolean' }, expectedVersion: version }, ['label', 'active', 'expectedVersion']),
  'need-notification-read': object({ expectedVersion: version }, ['expectedVersion']),
};
const decision = object({ action: { type: 'string', enum: ['create', 'reuse', 'classify_existing', 'create_distinct'] },
  targetRef: optionalId, reason: { type: ['string', 'null'], maxLength: 1000 } }, ['action']);
function tools(permissions = []) {
  const available = needs.operations.filter(operation => permissions.includes(operation.permission));
  if (!available.length) return [];
  return [
    { name: 'siyue_preview_need_operation', title: '核对商机需求办理',
      description: '接待使用服务端查重预览；其他操作读取当前资料后形成客户端核对页，服务端提交时检查版本和权限。接待可省略 decision 先看候选，不生成可提交计划；选定处理方式后再次核对。仅返回私有 planId，不写业务数据。',
      inputSchema: { type: 'object', oneOf: available.map(operation => object({ operation: { const: operation.name, type: 'string' }, input: schemas[operation.name],
        ...(operation.target ? { targetId: identifier } : {}), ...(operation.name === 'need-intake' ? { decision } : {}) },
      ['operation', 'input', ...(operation.target ? ['targetId'] : [])])) },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } },
    { name: 'siyue_execute_need_operation', title: '提交已核对的需求办理',
      description: '仅提交预览生成的原 planId。超时或断网继续使用同一 planId，不重新新建。权限与范围由 Java 服务端复核。',
      inputSchema: object({ planId: text(100) }, ['planId']),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false } },
  ];
}
module.exports = { tools, schemas };
