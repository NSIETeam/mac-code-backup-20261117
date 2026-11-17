import {createPolicyReviewStore, PolicyReviewError} from './policy-review.mjs';
import {createAgentReadApi, AgentReadError} from './agent-read-api.mjs';
import {mergeSearchResults} from './domain-search.mjs';
import {createServer} from 'node:http';
import {isIP} from 'node:net';
import {DatabaseSync} from 'node:sqlite';
import {randomBytes, randomUUID, scrypt as scryptCallback, timingSafeEqual, createHash} from 'node:crypto';
import {promisify} from 'node:util';
import {mkdirSync, chmodSync, existsSync, realpathSync, statSync, createReadStream} from 'node:fs';
import {dirname, resolve, relative, extname, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {profileSchema, registerSchema, needSchema, updateNeedSchema, ratingSchema, normalizeRoleId, normalizeRoleIds} from './runtime/validation.mjs';
import {createRecordingStore, AudioError} from './recordings.mjs';
import {createKnowledgeStore, KnowledgeError} from './knowledge.mjs';
import {getCatalog, searchCatalog} from './catalog.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 5317);
const host = process.env.HOST || '127.0.0.1';
const semanticSearchEnabled = process.env.SIYUE_SEMANTIC_SEARCH !== '0';
const dbPath = resolve(process.env.SIYUE_DB_PATH || resolve(here, 'data/siyue.sqlite'));
const staticDir = resolve(process.env.SIYUE_STATIC_DIR || resolve(here, '../standalone-dist'));
const secure = process.env.NODE_ENV === 'production';
const cookieName = 'siyue_work_session';
const cookiePath = process.env.SIYUE_COOKIE_PATH || '/';
if (!/^\/(?:[A-Za-z0-9._~%-]+\/)*[A-Za-z0-9._~%-]*$/.test(cookiePath) || cookiePath.length > 256 || cookiePath.split('/').some(part => part === '.' || part === '..')) {
 throw new Error('SIYUE_COOKIE_PATH 必须是有效的绝对路径，例如 /work/。');
}
const trustLoopbackProxy = process.env.TRUST_PROXY === 'loopback';
if (process.env.TRUST_PROXY && !trustLoopbackProxy) throw new Error('TRUST_PROXY 仅支持 loopback；不使用代理时请留空。');
const reviewerIds = new Set((process.env.SIYUE_REVIEWER_IDS || '').split(/[\s,]+/).filter(Boolean).map(id => id.toLowerCase()));
if ([...reviewerIds].some(id => !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id))) throw new Error('SIYUE_REVIEWER_IDS 只能填写员工稳定UUID，多个ID用逗号分隔。');
const canReview = employeeId => reviewerIds.has(employeeId.toLowerCase());
const knowledgeAdminIds = new Set((process.env.SIYUE_KNOWLEDGE_ADMIN_IDS || '').split(/[\s,]+/).filter(Boolean).map(id => id.toLowerCase()));
if ([...knowledgeAdminIds].some(id => !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id))) throw new Error('SIYUE_KNOWLEDGE_ADMIN_IDS 只能填写员工稳定UUID。');
const sessionLifetime = 7 * 24 * 60 * 60 * 1000;
const maxBodyBytes = 32 * 1024;
const scrypt = promisify(scryptCallback);
const hash = value => createHash('sha256').update(value).digest('hex');
const now = () => new Date().toISOString();
let publicOrigin = null;
if (process.env.PUBLIC_ORIGIN) {
 const configured = new URL(process.env.PUBLIC_ORIGIN);
 if (!['http:', 'https:'].includes(configured.protocol) || configured.username || configured.password || configured.pathname !== '/' || configured.search || configured.hash || (secure && configured.protocol !== 'https:')) {
  throw new Error('PUBLIC_ORIGIN 应为不含路径的站点来源，生产环境必须使用 HTTPS。');
 }
 publicOrigin = configured.origin;
}
const defaultOrigins = new Set([`http://127.0.0.1:${port}`, `http://localhost:${port}`]);
if (!['0.0.0.0', '::'].includes(host)) defaultOrigins.add(`http://${host.includes(':') ? `[${host}]` : host}:${port}`);

