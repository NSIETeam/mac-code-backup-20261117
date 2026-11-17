import {chromium} from '/Users/king/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import assert from 'node:assert/strict';import fs from 'node:fs';import http from 'node:http';import path from 'node:path';
const web=path.resolve('web'),before=process.argv.includes('--before');
const bound=process.argv.includes('--bound');
let reviewRevision=1;const reviewed=new Set();
const apiCalls=[],unhandled=[],dialogs=[],previewChecks=[];
const course={id:'c1',name:'2026·秋季·初一·数学·推荐生',canManage:true};const cl={id:'cl1',name:'秋季数学提高班·周六上午9:00',campusId:'camp',ownerStaffId:'u1'};
const student={id:'s1',name:'演示同学',code:'001'};const lecture={id:'l1',sequence:9,title:'一次函数与综合应用',courseId:'c1',classId:'cl1'};
const handout={id:'h1',lectureId:'l1',fileName:'初一秋季数学第九讲（学生版）.docx',state:'draft',version:1,revision:1,questionCount:2,reviewCount:1};
const question={id:'q1',label:'例1',order:1,confirmed:false,revision:1,previews:['/fixture.png'],issues:[],blocks:[{text:'已知 y = 2x + 1，求函数在 x = 3 时的值。',xml:'',kind:'paragraph'}],text:'已知 y = 2x + 1，求函数在 x = 3 时的值。',formulaReviews:[]};
const book={id:'b1',courseId:'c1',classId:'cl1',fileName:'2026初一秋季数学全册-学生讲义-长文件名对齐检查.pdf',state:'split',revision:1,handoutIds:['h1']};
const session={id:'session1',classId:'cl1',courseId:'c1',handoutId:'h1',className:cl.name,sequence:9,title:lecture.title,lessonDate:'2026-11-05',reviewed:0,total:1};
const detail={schoolClass:cl,students:[student],courses:[course],lectures:[lecture],handouts:[handout],materials:[],books:[book]};
const material={id:'m1',courseId:'c1',title:'初一秋季数学（学生版）',fileName:'数学.pdf',format:'pdf',originalRelativePath:'数学.pdf',role:'handout',audience:'student',materialVersion:1,lectureIds:['l1']};
const user={id:'u1',name:'演示老师',role:'admin'};const workspace={user,classes:[cl,{...cl,id:'cl2',name:'周日物理班'}],courses:[course],campuses:[{id:'camp',name:'银座晶都校区'}],assignments:[{classId:'cl1',courseId:'c1',staffId:'u1'}],printCampuses:[{id:'camp',name:'银座晶都校区'}]};
const doc={id:'d1',studentName:'演示同学',state:'ready',canDownloadWord:true,createdAt:'2026-11-05T10:00:00Z'};
if(bound){lecture.classId=null;detail.handouts=[];detail.books=[];detail.lectures.push({...lecture,id:'l2',sequence:2,title:'共享课程第二讲'});material.lectureIds.push('l2');}
const bankItems=Array.from({length:24},(_,i)=>({id:'h:q'+i,revision:'revision-'+i,label:'例'+(i+1),text:'已知一次函数 y = 2x + 1，求 x = 3 时的值，并说明解题思路。',subject:'数学',grade:'初一',tags:['一次函数'],sourceId:'h:h1',sourceTitle:i%2?'初一秋季数学·第三讲（教师校对版）':'函数专项复习资料',courseName:course.name,lecture:'第3讲 · 一次函数',shared:i%2===0,openRoute:'#handout?id=h1'}));
let bankPapers=[],posted=[];
function data(p){const u=new URL(p,'http://x'),s=u.pathname.replace(/^\/miniapp\/api/,'');
 if(s==='/material-question-bank'){const items=u.searchParams.get('q')==='空结果'?[]:bankItems;return {items,total:items.length,available:24,page:1,pageSize:24,subjects:['数学'],grades:['初一'],tags:['一次函数'],sources:[{id:'h:h1',name:'初一秋季数学第三讲',count:24}]};}
 if(s==='/material-question-bank/question')return {question:bankItems.find(q=>q.id===u.searchParams.get('id')),images:[]};
 if(s==='/material-question-bank/papers')return bankPapers;
 if(s.startsWith('/material-question-bank/papers/')){const doc=bankPapers.find(p=>p.id===s.split('/').at(-1));doc.state='ready';return doc;}
 if(s.startsWith('/exports/bank-paper/'))return {fileName:'专项练习.'+u.searchParams.get('format'),url:'/fixture.pdf'};
 if(s==='/print-files')return {id:'uploaded-file'};if(s==='/workspace')return workspace;
 if(s==='/central-research/me')return {canReview:true,canManage:true,directorName:'演示主任'};
 if(s==='/central-research/dashboard')return {directorName:'演示主任',pending:0,problems:2,courses:1,counts:{pending:0,problems:2,courses:1,all:2}};
 if(s==='/central-research/problems')return {items:[{id:'h1',kind:'handout',title:handout.fileName,courseName:course.name,className:cl.name,category:'review',state:'draft',reasons:['题目边界需核对'],route:'handout',routeParameters:{id:'h1',classId:'cl1'},sourcePath:'/source',questionCount:2,pendingCount:1}],counts:{all:1,review:1},total:1,page:0,pageSize:30};
 if(s==='/central-research/submissions')return {items:[],total:0};
 if(s==='/courses')return [course];
 if(s==='/courses/c1/lectures')return {...detail,course,canManage:true};
 if(s==='/classes/cl1')return detail;
 if(s==='/classes/cl1/books'||s==='/courses/c1/books')return bound?[]:[book];
 if(s.endsWith('/sync-sessions'))return {};
 if(s==='/class-sessions')return bound?[]:[session];
 if(s==='/class-sessions/session1')return {session,lecture,className:cl.name,students:[student],questions:[question],marks:[{studentId:'s1',questionIds:[],notes:{},reviewed:false,revision:0}]};
 if(s==='/handouts/old')return {handout:{...handout,state:'superseded'},replacementHandoutId:'h1'};
 if(s==='/handouts/h1')return {handout,lecture,classId:'cl1',students:[student],questions:[question,{...question,id:'q2',label:'练1'}],wrong:[],marks:[],absentStudentIds:[],sourceUrl:'/fixture.pdf'};
 if(s==='/books/b1/page-preview')return {state:'ready',revision:1,sourceVersion:'fixture-v1',pageCount:2,sourceUrl:'/fixture.pdf',canSplit:true};
 if(s==='/books/b1')return {book,handouts:[handout],sections:[{id:'sec1',kind:'lesson',sequence:9,title:lecture.title,handoutId:'h1'}],sourceUrl:'/fixture.pdf',progress:{stage:'completed'},publication:{published:false}};
 if(s==='/exports')return [doc,{...doc,id:'d2',studentName:'另一位同学',state:'processing'}];
 if(s==='/previews/export/d1/file'||s==='/previews/handout/h1/file')return {url:'/fixture.pdf',title:'预览验收',fileName:'验收.pdf'};
 if(s==='/print-jobs')return [{id:'j1',studentName:'演示同学',printerName:'银座晶都西侧打印机',documentId:'d1',copies:1,duplex:true,state:'submitted',createdAt:doc.createdAt,detail:'已提交打印机'}];
 if(s==='/admin/catalog')return {...workspace,staff:[{...user,enabled:true,account:'demo'},{...user,id:'u2',name:'另一位老师',account:null}],printers:[],devices:[]};
 if(s==='/admin/activity')return {items:[{createdAt:doc.createdAt,actorName:'演示老师',action:'book.upload',targetName:book.fileName}],hasMore:false};
 if(s==='/admin/learning-model')return {enabled:false,configured:false,provider:'custom',protocol:'chat-completions',endpoint:'https://example.test/v1',model:'sample-model',revision:0};
 if(s==='/course-material-library')return {courses:[{courseId:'c1',courseName:course.name,materials:[material]}]};
 if(s==='/published-course-books')return {courses:[]};
 if(s==='/course-material-library/c1/m1/sections')return {lectures:[{...lecture,startPage:1,endPage:2,questionIds:['q1']}],sourceEditionId:'v1'};
 if(s==='/courses/c1/materials')return {courseId:'c1',course,courseName:course.name,materials:[material],lectures:[lecture]};
 if(s==='/material-versions/by-material/m1')return {currentEditionId:'v1',history:[{id:'v1',number:1}],canEdit:true};
 if(s==='/material-versions/v1')return {id:'v1',title:material.title,number:1,revision:reviewRevision,isCurrent:true,canCentralReview:true,pdfReady:true,docxReady:true,questions:[1,2,3,4].map(i=>({...question,id:'q'+i,label:'例'+i,reviewState:reviewed.has('q'+i)?'reviewed':'pending',reviewRevision:reviewed.has('q'+i)?1:0,replaceable:true}))};
 if(s.endsWith('/images'))return {images:[]};
 if(s.includes('/history'))return {student,course,items:[],total:0,page:1,pageSize:20,hasMore:false};
 if(s==='/handout-library/book/b1')return {item:{kind:'book',...book,canOpen:true},children:[],sourcePdfUrl:'/fixture.pdf'};
 if(s==='/central-research/courses')return {courses:[{...course,courseId:'c1',courseName:course.name,lectures:1,books:1}],total:1};
 if(s==='/handout-library')return {items:[{kind:'book',...book,staffName:user.name,className:cl.name,courseName:course.name,lectureCount:1,questionCount:2,status:'review',canOpen:true,openUrl:'#book?id=b1&classId=cl1'}],stats:{uploads:1,teachers:1,classes:1,lectures:1},filters:{teachers:[],classes:[],courses:[],statuses:[]},page:1,total:1,pageSize:20};
 if(s==='/course-naming-options')return {minimumYear:2000,maximumYear:2100,terms:['秋季'],grades:['初一'],subjects:['数学'],types:['推荐生']};
 if(s==='/printers')return [{id:'pr1',campusId:'camp',name:'西侧打印机',enabled:true,duplex:true,status:'ready',online:true}];
 if(s.startsWith('/support'))return {};
 unhandled.push(s);return {};
}
const server=http.createServer((req,res)=>{const u=new URL(req.url,'http://x'),s=u.pathname;if(s.startsWith('/miniapp/api/')){if(s==='/miniapp/api/material-question-bank/papers'&&req.method==='POST'){let body='';req.on('data',x=>body+=x);req.on('end',()=>{posted.push(JSON.parse(body));const result={id:'bank-paper',state:'queued',title:JSON.parse(body).title};bankPapers.unshift(result);res.setHeader('Content-Type','application/json');res.end(JSON.stringify(result));});return;}if(s==='/miniapp/api/classes/cl2'&&req.method==='DELETE'){assert.equal(u.searchParams.get('expectedName'),'周日物理班');workspace.classes=workspace.classes.filter(c=>c.id!=='cl2');apiCalls.push({method:'DELETE',path:s});res.statusCode=204;return res.end();}if(req.method==='POST'&&/material-versions.*(?:review|review-batch)$/.test(s)){let body='';req.on('data',x=>body+=x);req.on('end',()=>{const input=JSON.parse(body);const ids=input.questions?.map(q=>q.occurrenceId)||[s.match(/questions\/(q\d+)\/review/)[1]];ids.forEach(id=>reviewed.add(id));reviewRevision++;apiCalls.push({path:s,method:req.method,body:input});res.setHeader('Content-Type','application/json');res.end(JSON.stringify({editionId:'v1',revision:reviewRevision}));});return;}apiCalls.push({path:s,method:req.method});if(u.searchParams.has('classId')&&!u.searchParams.get('classId')){res.statusCode=400;return res.end(JSON.stringify({message:'Invalid empty classId'}));}if(s==='/previews/handout/h1/file'&&req.headers.referer?.includes('central')&&u.searchParams.get('central')!=='1'){res.statusCode=403;return res.end('{}');}res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(data(req.url)));}if(s==='/fixture.png'){res.setHeader('Content-Type','image/svg+xml');return res.end('<svg xmlns="http://www.w3.org/2000/svg" width="350" height="48"><text x="8" y="28" font-size="16">已知 y = 2x + 1，求 x = 3 时的值。</text></svg>');}if(s==='/fixture.pdf'){res.setHeader('Content-Type','application/pdf');return res.end(fs.readFileSync('/Users/king/WeChatProjects/miniprogram-1/.local/acceptance/page-load-20261105/tests/fixture.pdf'));}if(s.includes('/support/')){res.setHeader('Content-Type','application/javascript');return res.end('');}let rel=s.replace('/miniapp/workbench/','')||'index.html',p=path.join(web,rel);if(!p.startsWith(web)||!fs.existsSync(p)){res.statusCode=404;return res.end('missing');}res.setHeader('Content-Type',p.endsWith('.js')||p.endsWith('.mjs')?'text/javascript':p.endsWith('.css')?'text/css':p.endsWith('.html')?'text/html; charset=utf-8':'application/octet-stream');let b=fs.readFileSync(p);if(before&&p.endsWith('index.html'))b=Buffer.from(b.toString().replace(/<link rel="stylesheet" href="\.\/usability.css[^>]+>/,''));res.end(b);});await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});const base='http://127.0.0.1:'+server.address().port+'/miniapp/workbench/';

const checks=[];
try{
 for(const width of [390,1440]){
  const page=await browser.newPage({viewport:{width,height:850}});page.setDefaultTimeout(6500);
  const errors=[],writes=[];let fullReads=0,historyReads=0,conflict=false,revision=4;
  const qs=[{...question,previews:['/fixture.png'],confirmed:true},{...question,id:'q2',label:'练2',previews:['/fixture.png']}];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/handouts/h1**',async route=>{
    const url=new URL(route.request().url());let result,status=200;
    if(url.pathname.endsWith('/regions/history')){historyReads++;result={revision,last:null};}
    else if(url.pathname.endsWith('/edit')){
      const body=route.request().postDataJSON();writes.push(body);
      await new Promise(r=>setTimeout(r,100));
      if(body.revision!==revision||conflict){status=409;result={message:'讲义已被修改，请刷新'};}
      else{const q=qs.find(q=>q.id===body.questionId);if(body.action==='label'){q.label=body.label.trim();q.confirmed=false;}else q.confirmed=true;
      result={revision:++revision,question:{id:q.id,label:q.label,confirmed:q.confirmed,revision:q.revision}};}
    }else{fullReads++;result={...data('/miniapp/api/handouts/h1'),handout:{...handout,revision},questions:qs};}
    await route.fulfill({status,json:result});
  });
  await page.addInitScript(()=>{if(location.protocol.startsWith('http'))sessionStorage.setItem('siyue_web_token','fixture')});
  await page.goto(base+'#handout?id=h1&classId=cl1');
  const row=page.locator('[data-question-id="q1"]'),img=row.locator('.question-preview img');
  await row.waitFor();const originalImage=await img.elementHandle();
  const edit=()=>row.getByRole('button',{name:/编辑/}).click();
  await edit();assert.equal(fullReads,1);assert.equal(writes.length,0);
  const label=page.locator('#label-edit [name=label]');await label.fill('练7');await page.locator('#label-edit').getByRole('button',{name:'保存',exact:true}).click();
  await row.getByRole('heading',{name:'练7',exact:true}).waitFor();
  assert.ok(await img.evaluate((current,original)=>current===original,originalImage));assert.match(await img.getAttribute('alt'),/练7/);
  const confirm=row.getByRole('button',{name:'确认此题',exact:true});assert.ok(await confirm.isEnabled());
  await confirm.click();await row.getByRole('button',{name:'已确认',exact:true}).waitFor();
  assert.deepEqual(writes.map(x=>[x.action,x.revision]),[['label',4],['confirm',5]]);
  assert.equal(fullReads,1);assert.equal(historyReads,1);
  await edit();await label.fill('练8');conflict=true;await page.locator('#label-edit').getByRole('button',{name:'保存',exact:true}).click();await page.locator('dialog [role=alert]').waitFor();
  assert.equal(await label.inputValue(),'练8');assert.equal(await row.locator('h2').textContent(),'练7');assert.equal(fullReads,1);
  assert.deepEqual(errors,[]);checks.push({width,openLocal:true,oneSaveWrite:true,confirmImmediatelyAvailable:true,noHandoutOrHistoryReload:true,samePreviewNode:true,staleWriteRejected:true});await page.close();
 }
 fs.writeFileSync('qa/browser.json',JSON.stringify(checks,null,2));console.log(JSON.stringify(checks));
}finally{await browser.close();server.close();}
