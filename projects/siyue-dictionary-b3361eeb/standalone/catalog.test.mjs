import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { build } from 'esbuild';
import { verifyPublicCatalog } from './verify-public-catalog.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const compiled = await build({
  stdin: { contents: `export {getCatalog,searchCatalog} from './lib/server-catalog';export {registrationRoles} from './lib/registration-options';`, resolveDir: root, loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', logLevel: 'silent',
});
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const { getCatalog, searchCatalog, registrationRoles } = module.exports;
const catalog = getCatalog();

test('server catalog preserves role, policy, source and guidance content for authenticated readers', () => {
  assert.equal(catalog.roles.length, 16);
  assert.equal(catalog.policies.length, 36);
  assert.equal(catalog.policyDocuments.length, 9);
  assert.equal(catalog.managementRoleIds.length, 6);
  assert.ok(catalog.managementRoleIds.includes('dept-head'));
  assert.ok(!catalog.managementRoleIds.includes('ai-dept'));
  const refs = new Set([...catalog.sources, ...catalog.policySources].map(source => source.id));
  assert.equal(refs.size, 100);
  for (const policy of catalog.policies) {
    for (const section of policy.sections) {
      assert.ok(section.paragraphs.length);
      for (const id of section.refs) assert.ok(refs.has(id), id);
    }
  }
});

test('registration labels remain in sync without bundling duties, scopes or sources', () => {
  assert.deepEqual(registrationRoles, catalog.roles.map(({ id, name, group, aliases }) => ({ id, name, group, aliases })));
  for (const role of registrationRoles) assert.deepEqual(Object.keys(role).sort(), ['aliases', 'group', 'id', 'name']);
});

test('server search keeps exact aliases, body matches and multi-term guidance matches', () => {
  assert.equal(searchCatalog('校长')[0].key, 'role:manager');
  assert.ok(searchCatalog('补卡').some(result => result.type === 'policy'));
  const result = searchCatalog('AI部 净释放工时').find(result => result.key === 'role:ai-dept');
  assert.equal(result.section, 'guidance');
  assert.ok(result.excerpt.includes('净释放工时'));
  assert.deepEqual(searchCatalog(''), []);
  assert.deepEqual(searchCatalog('考勤 无此关键词abc123'), []);
});

test('public application graph cannot import internal reference content', async () => {
  const frontend = await build({
    entryPoints: [join(root, 'standalone/main.tsx')], bundle: true, write: false,
    platform: 'browser', format: 'esm', packages: 'external', jsx: 'automatic',
    loader: { '.css': 'empty' }, metafile: true, logLevel: 'silent',
    define: { __SIYUE_BASE_PATH__: '"/"' },
  });
  const forbidden = /(?:^|\/)lib\/(?:dictionary-data|data-roles|data-training|data-academic|policy-data|policies-core|policies-extra|role-guidance|management-guidance|staff-guidance|knowledge-search|server-catalog)\.ts$/;
  assert.deepEqual(Object.keys(frontend.metafile.inputs).filter(path => forbidden.test(path)), []);
});

test('release guard detects source paragraphs but permits ordinary shared UI vocabulary', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'siyue-public-catalog-'));
  try {
    await writeFile(join(directory, 'app.js'), 'const labels = ["教务", "考勤", "工作资料"];');
    const good = await verifyPublicCatalog(directory, catalog);
    assert.equal(good.files, 1);
    assert.ok(good.protectedPhrases > 100);
    await writeFile(join(directory, 'leaked.js'), JSON.stringify(catalog.roles.find(role => role.scope.length >= 40).scope));
    await assert.rejects(verifyPublicCatalog(directory, catalog), /公开构建包含受限参考正文/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
