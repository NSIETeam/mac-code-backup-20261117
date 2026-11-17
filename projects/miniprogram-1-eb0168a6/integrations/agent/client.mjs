/** Dependency-free HTTP tool adapter. Node 20+ or a browser with fetch/AbortSignal.any. */
export class SiyueError extends Error {
  constructor(message, { status = 0, code = 'NETWORK_ERROR', traceId, retryAfter } = {}) {
    super(message); this.name = 'SiyueError'; Object.assign(this, { status, code, traceId, retryAfter });
  }
}
class Gate {
  constructor(limit, maxPending) { this.limit = limit; this.maxPending = maxPending; this.active = 0; this.queue = []; }
  async take(signal) {
    signal?.throwIfAborted();
    if (this.active >= this.limit) {
      if (this.queue.length >= this.maxPending) throw new SiyueError('客户端等待队列已满', { code: 'CLIENT_BUSY' });
      await new Promise((resolve, reject) => {
        const entry = { resolve: () => { signal?.removeEventListener('abort', abort); resolve(); } };
        const abort = () => { const i = this.queue.indexOf(entry); if (i >= 0) this.queue.splice(i, 1); reject(signal.reason); };
        this.queue.push(entry); signal?.addEventListener('abort', abort, { once: true });
      });
    } else this.active++;
    let released = false;
    return () => {
      if (released) return; released = true;
      const next = this.queue.shift(); if (next) next.resolve(); else this.active--;
    };
  }
}
const delay = (ms, signal) => new Promise((resolve, reject) => {
  signal?.throwIfAborted();
  const abort = () => { clearTimeout(timer); reject(signal.reason); };
  const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
  signal?.addEventListener('abort', abort, { once: true });
});
function retryDelay(value, now = Date.now()) {
  if (!value) return 0;
  if (/^\d+(\.\d+)?$/.test(value)) return Number(value) * 1000;
  const parsed = Date.parse(value); return Number.isFinite(parsed) ? Math.max(0, parsed - now) : 0;
}
export class SiyueAgent {
  constructor({ baseUrl, token, concurrency = 4, maxPending = 128, maxRetries = 3, timeoutMs = 30000, fetchImpl = globalThis.fetch, sleep = delay, random = Math.random }) {
    const base = new URL(baseUrl);
    if (base.username || base.password || base.search || base.hash || (base.protocol !== 'https:' && !(base.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)))) throw new Error('只允许 HTTPS 或本地回环地址，地址不得含凭证或查询参数');
    if (!token?.startsWith('sya_')) throw new Error('请提供独立 Agent 凭证');
    if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 8 || !Number.isInteger(maxPending) || maxPending < 0 || maxPending > 1024) throw new Error('concurrency 必须为 1–8；maxPending 为 0–1024');
    if (!Number.isInteger(maxRetries) || maxRetries < 0 || maxRetries > 5 || !Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 300000) throw new Error('重试次数 0–5；超时 1–300000 毫秒');
    this.baseUrl = base.href.replace(/\/$/, '');
    // Avoid accidental token disclosure when a client instance is serialized by an agent framework.
    Object.defineProperty(this, 'token', { value: token, enumerable: false });
    this.gate = new Gate(concurrency, maxPending); this.maxRetries = maxRetries; this.timeoutMs = timeoutMs;
    this.fetch = fetchImpl; this.sleep = sleep; this.random = random; this.operations = new Map();
  }
  async connect({ signal } = {}) {
    const capabilities = await this.request({ method: 'GET', path: '/api/automation/v1/capabilities', retry: 'read' }, { signal });
    if (!capabilities.version?.startsWith('1.')) throw new SiyueError('不兼容的 Agent 接口版本', { code: 'VERSION_MISMATCH' });
    this.capabilities = capabilities; this.operations = new Map(capabilities.operations.map(x => [x.id, x])); return capabilities;
  }
  async call(operationId, input = {}) {
    const operation = this.operations.get(operationId);
    if (!operation) throw new SiyueError('操作不可用，请先 connect 并检查凭证权限', { code: 'OPERATION_UNAVAILABLE' });
    return this.request(operation, input);
  }
  async request(operation, { path = {}, query = {}, body, formData, signal, timeoutMs = this.timeoutMs } = {}) {
    signal?.throwIfAborted();
    if (!Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 300000) throw new Error('超时必须为 1–300000 毫秒');
    if (body !== undefined && formData !== undefined) throw new Error('body 和 formData 不能同时提供');
    const route = operation.path.replace(/\{([^}]+)\}/g, (_, name) => {
      if (path[name] === undefined || path[name] === null || String(path[name]).length === 0) throw new Error(`缺少路径参数 ${name}`);
      const value = String(path[name]); if (value === '.' || value === '..' || /[\/\\]/.test(value)) throw new Error('无效路径参数');
      return encodeURIComponent(value);
    });
    if (!route.startsWith('/api/') || route.includes('..')) throw new Error('无效操作路径');
    const url = new URL(this.baseUrl + route);
    for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    // Freeze JSON before the first attempt; retrying never changes a key or payload.
    const serialized = body === undefined ? undefined : JSON.stringify(body);
    const retrySafe = operation.retry === 'read' || (operation.retry === 'idempotent' && operation.key && typeof body?.[operation.key] === 'string' && body[operation.key].length > 0);
    if (operation.retry === 'idempotent' && !retrySafe) throw new Error(`必须提供并持久保存 ${operation.key}`);
    const retries = retrySafe ? this.maxRetries : 0;
    for (let attempt = 0; ; attempt++) {
      const release = await this.gate.take(signal);
      let error;
      try {
        signal?.throwIfAborted();
        const timeout = new AbortController(); const timer = setTimeout(() => timeout.abort(new Error('请求超时')), timeoutMs);
        try {
          const response = await this.fetch(url, {
            method: operation.method, redirect: 'error',
            headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json', ...(serialized === undefined ? {} : { 'Content-Type': 'application/json' }) },
            body: formData ?? serialized,
            signal: signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal
          });
          if (response.status === 204) return null;
          const text = await response.text(); let value;
          try { value = text ? JSON.parse(text) : null; } catch { throw new SiyueError('接口返回了非 JSON 内容', { status: response.status, code: 'INVALID_RESPONSE', traceId: response.headers.get('X-Request-Id') }); }
          if (response.ok) return value;
          throw new SiyueError(value?.message ?? `请求失败 (${response.status})`, { status: response.status, code: value?.code ?? 'HTTP_ERROR', traceId: value?.traceId ?? response.headers.get('X-Request-Id'), retryAfter: retryDelay(response.headers.get('Retry-After')) });
        } finally { clearTimeout(timer); }
      } catch (e) {
        if (signal?.aborted) throw signal.reason;
        error = e instanceof SiyueError ? e : new SiyueError('连接中断或请求超时；写入结果可能未知');
      } finally { release(); }
      // A 409 is a reconciliation boundary, even for a keyed operation. Never overwrite a newer revision.
      if (attempt >= retries || ![0, 429, 500, 502, 503, 504].includes(error.status)) throw error;
      const wait = Math.max(error.retryAfter ?? 0, Math.min(8000, 500 * 2 ** attempt) + 250 * this.random());
      // Do not retry earlier than a long Retry-After; let the caller schedule a later attempt instead.
      if (wait > 60000) throw error;
      await this.sleep(wait, signal);
    }
  }
  async waitTask(kind, id, { signal, maxWaitMs = 300000 } = {}) {
    if (!Number.isFinite(maxWaitMs) || maxWaitMs <= 0) throw new Error('maxWaitMs 必须为正数');
    const deadline = new AbortController(); const timer = setTimeout(() => deadline.abort(new SiyueError('任务仍在处理中，请保留任务 ID 稍后查询', { code: 'POLL_TIMEOUT' })), maxWaitMs);
    const combined = signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal;
    try {
      for (;;) {
        const state = await this.call('task_status', { path: { kind, id }, signal: combined });
        if (state.terminal) return state; // failed/review/uncertain are NOT success; caller inspects state.
        await this.sleep(Math.max(2, state.pollAfterSeconds ?? 2) * 1000 + this.random() * 500, combined);
      }
    } finally { clearTimeout(timer); }
  }
}
