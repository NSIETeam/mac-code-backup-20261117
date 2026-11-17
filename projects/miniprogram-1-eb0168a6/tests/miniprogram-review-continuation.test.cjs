const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const options = { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2017 };
function source(path) { return ts.transpileModule(fs.readFileSync(path, 'utf8'), { compilerOptions: options }).outputText; }
function moduleAt(path) { const exports = {}; vm.runInNewContext(source(path), { exports }); return exports; }
const navigation = moduleAt('miniprogram/utils/review-navigation.ts');
const queue = moduleAt('miniprogram/utils/queue-estimate.ts');
function page(path, api) {
  let definition; const routes = [], calls = [];
  vm.runInNewContext(source(path), {
    exports: {}, require: name => name.endsWith('/review-navigation') ? navigation : name.endsWith('/queue-estimate') ? queue : {
      run: f => f(), confirm: async () => true, stateLabels: {}, api: async (...args) => { calls.push(args); return api(...args); }
    },
    Page: value => { definition = value; },
    wx: { setNavigationBarTitle() {}, redirectTo: value => routes.push(value.url), navigateTo: value => routes.push(value.url) },
    setTimeout: () => 1, clearTimeout() {}
  });
  return { ...definition, data: structuredClone(definition.data), setData(value) { Object.assign(this.data, value); }, routes, calls };
}
const detail = state => ({ handout: { id: 'h1', fileName: '第一讲', state, version: 1, revision: 4 }, questions: [], lecture: { sequence: 1, classId: null, courseId: 'math' }, classId: 'class' });
const book = nextState => ({ book: { classId: 'class', courseId: 'math' }, sections: [
  { kind: 'exclude', sequence: 0, title: '目录' },
  { kind: 'lesson', sequence: 10, title: '第十讲', handoutId: 'h10' },
  { kind: 'lesson', sequence: 1, title: '第一讲', handoutId: 'h1' },
  { kind: 'reference', sequence: 2, title: '答案', handoutId: 'answer' },
  { kind: 'lesson', sequence: 2, title: '第二讲', handoutId: 'h2' },
  { kind: 'lesson', sequence: 2, title: '第二讲限时训练', handoutId: 'h2' }
], handouts: [{ id: 'h1', state: 'published' }, { id: 'h2', state: nextState }, { id: 'h10', state: 'draft' }] });