process.umask(0o077);
mkdirSync(dirname(dbPath), {recursive: true, mode: 0o700});
// The default folder belongs to this service. An explicitly configured shared
// folder is never chmod'ed: require the caller to provide a private directory.
if (dirname(dbPath) === resolve(here, 'data')) chmodSync(dirname(dbPath), 0o700);
if ((statSync(dirname(dbPath)).mode & 0o777) !== 0o700) {
 throw new Error('SIYUE_DB_PATH 所在目录须为独立的私有目录，权限必须为 0700。');
}
const db = new DatabaseSync(dbPath);
chmodSync(dbPath, 0o600);
db.exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
db.exec(`
 CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS employee_sequence(id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL);
 INSERT OR IGNORE INTO employee_sequence(id,value) VALUES(1,0);
 CREATE TABLE IF NOT EXISTS employees(
  id TEXT PRIMARY KEY, employee_code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, role_ids TEXT NOT NULL,
  department TEXT NOT NULL DEFAULT '', campus TEXT NOT NULL DEFAULT '', grade TEXT NOT NULL DEFAULT '',
  password_hash TEXT NOT NULL, password_salt TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
 );
 CREATE TABLE IF NOT EXISTS work_sessions(
  token_hash TEXT PRIMARY KEY, employee_id TEXT NOT NULL REFERENCES employees(id) ON DELETE CASCADE, expires_at INTEGER NOT NULL
 );
 CREATE INDEX IF NOT EXISTS sessions_expiry ON work_sessions(expires_at);
 CREATE TABLE IF NOT EXISTS auth_attempts(
  attempt_key TEXT PRIMARY KEY, attempts INTEGER NOT NULL, window_start INTEGER NOT NULL
 );
 CREATE TABLE IF NOT EXISTS needs(
  id TEXT PRIMARY KEY, request_id TEXT NOT NULL, owner_id TEXT NOT NULL REFERENCES employees(id), author TEXT NOT NULL,
  payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT '待核实', version INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(owner_id,request_id)
 );
 CREATE INDEX IF NOT EXISTS needs_created ON needs(created_at DESC);
`);
db.prepare('INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(1,?)').run(now());
transaction(() => {
 db.exec('CREATE UNIQUE INDEX IF NOT EXISTS employees_name_unique ON employees(lower(name))');
 db.prepare('INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(2,?)').run(now());
});
transaction(() => {
 if (db.prepare('SELECT version FROM schema_migrations WHERE version=3').get()) return;
 for (const row of db.prepare('SELECT id,role_ids FROM employees').all()) {
  const original = JSON.parse(row.role_ids);
  const normalized = normalizeRoleIds(original);
  if (original.length !== normalized.length || original.some((id, index) => id !== normalized[index])) {
   db.prepare('UPDATE employees SET role_ids=? WHERE id=?').run(JSON.stringify(normalized), row.id);
  }
 }
 for (const row of db.prepare('SELECT id,payload_json FROM needs').all()) {
  const payload = JSON.parse(row.payload_json);
  if (payload.roleId === 'principal') {
   // Preserve key order, all other content and record metadata, so retries from
   // old clients normalize to the same stored payload and stay idempotent.
   payload.roleId = normalizeRoleId(payload.roleId);
   db.prepare('UPDATE needs SET payload_json=? WHERE id=?').run(JSON.stringify(payload), row.id);
  }
 }
 db.prepare('INSERT INTO schema_migrations(version,applied_at) VALUES(3,?)').run(now());
});
const recordingsDir = resolve(process.env.SIYUE_RECORDINGS_DIR || resolve(dirname(dbPath), 'recordings'));
function physicalPath(path) {
 let ancestor = path;
 while (!existsSync(ancestor)) ancestor = dirname(ancestor);
 return resolve(realpathSync(ancestor), relative(ancestor, path));
}
const physicalStatic = physicalPath(staticDir), physicalRecordings = physicalPath(recordingsDir);
if (physicalRecordings === physicalStatic || physicalRecordings.startsWith(physicalStatic + sep)) throw new Error('录音目录不能位于公开静态网站目录内。');
const recordings = createRecordingStore(db, recordingsDir);
transaction(() => {
 if (db.prepare('SELECT version FROM schema_migrations WHERE version=4').get()) return;
 for (const row of db.prepare('SELECT id,payload_json FROM needs').all()) {
  const payload = JSON.parse(row.payload_json);
  if (payload.recordingIds === undefined) {
   payload.recordingIds = [];
   db.prepare('UPDATE needs SET payload_json=? WHERE id=?').run(JSON.stringify(payload), row.id);
  }
 }
 db.prepare('INSERT INTO schema_migrations(version,applied_at) VALUES(4,?)').run(now());
});
transaction(() => {
 db.exec(`CREATE TABLE IF NOT EXISTS need_ratings(
  need_id TEXT PRIMARY KEY REFERENCES needs(id), score INTEGER NOT NULL CHECK(typeof(score)='integer' AND score BETWEEN 0 AND 100),
  comment TEXT NOT NULL DEFAULT '', reviewer_id TEXT NOT NULL REFERENCES employees(id), reviewer_name TEXT NOT NULL,
  updated_at TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0)
 );
 CREATE TABLE IF NOT EXISTS need_rating_history(
  id TEXT PRIMARY KEY, need_id TEXT NOT NULL REFERENCES needs(id), score INTEGER NOT NULL CHECK(typeof(score)='integer' AND score BETWEEN 0 AND 100),
  comment TEXT NOT NULL DEFAULT '', reviewer_id TEXT NOT NULL REFERENCES employees(id), reviewer_name TEXT NOT NULL,
  updated_at TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0), UNIQUE(need_id,version)
 );`);
 db.prepare('INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(5,?)').run(now());
});

