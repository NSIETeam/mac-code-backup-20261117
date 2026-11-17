import test from 'node:test';
import assert from 'node:assert/strict';
import {formatQueueEstimate,queueActive} from '../server/wwwroot/workbench/queue-estimate.mjs';
const queueTestScope={bookSplitView:null,queueActive,queueInfo:(q,state)=>formatQueueEstimate(q,state),queueViewUnchanged:()=>false,rememberQueueView(){},refreshQueueInfo(){}};
import {escapeHtml, classCourses, marksFor, gradingPayload, printPayload, safeResource, canVerifyPdfIntegrity} from '../server/wwwroot/workbench/core.mjs';
test('PDF confirmation distinguishes a label check from an unresolved shared figure',()=>{
 const q={previews:['preview'],issues:[{code:'pdf-contextual-label',blocking:true},{code:'pdf-source-review',blocking:true}]};
 assert.equal(canVerifyPdfIntegrity(q),true);
 assert.equal(canVerifyPdfIntegrity({...q,previews:[]}),false);
 assert.equal(canVerifyPdfIntegrity({...q,issues:[...q.issues,{code:'pdf-merged-region',blocking:true}]}),false);
 assert.equal(canVerifyPdfIntegrity({...q,issues:[...q.issues,{code:'pdf-orphan-prefix',blocking:true}]}),false);
});
test('untrusted student and document names remain text',()=>assert.equal(escapeHtml('<img src=x onerror="alert(1)">&\''),'&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;&#39;'));
test('class course choices come from assignments, not the entire admin catalog',()=>{
  const workspace={courses:[{id:'a'},{id:'b'}],assignments:[{classId:'c',courseId:'b'},{classId:'d',courseId:'a'}]};
  assert.deepEqual(classCourses(workspace,'c'),[{id:'b'}]);
});
const detail={classId:'class-a',handout:{id:'version-a'},students:[{id:'student-a'}],questions:[{id:'q-a'},{id:'q-b'}],wrong:[{studentId:'student-a',questionId:'q-a'},{studentId:'student-b',questionId:'q-b'}],marks:[{studentId:'student-a',states:{'q-b':'unfinished','other-version-q':'wrong'}}]};
test('student switch restores only the selected student and version',()=>assert.deepEqual(marksFor(detail,'student-a'),{'q-b':'unfinished','q-a':'wrong'}));
test('correcting a wrong answer removes it from wrong IDs while preserving explicit grade and class',()=>{
  const payload=gradingPayload(detail,'student-a',{'q-a':'correct','q-b':'wrong'});
  assert.deepEqual(payload.questionIds,['q-b']);assert.equal(payload.classId,'class-a');assert.equal(payload.handoutId,'version-a');assert.equal(payload.marks['q-a'],'correct');
  assert.throws(()=>gradingPayload(detail,'student-b',{}));assert.throws(()=>gradingPayload(detail,'student-a',{'foreign-q':'wrong'}));
});
test('print retries retain request identity and cannot request unsupported duplex',()=>{
  const printer={id:'west',duplex:false};const payload=printPayload(['doc','doc'],printer,1,false,'same-request');
  assert.deepEqual(payload.documentIds,['doc']);assert.equal(payload.requestKey,'same-request');
  assert.throws(()=>printPayload(['doc'],printer,1,true,'same-request'));assert.throws(()=>printPayload([],printer,1,false,'key'));assert.throws(()=>printPayload(['doc'],printer,0,false,'key'));
});
test('download grants may only point to the same HTTP origin',()=>{
  const base='https://www.siyuejn.com/miniapp/api/';
  assert.equal(safeResource('/miniapp/files/token',base),'https://www.siyuejn.com/miniapp/files/token');
  assert.throws(()=>safeResource('javascript:alert(1)',base));assert.throws(()=>safeResource('https://other.example/file',base));assert.throws(()=>safeResource('//other.example/file',base));
});

