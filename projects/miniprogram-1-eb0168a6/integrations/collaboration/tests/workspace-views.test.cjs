const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercises the staged client with anonymous data. No browser, API or production writes.
const clientPath = path.join(__dirname, '../wwwroot/collaboration/app.mjs');
function fixture() {
  const states = ['planned', 'in_progress', 'blocked', 'review', 'done'];
  const tasks = Array.from({length: 30}, (_, i) => ({
    id: `task-${i}`, title: `Task ${i}`, description: `Description ${i}`,
    criteria: '', blocker: '', state: states[i % states.length],
    ownerId: i === 0 ? 'me' : 'other', collaborators: i === 1 ? '["me"]' : '[]',
    needsStaffId: i === 2 ? 'me' : null, dueOn: null, startsOn: null,
  }));
  const actions = Array.from({length: 57}, (_, i) => ({
    id: `action-${i}`, taskId: i < 22 ? `task-${i}` : null,
    title: `Action ${i}`, body: `Action body ${i}`, state: states[i % states.length],
    kind: 'update', plannedOn: i % 2 ? '2026-11-16' : null,
    dateLabel: i % 2 ? '' : '未排期', codeRef: null, repositoryId: null,
  }));
  return {
    project: {id: 'project-1', name: 'Anonymous project', ownerTeam: 'AI创新实验室',
      ownerId: 'other', goal: 'Project goal', approach: 'Project approach',
      department: 'Demo', status: 'active'},
    tasks, actions, members: [], progress: [], attachments: [], canManage: false,
    development: {repository: null, builds: [], changes: [], releases: []},
  };
}
function load({hash = '#project?id=project-1&tab=tasks&view=list', data = fixture()} = {}) {
  const elements = new Map();
  const el = (selector) => {
    if (!elements.has(selector)) {
      const events = new Map(), attrs = new Map();
      elements.set(selector, {
      value: '', innerHTML: '', textContent: '', open: false,
      classList: {toggle() {}, add() {}, remove() {}},
      setAttribute(key, value) { attrs.set(key,value); },
      hasAttribute(key) { return attrs.has(key); }, removeAttribute(key) { attrs.delete(key); },
      addEventListener(type, fn) { if (!events.has(type)) events.set(type, []); events.get(type).push(fn); },
      querySelector: el,
      querySelectorAll() { return []; },
      showModal() { this.open = true; },
      close() { this.open = false; for (const fn of events.get('close') || []) fn(); },
      });
    }
    return elements.get(selector);
  };
  const document = {querySelector: el, querySelectorAll(selector) {
    if (selector !== '#task-content [data-action]') return [];
    return [...el('#task-content').innerHTML.matchAll(/data-action="(\d+)"/g)].map(m=>({dataset:{action:m[1]}}));
  }, body: el('body'), addEventListener() {}, hidden: false};
  const location = {hash, pathname: '/miniapp/collaboration/', origin: 'https://example.invalid'};
  const context = vm.createContext({document, location,
    window: {addEventListener() {}, scrollY: 0, scrollTo() {}},
    sessionStorage: {getItem() { return 'offline-test'; }, removeItem() {}},
    URL, URLSearchParams, performance, AbortSignal, FormData, crypto: require('node:crypto').webcrypto,
    history: {replaceState(_state, _unused, url) { location.hash = url; }},
    setTimeout() { return 0; }, clearTimeout() {}, requestAnimationFrame(fn) { fn(); },
    navigator: {},
    fetch() { throw new Error('Network is forbidden in the offline view tests'); },
  });
  const source = fs.readFileSync(clientPath, 'utf8')
    .replace(/^import .*;\n/gm, '')
    .replace(/^const platformAuth = .*;$/m, 'const platformAuth = {then() {}};')
    .replace(/^export const /gm, 'const ');
  vm.runInContext(source, context, {filename: clientPath});
  context.fixture = data;
  vm.runInContext(`
    me={user:{id:'me',name:'Anonymous user',role:'teacher'}};
    people=[{id:'me',name:'Me',department:'Demo'},{id:'other',name:'Other',department:'Demo'}];
    current=fixture;
    unread=async()=>{};
  `, context);
  const run = script => vm.runInContext(script, context);
  const paint = (view, search = '', status = '', mine = false) => {
    location.hash = `#project?id=project-1&tab=tasks&view=${view}${mine ? '&scope=mine' : ''}`;
    el('#task-search').value = search;
    el('#task-status').value = status;
    run('paintTasks()');
    return {html: el('#task-content').innerHTML, summary: el('#task-result-summary').textContent};
  };
  return {context, run, el, paint, location, data};
}
const occurrence = (text, needle) => text.split(needle).length - 1;