const knowledge = createKnowledgeStore(db, {directory: resolve(process.env.SIYUE_KNOWLEDGE_DIR || resolve(dirname(dbPath), 'knowledge')), staticDirectory: staticDir, administratorIds: knowledgeAdminIds});
const policyReview = createPolicyReviewStore(db, {getCatalog, requireActive: id => knowledge.requireActive(id)});
const agentRead = createAgentReadApi({getCatalog,searchCatalog,knowledge,semanticEnabled:semanticSearchEnabled,basePath:cookiePath});

class ApiError extends Error {
 constructor(status, message) { super(message); this.status = status; }
}
function transaction(fn) {
 db.exec('BEGIN IMMEDIATE');
 try { const value = fn(); db.exec('COMMIT'); return value; }
 catch (error) { db.exec('ROLLBACK'); throw error; }
}
function json(res, value, status = 200, headers = {}) {
 const body = JSON.stringify(value);
 res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers});
 res.end(body);
}
function publicProfile(row) {
 return {id: row.id, name: row.name, roleIds: normalizeRoleIds(JSON.parse(row.role_ids)),
  department: row.department, campus: row.campus, grade: row.grade, createdAt: row.created_at, updatedAt: row.updated_at};
}
function publicNeed(row, employeeId) {
 const {requestId, recordingIds = [], ...payload} = JSON.parse(row.payload_json);
 const rating = db.prepare('SELECT * FROM need_ratings WHERE need_id=?').get(row.id);
 const isOwner = row.owner_id === employeeId, canScore = canReview(employeeId) && knowledge.access(employeeId).status === 'active', canViewRating = isOwner || canScore;
 return {...payload, roleId: normalizeRoleId(payload.roleId), recordings: recordings.forNeed(row.id, recordingIds), id: row.id, ownerId: row.owner_id, author: row.author, status: row.status,
  version: row.version, createdAt: row.created_at, updatedAt: row.updated_at, canEdit: isOwner,
  rating: canViewRating && rating ? publicRating(rating) : null, canViewRating, canScore, canDelete: isOwner && !rating};
}
function publicRating(row) {
 return {score: row.score, comment: row.comment, reviewerId: row.reviewer_id, reviewerName: row.reviewer_name, updatedAt: row.updated_at, version: row.version};
}
function cookie(value, lifetime = sessionLifetime) {
 return `${cookieName}=${value}; Path=${cookiePath}; HttpOnly; SameSite=Lax; Max-Age=${Math.floor(lifetime / 1000)}${secure ? '; Secure' : ''}`;
}
function clientAddress(req) {
 const rawPeer = req.socket.remoteAddress || 'unknown';
 const peer = rawPeer.startsWith('::ffff:') && isIP(rawPeer.slice(7)) === 4 ? rawPeer.slice(7) : rawPeer;
 const loopback = peer === '::1' || (isIP(peer) === 4 && peer.startsWith('127.'));
 const real = req.headers['x-real-ip'];
 // nginx must overwrite X-Real-IP with $remote_addr. Never consume an XFF
 // chain, or trust any address header from a non-loopback network peer.
 if (trustLoopbackProxy && loopback && typeof real === 'string' && isIP(real)) {
  return real.startsWith('::ffff:') && isIP(real.slice(7)) === 4 ? real.slice(7) : real;
 }
 return peer;
}
function sessionToken(req) {
 const match = (req.headers.cookie || '').split(';').map(s => s.trim()).find(s => s.startsWith(`${cookieName}=`));
 const token = match?.slice(cookieName.length + 1);
 return token && /^[A-Za-z0-9_-]{48}$/.test(token) ? token : null;
}
function sessionProfile(req) {
 const token = sessionToken(req);
 if (!token) return null;
 return db.prepare(`SELECT e.* FROM work_sessions s JOIN employees e ON e.id=s.employee_id
  WHERE s.token_hash=? AND s.expires_at>?`).get(hash(token), Date.now()) || null;
}
function requireEmployee(req) {
 const profile = sessionProfile(req);
 if (!profile) throw new ApiError(401, '请先使用姓名和工作密码登录。');
 knowledge.requireAvailable(profile.id);
 return profile;
}
function issueSession(req, employeeId) {
 const token = randomBytes(36).toString('base64url');
 const previous = sessionToken(req);
 transaction(() => {
  if (previous) db.prepare('DELETE FROM work_sessions WHERE token_hash=?').run(hash(previous));
  db.prepare('DELETE FROM work_sessions WHERE expires_at<=?').run(Date.now());
  db.prepare('INSERT INTO work_sessions(token_hash,employee_id,expires_at) VALUES(?,?,?)').run(hash(token), employeeId, Date.now() + sessionLifetime);
 });
 return cookie(token);
}
function checkOrigin(req) {
 if (!['POST', 'PATCH', 'DELETE', 'PUT'].includes(req.method)) return;
 const site = req.headers['sec-fetch-site'];
 if (site && site !== 'same-origin' && site !== 'none') throw new ApiError(403, '请在本站页面提交操作。');
 if (req.headers.origin) {
  let origin;
  try { origin = new URL(req.headers.origin).origin; } catch { throw new ApiError(403, '请求来源无效。'); }
  const expected = publicOrigin || `http://${req.headers.host}`;
  if (origin === 'null' || origin !== expected || (!publicOrigin && !defaultOrigins.has(origin))) {
   throw new ApiError(403, '请求来源无效，请在本站页面重试。');
  }
 }
}
function readBody(req, limit = maxBodyBytes) {
 if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || '')) {
  throw new ApiError(415, '请使用 JSON 格式提交。');
 }
 if (Number(req.headers['content-length'] || 0) > limit) {
  req.resume(); throw new ApiError(413, '提交内容过大，请缩短后重试。');
 }
 return new Promise((resolveBody, reject) => {
  let size = 0, finished = false;
  const chunks = [];
  req.on('data', chunk => {
   if (finished) return;
   size += chunk.length;
   if (size > limit) { finished = true; chunks.length = 0; reject(new ApiError(413, '提交内容过大，请缩短后重试。')); return; }
   chunks.push(chunk);
  });
  req.on('end', () => {
   if (finished) return;
   try {
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    resolveBody(value);
   } catch { reject(new ApiError(400, '提交格式不正确，请刷新后重试。')); }
  });
  req.on('error', () => reject(new ApiError(400, '提交中断，请重试。')));
  req.on('aborted', () => reject(new ApiError(400, '提交中断，请重试。')));
 });
}
function reserveAttempt(key, maxAttempts, duration) {
 const current = Date.now();
 transaction(() => {
  const old = db.prepare('SELECT attempts,window_start FROM auth_attempts WHERE attempt_key=?').get(key);
  if (old && old.window_start > current - duration && old.attempts >= maxAttempts) {
   throw new ApiError(429, '尝试次数过多，请稍后再试。');
  }
  const attempts = old && old.window_start > current - duration ? old.attempts + 1 : 1;
  const start = attempts === 1 ? current : old.window_start;
  db.prepare(`INSERT INTO auth_attempts(attempt_key,attempts,window_start) VALUES(?,?,?)
   ON CONFLICT(attempt_key) DO UPDATE SET attempts=excluded.attempts,window_start=excluded.window_start`).run(key, attempts, start);
  db.prepare('DELETE FROM auth_attempts WHERE window_start<?').run(current - 24 * 60 * 60 * 1000);
 });
}
async function passwordHash(password, salt) {
 return (await scrypt(password, salt, 64, {N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024})).toString('hex');
}
const dummySalt = randomBytes(24).toString('hex');
const dummyHash = randomBytes(64);
const wrongCredentials = '姓名或工作密码不正确，请重试。';
const duplicateName = '该姓名已登记；同名员工可在姓名后加部门或校区。';

