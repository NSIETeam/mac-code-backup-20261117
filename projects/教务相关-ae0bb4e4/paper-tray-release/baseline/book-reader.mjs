// Self-hosted PDF.js renders one page at a time; signed file URLs never leave this origin.
let activeReader;
let pdfEngine;
const asset = path => new URL(`./vendor/pdfjs/${path}`, import.meta.url).href;

export function bookResourceUrl(value, base, safeResource) {
  if (typeof value !== 'string' || !value.trim()) throw Error('暂时没有可预览的文件');
  const checked = safeResource ? safeResource(value, base) : value;
  const url = new URL(checked, base);
  if (!['https:', 'http:'].includes(url.protocol) || url.origin !== new URL(base).origin || url.username || url.password) {
    throw Error('文件来源无效，请重新打开');
  }
  return url.href;
}

export function bookPageNumber(value, total) {
  const number = Number(value);
  return Math.min(Math.max(1, Math.trunc(Number.isFinite(number) ? number : 1)), Math.max(1, total));
}

// A supplied range is strict: invalid lesson metadata must never expose the full book.
export function bookPageRange(value, documentPages) {
  const limited = value !== undefined;
  const start = limited ? value?.start : 1;
  const end = limited ? value?.end : documentPages;
  if (!Number.isInteger(documentPages) || documentPages < 1 ||
      (limited && (value === null || typeof value !== 'object' || Array.isArray(value))) ||
      !Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end < start || end > documentPages) {
    throw Object.assign(Error('本讲页码范围无效，无法预览。请检查讲次页码后重试。'), { name: 'BookPageRangeError' });
  }
  return Object.freeze({ start, end, count: end - start + 1, limited });
}

export function bookSourcePage(value, range) {
  return range.start + bookPageNumber(value, range.count) - 1;
}

export async function bookDestinationPage(pdf, dest) {
  const target = typeof dest === 'string' ? await pdf.getDestination(dest) : dest;
  if (!Array.isArray(target) || !target.length) return null;
  const ref = target[0];
  const index = Number.isInteger(ref) ? ref : ref && typeof ref === 'object' ? await pdf.getPageIndex(ref) : -1;
  return Number.isInteger(index) && index >= 0 && index < pdf.numPages ? index + 1 : null;
}

function getEngine() {
  const modern = typeof Map.prototype.getOrInsertComputed === 'function' && typeof Uint8Array.prototype.toHex === 'function' && typeof Uint8Array.fromBase64 === 'function' && typeof Uint8Array.prototype.toBase64 === 'function' && typeof Promise.withResolvers === 'function' && typeof URL.parse === 'function';
  pdfEngine ||= (modern ? import('./vendor/pdfjs/pdf.min.mjs') : import('./vendor/pdfjs/legacy/pdf.min.mjs')).then(engine => {
    engine.GlobalWorkerOptions.workerSrc = asset(modern ? 'pdf.worker.min.mjs' : 'legacy/pdf.worker.min.mjs');
    return engine;
  }).catch(error => { pdfEngine = null; throw error; });
  return pdfEngine;
}

