'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {sync} = require('./sync-tools.cjs');
const {FILES} = require('./build-tools.cjs');
const source = path.resolve(__dirname, '..');
function fixture(t) {
  const root = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'siyue-sync-test-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  for (const name of [...FILES, 'scripts/build-tools.cjs']) {
    const target = path.join(root,name); fs.mkdirSync(path.dirname(target),{recursive:true});
    fs.copyFileSync(path.join(source,name),target);
  }
  fs.cpSync(path.join(source,'tools-typescript','src'),path.join(root,'tools-typescript','src'),{recursive:true});
  fs.copyFileSync(path.join(source,'tools-typescript','tsconfig.json'),path.join(root,'tools-typescript','tsconfig.json'));
  return root;
}
test('runtime regeneration uses strict sources and leaves unrelated files untouched', t => {
  const root=fixture(t), runtime=path.join(root,'cli','java-leads.cjs');
  fs.writeFileSync(runtime,'stale generated output');
  fs.writeFileSync(path.join(root,'unrelated.txt'),'keep');
  sync(root);
  assert.match(fs.readFileSync(runtime,'utf8'),/batch-assign/);
  assert.equal(fs.readFileSync(path.join(root,'unrelated.txt'),'utf8'),'keep');
});
test('a source type error prevents every runtime replacement', t => {
  const root=fixture(t);
  const names=FILES.filter(name=>name.endsWith('.cjs'));
  const before=names.map(name=>fs.readFileSync(path.join(root,name),'utf8'));
  fs.writeFileSync(path.join(root,'tools-typescript','src','invalid.cts'),'const value: number = "bad"; export = value;');
  assert.throws(()=>sync(root),/TS2322/);
  assert.deepEqual(names.map(name=>fs.readFileSync(path.join(root,name),'utf8')),before);
});