async function api(req, res, pathname) {
 checkOrigin(req);
 if (pathname === '/api/health' && req.method === 'GET') return json(res, {status: 'ok', storageReady: true, authMode: 'standalone'});
 if (pathname.startsWith('/api/agent/v1/')) {
  const profile = requireEmployee(req);
  if (req.method !== 'GET') throw new ApiError(405, 'Agent接口仅支持GET只读查询。');
  return json(res, agentRead(profile, new URL(req.url, 'http://local.invalid')));
 }
 if (pathname === '/api/me' && req.method === 'GET') {
  const profile = sessionProfile(req);
  return json(res, profile ? {identity: {userId: profile.id, displayName: profile.name}, profile: publicProfile(profile), authenticated: true, needsOnboarding: false, access: knowledge.access(profile.id), capabilities: {canReviewPolicies: policyReview.allowed(profile.id) && knowledge.access(profile.id).status === 'active', canScoreNeeds: canReview(profile.id) && knowledge.access(profile.id).status === 'active', canManageKnowledge: knowledge.access(profile.id).canManageKnowledge}}
   : {identity: null, profile: null, authenticated: false, needsOnboarding: true, capabilities: {canScoreNeeds: false, canManageKnowledge: false}});
 }
 if (pathname === '/api/policy-reviews' && req.method === 'GET') return json(res, policyReview.list(requireEmployee(req).id));
 const policyReviewMatch = pathname.match(/^\/api\/policy-reviews\/([a-zA-Z0-9_-]+)$/);
 if (policyReviewMatch && req.method === 'GET') return json(res, policyReview.detail(requireEmployee(req).id, policyReviewMatch[1]));
 if (policyReviewMatch && req.method === 'POST') {
  const profile = requireEmployee(req);
  reserveAttempt(`policy-review:${hash(profile.id)}`, 120, 60 * 60 * 1000);
  return json(res, policyReview.save(profile, policyReviewMatch[1], await readBody(req)));
 }
 if (pathname === '/api/catalog' && req.method === 'GET') {
  const profile = requireEmployee(req); knowledge.requireActive(profile.id);
  return json(res, getCatalog());
 }
 if (pathname === '/api/search' && req.method === 'GET') {
  const profile = requireEmployee(req), query = new URL(req.url, 'http://local.invalid').searchParams.get('q') || '';
  if (query.length > 200) throw new ApiError(400, '搜索内容最多200字。');
  const access = knowledge.access(profile.id);
  const results = mergeSearchResults(access.status === 'active' ? searchCatalog(query, semanticSearchEnabled) : [], knowledge.search(profile.id, query, semanticSearchEnabled));
  return json(res, {results, total: results.length});
 }
 if (pathname === '/api/knowledge/context' && req.method === 'GET') {
  return json(res, knowledge.context(requireEmployee(req).id));
 }
 if (pathname === '/api/organization/employees' && req.method === 'GET') {
  return json(res, knowledge.employees(requireEmployee(req).id));
 }
 if (pathname === '/api/organization/units' && req.method === 'POST') {
  const profile = requireEmployee(req); knowledge.requireAdmin(profile.id);
  return json(res, {unit: knowledge.createUnit(profile.id, await readBody(req))}, 201);
 }
 const accessMatch = pathname.match(/^\/api\/organization\/employees\/([0-9a-f-]+)$/i);
 if (accessMatch && req.method === 'PATCH') {
  const profile = requireEmployee(req); knowledge.requireAdmin(profile.id);
  return json(res, {employee: knowledge.updateEmployee(profile.id, accessMatch[1], await readBody(req))});
 }
 if (pathname === '/api/knowledge/attachments' && req.method === 'POST') {
  const profile = requireEmployee(req);
  reserveAttempt(`knowledge-upload:${hash(profile.id)}`, 30, 60 * 60 * 1000);
  reserveAttempt(`knowledge-upload-ip:${hash(clientAddress(req))}`, 100, 60 * 60 * 1000);
  const name = new URL(req.url, 'http://local.invalid').searchParams.get('name') || '';
  return json(res, {attachment: await knowledge.upload(profile.id, req, res, name)}, 201);
 }
 const knowledgeAttachmentMatch = pathname.match(/^\/api\/knowledge\/attachments\/([0-9a-f-]+)$/i);
 if (knowledgeAttachmentMatch && ['GET', 'HEAD'].includes(req.method)) {
  return knowledge.serveAttachment(requireEmployee(req).id, knowledgeAttachmentMatch[1], req, res);
 }
 if (pathname === '/api/knowledge' && req.method === 'GET') {
  const profile = requireEmployee(req), scope = new URL(req.url, 'http://local.invalid').searchParams.get('scope') || 'all';
  return json(res, {items: knowledge.listDocuments(profile.id, scope)});
 }
 if (pathname === '/api/knowledge' && req.method === 'POST') {
  const profile = requireEmployee(req);
  reserveAttempt(`knowledge-document:${hash(profile.id)}`, 60, 60 * 60 * 1000);
  const result = knowledge.saveDocument(profile.id, await readBody(req, 128 * 1024));
  return json(res, {document: result.document}, result.created ? 201 : 200);
 }
 const knowledgeMatch = pathname.match(/^\/api\/knowledge\/([0-9a-f-]+)$/i);
 if (knowledgeMatch && req.method === 'GET') return json(res, {document: knowledge.getDocument(requireEmployee(req).id, knowledgeMatch[1])});
 if (knowledgeMatch && req.method === 'PATCH') {
  const profile = requireEmployee(req), result = knowledge.saveDocument(profile.id, await readBody(req, 128 * 1024), knowledgeMatch[1]);
  return json(res, {document: result.document});
 }
 if (pathname === '/api/account/register' && req.method === 'POST') {
  reserveAttempt(`register-ip:${hash(clientAddress(req))}`, 10, 60 * 60 * 1000);
  const input = registerSchema.parse(await readBody(req));
  const salt = randomBytes(24).toString('hex');
  const digest = await passwordHash(input.password, salt);
  const profile = transaction(() => {
   if (db.prepare('SELECT id FROM employees WHERE lower(name)=lower(?)').get(input.name)) throw new ApiError(409, duplicateName);
   const sequence = db.prepare('UPDATE employee_sequence SET value=value+1 WHERE id=1 RETURNING value').get().value;
   const id = randomUUID(), code = `SY${String(sequence).padStart(5, '0')}`, timestamp = now();
   db.prepare(`INSERT INTO employees(id,employee_code,name,role_ids,department,campus,grade,password_hash,password_salt,created_at,updated_at)
    VALUES(?,?,?,?,?,?,?,?,?,?,?)`).run(id, code, input.name, JSON.stringify(input.roleIds), input.department, input.campus, input.grade, digest, salt, timestamp, timestamp);
   return db.prepare('SELECT * FROM employees WHERE id=?').get(id);
  });
  return json(res, {profile: publicProfile(profile)}, 201, {'Set-Cookie': issueSession(req, profile.id)});
 }
 if (pathname === '/api/account/login' && req.method === 'POST') {
  reserveAttempt(`login-ip:${hash(clientAddress(req))}`, 30, 15 * 60 * 1000);
  const input = await readBody(req);
  const name = typeof input.name === 'string' ? input.name.trim() : '';
  // SQLite's built-in lower() folds ASCII letters. Use the same normalization
  // for rate-limit keys as the unique index and lookup, including mixed-case names.
  const normalizedName = name.replace(/[A-Z]/g, letter => letter.toLowerCase());
  const attemptKey = `login-account:${hash(normalizedName)}`;
  reserveAttempt(attemptKey, 5, 15 * 60 * 1000);
  const profile = name.length >= 2 && name.length <= 30 ? db.prepare('SELECT * FROM employees WHERE lower(name)=lower(?)').get(name) : null;
  const validPassword = typeof input.password === 'string' && input.password.length >= 6 && input.password.length <= 128;
  const digest = Buffer.from(await passwordHash(validPassword ? input.password : '', profile?.password_salt || dummySalt), 'hex');
  if (!profile || !validPassword || !timingSafeEqual(digest, profile ? Buffer.from(profile.password_hash, 'hex') : dummyHash)) {
   throw new ApiError(401, wrongCredentials);
  }
  db.prepare('DELETE FROM auth_attempts WHERE attempt_key=?').run(attemptKey);
  return json(res, {profile: publicProfile(profile)}, 200, {'Set-Cookie': issueSession(req, profile.id)});
 }
 if (pathname === '/api/account/logout' && req.method === 'POST') {
  const token = sessionToken(req);
  if (token) db.prepare('DELETE FROM work_sessions WHERE token_hash=?').run(hash(token));
  return json(res, {ok: true}, 200, {'Set-Cookie': cookie('', 0)});
 }
 if (pathname === '/api/account/profile' && req.method === 'PATCH') {
  const profile = requireEmployee(req);
  const input = profileSchema.parse(await readBody(req));
  transaction(() => {
   if (db.prepare('SELECT id FROM employees WHERE lower(name)=lower(?) AND id<>?').get(input.name, profile.id)) throw new ApiError(409, duplicateName);
   db.prepare('UPDATE employees SET name=?,role_ids=?,department=?,campus=?,grade=?,updated_at=? WHERE id=?')
    .run(input.name, JSON.stringify(input.roleIds), input.department, input.campus, input.grade, now(), profile.id);
  });
  return json(res, {profile: publicProfile(db.prepare('SELECT * FROM employees WHERE id=?').get(profile.id))});
 }
 if (pathname === '/api/members' && req.method === 'GET') {
  const profile = requireEmployee(req);
  const rows = knowledge.access(profile.id).status === 'active' ? db.prepare("SELECT e.* FROM employees e JOIN employee_access a ON a.employee_id=e.id WHERE a.status='active' ORDER BY name,employee_code").all() : [profile];
  return json(res, {members: rows.map(row => ({...publicProfile(row), isSelf: row.id === profile.id}))});
 }
 if (pathname === '/api/recordings' && req.method === 'POST') {
  const profile = requireEmployee(req); knowledge.requireActive(profile.id);
  reserveAttempt(`recording-account:${hash(profile.id)}`, 30, 60 * 60 * 1000);
  reserveAttempt(`recording-ip:${hash(clientAddress(req))}`, 100, 60 * 60 * 1000);
  return json(res, await recordings.upload(req, res, profile.id), 201);
 }
 const recordingMatch = pathname.match(/^\/api\/recordings\/([0-9a-f-]+)$/i);
 if (recordingMatch && ['GET', 'HEAD'].includes(req.method)) {
  const profile = requireEmployee(req);
  if (knowledge.access(profile.id).status !== 'active') {
   const attachment = db.prepare('SELECT owner_id FROM recordings WHERE id=?').get(recordingMatch[1]);
   if (!attachment || attachment.owner_id !== profile.id) throw new ApiError(404, '录音不存在。');
  }
  return recordings.serve(req, res, recordingMatch[1], profile.id);
 }
 if (recordingMatch && req.method === 'DELETE') {
  const profile = requireEmployee(req);
  transaction(() => recordings.deletePending(recordingMatch[1], profile.id));
  return json(res, {ok: true});
 }
 if (pathname === '/api/needs' && req.method === 'GET') {
  const profile = requireEmployee(req);
  recordings.collect();
  const rows = knowledge.access(profile.id).status === 'active' ? db.prepare('SELECT * FROM needs ORDER BY created_at DESC,id DESC').all() : db.prepare('SELECT * FROM needs WHERE owner_id=? ORDER BY created_at DESC,id DESC').all(profile.id);
  return json(res, {needs: rows.map(row => publicNeed(row, profile.id)), total: rows.length});
 }
 if (pathname === '/api/needs' && req.method === 'POST') {
  const profile = requireEmployee(req); knowledge.requireActive(profile.id);
  recordings.collect();
  const input = needSchema.parse(await readBody(req));
  const payload = JSON.stringify(input);
  const result = transaction(() => {
   const old = db.prepare('SELECT * FROM needs WHERE owner_id=? AND request_id=?').get(profile.id, input.requestId);
   if (old) {
    const oldPayload = JSON.parse(old.payload_json);
    if (oldPayload.recordingIds === undefined) oldPayload.recordingIds = [];
    if (JSON.stringify(oldPayload) !== payload) throw new ApiError(409, '上次提交已保存，但内容与本次不同。请刷新查看，新增需求请重新打开登记表。');
    recordings.verify(input.recordingIds, profile.id, old.id);
    return {row: old, status: 200};
   }
   recordings.verify(input.recordingIds, profile.id);
   const id = randomUUID(), timestamp = now();
   db.prepare(`INSERT INTO needs(id,request_id,owner_id,author,payload_json,status,version,created_at,updated_at)
    VALUES(?,?,?,?,?,'待核实',1,?,?)`).run(id, input.requestId, profile.id, profile.name, payload, timestamp, timestamp);
   recordings.attach(input.recordingIds, profile.id, id);
   return {row: db.prepare('SELECT * FROM needs WHERE id=?').get(id), status: 201};
  });
  return json(res, {need: publicNeed(result.row, profile.id)}, result.status);
 }
 const ratingMatch = pathname.match(/^\/api\/needs\/([^/]+)\/rating$/);
 if (ratingMatch && req.method === 'PUT') {
  const profile = requireEmployee(req);
  knowledge.requireActive(profile.id);
  if (!canReview(profile.id)) throw new ApiError(403, '仅指定评审可以评分。');
  const input = ratingSchema.parse(await readBody(req));
  const need = transaction(() => {
   const target = db.prepare('SELECT * FROM needs WHERE id=?').get(ratingMatch[1]);
   if (!target) throw new ApiError(404, '反馈不存在。');
   const previous = db.prepare('SELECT version FROM need_ratings WHERE need_id=?').get(target.id);
   if ((previous?.version ?? 0) !== input.version) throw new ApiError(409, '评分已变化，请刷新后重试。');
   const version = input.version + 1, timestamp = now();
   if (previous) db.prepare('UPDATE need_ratings SET score=?,comment=?,reviewer_id=?,reviewer_name=?,updated_at=?,version=? WHERE need_id=? AND version=?')
    .run(input.score, input.comment, profile.id, profile.name, timestamp, version, target.id, input.version);
   else db.prepare('INSERT INTO need_ratings(need_id,score,comment,reviewer_id,reviewer_name,updated_at,version) VALUES(?,?,?,?,?,?,?)')
    .run(target.id, input.score, input.comment, profile.id, profile.name, timestamp, version);
   db.prepare('INSERT INTO need_rating_history(id,need_id,score,comment,reviewer_id,reviewer_name,updated_at,version) VALUES(?,?,?,?,?,?,?,?)')
    .run(randomUUID(), target.id, input.score, input.comment, profile.id, profile.name, timestamp, version);
   return target;
  });
  return json(res, {need: publicNeed(need, profile.id)});
 }
 const historyMatch = pathname.match(/^\/api\/needs\/([^/]+)\/rating\/history$/);
 if (historyMatch && req.method === 'GET') {
  const profile = requireEmployee(req);
  const target = db.prepare('SELECT owner_id FROM needs WHERE id=?').get(historyMatch[1]);
  if (!target) throw new ApiError(404, '反馈不存在。');
  if (target.owner_id !== profile.id && !(canReview(profile.id) && knowledge.access(profile.id).status === 'active')) throw new ApiError(403, '仅提交人和指定评审可以查看评分记录。');
  const history = db.prepare('SELECT * FROM need_rating_history WHERE need_id=? ORDER BY version DESC').all(historyMatch[1]);
  return json(res, {history: history.map(publicRating)});
 }
 const needMatch = pathname.match(/^\/api\/needs\/([^/]+)$/);
 if (needMatch && req.method === 'PATCH') {
  const profile = requireEmployee(req);
  const input = updateNeedSchema.parse(await readBody(req));
  const row = db.prepare('UPDATE needs SET status=?,version=version+1,updated_at=? WHERE id=? AND owner_id=? AND version=? RETURNING *')
   .get(input.status, now(), needMatch[1], profile.id, input.version);
  if (!row) throw new ApiError(409, '仅可更新本人需求；记录可能已变化，请刷新后重试。');
  return json(res, {need: publicNeed(row, profile.id)});
 }
 if (needMatch && req.method === 'DELETE') {
  const profile = requireEmployee(req);
  const input = await readBody(req);
  if (!Number.isSafeInteger(input.version) || input.version < 1) throw new ApiError(400, '记录版本不正确，请刷新。');
  transaction(() => {
   const row = db.prepare('SELECT id FROM needs WHERE id=? AND owner_id=? AND version=?').get(needMatch[1], profile.id, input.version);
   if (!row) throw new ApiError(409, '仅可删除本人需求；记录可能已变化，请刷新后重试。');
   if (db.prepare('SELECT need_id FROM need_ratings WHERE need_id=?').get(row.id)) throw new ApiError(409, '已评分的反馈需要保留，不能删除。');
   recordings.removeForNeed(row.id);
   db.prepare('DELETE FROM needs WHERE id=? AND owner_id=? AND version=?').run(row.id, profile.id, input.version);
  });
  recordings.reap();
  return json(res, {ok: true});
 }
 throw new ApiError(404, '接口不存在。');
}