/** Opens immediately. `ready` settles after the initial PDF page or its visible error state. */
export function openBookReader({ title = '讲义预览', load, returnFocus, mount }, ui) {
  // Reuse the open native dialog: a queued close event from the old reader could
  // otherwise arrive after the new reader installs its own close listener.
  activeReader?.dispose();
  const dialog = mount || ui.dialog;
  const markup = `<section class="book-reader" aria-label="讲义阅读器">
    <div class="book-reader-toolbar" aria-label="阅读工具">
      <button type="button" data-reader-prev aria-label="上一页" disabled>‹</button>
      <form class="book-reader-page-form"><label>第 <input data-reader-page type="number" inputmode="numeric" min="1" value="1" aria-label="跳转页码" disabled> <span data-reader-total>/ —</span> 页</label></form>
      <button type="button" data-reader-next aria-label="下一页" disabled>›</button>
      <label class="book-reader-zoom-label"><span class="book-reader-sr">缩放</span><select data-reader-zoom aria-label="缩放" disabled><option value="fit">适合宽度</option><option value="0.75">75%</option><option value="1">100%</option><option value="1.25">125%</option><option value="1.5">150%</option><option value="2">200%</option></select></label>
      <span class="book-reader-progress" aria-live="polite" data-reader-progress></span>
    </div>
    <div class="book-reader-content">
      <div class="book-reader-viewport" tabindex="0" aria-label="讲义页面">
        <div class="book-reader-state" role="status" aria-live="polite"><span class="book-reader-spinner" aria-hidden="true"></span><p data-reader-message>正在打开讲义…</p><button type="button" data-reader-retry hidden>重试</button>
          <form class="book-reader-password" hidden><label>PDF 密码<input type="password" autocomplete="off" aria-label="PDF 密码" required></label><button type="submit">打开</button></form>
        </div>
        <div class="book-reader-paper" hidden></div>
      </div>
      <details class="book-reader-sidebar"><summary>目录与版本</summary><div class="book-reader-side-content"></div></details>
    </div>
    <p class="book-reader-notice" role="status" hidden></p>
  </section>`;
  if (mount) { mount.innerHTML = markup; mount.classList.add('book-reader-embedded'); }
  else { ui.modal(title, markup, true); dialog.classList.add('book-reader-dialog'); }
  const root = dialog.querySelector('.book-reader');
  const find = selector => root.querySelector(selector);
  const viewport = find('.book-reader-viewport');
  const paper = find('.book-reader-paper');
  const state = find('.book-reader-state');
  const message = find('[data-reader-message]');
  const spinner = find('.book-reader-spinner');
  const retry = find('[data-reader-retry]');
  const passwordForm = find('.book-reader-password');
  const pageInput = find('[data-reader-page]');
  const prev = find('[data-reader-prev]');
  const next = find('[data-reader-next]');
  const zoom = find('[data-reader-zoom]');
  const total = find('[data-reader-total]');
  const progress = find('[data-reader-progress]');
  const sidebar = find('.book-reader-sidebar');
  const sideContent = find('.book-reader-side-content');
  const notice = find('.book-reader-notice');
  const events = new AbortController();
  let sidebarEvents = new AbortController();
  const on = (node, type, fn) => node.addEventListener(type, fn, { signal: events.signal });
  let stopped = false, generation = 0, renderGeneration = 0, pageNumber = 1;
  let pageRange = null;
  let pdf = null, loadingTask = null, renderTask = null, renderQueue = Promise.resolve();
  let selectedLoader = load, passwordCallback = null, sidebarGeneration = 0, resizeFrame = null;
  let lastFitWidth = 0;
  const live = stamp => !stopped && root.isConnected && generation === stamp;
  sidebar.open = window.matchMedia('(min-width: 760px)').matches;

  function setState(text, mode = 'loading') {
    state.hidden = false;
    paper.hidden = true;
    message.textContent = text;
    spinner.hidden = mode !== 'loading';
    retry.hidden = mode !== 'error';
    passwordForm.hidden = mode !== 'password';
    progress.textContent = '';
  }
  function setControls(enabled) {
    pageInput.disabled = zoom.disabled = !enabled;
    prev.disabled = !enabled || pageNumber <= 1;
    next.disabled = !enabled || pageNumber >= (pageRange?.count || 1);
    pageInput.value = String(pageNumber);
    pageInput.max = String(pageRange?.count || 1);
    total.textContent = `/ ${pageRange?.count || '—'}`;
  }
  function showNotice(text) {
    notice.textContent = text;
    notice.hidden = !text;
  }
  function actionButton(label, run) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('click', async () => {
      button.disabled = true;
      showNotice('');
      try { await run(); } catch { if (!stopped && root.isConnected) showNotice('操作暂未完成，请稍后重试。'); }
      finally { button.disabled = false; }
    }, { signal: sidebarEvents.signal });
    return button;
  }
  function sideGroup(label, items) {
    const valid = (items || []).filter(item => item?.label && typeof item.run === 'function');
    if (!valid.length) return;
    const section = document.createElement('section');
    const heading = document.createElement('h3');
    heading.textContent = label;
    section.append(heading, ...valid.map(item => actionButton(item.label, item.run)));
    sideContent.append(section);
  }
  function renderSidebar(spec) {
    sidebarGeneration++;
    sidebarEvents.abort();
    sidebarEvents = new AbortController();
    sideContent.replaceChildren();
    sideGroup('操作', spec.actions);
    sideGroup('讲次', spec.sections);
    sideGroup('源文件', spec.sources);
    if (spec.versions?.length) {
      const label = document.createElement('label');
      label.className = 'book-reader-version';
      label.textContent = '版本';
      const select = document.createElement('select');
      select.setAttribute('aria-label', '教材版本');
      const current = document.createElement('option');
      current.textContent = '当前查看版本';
      current.value = '';
      select.append(current);
      spec.versions.forEach((version, index) => {
        if (typeof version.load !== 'function') return;
        const option = document.createElement('option');
        option.textContent = version.label || `版本 ${index + 1}`;
        option.value = String(index);
        select.append(option);
      });
      select.addEventListener('change', () => {
        if (select.value === '') return;
        selectedLoader = spec.versions[Number(select.value)].load;
        void loadBook(selectedLoader);
      }, { signal: sidebarEvents.signal });
      label.append(select);
      sideContent.append(label);
    }
    if (spec.note) {
      const note = document.createElement('p');
      note.className = 'book-reader-side-note';
      note.textContent = spec.note;
      sideContent.append(note);
    }
  }
  async function addOutline(documentPdf, stamp) {
    const sidebarStamp = sidebarGeneration;
    try {
      const outline = await documentPdf.getOutline();
      if (!live(stamp) || sidebarStamp !== sidebarGeneration || !outline?.length) return;
      const section = document.createElement('section');
      const heading = document.createElement('h3');
      heading.textContent = '书内目录';
      section.append(heading);
      let count = 0;
      const append = (items, depth) => {
        for (const item of items) {
          if (count >= 150) break;
          if (item.dest) {
            count++;
            const button = actionButton(item.title || '未命名章节', async () => {
              const target = await bookDestinationPage(documentPdf, item.dest);
              if (!live(stamp)) return;
              if (!target) { showNotice('此目录暂时没有可跳转的页码。'); return; }
              if (window.matchMedia('(max-width: 759px)').matches) sidebar.open = false;
              await requestPage(target, true);
            });
            button.style.paddingInlineStart = `${12 + Math.min(depth, 3) * 10}px`;
            section.append(button);
          }
          if (item.items?.length) append(item.items, depth + 1);
        }
      };
      append(outline, 0);
      if (count) sideContent.prepend(section);
    } catch { /* A malformed optional outline must not block reading the PDF. */ }
  }
  async function cancelRender() {
    renderGeneration++;
    renderTask?.cancel();
    await renderQueue.catch(() => {});
  }
  async function releaseDocument() {
    const oldLoading = loadingTask, oldPdf = pdf;
    loadingTask = pdf = null;
    pageRange = null;
    pageNumber = 1;
    passwordCallback = null;
    passwordForm.querySelector('input').value = '';
    for (const canvas of paper.querySelectorAll('canvas')) { canvas.width = canvas.height = 0; }
    paper.replaceChildren();
    const renderingStopped = cancelRender();
    // Stop fetches immediately too: getPage may still be waiting for a network range.
    const documentStopped = oldLoading ? oldLoading.destroy() : oldPdf ? oldPdf.destroy() : Promise.resolve();
    await Promise.allSettled([renderingStopped, documentStopped]);
  }
  function requestPage(value, scrollTop = false) {
    if (!pdf || !pageRange || stopped) return Promise.resolve();
    pageNumber = bookPageNumber(value, pageRange.count);
    const sourcePage = bookSourcePage(pageNumber, pageRange), range = pageRange;
    const target = pageNumber, stamp = generation, renderStamp = ++renderGeneration, documentPdf = pdf;
    renderTask?.cancel();
    setControls(true);
    progress.textContent = '正在绘制…';
    renderQueue = renderQueue.catch(() => {}).then(async () => {
      if (!live(stamp) || renderStamp !== renderGeneration) return;
      const page = await documentPdf.getPage(sourcePage);
      if (!live(stamp) || renderStamp !== renderGeneration) return;
      const natural = page.getViewport({ scale: 1 });
      const available = Math.max(160, viewport.clientWidth - 32);
      lastFitWidth = available;
      const scale = zoom.value === 'fit' ? available / natural.width : Number(zoom.value);
      const bounds = page.getViewport({ scale });
      const ratio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(16000000 / (bounds.width * bounds.height)));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.floor(bounds.width * ratio));
      canvas.height = Math.max(1, Math.floor(bounds.height * ratio));
      canvas.style.width = `${bounds.width}px`;
      canvas.style.height = `${bounds.height}px`;
      canvas.setAttribute('role', 'img');
      canvas.setAttribute('aria-label', `讲义第 ${target} 页，共 ${range.count} 页${range.limited ? `；原稿第 ${sourcePage} 页，共 ${documentPdf.numPages} 页` : ''}`);
      const ownRender = page.render({ canvas, viewport: bounds, transform: ratio === 1 ? null : [ratio, 0, 0, ratio, 0, 0], background: '#ffffff' });
      renderTask = ownRender;
      try {
        await ownRender.promise;
        if (!live(stamp) || renderStamp !== renderGeneration) { canvas.width = canvas.height = 0; return; }
        for (const old of paper.querySelectorAll('canvas')) { old.width = old.height = 0; }
        paper.replaceChildren(canvas);
        state.hidden = true;
        paper.hidden = false;
        progress.textContent = range.limited ? `原稿第 ${sourcePage} 页` : '';
        if (scrollTop) viewport.scrollTop = viewport.scrollLeft = 0;
      } catch (error) {
        canvas.width = canvas.height = 0;
        if (error?.name !== 'RenderingCancelledException') throw error;
      } finally { if (renderTask === ownRender) renderTask = null; }
    }).catch(error => {
      if (!live(stamp) || renderStamp !== renderGeneration || error?.name === 'RenderingCancelledException') return;
      setState('这一页暂时无法显示，请重试或下载原稿查看。', 'error');
    });
    return renderQueue;
  }
  async function loadBook(loader) {
    const stamp = ++generation;
    pageRange = null;
    pageNumber = 1;
    setState('正在打开讲义…');
    setControls(false);
    showNotice('');
    await releaseDocument();
    if (!live(stamp)) return;
    try {
      const spec = await loader();
      if (!live(stamp)) return;
      if (spec?.title) {
        const heading = dialog.querySelector(':scope > .heading h2');
        if (heading) { heading.textContent = spec.title; heading.title = spec.title; }
      }
      renderSidebar(spec || {});
      if (!spec?.file?.url) {
        setState('此源文件暂不能直接预览，可在「源文件」中下载原稿查看。', 'empty');
        return;
      }
      const url = bookResourceUrl(spec.file.url, ui.apiBase || location.href, ui.safeResource);
      const engine = await getEngine();
      if (!live(stamp)) return;
      const task = engine.getDocument({
        url, cMapUrl: asset('cmaps/'), cMapPacked: true, standardFontDataUrl: asset('standard_fonts/'), wasmUrl: asset('wasm/'),
        isEvalSupported: false, enableXfa: false, useWasm: false, withCredentials: false
      });
      loadingTask = task;
      task.onPassword = (updatePassword, reason) => {
        if (!live(stamp)) return;
        passwordCallback = updatePassword;
        setState(reason === engine.PasswordResponses.INCORRECT_PASSWORD ? '密码不正确，请重新输入。' : '此 PDF 需要密码才能预览。', 'password');
        passwordForm.querySelector('input').focus();
      };
      task.onProgress = data => {
        if (!live(stamp) || passwordCallback || !data.total) return;
        message.textContent = `正在打开讲义… ${Math.min(100, Math.round(data.loaded / data.total * 100))}%`;
      };
      const loaded = await task.promise;
      if (!live(stamp)) { await task.destroy().catch(() => {}); return; }
      const loadedRange = bookPageRange(spec.file.pageRange, loaded.numPages);
      pdf = loaded;
      pageRange = loadedRange;
      pageNumber = 1;
      setControls(true);
      if (!loadedRange.limited) void addOutline(loaded, stamp);
      await requestPage(1, true);
    } catch (error) {
      if (!live(stamp)) return;
      if (error?.name === 'BookPageRangeError') {
        await releaseDocument();
        if (!live(stamp)) return;
        setControls(false);
        setState(error.message, 'error');
        return;
      }
      const text = error?.name === 'PasswordException' ? '此 PDF 需要密码，请重试或下载原稿查看。'
        : error?.name === 'InvalidPDFException' ? '这份文件不是有效的 PDF，可下载原稿查看。'
        : '讲义暂时无法打开，链接可能已过期。请重试或下载原稿查看。';
      setState(text, 'error');
    }
  }
  function cleanup() {
    if (stopped) return;
    stopped = true;
    generation++;
    events.abort();
    sidebarEvents.abort();
    resizeObserver?.disconnect();
    replacementObserver.disconnect();
    if (resizeFrame != null) cancelAnimationFrame(resizeFrame);
    void releaseDocument();
    if (activeReader === controller) activeReader = null;
    if (root.isConnected) dialog.classList.remove(mount ? 'book-reader-embedded' : 'book-reader-dialog');
    if (!mount && !dialog.open && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
  }
  const controller = {
    ready: null,
    dispose: cleanup,
    close() { if (stopped) return; if (!mount && root.isConnected && dialog.open) dialog.close(); cleanup(); }
  };
  activeReader = controller;
  on(dialog, 'close', () => { if (!dialog.open) cleanup(); });
  on(window, 'hashchange', () => controller.close());
  on(prev, 'click', () => void requestPage(pageNumber - 1, true));
  on(next, 'click', () => void requestPage(pageNumber + 1, true));
  on(find('.book-reader-page-form'), 'submit', event => { event.preventDefault(); void requestPage(pageInput.value, true); });
  on(pageInput, 'change', () => void requestPage(pageInput.value, true));
  on(zoom, 'change', () => void requestPage(pageNumber));
  on(retry, 'click', () => void loadBook(selectedLoader));
  on(passwordForm, 'submit', event => {
    event.preventDefault();
    const input = passwordForm.querySelector('input'), callback = passwordCallback;
    if (!callback || !input.value) return;
    const password = input.value;
    input.value = '';
    passwordCallback = null;
    setState('正在打开讲义…');
    callback(password);
  });
  on(viewport, 'keydown', event => {
    if (event.target !== viewport || event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      void requestPage(pageNumber + (event.key === 'ArrowLeft' ? -1 : 1), true);
    }
  });
  const resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => {
    if (!pdf || zoom.value !== 'fit' || Math.abs(viewport.clientWidth - 32 - lastFitWidth) < 2) return;
    if (resizeFrame != null) cancelAnimationFrame(resizeFrame);
    resizeFrame = requestAnimationFrame(() => { resizeFrame = null; void requestPage(pageNumber); });
  }) : null;
  resizeObserver?.observe(viewport);
  const replacementObserver = new MutationObserver(() => { if (!root.isConnected) cleanup(); });
  replacementObserver.observe(mount?.parentNode || dialog, { childList: true });
  controller.ready = loadBook(selectedLoader);
  return controller;
}
