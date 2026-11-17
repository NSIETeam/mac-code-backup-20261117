/**
 * 排课系统 - 后端服务（Node.js 原生实现，零第三方依赖）
 * 功能：读写服务器上的 data.json，实现多人共同编辑 + 账号权限管理
 *
 * 启动：node server.js
 * 默认端口 8080，可用环境变量 PORT 覆盖
 *
 * 接口：
 *   POST /api/login        -> 账号登录，返回 token（{username, password}）
 *   GET  /api/data         -> 返回当前排课数据（无需登录，俯视图只读）
 *   POST /api/data         -> 保存排课数据（需登录；普通账号只能改绑定表）
 *   GET  /api/users        -> 列出账号（仅管理员）
 *   POST /api/users        -> 账号增删改（仅管理员）
 *   GET  /                -> 返回前端页面
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const zlib = require('zlib');

// 强制 stdout/stderr 用 UTF-8，避免 Windows 黑框（GBK 代码页）中文乱码
if (process.stdout && process.stdout.setDefaultEncoding) process.stdout.setDefaultEncoding('utf8');
if (process.stderr && process.stderr.setDefaultEncoding) process.stderr.setDefaultEncoding('utf8');

const PORT = parseInt(process.env.PORT || '8080', 10);
const DATA_DIR = process.env.PAIKE_DATA_DIR || __dirname;
const HOST = process.env.PAIKE_HOST;
const DATA_FILE = path.join(DATA_DIR, 'data.json');
const INDEX_FILE = path.join(__dirname, 'index.html');
const PASSWORD_FILE = process.env.PAIKE_ADMIN_PASSWORD_FILE || path.join(DATA_DIR, 'password.txt');
const USERS_FILE = path.join(DATA_DIR, 'users.json');
const MEDIA_DIR = path.join(DATA_DIR, 'media-cache');
const MEDIA_REF = /^paike-image:([a-f0-9]{64})$/;
const IMAGE_URI = /^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\r\n]+)$/;

// 默认管理员密码（首次启动时若 users.json 不存在，用它创建 admin 账号）
const DEFAULT_ADMIN_PASSWORD = HOST ? '' : '123456';

// 会话 token（内存保存，重启后需重新登录）
// sessions: token -> { username, role, suits, exp }
const sessions = new Map();
const SESSION_TTL = 24 * 60 * 60 * 1000; // 24 小时

// 写锁：防止并发写坏文件（简单串行化）
let writing = false;
let writeQueue = [];

// ========== 密码 hash ==========
function hashPwd(pwd) {
  return crypto.createHash('sha256').update(String(pwd)).digest('hex');
}

// ========== 账号管理 ==========
function ensureUsersFile() {
  if (!fs.existsSync(USERS_FILE)) {
    // 首次：用 password.txt 的密码（若有）或默认密码创建 admin 账号
    let adminPwd = DEFAULT_ADMIN_PASSWORD;
    try {
      if (fs.existsSync(PASSWORD_FILE)) {
        adminPwd = fs.readFileSync(PASSWORD_FILE, 'utf-8').trim() || DEFAULT_ADMIN_PASSWORD;
      }
    } catch (_) {}
    if (!adminPwd) throw new Error('An initial admin password is required');
    const users = {
      users: [
        { username: 'admin', password: hashPwd(adminPwd), role: 'admin', suits: ['*'] }
      ]
    };
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2), 'utf-8');
    console.log('[OK] 默认管理员账号已创建: admin');
  }
}
function readUsers() {
  ensureUsersFile();
  const raw = fs.readFileSync(USERS_FILE, 'utf-8');
  try {
    const d = JSON.parse(raw);
    if (!d || !Array.isArray(d.users)) return { users: [] };
    return d;
  } catch (e) {
    return { users: [] };
  }
}
function writeUsers(users) {
  const tmp = USERS_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(users, null, 2), 'utf-8');
  fs.renameSync(tmp, USERS_FILE);
}
function findUser(username) {
  const d = readUsers();
  return d.users.find(u => u.username === username);
}
// 校验账号密码，返回用户对象（不含密码 hash）
function authenticate(username, password) {
  const u = findUser(username);
  if (!u) return null;
  if (u.password !== hashPwd(password)) return null;
  return { username: u.username, role: u.role, suits: u.suits || [], status: u.status || 'approved' };
}

// 检测字符串中是否有编码损坏（U+FFFD 替换符，通常由 GBK/UTF-8 混用导致）
function findBadChars(obj) {
  const bad = [];
  function walk(v, path) {
    if (typeof v === 'string') {
      if (v.indexOf('\uFFFD') >= 0) bad.push(path + ' = ' + JSON.stringify(v));
      return;
    }
    if (Array.isArray(v)) { v.forEach((it, i) => walk(it, path + '[' + i + ']')); return; }
    if (v && typeof v === 'object') { Object.keys(v).forEach(k => walk(v[k], path ? path + '.' + k : k)); }
  }
  walk(obj, '');
  return bad;
}

// 返回所有含 U+FFFD 的字段路径（如 suits[3].teachers[12].subject）
function findBadPaths(obj) {
  const bad = [];
  function walk(v, path) {
    if (typeof v === 'string') { if (v.indexOf('\uFFFD') >= 0) bad.push(path); return; }
    if (Array.isArray(v)) { v.forEach((it, i) => walk(it, path + '[' + i + ']')); return; }
    if (v && typeof v === 'object') { Object.keys(v).forEach(k => walk(v[k], path ? path + '.' + k : k)); }
  }
  walk(obj, '');
  return bad;
}
// 按路径取值/赋值，支持 suits[3].teachers[12].cells.8:00-11:00.cls 这种写法
function pathSegments(p) { const parts = String(p).match(/[^.[\]]+/g) || []; if (parts.some(s => ['__proto__','constructor','prototype'].includes(s))) throw new Error('无效字段路径'); return parts; }
function getByPath(root, p) {
  let cur = root;
  for (const s of pathSegments(p)) { if (cur === null || cur === undefined) return undefined; cur = cur[s]; }
  return cur;
}
function setByPath(root, p, val) {
  const segs = pathSegments(p);
  let cur = root;
  for (let i = 0; i < segs.length - 1; i++) {
    if (cur[segs[i]] === null || cur[segs[i]] === undefined) return false;
    cur = cur[segs[i]];
  }
  cur[segs[segs.length - 1]] = val;
  return true;
}

// ========== 数据管理 ==========
function ensureDataFile() {
  if (!fs.existsSync(DATA_FILE)) {
    const init = { suits: [], version: 1 };
    fs.writeFileSync(DATA_FILE, JSON.stringify(init, null, 2), 'utf-8');
  }
}
// 内存缓存：按文件 mtime 判断是否需要重新解析，避免每次请求都解析 63MB JSON
const cache = { mtimeMs: -1, data: null, buf: null, gz: null, lightBuf: null, lightGz: null };

function readData() {
  ensureDataFile();
  let st;
  try { st = fs.statSync(DATA_FILE); } catch (_) { st = { mtimeMs: -1 }; }
  if (cache.data && cache.mtimeMs === st.mtimeMs) return cache.data;

  const raw = fs.readFileSync(DATA_FILE, 'utf-8');
  let data;
  try {
    data = JSON.parse(raw);
  } catch (e) {
    throw new Error('数据文件无法解析，已停止读写并保留原文件，请联系管理员恢复备份');
  }
  if (!data || !Array.isArray(data.suits) || !Number.isSafeInteger(data.version) || data.version < 0) throw new Error('数据结构或版本无效，已停止读写');
  const bad = findBadChars(data);
  if (bad.length) {
    console.log('[编码告警] data.json 存在 ' + bad.length + ' 处编码损坏(U+FFFD)，疑似被非 UTF-8 工具写入：');
    bad.slice(0, 20).forEach(b => console.log('  - ' + b));
  }
  cache.data = data; cache.mtimeMs = st.mtimeMs; cache.buf = null; cache.gz = null; cache.lightBuf = null; cache.lightGz = null;
  return data;
}

// Lightweight API keeps image references in transit. The authoritative data.json
// and its backups still contain the original, lossless image strings.
function mapPhotoValues(value, convert, key) {
  if (typeof value === 'string') return key === 'photo' ? convert(value) : value;
  if (Array.isArray(value)) return value.map(v => mapPhotoValues(v, convert));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v]) => [k,mapPhotoValues(v,convert,k)]));
  return value;
}
function registerImage(uri) {
  if (!IMAGE_URI.test(uri)) return uri;
  const id = crypto.createHash('sha256').update(uri).digest('hex');
  fs.mkdirSync(MEDIA_DIR, {recursive:true,mode:0o700});
  const file = path.join(MEDIA_DIR,id+'.txt');
  if (!fs.existsSync(file)) {
    const tmp = file+'.tmp';
    fs.writeFileSync(tmp,uri,{encoding:'utf8',mode:0o600});
    fs.renameSync(tmp,file);
  }
  return 'paike-image:'+id;
}
function readImage(id) {
  if (!/^[a-f0-9]{64}$/.test(id)) throw new Error('无效图片编号');
  const file = path.join(MEDIA_DIR,id+'.txt');
  if (!fs.existsSync(file)) dataBuffer(true);
  if (!fs.existsSync(file)) { const e=new Error('图片不存在，请重新加载最新课表');e.status=422;throw e; }
  const uri=fs.readFileSync(file,'utf8');
  if (!IMAGE_URI.test(uri) || crypto.createHash('sha256').update(uri).digest('hex')!==id) throw new Error('图片缓存校验失败，原始课表数据未修改');
  return uri;
}
function dataBuffer(compact) {
  const data=readData();
  if (compact) {
    if (!cache.lightBuf) cache.lightBuf=Buffer.from(JSON.stringify(mapPhotoValues(data,registerImage)),'utf8');
    return cache.lightBuf;
  }
  if (!cache.buf) cache.buf=Buffer.from(JSON.stringify(data),'utf8');
  return cache.buf;
}
function sendData(res,req) {
  const compact=/[?&]compact=1(?:&|$)/.test(req.url||'');
  let buf=dataBuffer(compact);
  const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Vary':'Accept-Encoding','Access-Control-Allow-Origin':'*'};
  if (/\bgzip\b/.test(String(req.headers['accept-encoding']||''))) {
    const key=compact?'lightGz':'gz';
    if (!cache[key]) cache[key]=zlib.gzipSync(buf,{level:6});
    buf=cache[key]; headers['Content-Encoding']='gzip';
  }
  headers['Content-Length']=buf.length;
  res.writeHead(200,headers);res.end(buf);
}
function writeData(data) {
  const bad = findBadChars(data);
  if (bad.length) {
    const err = new Error('检测到编码损坏字符(U+FFFD)，已拒绝写入以保护数据：' + bad.slice(0, 3).join('；'));
    console.log('[编码保护] ' + err.message);
    throw err;
  }
  // Keep the previous 20 revisions; backup failure must stop the write.
  const backups = path.join(DATA_DIR, 'backups');
  fs.mkdirSync(backups, { recursive: true, mode: 0o700 });
  const previous = readData();
  const backup = path.join(backups, 'data-v' + previous.version + '-' + Date.now() + '.json');
  fs.copyFileSync(DATA_FILE, backup, fs.constants.COPYFILE_EXCL);
  const tmp = DATA_FILE + '.tmp';
  const fd = fs.openSync(tmp, 'w', 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(data), 'utf-8'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
  fs.renameSync(tmp, DATA_FILE);
  // Cleanup follows a successful commit; cleanup failure cannot change its result.
  try {
    const old = fs.readdirSync(backups).filter(n => /^data-v\d+-\d+\.json$/.test(n))
      .sort((a,b) => Number(a.match(/-(\d+)\.json$/)[1]) - Number(b.match(/-(\d+)\.json$/)[1]));
    old.slice(0, Math.max(0, old.length - 20)).forEach(n => fs.unlinkSync(path.join(backups,n)));
  } catch (_) { console.log('[backup] retention cleanup deferred'); }
  // 写入后刷新缓存
  try { cache.data = data; cache.mtimeMs = fs.statSync(DATA_FILE).mtimeMs; } catch (_) { cache.mtimeMs = -1; }
  cache.buf = null;
  cache.gz = null; cache.lightBuf = null; cache.lightGz = null;
}
function enqueueWrite(data, expectedVersion, cb) {
  writeQueue.push({ data, expectedVersion, cb });
  if (writing) return;
  writing = true;
  const next = () => {
    const item = writeQueue.shift();
    if (!item) { writing = false; return; }
    try {
      const current = readData();
      if (item.expectedVersion !== current.version) {
        const err = new Error('已有其他人保存了更新。请导出当前草稿，再加载最新数据进行合并。');
        err.status = 409; err.version = current.version; throw err;
      }
      if (current.version >= Number.MAX_SAFE_INTEGER) throw new Error('版本号达到上限');
      // Check the base version before resolving references: stale drafts must
      // never overwrite current data, and missing images must fail closed.
      const hydrated = mapPhotoValues(item.data, value => {
        const match=MEDIA_REF.exec(value);
        return match ? readImage(match[1]) : value;
      });
      item.data = hydrated;
      item.data.version = current.version + 1;
      item.data.updatedAt = new Date().toISOString();
      writeData(item.data);
      item.cb(null,item.data.version);
    } catch (e) {
      item.cb(e);
    }
    setImmediate(next);
  };
  next();
}

// ========== 工具 ==========
function sendJSON(res, code, obj) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  });
  res.end(JSON.stringify(obj));
}
function sendFile(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType + '; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(data);
  });
}
function readBody(req, cb) {
  let body = '';
  req.setEncoding('utf8');
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => cb(body));
}
function getToken(req) {
  const auth = req.headers['authorization'] || '';
  if (auth.startsWith('Bearer ')) return auth.slice(7);
  return '';
}
// 返回当前会话的用户对象，或 null
function getSessionUser(req) {
  const token = getToken(req);
  if (!token || !sessions.has(token)) return null;
  const s = sessions.get(token);
  if (Date.now() > s.exp) { sessions.delete(token); return null; }
  return s;
}
function isAdmin(user) {
  return user && user.role === 'admin';
}
// 用户是否有权编辑某张表（suitId）
function canEditSuit(user, suitId) {
  if (!user) return false;
  if (user.role === 'admin') return true;
  const suits = user.suits || [];
  if (suits.includes('*')) return true;
  return suits.includes(suitId);
}

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  const method = (req.method || 'GET').toUpperCase();

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    });
    res.end();
    return;
  }

  try {
    // ===== 登录（无需 token）=====
    if (url === '/api/login' && method === 'POST') {
      readBody(req, body => {
        try {
          const { username, password } = JSON.parse(body || '{}');
          if (!username || !password) {
            sendJSON(res, 400, { ok: false, error: '请输入用户名和密码' });
            return;
          }
          const user = authenticate(username, password);
          if (user) {
            if (user.status === 'pending') {
              sendJSON(res, 403, { ok: false, error: '账号待管理员审批，请耐心等待' });
              return;
            }
            const token = crypto.randomBytes(24).toString('hex');
            sessions.set(token, { username: user.username, role: user.role, suits: user.suits, exp: Date.now() + SESSION_TTL });
            sendJSON(res, 200, { ok: true, token, user });
          } else {
            sendJSON(res, 401, { ok: false, error: '用户名或密码错误' });
          }
        } catch (e) {
          sendJSON(res, 400, { ok: false, error: '请求格式错误' });
        }
      });
      return;
    }

    // ===== 申请账号（无需登录）=====
    if (url === '/api/apply' && method === 'POST') {
      readBody(req, body => {
        try {
          const { username, password, realname } = JSON.parse(body || '{}');
          if (!username || !password) {
            sendJSON(res, 400, { ok: false, error: '请输入用户名和密码' });
            return;
          }
          const uname = String(username).trim();
          const pwd = String(password);
          const realName = String(realname || '').trim();
          if (uname.length < 2) { sendJSON(res, 400, { ok: false, error: '用户名至少 2 个字符' }); return; }
          if (pwd.length < 4) { sendJSON(res, 400, { ok: false, error: '密码至少 4 位' }); return; }
          const d = readUsers();
          const exist = d.users.find(u => u.username === uname);
          if (exist) {
            if (exist.status === 'pending') {
              sendJSON(res, 400, { ok: false, error: '该账号已在申请中，请等待审批' });
            } else {
              sendJSON(res, 400, { ok: false, error: '该用户名已被占用' });
            }
            return;
          }
          d.users.push({ username: uname, password: hashPwd(pwd), role: 'user', suits: [], status: 'pending', realname: realName, applyAt: new Date().toISOString() });
          writeUsers(d);
          sendJSON(res, 200, { ok: true, message: '申请已提交，请等待管理员审批' });
        } catch (e) {
          sendJSON(res, 400, { ok: false, error: '请求格式错误' });
        }
      });
      return;
    }

    // 健康检查
    if (url === '/api/health') {
      sendJSON(res, 200, { ok: true, time: new Date().toISOString() });
      return;
    }

    // 首页
    if (url === '/' || url === '/index.html') {
      sendFile(res, INDEX_FILE, 'text/html');
      return;
    }

    // ===== 以下接口都需要登录（含 GET /api/data，未登录不能查看任何内容）=====
    const user = getSessionUser(req);
    if (!user) {
      sendJSON(res, 401, { ok: false, error: '未登录或登录已过期' });
      return;
    }

    // Images require the same authenticated session as schedules.
    const mediaMatch = /^\/api\/media\/([a-f0-9]{64})$/.exec(url);
    if (mediaMatch && method === 'GET') {
      const uri=readImage(mediaMatch[1]);
      if (/[?&]format=datauri(?:&|$)/.test(req.url||'')) {
        sendJSON(res,200,{ok:true,dataUri:uri}); return;
      }
      const match=IMAGE_URI.exec(uri), bytes=Buffer.from(match[2],'base64');
      res.writeHead(200,{'Content-Type':match[1],'Content-Length':bytes.length,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'none'; sandbox"});
      res.end(bytes); return;
    }

    // ===== 读数据（登录后）=====
    if (url === '/api/data' && method === 'GET') {
      sendData(res, req);
      return;
    }

    // ===== 轻量版本号（登录后，供前端轮询，几百字节）=====
    if (url === '/api/version' && method === 'GET') {
      const d = readData();
      sendJSON(res, 200, { ok: true, version: d.version || 0, updatedAt: d.updatedAt || '' });
      return;
    }

    // ===== 账号管理（仅管理员）=====
    if (url === '/api/users' && method === 'GET') {
      if (!isAdmin(user)) { sendJSON(res, 403, { ok: false, error: '无权限' }); return; }
      const d = readUsers();
      // 返回不含密码 hash 的列表
      const list = d.users.map(u => ({ username: u.username, role: u.role, suits: u.suits || [], status: u.status || 'approved', applyAt: u.applyAt || '', realname: u.realname || '' }));
      sendJSON(res, 200, { ok: true, users: list });
      return;
    }

    if (url === '/api/users' && method === 'POST') {
      if (!isAdmin(user)) { sendJSON(res, 403, { ok: false, error: '无权限' }); return; }
      readBody(req, body => {
        try {
          const { action, username, password, role, suits } = JSON.parse(body || '{}');
          const d = readUsers();
          if (action === 'add') {
            if (!username || !password) { sendJSON(res, 400, { ok: false, error: '用户名和密码必填' }); return; }
            if (d.users.find(u => u.username === username)) { sendJSON(res, 400, { ok: false, error: '用户名已存在' }); return; }
            d.users.push({ username, password: hashPwd(password), role: role === 'admin' ? 'admin' : 'user', suits: Array.isArray(suits) ? suits : [] });
            writeUsers(d);
            sendJSON(res, 200, { ok: true });
          } else if (action === 'update') {
            const u = d.users.find(x => x.username === username);
            if (!u) { sendJSON(res, 404, { ok: false, error: '账号不存在' }); return; }
            if (password) u.password = hashPwd(password);
            if (role === 'admin' || role === 'user') u.role = role;
            if (Array.isArray(suits)) u.suits = suits;
            writeUsers(d);
            sendJSON(res, 200, { ok: true });
          } else if (action === 'delete') {
            if (username === 'admin') { sendJSON(res, 400, { ok: false, error: '不能删除默认管理员' }); return; }
            d.users = d.users.filter(x => x.username !== username);
            writeUsers(d);
            sendJSON(res, 200, { ok: true });
          } else if (action === 'approve') {
            const u = d.users.find(x => x.username === username);
            if (!u) { sendJSON(res, 404, { ok: false, error: '申请不存在' }); return; }
            u.status = 'approved';
            delete u.applyAt;
            if (role === 'admin') u.role = 'admin';
            writeUsers(d);
            sendJSON(res, 200, { ok: true });
          } else if (action === 'reject') {
            const u = d.users.find(x => x.username === username);
            if (!u) { sendJSON(res, 404, { ok: false, error: '申请不存在' }); return; }
            d.users = d.users.filter(x => x.username !== username);
            writeUsers(d);
            sendJSON(res, 200, { ok: true });
          } else {
            sendJSON(res, 400, { ok: false, error: '未知操作' });
          }
        } catch (e) {
          sendJSON(res, 400, { ok: false, error: '请求格式错误' });
        }
      });
      return;
    }

    // ===== 保存排课数据（仅管理员）=====
    if (url === '/api/data' && method === 'POST') {
      if (!isAdmin(user)) { sendJSON(res, 403, { ok: false, error: '普通账号只能查看，不能编辑' }); return; }
      readBody(req, body => {
        try {
          const data = JSON.parse(body);
          if (!data || typeof data !== 'object' || !Array.isArray(data.suits)) {
            sendJSON(res, 400, { ok: false, error: '数据格式不正确' });
            return;
          }
          const expectedVersion = data.expectedVersion;
          if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
            sendJSON(res, 428, { ok: false, error: '页面版本过旧或缺少数据版本，请刷新后重试' }); return;
          }
          const finalData = data;
          delete finalData.expectedVersion;
          // ---- 乱码自愈 ----
          // 客户端（尤其是修复前就打开的老页面）可能带着 U+FFFD 的旧数据来保存。
          // 直接用服务端现有干净值替换这些乱码字段，避免每次保存都被拒、用户反复点保存失败。
          let repaired = 0;
          const unrepaired = [];
          const badPaths = findBadPaths(finalData);
          if (badPaths.length) {
            const cur = readData();
            for (const p of badPaths) {
              const v = getByPath(cur, p);
              if (typeof v === 'string' && v.indexOf('\uFFFD') < 0 && setByPath(finalData, p, v)) repaired++;
              else unrepaired.push(p);
            }
            const who = (user && user.username) || '?';
            const ip = req.socket.remoteAddress || '?';
            console.log('[' + new Date().toISOString() + '][自愈] ' + who + ' @ ' + ip + ' 提交了 ' + badPaths.length + ' 处乱码：已用服务端数据修复 ' + repaired + ' 处' +
              (unrepaired.length ? '，无法修复 ' + unrepaired.length + ' 处: ' + unrepaired.slice(0, 5).join(' | ') : ''));
            if (unrepaired.length) {
              sendJSON(res, 500, { ok: false, error: '保存失败：数据含无法修复的乱码字段（' + unrepaired.slice(0, 3).join('、') + '），请刷新页面后重试' });
              return;
            }
          }
          enqueueWrite(finalData, expectedVersion, (err, savedVersion) => {
            if (err) { sendJSON(res, err.status || 500, { ok: false, error: '保存失败：' + err.message, version: err.version }); return; }
            sendJSON(res, 200, { ok: true, version: savedVersion, repaired: repaired });
          });
        } catch (e) {
          sendJSON(res, 400, { ok: false, error: '请求体不是有效 JSON' });
        }
      });
      return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404 Not Found');
  } catch (e) {
    sendJSON(res, e.status || 500, { ok: false, error: e.message });
  }
});

ensureUsersFile();
server.listen(PORT, HOST, () => {
  const nets = os.networkInterfaces();
  const addrs = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) addrs.push(net.address);
    }
  }
  console.log('==============================================');
  console.log('  PaiKe System started (account login)');
  console.log('  Local:     http://localhost:' + PORT);
  if (!HOST) addrs.forEach(a => console.log('  LAN:       http://' + a + ':' + PORT));
  console.log('  Data file: ' + DATA_FILE);
  console.log('  User file: ' + USERS_FILE);
  console.log('  Admin:     admin');
  console.log('  Press Ctrl+C to stop');
  console.log('==============================================');
});
