import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {formatQueueEstimate,queueDuration,queueActive} from '../server/wwwroot/workbench/queue-estimate.mjs';
import {nextBookReview,nextClassReview} from '../server/wwwroot/workbench/review-navigation.mjs';
const ts=createRequire(import.meta.url)('typescript');
const app=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');
const tree=ts.createSourceFile('app.js',app,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
const code=name=>tree.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name?.text===name).getText(tree);
function scopeWith(names,values){const scope={bookSplitView:null,formatQueueEstimate,queueActive,nextBookReview,nextClassReview,...values};vm.createContext(scope);vm.runInContext(names.map(code).join('\n'),scope);return scope;}

test('queue estimates cover start, remaining, initial, long duration and invalid values without false zero',()=>{
 assert.equal(formatQueueEstimate({state:'queued',ahead:2,waitSeconds:121,basis:'observed'}),'前面还有 2 个任务 · 预计约 3 分钟开始');
 for(const state of ['running','processing'])assert.equal(formatQueueEstimate({state,remainingSeconds:61,basis:'initial'}),'预计还需约 2 分钟（初步估算）');
 assert.equal(queueDuration(3661),'1 小时 2 分钟');assert.equal(queueDuration(0),'1 分钟');
 for(const basis of ['observed','initial']){
  assert.equal(formatQueueEstimate({state:'queued',ahead:0,waitSeconds:0,basis}),`即将开始${basis==='initial'?'（初步估算）':''}`);
  for(const state of ['running','processing'])assert.equal(formatQueueEstimate({state,remainingSeconds:0,basis}),'正在完成收尾');
 }
 assert.match(formatQueueEstimate({state:'queued',ahead:0,waitSeconds:0,basis:'unknown'}),/暂无法估算/);
 for(const value of [null,undefined,-1,Infinity,NaN,'30'])assert.match(formatQueueEstimate({state:'running',remainingSeconds:value,basis:'observed'}),/暂无法估算/);
 assert.match(formatQueueEstimate({state:'queued',waitSeconds:10,ahead:null,basis:'unknown'}),/暂无法估算开始时间/);
 assert.equal(formatQueueEstimate({state:'waiting',basis:'unknown'}),'等待前置步骤完成，暂无法估算等待时间');
 assert.equal(formatQueueEstimate({state:'retrying',delayed:true}),'正在等待重试，暂无法估算等待时间');
 assert.match(formatQueueEstimate({state:'unknown',basis:'unknown'},'processing'),/暂无法估算剩余时间/);
 for(const state of ['done','failed','published','draft'])assert.equal(formatQueueEstimate({state,remainingSeconds:0,basis:'observed'}),'');
});

test('old estimate timestamp is not a countdown and only server delayed marks an overdue task',()=>{
 const queue={state:'running',remainingSeconds:180,basis:'observed',updatedAt:'2000-01-01T00:00:00Z'};
 assert.equal(formatQueueEstimate(queue),'预计还需约 3 分钟');
 assert.equal(formatQueueEstimate({...queue,delayed:true}),'耗时超过预估，仍在继续处理');
 assert.match(formatQueueEstimate({state:'queued',ahead:1,waitSeconds:0,basis:'observed',delayed:true}),/前面还有 1 个任务 · 等待时间超过预估/);
});

test('web and mini use identical queued and processing estimate wording',()=>{
 const scope={exports:{}};vm.runInNewContext(ts.transpile(fs.readFileSync('miniprogram/utils/queue-estimate.ts','utf8'),{module:ts.ModuleKind.CommonJS}),scope);
 for(const state of ['queued','running','processing'])for(const basis of ['initial','observed','unknown'])for(const value of [null,0,61,3661])for(const delayed of [false,true])for(const ahead of [0,2]){
  const q={state,basis,delayed,ahead,waitSeconds:value,remainingSeconds:value,updatedAt:'2000-01-01'};
  assert.equal(formatQueueEstimate(q),scope.exports.formatQueueEstimate(q),JSON.stringify(q));
 }
});

