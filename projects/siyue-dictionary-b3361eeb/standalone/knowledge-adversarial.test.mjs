import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { deflateRawSync } from 'node:zlib';
import { mkdtemp, mkdir, symlink, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createKnowledgeStore, inspectKnowledgeFile } from './knowledge.mjs';

const privateSharing = { mode: 'private', userIds: [], unitIds: [], match: 'any' };
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'siyue-adversarial-'));
  const db = new DatabaseSync(':memory:');
  db.exec(`PRAGMA foreign_keys=ON;
    CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
    CREATE TABLE employees(id TEXT PRIMARY KEY, name TEXT, role_ids TEXT, department TEXT, campus TEXT, grade TEXT);`);
  const admin = randomUUID(), owner = randomUUID();
  for (const id of [admin, owner]) db.prepare('INSERT INTO employees VALUES(?,?,?,?,?,?)').run(id, id === admin ? '管理员' : '作者', '["dept-head"]', '集团', '集团总部', '');
  const directory = join(root, 'private'), staticDirectory = join(root, 'public');
  await mkdir(staticDirectory);
  const store = createKnowledgeStore(db, { directory, staticDirectory, administratorIds: new Set([admin]) });
  store.updateEmployee(admin, owner, { status: 'active', unitIds: [], version: store.access(owner).version });
  return { root, directory, staticDirectory, db, store, admin, owner, async close() { db.close(); await rm(root, { recursive: true, force: true }); } };
}
function uploadRequest(body) {
  const req = Readable.from([body]); req.headers = { 'content-type': 'text/plain' }; req.complete = true;
  const res = new EventEmitter(); res.destroyed = false;
  return { req, res };
}

test('upload cancellation removes only uncommitted attachments and preserves bound or finished writes', async () => {
  const f = await fixture();
  try {
    const cancelled = uploadRequest(Buffer.from('尚未保存的文件'));
    const first = await f.store.upload(f.owner, cancelled.req, cancelled.res, '个人记录.txt');
    cancelled.res.emit('close');
    assert.equal(f.db.prepare('SELECT id FROM knowledge_attachments WHERE id=?').get(first.id), undefined);
    assert.deepEqual(await readdir(f.directory), []);

    const attached = uploadRequest(Buffer.from('已经归档的文件'));
    const second = await f.store.upload(f.owner, attached.req, attached.res, '已保存.txt');
    const saved = f.store.saveDocument(f.owner, { requestId: randomUUID(), title: '已保存工作资料', body: '', sharing: privateSharing, attachmentIds: [second.id] });
    attached.res.emit('close');
    assert.equal(f.db.prepare('SELECT document_id FROM knowledge_attachments WHERE id=?').get(second.id).document_id, saved.document.id);

    const finished = uploadRequest(Buffer.from('上传已完成'));
    const third = await f.store.upload(f.owner, finished.req, finished.res, '已完成.txt');
    finished.res.emit('finish'); finished.res.emit('close');
    assert.ok(f.db.prepare('SELECT id FROM knowledge_attachments WHERE id=?').get(third.id));
  } finally { await f.close(); }
});

test('approval changes while bytes are arriving invalidate the upload before persistence', async () => {
  const f = await fixture();
  try {
    const req = Readable.from((async function* () {
      yield Buffer.from('前半段');
      f.store.updateEmployee(f.admin, f.owner, { status: 'pending', unitIds: [], version: f.store.access(f.owner).version });
      yield Buffer.from('后半段');
    })());
    req.headers = { 'content-type': 'text/plain' }; req.complete = true;
    const res = new EventEmitter(); res.destroyed = false;
    await assert.rejects(f.store.upload(f.owner, req, res, '变更中的资料.txt'), error => error.status === 409);
    assert.equal(f.db.prepare('SELECT count(*) AS n FROM knowledge_attachments').get().n, 0);
    assert.deepEqual(await readdir(f.directory), []);
  } finally { await f.close(); }
});

test('private storage location rejects both direct paths and symlinks into the public web root', async () => {
  const f = await fixture();
  try {
    assert.throws(() => createKnowledgeStore(f.db, { directory: join(f.staticDirectory, 'uploads'), staticDirectory: f.staticDirectory }), /不能位于公开/);
    const inside = join(f.staticDirectory, 'inside'); await mkdir(inside);
    const alias = join(f.root, 'private-looking-alias'); await symlink(inside, alias);
    assert.throws(() => createKnowledgeStore(f.db, { directory: alias, staticDirectory: f.staticDirectory }), /不能位于公开/);
    assert.deepEqual(await readdir(inside), []);
  } finally { await f.close(); }
});

function officeZip(entries) {
  const local = [], central = []; let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name), data = Buffer.from(entry.text), compressed = deflateRawSync(data), size = entry.declaredSize ?? data.length;
    const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4); header.writeUInt16LE(8, 8); header.writeUInt32LE(compressed.length, 18); header.writeUInt32LE(size, 22); header.writeUInt16LE(name.length, 26);
    const row = Buffer.alloc(46); row.writeUInt32LE(0x02014b50); row.writeUInt16LE(20, 4); row.writeUInt16LE(20, 6); row.writeUInt16LE(8, 10); row.writeUInt32LE(compressed.length, 20); row.writeUInt32LE(size, 24); row.writeUInt16LE(name.length, 28); row.writeUInt32LE(offset, 42);
    local.push(header, name, compressed); central.push(row, name); offset += header.length + name.length + compressed.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}
test('Office parser bounds real inflation even when the ZIP directory understates expanded size', () => {
  const entry = text => [
    { name: '[Content_Types].xml', text: '<Types>application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml</Types>' },
    { name: '_rels/.rels', text: '<Relationships/>' },
    { name: 'word/document.xml', text },
  ];
  const valid = entry('<w:document><w:t>压缩工作资料</w:t></w:document>');
  assert.equal(inspectKnowledgeFile(officeZip(valid), '压缩资料.docx').searchableText, '压缩工作资料');
  const inflated = entry(`<w:document><w:t>${'资料'.repeat(200000)}</w:t></w:document>`);
  inflated[2].declaredSize = 16;
  assert.throws(() => inspectKnowledgeFile(officeZip(inflated), '虚报大小.docx'), /解压校验失败|长度校验失败/);
});
