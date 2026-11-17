import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {libraryFilters,libraryQuery,libraryTarget,librarySource,libraryReused,libraryItemMarkup,libraryDetailMarkup,sortLibraryChildren,renderHandoutLibrary} from '../.local/acceptance/handout-library-20261104/web/handout-library.mjs';
const base='https://example.test/miniapp/api/';
const item={kind:'book',id:'b',fileName:'第一册.pdf',staffName:'张老师',className:'一班',classId:'c',courseName:'数学',status:'review',lectureCount:2,questionCount:10,canOpen:true,openUrl:'/miniapp/workbench/#book?id=b&classId=c',createdAt:'2026-11-04T00:00:00Z'};
const data={items:[item],pagination:{page:1,pageSize:20,total:45,totalPages:3},stats:{uploads:45,teachers:2,classes:3,lectures:60},filters:{teachers:[{id:'s',name:'张老师',archived:false}],classes:[{id:'c',name:'一班'}],courses:[{id:'m',name:'数学'}],statuses:[{value:'review',label:'待校对'}]}};
const ctxBase={role:'admin',apiBase:base,go(){},openResource(){},downloadCsv(){},heading:(title,_back,actions)=>title+actions,button:(label)=>`<button>${label}</button>`};
function fixture({detailFailure=false}={}){
 let html='',detailCalls=0;const calls=[],buttons=new Map(),events={},formEvents={},selectEvents={};
 const content={innerHTML:''},row={dataset:{libraryKind:'book',libraryId:'b'},isConnected:true,open:false,querySelector:()=>content,addEventListener:(name,fn)=>events[name]=fn};
 const form={elements:Object.fromEntries(['q','staffId','classId','courseId','status'].map(k=>[k,{value:''}])),addEventListener:(name,fn)=>formEvents[name]=fn,querySelectorAll:()=>[{addEventListener:(name,fn)=>selectEvents[name]=fn}]};
 const context={...ctxBase,api:async path=>{calls.push(path);if(path.startsWith('/handout-library?'))return data;detailCalls++;if(detailFailure&&detailCalls===1)throw Error('暂时中断 <unsafe>');return {item,children:[{id:'h',sequence:2,title:'第二讲',version:1,state:'draft',questionCount:10,confirmedCount:3,canOpen:true,openUrl:'/miniapp/workbench/#handout?id=h&classId=c'}]};},paint:s=>html=s,document:{querySelector:()=>form,querySelectorAll:()=>[row]},button:(label,fn,style,disabled)=>{buttons.set(label,{fn,disabled});return `<button>${label}</button>`;}};
 return {context,calls,buttons,form,row,events,formEvents,selectEvents,content,html:()=>html};
}