test('next review uses numeric sequence, wraps unfinished work and retains not-yet-created lectures', () => {
  const select = navigation.nextReviewLesson;
  const lessons = [{ sequence: 10, handoutId: 'h10', state: 'draft' }, { sequence: 1, handoutId: 'h1', state: 'draft' }, { sequence: 2, state: 'queued' }];
  assert.equal(select(lessons, 'h1', 1).sequence, 2);
  assert.equal(select(lessons, 'h10', 10).handoutId, 'h1');
  assert.equal(select([{ sequence: 1, handoutId: 'h1', state: 'published' }], 'h1', 1), undefined);
});
test('publishing continues to the next draft while preserving book, class and course without confirming it', async () => {
  const p = page('miniprogram/pages/review/review.ts', async (path, method) => method === 'POST' ? {} : path.startsWith('/handouts/') ? detail('published') : book('draft'));
  p.onLoad({ id: 'h1', bookId: 'book', classId: 'class', courseId: 'math' }); p.data.handout = detail('draft').handout;
  await p.publish();
  assert.equal(p.routes[0], '/pages/review/review?id=h2&bookId=book&classId=class&courseId=math');
  assert.deepEqual(p.calls.filter(call => call[1] === 'POST').map(call => call[0]), ['/handouts/h1/publish']);
  assert.equal(p.data.busy, false);
});
test('a queued, processing or failed next lecture returns to its existing book progress instead of skipping it', async () => {
  for (const state of ['queued', 'processing', 'failed']) {
    const p = page('miniprogram/pages/review/review.ts', async () => book(state));
    Object.assign(p.data, { id: 'h1', bookId: 'book', classId: 'class', courseId: 'math', lectureSequence: 1 });
    await p.continueAfterPublish();
    assert.equal(p.routes[0], '/pages/book-import/book-import?id=book&classId=class&courseId=math&next=h2');
  }
});
test('missing next handouts return to their lecture sequence and all-finished books remain in the book', async () => {
  const value = book('published'); value.sections.find(s => s.handoutId === 'h10').handoutId = undefined;
  const p = page('miniprogram/pages/review/review.ts', async () => value);
  Object.assign(p.data, { id: 'h1', bookId: 'book', classId: 'class', courseId: 'math', lectureSequence: 1 });
  await p.continueAfterPublish(); assert.match(p.routes[0], /next=seq-10$/);
  value.sections = value.sections.filter(s => s.sequence !== 10);
  await p.continueAfterPublish(); assert.equal(p.routes[1], '/pages/book-import/book-import?id=book&classId=class&courseId=math');
});
test('class fallback considers only authorized same-course latest handout versions', async () => {
  const value = { lectures: [
    { id: 'l1', sequence: 1, courseId: 'math', classId: 'class' },
    { id: 'other', sequence: 2, courseId: 'english', classId: 'class' },
    { id: 'foreign', sequence: 2, courseId: 'math', classId: 'other-class' },
    { id: 'l2', sequence: 2, courseId: 'math', classId: null }
  ], handouts: [{ id: 'h1', lectureId: 'l1', version: 1, state: 'published' }, { id: 'old', lectureId: 'l2', version: 1, state: 'draft' }, { id: 'latest', lectureId: 'l2', version: 2, state: 'draft' }] };
  const p = page('miniprogram/pages/review/review.ts', async () => value);
  Object.assign(p.data, { id: 'h1', classId: 'class', courseId: 'math', lectureSequence: 1 });
  await p.continueAfterPublish(); assert.equal(p.calls[0][0], '/classes/class?courseId=math');
  assert.equal(p.routes[0], '/pages/review/review?id=latest&classId=class&courseId=math');
  value.handouts[2].state = 'queued'; await p.continueAfterPublish();
  assert.equal(p.routes[1], '/pages/classroom/classroom?classId=class&courseId=math&action=materials');
});
test('failure fetching the next lecture leaves the published current lecture available for safe retry', async () => {
  let fail = true;
  const p = page('miniprogram/pages/review/review.ts', async path => {
    if (path.startsWith('/books/')) { if (fail) throw Error('offline'); return book('draft'); }
    return detail('published');
  });
  p.onLoad({ id: 'h1', bookId: 'book', classId: 'class', courseId: 'math' }); p.data.handout = detail('draft').handout;
  await assert.rejects(p.publish(), /offline/); assert.equal(p.data.handout.state, 'published'); assert.equal(p.data.busy, false); assert.equal(p.routes.length, 0);
  fail = false; await p.continueReview(); assert.match(p.routes[0], /id=h2/);
  assert.equal(p.calls.filter(call => call[1] === 'POST').length, 1);
});
test('mismatched book or class context cannot continue into another lecture list', async () => {
  const otherBook = book('draft'); otherBook.book.classId = 'other';
  const p = page('miniprogram/pages/review/review.ts', async () => otherBook);
  Object.assign(p.data, { id: 'h1', bookId: 'book', classId: 'class', courseId: 'math', lectureSequence: 1 });
  await assert.rejects(p.continueAfterPublish(), /不一致/); assert.equal(p.routes.length, 0);
  const c = page('miniprogram/pages/review/review.ts', async () => ({ lectures: [{ id: 'l2', sequence: 2, courseId: 'math', classId: 'class' }], handouts: [{ id: 'h2', lectureId: 'l2', state: 'draft', version: 1 }] }));
  Object.assign(c.data, { id: 'h1', classId: 'class', courseId: 'math', lectureSequence: 1 });
  await assert.rejects(c.continueAfterPublish(), /不在本班本课程/); assert.equal(c.routes.length, 0);
});
test('book review entry keeps the import context and a pending lecture is located without changing it every poll', async () => {
  const value = { ...book('processing'), blocks: [] };
  const p = page('miniprogram/pages/book-import/book-import.ts', async () => value);
  p.onLoad({ id: 'book', classId: 'class', courseId: 'math', next: 'h2' }); await p.load();
  assert.match(p.data.nextNotice, /第2讲/); assert.match(p.data.nextNotice, /正在拆题/);
  p.review({ currentTarget: { dataset: { id: 'h2' } } });
  assert.equal(p.routes[0], '/pages/review/review?id=h2&classId=class&courseId=math&bookId=book');
  assert.equal(p.locateNext, false);
});