const mimeTypes = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
 '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
 '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8'};
function serveStatic(req, res, pathname) {
 if (!['GET', 'HEAD'].includes(req.method)) throw new ApiError(405, '请求方法不支持。');
 let decoded;
 try { decoded = decodeURIComponent(pathname); } catch { throw new ApiError(400, '路径格式不正确。'); }
 if (decoded.includes('\0') || decoded.includes('\\') || decoded.split('/').some(part => part === '..' || part.startsWith('.'))) throw new ApiError(404, '页面不存在。');
 if (!existsSync(staticDir)) throw new ApiError(503, '前端尚未构建，请先执行本机版构建。');
 const root = realpathSync(staticDir);
 let candidate = resolve(root, `.${decoded}`);
 if (!(candidate === root || candidate.startsWith(root + sep))) throw new ApiError(404, '页面不存在。');
 if (existsSync(candidate) && statSync(candidate).isDirectory()) candidate = resolve(candidate, 'index.html');
 if (!existsSync(candidate)) {
  if (extname(decoded)) throw new ApiError(404, '文件不存在。');
  candidate = resolve(root, 'index.html');
 }
 if (!existsSync(candidate)) throw new ApiError(503, '前端尚未构建完成。');
 const target = realpathSync(candidate);
 if (!target.startsWith(root + sep) || !statSync(target).isFile()) throw new ApiError(404, '文件不存在。');
 const file = statSync(target);
 res.writeHead(200, {'Content-Type': mimeTypes[extname(target)] || 'application/octet-stream', 'Content-Length': file.size,
  'Cache-Control': extname(target) === '.html' ? 'no-store' : 'public, max-age=3600'});
 if (req.method === 'HEAD') { res.end(); return; }
 const stream = createReadStream(target);
 stream.on('error', () => res.destroy());
 stream.pipe(res);
}

