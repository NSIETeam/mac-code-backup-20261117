const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const workbench = fs.readFileSync('server/wwwroot/workbench/app.js', 'utf8');
const pilot = fs.readFileSync('server/AcademicPilotAssets/app.js', 'utf8');

function entryHarness(response, onFetch) {
  const calls = { routes: [], modals: [], stored: [], assigned: [], timeouts: [] }, buttons = new Map();
  let sessionToken = 'private-session';
  const scope = { token: () => sessionToken, location: { hash: '#home?choose=1', assign: url => calls.assigned.push(url) },
    apiBase: 'https://example.test/miniapp/api/', fetch: async () => { onFetch?.(() => sessionToken = 'other-session'); if(response instanceof Error)throw response; return response; },
    AbortSignal: { timeout: ms => { calls.timeouts.push(ms); return AbortSignal.timeout(ms); } }, URL, URLSearchParams, workspace: { classes: [{ id: 'class-a', name: '数学甲班' }] },
    nameOf: (rows, id) => rows.find(x => x.id === id)?.name, go: (name, p) => calls.routes.push({ name, p }),
    modal: (title, body) => calls.modals.push({ title, body }), button: (label, fn) => { buttons.set(label, fn); return label; },
    dialog: { close() {} }, sessionStorage: { setItem: (k, v) => calls.stored.push([k, v]) }
  };
  vm.createContext(scope);
  vm.runInContext(workbench.slice(workbench.indexOf('async function openClass('), workbench.indexOf('async function home(')), scope);
  return { scope, calls, buttons };
}

test('non-Beta and unavailable Beta preserve original class navigation', async () => {
  for(const response of [{ ok: false }, new Error('offline'), { ok: true, json: async () => ({ beta: false }) }]) {
    const h = entryHarness(response); await h.scope.openClass('class-a');
    assert.equal(h.calls.routes[0].name, 'class'); assert.equal(h.calls.routes[0].p.id, 'class-a');
    assert.equal(h.calls.modals.length, 0); assert.equal(h.calls.stored.length, 0);
    assert.ok(h.calls.timeouts[0] <= 1200);
  }
});

test('Beta class chooser keeps original wrong-record route and transfers login without URL credentials', async () => {
  const h = entryHarness({ ok: true, json: async () => ({ beta: true }) });
  await h.scope.openClass('class-a');
  assert.equal(h.calls.routes.length, 0); assert.equal(h.calls.modals[0].title, '数学甲班');
  assert.equal(h.buttons.size, 3);
  h.buttons.get('记录错题')(); assert.equal(h.calls.routes[0].name, 'class');
  h.buttons.get('点名 <small>Beta</small>')();
  assert.deepEqual(h.calls.stored, [['siyue.academic-pilot.session.v1', 'private-session']]);
  const target = new URL(h.calls.assigned[0]);
  assert.equal(target.pathname, '/miniapp/academic-pilot/');
  assert.equal(target.searchParams.get('classId'), 'class-a');
  assert.equal(target.searchParams.get('module'), 'attendance');
  assert.doesNotMatch(target.href, /private-session|token=/);
});

test('account change during Beta probe cannot open a stale account entry', async () => {
  const h = entryHarness({ ok: true, json: async () => ({ beta: true }) }, change => change());
  await h.scope.openClass('class-a');
  assert.equal(h.calls.modals.length, 0); assert.equal(h.calls.routes.length, 0);
});

