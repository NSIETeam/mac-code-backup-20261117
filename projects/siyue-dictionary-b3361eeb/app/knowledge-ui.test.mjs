import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

const compiled = await build({
  stdin: { contents: `export * from './app/knowledge-search-ui';export * from './app/policy-reader';export * from './app/role-guidance-section';`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', logLevel: 'silent',
});
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { readKnowledgeLocation, knowledgeLocation, KnowledgeSearchResults, PolicyReader, RoleGuidanceSection } = module.exports;
const catalog = { roleIds: ['academic', 'manager'], processIds: ['enrollment'], policyIds: ['leave', 'refund'], defaultRoleId: 'academic' };
const read = url => readKnowledgeLocation(new URL(url, 'https://example.test/work/').search, catalog);

test('old role links, retired principal alias and process links remain readable', () => {
  assert.deepEqual(read('?role=principal'), { view: 'dictionary', role: 'manager', query: '', section: undefined });
  assert.equal(read('?role=academic').role, 'academic');
  assert.deepEqual(read('?process=enrollment'), { view: 'processes', process: 'enrollment', query: '' });
});
test('search, selected policy and browser history entries can each be restored independently', () => {
  const initial = 'https://example.test/work/?role=academic';
  const search = knowledgeLocation(initial, 'dictionary', { role: 'academic', q: '请假' });
  const selected = knowledgeLocation(search.href, 'policies', { policy: 'leave' });
  assert.equal(selected.pathname, '/work/');
  assert.deepEqual(read(selected), { view: 'policies', policy: 'leave', query: '' });
  assert.equal(read(search).query, '请假');
  assert.equal(read(search).role, 'academic');
  assert.equal(read(initial).query, '');
  assert.equal(read(initial).role, 'academic');
});
test('work document links restore only a valid identifier and leave permission decisions to the server', () => {
  const id='e40bd94a-13c6-48dd-847d-8c128d125e15';
  const url=knowledgeLocation('https://example.test/work/?policy=leave&q=旧查询','library',{document:id});
  assert.deepEqual(read(url),{view:'library',query:'',document:id});
  assert.equal(read('?document=../../secret').view,'dictionary');
  assert.equal(read('?view=organization').view,'organization');
  const cleared=knowledgeLocation(url.href,'library',{document:''});
  assert.equal(cleared.search,'?view=library');
});
test('permission-filtered uploaded document results render as work material with escaped excerpts', () => {
  const html=renderToStaticMarkup(React.createElement(KnowledgeSearchResults,{results:[{key:'document:1',type:'document',id:'1',title:'部门交接',subtitle:'工作资料 · 张老师',excerpt:'<img src=x onerror=alert(1)>'}],onSelect(){}}));
  assert.ok(html.includes('工作资料'));assert.ok(html.includes('部门交接'));
  assert.match(html,/&lt;img/);assert.doesNotMatch(html,/<img/);
});
test('role hits restore exactly the section needing expansion without leaking it onto other views', () => {
  const hit = knowledgeLocation('https://example.test/work/?q=复盘', 'dictionary', { role: 'manager', section: 'guidance' });
  assert.equal(read(hit).section, 'guidance');
  assert.equal(read(hit).query, '');
  const next = knowledgeLocation(hit.href, 'policies', { policy: 'refund', section: 'guidance' });
  assert.equal(next.searchParams.has('role'), false);
  assert.equal(next.searchParams.has('section'), false);
  assert.equal(read('?role=academic&section=requirements').section, 'requirements');
});
test('invalid IDs and sections cannot become navigation targets and oversized queries are bounded', () => {
  const invalid = read('?policy=missing&role=javascript%3Aalert(1)&section=constructor&view=constructor');
  assert.deepEqual(invalid, { view: 'dictionary', role: 'academic', query: '' });
  assert.equal(read('?role=manager&section=constructor').section, undefined);
  assert.equal(read(`?q=${'学'.repeat(250)}`).query.length, 200);
  assert.equal(read('?q=%E0%A4%A').view, 'dictionary');
  const cleared = knowledgeLocation('https://example.test/work/?role=manager&q=旧词&section=guidance', 'members', { q: '   ' });
  assert.equal(cleared.search, '?view=members');
});
test('search rows have a type, excerpt and native keyboard-focusable button; unknown text is escaped', () => {
  const html = renderToStaticMarkup(React.createElement(KnowledgeSearchResults, { results: [{ key: 'role:academic', type: 'role', id: 'academic', title: '教务', subtitle: '岗位说明', excerpt: '<script>bad()</script>', section: 'guidance' }], onSelect() {} }));
  assert.match(html, /1 条结果/);assert.match(html, /<button/);assert.match(html, /岗位 · 完善建议/);
  assert.match(html, /&lt;script&gt;/);assert.doesNotMatch(html, /<script>/);
  const empty = renderToStaticMarkup(React.createElement(KnowledgeSearchResults, { results: [], onSelect() {} }));
  assert.match(empty, /没有找到相关内容/);assert.doesNotMatch(empty, /<button/);
});
test('policy rendering keeps status, version, paragraphs, refs and open questions separate', () => {
  const policy = { id: 'leave', title: '请假制度', category: '考勤', documentId: 'rules', summary: '摘要', keywords: [], audience: ['员工'], roleIds: ['academic'], status: '初稿 · 待确认', sections: [{ title: '办理', paragraphs: ['第一段', '<img src=x onerror=alert(1)>'], refs: ['policy-ref'] }], questions: ['批准人待确认'] };
  const document = { id: 'rules', title: '规则', file: '制度.docx', kind: '制度与规范', versionLabel: '日期待核实', note: '适用版本待确认' };
  const html = renderToStaticMarkup(React.createElement(PolicyReader, { policy, document, roleNames: { academic: '教务' }, onSources() {}, onRole() {} }));
  for (const text of ['初稿 · 待确认', '原文件：制度.docx', '版本：日期待核实', '第一段', '资料依据', '待确认', '批准人待确认', '相关岗位', '教务']) assert.ok(html.includes(text), text);
  assert.match(html, /&lt;img/);assert.doesNotMatch(html, /<img/);assert.doesNotMatch(html, /href=.*\.docx/);
  assert.doesNotMatch(html, /摘要/);
  assert.match(html, /<details class="policy-document-notes"><summary>资料说明/);
  assert.match(html, /<details class="policy-document-notes">[\s\S]*版本：日期待核实[\s\S]*适用版本待确认[\s\S]*<\/details>/);
  assert.doesNotMatch(html, /<details[^>]*\bopen(?:=|\s|>)/);
  const everyone = renderToStaticMarkup(React.createElement(PolicyReader, { policy: { ...policy, audience: ['全体员工'] }, document, roleNames: { academic: '教务' }, onSources() {}, onRole() {} }));
  assert.match(everyone, /适用对象：全体员工/);assert.doesNotMatch(everyone, /相关岗位/);
  for (const text of ['第一段', '资料依据', '批准人待确认']) assert.ok(everyone.includes(text), text);
});
test('search can expand guidance while ordinary role browsing remains collapsed', () => {
  const guidance = { summary: '建议待确认：摘要', requirements: [], checks: [], handoffs: [], questions: [] };
  const expanded = renderToStaticMarkup(React.createElement(RoleGuidanceSection, { guidance, open: true }));
  const collapsed = renderToStaticMarkup(React.createElement(RoleGuidanceSection, { guidance }));
  assert.match(expanded, /^<details[^>]* open=""/);
  assert.doesNotMatch(collapsed, /^<details[^>]* open=/);
  assert.match(expanded, /以下为建议，待集团确认；不代表现行制度/);
});