for(const mode of ['preview','print'])test('classroom '+mode+' keeps export identity and separates printer settings',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('async function sessionPrint('),end=source.indexOf('\nasync function directPage',start);let submit,route,settings,fail=true,html;const buttons=new Map(),stored=new Map(),calls=[];
 const scope={e:x=>x,select:()=>'',button:(label,fn)=>{buttons.set(label,fn);return label},modal:(_title,s)=>html=s,bindForm:(_id,fn)=>submit=fn,document:{querySelector:()=>({reportValidity:()=>true,addEventListener(){}})},workspace:{user:{id:'teacher'},classes:[{id:'class',campusId:'home'}]},sessionStorage:{getItem:k=>stored.get(k),setItem:(k,v)=>stored.set(k,v),removeItem:k=>stored.delete(k)},crypto:{randomUUID:()=> 'stable-key'},dialog:{close(){}},go:(name,p)=>route={name,p},FormData:class{getAll(name){return name==='students'?['student']:['q'];}get(){return 'analysis'}},printDialog:async(ids,context)=>settings={ids,context},api:async(path,method,body)=>{calls.push({path,method,body});if(fail){fail=false;throw Error('lost response');}return{batchId:'batch',documents:[{id:'doc'}]}}};vm.createContext(scope);vm.runInContext(source.slice(start,end),scope);
 await scope.sessionPrint({session:{id:'session',classId:'class'},students:[{id:'student',name:'甲'}],marks:[{studentId:'student',questionIds:['q'],revision:1}],questions:[{id:'q',label:'例1'}]},'student');assert.doesNotMatch(html,/name="printerId"|name="action"/);assert.match(html,/直接打印/);const act=()=>mode==='preview'?buttons.get('生成并预览（可选）')():submit();
 await assert.rejects(act(),/lost response/);await act();assert.equal(calls.length,2);assert.equal(calls[0].body.requestKey,calls[1].body.requestKey);assert.equal(calls[0].path,'/class-sessions/session/exports');
 if(mode==='preview'){assert.equal(route.name,'preview');assert.equal(route.p.batchId,'batch');assert.equal(settings,undefined);assert.equal(stored.size,0)}else{assert.equal(route,undefined);assert.deepEqual(Array.from(settings.ids),['doc']);assert.equal(settings.context.campusId,'home');assert.equal(stored.size,1);settings.context.onSubmitted();assert.equal(stored.size,0)}
});

import {readDownload} from '../server/wwwroot/workbench/download.mjs';
test('download progress follows received chunks and reserves completion for saving',async()=>{
 const values=[];let i=0;const response=new Response(new ReadableStream({pull(c){if(i++===0)c.enqueue(new Uint8Array([1,2]));else if(i===2)c.enqueue(new Uint8Array([3,4]));else c.close();}}),{headers:{'content-length':'4'}});
 const file=await readDownload(response,(percent)=>values.push(percent));assert.deepEqual(values,[0,50,99]);assert.deepEqual([...new Uint8Array(await file.arrayBuffer())],[1,2,3,4]);
});
test('unknown download length remains indeterminate and truncated transfers fail',async()=>{
 const progress=[];await readDownload(new Response('abc'),p=>progress.push(p));assert.ok(progress.every(x=>x===null));
 await assert.rejects(readDownload(new Response('abc',{headers:{'content-length':'4'}}),()=>{}),/未下载完整/);
 await assert.rejects(readDownload(new Response('',{status:403}),()=>{}),/重新下载/);
});

test('the download button displays progress, uses the supplied name and resets after failure',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('async function downloadDocument('),end=source.indexOf('\nasync function openResource',start);
 let savedName,fail=false;const fill={style:{}},label={textContent:''},attrs={};const b={querySelector:s=>s==='.download-fill'?fill:label,classList:{toggle(){}},setAttribute:(k,v)=>attrs[k]=v};
 const scope={api:async()=>({url:'protected',fileName:'甲个性化错题-第4讲-数列.pdf'}),fetch:async()=>{if(fail)throw Error('offline');return new Response('abc',{headers:{'content-length':'3'}})},readDownload,safeResource:x=>x,apiBase:'https://example.test/api',AbortSignal,URL:{createObjectURL:()=> 'blob:download',revokeObjectURL(){}},document:{createElement:()=>({click(){savedName=this.download}})},setTimeout(){},notice(){}};
 vm.createContext(scope);vm.runInContext(source.slice(start,end),scope);await scope.downloadDocument('id','pdf',b);assert.equal(savedName,'甲个性化错题-第4讲-数列.pdf');assert.equal(fill.style.width,'100%');assert.equal(label.textContent,'已下载 PDF');assert.equal(b.disabled,false);assert.equal(attrs['aria-busy'],'false');
 fail=true;await assert.rejects(scope.downloadDocument('id','pdf',b),/offline/);assert.equal(fill.style.width,'0%');assert.equal(label.textContent,'重试下载 PDF');assert.equal(b.disabled,false);
});