const ctx={classId:'c',courseId:'math',currentId:'h1',lectureId:'l1'};
const book={book:{id:'b',classId:'c',courseId:'math'},sections:[{kind:'lesson',sequence:10,handoutId:'h10'},{kind:'lesson',sequence:2,handoutId:'h2'},{kind:'lesson',sequence:1,handoutId:'h1'}],handouts:[{id:'h1',state:'published'},{id:'h2',state:'draft'},{id:'h10',state:'draft'}]};
test('publishing continues numeric lecture order, never skips a waiting lesson and stays in the book when finished',()=>{
 assert.deepEqual(nextBookReview(book,ctx),{name:'handout',p:{id:'h2',classId:'c',bookId:'b'}});
 const processing={...book,handouts:book.handouts.map(h=>h.id==='h2'?{...h,state:'queued'}:h)};
 assert.deepEqual(nextBookReview(processing,ctx),{name:'book',p:{id:'b',classId:'c',next:'h2'}});
 const done={...book,handouts:book.handouts.map(h=>({...h,state:'published'}))};
 assert.deepEqual(nextBookReview(done,ctx),{name:'book',p:{id:'b',classId:'c'}});
 assert.deepEqual(nextBookReview(book,{...ctx,currentId:'h10'}),{name:'handout',p:{id:'h2',classId:'c',bookId:'b'}});
});
test('missing or failed next lecture stays visible instead of being marked complete',()=>{
 const waiting={...book,sections:[book.sections[2],{kind:'lesson',sequence:2,title:'uncreated'}],handouts:[book.handouts[0]]};
 assert.deepEqual(nextBookReview(waiting,ctx),{name:'book',p:{id:'b',classId:'c',next:'seq-2'}});
 assert.equal(nextBookReview({...book,handouts:book.handouts.map(h=>({...h,state:h.id==='h1'?'published':'failed'}))},ctx).name,'book');
});
test('review continuation cannot cross class, course or an unrelated book',()=>{
 for(const variant of [{...book,book:{...book.book,classId:'other'}},{...book,book:{...book.book,courseId:'english'}}])assert.deepEqual(nextBookReview(variant,ctx),{name:'class',p:{id:'c',handouts:'1'}});
 assert.equal(nextBookReview(book,{...ctx,currentId:'not-in-book'}).name,'class');
 const detail={schoolClass:{id:'c'},lectures:[{id:'l1',classId:'c',courseId:'math',sequence:1},{id:'foreign',classId:'other',courseId:'math',sequence:2},{id:'eng',classId:'c',courseId:'english',sequence:2},{id:'l2',classId:'c',courseId:'math',sequence:2}],handouts:[{id:'h1',lectureId:'l1',version:1,state:'published'},{id:'bad-class',lectureId:'foreign',version:1,state:'draft'},{id:'bad-course',lectureId:'eng',version:1,state:'draft'},{id:'old-draft',lectureId:'l2',version:1,state:'draft'},{id:'h2',lectureId:'l2',version:2,state:'draft'}]};
 assert.deepEqual(nextClassReview(detail,ctx),{name:'handout',p:{id:'h2',classId:'c'}});
 assert.deepEqual(nextClassReview(detail,{...ctx,currentId:'not-in-class'}),{name:'class',p:{id:'c',handouts:'1'}});
 detail.handouts.at(-1).state='published';assert.equal(nextClassReview(detail,ctx).name,'class');
 assert.deepEqual(nextClassReview({...detail,schoolClass:{id:'other'}},ctx),{name:'class',p:{id:'c',handouts:'1'}});
});

test('same review signature updates only ETA text, retaining loaded questions and scroll',async()=>{
 const eta={dataset:{queueKey:'handout:h'},textContent:'',hidden:false};const main={dataset:{},querySelectorAll:()=>[eta]};let paints=0,scrolls=0,html='';
 const detail={handout:{id:'h',state:'processing',revision:1},lecture:{id:'l',courseId:'math'},questions:[],queue:{state:'running',basis:'observed',remainingSeconds:600}};
 const scope=scopeWith(['queueInfo','refreshQueueInfo','handoutPage'],{document:{querySelector:()=>main},window:{scrollTo(){scrolls++;}},api:async()=>detail,paint:value=>{html=value;paints++;},heading:()=>'',button:label=>label,e:x=>x,empty:()=>'',labels:{},schedule(){},go(){},render(){},editButton(){},canVerifyPdfIntegrity(){},questionHtml(){},formulaEvidenceHtml(){}});
 await scope.handoutPage({id:'h',classId:'c'});assert.match(html,/预计还需约 10 分钟/);
 detail.queue.remainingSeconds=120;await scope.handoutPage({id:'h',classId:'c'});
 assert.equal(paints,1);assert.equal(scrolls,0);assert.equal(eta.textContent,'预计还需约 2 分钟');
 detail.queue={state:'done'};await scope.handoutPage({id:'h',classId:'c'});assert.equal(eta.hidden,true);assert.equal(paints,1);
});

test('class session synchronization is idempotent per published set and sorts numeric lecture sequences',async()=>{
 const calls=[],detail={lectures:[{id:'l1',courseId:'math',sequence:1}],handouts:[{id:'h1',state:'published',version:1}]};
 const scope=scopeWith(['synchronizedClassSessions'],{workspace:{user:{id:'teacher'}},api:async(path,method)=>{calls.push([path,method]);return method==='POST'?{}:[{sequence:10,lessonDate:''},{sequence:2,lessonDate:'2026-11-04'},{sequence:1,lessonDate:''}];}});
 assert.equal(JSON.stringify((await scope.synchronizedClassSessions('c',detail)).map(s=>s.sequence)),JSON.stringify([1,2,10]));
 await scope.synchronizedClassSessions('c',detail);assert.equal(calls.filter(c=>c[1]==='POST').length,1);
 detail.handouts.push({id:'h2',state:'published',version:1});await scope.synchronizedClassSessions('c',detail);assert.equal(calls.filter(c=>c[1]==='POST').length,2);
 detail.students=[{id:'new-student'}];await scope.synchronizedClassSessions('c',detail);assert.equal(calls.filter(c=>c[1]==='POST').length,3,'new students must be added to automatic sessions');
});

