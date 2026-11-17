'use strict';
const identity = require('../cli/java-customer-identity.cjs');
const identifier = { type: 'string', pattern: '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$' };
const text = maxLength => ({ type: 'string', minLength: 1, maxLength });
const version = { type: 'integer', minimum: 1 };
const object = (properties, required = []) => ({ type: 'object', properties, required, additionalProperties: false });
const inputs = {
  'student-identifier-create': object({ namespace: text(100), value: text(120), expectedStudentVersion: version }, ['namespace', 'value', 'expectedStudentVersion']),
  'student-identifier-deactivate': object({ reason: text(1000), expectedVersion: version }, ['reason', 'expectedVersion']),
  'student-name-index-rebuild': object({ afterId: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 200 } }, ['afterId', 'limit']),
  'student-number-index-rebuild': object({ afterId: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 200 } }, ['afterId', 'limit']),
};
function tools(me = {}) {
  const available = identity.operations.filter(operation => me.permissions?.includes(operation.permission)
    && (operation.index ? me.scope === 'all' : me.permissions.includes('crm.customer.read')));
  if (!available.length) return [];
  return [
    { name: 'siyue_preview_identity_operation', title: '核对学号备案与检索维护',
      description: '读取当前授权目录或索引状态后形成私有办理计划；该核对不是服务端预留。学号和来源按原值保留，不能用学生系统编号代替或猜造历史号码。检索维护只允许专门权限且all范围，每批最多200条。',
      inputSchema: { type: 'object', oneOf: available.map(operation => object({ operation: { type: 'string', const: operation.name }, input: inputs[operation.name],
        ...(operation.index ? {} : { customerId: identifier, studentId: identifier }), ...(operation.target ? { identifierId: identifier } : {}) },
      ['operation', 'input', ...(operation.index ? [] : ['customerId', 'studentId']), ...(operation.target ? ['identifierId'] : [])])) },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } },
    { name: 'siyue_execute_identity_operation', title: '提交已核对的身份办理',
      description: '提交原planId。断网或超时继续使用原planId；每个维护批次单独核对，使用服务端返回的下一游标，不修改旧计划。Java重新检查身份、权限、范围及版本。',
      inputSchema: object({ planId: text(100) }, ['planId']),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false } },
  ];
}
module.exports = { tools, inputs };
