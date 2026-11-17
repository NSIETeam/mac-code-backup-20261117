import {randomUUID} from 'node:crypto';
import {mkdirSync, statSync, lstatSync, readdirSync, unlinkSync, createReadStream} from 'node:fs';
import {open, readFile, rename, rm} from 'node:fs/promises';
import {resolve} from 'node:path';

export class AudioError extends Error {
 constructor(status, message) { super(message); this.status = status; }
}
export const MAX_AUDIO_BYTES = 8 * 1024 * 1024;
const MAX_SECONDS = 120;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MIME = new Set(['audio/webm', 'audio/mp4', 'audio/ogg', 'audio/wav']);
const invalid = () => { throw new AudioError(400, '录音格式无效或文件不完整，请重新录制。'); };
const seconds = value => {
 if (!Number.isFinite(value) || value < 0) invalid();
 if (value > MAX_SECONDS + 0.25) throw new AudioError(400, '每段录音最多120秒，请分段录制。');
 return value > 0 ? Math.round(value * 1000) / 1000 : null;
};
function wavDuration(buffer) {
 if (buffer.length < 44 || buffer.toString('ascii', 0, 4) !== 'RIFF' || buffer.toString('ascii', 8, 12) !== 'WAVE') invalid();
 if (buffer.readUInt32LE(4) + 8 !== buffer.length) invalid();
 let byteRate = 0, dataSize = 0;
 for (let offset = 12; offset + 8 <= buffer.length;) {
  const size = buffer.readUInt32LE(offset + 4), end = offset + 8 + size;
  if (end > buffer.length) invalid();
  const kind = buffer.toString('ascii', offset, offset + 4);
  if (kind === 'fmt ') {
   if (size < 16) invalid();
   const format = buffer.readUInt16LE(offset + 8), channels = buffer.readUInt16LE(offset + 10);
   const rate = buffer.readUInt32LE(offset + 12), bits = buffer.readUInt16LE(offset + 22);
   byteRate = buffer.readUInt32LE(offset + 16);
   if (![1, 3].includes(format) || channels < 1 || channels > 8 || rate < 8000 || rate > 192000 || ![8, 16, 24, 32, 64].includes(bits) || byteRate !== rate * channels * bits / 8) invalid();
  }
  if (kind === 'data') dataSize += size;
  offset = end + (size % 2);
 }
 if (!byteRate || !dataSize) invalid();
 return seconds(dataSize / byteRate);
}
function oggDuration(buffer) {
 let offset = 0, granule = 0, preSkip = 0, opus = false, pages = 0;
 while (offset < buffer.length) {
  if (offset + 27 > buffer.length || buffer.toString('ascii', offset, offset + 4) !== 'OggS' || buffer[offset + 4] !== 0) invalid();
  const segments = buffer[offset + 26], payloadStart = offset + 27 + segments;
  if (payloadStart > buffer.length) invalid();
  let size = 0;
  for (let i = offset + 27; i < payloadStart; i++) size += buffer[i];
  if (payloadStart + size > buffer.length) invalid();
  if (!pages && size >= 19 && buffer.toString('ascii', payloadStart, payloadStart + 8) === 'OpusHead') {
   opus = true; preSkip = buffer.readUInt16LE(payloadStart + 10);
  }
  const current = buffer.readBigUInt64LE(offset + 6);
  if (current !== 0xffffffffffffffffn) granule = Math.max(granule, Number(current));
  offset = payloadStart + size; pages++;
 }
 if (!opus || pages < 2 || granule <= preSkip) invalid();
 return seconds((granule - preSkip) / 48000);
}
function ebmlValue(buffer, offset, isId = false) {
 if (offset >= buffer.length || !buffer[offset]) invalid();
 let length = 1, mask = 0x80;
 while (!(buffer[offset] & mask)) { length++; mask >>= 1; }
 if (length > (isId ? 4 : 8) || offset + length > buffer.length) invalid();
 let value = BigInt(isId ? buffer[offset] : buffer[offset] & (mask - 1));
 for (let i = 1; i < length; i++) value = value * 256n + BigInt(buffer[offset + i]);
 const unknown = !isId && value === (1n << BigInt(7 * length)) - 1n;
 return {length, value: unknown ? -1 : Number(value)};
}
function webmDuration(buffer) {
 if (buffer.length < 12 || buffer.readUInt32BE(0) !== 0x1a45dfa3) invalid();
 let scale = 1000000, duration = 0, maxBlockTime = 0, blockCount = 0, audioTracks = 0, codec = false, docType = false, elements = 0;
 const containers = new Set([0x1a45dfa3, 0x18538067, 0x1549a966, 0x1654ae6b, 0xae, 0xe1, 0x1f43b675, 0xa0]);
 const uint = (start, end) => { if (end - start > 8) invalid(); let n = 0; for (let i = start; i < end; i++) n = n * 256 + buffer[i]; return n; };
 function visit(start, end, depth = 0, clusterTime = 0) {
  if (depth > 10) invalid();
  for (let offset = start; offset < end;) {
   if (++elements > 100000) invalid();
   const id = ebmlValue(buffer, offset, true), size = ebmlValue(buffer, offset + id.length);
   const from = offset + id.length + size.length, to = size.value < 0 ? end : from + size.value;
   if (!Number.isSafeInteger(to) || to > end || to < from) invalid();
   if (id.value === 0x4282) docType = buffer.toString('ascii', from, to) === 'webm';
   if (id.value === 0x2ad7b1) scale = uint(from, to);
   if (id.value === 0x4489) duration = to - from === 4 ? buffer.readFloatBE(from) : to - from === 8 ? buffer.readDoubleBE(from) : invalid();
   if (id.value === 0x83) { if (uint(from, to) !== 2) invalid(); audioTracks++; }
   if (id.value === 0x86) codec ||= ['A_OPUS', 'A_VORBIS'].includes(buffer.toString('ascii', from, to));
   if (id.value === 0xe7) clusterTime = uint(from, to);
   if (id.value === 0xa3 || id.value === 0xa1) {
    const track = ebmlValue(buffer, from);
    if (from + track.length + 3 >= to) invalid();
    maxBlockTime = Math.max(maxBlockTime, clusterTime + buffer.readInt16BE(from + track.length)); blockCount++;
   }
   if (containers.has(id.value)) visit(from, to, depth + 1, id.value === 0x1f43b675 ? 0 : clusterTime);
   offset = to;
  }
 }
 visit(0, buffer.length);
 if (!docType || !audioTracks || !codec || !blockCount || !scale) invalid();
 // Streaming WebM commonly omits Duration; block timestamps still bound length.
 return seconds(Math.max(duration * scale / 1e9, maxBlockTime * scale / 1e9 + 0.02));
}
function mp4Duration(buffer) {
 let ftyp = false, mdat = false, audioTracks = 0, scale = 0, longest = 0, declaredDuration = 0, unknownTiming = false, fragmentBase = 0, fragmentDuration = 0, defaultDuration = 0, boxes = 0;
 const trackDefaults = new Map();
 const containers = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'moof', 'traf', 'mvex']);
 function visit(start, end, depth = 0) {
  if (depth > 10) invalid();
  for (let offset = start; offset < end;) {
   if (++boxes > 100000 || offset + 8 > end) invalid();
   let size = buffer.readUInt32BE(offset), header = 8;
   if (size === 1) { if (offset + 16 > end) invalid(); size = Number(buffer.readBigUInt64BE(offset + 8)); header = 16; }
   if (size === 0) size = end - offset;
   const from = offset + header, to = offset + size, kind = buffer.toString('ascii', offset + 4, offset + 8);
   if (!Number.isSafeInteger(size) || size < header || to > end) invalid();
   if (kind === 'ftyp') ftyp = size >= 16;
   if (kind === 'mdat') mdat ||= to > from;
   if (kind === 'hdlr') { if (to - from < 12 || buffer.toString('ascii', from + 8, from + 12) !== 'soun') invalid(); audioTracks++; }
   if (kind === 'mdhd') {
    const version = buffer[from], base = from + (version === 1 ? 20 : 12);
    if (base + (version === 1 ? 12 : 8) > to) invalid();
    scale = buffer.readUInt32BE(base);
    const n = version === 1 ? Number(buffer.readBigUInt64BE(base + 4)) : buffer.readUInt32BE(base + 4);
    if (scale && n !== 0xffffffff) { declaredDuration = Math.max(declaredDuration, n / scale); longest = Math.max(longest, n / scale); }
   }
   if (kind === 'trex') {
    if (to - from < 24) invalid();
    trackDefaults.set(buffer.readUInt32BE(from + 4), buffer.readUInt32BE(from + 12));
   }
   if (kind === 'stts') {
    if (to - from < 8) invalid();
    const count = buffer.readUInt32BE(from + 4);
    if (count > 100000 || from + 8 + count * 8 > to) invalid();
    let total = 0;
    for (let i = 0; i < count; i++) total += buffer.readUInt32BE(from + 8 + i * 8) * buffer.readUInt32BE(from + 12 + i * 8);
    if (scale) { declaredDuration = Math.max(declaredDuration, total / scale); longest = Math.max(longest, total / scale); }
   }
   if (kind === 'traf') { fragmentBase = 0; fragmentDuration = 0; defaultDuration = 0; }
   if (kind === 'tfhd') {
    if (to - from < 8) invalid();
    const flags = buffer.readUInt32BE(from) & 0xffffff;
    defaultDuration = trackDefaults.get(buffer.readUInt32BE(from + 4)) || 0;
    const p = from + 8 + ((flags & 1) ? 8 : 0) + ((flags & 2) ? 4 : 0);
    if (flags & 8) { if (p + 4 > to) invalid(); defaultDuration = buffer.readUInt32BE(p); }
   }
   if (kind === 'tfdt') {
    if (to - from < (buffer[from] === 1 ? 12 : 8)) invalid();
    fragmentBase = buffer[from] === 1 ? Number(buffer.readBigUInt64BE(from + 4)) : buffer.readUInt32BE(from + 4);
   }
   if (kind === 'trun') {
    if (to - from < 8) invalid();
    const flags = buffer.readUInt32BE(from) & 0xffffff, count = buffer.readUInt32BE(from + 4);
    if (count > 100000) invalid();
    let p = from + 8 + ((flags & 1) ? 4 : 0) + ((flags & 4) ? 4 : 0);
    for (let i = 0; i < count; i++) {
     if (flags & 0x100) { if (p + 4 > to) invalid(); fragmentDuration += buffer.readUInt32BE(p); p += 4; }
     else { fragmentDuration += defaultDuration; if (!defaultDuration) unknownTiming = true; }
     for (const flag of [0x200, 0x400, 0x800]) if (flags & flag) p += 4;
     if (p > to) invalid();
    }
    if (scale) longest = Math.max(longest, (fragmentBase + fragmentDuration) / scale);
   }
   if (containers.has(kind)) visit(from, to, depth + 1);
   offset = to;
  }
 }
 visit(0, buffer.length);
 if (!ftyp || !mdat || audioTracks !== 1 || !scale || !longest || (unknownTiming && !declaredDuration)) invalid();
 return seconds(longest);
}
export function inspectAudio(buffer, declaredMime) {
 const mimeType = declaredMime.split(';', 1)[0].trim().toLowerCase().replace('audio/x-wav', 'audio/wav');
 if (!MIME.has(mimeType)) throw new AudioError(415, '请上传浏览器录制的 WebM、MP4、Ogg 或 WAV 音频。');
 if (!buffer.length || buffer.length > MAX_AUDIO_BYTES) throw new AudioError(413, '录音文件须大于0且不超过8MiB。');
 const duration = {'audio/wav': wavDuration, 'audio/ogg': oggDuration, 'audio/webm': webmDuration, 'audio/mp4': mp4Duration}[mimeType](buffer);
 return {mimeType, size: buffer.length, ...(duration === null ? {} : {duration})};
}

