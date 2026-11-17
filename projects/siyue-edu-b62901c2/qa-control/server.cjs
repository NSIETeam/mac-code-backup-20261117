'use strict';

// Local-only control plane for running synthetic Siyue service scenarios.
// Secrets and service tokens remain in memory; the private state file stores IDs only.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = __dirname;
const PUBLIC = path.join(ROOT, 'public');
const STATE_DIR = path.join(ROOT, '.state');
const STATE_FILE = path.join(STATE_DIR, 'runs.json');
const HOST = '127.0.0.1';
const PORT = Number(process.env.SIYUE_QA_PORT || 8788);
const CONTROL_TOKEN = crypto.randomBytes(32).toString('base64url');
const PROD_HOSTS = new Set(['siyuejn.com', 'www.siyuejn.com']);
const ROLE_LABELS = {
  principal: '校长', academic: '教务', sales: '销售', finance: '财务',
  teacher: '教师', operations: '运营', auditor: '只读审计员'
};
const READ_ENDPOINTS = new Set([
  'catalog', 'state', 'students', 'schedule', 'leads', 'crm-workbench', 'crm-reports', 'sales-reports', 'teacher-reports'
]);
const jobs = new Map();
const identityJobs = new Map();
let connection = null;

function ensurePrivateState() {
  fs.mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
  fs.chmodSync(STATE_DIR, 0o700);
  if (!fs.existsSync(STATE_FILE)) writeRuns({ runs: [] });
  const st = fs.lstatSync(STATE_FILE);
  if (!st.isFile() || st.isSymbolicLink() || (st.mode & 0o077)) throw new Error('测试运行记录必须是 0600 私有文件');
}
function readRuns() {
  ensurePrivateState();
  return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
}
function writeRuns(value) {
  fs.mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
  fs.chmodSync(STATE_DIR, 0o700);
  const tmp = path.join(STATE_DIR, `.runs-${crypto.randomBytes(8).toString('hex')}`);
  const fd = fs.openSync(tmp, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW, 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n'); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  fs.renameSync(tmp, STATE_FILE);
  fs.chmodSync(STATE_FILE, 0o600);
}
function saveRun(run) {
  const state = readRuns();
  const i = state.runs.findIndex(x => x.id === run.id);
  if (i < 0) state.runs.unshift(run); else state.runs[i] = run;
  state.runs = state.runs.slice(0, 200);
  writeRuns(state);
}
function markInterruptedIdentityJobs() {
  const state = readRuns(), now = new Date().toISOString(); let changed = false;
  for (const run of state.runs) {
    if (run.identityProgress?.status !== 'running') continue;
    run.status = 'interrupted'; run.failure = '控制台在身份批次完成前停止';
    run.identityProgress.status = 'interrupted'; run.identityProgress.phase = '控制台已停止，可归档已创建账号';
    run.identityProgress.updatedAt = now; run.identityProgress.finishedAt = now; changed = true;
  }
  if (changed) writeRuns(state);
}
function json(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}
async function readBody(req, max = 262144) {
  const chunks = []; let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > max) throw Object.assign(new Error('请求内容过大'), { status: 413 });
    chunks.push(chunk);
  }
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    return value;
  } catch { throw Object.assign(new Error('请求 JSON 无效'), { status: 400 }); }
}
function targetURL(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('服务地址无效'); }
  if (url.username || url.password || url.search || url.hash) throw new Error('服务地址不能包含账号、密码、查询参数或片段');
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && local))) throw new Error('必须使用 HTTPS；HTTP 仅允许本机测试');
  const hostAllowed = local || PROD_HOSTS.has(url.hostname) || /^(?:qa|test|stage|staging)\./i.test(url.hostname)
    || (process.env.SIYUE_QA_ALLOWED_HOSTS || '').split(',').map(x => x.trim().toLowerCase()).filter(Boolean).includes(url.hostname.toLowerCase());
  if (!hostAllowed) throw new Error('目标主机不在允许列表；可用本机、siyuejn.com、qa/test/stage 域名或 SIYUE_QA_ALLOWED_HOSTS');
  if (!url.pathname.endsWith('/crm-api/')) throw new Error('地址路径必须以 /crm-api/ 结尾');
  url.pathname = url.pathname.replace(/\/+$/, '') + '/';
  return { url, production: PROD_HOSTS.has(url.hostname) };
}
function requireTestTarget() {
  if (!connection) throw new Error('请先连接测试服务');
  if (connection.production) throw new Error('为防止污染线上数据，账号创建和写入压测只允许本机或隔离测试环境');
  if (!connection.qaLoadMode) throw new Error('目标服务未启用隔离测试模式；请在测试服务设置 SIYUE_QA_LOAD_MODE=1 并重启后再运行写入');
}
function safeMessage(error) {
  if (error?.code === 'PARTIAL_IDENTITY_CREATION') return String(error.message || '批量建号未完整完成').slice(0, 180);
  if (error?.status === 429 || error?.code === 'RATE_LIMITED') return '服务端限流';
  if (error?.code === 'PROMOTION_LINK_UNAVAILABLE') return '推广链接无效或与表单不匹配';
  if (error?.code === 'FORM_FULL') return '表单名额已满';
  if (error?.code === 'DUPLICATE_SUBMISSION') return '该测试身份已提交过表单';
  if (error?.status === 409) return '数据冲突或预览已过期';
  if (error?.status === 403) return '当前身份无权执行';
  if (error?.status === 401) return '会话失效';
  if (error?.status >= 500) return '服务端错误';
  return String(error?.code || error?.message || '请求失败').slice(0, 100);
}
async function request(base, endpoint, { method = 'GET', body, token, headers = {}, signal } = {}) {
  const url = new URL(endpoint.replace(/^\//, ''), base);
  if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) throw new Error('请求目标越界');
  let response;
  try {
    response = await fetch(url, {
      method, redirect: 'error', cache: 'no-store', signal: signal || AbortSignal.timeout(15000),
      headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}), ...headers },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw Object.assign(new Error('无法连接目标服务'), { code: 'CONNECTION_FAILED' });
  }
  let data;
  try { data = await response.json(); } catch { throw Object.assign(new Error('服务未返回 JSON'), { status: response.status, code: 'INVALID_RESPONSE' }); }
  if (!response.ok) {
    const detail = data.error?.code || data.code || `HTTP_${response.status}`;
    throw Object.assign(new Error(detail), { status: response.status, code: detail });
  }
  return { data, response };
}
function sessionRequest(endpoint, options = {}) {
  if (!connection) throw new Error('请先连接测试服务');
  return request(connection.base, endpoint, { ...options, token: options.token || connection.admin.token });
}
function publicSession(user) { return { id: user.id, name: user.name, username: user.username, roleIds: user.roleIds, scope: user.scope }; }
function newRunId() { return new Date().toISOString().replace(/\D/g, '').slice(0, 14) + '-' + crypto.randomBytes(3).toString('hex'); }
function strongPassword(username) {
  const chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const specials = '!@#$%_-';
  let out = 'Qz7!' + Array.from({ length: 24 }, () => chars[crypto.randomInt(chars.length)]).join('') + specials[crypto.randomInt(specials.length)];
  if (out.toLowerCase().includes(username.toLowerCase())) out = 'Qz7!' + crypto.randomBytes(24).toString('base64url') + 'X';
  return out;
}
async function cliLogin(base, username, password) {
  const { data } = await request(base, 'v1/auth/login', { method: 'POST', body: { username, password, client: 'cli' } });
  if (typeof data.token !== 'string' || !data.token) throw new Error('登录未返回 CLI 会话');
  if (data.user?.mustChangePassword) throw new Error('该账号必须先通过员工入口修改初始密码，再连接测试模块');
  return { token: data.token, user: data.user };
}
function summaryConnection() {
  if (!connection) return { connected: false };
  return {
    connected: true, target: connection.base.href, production: connection.production, qaLoadMode: connection.qaLoadMode,
    user: { id: connection.admin.user.id, name: connection.admin.user.name, roles: connection.admin.user.roleIds },
    roles: connection.roles.map(r => ({ id: r.id, label: r.label, defaultScope: r.defaultScope, description: r.description })),
    campuses: connection.campuses.map(c => ({ id: c.id, name: c.name })),
    operations: connection.operations.map(o => ({ id: o.id, label: o.label, group: o.group, permission: o.permission, inputSchema: o.inputSchema })),
    accounts: [...connection.users.values()].map(x => ({ id: x.user.id, name: x.user.name, username: x.user.username, roleId: x.roleId, archived: false })),
    identityJobs: [...identityJobs.values()].filter(j => j.status === 'running').map(identityJobView),
    runs: readRuns().runs.filter(r => r.target === connection.base.origin).map(r => ({ id: r.id, createdAt: r.createdAt, identities: r.identities.length,
      artifacts: r.artifacts?.length || 0, activeLeads: (r.artifacts || []).filter(a => a.kind === 'leadId' && !a.archived).length,
      status: r.status, failure: r.failure || null, archived: r.archived, fixtureDisposed: r.fixtureDisposed === true, identityProgress: r.identityProgress || null,
      resumable: ['partial', 'interrupted'].includes(r.status) && Array.isArray(r.requestedRoles) && !r.archived }))
  };
}
function identityJobView(job) {
  return { jobId: job.id, runId: job.runId, status: job.status, total: job.total, completed: job.completed, created: job.created,
    currentRole: job.currentRole, currentIndex: job.currentIndex, currentRoleTotal: job.currentRoleTotal,
    phase: job.phase, startedAt: job.startedAt, updatedAt: job.updatedAt, finishedAt: job.finishedAt,
    failurePhase: job.failurePhase || null, error: job.error || null };
}
function persistIdentityProgress(job, run) {
  job.updatedAt = new Date().toISOString();
  run.identityProgress = { total: job.total, completed: job.completed, created: job.created, currentRole: job.currentRole,
    currentIndex: job.currentIndex, currentRoleTotal: job.currentRoleTotal, phase: job.phase,
    status: job.status, failurePhase: job.failurePhase || null, error: job.error || null,
    startedAt: job.startedAt, updatedAt: job.updatedAt, finishedAt: job.finishedAt || null };
  saveRun(run);
}
async function provisionIdentityJob(job, roles, campusId, run, resume = false) {
  try {
    for (const [roleId, amount] of roles) {
      const role = connection.roles.find(r => r.id === roleId);
      for (let i = 0; i < amount; i++) {
        job.currentRole = roleId; job.currentIndex = i + 1; job.currentRoleTotal = amount;
        let metadata = resume ? run.identities.find(x => x.roleId === roleId && x.index === i + 1 && !x.archived) : null;
        let username = metadata?.username || (run.pendingIdentity?.roleId === roleId && run.pendingIdentity?.index === i + 1 ? run.pendingIdentity.username : `qa-${job.runId.slice(-8)}-${roleId.slice(0, 4)}-${i + 1}`.toLowerCase());
        let name = metadata?.name || `QA-${roleId}-${job.runId.slice(-6)}-${i + 1}`;
        const scope = { type: role.defaultScope, campusIds: role.defaultScope === 'campus' ? [campusId] : [] };
        let user, initialPassword;
        if (metadata) {
          job.phase = '恢复账号'; persistIdentityProgress(job, run);
          const { data } = await sessionRequest(`v1/admin/users/${metadata.id}/reset-password`, { method: 'POST', body: {} });
          user = data.user; initialPassword = data.initialPassword;
        } else {
          job.phase = '创建账号'; run.pendingIdentity = { roleId, index: i + 1, username }; persistIdentityProgress(job, run);
          const employeeNo = `QA-${job.runId.slice(-8)}-${roleId}-${i + 1}-${username.slice(-6)}`;
          const { data: createdResult } = await sessionRequest('v1/admin/users', { method: 'POST', body: { name, username, employeeNo, roleIds: [roleId], scope } });
          user = createdResult.user; initialPassword = createdResult.initialPassword;
          if (!user?.id || !initialPassword) throw new Error('服务未返回测试账号或一次性初始密码');
          metadata = { id: user.id, name: user.name, username: user.username, roleId, index: i + 1, activated: false, archived: false };
          run.identities.push(metadata); delete run.pendingIdentity; job.created++; persistIdentityProgress(job, run);
        }
        if (!user?.id || !initialPassword) throw new Error('服务未返回测试账号或一次性初始密码');
        job.phase = '首次登录'; persistIdentityProgress(job, run);
        const initial = await request(connection.base, 'v1/auth/login', { method: 'POST', body: { username, password: initialPassword, client: 'cli' } });
        const changed = strongPassword(username);
        job.phase = '设置测试密码'; persistIdentityProgress(job, run);
        await request(connection.base, 'v1/auth/password', { method: 'POST', token: initial.data.token, body: { currentPassword: initialPassword, newPassword: changed } });
        job.phase = '验证身份'; persistIdentityProgress(job, run);
        const activated = await cliLogin(connection.base, username, changed);
        connection.users.set(user.id, { user: activated.user, token: activated.token, roleId });
        metadata.activated = true; job.completed++; job.phase = '身份已激活'; persistIdentityProgress(job, run);
      }
    }
    job.status = 'completed'; job.phase = '完成'; run.status = 'ready';
  } catch (error) {
    job.status = 'partial'; job.failurePhase = job.phase; job.error = safeMessage(error); job.phase = '需要处理';
    run.status = 'partial'; run.failure = job.error;
  } finally {
    job.finishedAt = new Date().toISOString(); persistIdentityProgress(job, run);
  }
}
function ensureLocalRequest(req) {
  const host = req.headers.host || '';
  if (!/^(127\.0\.0\.1|localhost):\d+$/.test(host)) throw Object.assign(new Error('仅允许本机访问'), { status: 403 });
  if (req.method !== 'GET') {
    const expected = `http://${host}`;
    if (req.headers.origin !== expected || req.headers['x-qa-control'] !== CONTROL_TOKEN) throw Object.assign(new Error('本地控制校验失败'), { status: 403 });
  }
}
function substitute(value, context) {
  if (typeof value === 'string') {
    return value.replace(/\{\{([\w.]+)\}\}/g, (whole, key) => {
      const found = key.split('.').reduce((v, k) => v == null ? undefined : v[k], context);
      return found === undefined || found === null ? whole : String(found);
    });
  }
  if (Array.isArray(value)) return value.map(v => substitute(v, context));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, context)]));
  return value;
}
function freshPhone(runId, seq) {
  const suffix = String(seq).padStart(8, '0').slice(-8);
  const digit = crypto.createHash('sha256').update(runId + ':' + suffix).digest()[0] % 10;
  return '18' + (3 + digit % 7) + suffix;
}
function percentile(values, p) {
  if (!values.length) return 0;
  const a = [...values].sort((x, y) => x - y);
  return Math.round(a[Math.min(a.length - 1, Math.ceil(p * a.length) - 1)]);
}
function jobSnapshot(job) {
  return { id: job.id, status: job.status, progress: job.done, total: job.total, concurrency: job.concurrency,
    startedAt: job.startedAt, finishedAt: job.finishedAt || null, counts: { ...job.counts }, retries: job.retries, steps: job.steps,
    latencyMs: { p50: percentile(job.latencies, .50), p95: percentile(job.latencies, .95), p99: percentile(job.latencies, .99), max: Math.max(0, ...job.latencies) },
    lastErrors: job.lastErrors.slice(-10) };
}
function classify(status, code) {
  if (status >= 200 && status < 300) return 'success';
  if (status === 429 || code === 'RATE_LIMITED') return 'rateLimited';
  if (status === 409) return 'conflict';
  if (status === 401 || status === 403) return 'authorization';
  if (status >= 500) return 'serverError';
  return 'rejected';
}
function increment(job, step, status, code, elapsed) {
  const type = classify(status, code);
  job.counts[type]++;
  job.steps[step] ||= { success: 0, rateLimited: 0, conflict: 0, authorization: 0, serverError: 0, rejected: 0 };
  job.steps[step][type]++;
  job.latencies.push(elapsed);
}
function saveArtifacts(job, step, value) {
  const keys = new Set(['submissionId', 'leadId', 'classId', 'sessionId', 'enrollmentId', 'studentId', 'adjustmentId', 'requestId', 'batchId']);
  const found = [];
  const walk = (node, depth = 0) => {
    if (!node || typeof node !== 'object' || depth > 5) return;
    for (const [key, child] of Object.entries(node)) {
      if (keys.has(key) && typeof child === 'string' && child.length <= 160) found.push({ kind: key, id: child });
      else if (child && typeof child === 'object') walk(child, depth + 1);
    }
  };
  walk(value);
  if (!found.length) return;
  const run = readRuns().runs.find(x => x.id === job.id);
  if (!run) return;
  run.artifacts ||= [];
  for (const item of found) {
    if (run.artifacts.length >= 5000) break;
    if (!run.artifacts.some(old => old.kind === item.kind && old.id === item.id)) run.artifacts.push({ ...item, step, createdAt: new Date().toISOString(), archived: false });
  }
  saveRun(run);
}
async function readForm(formId) {
  if (!connection) throw new Error('请先连接测试服务');
  if (!/^[A-Za-z0-9_-]{1,120}$/.test(formId)) throw new Error('表单编号无效');
  const { data } = await request(connection.base, `v1/public/forms/${encodeURIComponent(formId)}`);
  const form = data.form;
  if (!form || !Array.isArray(form.fields)) throw new Error('未读取到可公开填写的表单');
  return { id: form.id, name: form.name || form.title || form.id, fields: form.fields.map(f => ({ id: f.id, title: f.title, type: f.type, required: !!f.required, options: Array.isArray(f.options) ? f.options.map(o => typeof o === 'string' ? { value: o, label: o } : ({ value: o.value, label: o.label })) : [] })) };
}
async function executeOperation(job, step, input, actor, context) {
  const token = actor.token;
  const start = performance.now();
  let error;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      const { data: stateData } = await sessionRequest('v1/state?crm=compact', { token, signal: job.controller.signal });
      const { data: preview } = await sessionRequest(`v1/operations/${step.operationId}/preview`, {
        method: 'POST', token, signal: job.controller.signal, body: { input, expectedRevision: stateData.revision }
      });
      if (job.controller.signal.aborted) throw new Error('已停止');
      const { data } = await sessionRequest(`v1/operations/${step.operationId}/execute`, {
        method: 'POST', token, signal: job.controller.signal, headers: { 'Idempotency-Key': `qa-${job.id}-${context.worker}-${context.seq}-${crypto.randomBytes(5).toString('hex')}` },
        body: { input, expectedRevision: preview.revision, previewToken: preview.previewToken }
      });
      const elapsed = Math.round(performance.now() - start); increment(job, step.label, 200, '', elapsed);
      return data.result || data;
    } catch (caught) {
      error = caught;
      const retryable = ['REVISION_CONFLICT', 'PREVIEW_EXPIRED'].includes(caught.code);
      if (!retryable || attempt === 5 || job.controller.signal.aborted) break;
      job.retries++;
      await new Promise(resolve => setTimeout(resolve, Math.min(250, 20 * (2 ** attempt)) + crypto.randomInt(0, 25)));
    }
  }
  const elapsed = Math.round(performance.now() - start); const status = error?.status || (job.controller.signal.aborted ? 499 : 0);
  increment(job, step.label, status, error?.code, elapsed);
  if (job.lastErrors.length < 20) job.lastErrors.push({ step: step.label, code: safeMessage(error), status: status || null });
  throw error;
}
async function executePublicForm(job, step, answers, context) {
  const start = performance.now();
  try {
    if (connection.production) throw new Error('线上只允许只读压测');
    const formId = String(step.formId || '');
    const attribution = step.campaignId && step.promotionId ? `?campaign=${encodeURIComponent(step.campaignId)}&promotion=${encodeURIComponent(step.promotionId)}` : '';
    const formPath = `v1/public/forms/${encodeURIComponent(formId)}`;
    const publicPath = `${formPath}${attribution}`;
    const { data: init, response: getResponse } = await request(connection.base, publicPath, { signal: job.controller.signal });
    const cookies = getResponse.headers.getSetCookie?.() || [];
    const cookie = cookies.map(x => x.split(';', 1)[0]).join('; ');
    const key = `qa-${job.id}-${context.worker}-${context.seq}-${crypto.randomBytes(5).toString('hex')}`;
    const resolved = substitute(answers, { ...context, submissionId: key, submissionKey: init.submissionKey, phone: freshPhone(job.id, context.seq), name: `QA${job.id.slice(-6)}-${context.seq}` });
    const { data } = await request(connection.base, `${formPath}/submissions${attribution}`, {
      method: 'POST', signal: job.controller.signal,
      headers: { ...(cookie ? { Cookie: cookie } : {}), Origin: connection.base.origin, 'Idempotency-Key': key },
      body: { formId, answersById: resolved, submissionId: key }
    });
    increment(job, step.label, 200, '', Math.round(performance.now() - start));
    return data.result || data;
  } catch (error) {
    increment(job, step.label, error.status || (job.controller.signal.aborted ? 499 : 0), error.code, Math.round(performance.now() - start));
    if (job.lastErrors.length < 20) job.lastErrors.push({ step: step.label, code: safeMessage(error), status: error.status || null });
    throw error;
  }
}
async function executeRead(job, step, actor) {
  const start = performance.now();
  try {
    const endpoint = {
      catalog: 'v1/catalog', state: 'v1/state?crm=compact', students: 'v1/students', schedule: 'v1/schedule',
      leads: 'v1/leads', 'crm-workbench': 'v1/crm/workbench', 'crm-reports': 'v1/crm/reports',
      'sales-reports': 'v1/reports/sales', 'teacher-reports': 'v1/reports/teachers'
    }[step.endpoint];
    if (!endpoint || !READ_ENDPOINTS.has(step.endpoint)) throw new Error('只允许目录中的只读接口');
    const { data } = await sessionRequest(endpoint, { token: actor.token, signal: job.controller.signal });
    // Deliberately retain only aggregate shape; student/lead payloads never reach the UI or disk.
    const count = Array.isArray(data.items) ? data.items.length : Array.isArray(data.operations) ? data.operations.length : null;
    increment(job, step.label, 200, '', Math.round(performance.now() - start));
    return { count };
  } catch (error) {
    increment(job, step.label, error.status || (job.controller.signal.aborted ? 499 : 0), error.code, Math.round(performance.now() - start));
    if (job.lastErrors.length < 20) job.lastErrors.push({ step: step.label, code: safeMessage(error), status: error.status || null });
    throw error;
  }
}
async function runJob(job, config) {
  const userPool = new Map();
  userPool.set('superadmin', [connection.admin]);
  for (const [role, list] of new Map([...connection.users.values()].reduce((all, u) => {
    const row = all.find(x => x[0] === u.roleId); row ? row[1].push(u) : all.push([u.roleId, [u]]); return all;
  }, []))) userPool.set(role, list);
  const work = Array.from({ length: job.total }, (_, i) => i);
  let next = 0;
  const worker = async workerIndex => {
    while (!job.controller.signal.aborted) {
      const seq = next++;
      if (seq >= work.length) return;
      const salesPool = userPool.get('sales') || [];
      const assignedSales = salesPool.length ? salesPool[seq % salesPool.length] : null;
      const context = { runId: job.id, worker: workerIndex + 1, seq: seq + 1, nonce: crypto.randomBytes(5).toString('hex'), now: new Date().toISOString(),
        followupPercent: Number(config.followupPercent ?? 100), assignedSalesId: assignedSales?.user.id || '',
        users: Object.fromEntries([...userPool].map(([role, list]) => [role, list.map(x => x.user)])) };
      const resultByStep = [];
      for (const step of config.steps) {
        if (job.controller.signal.aborted) break;
        if (step.whenFollowup && (((context.seq - 1) * 37) % 100) >= context.followupPercent) { resultByStep.push(null); continue; }
        const role = step.roleId || 'superadmin';
        const pool = userPool.get(role);
        if (!pool?.length) {
          job.counts.rejected++;
          if (job.lastErrors.length < 20) job.lastErrors.push({ step: step.label, code: `没有可用的${ROLE_LABELS[role] || role}测试账号`, status: 0 });
          break;
        }
        const actor = pool[seq % pool.length];
        const stepContext = { ...context, actor: actor.user, steps: resultByStep };
        try {
          let result;
          if (step.kind === 'publicForm') {
            const answers = substitute(step.answers || {}, stepContext);
            result = await executePublicForm(job, step, answers, context);
          } else if (step.kind === 'read') {
            result = await executeRead(job, step, actor);
          } else {
            const raw = step.inputs || step.input || {};
            let input = substitute(raw, stepContext);
            if (Array.isArray(input)) input = input[seq % input.length];
            if (!step.operationId || !/^[a-zA-Z][a-zA-Z0-9.]+$/.test(step.operationId)) throw new Error('操作编号无效');
            result = await executeOperation(job, step, input, actor, context);
          }
          resultByStep.push(result); saveArtifacts(job, step.label, result);
        } catch {
          resultByStep.push(null);
          break;
        }
      }
      job.done++;
    }
  };
  await Promise.all(Array.from({ length: job.concurrency }, (_, i) => worker(i)));
  job.status = job.controller.signal.aborted ? 'cancelled' : 'completed';
  job.finishedAt = new Date().toISOString();
  const runs = readRuns();
  const record = runs.runs.find(x => x.id === job.id);
    if (record) { record.status = job.status; record.finishedAt = job.finishedAt; record.summary = { ...job.counts }; saveRun(record); }
}
function makeJob(config) {
  if (!Array.isArray(config.steps) || !config.steps.length || config.steps.length > 12) throw new Error('流程需包含 1–12 步');
  const iterations = Number(config.iterations), concurrency = Number(config.concurrency);
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > 2000) throw new Error('每轮次数须为 1–2000');
  if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 50) throw new Error('并发数须为 1–50');
  const writes = config.steps.some(step => step.kind !== 'read');
  if (connection.production && writes) throw new Error('线上目标为只读；写入和账号生成已禁用');
  if (writes && !connection.qaLoadMode) throw new Error('目标服务未启用隔离测试模式；请先在隔离测试服务启用 SIYUE_QA_LOAD_MODE');
  if (connection.production && (concurrency > 5 || iterations > 500)) throw new Error('线上只读压测最多 5 并发、500 次；高并发请连接隔离测试环境');
  for (const step of config.steps) {
    if (!step || !['operation', 'publicForm', 'read'].includes(step.kind)) throw new Error('流程步骤类型无效');
    step.label = String(step.label || (step.kind === 'publicForm' ? '扫码提交' : step.operationId || '业务操作')).slice(0, 60);
    if (step.kind === 'publicForm' && !/^[A-Za-z0-9_-]{1,120}$/.test(String(step.formId || ''))) throw new Error('表单步骤需填写有效的表单编号');
    if (step.kind === 'operation' && (!/^[a-zA-Z][a-zA-Z0-9.]+$/.test(String(step.operationId || '')) || !connection.operations.some(o => o.id === step.operationId))) throw new Error('操作不在当前服务目录中');
    if (step.kind === 'read' && !READ_ENDPOINTS.has(step.endpoint)) throw new Error('只读接口不在允许列表');
    if (step.roleId && step.roleId !== 'superadmin' && !ROLE_LABELS[step.roleId]) throw new Error('测试角色无效');
  }
  const id = newRunId();
  const job = { id, status: 'running', total: iterations, concurrency, done: 0, startedAt: new Date().toISOString(), finishedAt: null,
    counts: { success: 0, rateLimited: 0, conflict: 0, authorization: 0, serverError: 0, rejected: 0 }, retries: 0, steps: {}, latencies: [], lastErrors: [], controller: new AbortController() };
  jobs.set(id, job);
  saveRun({ id, target: connection.base.origin, createdAt: job.startedAt, status: 'running', identities: [], artifacts: [], archived: false });
  queueMicrotask(() => runJob(job, config).catch(error => {
    job.status = 'failed'; job.finishedAt = new Date().toISOString();
    job.lastErrors.push({ step: 'runner', code: safeMessage(error), status: error.status || null });
    const record = readRuns().runs.find(x => x.id === job.id);
    if (record) { record.status = job.status; record.finishedAt = job.finishedAt; record.summary = { ...job.counts }; saveRun(record); }
  }));
  return job;
}
function staticFile(res, name, type) {
  const file = path.join(PUBLIC, name);
  const content = fs.readFileSync(file);
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Length': content.length });
  res.end(content);
}
async function api(req, res, url) {
  try {
    ensureLocalRequest(req);
    if (url.pathname === '/api/bootstrap.js' && req.method === 'GET') {
      const content = `window.SIYUE_QA_CONTROL=${JSON.stringify(CONTROL_TOKEN)};`;
      res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); res.end(content); return;
    }
    if (req.method !== 'GET' && req.headers['x-qa-control'] !== CONTROL_TOKEN) throw Object.assign(new Error('本地控制校验失败'), { status: 403 });
    if (url.pathname === '/api/status' && req.method === 'GET') return json(res, 200, summaryConnection());
    const identityJobMatch = url.pathname.match(/^\/api\/identity-jobs\/([a-f0-9-]+)$/i);
    if (identityJobMatch && req.method === 'GET') {
      const job = identityJobs.get(identityJobMatch[1]);
      if (!job) throw Object.assign(new Error('身份批次进度暂不可用；可在测试批次中查看已创建账号并归档'), { status: 404 });
      return json(res, 200, identityJobView(job));
    }
    if (url.pathname === '/api/connect' && req.method === 'POST') {
      if ([...identityJobs.values()].some(j => j.status === 'running')) throw Object.assign(new Error('身份批次正在创建，请完成或等待当前批次后再切换服务'), { status: 409 });
      for (const job of jobs.values()) if (job.status === 'running') job.controller.abort();
      connection = null;
      const input = await readBody(req, 8192), target = targetURL(input.target);
      if (!input.username || typeof input.username !== 'string' || typeof input.password !== 'string' || input.password.length > 256) throw new Error('请填写员工账号和密码');
      const admin = await cliLogin(target.url, input.username.trim(), input.password);
      if (!admin.user?.roleIds?.includes('superadmin')) throw new Error('仅超级管理员可连接控制面板');
      const [{ data: health }, { data: roleResult }, { data: catalog }, { data: stateData }] = await Promise.all([
        request(target.url, 'healthz'),
        request(target.url, 'v1/roles', { token: admin.token }), request(target.url, 'v1/catalog', { token: admin.token }), request(target.url, 'v1/state?crm=compact', { token: admin.token })
      ]);
      const state = stateData.state || stateData;
      const runs = readRuns().runs.filter(r => r.target === target.url.origin);
      connection = { base: target.url, production: target.production, qaLoadMode: health.qaLoadMode === true, admin, roles: roleResult.roles || [],
        operations: catalog.operations || [], campuses: (state.campuses || []).map(c => ({ id: c.id, name: c.name })),
        users: new Map(), runs };
      return json(res, 200, summaryConnection());
    }
    if (url.pathname === '/api/disconnect' && req.method === 'POST') {
      if ([...identityJobs.values()].some(j => j.status === 'running')) throw Object.assign(new Error('身份批次正在创建，完成后再断开服务连接'), { status: 409 });
      for (const job of jobs.values()) if (job.status === 'running') job.controller.abort();
      connection = null; return json(res, 200, { disconnected: true });
    }
    if (!connection) throw new Error('请先连接测试服务');
    if (url.pathname === '/api/forms' && req.method === 'GET') {
      const { data } = await sessionRequest('v1/state?crm=compact'); const state = data.state || data;
      const forms = (state.forms || []).filter(f => f.published === true || f.status === '已发布').map(f => ({ id: f.id, name: f.name || f.title || f.id, status: f.status || '已发布' }));
      return json(res, 200, { forms });
    }
    if (url.pathname === '/api/campaigns' && req.method === 'GET') {
      const { data } = await sessionRequest('v1/state?crm=compact'); const state = data.state || data;
      const campaigns = (state.campaigns || []).filter(c => c.status === 'published').map(c => ({ id: c.id, name: c.name || c.id,
        links: (c.promotionLinks || []).map(l => ({ id: l.id, formId: l.formId, name: l.name || l.id })) }));
      return json(res, 200, { campaigns });
    }
    if (url.pathname === '/api/forms/inspect' && req.method === 'POST') {
      const input = await readBody(req, 8192); return json(res, 200, await readForm(String(input.formId || '')));
    }
    if (url.pathname === '/api/identities' && req.method === 'POST') {
      requireTestTarget();
      const input = await readBody(req, 32768), counts = input.counts || {}, campusId = String(input.campusId || '');
      if (campusId && !connection.campuses.some(c => c.id === campusId)) throw new Error('校区不在当前服务范围');
      const roles = Object.entries(counts).filter(([, count]) => Number(count) > 0);
      if (!roles.length || roles.some(([id, count]) => !ROLE_LABELS[id] || !Number.isInteger(Number(count)) || Number(count) > 50)) throw new Error('身份数量或角色无效；每种角色最多 50 个');
      if (roles.reduce((sum, [, count]) => sum + Number(count), 0) > 100) throw new Error('单次最多创建 100 个测试身份');
      if ([...identityJobs.values()].some(j => j.status === 'running')) throw Object.assign(new Error('已有身份批次正在创建，请等待完成后再发起下一批'), { status: 409 });
      for (const [roleId] of roles) {
        const role = connection.roles.find(r => r.id === roleId);
        if (!role) throw new Error(`服务未提供${ROLE_LABELS[roleId]}角色`);
        if (role.defaultScope === 'campus' && !campusId) throw new Error(`${ROLE_LABELS[roleId]}身份需要选择校区`);
      }
      const runId = newRunId(), now = new Date().toISOString(), persisted = { id: runId, target: connection.base.origin, createdAt: now, status: 'provisioning', identities: [], artifacts: [], archived: false,
        requestedRoles: roles.map(([roleId, amount]) => [roleId, Number(amount)]), campusId };
      saveRun(persisted);
      const id = crypto.randomUUID(), job = { id, runId, status: 'running', total: roles.reduce((sum, [, n]) => sum + Number(n), 0), completed: 0, created: 0,
        currentRole: null, currentIndex: 0, currentRoleTotal: 0, phase: '准备批次', startedAt: now, updatedAt: now, finishedAt: null, error: null, failurePhase: null };
      identityJobs.set(id, job); persistIdentityProgress(job, persisted);
      provisionIdentityJob(job, roles.map(([roleId, amount]) => [roleId, Number(amount)]), campusId, persisted).catch(error => {
        job.status = 'partial'; job.failurePhase = job.phase; job.phase = '需要处理'; job.error = safeMessage(error); job.finishedAt = new Date().toISOString();
        persisted.status = 'partial'; persisted.failure = job.error;
        try { persistIdentityProgress(job, persisted); } catch {}
      });
      return json(res, 202, { ...identityJobView(job), summary: summaryConnection() });
    }
    const resumeIdentityMatch = url.pathname.match(/^\/api\/identity-runs\/([\d-]+-[a-f0-9]+)\/resume$/i);
    if (resumeIdentityMatch && req.method === 'POST') {
      requireTestTarget();
      const run = readRuns().runs.find(r => r.id === resumeIdentityMatch[1] && r.target === connection.base.origin);
      if (!run || !['partial', 'interrupted'].includes(run.status) || !Array.isArray(run.requestedRoles) || run.archived) throw Object.assign(new Error('该批次当前不可续跑'), { status: 409 });
      if ([...identityJobs.values()].some(j => j.status === 'running')) throw Object.assign(new Error('已有身份批次正在创建，请等待完成后再续跑'), { status: 409 });
      for (const [roleId] of run.requestedRoles) if (!connection.roles.some(r => r.id === roleId)) throw new Error(`服务未提供${ROLE_LABELS[roleId] || roleId}角色`);
      if (run.campusId && !connection.campuses.some(c => c.id === run.campusId)) throw new Error('原测试校区已不可用，无法续跑该批次');
      if (run.pendingIdentity) {
        const { data } = await sessionRequest('v1/admin/users');
        const uncertain = (data.users || []).find(u => u.username === run.pendingIdentity.username && !u.archivedAt);
        if (uncertain) {
          await sessionRequest(`v1/admin/users/${uncertain.id}/archive`, { method: 'POST', body: { reason: `恢复批次前清理不确定创建 ${run.id}` } });
          run.identities.push({ id: uncertain.id, name: uncertain.name, username: uncertain.username, roleId: run.pendingIdentity.roleId, index: run.pendingIdentity.index, activated: false, archived: true });
        }
        run.pendingIdentity.username = `${run.pendingIdentity.username}-r${Date.now().toString(36)}`;
      }
      const total = run.requestedRoles.reduce((sum, [, amount]) => sum + amount, 0), now = new Date().toISOString();
      const job = { id: crypto.randomUUID(), runId: run.id, status: 'running', total, completed: 0,
        created: run.identities.filter(x => !x.archived).length, currentRole: null, currentIndex: 0, currentRoleTotal: 0,
        phase: '恢复批次', startedAt: now, updatedAt: now, finishedAt: null, error: null, failurePhase: null };
      run.status = 'provisioning'; run.failure = null; identityJobs.set(job.id, job); persistIdentityProgress(job, run);
      provisionIdentityJob(job, run.requestedRoles, run.campusId, run, true).catch(error => {
        job.status = 'partial'; job.failurePhase = job.phase; job.phase = '需要处理'; job.error = safeMessage(error); job.finishedAt = new Date().toISOString();
        run.status = 'partial'; run.failure = job.error; try { persistIdentityProgress(job, run); } catch {}
      });
      return json(res, 202, identityJobView(job));
    }
    if (url.pathname === '/api/cleanup' && req.method === 'POST') {
      requireTestTarget();
      const input = await readBody(req, 8192), run = readRuns().runs.find(r => r.id === input.runId && r.target === connection.base.origin);
      if (!run) throw new Error('找不到当前服务下的测试批次');
      const results = [];
      for (const identity of run.identities) {
        if (identity.archived) continue;
        try {
          await sessionRequest(`v1/admin/users/${identity.id}/archive`, { method: 'POST', body: { reason: `测试批次清理 ${run.id}` } });
          identity.archived = true; results.push({ id: identity.id, status: 'archived' }); connection.users.delete(identity.id);
        } catch (error) { results.push({ id: identity.id, status: 'failed', reason: safeMessage(error) }); }
      }
      run.archived = run.identities.every(x => x.archived); saveRun(run);
      return json(res, 200, { runId: run.id, archived: results.filter(x => x.status === 'archived').length, results, summary: summaryConnection() });
    }
    if (url.pathname === '/api/cleanup-leads' && req.method === 'POST') {
      requireTestTarget();
      const input = await readBody(req, 8192), run = readRuns().runs.find(r => r.id === input.runId && r.target === connection.base.origin);
      if (!run) throw new Error('找不到当前服务下的测试批次');
      const leads = (run.artifacts || []).filter(x => x.kind === 'leadId' && !x.archived), results = [];
      for (const lead of leads) {
        try {
          const { data: stateData } = await sessionRequest('v1/state?crm=compact');
          const current = await sessionRequest('v1/operations/lead.archive/preview', { method: 'POST', body: { input: { leadId: lead.id, reason: `QA测试批次清理 ${run.id}` }, expectedRevision: stateData.revision } });
          await sessionRequest('v1/operations/lead.archive/execute', { method: 'POST', headers: { 'Idempotency-Key': `qa-clean-${run.id}-${crypto.randomBytes(5).toString('hex')}` }, body: { input: { leadId: lead.id, reason: `QA测试批次清理 ${run.id}` }, expectedRevision: current.data.revision, previewToken: current.data.previewToken } });
          lead.archived = true; results.push({ id: lead.id, status: 'archived' }); saveRun(run);
        } catch (error) { results.push({ id: lead.id, status: 'failed', reason: safeMessage(error) }); }
      }
      return json(res, 200, { runId: run.id, archived: results.filter(x => x.status === 'archived').length, results, summary: summaryConnection() });
    }
    if (url.pathname === '/api/runs' && req.method === 'POST') {
      const config = await readBody(req);
      const job = makeJob(config);
      return json(res, 202, { job: jobSnapshot(job) });
    }
    const jobMatch = url.pathname.match(/^\/api\/runs\/([\dA-Za-z-]+)$/);
    if (jobMatch && req.method === 'GET') {
      const job = jobs.get(jobMatch[1]); if (!job) throw Object.assign(new Error('运行记录不在本次控制面板会话中'), { status: 404 });
      return json(res, 200, { job: jobSnapshot(job) });
    }
    const cancelMatch = url.pathname.match(/^\/api\/runs\/([\dA-Za-z-]+)\/cancel$/);
    if (cancelMatch && req.method === 'POST') {
      const job = jobs.get(cancelMatch[1]); if (!job) throw Object.assign(new Error('运行记录不存在'), { status: 404 });
      job.controller.abort(); return json(res, 200, { job: jobSnapshot(job) });
    }
    throw Object.assign(new Error('接口不存在'), { status: 404 });
  } catch (error) { json(res, error.status || 400, { error: safeMessage(error) }); }
}

ensurePrivateState();
markInterruptedIdentityJobs();
const server = http.createServer(async (req, res) => {
  let url; try { url = new URL(req.url, `http://${HOST}:${PORT}`); } catch { return json(res, 400, { error: '请求地址无效' }); }
  if (url.pathname.startsWith('/api/')) return api(req, res, url);
  if (req.method !== 'GET') return json(res, 405, { error: '只支持 GET' });
  if (url.pathname === '/') return staticFile(res, 'index.html', 'text/html; charset=utf-8');
  if (url.pathname === '/app.js') return staticFile(res, 'app.js', 'text/javascript; charset=utf-8');
  if (url.pathname === '/styles.css') return staticFile(res, 'styles.css', 'text/css; charset=utf-8');
  return json(res, 404, { error: '未找到' });
});
if (!Number.isInteger(PORT) || PORT < 1024 || PORT > 65535) throw new Error('SIYUE_QA_PORT 须在 1024–65535');
server.listen(PORT, HOST, () => process.stdout.write(`思跃测试控制台：http://${HOST}:${PORT}\n`));
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  for (const job of jobs.values()) if (job.status === 'running') job.controller.abort();
  connection = null; server.close(() => process.exit(0));
});
