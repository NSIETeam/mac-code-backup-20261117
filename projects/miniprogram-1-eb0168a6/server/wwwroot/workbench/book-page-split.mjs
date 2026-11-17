import {formatQueueEstimate, queueActive} from './queue-estimate.mjs?v=20261104-queue-review-v4';
// Page boundaries are source PDF page numbers, never reflowed paragraph offsets.
const MAX_PAGES = 500;
const MAX_HISTORY = 50;
let enginePromise;
const asset = path => new URL(`./vendor/pdfjs/${path}`, import.meta.url).href;

export function pageSplitCuts(cuts, pageCount) {
  if (!Number.isInteger(pageCount) || pageCount < 1 || pageCount > MAX_PAGES) throw Error('原稿页数无效');
  if (!Array.isArray(cuts) || cuts.some(n => !Number.isInteger(n) || n < 1 || n >= pageCount)) throw Error('分割线只能放在原稿两页之间');
  const unique = [...new Set(cuts)].sort((a, b) => a - b);
  if (unique.length > 199) throw Error('一次最多拆为 200 讲');
  return unique;
}
export function pageSplitRanges(pageCount, cuts, startSequence = 1, titles = []) {
  const ends = [...pageSplitCuts(cuts, pageCount), pageCount];
  if (!Number.isSafeInteger(startSequence) || startSequence < 1 || startSequence + ends.length - 1 > 2147483647) throw Error('起始讲次须为正整数');
  if (!Array.isArray(titles) || titles.some(t => typeof t !== 'string' || t.length > 120)) throw Error('讲次标题无效');
  return ends.map((end, i) => ({ start: i ? ends[i - 1] + 1 : 1, end, sequence: startSequence + i, title: (titles[i] || '').trim() }));
}
export function pageSplitWindow(page, pageCount) {
  if (!Number.isInteger(pageCount) || pageCount < 1 || pageCount > MAX_PAGES) return [];
  const center = Math.min(pageCount, Math.max(1, Math.trunc(Number(page) || 1)));
  return Array.from({ length: Math.min(pageCount, center + 2) - Math.max(1, center - 2) + 1 }, (_, i) => Math.max(1, center - 2) + i);
}
export function pageSplitSourceUrl(value, base) {
  if (typeof value !== 'string' || !value.trim()) throw Error('原稿预览链接无效');
  const origin = new URL(base), url = new URL(value, origin);
  if (!['http:', 'https:'].includes(url.protocol) || url.origin !== origin.origin || url.username || url.password) throw Error('原稿必须来自当前工作台');
  return url.href;
}
export function createPageSplitState({ pageCount, cuts = [], startSequence = 1, titles = [] }) {
  let current = { cuts: pageSplitCuts(cuts, pageCount), startSequence, titles: new Map() };
  pageSplitRanges(pageCount, current.cuts, startSequence, titles).forEach(r => current.titles.set(r.start, r.title));
  const history = [];
  const save = () => { history.push({ cuts: [...current.cuts], startSequence: current.startSequence, titles: new Map(current.titles) }); if (history.length > MAX_HISTORY) history.shift(); };
  const ranges = () => pageSplitRanges(pageCount, current.cuts, current.startSequence).map(r => ({ ...r, title: current.titles.get(r.start) || '' }));
  return {
    get() { return { pageCount, cuts: [...current.cuts], startSequence: current.startSequence, titles: ranges().map(r => r.title), ranges: ranges(), canUndo: history.length > 0 }; },
    toggle(page) { pageSplitCuts([page], pageCount); const cuts = current.cuts.includes(page) ? current.cuts.filter(n => n !== page) : pageSplitCuts([...current.cuts, page], pageCount); pageSplitRanges(pageCount, cuts, current.startSequence); save(); current.cuts = cuts; return this.get(); },
    clear() { if (current.cuts.length) { save(); current.cuts = []; } return this.get(); },
    undo() { if (history.length) current = history.pop(); return this.get(); },
    title(start, value) { if (!ranges().some(r => r.start === start) || typeof value !== 'string' || value.length > 120) throw Error('讲次标题无效'); current.titles.set(start, value); return this.get(); },
    sequence(value) { pageSplitRanges(pageCount, current.cuts, value); current.startSequence = value; return this.get(); }
  };
}
async function getEngine() {
  const modern = typeof Map.prototype.getOrInsertComputed === 'function' && typeof Uint8Array.prototype.toHex === 'function' && typeof Uint8Array.fromBase64 === 'function' && typeof Uint8Array.prototype.toBase64 === 'function' && typeof Promise.withResolvers === 'function' && typeof URL.parse === 'function';
  enginePromise ||= (modern ? import('./vendor/pdfjs/pdf.min.mjs') : import('./vendor/pdfjs/legacy/pdf.min.mjs')).then(engine => {
    engine.GlobalWorkerOptions.workerSrc = asset(modern ? 'pdf.worker.min.mjs' : 'legacy/pdf.worker.min.mjs');
    return engine;
  }).catch(error => { enginePromise = null; throw error; });
  return enginePromise;
}
const symbols = {
  undo: 'M9 5 4 10l5 5M4 10h10a6 6 0 0 1 0 12',
  clear: 'M5 6h14M9 6V4h6v2M7 6l1 14h8l1-14M10 10v6M14 10v6',
  split: 'M4 12h16M9 5l3-3 3 3M12 2v6M9 19l3 3 3-3M12 16v6'
};