export function createRecordingStore(db, directory) {
 const root = resolve(directory);
 mkdirSync(root, {recursive: true, mode: 0o700});
 if ((statSync(root).mode & 0o777) !== 0o700) throw new Error('录音目录须为权限0700的私有目录。');
 db.exec(`CREATE TABLE IF NOT EXISTS recordings(id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES employees(id), mime_type TEXT NOT NULL,
  size INTEGER NOT NULL, duration REAL, created_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'ready', need_id TEXT REFERENCES needs(id));
  CREATE INDEX IF NOT EXISTS recordings_pending ON recordings(need_id,state,created_at);`);
 const active = new Set();
 const path = id => { if (!UUID.test(id)) throw new AudioError(404, '录音不存在。'); return resolve(root, id); };
 const metadata = row => ({id: row.id, mimeType: row.mime_type, size: row.size, ...(row.duration === null ? {} : {duration: row.duration})});
 function reap() {
  for (const row of db.prepare("SELECT id FROM recordings WHERE state='deleting'").all()) {
   try { unlinkSync(path(row.id)); } catch (error) { if (error.code !== 'ENOENT') continue; }
   db.prepare("DELETE FROM recordings WHERE id=? AND state='deleting'").run(row.id);
  }
 }
 function collect() {
  db.prepare("UPDATE recordings SET state='deleting' WHERE need_id IS NULL AND state='ready' AND created_at<?").run(Date.now() - 24 * 60 * 60 * 1000);
  reap();
  for (const name of readdirSync(root)) {
   const id = name.endsWith('.partial') ? name.slice(0, -8) : name;
   if (!UUID.test(id)) continue;
   const file = resolve(root, name);
   try {
    if (statSync(file).mtimeMs < Date.now() - 5 * 60 * 1000 && !db.prepare('SELECT id FROM recordings WHERE id=?').get(id)) unlinkSync(file);
   } catch {}
  }
 }
 async function upload(req, res, ownerId) {
  collect();
  const mime = (req.headers['content-type'] || '').split(';', 1)[0].trim().toLowerCase().replace('audio/x-wav', 'audio/wav');
  if (!MIME.has(mime)) throw new AudioError(415, '请上传浏览器录制的 WebM、MP4、Ogg 或 WAV 音频。');
  if (Number(req.headers['content-length'] || 0) > MAX_AUDIO_BYTES) throw new AudioError(413, '录音文件不能超过8MiB。');
  if (active.size >= 2 || active.has(ownerId)) throw new AudioError(429, '录音正在上传，请稍后再试。');
  if (db.prepare("SELECT COUNT(*) AS n FROM recordings WHERE owner_id=? AND need_id IS NULL AND state='ready'").get(ownerId).n >= 10) throw new AudioError(429, '待提交录音过多，请先提交或删除已有录音。');
  active.add(ownerId);
  const id = randomUUID(), destination = path(id), partial = `${destination}.partial`;
  let file, size = 0, completed = false, cancelled = false;
  const abort = () => { cancelled = true; };
  const finish = () => { res.off('close', close); };
  const close = () => {
   res.off('finish', finish);
   if (!res.writableFinished) {
    abort();
    if (completed) {
     // The response may close after the body was saved. Only reclaim a still
     // pending upload; a concurrent successful need submission must keep it.
     try { db.prepare("UPDATE recordings SET state='deleting' WHERE id=? AND need_id IS NULL AND state='ready'").run(id); reap(); } catch {}
    }
   }
  };
  req.once('aborted', abort); res.once('close', close); res.once('finish', finish);
  const timer = setTimeout(() => { abort(); req.destroy(); }, 30_000);
  try {
   file = await open(partial, 'wx', 0o600);
   for await (const chunk of req.iterator({destroyOnReturn: false})) {
    if (cancelled) throw new AudioError(400, '录音上传已取消。');
    size += chunk.length;
    if (size > MAX_AUDIO_BYTES) throw new AudioError(413, '录音文件不能超过8MiB。');
    await file.writeFile(chunk);
   }
   await file.close(); file = null;
   if (cancelled || !req.complete) throw new AudioError(400, '录音上传已取消。');
   const info = inspectAudio(await readFile(partial), mime);
   if (cancelled) throw new AudioError(400, '录音上传已取消。');
   await rename(partial, destination);
   if (cancelled) throw new AudioError(400, '录音上传已取消。');
   db.prepare("INSERT INTO recordings(id,owner_id,mime_type,size,duration,created_at,state,need_id) VALUES(?,?,?,?,?,?,'ready',NULL)").run(id, ownerId, info.mimeType, info.size, info.duration ?? null, Date.now());
   completed = true;
   return {id, ...info};
  } finally {
   clearTimeout(timer); req.off('aborted', abort); active.delete(ownerId);
   if (!completed) { res.off('close', close); res.off('finish', finish); }
   if (file) await file.close().catch(() => {});
   if (!completed) {
    await Promise.all([rm(partial, {force: true}), rm(destination, {force: true})]);
    if (!req.destroyed && !req.complete) req.resume();
   }
  }
 }
 function verify(ids, ownerId, needId = null) {
  for (const id of ids) {
   const row = db.prepare('SELECT * FROM recordings WHERE id=?').get(id);
   if (!row || row.owner_id !== ownerId || row.state !== 'ready' || row.need_id !== needId) throw new AudioError(409, '录音不可用或已提交，请重新录制或刷新查看。');
  }
 }
 function attach(ids, ownerId, needId) {
  for (const id of ids) {
   const result = db.prepare("UPDATE recordings SET need_id=? WHERE id=? AND owner_id=? AND state='ready' AND need_id IS NULL").run(needId, id, ownerId);
   if (result.changes !== 1) throw new AudioError(409, '录音已被提交，请刷新查看。');
  }
 }
 function forNeed(needId, ids) {
  return ids.map(id => db.prepare("SELECT * FROM recordings WHERE id=? AND need_id=? AND state='ready'").get(id, needId)).filter(Boolean).map(metadata);
 }
 function deletePending(id, ownerId) {
  const row = db.prepare('SELECT * FROM recordings WHERE id=?').get(id);
  if (!row || row.owner_id !== ownerId) throw new AudioError(404, '录音不存在。');
  if (row.need_id) throw new AudioError(409, '已提交录音请通过删除本人反馈一并清理。');
  db.prepare("UPDATE recordings SET state='deleting' WHERE id=? AND owner_id=? AND need_id IS NULL").run(id, ownerId); reap();
 }
 function removeForNeed(needId) { db.prepare("UPDATE recordings SET state='deleting',need_id=NULL WHERE need_id=?").run(needId); }
 function serve(req, res, id, ownerId) {
  collect();
  const row = db.prepare("SELECT * FROM recordings WHERE id=? AND state='ready'").get(id);
  if (!row || (!row.need_id && row.owner_id !== ownerId)) throw new AudioError(404, '录音不存在。');
  const filename = path(id);
  try { const file = lstatSync(filename); if (!file.isFile() || file.size !== row.size) throw new Error(); } catch { throw new AudioError(404, '录音文件不存在。'); }
  let start = 0, end = row.size - 1, status = 200;
  if (req.headers.range) {
   const match = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
   const rejectRange = () => { res.writeHead(416, {'Content-Range': `bytes */${row.size}`, 'Cache-Control': 'no-store'}); res.end(); };
   if (!match || (!match[1] && !match[2])) return rejectRange();
   if (!match[1]) { const suffix = Number(match[2]); if (!Number.isSafeInteger(suffix) || suffix <= 0) return rejectRange(); start = Math.max(0, row.size - suffix); }
   else { start = Number(match[1]); if (match[2]) end = Math.min(end, Number(match[2])); }
   if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= row.size) return rejectRange();
   status = 206;
  }
  res.writeHead(status, {'Content-Type': row.mime_type, 'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes', 'Cache-Control': 'private, no-store',
   'X-Content-Type-Options': 'nosniff', 'Content-Disposition': 'inline', ...(status === 206 ? {'Content-Range': `bytes ${start}-${end}/${row.size}`} : {})});
  if (req.method === 'HEAD') { res.end(); return; }
  const stream = createReadStream(filename, {start, end});
  stream.on('error', () => res.destroy()); res.once('close', () => stream.destroy()); stream.pipe(res);
 }
 collect();
 return {upload, verify, attach, forNeed, deletePending, removeForNeed, reap, collect, serve};
}
