const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const compilerOptions = { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2017 };
function compile(path) { return ts.transpileModule(fs.readFileSync(path, 'utf8'), { compilerOptions }).outputText; }
const helpers = {};
vm.runInNewContext(compile('miniprogram/utils/queue-estimate.ts'), { exports: helpers });
const format = helpers.formatQueueEstimate;
const queued = { state: 'queued', ahead: 3, waitSeconds: 121, basis: 'observed' };

test('queued estimates retain the real queue count and round up to minutes', () => {
  assert.equal(format(queued), '前面还有 3 个任务 · 预计约 3 分钟开始');
  assert.equal(format({ ...queued, ahead: 0, waitSeconds: 0 }), '即将开始');
  assert.equal(format({ ...queued, ahead: 0, waitSeconds: 0, basis: 'initial' }), '即将开始（初步估算）');
  assert.equal(format({ ...queued, ahead: null }), '正在排队 · 预计约 3 分钟开始');
});
test('processing estimates use remaining work and explicitly label initial estimates', () => {
  assert.equal(format({ ...queued, state: 'processing', remainingSeconds: 61 }), '预计还需约 2 分钟');
  assert.equal(format({ ...queued, state: 'running', remainingSeconds: 61 }), '预计还需约 2 分钟');
  assert.equal(format({ ...queued, state: 'running', remainingSeconds: 0 }), '正在完成收尾');
  assert.equal(format({ ...queued, state: 'processing', remainingSeconds: 30, basis: 'initial' }), '预计还需约 1 分钟（初步估算）');
});
test('unknown, absent and invalid seconds never become zero or a fabricated estimate', () => {
  for (const value of [null, undefined, NaN, Infinity, -1, '60']) {
    assert.equal(format({ ...queued, waitSeconds: value }), '前面还有 3 个任务 · 暂无法估算开始时间');
    assert.equal(format({ ...queued, state: 'processing', remainingSeconds: value }), '正在处理，暂无法估算剩余时间');
  }
  assert.equal(format({ ...queued, basis: 'unknown' }), '前面还有 3 个任务 · 暂无法估算开始时间');
  assert.equal(format({ ...queued, ahead: -1 }), '正在排队 · 预计约 3 分钟开始');
});
test('long estimates use hours and minutes instead of a long minute count', () => {
  assert.equal(format({ ...queued, waitSeconds: 3600 }), '前面还有 3 个任务 · 预计约 1 小时开始');
  assert.equal(format({ ...queued, state: 'processing', remainingSeconds: 7261 }), '预计还需约 2 小时 2 分钟');
});
test('old telemetry does not create a client countdown or independently mark work delayed', () => {
  assert.equal(format({ ...queued, updatedAt: '2000-01-01T00:00:00Z' }), format(queued));
  assert.equal(format({ ...queued, updatedAt: 'invalid' }), format(queued));
  assert.equal(format({ ...queued, state: 'processing', remainingSeconds: 0, delayed: true }), '耗时超过预估，仍在继续处理');
  assert.equal(format({ ...queued, delayed: true }), '前面还有 3 个任务 · 等待时间超过预估，仍在排队');
});
test('completed, failed or missing queues have no active waiting estimate', () => {
  for (const state of ['done', 'ready', 'draft', 'published', 'failed']) assert.equal(format({ ...queued, state }), '');
  assert.equal(format(null), ''); assert.equal(format(undefined), '');
});
test('waiting dependencies and deferred retries remain visible without fabricated seconds', () => {
  assert.equal(format({ state: 'waiting' }), '等待前置步骤完成，暂无法估算等待时间');
  assert.equal(format({ state: 'retrying', remainingSeconds: 0 }), '正在等待重试，暂无法估算等待时间');
});

function page(path, api) {
  let definition; const timers = []; let scrolls = 0;
  vm.runInNewContext(compile(path), {
    exports: {}, require: name => name.endsWith('/queue-estimate') ? helpers : { api, run: f => f(), stateLabels: {} },
    Page: value => { definition = value; },
    wx: { setNavigationBarTitle() {}, pageScrollTo() { scrolls++; } },
    setTimeout: (_fn, ms) => { timers.push(ms); return timers.length; }, clearTimeout() {}
  });
  return { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value); }, timers, scrollCount: () => scrolls };
}
test('book polling renders each lecture estimate, preserves section page and never scrolls', async () => {
  const detail = {
    book: { id: 'book', state: 'split', classId: 'class', courseId: 'course' }, queue: queued,
    sections: Array.from({ length: 60 }, (_, n) => ({ start: n, kind: 'lesson', sequence: n + 1, title: '讲次 ' + n, handoutId: 'h' + n })),
    handouts: Array.from({ length: 60 }, (_, n) => ({ id: 'h' + n, state: 'queued', queue: { ...queued, ahead: n } })), blocks: []
  };
  const p = page('miniprogram/pages/book-import/book-import.ts', async () => structuredClone(detail));
  p.data.id = 'book'; p.data.sectionOffset = 50; await p.load();
  assert.equal(p.data.queueText, format(queued)); assert.equal(p.data.sectionOffset, 50);
  assert.equal(p.allSections.length, 60); assert.equal(p.allSections.filter(s => s.queueText).length, 60);
  assert.match(p.data.sections[0].queueText, /50 个任务/); assert.equal(p.scrollCount(), 0);
  assert.equal(p.timers.at(-1), 3000);
  detail.handouts[50] = { id: 'h50', state: 'published', queue: { state: 'done' } };
  await p.load(); assert.equal(p.data.sections[0].queueText, ''); assert.equal(p.data.sectionOffset, 50);
});
test('review polling updates server estimates, clears completed ones and keeps existing polling cadence', async () => {
  let detail = { handout: { state: 'processing', fileName: '讲义', version: 1 }, questions: [], queue: { ...queued, state: 'processing', remainingSeconds: 600 } };
  const p = page('miniprogram/pages/review/review.ts', async () => structuredClone(detail));
  p.visible = true; p.data.id = 'handout'; await p.load();
  assert.equal(p.data.queueText, '预计还需约 10 分钟'); assert.equal(p.timers.at(-1), 3500);
  detail.queue.remainingSeconds = 120; await p.load(); assert.equal(p.data.queueText, '预计还需约 2 分钟');
  detail = { ...detail, handout: { ...detail.handout, state: 'draft' }, queue: { state: 'done' } };
  await p.load(); assert.equal(p.data.queueText, ''); assert.equal(p.scrollCount(), 0);
});