class Element {
  constructor(tag, className, text) { this.tag = tag; this.className = className || ''; this.textContent = text || ''; this.children = []; this.dataset = {}; this.attributes = {}; this.value = ''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(k, v) { this.attributes[k] = v; }
  addEventListener(k, fn) { this[k] = fn; }
  close() { this.open = false; }
  remove() { this.removed = true; }
  showModal() { this.open = true; }
  checkValidity() { return !!this.value; }
  focus() { this.focused = true; }
}
function all(root) { return [root, ...root.children.flatMap(all)]; }
function rollCallHarness(statuses = ['unrecorded', 'present', 'leave']) {
  const app = new Element('div'), detail = new Element('div'); app.append(detail);
  const calls = [], messages = [];
  const data = { snapshot: 'version-a', students: statuses.map((status, index) => ({ studentId: 'student-' + index, name: ['甲', '乙', '丙'][index], status })),
    transferClasses: [{ id: 'class-b', name: '数学乙班' }], incomingTransfers: [{ studentId: 'incoming', name: '临时同学', sourceClassName: '丙班', transferOn: '2026-11-02' }] };
  const scope = {
    attendanceDialog: null, state: { me: {}, epoch: 1, busy: false, moduleDirty: new Set(), session: { lessonDate: '2026-11-02' } },
    $: id => id === 'app-view' ? app : detail, el: (tag, cls, text) => new Element(tag, cls, text),
    append: (parent, ...children) => { parent.append(...children); return parent; },
    panel: () => new Element('section'),
    button: (text, cls, fn) => { const b = new Element('button', cls, text); b.click = fn; return b; },
    field: (label, input) => { const node = new Element('label', '', label); node.append(input); return node; },
    selectInput: (options, value) => { const node = new Element('select'); node.options = options; node.value = value; return node; },
    textInput: (value, type) => { const node = new Element('input'); node.type = type; node.value = value; return node; },
    current: epoch => scope.state.me && scope.state.epoch === epoch,
    syncDirty: () => scope.state.dirty = scope.state.moduleDirty.size > 0,
    resetDirty: () => { scope.state.moduleDirty.clear(); scope.state.dirty = false; },
    action: operation => operation(), toast: text => messages.push(text), mayLeave: () => true,
    api: async (path, options) => { calls.push({ path, ...options }); return { ...data, snapshot: 'version-b', students: data.students.map(s => ({ ...s, status: options.body.exceptions.find(e => e.studentId === s.studentId)?.status || 'present' })) }; }
  };
  vm.createContext(scope);
  vm.runInContext(pilot.slice(pilot.indexOf('  function closeAttendanceDialog('), pilot.indexOf('  async function loadHomework(')), scope);
  scope.renderRollCall(data, '/sessions/source/roll-call', 1);
  const findButton = text => all(app).find(n => n.tag === 'button' && n.textContent === text && !n.removed);
  const student = id => all(detail).find(n => n.dataset.studentId === id);
  const dialogButton = text => all(scope.attendanceDialog).find(n => n.tag === 'button' && n.textContent === text);
  return { scope, app, detail, calls, messages, findButton, student, dialogButton };
}

test('selecting leave/transfer is local until one atomic completion request; restoring normal removes the exception', async () => {
  const h = rollCallHarness();
  assert.equal(h.calls.length, 0);
  h.student('student-0').click(); h.dialogButton('请假').click();
  assert.equal(h.calls.length, 0); assert.equal(h.scope.state.dirty, true);
  h.student('student-1').click(); h.dialogButton('临时调班级').click();
  const fields = all(h.scope.attendanceDialog);
  fields.find(n => n.tag === 'select').value = 'class-b';
  assert.equal(fields.find(n => n.tag === 'input').value, '2026-11-02');
  h.dialogButton('确定调班').click();
  h.student('student-2').click(); h.dialogButton('恢复正常出勤').click();
  await h.findButton('完成点名').click();
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].method, 'PUT');
  assert.equal(h.calls[0].body.snapshot, 'version-a'); assert.equal(h.calls[0].body.confirmed, true);
  const exceptions = JSON.parse(JSON.stringify(h.calls[0].body.exceptions));
  assert.deepEqual(exceptions, [{ studentId: 'student-0', status: 'leave' }, { studentId: 'student-1', status: 'transfer', transferClassId: 'class-b', transferOn: '2026-11-02' }]);
  assert.equal(h.scope.state.dirty, false); assert.equal(h.messages[0], '点名已保存。');
  assert.equal(h.student('incoming'), undefined);
});

test('late/absent legacy states require explicit reconciliation, and cancellation does not change data', async () => {
  const h = rollCallHarness(['late', 'absent', 'present']);
  assert.equal(h.findButton('完成点名').disabled, true);
  h.student('student-0').click(); h.dialogButton('取消').click();
  assert.equal(h.scope.state.dirty, false); assert.equal(h.findButton('完成点名').disabled, true);
  h.student('student-0').click(); h.dialogButton('恢复正常出勤').click();
  h.student('student-1').click(); h.dialogButton('请假').click();
  assert.equal(h.findButton('完成点名').disabled, false);
  await h.findButton('完成点名').click();
  assert.equal(h.calls[0].body.exceptions.length, 1); assert.equal(h.calls[0].body.exceptions[0].studentId, 'student-1');
});

