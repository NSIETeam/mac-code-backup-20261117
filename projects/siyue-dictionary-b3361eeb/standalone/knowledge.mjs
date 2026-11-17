import {prepareSemanticQuery, prepareSemanticEntry, semanticMatch, SEMANTIC_DOCUMENT_LIMIT} from './domain-search.mjs';
import {randomUUID, createHash} from 'node:crypto';
import {mkdirSync, existsSync, realpathSync, statSync, createReadStream, readdirSync, unlinkSync} from 'node:fs';
import {writeFile, rename, rm} from 'node:fs/promises';
import {resolve, dirname, relative, sep, extname} from 'node:path';
import {inflateRawSync} from 'node:zlib';

export class KnowledgeError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new KnowledgeError(status, message); };
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const KINDS = ['department', 'campus', 'grade', 'project'];
const STATUSES = ['pending', 'active', 'suspended'];
export const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
const MAX_OWNER_BYTES = 200 * 1024 * 1024;
const MAX_OWNER_FILES = 100;
const MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024;
const MAX_CONCURRENT_UPLOADS = 2;
const PENDING_LIFETIME = 24 * 60 * 60 * 1000;
const now = () => new Date().toISOString();
const digest = value => createHash('sha256').update(value).digest('hex');
const normalized = value => value.normalize('NFKC').toLowerCase().replace(/\s+/gu, ' ').trim();
function text(value, max, label, required = false) {
 if (typeof value !== 'string' || value.trim().length > max || (required && !value.trim())) fail(400, `${label}${required ? '不能为空且' : ''}最多${max}字。`);
 return value.trim();
}
function ids(value, max = 100) {
 if (!Array.isArray(value) || value.length > max || value.some(id => typeof id !== 'string' || !UUID.test(id)) || new Set(value.map(id => id.toLowerCase())).size !== value.length) fail(400, '共享对象或附件标识无效，请重新选择。');
 return value.map(id => id.toLowerCase()).sort();
}
function physicalPath(path) {
 let ancestor = path;
 while (!existsSync(ancestor)) ancestor = dirname(ancestor);
 return resolve(realpathSync(ancestor), relative(ancestor, path));
}
function plainXml(value) {
 return value.replace(/<[^>]*>/g, ' ').replace(/&(?:amp|lt|gt|quot|apos);/g, part => ({'&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'"}[part])).replace(/&#(x[0-9a-f]+|\d+);/gi, (_, code) => { const n = code[0].toLowerCase() === 'x' ? parseInt(code.slice(1), 16) : parseInt(code, 10); return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : ''; }).replace(/\s+/gu, ' ').trim();
}
function inspectOffice(buffer, extension) {
 let end = -1;
 for (let offset = buffer.length - 22; offset >= Math.max(0, buffer.length - 65557); offset--) if (buffer.readUInt32LE(offset) === 0x06054b50) { end = offset; break; }
 if (end < 0 || end + 22 + buffer.readUInt16LE(end + 20) !== buffer.length || buffer.readUInt16LE(end + 4) || buffer.readUInt16LE(end + 6)) fail(400, 'Office 文件结构无效。');
 const count = buffer.readUInt16LE(end + 10), size = buffer.readUInt32LE(end + 12), start = buffer.readUInt32LE(end + 16);
 if (!count || count > 10000 || count !== buffer.readUInt16LE(end + 8) || start + size !== end) fail(400, 'Office 文件结构无效或内容过大。');
 const entries = new Map(); let offset = start, expanded = 0, extracted = '';
 for (let index = 0; index < count; index++) {
  if (offset + 46 > end || buffer.readUInt32LE(offset) !== 0x02014b50) fail(400, 'Office 文件目录无效。');
  const flags = buffer.readUInt16LE(offset + 8), method = buffer.readUInt16LE(offset + 10), packed = buffer.readUInt32LE(offset + 20), unpacked = buffer.readUInt32LE(offset + 24);
  const nameLength = buffer.readUInt16LE(offset + 28), extraLength = buffer.readUInt16LE(offset + 30), commentLength = buffer.readUInt16LE(offset + 32), local = buffer.readUInt32LE(offset + 42);
  const next = offset + 46 + nameLength + extraLength + commentLength;
  if (next > end || flags & 1 || ![0, 8].includes(method) || local + 30 > start || buffer.readUInt32LE(local) !== 0x04034b50 || buffer.readUInt16LE(local + 8) !== method || buffer.readUInt16LE(local + 6) !== flags) fail(400, '不支持加密或结构异常的 Office 文件。');
  const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);
  if (!name || name.includes('\0') || name.includes('\\') || name.startsWith('/') || name.split('/').some(part => part === '..') || entries.has(name)) fail(400, 'Office 文件内部路径无效。');
  if (/vbaproject|\.exe$|\.dll$|\.js$|\.html?$|\.vbs$|\.bat$|\.ps1$/i.test(name)) fail(400, '不支持包含宏或可执行内容的文件。');
  const localNameLength = buffer.readUInt16LE(local + 26), localExtraLength = buffer.readUInt16LE(local + 28), from = local + 30 + localNameLength + localExtraLength;
  if (from + packed > start || buffer.toString('utf8', local + 30, local + 30 + localNameLength) !== name || unpacked > 20 * 1024 * 1024) fail(400, 'Office 文件内容无效或过大。');
  expanded += unpacked;
  if (expanded > 100 * 1024 * 1024 || (packed > 0 && unpacked / packed > 1000)) fail(400, 'Office 文件展开后过大。');
  entries.set(name, {from, packed, unpacked, method}); offset = next;
 }
 if (offset !== end || !entries.has('[Content_Types].xml') || !entries.has('_rels/.rels') || !entries.has(extension === '.docx' ? 'word/document.xml' : 'xl/workbook.xml')) fail(400, '文件内容与 Office 扩展名不符。');
 const readEntry = name => {
  const e = entries.get(name); if (!e) return '';
  let raw; try { raw = e.method === 0 ? buffer.subarray(e.from, e.from + e.packed) : inflateRawSync(buffer.subarray(e.from, e.from + e.packed), {maxOutputLength: Math.max(1, e.unpacked)}); } catch { fail(400, 'Office 文件解压校验失败。'); }
  if (raw.length !== e.unpacked) fail(400, 'Office 文件长度校验失败。');
  const xml = raw.toString('utf8'); if (/<!DOCTYPE|<!ENTITY/i.test(xml)) fail(400, '不支持包含特殊实体的 Office 文件。'); return xml;
 };
 const types = readEntry('[Content_Types].xml');
 if (!types.includes(extension === '.docx' ? 'wordprocessingml.document.main+xml' : 'spreadsheetml.sheet.main+xml')) fail(400, '文件内容与 Office 扩展名不符。');
 for (const name of entries.keys()) {
  if ((extension === '.docx' && /^word\/(document|header\d+|footer\d+)\.xml$/.test(name)) || (extension === '.xlsx' && /^xl\/(sharedStrings\.xml|worksheets\/sheet\d+\.xml)$/.test(name))) {
   extracted += ` ${plainXml(readEntry(name))}`;
   if (extracted.length > 100000) break;
  }
 }
 return extracted.slice(0, 100000).trim();
}
function readAttachmentBytes(req) {
 return new Promise((resolveBody, reject) => {
  let size = 0, finished = false; const chunks = [];
  const rejected = error => { if (finished) return; finished = true; chunks.length = 0; req.resume(); reject(error); };
  req.on('data', chunk => {
   if (finished) return;
   size += chunk.length;
   if (size > MAX_ATTACHMENT_BYTES) { rejected(new KnowledgeError(413, '每个文件最多20MB。')); return; }
   chunks.push(chunk);
  });
  req.on('end', () => { if (finished) return; finished = true; resolveBody(Buffer.concat(chunks)); chunks.length = 0; });
  req.on('error', () => rejected(new KnowledgeError(400, '上传中断，请重试。')));
  req.on('aborted', () => rejected(new KnowledgeError(400, '上传中断，请重试。')));
 });
}

export function inspectKnowledgeFile(buffer, name, suppliedType = '') {
 if (!Buffer.isBuffer(buffer) || !buffer.length || buffer.length > MAX_ATTACHMENT_BYTES) fail(413, '文件不能为空，且每个文件最多20MB。');
 if (typeof name !== 'string' || name.length > 180 || !name.trim() || /[\x00-\x1f\x7f/\\]/u.test(name) || name.startsWith('.')) fail(400, '文件名无效，请去掉路径或特殊字符。');
 const extension = extname(name).toLowerCase();
 const types = {'.pdf':'application/pdf','.docx':'application/vnd.openxmlformats-officedocument.wordprocessingml.document','.xlsx':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','.txt':'text/plain','.md':'text/markdown','.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp'};
 const mimeType = types[extension]; if (!mimeType) fail(415, '支持 PDF、Word(docx)、Excel(xlsx)、文本和 PNG/JPG/WebP 图片。');
 const given = suppliedType.split(';')[0].trim().toLowerCase();
 if (given && !['application/octet-stream', mimeType, ...(extension === '.md' ? ['text/plain', 'text/x-markdown'] : [])].includes(given)) fail(415, '文件类型与扩展名不符。');
 let searchableText = '';
 if (extension === '.pdf' && !(buffer.subarray(0, 5).toString('ascii') === '%PDF-' && buffer.subarray(-2048).includes(Buffer.from('%%EOF')))) fail(400, 'PDF 文件无效或不完整。');
 if (extension === '.png' && !(buffer.length >= 45 && buffer.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) && buffer.toString('ascii', 12, 16) === 'IHDR' && buffer.subarray(-12).toString('ascii', 4, 8) === 'IEND')) fail(400, 'PNG 图片无效或不完整。');
 if (['.jpg','.jpeg'].includes(extension) && !(buffer.length >= 4 && buffer[0] === 255 && buffer[1] === 216 && buffer[buffer.length - 2] === 255 && buffer[buffer.length - 1] === 217)) fail(400, 'JPG 图片无效或不完整。');
 if (extension === '.webp' && !(buffer.length >= 20 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP' && buffer.readUInt32LE(4) + 8 === buffer.length && ['VP8 ', 'VP8L', 'VP8X'].includes(buffer.toString('ascii',12,16)))) fail(400, 'WebP 图片无效或不完整。');
 if (['.docx','.xlsx'].includes(extension)) searchableText = inspectOffice(buffer, extension);
 if (['.txt','.md'].includes(extension)) {
  try { searchableText = new TextDecoder('utf-8', {fatal:true}).decode(buffer); } catch { fail(400, '文本文件请保存为 UTF-8 编码后上传。'); }
  if (searchableText.includes('\0')) fail(400, '文本文件含无效字符。');
  searchableText = searchableText.slice(0,100000);
 }
 return {name: name.trim(), mimeType, searchableText};
}

export function createKnowledgeStore(db, {directory, staticDirectory, administratorIds = new Set(), maxConcurrentUploads = MAX_CONCURRENT_UPLOADS, maxTotalBytes = MAX_TOTAL_BYTES}) {
 const admins = new Set([...administratorIds].map(id => id.toLowerCase()));
 const dir = resolve(directory), physicalDir = physicalPath(dir), physicalStatic = physicalPath(staticDirectory);
 if (physicalDir === physicalStatic || physicalDir.startsWith(physicalStatic + sep)) throw new Error('知识附件目录不能位于公开网站目录。');
 mkdirSync(dir, {recursive:true, mode:0o700});
 if ((statSync(dir).mode & 0o777) !== 0o700) throw new Error('知识附件目录须为独立私有目录，权限必须为0700。');
 if (!Number.isSafeInteger(maxConcurrentUploads) || maxConcurrentUploads < 1 || !Number.isSafeInteger(maxTotalBytes) || maxTotalBytes < MAX_ATTACHMENT_BYTES) throw new Error('知识附件容量配置无效。');
 db.exec(`
 CREATE TABLE IF NOT EXISTS employee_access(employee_id TEXT PRIMARY KEY REFERENCES employees(id), status TEXT NOT NULL CHECK(status IN ('pending','active','suspended')), version INTEGER NOT NULL DEFAULT 1, updated_at TEXT NOT NULL, updated_by TEXT);
 CREATE TABLE IF NOT EXISTS organization_units(id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('department','campus','grade','project')), name TEXT NOT NULL, UNIQUE(kind,name));
 CREATE TABLE IF NOT EXISTS organization_memberships(employee_id TEXT NOT NULL REFERENCES employees(id), unit_id TEXT NOT NULL REFERENCES organization_units(id), PRIMARY KEY(employee_id,unit_id));
 CREATE TABLE IF NOT EXISTS knowledge_documents(id TEXT PRIMARY KEY, request_id TEXT NOT NULL, owner_id TEXT NOT NULL REFERENCES employees(id), author TEXT NOT NULL, owner_units_json TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, sharing_json TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE(owner_id,request_id));
 CREATE TABLE IF NOT EXISTS knowledge_attachments(id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES employees(id), document_id TEXT REFERENCES knowledge_documents(id), name TEXT NOT NULL, mime_type TEXT NOT NULL, size INTEGER NOT NULL, file_hash TEXT NOT NULL, searchable_text TEXT NOT NULL, created_at TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS knowledge_owner ON knowledge_documents(owner_id);
 CREATE INDEX IF NOT EXISTS knowledge_attachment_document ON knowledge_attachments(document_id);
 CREATE TABLE IF NOT EXISTS knowledge_revisions(document_id TEXT NOT NULL REFERENCES knowledge_documents(id), version INTEGER NOT NULL, actor_id TEXT NOT NULL REFERENCES employees(id), payload_json TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(document_id,version));
 CREATE TABLE IF NOT EXISTS organization_audit(id TEXT PRIMARY KEY, actor_id TEXT NOT NULL, target_id TEXT NOT NULL, action TEXT NOT NULL, previous_json TEXT, next_json TEXT NOT NULL, created_at TEXT NOT NULL);
 `);
 function transaction(fn) { db.exec('BEGIN IMMEDIATE'); try { const result = fn(); db.exec('COMMIT'); return result; } catch(error) { db.exec('ROLLBACK'); throw error; } }
 transaction(() => {
  const seed = [...['AI部','新媒体运营部','年级运营','招生','教务','教学教研','一对一','HR','行政','校区管理','集团','财务'].map(name => ({kind:'department',name})), {kind:'campus',name:'集团总部'}];
  for (const unit of seed) db.prepare('INSERT OR IGNORE INTO organization_units(id,kind,name) VALUES(?,?,?)').run(randomUUID(),unit.kind,unit.name);
  db.prepare("INSERT OR IGNORE INTO employee_access(employee_id,status,version,updated_at) SELECT id,'pending',1,? FROM employees").run(now());
  for (const id of admins) {
   if (!db.prepare('SELECT id FROM employees WHERE id=?').get(id)) continue;
   const access = db.prepare('SELECT * FROM employee_access WHERE employee_id=?').get(id);
   // Administrator recovery is explicit deployment configuration, never a name/role claim.
   if (access.status !== 'active') {
    db.prepare("UPDATE employee_access SET status='active',version=version+1,updated_at=? WHERE employee_id=?").run(now(),id);
    db.prepare('INSERT INTO organization_audit(id,actor_id,target_id,action,previous_json,next_json,created_at) VALUES(?,?,?,?,?,?,?)').run(randomUUID(),'system',id,'administrator-bootstrap',JSON.stringify({status:access.status}),JSON.stringify({status:'active'}),now());
   }
  }
  db.prepare('INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(6,?)').run(now());
 });
 function isAdmin(id) { return admins.has(id.toLowerCase()); }
 function ensure(employeeId) {
  db.prepare("INSERT OR IGNORE INTO employee_access(employee_id,status,version,updated_at) VALUES(?,'pending',1,?)").run(employeeId, now());
  return db.prepare('SELECT * FROM employee_access WHERE employee_id=?').get(employeeId);
 }
 function memberships(employeeId) { return db.prepare('SELECT u.id,u.kind,u.name FROM organization_memberships m JOIN organization_units u ON u.id=m.unit_id WHERE m.employee_id=? ORDER BY u.kind,u.name,u.id').all(employeeId); }
 function access(employeeId) { const row = ensure(employeeId); return {status:row.status,version:row.version,memberships:memberships(employeeId),canManageKnowledge:isAdmin(employeeId)&&row.status==='active'}; }
 function requireAvailable(employeeId) { const result = access(employeeId); if (result.status === 'suspended') fail(403,'该工作账号已停用，请联系管理员。'); return result; }
 function requireActive(employeeId) { const result = requireAvailable(employeeId); if (result.status !== 'active') fail(403,'员工身份尚待确认，请联系管理员。'); return result; }
 function requireAdmin(employeeId) { const result = requireActive(employeeId); if (!result.canManageKnowledge) fail(403,'仅指定管理员可以确认员工身份和权限。'); return result; }
 function units() { return db.prepare('SELECT id,kind,name FROM organization_units ORDER BY kind,name,id').all(); }
 function employee(row) { return {id:row.id,name:row.name,roleIds:JSON.parse(row.role_ids),department:row.department,campus:row.campus,grade:row.grade,access:access(row.id)}; }
 function context(employeeId) {
  const current = requireAvailable(employeeId);
  const people = current.status === 'active' ? db.prepare("SELECT e.id,e.name FROM employees e JOIN employee_access a ON a.employee_id=e.id WHERE a.status='active' ORDER BY e.name,e.id").all() : db.prepare('SELECT id,name FROM employees WHERE id=?').all(employeeId);
  return {access:current,units:current.status==='active'?units():[],members:people.map(row=>({...row,memberships:memberships(row.id)}))};
 }
 function employees(employeeId) { requireAdmin(employeeId); return {employees:db.prepare('SELECT * FROM employees ORDER BY name,id').all().map(employee),units:units()}; }
 function createUnit(employeeId,input) {
  requireAdmin(employeeId); if (!KINDS.includes(input.kind)) fail(400,'组织类型无效。'); const name=text(input.name,60,'组织名称',true);
  return transaction(()=>{
   const old=db.prepare('SELECT id,kind,name FROM organization_units WHERE kind=? AND name=?').get(input.kind,name); if(old)return old;
   const unit={id:randomUUID(),kind:input.kind,name}; db.prepare('INSERT INTO organization_units(id,kind,name) VALUES(?,?,?)').run(unit.id,unit.kind,unit.name);
   db.prepare('INSERT INTO organization_audit(id,actor_id,target_id,action,next_json,created_at) VALUES(?,?,?,?,?,?)').run(randomUUID(),employeeId,unit.id,'unit-created',JSON.stringify(unit),now()); return unit;
  });
 }
 function updateEmployee(employeeId,targetId,input) {
  requireAdmin(employeeId); if (!UUID.test(targetId)||!STATUSES.includes(input.status)||!Number.isSafeInteger(input.version)||input.version<1) fail(400,'员工状态或版本无效。');
  const unitIds=ids(input.unitIds);
  return transaction(()=>{
   const target=db.prepare('SELECT * FROM employees WHERE id=?').get(targetId); if(!target)fail(404,'员工不存在。');
   const previous=access(targetId); if(previous.version!==input.version)fail(409,'员工权限已经更新，请刷新后重试。');
   if (isAdmin(targetId) && input.status !== 'active') fail(400,'指定管理员账号不能在此停用。');
   for(const id of unitIds)if(!db.prepare('SELECT id FROM organization_units WHERE id=?').get(id))fail(400,'组织范围不存在，请重新选择。');
   db.prepare('UPDATE employee_access SET status=?,version=version+1,updated_at=?,updated_by=? WHERE employee_id=?').run(input.status,now(),employeeId,targetId);
   db.prepare('DELETE FROM organization_memberships WHERE employee_id=?').run(targetId);
   for(const id of unitIds)db.prepare('INSERT INTO organization_memberships(employee_id,unit_id) VALUES(?,?)').run(targetId,id);
   const next=access(targetId); db.prepare('INSERT INTO organization_audit(id,actor_id,target_id,action,previous_json,next_json,created_at) VALUES(?,?,?,?,?,?,?)').run(randomUUID(),employeeId,targetId,'employee-access',JSON.stringify(previous),JSON.stringify(next),now());
   return employee(target);
  });
 }
 function validateSharing(employeeId,value) {
  const actor=requireAvailable(employeeId);
  if(!value || typeof value!=='object'||!['private','employees','selected'].includes(value.mode)||!['any','all'].includes(value.match))fail(400,'请选择有效的共享范围。');
  const result={mode:value.mode,userIds:ids(value.userIds),unitIds:ids(value.unitIds),match:value.match};
  if(actor.status!=='active'&&result.mode!=='private')fail(403,'身份确认后才能共享资料，当前可保存为仅自己。');
  if(result.mode!=='selected') { if(result.userIds.length||result.unitIds.length)fail(400,'共享范围与选择的对象不一致。'); return result; }
  if(!result.userIds.length&&!result.unitIds.length)fail(400,'请至少选择一个共享对象。');
  for(const id of result.userIds)if(!db.prepare("SELECT employee_id FROM employee_access WHERE employee_id=? AND status='active'").get(id))fail(400,'共享人员不可用，请重新选择。');
  for(const id of result.unitIds)if(!db.prepare('SELECT id FROM organization_units WHERE id=?').get(id))fail(400,'共享组织不存在，请重新选择。');
  return result;
 }
 function canRead(employeeId,row,current=access(employeeId)) {
  if(current.status==='suspended')return false;
  if(row.owner_id===employeeId)return true;
  if(current.status!=='active')return false;
  const scope=JSON.parse(row.sharing_json);
  if(scope.mode==='employees')return true;
  if(scope.mode!=='selected')return false;
  if(scope.userIds.includes(employeeId))return true;
  const mine=new Set(current.memberships.map(unit=>unit.id));
  return scope.unitIds.length>0 && (scope.match==='all'?scope.unitIds.every(id=>mine.has(id)):scope.unitIds.some(id=>mine.has(id)));
 }
 function attachmentPublic(row) { return {id:row.id,name:row.name,size:row.size,mimeType:row.mime_type}; }
 function documentPublic(row,employeeId) { return {id:row.id,title:row.title,body:row.body,ownerId:row.owner_id,author:row.author,ownerUnits:JSON.parse(row.owner_units_json),sharing:JSON.parse(row.sharing_json),attachments:db.prepare('SELECT * FROM knowledge_attachments WHERE document_id=? ORDER BY created_at,id').all(row.id).map(attachmentPublic),version:row.version,createdAt:row.created_at,updatedAt:row.updated_at,canEdit:row.owner_id===employeeId}; }
 function getDocument(employeeId,id) { requireAvailable(employeeId); const row=UUID.test(id)?db.prepare('SELECT * FROM knowledge_documents WHERE id=?').get(id):null; if(!row||!canRead(employeeId,row))fail(404,'资料不存在或你没有查看权限。'); return documentPublic(row,employeeId); }
 function listDocuments(employeeId,scope='all') { const current=requireAvailable(employeeId); if(!['all','mine'].includes(scope))fail(400,'资料范围无效。'); return db.prepare('SELECT * FROM knowledge_documents ORDER BY updated_at DESC,id DESC').all().filter(row=>(scope!=='mine'||row.owner_id===employeeId)&&canRead(employeeId,row,current)).map(row=>documentPublic(row,employeeId)); }
 function validateInput(employeeId,input) {
  const title=text(input.title,120,'标题',true),body=text(input.body,12000,'正文');
  const attachmentIds=ids(input.attachmentIds,5),sharing=validateSharing(employeeId,input.sharing);
  if(!body&&!attachmentIds.length)fail(400,'请填写内容或上传文件。'); return {title,body,sharing,attachmentIds};
 }
 function verifyAttachments(employeeId,attachmentIds,documentId=null) {
  for(const id of attachmentIds) {
   const row=db.prepare('SELECT * FROM knowledge_attachments WHERE id=?').get(id);
   if(!row||row.owner_id!==employeeId||(row.document_id&&row.document_id!==documentId)||!existsSync(resolve(dir,id)))fail(400,'附件不可用，请重新上传。');
  }
 }
 function saveDocument(employeeId,input,id=null) {
  const accessBefore=requireAvailable(employeeId), content=validateInput(employeeId,input);
  if(id ? (!UUID.test(id)||!Number.isSafeInteger(input.version)||input.version<1) : (typeof input.requestId!=='string'||!UUID.test(input.requestId)))fail(400,'资料标识或版本无效，请刷新重试。');
  return transaction(()=>{
   const currentAccess=requireAvailable(employeeId); if(currentAccess.version!==accessBefore.version)fail(409,'权限已变化，请刷新重试。');
   const existing=id?db.prepare('SELECT * FROM knowledge_documents WHERE id=?').get(id):db.prepare('SELECT * FROM knowledge_documents WHERE owner_id=? AND request_id=?').get(employeeId,input.requestId);
   if(id&&(!existing||existing.owner_id!==employeeId))fail(404,'资料不存在或你没有修改权限。');
   if(existing&&!id) {
    const attachments=db.prepare('SELECT id FROM knowledge_attachments WHERE document_id=? ORDER BY id').all(existing.id).map(a=>a.id);
    if(existing.title!==content.title||existing.body!==content.body||existing.sharing_json!==JSON.stringify(content.sharing)||JSON.stringify(attachments)!==JSON.stringify(content.attachmentIds))fail(409,'该提交已保存，内容与本次不同，请刷新查看。');
    return {document:documentPublic(existing,employeeId),created:false};
   }
   if(existing&&existing.version!==input.version)fail(409,'资料已被更新，请刷新后重试。');
   verifyAttachments(employeeId,content.attachmentIds,id);
   const timestamp=now(),documentId=id||randomUUID(),version=existing?existing.version+1:1;
   if(existing)db.prepare('UPDATE knowledge_documents SET title=?,body=?,sharing_json=?,version=?,updated_at=? WHERE id=?').run(content.title,content.body,JSON.stringify(content.sharing),version,timestamp,id);
   else {
    const author=db.prepare('SELECT name FROM employees WHERE id=?').get(employeeId).name;
    db.prepare('INSERT INTO knowledge_documents(id,request_id,owner_id,author,owner_units_json,title,body,sharing_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)').run(documentId,input.requestId,employeeId,author,JSON.stringify(currentAccess.memberships),content.title,content.body,JSON.stringify(content.sharing),timestamp,timestamp);
   }
   const kept=new Set(content.attachmentIds);
   for(const old of db.prepare('SELECT id FROM knowledge_attachments WHERE document_id=?').all(documentId))if(!kept.has(old.id))db.prepare('UPDATE knowledge_attachments SET document_id=NULL,created_at=? WHERE id=?').run(timestamp,old.id);
   for(const attachmentId of content.attachmentIds)db.prepare('UPDATE knowledge_attachments SET document_id=? WHERE id=?').run(documentId,attachmentId);
   db.prepare('INSERT INTO knowledge_revisions(document_id,version,actor_id,payload_json,created_at) VALUES(?,?,?,?,?)').run(documentId,version,employeeId,JSON.stringify(content),timestamp);
   return {document:documentPublic(db.prepare('SELECT * FROM knowledge_documents WHERE id=?').get(documentId),employeeId),created:!existing};
  });
 }
 const uploadReservations=new Map();
 let concurrentUploads=0;
 function collect() {
  const cutoff=new Date(Date.now()-PENDING_LIFETIME).toISOString();
  for(const row of db.prepare('SELECT id FROM knowledge_attachments WHERE document_id IS NULL AND created_at<?').all(cutoff)) {
   try { unlinkSync(resolve(dir,row.id)); } catch(error) { if(error.code!=='ENOENT')continue; }
   db.prepare('DELETE FROM knowledge_attachments WHERE id=? AND document_id IS NULL').run(row.id);
  }
  // Remove abandoned temporary files and crash-orphaned UUID writes only after
  // the grace period. Live/persisted attachments always remain database-owned.
  for(const name of readdirSync(dir))if(name.endsWith('.part')||(UUID.test(name)&&!db.prepare('SELECT id FROM knowledge_attachments WHERE id=?').get(name))) { const path=resolve(dir,name); try { if(statSync(path).mtimeMs<Date.now()-PENDING_LIFETIME)unlinkSync(path); }catch{} }
 }
 async function upload(employeeId,req,res,name) {
  const initial=requireAvailable(employeeId); collect();
  if(concurrentUploads >= maxConcurrentUploads){req.resume();fail(429,'当前上传较多，请稍后重试。');}
  const totalBytes=db.prepare('SELECT coalesce(sum(size),0) AS bytes FROM knowledge_attachments').get().bytes;
  if(totalBytes+(concurrentUploads+1)*MAX_ATTACHMENT_BYTES>maxTotalBytes){req.resume();fail(413,'知识库附件空间已满，请联系管理员。');}
  const declared=Number(req.headers['content-length']); if(Number.isFinite(declared)&&declared>MAX_ATTACHMENT_BYTES){req.resume();fail(413,'每个文件最多20MB。');}
  const occupied=db.prepare('SELECT count(*) AS files,coalesce(sum(size),0) AS bytes FROM knowledge_attachments WHERE owner_id=?').get(employeeId),reservations=uploadReservations.get(employeeId)||0;
  if(occupied.files+reservations>=MAX_OWNER_FILES||occupied.bytes+(reservations+1)*MAX_ATTACHMENT_BYTES>MAX_OWNER_BYTES){req.resume();fail(413,'个人附件空间已满，请联系管理员。');}
  uploadReservations.set(employeeId,reservations+1);
  concurrentUploads+=1;
  let temporary=null, id=null, persisted=false;
  try {
   const buffer=await readAttachmentBytes(req);
   if(req.aborted||req.complete===false)fail(400,'上传中断，请重试。');
   const inspection=inspectKnowledgeFile(buffer,name,req.headers['content-type']||'');
   const current=requireAvailable(employeeId); if(current.version!==initial.version)fail(409,'权限已变化，请刷新后重新上传。');
   id=randomUUID();temporary=resolve(dir,`${id}.part`);await writeFile(temporary,buffer,{flag:'wx',mode:0o600});
   if(req.aborted||res.destroyed)fail(400,'上传已取消。');
   await rename(temporary,resolve(dir,id));temporary=null;
   transaction(()=>{
    const refreshed=requireAvailable(employeeId); if(refreshed.version!==initial.version)fail(409,'权限已变化，请刷新后重新上传。');
    db.prepare('INSERT INTO knowledge_attachments(id,owner_id,name,mime_type,size,file_hash,searchable_text,created_at) VALUES(?,?,?,?,?,?,?,?)').run(id,employeeId,inspection.name,inspection.mimeType,buffer.length,digest(buffer),inspection.searchableText,now());
   }); persisted=true;
   const metadata={id,name:inspection.name,mimeType:inspection.mimeType,size:buffer.length};
   // An upload whose response never completes is not a saved attachment.
   let completed=false;res.once('finish',()=>{completed=true;});res.once('close',()=>{if(!completed){const row=db.prepare('SELECT document_id FROM knowledge_attachments WHERE id=?').get(id);if(row&&!row.document_id){db.prepare('DELETE FROM knowledge_attachments WHERE id=?').run(id);try{unlinkSync(resolve(dir,id));}catch{}}}});
   return metadata;
  }catch(error){if(temporary)await rm(temporary,{force:true});if(id&&!persisted)await rm(resolve(dir,id),{force:true});throw error;}
  finally{concurrentUploads-=1;const remaining=(uploadReservations.get(employeeId)||1)-1;if(remaining)uploadReservations.set(employeeId,remaining);else uploadReservations.delete(employeeId);}
 }
 function serveAttachment(employeeId,id,req,res) {
  requireAvailable(employeeId); const row=UUID.test(id)?db.prepare('SELECT * FROM knowledge_attachments WHERE id=?').get(id):null;
  if(!row)fail(404,'附件不存在或你没有查看权限。');
  if(row.document_id) {const document=db.prepare('SELECT * FROM knowledge_documents WHERE id=?').get(row.document_id);if(!document||!canRead(employeeId,document))fail(404,'附件不存在或你没有查看权限。');}
  else if(row.owner_id!==employeeId)fail(404,'附件不存在或你没有查看权限。');
  const path=resolve(dir,id); if(!existsSync(path)||!statSync(path).isFile())fail(404,'附件不存在或你没有查看权限。');
  res.writeHead(200,{'Content-Type':'application/octet-stream','Content-Length':row.size,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Content-Disposition':`attachment; filename="download${extname(row.name).toLowerCase()}"; filename*=UTF-8''${encodeURIComponent(row.name).replace(/['()*]/g,c=>`%${c.charCodeAt(0).toString(16).toUpperCase()}`)}`});
  if(req.method==='HEAD'){res.end();return;}const stream=createReadStream(path);stream.on('error',()=>res.destroy());stream.pipe(res);
 }
 function search(employeeId,query,semantic=true) {
  const current=requireAvailable(employeeId),term=normalized(query),tokens=[...new Set(term.split(/[\s,，、;；]+/u).filter(Boolean))];if(!tokens.length)return[];
  const groups=prepareSemanticQuery(query,semantic);let semanticDocuments=0;
  const results=db.prepare('SELECT * FROM knowledge_documents ORDER BY updated_at DESC,id DESC').all().filter(row=>canRead(employeeId,row,current)).flatMap(row=>{
   const attachments=db.prepare('SELECT name,searchable_text FROM knowledge_attachments WHERE document_id=? ORDER BY id').all(row.id);
   const full=normalized([row.title,row.body,...attachments.flatMap(item=>[item.name,item.searchable_text])].join('\n'));
   if(!tokens.every(token=>full.includes(token))){
    if(!groups||semanticDocuments++>=SEMANTIC_DOCUMENT_LIMIT)return[];
    // Permission was checked above, on every request. No user-result cache.
    const match=semanticMatch(prepareSemanticEntry(row.title,[],[row.body,...attachments.flatMap(item=>[item.name,item.searchable_text])].filter(Boolean).map(text=>({text}))),groups);
    return match?[{key:`document:${row.id}`,type:'document',id:row.id,title:row.title,subtitle:row.author,excerpt:match.excerpt,matchKind:'semantic',semanticScore:match.score}]:[];
   }
   const pieces=[row.body,...attachments.flatMap(item=>[item.name,item.searchable_text])].filter(Boolean),matched=pieces.sort((a,b)=>tokens.filter(token=>normalized(b).includes(token)).length-tokens.filter(token=>normalized(a).includes(token)).length)[0]||row.title;
   const haystack=normalized(matched),positions=tokens.map(token=>haystack.indexOf(token)).filter(position=>position>=0),start=Math.max(0,(positions.length?Math.min(...positions):0)-30);
   const excerpt=matched.slice(start,start+145);return[{key:`document:${row.id}`,type:'document',id:row.id,title:row.title,subtitle:row.author,excerpt:`${start?'…':''}${excerpt}${start+145<matched.length?'…':''}`}];
  }).sort((a,b)=>Number(normalized(b.title)===term)-Number(normalized(a.title)===term));
  return results;
 }
 return {access,requireAvailable,requireActive,requireAdmin,isAdmin,context,employees,createUnit,updateEmployee,getDocument,listDocuments,saveDocument,upload,serveAttachment,search,collect};
}
