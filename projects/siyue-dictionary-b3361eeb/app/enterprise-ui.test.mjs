import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

const compiled = await build({
  stdin: { contents: `export * from './app/enterprise-data';export * from './app/enterprise-library';export * from './app/organization-admin';`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', logLevel: 'silent', loader: { '.css': 'empty' },
});
const loaded = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), loaded, loaded.exports);
const { sharingSummary, privateSharing, validateSharing, cleanSharing, validateKnowledgeFile, EnterpriseLibrary, OrganizationAdmin } = loaded.exports;
const units = [{ id: 'dept', kind: 'department', name: '教务' }, { id: 'campus', kind: 'campus', name: '历下校区' }];
const members = [{ id: 'person', name: '王老师', memberships: [] }];

test('department and campus intersection is described as simultaneous membership, not the union', () => {
  const base = { mode: 'selected', unitIds: ['dept', 'campus'], userIds: [], match: 'all' };
  const intersection = sharingSummary(base, units, members), union = sharingSummary({ ...base, match: 'any' }, units, members);
  assert.match(intersection, /同时属于「教务」和「历下校区」/);
  assert.doesNotMatch(intersection, /任一/); assert.match(union, /任一范围内/);
  assert.match(sharingSummary({ ...base, userIds: ['person'] }, units, members), /以及指定人员：王老师/);
});
test('sharing can start private and pending employees cannot select a wider audience', () => {
  assert.deepEqual(privateSharing(), { mode: 'private', userIds: [], unitIds: [], match: 'any' });
  assert.equal(validateSharing(privateSharing(), false), '');
  assert.ok(validateSharing({ ...privateSharing(), mode: 'employees' }, false));
  assert.ok(validateSharing({ ...privateSharing(), mode: 'selected' }, true));
  assert.equal(validateSharing({ ...privateSharing(), mode: 'selected', unitIds: ['dept'] }, true), '');
  assert.equal(sharingSummary({ ...privateSharing(), mode: 'employees' }, units, members), '全体已确认员工');
});
test('switching back to private removes obsolete recipients from the saved input', () => {
  assert.deepEqual(cleanSharing({ mode: 'private', unitIds: ['dept'], userIds: ['person'], match: 'all' }), privateSharing());
  assert.deepEqual(cleanSharing({ mode: 'selected', unitIds: ['dept', 'dept'], userIds: ['person', 'person'], match: 'all' }), { mode: 'selected', unitIds: ['dept'], userIds: ['person'], match: 'all' });
});
test('missing names are not rendered as raw internal identifiers', () => {
  const text = sharingSummary({ mode: 'selected', userIds: ['hidden-user-id'], unitIds: ['hidden-unit-id'], match: 'any' }, [], []);
  assert.doesNotMatch(text, /hidden-/); assert.match(text, /指定人员/); assert.match(text, /指定组织/);
});
test('file picker accepts bounded work documents and refuses active formats or empty files', () => {
  for (const name of ['说明.PDF', '资料.docx', '课时.xlsx', '经验.md', '笔记.txt', '图片.png', '照片.jpeg', '图片.webp']) assert.equal(validateKnowledgeFile({ name, size: 1024 }), '', name);
  for (const name of ['网页.html', '程序.exe', '矢量.svg', '文档.pdf.html']) assert.ok(validateKnowledgeFile({ name, size: 1024 }), name);
  assert.ok(validateKnowledgeFile({ name: '文档.pdf', size: 0 }));
  assert.ok(validateKnowledgeFile({ name: '文档.pdf', size: 20 * 1024 * 1024 + 1 }));
  assert.equal(validateKnowledgeFile({ name: '文档.pdf', size: 20 * 1024 * 1024 }), '');
});
test('suspended accounts render neither document controls nor an upload entry', () => {
  const html = renderToStaticMarkup(React.createElement(EnterpriseLibrary, { employeeId: 'employee', access: { status: 'suspended', version: 1, memberships: [], canManageKnowledge: false }, onSelect() {}, onUnauthorized() {} }));
  assert.match(html, /账号已停用/); assert.doesNotMatch(html, /上传资料|我的资料|<button/);
});
test('pending accounts get a clear private-draft state and no cached member or document names', () => {
  const html = renderToStaticMarkup(React.createElement(EnterpriseLibrary, { employeeId: 'employee', access: { status: 'pending', version: 1, memberships: [], canManageKnowledge: false }, onSelect() {}, onUnauthorized() {} }));
  assert.match(html, /身份待确认/); assert.match(html, /保存个人资料/);
  assert.doesNotMatch(html, /王老师|历下校区/); assert.match(html, /disabled=""[^>]*><svg[^]*上传资料/);
});
test('organization view starts without any preloaded employee records or names', () => {
  const html = renderToStaticMarkup(React.createElement(OrganizationAdmin, { employeeId: 'admin', onUnauthorized() {} }));
  assert.match(html, /员工身份与权限/); assert.match(html, /查找姓名、部门或校区/); assert.match(html, /待确认/);
  assert.doesNotMatch(html, /王老师|历下校区|password|employee-id/);
});
