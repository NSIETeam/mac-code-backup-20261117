'use strict';
const routing = require('../cli/java-task-routing.cjs');
const identifier = { type: 'string', pattern: '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$' };
const optionalId = { ...identifier, type: ['string', 'null'] };
const version = { type: 'integer', minimum: 1, maximum: Number.MAX_SAFE_INTEGER };
const text = maxLength => ({ type: 'string', minLength: 1, maxLength });
const time = { type: 'string', format: 'date-time', pattern: 'Z$' };
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const array = (items, maxItems, minItems = 0) => ({ type: 'array', items, minItems, maxItems });
const quota = object({ ownerId: identifier, count: { type: 'integer', minimum: 0, maximum: 500 } }, ['ownerId', 'count']);
const plan = object({ strategy: { type: 'string', enum: ['even', 'quota'] }, ownerIds: array(identifier, 100, 1),
  quotas: { ...array(quota, 100), type: ['array', 'null'] } }, ['strategy', 'ownerIds']);
const source = object({ type: { type: 'string', enum: ['lead', 'touchpoint'] }, id: identifier,
  version: { ...version, type: ['integer', 'null'] } }, ['type', 'id']);
source.oneOf = [{ properties: { type: { const: 'lead' }, version }, required: ['version'] },
  { properties: { type: { const: 'touchpoint' }, version: { type: 'null' } } }];
const target = object({ customerId: identifier, studentId: optionalId,
  subject: { ...text(100), type: ['string', 'null'] }, term: { ...text(100), type: ['string', 'null'] },
  needIdentity: { ...object({ departmentId: identifier, projectId: optionalId }, ['departmentId']), type: ['object', 'null'] },
  sources: array(source, 100) }, ['customerId']);
const selection = array(object({ taskId: identifier, expectedVersion: version }, ['taskId', 'expectedVersion']), 500, 1);
const delivery = { type: 'object', oneOf: [
  object({ mode: { const: 'supervisor', type: 'string' }, supervisorId: identifier, employeePlan: { type: 'null' } }, ['mode', 'supervisorId']),
  object({ mode: { const: 'direct', type: 'string' }, supervisorId: { type: 'null' }, employeePlan: plan }, ['mode', 'employeePlan']),
] };
const inputs = {
  create: object({ title: text(200), taskType: { const: 'marketing', type: 'string' }, campusId: identifier,
    activityId: optionalId, startsAt: time, dueAt: time, delivery, targets: array(target, 500, 1),
    note: { ...text(5000), type: ['string', 'null'] } }, ['title', 'taskType', 'campusId', 'startsAt', 'dueAt', 'delivery', 'targets']),
  allocation: object({ expectedManagementVersion: version, selection, ...plan.properties,
    sourceOwnerPolicy: { type: 'string', enum: ['none', 'source_lead_owner_first'] } },
  ['expectedManagementVersion', 'selection', 'strategy', 'ownerIds', 'sourceOwnerPolicy']),
  recall: object({ expectedManagementVersion: version, selection, reason: text(1000) }, ['expectedManagementVersion', 'selection', 'reason']),
  transfer: object({ expectedManagementVersion: version, supervisorId: identifier, reason: text(1000) }, ['expectedManagementVersion', 'supervisorId', 'reason']),
  schedule: object({ expectedManagementVersion: version, startsAt: time, dueAt: time, reason: text(1000) }, ['expectedManagementVersion', 'startsAt', 'dueAt', 'reason']),
  cancel: object({ expectedManagementVersion: version, reason: text(1000) }, ['expectedManagementVersion', 'reason']),
};
function tools(me = {}) {
  const available = routing.operations.filter(operation => operation.permissions.every(permission => me.permissions?.includes(permission)));
  if (!available.length) return [];
  return [
    { name: 'siyue_preview_task_routing', title: '预览主管任务分派',
      description: '真实Java服务端预览创建、部分分派、回收、转主管、调整日期或取消。来源线索负责人优先只能用于平均分派，实际接收人可能不在候选员工列表中；必须核对逐条依据与阻止项，不根据客户负责人推算。只生成私有计划，不提交。',
      inputSchema: { type: 'object', oneOf: available.map(operation => object({
        operation: { type: 'string', const: operation.name }, input: inputs[operation.action],
        ...(operation.action === 'create' ? {} : { batchId: identifier }),
      }, ['operation', 'input', ...(operation.action === 'create' ? [] : ['batchId'])])) },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } },
    { name: 'siyue_execute_task_routing', title: '提交主管任务分派计划',
      description: '仅提交原planId保存的输入、完整服务端预览和幂等键；未知结果重复使用原planId。left_scope表示已提交且当前无读取范围，不能改键再提交。服务端仍复核当前操作权限，不能用旧计划越权。',
      inputSchema: object({ planId: { type: 'string', pattern: '^[0-9a-f-]{36}$' } }, ['planId']),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false } },
  ];
}
module.exports = { tools, inputs };