test('a failed bound-book slice still shows successful lecture links and a retry action',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('async function bookPage('),end=source.indexOf('\nfunction questionHtml',start);let html='';
 const scope={...queueTestScope,api:async()=>({book:{classId:'class',state:'failed',fileName:'合订.pdf',error:'第二讲失败'},sections:[{handoutId:'good',sequence:1,title:'成功讲次'},{handoutId:'bad',sequence:2,title:'异常讲次'}],handouts:[{id:'good',state:'draft'},{id:'bad',state:'queued'}],sourceUrl:'source'}),button:text=>`<button>${text}</button>`,heading:(title,_back,actions)=>title+actions,paint:s=>html=s,e:x=>x,badge:x=>x,schedule(){},openResource(){},go(){}};
 vm.createContext(scope);vm.runInContext(source.slice(start,end),scope);await scope.bookPage({id:'book'});assert.match(html,/成功讲次/);assert.match(html,/异常讲次/);assert.match(html,/重试未完成讲次/);assert.match(html,/校对题目/);assert.match(html,/查看处理/);
});
test('web session creation uses its class context without a class selector',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('function newClassSession('),end=source.indexOf('\nfunction uploadBooks',start);let submit,request,route,html;
 const scope={currentClassroom:()=>({classId:'class-a',handoutId:'h'}),e:x=>x,select:(label,name,items,selected)=>{assert.equal(selected,'h');return name},field:()=>'',modal:(_title,s)=>html=s,crypto:{randomUUID:()=> 'key'},bindForm:(_id,fn)=>submit=fn,api:async(path,method,body)=>{request={path,body};return{id:'s'}},dialog:{close(){}},go:(name,p)=>route={name,p}};
 vm.createContext(scope);vm.runInContext(source.slice(start,end),scope);scope.newClassSession({schoolClass:{name:'当前班级'},students:[{id:'student'}],lectures:[{id:'l',sequence:1,title:'本讲'}],handouts:[{id:'h',lectureId:'l',version:1,state:'published'}]},'class-a',[]);assert.doesNotMatch(html,/name="classId"/);await submit({handoutId:'h',lessonDate:'2026-10-28'});assert.equal(request.body.classId,'class-a');assert.equal(route.p.id,'s');
});

import {validateRegions} from '../server/wwwroot/workbench/regions.mjs';
test('manual frames reject overlaps and report omitted source area',()=>{
 const a={left:0,top:0,right:1,bottom:.5,label:'例1'},b={left:0,top:.5,right:1,bottom:1,label:'例2'};
 assert.equal(validateRegions([a,b],[0,0,1,1]),false);assert.equal(validateRegions([a],[0,0,1,1]),true);
 assert.throws(()=>validateRegions([a,{...b,top:.4}],[0,0,1,1]),/重叠/);
 assert.throws(()=>validateRegions([{...a,left:-.1}],[0,0,1,1]),/范围/);
 assert.throws(()=>validateRegions([{...a,label:''}],[0,0,1,1]),/题号/);
});


test('PDF interference removal opens a keep-region editor without deleting a block',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('function excludeInterference('),end=source.indexOf('\nasync function frameQuestion',start);let called,deleted=false;
 const scope={frameQuestion:(...args)=>called=args};vm.createContext(scope);vm.runInContext(source.slice(start,end),scope);
 const q={id:'q',blocks:[{index:0,canFrame:true,text:'测1 测2 测3 页脚'}]};scope.excludeInterference('h',q,3,()=>deleted=true);
 assert.equal(called[0],'h');assert.equal(called[1],q);assert.equal(called[3],'trim');assert.equal(called[4],0);assert.equal(deleted,false);
});

