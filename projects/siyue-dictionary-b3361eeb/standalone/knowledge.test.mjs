import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,writeFile,copyFile,rm,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes,randomUUID} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {createServer} from 'node:net';
import {PassThrough,Readable} from 'node:stream';
import {EventEmitter} from 'node:events';
import {build as bundle} from 'esbuild';
import {createKnowledgeStore,inspectKnowledgeFile,MAX_ATTACHMENT_BYTES} from './knowledge.mjs';
const here=dirname(fileURLToPath(import.meta.url));
const pause=ms=>new Promise(resolvePause=>setTimeout(resolvePause,ms));
const privateScope={mode:'private',userIds:[],unitIds:[],match:'any'};
const allScope={...privateScope,mode:'employees'};
const select=(unitIds=[],userIds=[],match='any')=>({mode:'selected',unitIds,userIds,match});
function zip(entries) {
 const local=[],central=[];let offset=0;
 for(const [name,value]of Object.entries(entries)){
  const filename=Buffer.from(name),data=Buffer.from(value),header=Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50);header.writeUInt16LE(20,4);header.writeUInt32LE(data.length,18);header.writeUInt32LE(data.length,22);header.writeUInt16LE(filename.length,26);
  const row=Buffer.alloc(46);row.writeUInt32LE(0x02014b50);row.writeUInt16LE(20,4);row.writeUInt16LE(20,6);row.writeUInt32LE(data.length,20);row.writeUInt32LE(data.length,24);row.writeUInt16LE(filename.length,28);row.writeUInt32LE(offset,42);
  local.push(header,filename,data);central.push(row,filename);offset+=header.length+filename.length+data.length;
 }
 const directory=Buffer.concat(central),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(Object.keys(entries).length,8);end.writeUInt16LE(Object.keys(entries).length,10);end.writeUInt32LE(directory.length,12);end.writeUInt32LE(offset,16);
 return Buffer.concat([...local,directory,end]);
}
function docx(extra={}){return zip({'[Content_Types].xml':'<Types>application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml</Types>','_rels/.rels':'<Relationships/>','word/document.xml':'<w:document><w:p><w:t>办公资料测试正文</w:t></w:p></w:document>',...extra});}

test('knowledge attachment inspection rejects active formats and malformed containers',()=>{
 assert.equal(inspectKnowledgeFile(Buffer.from('可搜索文本'),'资料.txt','text/plain').searchableText,'可搜索文本');
 assert.equal(inspectKnowledgeFile(docx(),'资料.docx').searchableText,'办公资料测试正文');
 assert.throws(()=>inspectKnowledgeFile(Buffer.from('<html>bad</html>'),'资料.pdf','application/pdf'),/PDF 文件无效/);
 assert.throws(()=>inspectKnowledgeFile(Buffer.from('<html>bad</html>'),'资料.html','text/html'),/支持 PDF/);
 assert.throws(()=>inspectKnowledgeFile(Buffer.from([0xff]),'资料.txt','text/plain'),/UTF-8/);
 assert.throws(()=>inspectKnowledgeFile(Buffer.from('text'),'../资料.txt'),/文件名无效/);
 assert.throws(()=>inspectKnowledgeFile(Buffer.alloc(MAX_ATTACHMENT_BYTES+1),'资料.txt'),/20MB/);
 assert.throws(()=>inspectKnowledgeFile(docx(),'资料.xlsx'),/Office 扩展名不符/);
 assert.throws(()=>inspectKnowledgeFile(docx({'word/vbaProject.bin':'macro'}),'资料.docx'),/宏或可执行/);
 assert.throws(()=>inspectKnowledgeFile(docx({'../escape.txt':'escape'}),'资料.docx'),/路径无效/);
 assert.throws(()=>inspectKnowledgeFile(docx({'word/document.xml':'<!DOCTYPE a [<!ENTITY foo SYSTEM "file:///etc/passwd">]><document>&foo;</document>'}),'资料.docx'),/特殊实体/);
 const bomb=docx();const central=bomb.indexOf(Buffer.from([0x50,0x4b,0x01,0x02]));bomb.writeUInt32LE(30*1024*1024,central+24);
 assert.throws(()=>inspectKnowledgeFile(bomb,'资料.docx'),/过大/);
});

