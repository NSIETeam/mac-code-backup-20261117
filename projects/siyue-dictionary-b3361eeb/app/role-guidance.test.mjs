import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

const compiled = await build({
  stdin: { contents: `export {roles,sources,processes,candidates} from './lib/dictionary-data';export {roleGuidance,isManagementGuidance} from './lib/role-guidance';export {managementGuidance} from './lib/management-guidance';export {staffGuidance} from './lib/staff-guidance';export {RoleGuidanceSection} from './app/role-guidance-section';`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', logLevel: 'silent',
});
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { roles, sources, processes, candidates, roleGuidance, isManagementGuidance, managementGuidance, staffGuidance, RoleGuidanceSection } = module.exports;

test('all sixteen current roles have exactly one separate guidance entry', () => {
  assert.equal(roles.length, 16);
  assert.deepEqual(Object.keys(roleGuidance).sort(), roles.map(role => role.id).sort());
  assert.deepEqual(Object.keys(managementGuidance).filter(id => Object.hasOwn(staffGuidance, id)), []);
  assert.equal(Object.keys(managementGuidance).length, 6); assert.equal(Object.keys(staffGuidance).length, 10);
});
test('every guidance entry contains proposed requirements, checks, handoffs and confirmation questions', () => {
  for (const [id, guidance] of Object.entries(roleGuidance)) {
    assert.match(guidance.summary, /建议|待确认/, id);
    for (const key of ['requirements', 'checks', 'handoffs']) {
      assert.ok(guidance[key].length > 0, `${id}.${key}`);
      assert.ok(guidance[key].every(item => item.title.trim() && item.text.trim()), `${id}.${key}`);
    }
    assert.ok(guidance.questions.length > 0 && guidance.questions.every(question => question.trim()), id);
    assert.equal(Object.hasOwn(guidance, 'refs'), false);
  }
});
test('management priority depends on the selected role and does not infer a user department', () => {
  for (const role of roles) assert.equal(isManagementGuidance(role.id), Object.hasOwn(managementGuidance, role.id));
  assert.equal(isManagementGuidance('ai-dept'), false);
  assert.equal(isManagementGuidance('dept-head'), true);
  const requirements = roleGuidance['dept-head'].requirements;
  assert.ok(requirements.some(item => item.title.includes('部门负责人')));
  assert.ok(requirements.some(item => item.title.includes('集团负责人')));
});
test('the retired principal role remains merged into manager', () => {
  assert.ok(!roles.some(role => role.id === 'principal'));
  assert.equal(roleGuidance.principal, undefined);
  assert.ok(roles.find(role => role.id === 'manager').aliases.includes('校长'));
  assert.ok(roleGuidance.manager);
});
test('all original evidence references still resolve and remain separate from proposed guidance', () => {
  const sourceIds = new Set(sources.map(source => source.id));
  const refs = [
    ...roles.flatMap(role => [...role.duties, ...role.schedule, ...role.checks, ...role.handoffs].flatMap(item => item.refs)),
    ...processes.flatMap(process => process.steps.flatMap(step => step.refs)),
    ...candidates.flatMap(candidate => candidate.refs),
  ];
  assert.ok(refs.length > 0); assert.deepEqual([...new Set(refs.filter(ref => !sourceIds.has(ref)))], []);
  assert.ok(roles.every(role => !Object.hasOwn(role, 'guidance')));
});
test('guidance renders collapsed with an explicit proposal notice and all four groups', () => {
  for (const role of roles) {
    const html = renderToStaticMarkup(React.createElement(RoleGuidanceSection, { guidance: roleGuidance[role.id] }));
    assert.match(html, /^<details class="role-guidance">/);
    assert.doesNotMatch(html, /^<details[^>]*\bopen(?:=|\s|>)/);
    for (const text of ['岗位完善建议', '以下为建议，待集团确认；不代表现行制度。', '优先复用已有业务记录，不重复填表。', '职责要求', '检查口径', '交接', '需确认的问题']) assert.ok(html.includes(text), `${role.id}: ${text}`);
    assert.doesNotMatch(html, /source-link|原文依据/);
    assert.doesNotMatch(html, /<(?:p|li)[^>]*>(?:建议待确认：|建议先确认：)/u);
  }
  const guidance = { ...roleGuidance.academic, summary: '建议待确认：保留段内的建议先确认：原文。' };
  const html = renderToStaticMarkup(React.createElement(RoleGuidanceSection, { guidance }));
  assert.ok(html.includes('保留段内的建议先确认：原文。'));
  assert.equal(guidance.summary, '建议待确认：保留段内的建议先确认：原文。');
});