test('published book URLs return to the book, and direct review never returns to the class setup wizard',async()=>{
 const routes=[],detail={classId:'c',handout:{id:'h1',state:'published'},lecture:{id:'l1',courseId:'math'}};
 const scope=scopeWith(['continueHandoutReview','handoutPage'],{api:async path=>path.startsWith('/handouts/')?detail:book,go:(name,p)=>routes.push({name,p}),notice(){}});
 await scope.handoutPage({id:'h1',classId:'c',bookId:'b'});assert.equal(routes.at(-1).name,'book');
 await scope.handoutPage({id:'h1',classId:'c'});assert.equal(routes.at(-1).name,'grade');
 assert.ok(routes.every(r=>r.name!=='setup'));
});

test('whole-book polling updates every lecture ETA without repainting unchanged content',async()=>{
 let paints=0,html='';const nodes=['book:b','handout:h2','handout:h10'].map(key=>({dataset:{queueKey:key},textContent:'',hidden:false,style:{}}));
 const main={dataset:{},querySelectorAll:()=>nodes};
 const detail={...book,book:{...book.book,state:'split',fileName:'整册.pdf'},queue:{state:'done'},handouts:book.handouts.map(h=>({...h,state:h.id==='h1'?'published':'queued',queue:h.id==='h1'?{state:'done'}:{state:'queued',ahead:2,waitSeconds:300,basis:'observed'}}))};
 const scope=scopeWith(['queueInfo','refreshQueueInfo','queueViewUnchanged','rememberQueueView','bookPage'],{document:{querySelector:()=>main},api:async()=>detail,paint:s=>{html=s;paints++;},heading:()=>'',button:text=>text,e:x=>x,schedule(){},go(){}});
 await scope.bookPage({id:'b',classId:'c',next:'h2'});assert.match(html,/下一讲 · 第2讲/);assert.equal((html.match(/预计约 5 分钟开始/g)||[]).length,2);
 detail.handouts.find(h=>h.id==='h2').queue.waitSeconds=60;detail.handouts.find(h=>h.id==='h10').queue.waitSeconds=120;
 await scope.bookPage({id:'b',classId:'c',next:'h2'});assert.equal(paints,1);assert.match(nodes[1].textContent,/约 1 分钟/);assert.match(nodes[2].textContent,/约 2 分钟/);assert.equal(nodes[0].hidden,true);
});

test('class view synchronizes before reading sessions, shows unscheduled dates and refreshes ETA in place',async()=>{
 const detail={schoolClass:{id:'c',name:'本班'},students:[],courses:[{id:'math',name:'数学'}],lectures:[{id:'l1',courseId:'math',sequence:1,title:'一讲'}],handouts:[{id:'h1',lectureId:'l1',version:1,state:'queued',queue:{state:'queued',ahead:1,waitSeconds:120,basis:'observed'}}]};
 const node={dataset:{queueKey:'handout:h1'},textContent:'',hidden:false,style:{}},main={dataset:{},querySelectorAll:()=>[node]},imports={innerHTML:''},calls=[];let paints=0,html='';
 const scope=scopeWith(['queueInfo','refreshQueueInfo','queueViewUnchanged','rememberQueueView','synchronizedClassSessions','bookImportProgress','classPage'],{document:{querySelector:s=>s==='#imports'?imports:main},workspace:{user:{id:'teacher'}},classCourses:()=>[],rememberClassroom(){},nameOf:()=> '数学',empty:text=>text,paint:s=>{html=s;paints++;},heading:()=>'',button:text=>text,e:x=>x,badge:()=>'',schedule(){},api:async(path,method)=>{calls.push([path,method]);if(method==='POST')return {};if(path==='/classes/c')return detail;if(path.startsWith('/class-sessions'))return[{id:'s10',sequence:10,title:'十讲',lessonDate:'',reviewed:0,total:0},{id:'s2',sequence:2,title:'二讲',lessonDate:'2026-11-04',reviewed:0,total:0}];return[];}});
 await scope.classPage({id:'c',handouts:'1'});assert.equal(calls[1][0],'/classes/c/sync-sessions');assert.match(html,/待安排上课/);assert.match(html,/2026-11-04/);assert.ok(html.indexOf('第2讲')<html.indexOf('第10讲'));assert.match(html,/<details class="card" open>/);
 detail.handouts[0].queue.waitSeconds=60;await scope.classPage({id:'c',handouts:'1'});assert.equal(paints,1);assert.match(node.textContent,/约 1 分钟/);assert.equal(calls.filter(x=>x[1]==='POST').length,1);
});
