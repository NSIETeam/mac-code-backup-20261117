// Text stays visible even when a Word question's image is only a diagram.
let activeCleanup;

function imageUrl(value, ui) {
  if (typeof value !== 'string' || !value.trim()) throw Error('题图链接无效');
  const base = new URL(ui.apiBase || location.href, location.href);
  const checked = ui.safeResource ? ui.safeResource(value, base.href) : value;
  const url = new URL(checked, base);
  if (!['https:', 'http:'].includes(url.protocol) || url.origin !== base.origin || url.username || url.password) throw Error('题图来源无效');
  return url.href;
}

/** Opens synchronously; ready settles once the question text or an error is visible. */
export function openLecturePreview({ title = '分讲预览', load, returnFocus }, ui = {}) {
  activeCleanup?.();
  const dialog = ui.dialog;
  ui.modal(title, `<section class="lecture-preview" aria-label="分讲阅读器">
    <div class="lecture-preview-toolbar"><span class="lecture-preview-count" role="status"></span><div class="lecture-preview-actions"></div></div>
    <p class="lecture-preview-note" hidden></p>
    <div class="lecture-preview-viewport" tabindex="0" aria-label="本讲题目">
      <div class="lecture-preview-state" role="status" aria-live="polite"><p>正在打开本讲内容…</p><button type="button" hidden>重新加载</button></div>
      <div class="lecture-preview-questions"></div>
    </div><p class="lecture-preview-notice" role="status" hidden></p>
  </section>`, true);
  dialog.classList.add('lecture-preview-dialog');
  const root = dialog.querySelector('.lecture-preview');
  const find = selector => root.querySelector(selector);
  const viewport = find('.lecture-preview-viewport');
  const questions = find('.lecture-preview-questions');
  const state = find('.lecture-preview-state');
  const message = state.querySelector('p');
  const retryLoad = state.querySelector('button');
  const count = find('.lecture-preview-count');
  const actions = find('.lecture-preview-actions');
  const note = find('.lecture-preview-note');
  const notice = find('.lecture-preview-notice');
  const events = new AbortController();
  let requests = new AbortController();
  let stopped = false, generation = 0, running = 0, scrollFrame = null;
  const queue = [], awaitingView = new Map();
  const alive = stamp => !stopped && root.isConnected && dialog.contains(root) && (stamp === undefined || stamp === generation);
  const on = (node, type, fn) => node.addEventListener(type, fn, { signal: events.signal });
  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  };
  const button = (label, run) => {
    const node = el('button', '', label);
    node.type = 'button';
    on(node, 'click', run);
    return node;
  };

  // Metadata and actual image transfers share the same three slots.
  function enqueue(run, stamp) {
    if (!alive(stamp)) return;
    queue.push({ run, stamp });
    pump();
  }
  function pump() {
    while (alive() && running < 3 && queue.length) {
      const task = queue.shift();
      if (!alive(task.stamp)) continue;
      running++;
      Promise.resolve().then(() => alive(task.stamp) && task.run()).catch(() => {}).finally(() => { running--; pump(); });
    }
  }
  const intersection = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting || !alive()) continue;
      const run = awaitingView.get(entry.target);
      awaitingView.delete(entry.target);
      intersection.unobserve(entry.target);
      run?.();
    }
  }, { root: viewport, rootMargin: '220px 0px' }) : null;
  function checkView() {
    scrollFrame = null;
    if (!alive()) return;
    const bounds = viewport.getBoundingClientRect();
    for (const [node, run] of awaitingView) {
      const rect = node.getBoundingClientRect();
      if (rect.bottom >= bounds.top - 220 && rect.top <= bounds.bottom + 220) {
        awaitingView.delete(node);
        run();
      }
    }
  }
  function scheduleView() {
    if (!intersection && scrollFrame === null && alive()) scrollFrame = requestAnimationFrame(checkView);
  }
  function observe(node, run) {
    awaitingView.set(node, run);
    if (intersection) intersection.observe(node);
    else scheduleView();
  }

  function appendImage(container, spec, label, index, stamp) {
    const figure = el('figure', 'lecture-preview-image');
    const status = el('p', 'lecture-preview-image-status', '题图待加载');
    const image = el('img');
    image.alt = `${label} · 题图 ${index + 1}`;
    image.decoding = 'async';
    image.hidden = true;
    let busy = false;
    const retry = button('重试这张题图', () => start());
    retry.hidden = true;
    figure.append(image, status, retry);
    container.append(figure);
    function start() {
      if (busy || !alive(stamp)) return;
      busy = true;
      retry.hidden = true;
      status.hidden = false;
      status.textContent = '正在加载题图…';
      enqueue(async () => {
        const signal = requests.signal;
        try {
          const url = imageUrl(spec?.url, ui);
          await new Promise((resolve, reject) => {
            let timeout;
            const finish = error => {
              clearTimeout(timeout);
              image.onload = image.onerror = null;
              signal.removeEventListener('abort', abort);
              if (error) { image.removeAttribute('src'); reject(error); } else resolve();
            };
            const abort = () => finish(Error('已关闭'));
            image.onload = () => finish();
            image.onerror = () => finish(Error('题图加载失败'));
            signal.addEventListener('abort', abort, { once: true });
            timeout = setTimeout(() => finish(Error('题图加载超时')), 30000);
            if (signal.aborted || !alive(stamp)) abort();
            else image.src = url;
          });
          if (!alive(stamp)) return;
          image.hidden = false;
          status.hidden = true;
          figure.classList.add('is-loaded');
        } catch {
          if (!alive(stamp)) return;
          status.textContent = '这张题图暂时无法显示。';
          retry.hidden = false;
        } finally { busy = false; scheduleView(); }
      }, stamp);
    }
    observe(figure, start);
  }

  function appendQuestion(question, index, stamp) {
    const label = String(question?.label || `第 ${index + 1} 题`);
    const article = el('article', 'lecture-preview-question');
    article.append(el('h3', '', label));
    const text = String(question?.text || '').trim();
    article.append(el('div', `lecture-preview-text${text ? '' : ' is-empty'}`, text || '此题暂无可显示的文字。'));
    questions.append(article);
    if (typeof question?.loadImages !== 'function') return;
    const media = el('div', 'lecture-preview-media');
    const status = el('p', 'lecture-preview-image-status', '题图将在阅读到此处时加载');
    let busy = false;
    const retry = button('重试本题题图', () => start());
    retry.hidden = true;
    media.append(status, retry);
    article.append(media);
    function start() {
      if (busy || !alive(stamp)) return;
      busy = true;
      retry.hidden = true;
      status.textContent = '正在读取题图…';
      enqueue(async () => {
        try {
          const images = await question.loadImages({ signal: requests.signal });
          if (!alive(stamp)) return;
          if (!Array.isArray(images)) throw Error('题图列表无效');
          media.replaceChildren();
          if (!images.length) media.append(el('p', 'lecture-preview-image-status', '本题没有题图。'));
          images.forEach((image, imageIndex) => appendImage(media, image, label, imageIndex, stamp));
        } catch {
          if (!alive(stamp)) return;
          status.textContent = '本题题图暂时无法读取，文字仍可阅读。';
          retry.hidden = false;
        } finally { busy = false; scheduleView(); }
      }, stamp);
    }
    observe(media, start);
  }

  async function loadContent() {
    if (!alive()) return;
    const stamp = ++generation;
    requests.abort();
    requests = new AbortController();
    queue.length = 0;
    awaitingView.clear();
    intersection?.disconnect();
    questions.replaceChildren();
    actions.replaceChildren();
    note.hidden = notice.hidden = retryLoad.hidden = true;
    count.textContent = '正在读取…';
    message.textContent = '正在打开本讲内容…';
    state.hidden = false;
    try {
      if (!alive(stamp)) return;
      const spec = await load({ signal: requests.signal });
      if (!alive(stamp)) return;
      const items = Array.isArray(spec?.questions) ? spec.questions : [];
      count.textContent = `本讲 ${items.length} 题`;
      note.textContent = String(spec?.note || '');
      note.hidden = !note.textContent;
      for (const action of Array.isArray(spec?.actions) ? spec.actions : []) {
        if (!action?.label || typeof action.run !== 'function') continue;
        const control = button(action.label, async () => {
          if (!alive(stamp)) return;
          control.disabled = true;
          notice.hidden = true;
          try { await action.run(); }
          catch { if (alive(stamp)) { notice.textContent = '操作暂未完成，请稍后重试。'; notice.hidden = false; } }
          finally { if (alive(stamp)) control.disabled = false; }
        });
        actions.append(control);
      }
      if (!items.length) {
        message.textContent = '尚未有该讲的拆分内容。';
        return;
      }
      state.hidden = true;
      items.forEach((question, index) => appendQuestion(question, index, stamp));
    } catch {
      if (!alive(stamp)) return;
      count.textContent = '本讲内容';
      message.textContent = '本讲内容暂时无法打开，请重试。';
      retryLoad.hidden = false;
    }
  }
  function cleanup() {
    if (stopped) return;
    stopped = true;
    generation++;
    events.abort();
    requests.abort();
    queue.length = 0;
    awaitingView.clear();
    intersection?.disconnect();
    replacement.disconnect();
    if (scrollFrame !== null) cancelAnimationFrame(scrollFrame);
    for (const image of questions.querySelectorAll('img')) image.removeAttribute('src');
    if (activeCleanup === cleanup) activeCleanup = undefined;
    if (root.isConnected) dialog.classList.remove('lecture-preview-dialog');
    if (!dialog.open && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
  }
  const controller = {
    ready: null,
    close() { if (stopped) return; if (root.isConnected && dialog.open) dialog.close(); cleanup(); }
  };
  const replacement = new MutationObserver(() => { if (!alive() || !dialog.open) cleanup(); });
  replacement.observe(dialog, { childList: true, subtree: true, attributes: true, attributeFilter: ['open'] });
  on(dialog, 'close', () => { if (!dialog.open) cleanup(); });
  on(window, 'hashchange', () => controller.close());
  on(viewport, 'scroll', scheduleView);
  on(window, 'resize', scheduleView);
  on(retryLoad, 'click', () => { if (alive()) controller.ready = loadContent(); });
  activeCleanup = cleanup;
  controller.ready = Promise.resolve().then(loadContent);
  return controller;
}