test('keep-region editor replaces the selected body and saves only that region',async()=>{
 const {mountRegionEditor}=await import('../server/wwwroot/workbench/regions.mjs');
 const image={complete:false},svg={setPointerCapture(){}},list={};
 const canvas={querySelector:()=>image,getBoundingClientRect:()=>({left:0,top:0,width:1000,height:1000})};
 const controls=new Map([['.region-canvas',canvas],['svg',svg],['.region-list',list],['.region-reason',{value:'仅排除页眉页脚和空白'}],['.region-confirm input',{checked:true}]]);
 const events=new Map();const host={querySelector(selector){if(!controls.has(selector))controls.set(selector,{});return controls.get(selector);},addEventListener(name,fn){events.set(name,fn)}};
 let saved;const errors=[];mountRegionEditor(host,{url:'/source.png',bounds:[0,0,1,1],label:'第9页待校对',mode:'trim',onSave:async value=>saved=value,onError:error=>errors.push(error)});
 const drag=(x,y,r,b)=>{svg.onpointerdown({clientX:x,clientY:y,pointerId:1,preventDefault(){}});svg.onpointermove({clientX:r,clientY:b});svg.onpointerup();};
 drag(0,100,1000,900);drag(100,200,900,800);events.get('change')({target:{dataset:{coordinate:'left',index:'0'},value:'15'}});await controls.get('.region-save').onclick();
 assert.deepEqual(errors,[]);assert.deepEqual(saved.regions,[{left:.15,top:.2,right:.9,bottom:.8,label:'第9页待校对'}]);assert.equal(saved.confirmedCoverage,true);assert.match(host.innerHTML,/保留正文并重新生成预览/);
});

import {readClassroom,saveClassroom} from '../server/wwwroot/workbench/context.mjs';
test('classroom selection survives navigation and is isolated by user and authorization',()=>{
 const values=new Map();const storage={getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v)};const classes=[{id:'a'},{id:'b'}];
 saveClassroom(storage,'teacher1',classes,{classId:'a',sessionId:'s1',handoutId:'h1'});saveClassroom(storage,'teacher1',classes,{classId:'a',className:'A'});
 assert.equal(readClassroom(storage,'teacher1',classes).sessionId,'s1');assert.equal(readClassroom(storage,'teacher2',classes),null);assert.equal(readClassroom(storage,'teacher1',[{id:'b'}]),null);
 saveClassroom(storage,'teacher1',classes,{classId:'b'});assert.equal(readClassroom(storage,'teacher1',classes).sessionId,undefined);assert.equal(saveClassroom(storage,'teacher1',classes,{classId:'forbidden'}),null);
});
test('handout review shows the common PDF guidance once while retaining specific errors',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');const start=source.indexOf('async function handoutPage('),end=source.indexOf('\nasync function sessionsPage(',start);let html='',paints=0;const main={dataset:{}};
 const questions=[1,2].map(i=>({id:'q'+i,label:'【'+i+'】',blocks:[{}],confirmed:false,previews:['image'],issues:[{code:'pdf-layout',message:'PDF 原版面题目，请核对讲次、题号、图文归属和跨页内容；复杂排版请修改源文件',blocking:false},...(i===2?[{code:'pdf-orphan-prefix',message:'内容归属待核对',blocking:true}]:[])]}));
 const scope={...queueTestScope,document:{querySelector:()=>main},api:async()=>({handout:{id:'h',state:'draft',revision:1},questions}),paint:s=>{html=s;paints++},heading:()=>'',button:text=>'<button>'+text+'</button>',editButton:()=>'',canVerifyPdfIntegrity:()=>false,questionHtml:()=>'<img>',e:x=>x,labels:{},empty:()=>'',go(){},render(){},schedule(){}};
 vm.createContext(scope);vm.runInContext(source.slice(source.indexOf('function formulaEvidenceHtml('),source.indexOf('async function integrationsPage('))+source.slice(start,end),scope);await scope.handoutPage({id:'h',classId:'c'});
 assert.equal((html.match(/PDF 原版面题目/g)||[]).length,1);assert.match(html,/内容归属待核对/);assert.equal((html.match(/question-toolbar/g)||[]).length,2);
 questions[0].previews=['image?renewed-token'];await scope.handoutPage({id:'h',classId:'c'});assert.equal(paints,1,'unchanged polling must retain the existing DOM');
 questions[0].confirmed=true;await scope.handoutPage({id:'h',classId:'c'});assert.equal(paints,2,'changed review state must update');
});

import {printerStatus,printerLoad,recommendPrinter} from '../server/wwwroot/workbench/printers.mjs';
test('web printer recommendation matches the phone for freshness, load, duplex and faults',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm'),{createRequire}=await import('node:module');const ts=createRequire(import.meta.url)('typescript'),scope={exports:{}};
 vm.runInNewContext(ts.transpile(fs.readFileSync('miniprogram/services/printer-status.ts','utf8'),{module:ts.ModuleKind.CommonJS}),scope);
 const now=Date.now(),fresh=new Date(now).toISOString(),base={id:'p',name:'west',lastSeen:fresh,statusAt:fresh,loadObservedAt:fresh,status:'ready',jobCount:0,serverQueuedCount:0,serverActiveCount:0,duplex:true};
 const samples=[base,{...base,id:'busy',status:'busy',jobCount:3},{...base,id:'offline',lastSeen:new Date(now-100000).toISOString()},{...base,id:'unreachable',deviceReachable:false,deviceCheckedAt:fresh},{...base,id:'unknown',serverQueuedCount:undefined},{...base,id:'simplex',duplex:false}];
 for(const p of samples){assert.equal(printerStatus(p,now),scope.exports.printerStatus(p,now));assert.equal(JSON.stringify(printerLoad(p,now)),JSON.stringify(scope.exports.printerLoad(p,now)))}
 for(const duplex of [true,false])assert.equal(recommendPrinter(samples,'simplex',duplex,now),scope.exports.recommendPrinter(samples,'simplex',duplex,now));
 assert.equal(printPayload(['doc'],base,99,true,'key').copies,99);assert.throws(()=>printPayload(['doc'],base,100,true,'key'));
});