test('list, board and timeline retain all 30 tasks and 57 nodes, including all 35 project records', () => {
  const app = load();
  for (const view of ['list', 'board', 'table']) {
    const {html, summary} = app.paint(view);
    assert.equal(summary, '30 项任务 · 57 个推进节点', view);
    for (const task of app.data.tasks) assert.equal(occurrence(html, `id="item-${task.id}"`), 1, `${view}: ${task.id}`);
    for (const action of app.data.actions.filter(a => a.taskId === null)) {
      assert.equal(occurrence(html, `<strong>${action.title}</strong>`), 1, `${view}: ${action.id}`);
    }
    if (view === 'table') {
      for (const action of app.data.actions) assert.equal(occurrence(html, `<strong>${action.title}</strong>`), 1, action.id);
    }
  }
});

test('mine includes owner, collaborator and requested helper, while project-wide records are excluded', () => {
  const app = load();
  for (const view of ['list', 'board', 'table']) {
    const {html, summary} = app.paint(view, '', '', true);
    assert.equal(summary, '3 项任务 · 3 个推进节点');
    for (const i of [0, 1, 2]) assert.ok(html.includes(`id="item-task-${i}"`));
    assert.ok(!html.includes('id="item-task-3"'));
    assert.ok(!html.includes('项目推进记录'));
  }
});

test('task state and case-insensitive action search intersect without losing its task context', () => {
  const app = load();
  for (const view of ['list', 'board', 'table']) {
    let result = app.paint(view, 'aCtIoN bOdY 1', 'in_progress');
    assert.equal(result.summary, '3 项任务 · 3 个推进节点'); // Task 1, Task 11 and Task 16.
    assert.ok(result.html.includes('id="item-task-1"'));
    assert.ok(result.html.includes('id="item-task-11"'));
    assert.ok(result.html.includes('id="item-task-16"'));
    result = app.paint(view, 'aCtIoN bOdY 1', 'in_progress', true);
    assert.equal(result.summary, '1 项任务 · 1 个推进节点');
  }
  assert.equal(app.paint('list', 'Action body 26', 'in_progress').summary, '0 项任务 · 1 个推进节点');
  assert.equal(app.paint('list', 'no such content', '').summary, '0 项任务 · 0 个推进节点');
});

test('clearing URL-backed search and state remains cleared when changing view or scope', () => {
  const app = load({hash: '#project?id=project-1&view=list&q=old&status=blocked&scope=mine'});
  app.el('#task-search').value = '';
  app.el('#task-status').value = '';
  assert.equal(app.run('taskOptions().q'), '');
  assert.equal(app.run('taskOptions().status'), '');
  assert.equal(app.run('taskOptions().scope'), 'mine');
  assert.equal(app.run("taskOptions({scope:'all'}).scope"), 'all');
});

test('task and action text is escaped in every view and the read-only task drawer', () => {
  const data = fixture();
  data.tasks[0].title = '<img src=x onerror=alert(1)>';
  data.tasks[0].description = '<script>bad()</script>';
  data.actions[22].title = '<img src=y onerror=alert(2)>';
  const app = load({data});
  for (const view of ['list', 'board', 'table']) {
    const {html} = app.paint(view);
    assert.ok(!html.includes('<img'));
    assert.ok(!html.includes('<script>'));
    assert.ok(html.includes('&lt;img'));
  }
  app.run('taskDetail(current.tasks[0])');
  const html = app.el('#dialog').innerHTML;
  assert.ok(html.includes('&lt;script&gt;bad()&lt;/script&gt;'));
  assert.ok(html.includes('编辑任务') && html.includes('添加节点') && html.includes('复制给 Agent'));
  assert.ok(html.includes('Action 0'));
});

test('direct task, action and comment targets open details even outside selected task filters', async () => {
  const data = fixture();
  data.progress = [{id:'comment-1',actionId:'action-0',parentId:null,authorId:'other',body:'Comment text',createdAt:'2026-11-16T00:00:00Z',requestId:'request-1'}];
  const app = load({data, hash:'#project?id=project-1&tab=tasks&view=board&scope=mine&status=done&q=impossible'});
  app.run('api=async()=>current.development; shell=()=>{}; bindTaskViews=()=>{};');
  for (const [target, title] of [['task-29', 'Task 29'], ['action-0', 'Action 0'], ['comment-1', 'Action 0']]) {
    await app.run(`projectPage({...route(),target:${JSON.stringify(target)}})`);
    assert.ok(app.el('#dialog').innerHTML.includes(`>${title}</h2>`), target);
  }
});