/**
 * mountBookPageSplit(host, {loadPreview({start,signal}), submit(payload), onSubmitted,
 *   onError, apiBase, revision?, cuts?, startSequence?, titles?})
 * returns {ready, destroy, getState, refresh, submit}. Keep this mount across ETA polls.
 * Only loadPreview prepares PDF metadata; all page rendering stays in this browser.
 */
export function mountBookPageSplit(host, options) {
  if (!host || typeof options?.loadPreview !== 'function' || typeof options?.submit !== 'function') throw Error('缺少原稿拆讲入口');
  const doc = host.ownerDocument || globalThis.document;
  const win = doc.defaultView || globalThis;
  const raf = fn => (win.requestAnimationFrame || (cb => setTimeout(cb, 0)))(fn);
  const caf = id => (win.cancelAnimationFrame || clearTimeout)(id);
  const create = (tag, className, text) => { const node = doc.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = String(text); return node; };
  const root = create('section', 'book-page-split'); root.setAttribute('aria-label', '按原稿页间拆讲次');
  const listeners = [], planListeners = [];
  const onPlan = (node, event, fn) => { node.addEventListener(event, fn); planListeners.push(() => node.removeEventListener(event, fn)); };
  const on = (node, event, fn) => { node.addEventListener(event, fn); listeners.push(() => node.removeEventListener(event, fn)); };
  const button = (label, run, className = '', symbol) => {
    const node = create('button', className); node.type = 'button';
    if (symbol) { const svg = doc.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true'); const path = doc.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', symbols[symbol]); svg.append(path); node.append(svg); }
    node.append(create('span', '', label)); if (run) on(node, 'click', run); return node;
  };
  const top = create('div', 'page-split-toolbar');
  const instruction = create('p', '', '在两页之间点击横栏，即可分为下一讲。');
  const undo = button('撤销', () => change(() => model.undo()), '', 'undo');
  const clear = button('清空分割线', () => change(() => model.clear()), '', 'clear');
  top.append(instruction, undo, clear);
  const status = create('p', 'page-split-status', '正在准备原稿预览…'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  const retry = button('重新加载预览', () => { automaticRefreshes = 0; void refresh(true, true); }, 'page-split-retry'); retry.hidden = true;
  const layout = create('div', 'page-split-layout');
  const left = create('div', 'page-split-source');
  const pageToolbar = create('form', 'page-split-page-nav');
  const pageLabel = create('label', '', '原稿第 '), pageInput = create('input'); pageInput.type = 'number'; pageInput.min = '1'; pageInput.value = '1'; pageInput.setAttribute('aria-label', '跳转原稿页码');
  const pageTotal = create('span', '', ' / — 页'); pageLabel.append(pageInput, pageTotal);
  const jumpButton = button('跳转', () => jump(Number(pageInput.value))); pageToolbar.append(pageLabel, jumpButton);
  const zoomLabel = create('label', 'page-split-zoom', '缩放 '), zoomSelect = create('select'); zoomSelect.setAttribute('aria-label', '原稿缩放');
  for (const [value, text] of [[1, '适宽'], [1.25, '125%'], [1.5, '150%'], [2, '200%']]) { const option = create('option', '', text); option.value = String(value); zoomSelect.append(option); }
  zoomSelect.value = '1'; zoomLabel.append(zoomSelect); pageToolbar.append(zoomLabel);
  on(zoomSelect, 'change', () => { const next = Number(zoomSelect.value); if (!pdf || ![1, 1.25, 1.5, 2].includes(next)) { zoomSelect.value = String(zoom); return; } zoom = next; resizePages(); });
  on(pageToolbar, 'submit', event => { event.preventDefault(); jump(Number(pageInput.value)); });
  const viewport = create('div', 'page-split-viewport'); viewport.tabIndex = 0; viewport.setAttribute('aria-label', '可滚动原稿预览');
  const pages = create('div', 'page-split-pages'); viewport.append(pages); left.append(pageToolbar, viewport);
  const right = create('aside', 'page-split-plan'); right.setAttribute('aria-label', '拆分后的讲次');
  const planTitle = create('h2', '', '讲次清单');
  const sequenceLabel = create('label', 'page-split-sequence', '从第 '), sequenceInput = create('input'); sequenceInput.type = 'number'; sequenceInput.min = '1'; sequenceInput.setAttribute('aria-label', '起始讲次'); sequenceLabel.append(sequenceInput, create('span', '', ' 讲开始'));
  on(sequenceInput, 'change', () => { if (!editable()) return; try { model.sequence(Number(sequenceInput.value)); drawPlan(); } catch (error) { sequenceInput.value = String(model.get().startSequence); report(error); } });
  const list = create('ol', 'page-split-list');
  const policy = create('p', 'page-split-hint', '所有页面都会保留；拆分后继续核对题目，再发布讲义。');
  const submitButton = button('确认拆分', () => { void submit(); }, 'page-split-submit');
  right.append(planTitle, sequenceLabel, list, policy, submitButton); layout.append(left, right); root.append(top, status, retry, layout); host.replaceChildren(root);

  let stopped = false, generation = 0, requestGeneration = 0, requestAbort = new AbortController();
  let meta = null, model = null, sourceVersion = null, revision = null, phase = 'loading', stale = false, saved = false;
  let pdf = null, loadingTask = null, openPromise = null, renderPromise = Promise.resolve(), renderBusy = false;
  let currentPage = 1, wanted = new Set(), slots = [], scrollFrame = null, pollTimer = null, loadBusy = false;
  let automaticRefreshes = 0, submitting = null, layoutWidth = 0, zoom = 1;
  const alive = stamp => !stopped && root.isConnected && (stamp === undefined || stamp === generation);
  const previewBusy = spec => spec?.busy === true || (spec?.busy === undefined && spec?.canSplit === false && queueActive(spec?.queue, 'unknown') && !spec?.titles?.length && !spec?.cuts?.length);
  const readyMessage = () => { const text = meta?.canSplit ? '点页间横栏分讲，右侧讲次清单会同步更新。' : meta?.lockReason || '此讲义暂不能再次拆分，可查看原稿。'; const eta = formatQueueEstimate(meta?.queue, previewBusy(meta) ? 'processing' : 'done'); return text + (eta ? ' ' + eta : ''); };
  const editable = () => alive() && model && meta?.canSplit === true && phase === 'ready' && !stale && !saved && !submitting;
  function message(text, error = false) { status.textContent = text; status.classList.toggle('is-error', error); status.hidden = !text; }
  function report(error) { if (!alive()) return; message(error?.message || '操作暂未完成，请重试。', true); try { options.onError?.(error); } catch { /* host error UI must not lose the plan */ } }
  function controls() {
    const state = model?.get();
    const hasPlan = meta?.hasPlan ?? (!!meta?.cuts?.length || !!meta?.titles?.length);
    const previewOnly = !!meta && meta.canSplit === false && !previewBusy(meta) && !hasPlan;
    sequenceLabel.hidden = list.hidden = submitButton.hidden = undo.hidden = clear.hidden = previewOnly;
    planTitle.textContent = previewOnly ? '原稿预览' : state ? `拆分后共 ${state.ranges.length} 讲` : '讲次清单';
    policy.textContent = previewOnly ? meta.lockReason || '已有讲次保持不变，请返回讲次列表查看。' : '所有页面都会保留；拆分后继续核对题目，再发布讲义。';
    instruction.textContent = previewOnly ? '查看原始讲义，已有讲次保持不变。' : '在两页之间点击横栏，即可分为下一讲。';
    undo.disabled = !editable() || !state?.canUndo; clear.disabled = !editable() || !state?.cuts.length;
    sequenceInput.disabled = !editable(); submitButton.disabled = !editable(); pageInput.disabled = jumpButton.disabled = zoomSelect.disabled = !pdf;
    submitButton.querySelector('span').textContent = submitting ? '正在提交…' : saved ? '已提交拆分' : state ? `确认拆为 ${state.ranges.length} 讲` : '确认拆分';
    slots.forEach(slot => { if (slot.cut) { slot.cut.disabled = !editable(); slot.cut.hidden = previewOnly; } });
    for (const field of list.querySelectorAll('input')) field.disabled = !editable();
  }
  function highlight() {
    if (!model) return;
    const active = model.get().ranges.find(r => currentPage >= r.start && currentPage <= r.end)?.start;
    for (const item of list.children) { const selected = Number(item.dataset.start) === active; item.classList.toggle('is-current', selected); item.setAttribute('aria-current', selected ? 'true' : 'false'); }
  }
  function drawPlan() {
    if (!model || !alive()) { controls(); return; }
    const state = model.get(); sequenceInput.value = String(state.startSequence); planTitle.textContent = `拆分后共 ${state.ranges.length} 讲`;
    planListeners.splice(0).forEach(off => off()); list.replaceChildren();
    for (const range of state.ranges) {
      const row = create('li', 'page-split-lecture'); row.dataset.start = String(range.start);
      const link = button(`第 ${range.sequence} 讲 · 第 ${range.start}${range.end === range.start ? '' : '–' + range.end} 页`, null, 'page-split-range'); onPlan(link, 'click', () => jump(range.start));
      const label = create('label', 'page-split-title'); label.append(create('span', 'page-split-sr', `第 ${range.sequence} 讲标题`));
      const input = create('input'); input.type = 'text'; input.maxLength = 120; input.value = range.title; input.placeholder = meta?.defaultTitle ? `默认：${String(meta.defaultTitle).slice(0, 120)}` : '标题（可不填）';
      onPlan(input, 'input', () => { if (editable()) model.title(range.start, input.value); }); label.append(input); row.append(link, label); list.append(row);
    }
    for (const slot of slots) if (slot.cut) { const selected = state.cuts.includes(slot.number); slot.cut.setAttribute('aria-pressed', String(selected)); slot.cut.classList.toggle('is-cut', selected); slot.cut.querySelector('span').textContent = selected ? `已在第 ${slot.number} 页后分讲 · 点击合并` : `在第 ${slot.number} 页后分为下一讲`; }
    highlight(); controls(); options.onChange?.(getState());
  }
  function change(fn) { if (!editable()) return; try { fn(); drawPlan(); } catch (error) { report(error); } }
  function makePages(count) {
    pages.replaceChildren(); slots = [];
    for (let number = 1; number <= count; number++) {
      const figure = create('figure', 'page-split-page'); figure.dataset.page = String(number);
      const caption = create('figcaption', '', `原稿第 ${number} 页`), paper = create('div', 'page-split-paper'), pending = create('p', 'page-split-page-status', '滚动到此处加载原稿');
      paper.append(pending); figure.append(caption, paper);
      const slot = { number, figure, paper, pending, ratio: Math.SQRT2, canvas: null, task: null, page: null, state: 'idle', stamp: 0, cut: null };
      if (number < count) { slot.cut = button(`在第 ${number} 页后分为下一讲`, () => change(() => model.toggle(number)), 'page-split-cut', 'split'); slot.cut.setAttribute('aria-pressed', 'false'); figure.append(slot.cut); }
      pages.append(figure); slots.push(slot);
    }
    pageInput.max = String(count); pageTotal.textContent = ` / ${count} 页`; resizePages(false); drawPlan();
  }
  function pageTop(slot) { return slot.figure.getBoundingClientRect().top - viewport.getBoundingClientRect().top + viewport.scrollTop; }
  function locatePage() {
    const line = viewport.getBoundingClientRect().top + Math.min(160, viewport.clientHeight * .3);
    let selected = slots[0];
    for (const slot of slots) { if (slot.figure.getBoundingClientRect().top <= line) selected = slot; else break; }
    return selected?.number || 1;
  }
  function jump(number) {
    if (!pdf || !slots.length || !Number.isFinite(number)) return;
    currentPage = Math.min(slots.length, Math.max(1, Math.trunc(number))); viewport.scrollTop = pageTop(slots[currentPage - 1]); pageInput.value = String(currentPage); updateWindow();
  }
  function releaseSlot(slot) {
    slot.stamp++; slot.task?.cancel(); slot.task = null;
    if (slot.canvas) { slot.canvas.width = slot.canvas.height = 0; slot.canvas.remove(); slot.canvas = null; }
    if (slot.page && slot.state !== 'rendering') { slot.page.cleanup?.(); slot.page = null; }
    slot.state = 'idle'; slot.pending.hidden = false; slot.pending.textContent = '滚动到此处加载原稿';
  }
  function resizePages(preserve = true) {
    const anchor = slots[currentPage - 1], before = anchor ? pageTop(anchor) - viewport.scrollTop : 0;
    const fitWidth = Math.max(160, viewport.clientWidth - 24), width = Math.round(fitWidth * zoom); if (Math.abs(width - layoutWidth) < 2) return;
    const factor = layoutWidth ? width / layoutWidth : 1, left = viewport.scrollLeft || 0;
    layoutWidth = width;
    pages.style.width = `${width + 24}px`;
    for (const slot of slots) { slot.paper.style.width = `${width}px`; slot.paper.style.height = `${Math.ceil(width * slot.ratio)}px`; if (slot.cut) slot.cut.style.width = `${fitWidth}px`; releaseSlot(slot); }
    if (preserve && anchor) viewport.scrollTop = pageTop(anchor) - before * factor;
    viewport.scrollLeft = zoom === 1 ? 0 : Math.max(0, Math.min(width + 24 - viewport.clientWidth, (left + viewport.clientWidth / 2) * factor - viewport.clientWidth / 2));
    updateWindow();
  }
  function updateWindow() {
    scrollFrame = null; if (!alive() || !pdf || !slots.length) return;
    currentPage = locatePage(); pageInput.value = String(currentPage); wanted = new Set(pageSplitWindow(currentPage, slots.length));
    for (const slot of slots) if (!wanted.has(slot.number) && (slot.canvas || slot.task)) releaseSlot(slot);
    highlight(); pumpRender();
  }
  function scheduleView() { if (alive() && scrollFrame === null) scrollFrame = raf(updateWindow); }
  function pumpRender() {
    if (renderBusy || !alive() || !pdf) return;
    const slot = [...wanted].map(n => slots[n - 1]).filter(x => x?.state === 'idle').sort((a, b) => Math.abs(a.number - currentPage) - Math.abs(b.number - currentPage))[0];
    if (!slot) return;
    renderBusy = true; slot.state = 'rendering'; const stamp = ++slot.stamp, documentPdf = pdf, generationStamp = generation;
    slot.pending.textContent = '正在绘制原稿…';
    renderPromise = (async () => {
      let page, canvas;
      try {
        page = await documentPdf.getPage(slot.number);
        if (!alive(generationStamp) || slot.stamp !== stamp || !wanted.has(slot.number)) return;
        slot.page = page;
        const natural = page.getViewport({ scale: 1 }), ratio = natural.height / natural.width;
        if (!Number.isFinite(ratio) || ratio <= 0) throw Error('原稿页面尺寸无效');
        const oldHeight = Math.ceil(layoutWidth * slot.ratio), above = pageTop(slot) < viewport.scrollTop;
        slot.ratio = ratio; const height = Math.ceil(layoutWidth * ratio); slot.paper.style.height = `${height}px`;
        if (above) viewport.scrollTop += height - oldHeight;
        const bounds = page.getViewport({ scale: layoutWidth / natural.width });
        const density = Math.min(win.devicePixelRatio || 1, 1.5, Math.sqrt(4_000_000 / (bounds.width * bounds.height)));
        canvas = create('canvas'); canvas.width = Math.max(1, Math.floor(bounds.width * density)); canvas.height = Math.max(1, Math.floor(bounds.height * density)); canvas.style.width = `${bounds.width}px`; canvas.style.height = `${bounds.height}px`; canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', `原稿第 ${slot.number} 页`);
        slot.canvas = canvas; slot.paper.append(canvas);
        const task = page.render({ canvas, viewport: bounds, transform: density === 1 ? null : [density, 0, 0, density, 0, 0], background: '#ffffff' }); slot.task = task;
        await task.promise;
        if (!alive(generationStamp) || slot.stamp !== stamp || !wanted.has(slot.number)) { if (slot.canvas === canvas) releaseSlot(slot); return; }
        slot.pending.hidden = true; slot.state = 'ready';
      } catch (error) {
        if (!alive(generationStamp) || slot.stamp !== stamp || error?.name === 'RenderingCancelledException') return;
        if (slot.canvas) { slot.canvas.width = slot.canvas.height = 0; slot.canvas.remove(); slot.canvas = null; }
        slot.state = 'error'; slot.pending.hidden = false; slot.pending.textContent = '这一页暂时无法显示，请重新加载预览。'; retry.hidden = false;
        if (isLinkError(error) && automaticRefreshes < 1) { automaticRefreshes++; void refresh(false, true); }
      } finally {
        if (slot.stamp === stamp) slot.task = null;
        if (!alive(generationStamp) || slot.stamp !== stamp || !wanted.has(slot.number)) { page?.cleanup?.(); if (slot.page === page) slot.page = null; }
        renderBusy = false; if (alive()) pumpRender();
      }
    })();
  }
  function isLinkError(error) { return [401, 403, 404, 410].includes(error?.status) || ['UnexpectedResponseException', 'MissingPDFException', 'NetworkError'].includes(error?.name); }
  async function releasePdf() {
    const task = loadingTask, documentPdf = pdf; loadingTask = pdf = null; wanted.clear(); slots.forEach(releaseSlot);
    const destroying = task ? task.destroy() : documentPdf?.destroy(); await Promise.allSettled([renderPromise, destroying]);
  }
  function schedulePoll() { clearTimeout(pollTimer); if (alive()) pollTimer = setTimeout(() => { pollTimer = null; void refresh(false); }, Math.max(1000, options.pollMs || 3000)); }
  async function openPdf(spec, force) {
    if (pdf && !force) { phase = 'ready'; message(readyMessage()); controls(); return; }
    if (openPromise) return openPromise;
    openPromise = (async () => {
      const stamp = generation, top = viewport.scrollTop;
      await releasePdf(); if (!alive(stamp)) return;
      const engine = await (options.getPdfEngine || getEngine)(); if (!alive(stamp)) return;
      const task = engine.getDocument({ url: pageSplitSourceUrl(spec.sourceUrl, options.apiBase || win.location.href), cMapUrl: asset('cmaps/'), cMapPacked: true, standardFontDataUrl: asset('standard_fonts/'), wasmUrl: asset('wasm/'), isEvalSupported: false, enableXfa: false, useWasm: false, withCredentials: false });
      loadingTask = task;
      task.onPassword = () => { void task.destroy(); report(Error('此 PDF 有密码，请先解密原稿后重新上传。')); };
      const loaded = await task.promise;
      if (!alive(stamp)) { await task.destroy(); return; }
      if (loaded.numPages !== spec.pageCount) { await task.destroy(); throw Error('预览页数与原稿记录不一致，请重新打开。'); }
      pdf = loaded;
      if (!slots.length) makePages(loaded.numPages);
      phase = 'ready'; message(readyMessage());
      retry.hidden = true; controls(); viewport.scrollTop = top; updateWindow();
    })().finally(() => { openPromise = null; });
    return openPromise;
  }
  async function refresh(start = false, forcePdf = false) {
    if (!alive() || loadBusy || submitting || saved || stale) return;
    loadBusy = true; const requestStamp = ++requestGeneration, stamp = generation;
    clearTimeout(pollTimer); requestAbort.abort(); requestAbort = new AbortController();
    try {
      const spec = await options.loadPreview({ start, signal: requestAbort.signal });
      if (!alive(stamp) || requestStamp !== requestGeneration) return;
      if (!spec || !Number.isInteger(spec.revision) || spec.revision < 0) throw Error('原稿状态无效，请重新打开。');
      if (sourceVersion !== null && (spec.sourceVersion && spec.sourceVersion !== sourceVersion || spec.revision !== revision)) { stale = true; phase = 'conflict'; message('原稿或拆分结果已变更，请重新打开此讲义后再拆分。', true); controls(); return; }
      meta = spec;
      if (spec.state !== 'ready') {
        phase = 'loading'; controls(); retry.hidden = spec.state !== 'failed';
        const eta = formatQueueEstimate(spec.queue, spec.state);
        message((spec.error || spec.lockReason || (spec.state === 'failed' ? '原稿预览准备失败，请重试。' : '正在准备原稿预览，完成后即可点页间横栏分讲。')) + (eta ? ' ' + eta : ''), spec.state === 'failed');
        if (['idle', 'queued', 'processing'].includes(spec.state)) schedulePoll();
        return;
      }
      if (!spec.sourceVersion || typeof spec.sourceVersion !== 'string' || spec.sourceVersion.length > 256) throw Error('缺少原稿版本，请重新打开。');
      pageSplitCuts([], spec.pageCount);
      if (sourceVersion === null) {
        revision = spec.revision; sourceVersion = spec.sourceVersion;
        model = createPageSplitState({ pageCount: spec.pageCount, cuts: options.cuts ?? spec.cuts ?? [], startSequence: options.startSequence ?? spec.startSequence ?? 1, titles: options.titles ?? spec.titles ?? [] });
      } else if (model.get().pageCount !== spec.pageCount) { stale = true; throw Error('原稿页数已变化，请重新打开。'); }
      await openPdf(spec, forcePdf);
      if (alive(stamp) && previewBusy(spec)) schedulePoll();
    } catch (error) {
      if (!alive(stamp) || requestStamp !== requestGeneration) return;
      phase = stale ? 'conflict' : 'error'; retry.hidden = stale; report(error); controls();
      if (isLinkError(error) && automaticRefreshes < 1) { automaticRefreshes++; loadBusy = false; return refresh(false, true); }
    } finally { loadBusy = false; }
  }
  function getState() { return { ...(model?.get() || { pageCount: null, cuts: [], titles: [], ranges: [] }), revision, sourceVersion, phase, currentPage, zoom, canSplit: !!editable(), stale, submitted: saved, renderingPages: slots.filter(s => s.canvas).map(s => s.number) }; }
  async function submit() {
    if (submitting) return submitting;
    if (!editable()) return;
    const plan = model.get(); const payload = { revision, sourceVersion, cuts: plan.cuts, startSequence: plan.startSequence, titles: plan.titles };
    phase = 'submitting';
    submitting = Promise.resolve().then(() => options.submit(payload)).then(result => {
      if (!alive()) return result;
      saved = true; phase = 'submitted'; message(`已确认 ${plan.ranges.length} 讲，正在继续拆分题目。`); controls(); options.onSubmitted?.(result); return result;
    }).catch(error => {
      if (alive()) { if (error?.status === 409) { stale = true; phase = 'conflict'; } else phase = 'ready'; report(error); } return undefined;
    }).finally(() => { submitting = null; if (alive()) controls(); });
    controls(); return submitting;
  }
  function destroy() {
    if (stopped) return; stopped = true; generation++; requestGeneration++; requestAbort.abort(); clearTimeout(pollTimer); if (scrollFrame !== null) caf(scrollFrame);
    listeners.forEach(off => off()); planListeners.splice(0).forEach(off => off()); resizeObserver?.disconnect(); removalObserver?.disconnect(); void releasePdf();
  }
  on(viewport, 'scroll', scheduleView);
  const resizeObserver = typeof win.ResizeObserver === 'function' ? new win.ResizeObserver(() => { if (pdf) resizePages(); }) : null; resizeObserver?.observe(viewport);
  const removalObserver = typeof win.MutationObserver === 'function' ? new win.MutationObserver(() => { if (!root.isConnected) destroy(); }) : null; removalObserver?.observe(host, { childList: true });
  const controller = { ready: null, destroy, getState, refresh: () => refresh(false, true), submit };
  controls(); controller.ready = refresh(true);
  return controller;
}
