const collaborationApi = '/miniapp/api/collaboration/notifications';
const labApi = '/ip-workbench/training/assignment-reminder';
const collaborationPage = '/miniapp/collaboration/';
const guid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const deliveryKinds = new Set(['test_ready', 'accepted', 'rejected', 'published', 'publish_failed', 'release_requested']);
const dates = new Intl.DateTimeFormat('zh-CN', {month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'});

export function notificationLink(note) {
  if (!guid.test(note.projectId)) return collaborationPage + '#notifications';
  const route = new URLSearchParams({id: note.projectId, tab: deliveryKinds.has(note.kind) ? 'code' : 'tasks', view: 'table'});
  if (guid.test(note.targetId)) route.set('target', note.targetId);
  return collaborationPage + '#project?' + route;
}

const session = () => sessionStorage.getItem('siyue_web_token') || '';

async function request(path, token, {body, signal} = {}) {
  const timeout = new AbortController();
  const abort = () => timeout.abort();
  signal?.addEventListener('abort', abort, {once: true});
  const timer = setTimeout(abort, 10000);
  try {
    const response = await fetch(path, {
      method: body === undefined ? 'GET' : 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      signal: timeout.signal,
      headers: {
        'X-Siyue-Inbox': '1',
        ...(token ? {Authorization: 'Bearer ' + token} : {}),
        ...(body === undefined ? {} : {'Content-Type': 'application/json', 'X-Workshop': '1'}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) throw Object.assign(new Error('暂时无法读取通知'), {status: response.status});
    return response.status === 204 ? null : await response.json();
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

function element(tag, properties = {}) {
  const node = document.createElement(tag);
  Object.assign(node, properties);
  return node;
}

export function mountPlatformInbox(button) {
  const dialog = element('dialog', {className: 'platform-inbox'});
  dialog.setAttribute('aria-labelledby', 'platform-inbox-title');
  dialog.innerHTML = `
    <div class="platform-inbox-header">
      <h2 id="platform-inbox-title">站内通知</h2>
      <button type="button" data-inbox-close>关闭</button>
    </div>
    <label class="platform-inbox-filter"><input type="checkbox">只看未读</label>
    <p class="platform-inbox-status" role="status" hidden></p>
    <div class="platform-inbox-list"></div>
    <div class="platform-inbox-footer">
      <button type="button" data-inbox-retry hidden>重试</button>
      <a href="${collaborationPage}#notifications" data-inbox-history hidden>全部协作通知</a>
      <a href="/miniapp/workbench/#home?choose=1" data-inbox-login hidden>登录思跃工作台</a>
    </div>`;
  document.body.append(dialog);
  const badge = button.querySelector('.platform-inbox-badge');
  const list = dialog.querySelector('.platform-inbox-list');
  const status = dialog.querySelector('.platform-inbox-status');
  const retry = dialog.querySelector('[data-inbox-retry]');
  const history = dialog.querySelector('[data-inbox-history]');
  const login = dialog.querySelector('[data-inbox-login]');
  const close = dialog.querySelector('[data-inbox-close]');
  const unreadOnly = dialog.querySelector('.platform-inbox-filter input');
  const sources = {
    collaboration: {delay: 30000, count: 0, next: 0},
    lab: {delay: 120000, count: 0, next: 0},
  };
  let token = session(), timer;

  function paintBadge() {
    const count = sources.collaboration.count + sources.lab.count;
    badge.hidden = count === 0;
    badge.textContent = count > 99 ? '99+' : String(count);
    button.setAttribute('aria-label', count ? `站内通知，${count} 条未读` : '站内通知');
  }

  function schedule() {
    clearTimeout(timer);
    if (document.hidden) return;
    const next = Object.values(sources).filter(source => !source.request && Number.isFinite(source.next));
    if (next.length) timer = setTimeout(refresh, Math.max(0, Math.ceil(Math.min(...next.map(source => source.next)) - performance.now())));
  }

  function reset() {
    clearTimeout(timer);
    for (const source of Object.values(sources)) {
      source.request?.abort();
      Object.assign(source, {count: 0, items: undefined, next: 0, request: null, state: undefined});
    }
    paintBadge();
    if (dialog.open) paintList();
  }

  function syncIdentity() {
    const current = session();
    if (current === token) return;
    token = current;
    reset();
    refresh();
  }

  function load(key, force = false) {
    const source = sources[key];
    if (!force && (source.request || performance.now() < source.next)) return;
    source.request?.abort();
    const controller = new AbortController();
    source.request = controller;
    const includeList = dialog.open && force;
    const path = key === 'lab' ? labApi : includeList ? collaborationApi : collaborationApi + '/unread-count';
    const current = token;
    let reloadList = false;
    void request(path, current, {signal: controller.signal}).then(data => {
      if (source.request !== controller || session() !== current) return;
      source.state = 'ready';
      if (key === 'lab') {
        source.items = data.reminder;
        source.count = data.reminder ? 1 : 0;
      } else if (includeList) {
        source.items = data;
        source.count = data.filter(note => !note.readAt).length;
      } else {
        reloadList = dialog.open && data.count !== source.count;
        source.count = data.count;
      }
    }).catch(error => {
      if (source.request !== controller || session() !== current) return;
      if ([401, 403, 428].includes(error.status)) {
        source.state = 'signed-out';
        source.items = undefined;
        source.count = 0;
      } else {
        source.state = 'unavailable';
      }
    }).finally(() => {
      if (source.request !== controller) return;
      source.request = null;
      source.next = source.state === 'signed-out' ? Infinity : performance.now() + source.delay;
      paintBadge();
      if (dialog.open) paintList();
      if (reloadList && dialog.open) load(key, true);
      schedule();
    });
  }

  function refresh(force = false) {
    if (document.hidden) return;
    load('collaboration', force);
    load('lab', force);
    schedule();
  }

  function row(unread, meta) {
    const item = element('article', {className: 'platform-inbox-item'});
    if (unread) item.dataset.unread = '';
    item.append(element('p', {className: 'platform-inbox-meta', textContent: meta}));
    return item;
  }

  async function markNote(note) {
    const current = token;
    await request(`${collaborationApi}/${note.id}/read`, current, {body: {}});
    if (session() !== current || note.readAt) return;
    note.readAt = new Date().toISOString();
    sources.collaboration.count = Math.max(0, sources.collaboration.count - 1);
    paintBadge();
    if (dialog.open) paintList();
  }

  async function dismissReminder(reminder) {
    const current = token;
    await request(labApi + '/ack', current, {body: {id: reminder.id}});
    if (session() !== current) return;
    if (sources.lab.items?.id === reminder.id) {
      sources.lab.items = null;
      sources.lab.count = 0;
      paintBadge();
      if (dialog.open) paintList();
    }
    window.dispatchEvent(new CustomEvent('siyue:inbox-change', {detail: 'lab'}));
  }

  function action(label, apply) {
    const control = element('button', {type: 'button', textContent: label});
    control.addEventListener('click', async () => {
      control.disabled = true;
      try { await apply(); } catch {
        status.hidden = false;
        status.textContent = '操作未成功，请重试。';
        control.disabled = false;
      }
    });
    return control;
  }

  function paintList() {
    const collaboration = sources.collaboration;
    const lab = sources.lab;
    const items = document.createDocumentFragment();
    const active = document.activeElement;
    const activeKey = dialog.contains(active) ? active?.dataset.inboxFocus : null;
    if (lab.items) {
      const reminder = lab.items;
      const item = row(true, 'AI Lab · 作业提醒');
      for (const task of reminder.tasks) {
        if (!/^[\w-]{1,80}$/.test(task.id)) continue;
        const link = element('a', {href: '/ai-lab/assignments/' + task.id + '/', textContent: task.title});
        link.dataset.inboxFocus = 'task-' + task.id;
        item.append(link);
      }
      const dismiss = action('知道了', () => dismissReminder(reminder));
      dismiss.dataset.inboxFocus = 'dismiss-lab';
      item.append(dismiss);
      items.append(item);
    }
    for (const note of collaboration.items || []) {
      if (unreadOnly.checked && note.readAt) continue;
      let meta = note.projectName ? '项目协作 · ' + note.projectName : '项目协作';
      const created = new Date(note.createdAt);
      if (!Number.isNaN(created.valueOf())) meta += ' · ' + dates.format(created);
      const item = row(!note.readAt, meta);
      const link = element('a', {href: notificationLink(note), textContent: note.text});
      link.dataset.inboxFocus = 'note-' + note.id;
      if (!note.readAt && guid.test(note.id)) {
        link.addEventListener('click', async event => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          try { await markNote(note); } catch { /* The destination stays available when marking read fails. */ }
          dialog.close();
          location.assign(link.href);
        });
      } else if (link.href.startsWith(location.origin + location.pathname + '#')) {
        link.addEventListener('click', () => dialog.close());
      }
      item.append(link);
      if (!note.readAt && guid.test(note.id)) {
        const read = action('标为已读', () => markNote(note));
        read.dataset.inboxFocus = 'read-' + note.id;
        item.append(read);
      }
      items.append(item);
    }
    list.replaceChildren(items);
    if (activeKey) {
      const restore = [...list.querySelectorAll('[data-inbox-focus]')].find(node => node.dataset.inboxFocus === activeKey);
      (restore || close).focus({preventScroll: true});
    }
    const states = Object.values(sources);
    const unavailable = [
      collaboration.state === 'unavailable' && '项目协作',
      lab.state === 'unavailable' && 'AI Lab',
    ].filter(Boolean);
    const signedOut = states.every(source => source.state === 'signed-out');
    const waiting = states.some(source => !source.state || source.request);
    status.textContent = signedOut ? '登录后即可查看通知。' : unavailable.length
      ? `${unavailable.join('、')}的通知暂时无法加载。`
      : waiting && !list.childNodes.length ? '正在加载…'
      : list.childNodes.length ? '' : unreadOnly.checked ? '暂无未读通知。' : '暂无站内通知。';
    status.hidden = !status.textContent;
    login.hidden = !signedOut;
    retry.hidden = unavailable.length === 0;
    history.hidden = !token || !collaboration.items?.length;
  }

  button.addEventListener('click', () => {
    syncIdentity();
    if (dialog.open) return;
    dialog.showModal();
    close.focus();
    refresh(true);
    paintList();
  });
  close.addEventListener('click', () => dialog.close());
  unreadOnly.addEventListener('change', paintList);
  history.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    sources.collaboration.items = undefined;
    list.replaceChildren();
    button.focus({preventScroll: true});
  });
  retry.addEventListener('click', () => refresh(true));
  window.addEventListener('siyue:session-change', syncIdentity);
  window.addEventListener('siyue:inbox-change', event => {
    syncIdentity();
    if (['collaboration', 'lab'].includes(event.detail) && !document.hidden) load(event.detail, true);
  });
  window.addEventListener('pageshow', event => {
    syncIdentity();
    refresh(event.persisted);
  });
  window.addEventListener('pagehide', reset);
  document.addEventListener('visibilitychange', () => {
    syncIdentity();
    if (!token && document.hidden) reset();
    else refresh(!token && dialog.open);
  });
  window.addEventListener('hashchange', syncIdentity);
  const root = document.querySelector('#app');
  if (root) new MutationObserver(syncIdentity).observe(root, {childList: true});
  document.body.setAttribute('data-platform-inbox', '');
  refresh();
}