test('action drawer retains comments/replies, all attachment links, reminders and code preview', async () => {
  const data = fixture(), sha = 'a'.repeat(40);
  Object.assign(data.actions[0], {codeRef:sha, repositoryId:'repo-1',kind:'code'});
  data.development = {repository:{id:'repo-1',name:'Example repo',mainBranch:'main'},codeUrl:'https://example.invalid/repo',builds:[{id:'build-1',sha,state:'tested'}]};
  data.progress = [
    {id:'comment-1',actionId:'action-0',parentId:null,authorId:'other',body:'Visible comment',createdAt:'2026-11-16T00:00:00Z',requestId:'request-1'},
    {id:'comment-2',actionId:'action-0',parentId:'comment-1',authorId:'me',body:'Visible reply',createdAt:'2026-11-16T00:00:01Z',requestId:'request-2'},
    {id:'comment-3',actionId:'action-0',parentId:null,authorId:'me',body:'Import duplicate',createdAt:'2026-11-16T00:00:02Z',requestId:'csv:old'},
  ];
  data.attachments = Array.from({length:10},(_,i)=>({id:`file-${i}`,actionId:'action-0',name:`Attachment ${i}.txt`}));
  const app = load({data});
  app.run('api=async()=>current.development;');
  await app.run('actionDetail(current.actions[0])');
  const html = app.el('#dialog').innerHTML;
  for (const label of ['编辑','提醒协作者','添加资料','复制给 Agent','写评论','回复','体验此提交','Visible comment','Visible reply','Example repo']) assert.ok(html.includes(label), label);
  for (const file of data.attachments) assert.ok(html.includes(file.name));
  assert.ok(!html.includes('Import duplicate'));
});

test('non-member project row uses summary instead of opening protected project details', async () => {
  const app = load();
  app.run("showSummary=p=>globalThis.opened='summary'; openListedProject=id=>globalThis.opened='detail';");
  for (const member of [false, true]) {
    app.context.member = member;
    const html = app.run('projectTable([{...current.project,member,completed:0,total:30}])');
    const action = html.match(/data-action="(\d+)"/)[1];
    await app.run(`handlers.get('${action}')()`);
    assert.equal(app.context.opened, member ? 'detail' : 'summary');
  }
});

test('old development and release URLs still select code while manage-only project editing stays gated', async () => {
  const app = load();
  app.run("api=async()=>current.development; codeView=()=>'<div>CODE-SENTINEL</div>'; shell=html=>globalThis.pageHtml=html; bindTaskViews=()=>{};");
  for (const tab of ['code','development','releases']) {
    await app.run(`projectPage({tab:'${tab}'})`);
    assert.ok(app.context.pageHtml.includes('CODE-SENTINEL'));
    assert.ok(!app.context.pageHtml.includes('编辑简介'));
  }
  app.run('current.canManage=true;');
  await app.run("projectPage({tab:'tasks'})");
  assert.ok(app.context.pageHtml.includes('编辑简介'));
});

test('canceling task edits, node edits, a new task node or attachment upload returns to the originating details', async () => {
  const app = load();
  app.run('mentions=()=>{}; bindForm=()=>{};');
  const cases = [
    ['taskForm(current.tasks[0])', 'Task 0'],
    ['actionForm(current.actions[0])', 'Action 0'],
    ["actionForm(null,'task-0')", 'Task 0'],
    ['uploadForm(current.actions[0])', 'Action 0'],
  ];
  for (const [script, title] of cases) {
    app.run(script);
    const cancel = app.el('#dialog').innerHTML.match(/<button[^>]*data-action="(\d+)"[^>]*>取消<\/button>/);
    assert.ok(cancel, script);
    await app.run(`handlers.get('${cancel[1]}')()`);
    assert.ok(app.el('#dialog').innerHTML.includes(`>${title}</h2>`), script);
  }
});

test('todos show their project, exclude archived projects by default, and retain a separate archive view', async () => {
  const app = load({hash:'#todos'});
  app.context.todoFixtures = [
    {...app.data.tasks[0], projectId:'active-project', title:'Current todo'},
    {...app.data.tasks[1], projectId:'archived-project', title:'Archived todo'},
  ];
  app.context.projectFixtures = [
    {id:'active-project', name:'Active <project>', status:'active'},
    {id:'archived-project', name:'Archived project', status:'archived'},
  ];
  app.run("api=async path=>path==='/todos'?todoFixtures:projectFixtures; shell=html=>globalThis.pageHtml=html;");
  await app.run('todoPage()');
  assert.ok(app.context.pageHtml.includes('Current todo'));
  assert.ok(app.context.pageHtml.includes('Active &lt;project&gt;'));
  assert.ok(!app.context.pageHtml.includes('Archived todo'));
  assert.ok(app.context.pageHtml.includes('已归档项目 · 1'));
  app.location.hash = '#todos?archived=1';
  await app.run('todoPage()');
  assert.ok(app.context.pageHtml.includes('Archived todo'));
  assert.ok(app.context.pageHtml.includes('Archived project'));
  assert.ok(!app.context.pageHtml.includes('Current todo'));
});