test('transfer requires a target and date; stale-page callbacks cannot submit', async () => {
  const h = rollCallHarness(['unrecorded']);
  h.student('student-0').click(); h.dialogButton('临时调班级').click();
  h.dialogButton('确定调班').click(); assert.equal(h.scope.attendanceDialog.open, true);
  assert.equal(h.scope.state.dirty, false);
  const nodes = all(h.scope.attendanceDialog); nodes.find(n => n.tag === 'select').value = 'class-b';
  nodes.find(n => n.tag === 'input').value = ''; h.dialogButton('确定调班').click();
  assert.equal(h.scope.attendanceDialog.open, true); assert.equal(h.calls.length, 0);
  h.dialogButton('取消').click(); h.scope.state.epoch = 2;
  await h.findButton('完成点名').click(); assert.equal(h.calls.length, 0);
});

test('class attendance entry has only the teacher task while the general Beta keeps all modules', () => {
  const modeScope = { URLSearchParams };
  vm.createContext(modeScope);
  vm.runInContext(pilot.slice(pilot.indexOf('  function isTeacherRollCallEntry('), pilot.indexOf('  let attendanceDialog')), modeScope);
  assert.equal(modeScope.isTeacherRollCallEntry('?classId=class-a&module=attendance'), true);
  assert.equal(modeScope.isTeacherRollCallEntry(''), false);
  assert.equal(modeScope.isTeacherRollCallEntry('?module=attendance'), false);
  assert.equal(modeScope.isTeacherRollCallEntry('?classId=class-a&module=feedback'), false);
  for(const teacherEntry of [true, false]) {
    const nodes = new Map();
    const scope = { teacherEntry, trainingEntry:false, state: { module: 'attendance' },
      $: id => { if(!nodes.has(id)){const node=new Element('div');node.classList={toggle(){}};nodes.set(id,node);}return nodes.get(id); },
      el: (tag, cls, text) => new Element(tag, cls, text),
      button: (text, cls) => new Element('button', cls, text),
      append: (parent, ...children) => { parent.append(...children); return parent; }
    };
    vm.createContext(scope);
    vm.runInContext(pilot.slice(pilot.indexOf('  const modules ='), pilot.indexOf('  async function runNavigation(')), scope);
    scope.renderNavigation();
    assert.equal(nodes.get('module-nav').hidden, teacherEntry);
    assert.equal(nodes.get('module-nav').children.length, teacherEntry ? 0 : 11);
  }
});

test('teacher class entry selects only its class and never falls back to an unrelated class', async () => {
  for(const hasClass of [true, false]) {
    const select = new Element('select'), loaded = [], empty = [], paths = [];
    const scope = { entryClassId: 'class-a', state: { me: {}, epoch: 0 },
      api: async path => { paths.push(path);return [{ id: 'other', classId: 'class-b', className: '乙班' }, ...(hasClass ? [{ id: 'ours', classId: 'class-a', className: '甲班' }] : [])]; },
      $: () => select, el: (tag, cls, text) => new Element(tag, cls, text),
      loadSession: async id => loaded.push(id), resetDirty() {}, renderStudents() {}, emptyDetail: title => empty.push(title), encodeURIComponent
    };
    vm.createContext(scope);
    vm.runInContext(pilot.slice(pilot.indexOf('  async function loadSessions('), pilot.indexOf('  async function loadSession(')), scope);
    await scope.loadSessions();
    assert.equal(paths[0], '/sessions?classId=class-a');
    assert.deepEqual(loaded, hasClass ? ['ours'] : []);
    if(!hasClass)assert.equal(empty[0], '这个班级暂无课堂记录');
  }
});

test('Beta class chooser opens reinforcement training for the clicked class without credentials in URL',async()=>{
  const h=entryHarness({ok:true,json:async()=>({beta:true})});await h.scope.openClass('class-a');
  h.buttons.get('强化训练 <small>Beta</small>')();const target=new URL(h.calls.assigned[0]);
  assert.equal(target.searchParams.get('classId'),'class-a');assert.equal(target.searchParams.get('module'),'training');
  assert.doesNotMatch(target.href,/private-session|token=/);
});

test('switching accounts after opening the class menu cannot transfer the previous login to training',async()=>{
  let changeAccount;const h=entryHarness({ok:true,json:async()=>({beta:true})},change=>changeAccount=change);
  await h.scope.openClass('class-a');changeAccount();h.buttons.get('强化训练 <small>Beta</small>')();
  assert.equal(h.calls.assigned.length,0);assert.equal(h.calls.stored.length,0);
});