test('bound-book results retain pending lessons, references and exclusions after partial failure',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');let html='';const calls=[];
 const detail={book:{classId:'class',state:'failed',fileName:'整本.pdf'},sections:[{kind:'lesson',handoutId:'h',sequence:1,title:'第一讲'},{kind:'lesson',sequence:2,title:'尚未创建的第二讲'},{kind:'reference',title:'参考答案'},{kind:'exclude',title:'封面目录'},{kind:'unassigned',title:'归属待定的训练'}],handouts:[{id:'h',state:'draft'}]};
 const scope={...queueTestScope,api:async(path,...rest)=>{calls.push([path,...rest]);return detail;},button:text=>`<button>${text}</button>`,heading:(title,_back,actions)=>title+actions,paint:s=>html=s,e:escapeHtml,schedule(){},go(){}};
 vm.createContext(scope);vm.runInContext(source.slice(source.indexOf('async function bookPage('),source.indexOf('\nfunction questionHtml')),scope);await scope.bookPage({id:'book'});
 for(const title of ['第一讲','尚未创建的第二讲','参考答案','封面目录','归属待定的训练'])assert.ok(html.includes(title),title);
 assert.match(html,/未完成，请重试/);assert.match(html,/待确认归属/);assert.equal((html.match(/已确认排除/g)||[]).length,1);assert.doesNotMatch(html,/0 道题|0 道疑点|0 道已有预览/);assert.deepEqual(calls,[['/books/book']]);
 detail.book.state='splitting';detail.progress={stage:'questions',current:2,total:8,message:'正在拆分第三讲'};detail.handouts[0]={id:'h',state:'draft',questionCount:12,reviewCount:2,previewCount:10};await scope.bookPage({id:'book'});
 assert.match(html,/2 \/ 8/);assert.match(html,/12 道题 · 2 道疑点需核对 · 10 道已有预览/);assert.match(html,/等待拆题/);
 detail.progress={current:12,total:8};await scope.bookPage({id:'book'});assert.doesNotMatch(html,/<progress/);
 detail.book.state='split';detail.progress={stage:'splitting',current:8,total:8,message:'正在归并讲次'};await scope.bookPage({id:'book'});assert.match(html,/<strong class="grow">查看各讲处理结果<\/strong>/);assert.doesNotMatch(html,/正在归并讲次/);
});