test('closing targeted action details removes only target and does not replay the route', async () => {
  const app = load({hash:'#project?id=project-1&tab=tasks&view=board&scope=mine&q=find&status=blocked&target=action-0'});
  app.run('render=async()=>{globalThis.renderCalls=(globalThis.renderCalls||0)+1;};');
  await app.run('actionDetail(current.actions[0])');
  const close = app.el('#dialog').innerHTML.match(/<button[^>]*data-action="(\d+)"[^>]*>关闭<\/button>/);
  await app.run(`handlers.get('${close[1]}')()`);
  assert.equal(app.el('#dialog').open, false);
  assert.equal(app.run('route().target'), undefined);
  assert.equal(app.run('route().view'), 'board');
  assert.equal(app.run('route().scope'), 'mine');
  assert.equal(app.run('route().q'), 'find');
  assert.equal(app.run('route().status'), 'blocked');
  assert.equal(app.context.renderCalls || 0, 0);
});

test('repainting filtered tasks retires old row handlers instead of accumulating stale closures', () => {
  const app = load();
  app.paint('list');
  const count = app.run('handlers.size');
  const oldIds = app.run('[...handlers.keys()]');
  app.paint('list', 'no such content');
  assert.equal(app.run('handlers.size'), 0);
  for (const id of oldIds) assert.equal(app.run(`handlers.has('${id}')`), false);
  app.paint('list');
  assert.equal(app.run('handlers.size'), count);
});

test('same-project view navigation swaps only the workspace and retires its handlers without an API reload', async () => {
  const app = load({hash:'#project?id=project-1&tab=tasks&view=board&q=find&status=blocked&scope=mine'});
  app.run("globalThis.headerHandler=on(()=>{}); globalThis.staleRowHandler=on(()=>{}); globalThis.apiCalls=[]; api=async(...args)=>{apiCalls.push(args);throw Error('Unexpected reload');}; bindTaskViews=()=>{globalThis.didBind=true;};");
  app.el('.task-workspace').querySelectorAll = () => [{dataset:{action:app.context.staleRowHandler}}];
  await app.run('render({navigation:true})');
  assert.equal(app.context.apiCalls.length, 0);
  assert.equal(app.context.didBind, true);
  assert.equal(app.run('handlers.has(headerHandler)'), true);
  assert.equal(app.run('handlers.has(staleRowHandler)'), false);
  assert.ok(app.el('.task-workspace').outerHTML.includes('value="find"'));
  assert.ok(app.el('.task-workspace').outerHTML.includes('value="blocked" selected'));
  assert.ok(app.el('.task-workspace').outerHTML.includes('与我有关 · 已选'));
  assert.equal(app.el('#app').inert, false);
});

test('saving an existing task keeps its revision and forces fresh project data rather than using navigation fast path', async () => {
  const app = load();
  app.data.tasks[0].revision = 7;
  app.context.fresh = structuredClone(app.data);
  app.context.fresh.tasks[0].title = 'Saved title';
  app.run(`
    globalThis.calls=[]; mentions=()=>{}; bindForm=fn=>{globalThis.submitTask=fn;};
    api=async(path,method,body)=>{calls.push({path,method:method||'GET',body});return method==='PUT'?null:fresh;};
    projectPage=async()=>{globalThis.refreshedTitle=current.tasks[0].title;};
    taskForm(current.tasks[0]);
  `);
  await app.run("submitTask({title:'Saved title',state:'planned',ownerId:'me',description:'New description',criteria:'',blocker:'',needsStaffId:'',startsOn:'',dueOn:''},{elements:{collaborators:{selectedOptions:[]}}})");
  assert.equal(app.context.calls[0].method, 'PUT');
  assert.equal(app.context.calls[0].path, '/projects/project-1/tasks/task-0');
  assert.equal(app.context.calls[0].body.revision, 7);
  assert.ok(app.context.calls.some(call=>call.method==='GET'&&call.path==='/projects/project-1'));
  assert.equal(app.context.refreshedTitle, 'Saved title');
});
