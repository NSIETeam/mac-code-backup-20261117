/** Usage: node example.mjs /private/credential.json /private/intent.json [--print]
 * intent.json: { "sessionId":"UUID", "studentIds":["UUID"], "layout":"analysis",
 *                "printerId":"UUID (only for --print)", "copies":1, "duplex":false }
 * Use a NEW intent file for a new business intention. Never reuse it for changed selections.
 */
import { readFile, writeFile, rename, mkdir, open, unlink } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { dirname, basename, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { SiyueAgent } from './client.mjs';
const [credentialPath, intentPath, flag] = process.argv.slice(2);
if (!credentialPath || !intentPath || (flag && flag !== '--print')) throw new Error('提供私密凭证文件、持久意图文件和可选 --print；不要把密钥放在命令行');
const lockPath = intentPath + '.lock';
const lock = await open(lockPath, 'wx', 0o600); // One process owns this local intent. After a crash, inspect before removing the lock.
try {
const credential = JSON.parse(await readFile(credentialPath, 'utf8'));
const intent = JSON.parse(await readFile(intentPath, 'utf8'));
const save = async () => { const temp = intentPath + '.tmp-' + randomUUID(); await writeFile(temp, JSON.stringify(intent, null, 2), { mode:0o600 }); await rename(temp, intentPath); };
const client = new SiyueAgent({ baseUrl:credential.baseUrl, token:credential.token });
await client.connect();
if (!intent.exportRequestKey) { intent.exportRequestKey = randomUUID(); await save(); }
// Always replay with the persisted key, including after a crash between response and save.
const generated = await client.call('export_session', { path:{id:intent.sessionId}, body:{studentIds:intent.studentIds,layout:intent.layout ?? 'analysis',requestKey:intent.exportRequestKey} });
intent.generated = generated; intent.results ??= {}; await save();
const ready = [];
for (const doc of generated.documents) {
  const status = await client.waitTask('export', doc.id);
  intent.results[doc.id] = { ...intent.results[doc.id], state:status.state, studentName:doc.studentName, error:status.error }; await save();
  if (status.state !== 'ready') { console.log(`${doc.studentName}：${status.state}`); continue; }
  ready.push(doc.id);
  await client.call('start_preview', {path:{kind:'export',id:doc.id}});
  const download = await client.call('download', {path:{id:doc.id},query:{format:'pdf'}});
  const url = new URL(download.url);
  if (url.origin !== new URL(credential.baseUrl).origin) throw new Error('下载地址不属于当前服务，停止发送请求');
  const folder = resolve(dirname(intentPath), 'documents', doc.id); await mkdir(folder, {recursive:true});
  if (basename(download.fileName) !== download.fileName || !download.fileName) throw new Error('无效下载文件名');
  const destination = resolve(folder, download.fileName);
  if (!intent.results[doc.id].downloaded) {
    const response = await fetch(url, {redirect:'error',signal:AbortSignal.timeout(60000)});
    if (!response.ok || !response.body) throw new Error('下载失败，请重新获取链接');
    const temporary = destination + '.part-' + randomUUID();
    await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary, {flags:'wx',mode:0o600}));
    await rename(temporary, destination);
    intent.results[doc.id].downloaded = destination; await save();
  }
  console.log(`${doc.studentName}：PDF 已保存`);
}
if (flag === '--print') {
  if (!intent.printerId) throw new Error('显式打印需要指定已授权 printerId');
  if (!ready.length) throw new Error('没有就绪文档，不打印');
  // Freeze the document subset as well as the key. Partial failures may finish on a later run.
  if (!intent.printRequestKey) { intent.printRequestKey=randomUUID();intent.printDocumentIds=ready;await save(); }
  const jobs = await client.call('submit_print', {body:{documentIds:intent.printDocumentIds,printerId:intent.printerId,copies:intent.copies ?? 1,duplex:intent.duplex ?? false,requestKey:intent.printRequestKey}});
  intent.printJobs=jobs.map(({id,documentId,state})=>({id,documentId,state}));await save();
  console.log(`已提交 ${jobs.length} 个打印任务；这不表示已出纸。`);
} else console.log('仅生成和下载；未提交打印。');

} finally { await lock.close(); await unlink(lockPath); }