const server = createServer(async (req, res) => {
 res.setHeader('X-Content-Type-Options', 'nosniff');
 res.setHeader('X-Frame-Options', 'DENY');
 res.setHeader('Referrer-Policy', 'same-origin');
 try {
  const pathname = new URL(req.url, 'http://local.invalid').pathname;
  if (pathname === '/api' || pathname.startsWith('/api/')) await api(req, res, pathname);
  else serveStatic(req, res, pathname);
 } catch (error) {
  if (res.headersSent) { res.destroy(); return; }
  if (error instanceof PolicyReviewError || error instanceof ApiError || error instanceof AudioError || error instanceof KnowledgeError || error instanceof AgentReadError) {
   if ((error instanceof AudioError || error instanceof KnowledgeError) && !req.complete && !req.destroyed) req.resume();
   if (error.status === 413 && !(error instanceof AudioError)) res.setHeader('Connection', 'close');
   return json(res, {error: error.message}, error.status);
  }
  if (Array.isArray(error?.issues)) return json(res, {error: error.issues[0]?.message || '提交内容不正确。'}, 400);
  // Deliberately omit request bodies, SQL, parameters, credentials and session tokens.
  console.error(JSON.stringify({event: 'request_failed', errorType: error?.name || 'Error', time: now()}));
  json(res, {error: '服务暂时不可用，请稍后重试。'}, 500);
 }
});
server.requestTimeout = 30_000;
server.headersTimeout = 15_000;
server.keepAliveTimeout = 5_000;
server.listen(port, host, () => console.log(`思跃本机服务已启动：http://${host.includes(':') ? `[${host}]` : host}:${port}`));
let closing = false;
function shutdown() {
 if (closing) return;
 closing = true;
 server.close(() => { db.close(); process.exit(0); });
 setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