test('book upload preserves fixed PDF defaults and offers only named or legacy recognition',async()=>{
 const fs=await import('node:fs'),vm=await import('node:vm');const source=fs.readFileSync('server/wwwroot/workbench/app.js','utf8');let html,submit;const calls=[],chosen=[];
 const results={innerHTML:'',insertAdjacentHTML(_where,s){this.innerHTML+=s;}};const primary={};let opened=0;const picker={addEventListener(){},click(){opened++;}};const uploadForm={elements:{files:picker},querySelector:()=>primary};
 const scope={modal:(_title,s)=>html=s,e:escapeHtml,select:(_label,_name,options,selected)=>{chosen.push({options,selected});return '<select></select>';},filePicker:()=>'',bindForm:(_id,fn)=>submit=fn,document:{querySelector:selector=>selector==='#books-upload'?uploadForm:results},dialog:{inert:true,close(){}},go(){},bindBookDrop(){},bindRecognitionChoice:form=>{const mode=form.elements.recognitionMode,force=form.elements.forceReparse;if(mode&&force)mode.addEventListener('change',()=>{force.checked=true;});},button:text=>text,notice(){},checkFile(){},FormData:class{constructor(){this.values={};}append(k,v){this.values[k]=v;}},api:async(path,method,body)=>{if(path==='/workspace')return {};calls.push({path,method,body:body.values});return{id:'book'};}};
 vm.createContext(scope);const modeStart=source.indexOf('function recognitionModeField('),modeEnd=source.indexOf('\nfunction uploadBooks(',modeStart);vm.runInContext(source.slice(modeStart,modeEnd),scope);const start=source.indexOf('function uploadBooks('),end=source.indexOf('\nfunction ',start+1);vm.runInContext(source.slice(start,end),scope);
 scope.uploadBooks('class',[{id:'math',name:'数学'}]);assert.match(html,/<details[^>]*><summary>更多设置<\/summary>/);assert.match(html,/上传并查看讲义/);assert.equal(chosen[0].selected,'pages');assert.equal(chosen[1].selected,'named');assert.deepEqual(chosen[1].options.map(x=>x.id).join(','),'named,legacy');assert.equal(opened,1);
 const form={elements:{files:{files:[{name:'第一本.pdf',size:10,lastModified:1}]}},querySelector:()=>primary};await submit({courseId:'math'},form);assert.equal(calls[0].body.recognitionMode,'named');assert.equal(calls[0].method,'POST');
 form.elements.files.files=[{name:'第二本.pdf',size:10,lastModified:1}];await submit({courseId:'math',recognitionMode:'legacy'},form);assert.equal(calls[1].body.recognitionMode,'legacy');assert.ok(calls.every(c=>c.path==='/classes/class/books?courseId=math'));
});

test('manual split saves sparse boxes and labels without coverage controls',async()=>{
 const {mountRegionEditor}=await import('../server/wwwroot/workbench/regions.mjs');
 const image={complete:false},svg={setPointerCapture(){}},list={};
 const canvas={querySelector:()=>image,getBoundingClientRect:()=>({left:0,top:0,width:1000,height:1000})};
 const controls=new Map([['.region-canvas',canvas],['svg',svg],['.region-list',list]]),events=new Map();
 const host={querySelector(selector){assert(!['.region-reason','.region-confirm input'].includes(selector),'split must not read coverage controls');if(!controls.has(selector))controls.set(selector,{});return controls.get(selector);},addEventListener(name,fn){events.set(name,fn)}};
 let saved;const errors=[];mountRegionEditor(host,{url:'/source.png',bounds:[0,0,1,1],label:'',onSave:async value=>saved=value,onError:error=>errors.push(error.message)});
 const drag=(x,y,r,b)=>{svg.onpointerdown({clientX:x,clientY:y,pointerId:1,preventDefault(){}});svg.onpointermove({clientX:r,clientY:b});svg.onpointerup();};
 drag(50,100,450,300);await controls.get('.region-save').onclick();assert.match(errors.pop(),/题号/);assert.equal(saved,undefined);
 events.get('input')({target:{dataset:{label:'0'},value:'例1'}});
 drag(550,500,950,700);events.get('input')({target:{dataset:{label:'1'},value:'练2'}});
 await controls.get('.region-save').onclick();assert.deepEqual(errors,[]);assert.equal(saved.regions.length,2);assert.deepEqual(Object.keys(saved),['regions']);
 assert.equal(saved.regions[0].label,'例1');assert.equal(saved.regions[1].label,'练2');assert(!host.innerHTML.includes('region-confirm'));assert(!host.innerHTML.includes('region-reason'));assert.match(host.innerHTML,/无需覆盖整页/);
});