test('library filters use API contract and bounded search, reset malformed pagination, omit pagination from CSV',()=>{
 assert.deepEqual(libraryFilters({q:'  数学 ',staffId:'s',status:'review',page:'2',pageSize:999,untrusted:'x'}),{q:'数学',staffId:'s',status:'review',page:2,pageSize:20});
 assert.equal(libraryFilters({q:'x'.repeat(500),status:'all',page:-2}).q.length,120);
 assert.equal(libraryFilters({page:'Infinity',status:'admin'}).page,1);
 assert.equal(libraryQuery({staffId:'s',status:'failed',page:4},true),'staffId=s&status=failed');
});
test('only same-origin workbench routes and source URLs are accepted',()=>{
 assert.deepEqual(libraryTarget('/miniapp/workbench/#book?id=b&classId=c',base),{name:'book',p:{id:'b',classId:'c'}});
 assert.deepEqual(libraryTarget('#handout?id=h',base),{name:'handout',p:{id:'h'}});
 for(const url of ['javascript:alert(1)','https://evil.test/miniapp/workbench/#book?id=x','/miniapp/admin/#book?id=x','#admin?id=x','#book'])assert.equal(libraryTarget(url,base),null);
 assert.equal(librarySource('/miniapp/files/grant',base),'https://example.test/miniapp/files/grant');
 assert.equal(librarySource('https://evil.test/file',base),null);
});
test('untrusted metadata is escaped, source-unrecorded remains honest, reuse only appears on explicit confirmed state',()=>{
 const html=libraryItemMarkup({...item,fileName:'<img src=x onerror=alert(1)>',staffName:null,staffArchived:false,reuse:null,createdAt:null});
 assert.doesNotMatch(html,/<img src/);assert.match(html,/&lt;img/);assert.match(html,/来源未记录/);assert.match(html,/上传时间未记录/);assert.doesNotMatch(html,/已复用解析/);
 assert.equal(libraryReused({state:'pending'}),false);assert.equal(libraryReused({state:'reused'}),true);assert.match(libraryItemMarkup({...item,reuse:{state:'reused'}}),/已复用解析/);
});
test('detail sorts numeric lecture sequences and retains failed and archived children with no unsafe navigation',()=>{
 const clicked=[],ctx={...ctxBase,button:(label,fn)=>{clicked.push(label);return `<button>${label}</button>`;}};
 const children=[{id:'ten',sequence:10,title:'十讲',state:'failed',canOpen:true,openUrl:'#handout?id=ten'},{id:'two',sequence:2,title:'二讲<script>',state:'draft',canOpen:false,openUrl:'#handout?id=two'}];
 assert.deepEqual(sortLibraryChildren(children).map(h=>h.sequence),[2,10]);
 const html=libraryDetailMarkup({item:{...item,canOpen:false},children,originalUrl:'https://evil.test/file'},ctx);
 assert.ok(html.indexOf('二讲')<html.indexOf('十讲'));assert.match(html,/处理失败/);assert.match(html,/仅归档信息/);assert.doesNotMatch(html,/<script>|下载原稿/);assert.deepEqual(clicked,['查看处理']);
});
test('teacher access is rejected before querying the admin catalog',async()=>{
 let calls=0;await assert.rejects(()=>renderHandoutLibrary({}, {...ctxBase,role:'teacher',api:()=>{calls++;}}),e=>e.status===403);assert.equal(calls,0);
});
test('catalog counts original uploads, expands lazily, retains filters and never creates or publishes work',async()=>{
 const f=fixture();await renderHandoutLibrary({q:'数学',status:'review'},f.context);
 assert.match(f.html(),/<strong>45<\/strong> 份原始上传/);assert.match(f.html(),/2 位上传老师/);assert.match(f.html(),/相同文件可复用已有解析/);assert.match(f.calls[0],/status=review/);assert.equal(f.calls.length,1);
 f.row.open=true;await f.events.toggle();await f.events.toggle();assert.equal(f.calls.length,2);assert.match(f.content.innerHTML,/继续校对/);
 let route;f.context.go=(name,p)=>route={name,p};f.form.elements.q.value='新题';f.form.elements.staffId.value='s';f.formEvents.submit({preventDefault(){}});assert.deepEqual(route,{name:'handout-library',p:{page:1,q:'新题',staffId:'s'}});
 f.buttons.get('下一页').fn();assert.equal(route.p.page,2);assert.equal(route.p.status,'review');
 assert.ok(f.calls.every(p=>p.startsWith('/handout-library')));assert.equal(f.buttons.get('上一页').disabled,true);
});
test('failed detail request remains retryable, escaped and does not silently omit the upload',async()=>{
 const f=fixture({detailFailure:true});await renderHandoutLibrary({},f.context);f.row.open=true;await f.events.toggle();assert.match(f.content.innerHTML,/&lt;unsafe&gt;/);await f.buttons.get('重试').fn();assert.match(f.content.innerHTML,/第二讲/);assert.equal(f.calls.length,3);
});
test('detached rows do not receive late detail content after navigation',async()=>{
 const f=fixture();await renderHandoutLibrary({},f.context);f.row.open=true;f.row.isConnected=false;await f.events.toggle();assert.doesNotMatch(f.content.innerHTML,/第二讲/);
});
const candidate=fs.readFileSync('.local/acceptance/handout-library-20261104/web/app.js','utf8');const ts=createRequire(import.meta.url)('typescript');const tree=ts.createSourceFile('app.js',candidate,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);const code=name=>tree.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name?.text===name).getText(tree);
test('CSV download uses authenticated CSV response and reports JSON errors instead of saving them',async()=>{
 let downloaded,failed=false;const scope={apiBase:base,token:()=> 'test-token',AbortSignal,blobs:[],URL:{createObjectURL:()=> 'blob:csv'},notice(){},document:{createElement:()=>({click(){downloaded=this.download;}})},fetch:async(url,options)=>{assert.match(url,/handout-library\/export.csv\?staffId=s/);assert.equal(options.headers.Authorization,'Bearer test-token');return failed?new Response(JSON.stringify({message:'请缩小筛选'}),{status:400,headers:{'content-type':'application/json'}}):new Response('文件名,老师\n第一册,张老师',{headers:{'content-type':'text/csv'}});}};
 vm.createContext(scope);vm.runInContext(code('downloadHandoutLibraryCsv'),scope);await scope.downloadHandoutLibraryCsv('staffId=s');assert.match(downloaded,/全部讲义-.*\.csv$/);failed=true;await assert.rejects(()=>scope.downloadHandoutLibraryCsv('staffId=s'),/请缩小筛选/);
});
test('minimal integration preserves queue review, PDF policy and all unrelated deployed functions',()=>{
 const previous=fs.readFileSync('.local/acceptance/handout-library-20261104/web-before/app.js','utf8'),before=ts.createSourceFile('before.js',previous,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS),changed=new Set(['home','settings','search','render','recognitionModeField','uploadBooks','replaceHandout']);
 for(const node of before.statements.filter(n=>ts.isFunctionDeclaration(n)))if(!changed.has(node.name.text))assert.equal(code(node.name.text),node.getText(before),node.name.text);
 assert.match(code('home'),/workspace\.user\.role==='admin'\?button\('全部讲义'/);assert.match(code('render'),/'handout-library':handoutLibraryPage/);assert.match(code('search'),/全部讲义 讲义库 上传资料/);
 assert.ok(fs.readFileSync('.local/acceptance/handout-library-20261104/web/style.css','utf8').startsWith(fs.readFileSync('.local/acceptance/handout-library-20261104/web-before/style.css','utf8')));
});

test('book upload defaults to reusable results, explicit mode changes force reparse, and real reuse reports the existing result without guessing its state',async()=>{
 let submit,html,modeChange,lastNotice;const calls=[],force={checked:false},mode={addEventListener:(_event,fn)=>modeChange=fn},primary={},picker={files:[{name:'册一.pdf',size:10,lastModified:1}],addEventListener(){},click(){}};
 const form={elements:{files:picker,forceReparse:force,recognitionMode:mode},querySelector:()=>primary},results={innerHTML:'',insertAdjacentHTML(_pos,value){this.innerHTML+=value;}};
 const scope={modal:(_t,s)=>html=s,e:x=>x,select:()=>'',filePicker:()=>'',bindForm:(_id,fn)=>submit=fn,document:{querySelector:s=>s==='#books-upload'?form:results},dialog:{inert:true},bindBookDrop(){},button:t=>t,notice:v=>lastNotice=v,checkFile(){},FormData:class{constructor(){this.values={}}append(k,v){this.values[k]=v;}},api:async(p,_method,body)=>{if(p==='/workspace')return {};calls.push(body.values);return{id:'b'+calls.length,reuse:body.values.forceReparse==='false'?{state:'reused',reusedLectures:2,reusedQuestions:30,sourceRecognitionMode:'legacy'}:null};}};
 vm.createContext(scope);vm.runInContext(['recognitionModeField','bindRecognitionChoice','uploadBooks'].map(code).join('\n'),scope);
 scope.uploadBooks('c',[{id:'m'}]);assert.match(html,/重新解析（不使用已有结果）/);assert.equal(force.checked,false);await submit({recognitionMode:'named'},form);assert.equal(calls[0].forceReparse,'false');assert.match(lastNotice,/已复用 2 讲 \/ 30 题，已打开已有解析结果/);assert.doesNotMatch(lastNotice,/已发布/);assert.match(results.innerHTML,/已打开已有解析结果/);
 modeChange();assert.equal(force.checked,true);picker.files=[{name:'册二.pdf',size:10,lastModified:2}];await submit({recognitionMode:'legacy'},form);assert.equal(calls[1].forceReparse,'true');assert.equal(calls[1].recognitionMode,'legacy');
});
test('independent handout replacement sends explicit reparse choice without auto publishing',async()=>{
 let submit,body,route,message;const form={elements:{recognitionMode:{addEventListener(){}},forceReparse:{checked:true},file:{files:[{name:'单讲.pdf'}]}}};
 const scope={modal(){},recognitionModeField:()=>'',filePicker:()=>'',document:{querySelector:()=>form},bindForm:(_id,fn)=>submit=fn,checkFile(){},FormData:class{constructor(){this.values={}}append(k,v){this.values[k]=v;}},api:async(p,m,fd)=>{assert.equal(p,'/lectures/l/handouts');assert.equal(m,'POST');body=fd.values;return{id:'h',reuse:{state:'reused',reusedLectures:1,reusedQuestions:8}};},dialog:{close(){}},go:(name,p)=>route={name,p},notice:value=>message=value};
 vm.createContext(scope);vm.runInContext(code('bindRecognitionChoice')+'\n'+code('replaceHandout'),scope);scope.replaceHandout('l','c');await submit({recognitionMode:'legacy'},form);assert.equal(body.forceReparse,'true');assert.equal(body.recognitionMode,'legacy');assert.equal(route.name,'handout');assert.equal(route.p.id,'h');assert.match(message,/已打开已有解析结果/);
});