test('knowledge API enforces confirmed organization memberships, sharing, attachments and search', {timeout:60000},async t=>{
 const folder=await mkdtemp(resolve(tmpdir(),'siyue-knowledge-test-')),dbPath=resolve(folder,'data/test.sqlite'),staticPath=resolve(folder,'public'),serverPath=resolve(folder,'server/server.mjs');
 await mkdir(resolve(folder,'server/runtime'),{recursive:true});await mkdir(staticPath);await writeFile(resolve(staticPath,'index.html'),'<!doctype html><title>fixture</title>');
 for(const file of ['policy-review.mjs','agent-read-api.mjs','domain-search.mjs','server.mjs','recordings.mjs','knowledge.mjs','catalog.mjs','runtime/catalog-data.mjs'])await copyFile(resolve(here,file),resolve(folder,'server',file));
 await bundle({entryPoints:[resolve(here,'../lib/validation.ts')],bundle:true,platform:'node',format:'esm',outfile:resolve(folder,'server/runtime/validation.mjs'),logLevel:'silent'});
 const reservation=createServer();await new Promise(r=>reservation.listen(0,'127.0.0.1',r));const port=reservation.address().port;await new Promise(r=>reservation.close(r));
 const base=`http://127.0.0.1:${port}`,password=randomBytes(24).toString('base64url');let server,adminId='';
 async function start(){server=spawn(process.execPath,[serverPath],{env:{...process.env,NODE_ENV:'test',HOST:'127.0.0.1',PORT:String(port),SIYUE_DB_PATH:dbPath,SIYUE_STATIC_DIR:staticPath,SIYUE_RECORDINGS_DIR:resolve(folder,'data/recordings'),SIYUE_KNOWLEDGE_DIR:resolve(folder,'data/knowledge'),SIYUE_REVIEWER_IDS:'',SIYUE_KNOWLEDGE_ADMIN_IDS:adminId,PUBLIC_ORIGIN:'',SIYUE_COOKIE_PATH:'/',TRUST_PROXY:''},stdio:['ignore','pipe','pipe']});let output='';server.stdout.on('data',c=>output+=c);server.stderr.on('data',c=>output+=c);for(let n=0;n<150;n++){if(server.exitCode!==null)throw Error(output);try{if((await fetch(`${base}/api/health`)).ok)return;}catch{}await pause(30);}throw Error(output);}
 async function stop(){if(!server||server.exitCode!==null)return;const exited=new Promise(r=>server.once('exit',r));server.kill('SIGTERM');await exited;}
 async function request(path,{cookie,method='GET',body,raw,headers={}}={}){const response=await fetch(base+path,{method,headers:{...(cookie?{Cookie:cookie}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{}),...headers},...(body!==undefined?{body:JSON.stringify(body)}:raw!==undefined?{body:raw}:{})});const content=await response.text();let parsed;try{parsed=JSON.parse(content);}catch{parsed=content;}return{status:response.status,body:parsed,headers:response.headers,cookie:response.headers.get('set-cookie')?.split(';')[0]};}
 async function register(name){const r=await request('/api/account/register',{method:'POST',body:{name,password,roleIds:['dept-head'],department:'集团',campus:'集团总部',isAdmin:true,access:{status:'active'}}});assert.equal(r.status,201,JSON.stringify(r.body));return{id:r.body.profile.id,cookie:r.cookie,name};}
 const jsonMethod=(actor,method,body)=>({cookie:actor.cookie,method,body});
 try{
  await start();const administrator=await register('权限管理员'),a=await register('招生甲员工'),b=await register('人事乙员工'),c=await register('招生丙员工'),pending=await register('待确认员工');
  adminId=administrator.id;
  assert.equal((await request('/api/me',{cookie:administrator.cookie})).body.access.status,'pending');
  assert.equal((await request('/api/organization/employees',{cookie:administrator.cookie})).status,403);
  await stop();await start();
  const organization=await request('/api/organization/employees',{cookie:administrator.cookie});assert.equal(organization.status,200);assert.equal(organization.body.employees.length,5);
  const department=name=>organization.body.units.find(unit=>unit.kind==='department'&&unit.name===name).id;
  const admissions=department('招生'),hr=department('HR'),ai=department('AI部');
  async function unit(kind,name){const r=await request('/api/organization/units',jsonMethod(administrator,'POST',{kind,name}));assert.equal(r.status,201);return r.body.unit.id;}
  const east=await unit('campus','东校区'),west=await unit('campus','西校区'),project=await unit('project','新生交接项目');
  async function approve(actor,unitIds,status='active'){const current=(await request('/api/organization/employees',{cookie:administrator.cookie})).body.employees.find(e=>e.id===actor.id);const r=await request(`/api/organization/employees/${actor.id}`,jsonMethod(administrator,'PATCH',{status,unitIds,version:current.access.version}));assert.equal(r.status,200,JSON.stringify(r.body));return r.body.employee;}
  await approve(administrator,[ai]);await approve(a,[admissions,east,project]);await approve(b,[hr,east]);await approve(c,[admissions,west]);
  assert.equal((await request('/api/agent/v1/search?q=test')).status,401);
  assert.equal((await request('/api/agent/v1/me',{cookie:a.cookie})).body.accessStatus,'active');
  assert.equal((await request('/api/agent/v1/me',{cookie:pending.cookie})).body.accessStatus,'pending');
  const agentQuery='/api/agent/v1/search?q='+encodeURIComponent('忘记打卡怎么办');
  assert.equal((await request(agentQuery,{cookie:pending.cookie})).body.total,0);
  const agentFound=await request(agentQuery+'&limit=1',{cookie:a.cookie});
  assert.equal(agentFound.body.results[0].key,'policy:policy-leave-correction');
  assert.equal(agentFound.body.results.length,1);assert.equal(agentFound.body.nextOffset,1);
  assert.equal(agentFound.body.results[0].matchKind,'semantic');
  const agentOriginal=await request('/api/agent/v1/entries/policy/policy-leave-correction',{cookie:a.cookie});
  assert.equal(agentOriginal.status,200);assert.ok(agentOriginal.body.content.sections.length);assert.ok(agentOriginal.body.sources.length);assert.match(agentOriginal.body.status,/待确认/);
  assert.equal((await request('/api/agent/v1/entries/policy/policy-leave-correction',{cookie:pending.cookie})).status,403);
  assert.equal((await request(agentQuery,jsonMethod(a,'POST',{}))).status,405);
  assert.equal((await request(agentQuery+'&limit=21',{cookie:a.cookie})).status,400);
  assert.equal((await request('/api/agent/v1/search?q=',{cookie:a.cookie})).status,400);
  assert.equal((await request('/api/catalog')).status,401);assert.equal((await request('/api/catalog',{cookie:pending.cookie})).status,403);assert.equal((await request('/api/catalog',{cookie:a.cookie})).status,200);
  const pendingMembers=(await request('/api/members',{cookie:pending.cookie})).body.members;assert.equal(pendingMembers.length,1);assert.equal(pendingMembers[0].id,pending.id);
  assert.equal((await request('/api/knowledge/context',{cookie:pending.cookie})).body.units.length,0);
  assert.equal((await request('/api/organization/employees',{cookie:a.cookie})).status,403);
  const before=(await request('/api/me',{cookie:a.cookie})).body.access;
  assert.equal((await request('/api/account/profile',jsonMethod(a,'PATCH',{name:a.name,roleIds:['dept-head'],department:'HR',campus:'集团总部'}))).status,200);
  assert.deepEqual((await request('/api/me',{cookie:a.cookie})).body.access,before);
  assert.equal((await request('/api/organization/units',jsonMethod(a,'POST',{kind:'department',name:'越权部门'}))).status,403);
  const create=async(actor,title,sharing,attachmentIds=[],body='只有授权人员才能看到的工作内容')=>request('/api/knowledge',jsonMethod(actor,'POST',{requestId:randomUUID(),title,body,sharing,attachmentIds}));
  const pendingPrivate=await create(pending,'未确认个人草稿',privateScope);assert.equal(pendingPrivate.status,201);assert.equal((await create(pending,'非法全员共享',allScope)).status,403);
  assert.equal((await request(`/api/knowledge/${pendingPrivate.body.document.id}`,{cookie:administrator.cookie})).status,404);
  const upload=await request('/api/knowledge/attachments?name='+encodeURIComponent('跨部门权限搜索.txt'),{cookie:a.cookie,method:'POST',raw:Buffer.from('附件深层暗号鹭鸶七号'),headers:{'Content-Type':'text/plain'}});assert.equal(upload.status,201,JSON.stringify(upload.body));const attachment=upload.body.attachment;
  const attachmentPath=`/api/knowledge/attachments/${attachment.id}`;
  assert.equal((await request(attachmentPath,{cookie:c.cookie})).status,404);
  assert.equal((await create(b,'盗用他人附件',privateScope,[attachment.id])).status,400);
  const departmentDoc=await create(a,'招生部门共享专题',select([admissions]),[attachment.id]);assert.equal(departmentDoc.status,201);const docId=departmentDoc.body.document.id;
  for(const actor of [a,c])assert.equal((await request(`/api/knowledge/${docId}`,{cookie:actor.cookie})).status,200);
  for(const actor of [b,pending,administrator]){assert.equal((await request(`/api/knowledge/${docId}`,{cookie:actor.cookie})).status,404);assert.equal((await request(attachmentPath,{cookie:actor.cookie,method:'HEAD'})).status,404);assert.equal((await request(attachmentPath,{cookie:actor.cookie,headers:{Range:'bytes=0-2'}})).status,404);assert.equal((await request('/api/search?q='+encodeURIComponent('鹭鸶七号'),{cookie:actor.cookie})).body.total,0);}
  const agentPrivatePath='/api/agent/v1/entries/document/'+docId;
  const agentPrivate=await request(agentPrivatePath,{cookie:c.cookie});assert.equal(agentPrivate.status,200);assert.equal(agentPrivate.body.content.attachments[0].id,attachment.id);assert.ok(!('sharing' in agentPrivate.body.content));
  for(const actor of [b,pending,administrator])assert.equal((await request(agentPrivatePath,{cookie:actor.cookie})).status,404);
  const agentDocSearch=await request('/api/agent/v1/search?q='+encodeURIComponent('鹭鸶七号'),{cookie:c.cookie});assert.equal(agentDocSearch.body.total,1);assert.equal(agentDocSearch.body.results[0].key,'document:'+docId);
  const download=await request(attachmentPath,{cookie:c.cookie});assert.equal(download.status,200);assert.equal(download.body,'附件深层暗号鹭鸶七号');assert.equal(download.headers.get('cache-control'),'no-store');assert.match(download.headers.get('content-disposition'),/^attachment;/);
  assert.equal((await request(attachmentPath,{cookie:c.cookie,method:'HEAD'})).status,200);
  const search=await request('/api/search?q='+encodeURIComponent('鹭鸶七号'),{cookie:c.cookie});assert.equal(search.body.total,1);assert.equal(search.body.results[0].id,docId);
  assert.equal((await request('/api/search?q='+encodeURIComponent('鹭鸶七号 不存在'),{cookie:c.cookie})).body.total,0);
  assert.equal((await request(`/api/knowledge/${docId}`,jsonMethod(c,'PATCH',{version:1,title:'越权修改',body:'越权内容',sharing:allScope,attachmentIds:[attachment.id]}))).status,404);
  const both=await create(a,'仅东校区招生可见',select([admissions,east],[],'all'));assert.equal(both.status,201);
  assert.equal((await request(`/api/knowledge/${both.body.document.id}`,{cookie:c.cookie})).status,404);assert.equal((await request(`/api/knowledge/${both.body.document.id}`,{cookie:b.cookie})).status,404);
  await approve(c,[admissions,east]);assert.equal((await request(`/api/knowledge/${both.body.document.id}`,{cookie:c.cookie})).status,200);
  const individuals=await create(a,'指定乙员工可见',select([],[b.id]));assert.equal(individuals.status,201);assert.equal((await request(`/api/knowledge/${individuals.body.document.id}`,{cookie:b.cookie})).status,200);assert.equal((await request(`/api/knowledge/${individuals.body.document.id}`,{cookie:c.cookie})).status,404);
  const shared=await create(a,'全员经验',allScope);assert.equal(shared.status,201);assert.equal((await request(`/api/knowledge/${shared.body.document.id}`,{cookie:administrator.cookie})).status,200);assert.equal((await request(`/api/knowledge/${shared.body.document.id}`,{cookie:pending.cookie})).status,404);
  const projectDoc=await create(a,'项目资料',select([project]));assert.equal(projectDoc.status,201);assert.equal((await request(`/api/knowledge/${projectDoc.body.document.id}`,{cookie:c.cookie})).status,404);
  await approve(c,[project]);assert.equal((await request(`/api/knowledge/${projectDoc.body.document.id}`,{cookie:c.cookie})).status,200);assert.equal((await request(`/api/knowledge/${docId}`,{cookie:c.cookie})).status,404);assert.equal((await request(attachmentPath,{cookie:c.cookie})).status,404);assert.equal((await request('/api/search?q='+encodeURIComponent('鹭鸶七号'),{cookie:c.cookie})).body.total,0);
  const revise={version:1,title:'招生部门共享专题',body:'只有授权人员才能看到的工作内容',sharing:privateScope,attachmentIds:[attachment.id]};const changed=await request(`/api/knowledge/${docId}`,jsonMethod(a,'PATCH',revise));assert.equal(changed.status,200);assert.equal(changed.body.document.version,2);
  assert.equal((await request(`/api/knowledge/${docId}`,jsonMethod(a,'PATCH',revise))).status,409);
  await approve(c,[admissions,east]);assert.equal((await request(`/api/knowledge/${docId}`,{cookie:c.cookie})).status,404);assert.equal((await request(attachmentPath,{cookie:c.cookie})).status,404);
  const idempotent={requestId:randomUUID(),title:'重复提交保护',body:'幂等正文',sharing:privateScope,attachmentIds:[]};const first=await request('/api/knowledge',jsonMethod(a,'POST',idempotent));const retried=await request('/api/knowledge',jsonMethod(a,'POST',idempotent));assert.equal(first.status,201);assert.equal(retried.status,200);assert.equal(first.body.document.id,retried.body.document.id);assert.equal((await request('/api/knowledge',jsonMethod(a,'POST',{...idempotent,body:'不同正文'}))).status,409);
  const savedAuthor=first.body.document.author;await request('/api/account/profile',jsonMethod(a,'PATCH',{name:'更名招生员工',roleIds:['hr']}));assert.equal((await request(`/api/knowledge/${first.body.document.id}`,{cookie:a.cookie})).body.document.author,savedAuthor);
  const oldVersion=(await request('/api/me',{cookie:b.cookie})).body.access.version;await approve(b,[hr], 'suspended');assert.equal((await request('/api/me',{cookie:b.cookie})).body.access.status,'suspended');for(const path of ['/api/catalog','/api/knowledge','/api/knowledge/context','/api/search?q=经验',`/api/knowledge/${individuals.body.document.id}`,attachmentPath,'/api/needs','/api/members'])assert.equal((await request(path,{cookie:b.cookie})).status,403,path);
  assert.equal((await request(`/api/organization/employees/${b.id}`,jsonMethod(administrator,'PATCH',{version:oldVersion,status:'active',unitIds:[hr]}))).status,409);
  const adminCurrent=(await request('/api/me',{cookie:administrator.cookie})).body.access;
  assert.equal((await request(`/api/organization/employees/${administrator.id}`,jsonMethod(administrator,'PATCH',{version:adminCurrent.version,status:'suspended',unitIds:[]}))).status,400);
  assert.equal((await request('/api/knowledge',jsonMethod(a,'POST',{requestId:randomUUID(),title:'空范围',body:'不能默认公开',sharing:select(),attachmentIds:[]}))).status,400);
  assert.equal((await request('/api/knowledge/attachments?name=bad.html',{cookie:a.cookie,method:'POST',raw:Buffer.from('<html>'),headers:{'Content-Type':'text/html'}})).status,415);
  assert.equal((await request('/api/knowledge/attachments?name='+encodeURIComponent('../secret.txt'),{cookie:a.cookie,method:'POST',raw:Buffer.from('bad'),headers:{'Content-Type':'text/plain'}})).status,400);
  const word=await request('/api/knowledge/attachments?name='+encodeURIComponent('测试.docx'),{cookie:a.cookie,method:'POST',raw:docx(),headers:{'Content-Type':'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}});assert.equal(word.status,201,JSON.stringify(word.body));const wordDoc=await create(a,'Office附件',privateScope,[word.body.attachment.id],'');assert.equal(wordDoc.status,201);assert.equal((await request('/api/search?q='+encodeURIComponent('办公资料测试正文'),{cookie:a.cookie})).body.total,1);assert.equal((await request('/api/search?q='+encodeURIComponent('办公资料测试正文'),{cookie:administrator.cookie})).body.total,0);
  const inspection=new DatabaseSync(dbPath);assert.equal(inspection.prepare('SELECT count(*) AS n FROM knowledge_revisions WHERE document_id=?').get(docId).n,2);assert.ok(inspection.prepare('SELECT count(*) AS n FROM organization_audit').get().n>10);assert.equal(inspection.prepare('SELECT status FROM employee_access WHERE employee_id=?').get(pending.id).status,'pending');inspection.close();
  await stop();await start();assert.equal((await request(`/api/knowledge/${docId}`,{cookie:a.cookie})).status,200);assert.equal((await request(attachmentPath,{cookie:a.cookie})).body,'附件深层暗号鹭鸶七号');assert.equal((await request('/api/me',{cookie:b.cookie})).body.access.status,'suspended');
  t.diagnostic('Confirmed identities, self-role spoofing, department/campus intersection, named people, projects, private admin boundary, permission search, protected GET/HEAD/Range attachments, revocation, suspended accounts, versions, idempotency, author attribution and restart persistence passed.');
 }finally{await stop();await rm(folder,{recursive:true,force:true});}
});

test('knowledge upload concurrency and site storage quotas bound memory and release reservations',async()=>{
 const folder=await mkdtemp(resolve(tmpdir(),'siyue-upload-limits-')),db=new DatabaseSync(':memory:');
 db.exec('PRAGMA foreign_keys=ON; CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT); CREATE TABLE employees(id TEXT PRIMARY KEY,name TEXT,role_ids TEXT,department TEXT,campus TEXT,grade TEXT);');
 const people=[randomUUID(),randomUUID(),randomUUID()];for(const id of people)db.prepare('INSERT INTO employees VALUES(?,?,?,?,?,?)').run(id,'上传员工','[]','','','');
 const directory=resolve(folder,'private'),staticDirectory=resolve(folder,'public');await mkdir(staticDirectory);
 const store=createKnowledgeStore(db,{directory,staticDirectory});
 const stream=()=>{const req=new PassThrough();req.headers={'content-type':'text/plain'};req.complete=true;const res=new EventEmitter();res.destroyed=false;return{req,res};};
 try{
  const first=stream(),second=stream(),third=stream();
  const a=store.upload(people[0],first.req,first.res,'第一份.txt');const b=store.upload(people[1],second.req,second.res,'第二份.txt');
  await assert.rejects(store.upload(people[2],third.req,third.res,'第三份.txt'),error=>error.status===429&&/当前上传较多/.test(error.message));third.req.end('rejected');
  first.req.end('first');second.req.end('second');await Promise.all([a,b]);first.res.emit('finish');second.res.emit('finish');
  const after=stream();after.req.end('third');await store.upload(people[2],after.req,after.res,'第三份.txt');after.res.emit('finish');
  const invalid=stream();invalid.req.end(Buffer.from([0xff]));await assert.rejects(store.upload(people[0],invalid.req,invalid.res,'失败.txt'),/UTF-8/);
  const oversized=stream();const overflow=store.upload(people[0],oversized.req,oversized.res,'过大.txt');oversized.req.end(Buffer.alloc(MAX_ATTACHMENT_BYTES+1,65));await assert.rejects(overflow,error=>error.status===413);
  const availableA=stream(),availableB=stream();availableA.req.end('ok');availableB.req.end('ok');await Promise.all([store.upload(people[0],availableA.req,availableA.res,'重试甲.txt'),store.upload(people[1],availableB.req,availableB.res,'重试乙.txt')]);availableA.res.emit('finish');availableB.res.emit('finish');
  const limited=createKnowledgeStore(db,{directory,staticDirectory,maxTotalBytes:MAX_ATTACHMENT_BYTES});const blocked=stream();
  await assert.rejects(limited.upload(people[0],blocked.req,blocked.res,'站点满额.txt'),error=>error.status===413&&/知识库附件空间已满/.test(error.message));blocked.req.end('rejected');
  // A failed quota reservation must not permanently occupy a concurrent slot.
  assert.equal(db.prepare('SELECT count(*) AS n FROM knowledge_attachments').get().n,5);
  assert.equal(db.prepare('SELECT count(*) AS n FROM schema_migrations WHERE version=6').get().n,1);
 }finally{db.close();await rm(folder,{recursive:true,force:true});}
});
