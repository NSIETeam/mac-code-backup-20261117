import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const compiled = await build({
  stdin: { contents: `export {searchKnowledge} from './lib/knowledge-search';export {policies,policyDocuments,policySources} from './lib/policy-data';export {roles,sources,processes} from './lib/dictionary-data';`, resolveDir: fileURLToPath(new URL('..', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', logLevel: 'silent',
});
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { searchKnowledge, policies, policyDocuments, policySources, roles, sources, processes } = module.exports;

test('every reference-library topic resolves its document, roles and precise source locations', () => {
  assert.ok(policyDocuments.length >= 9);
  assert.ok(policies.length >= 30);
  const ids = values => new Set(values.map(value => value.id));
  assert.equal(ids(policyDocuments).size, policyDocuments.length);
  assert.equal(ids(policies).size, policies.length);
  const allSources = [...sources, ...policySources];
  assert.equal(ids(allSources).size, allSources.length);
  const documents = ids(policyDocuments), roleIds = ids(roles), sourceIds = ids(allSources);
  for (const policy of policies) {
    assert.ok(documents.has(policy.documentId), policy.id);
    assert.match(policy.status, /待确认|历史参考/, policy.id);
    assert.ok(policy.title && policy.summary && policy.category, policy.id);
    assert.ok(policy.sections.length > 0, policy.id);
    for (const id of policy.roleIds) assert.ok(roleIds.has(id), `${policy.id}: ${id}`);
    for (const section of policy.sections) {
      assert.ok(section.paragraphs.length && section.paragraphs.every(text => text.trim()), policy.id);
      assert.ok(section.refs.length, policy.id);
      for (const ref of section.refs) assert.ok(sourceIds.has(ref), `${policy.id}: ${ref}`);
    }
  }
  for (const source of policySources) assert.ok(source.file && source.locator && source.excerpt, source.id);
});

test('exact role names and retired aliases rank before incidental body mentions', () => {
  assert.equal(searchKnowledge('教务')[0]?.key, 'role:academic');
  assert.equal(searchKnowledge('校长')[0]?.key, 'role:manager');
  assert.equal(searchKnowledge('ａｉ部')[0]?.key, 'role:ai-dept');
});

test('search covers proposed requirements and identifies the collapsed section to reveal', () => {
  for (const query of ['净释放工时', 'AI部 净释放工时']) {
    const result = searchKnowledge(query).find(item => item.key === 'role:ai-dept');
    assert.ok(result, query);
    assert.equal(result.section, 'guidance', query);
    assert.ok(result.excerpt.includes('净释放工时'), query);
  }
});

test('search finds shared staff policies and newly supplied assistant workflows', () => {
  for (const query of ['日报', '考勤', '补卡', '新生回访', '学习助教', '生活助教']) {
    assert.ok(searchKnowledge(query).some(item => item.type === 'policy'), query);
  }
  assert.ok(searchKnowledge('退费').some(item => item.type === 'process'));
});

test('all query terms must match, including body-only matches, and unknown text returns no results', () => {
  assert.deepEqual(searchKnowledge(''), []);
  assert.deepEqual(searchKnowledge('  \n  '), []);
  assert.deepEqual(searchKnowledge('日报 不存在条款随机标记zz'), []);
  assert.doesNotThrow(() => searchKnowledge('[.*+?'));
  const results = searchKnowledge('24:00');
  assert.ok(results.some(item => item.type === 'policy' && item.excerpt.includes('24:00')));
});

test('results are unique reference entries and disclose no private ledger fields', () => {
  const allowed = new Set(['key', 'type', 'id', 'title', 'subtitle', 'excerpt', 'section']);
  const counts = { role: roles.length, policy: policies.length, process: processes.length };
  for (const query of ['教务', '退费', '沟通', '课时']) {
    const results = searchKnowledge(query);
    assert.equal(new Set(results.map(item => item.key)).size, results.length);
    for (const item of results) {
      assert.ok(item.type in counts);
      assert.ok(Object.keys(item).every(key => allowed.has(key)));
      assert.ok(item.excerpt.length <= 147);
    }
  }
});
