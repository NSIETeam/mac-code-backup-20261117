const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
function load(name,deps={},wx={},component=false){let def;const src=ts.transpileModule(fs.readFileSync('miniprogram/'+name+'.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2017}}).outputText;vm.runInNewContext(src,{exports:{},require:()=>({run:f=>f(),...deps}),Page:x=>def=x,Component:x=>def=x,wx,getCurrentPages:()=>wx.pages||[]});return {...def,...(component?def.methods:{}),data:structuredClone(def.data),setData(v){Object.assign(this.data,v)}};}
const mat=(id,path,lectures=[])=>({id,courseId:'c1',lectureIds:lectures,role:'handout',audience:'student',title:'教材'+id,fileName:path.split('/').at(-1),format:'pdf',materialVersion:1,bytes:30,originalRelativePath:path});
const ev=id=>({currentTarget:{dataset:{id}}});
test('shared library preserves folders and does not mix sibling prefix paths',async()=>{
 const p=load('pages/material-library/material-library',{api:async()=>({revision:1,courses:[{courseId:'c1',courseName:'初一',materials:[mat('a','数学/一讲.pdf'),mat('b','数学扩展/二讲.pdf'),mat('c','数学/练习/三讲.pdf')]}]})});await p.load();assert.deepEqual(Array.from(p.data.folders,x=>x.name),['数学','数学扩展']);p.data.path='数学';p.filter();assert.deepEqual(Array.from(p.data.files,x=>x.id),['a']);assert.deepEqual(Array.from(p.data.folders,x=>x.path),['数学/练习']);p.data.query='三讲';p.filter();assert.deepEqual(Array.from(p.data.files,x=>x.id),['c']);assert.equal(p.data.files[0].originalRelativePath,'数学/练习/三讲.pdf');
});
test('course materials filter both lecture association and source role without treating general files as lecture files',()=>{
 const p=load('pages/course-materials/course-materials');p.response={courseId:'c1',courseName:'课程',lectures:[{id:'l1',sequence:1,title:'第一讲'},{id:'l2',sequence:2,title:'第二讲'}],materials:[mat('a','整册.pdf',['l1','l2']),mat('b','第二讲.pdf',['l2']),mat('c','通用.pdf',[])]};Object.assign(p.data,{lectureId:'l1',role:'handout',query:'整册'});p.filter();assert.deepEqual(Array.from(p.data.cards,x=>x.id),['a']);assert.match(p.data.cards[0].context,/第1讲、 第2讲|第1讲、第二讲|第1讲、第2讲/);assert.equal(p.data.cards[0].downloadLabel,'原稿下载');
});
test('course material viewing passes the original course scope and encodes filenames',()=>{
 let url;const p=load('pages/course-materials/course-materials',{}, {navigateTo:x=>url=x.url});p.response={materials:[mat('m&1','资料/讲义 & 答案.pdf',['l1'])]};p.view(ev('m&1'));assert.match(url,/id=m%261/);assert.match(url,/sourceScope=course/);assert.match(url,/courseId=c1/);assert.ok(url.includes(encodeURIComponent('讲义 & 答案.pdf')));
});
test('edition summary selects the explicit current pointer and preserves old editions',async()=>{
 const p=load('pages/material-edition/material-edition',{api:async()=>({currentEditionId:'new',history:[{id:'old',number:1,createdAt:'2026-11-01',docxReady:true,pdfReady:true},{id:'new',number:2,createdAt:'2026-11-05',docxReady:true,pdfReady:true}]})});p.data.materialId='m';await p.load();assert.equal(p.data.current.id,'new');assert.deepEqual(Array.from(p.data.history,x=>x.id),['old']);
});
test('a missing current version is explicit and never represented by the original source',async()=>{
 const p=load('pages/material-edition/material-edition',{api:async()=>({currentEditionId:null,history:[]})});p.data.materialId='m';await p.load();assert.equal(p.data.current,null);assert.equal(p.data.failed,false);
});
test('an unavailable edition download does not silently download the original',async()=>{
 const calls=[];const p=load('pages/material-edition/material-edition',{api:async path=>{calls.push(path);throw Object.assign(new Error('not ready'),{status:409})},openFile:()=>{throw Error('must not open')} });await assert.rejects(p.editionFile({currentTarget:{dataset:{id:'e',format:'pdf'}}}),/not ready/);assert.deepEqual(calls,['/material-versions/e/file?format=pdf']);assert.equal(p.data.busy,false);
});
test('course original download keeps course permissions while library download uses the shared grant route',async()=>{
 const calls=[],opened=[];const p=load('pages/material-edition/material-edition',{api:async path=>{calls.push(path);return{format:'pdf',url:'signed',fileName:'原稿.pdf'}},openFile:async(...x)=>opened.push(x)});Object.assign(p.data,{materialId:'m',courseId:'c',sourceScope:'course'});await p.original();p.data.sourceScope='library';await p.original();assert.deepEqual(calls,['/courses/c/materials/m/file','/course-material-library/c/m/file']);assert.equal(opened.length,2);
});
test('source-format companions copy a short-lived grant link without pretending to be Word',async()=>{
 let copied,open=false;const p=load('pages/material-edition/material-edition',{api:async()=>({format:'wsd',url:'signed-wsd',fileName:'原稿.wsd'}),openFile:async()=>open=true},{setClipboardData:x=>{copied=x.data;x.success()},showToast(){}});Object.assign(p.data,{materialId:'m',courseId:'c'});await p.original();assert.equal(copied,'signed-wsd');assert.equal(open,false);
});
test('compact navigation reserves the real system capsule and returns safely from a direct entry',()=>{
 let url,back;const p=load('components/compact-nav/compact-nav',{}, {getMenuButtonBoundingClientRect:()=>({top:27,left:276,height:32}),getSystemInfoSync:()=>({windowWidth:375,statusBarHeight:20}),reLaunch:x=>url=x.url,navigateBack:x=>back=x.delta},true);p.lifetimes.attached.call(p);assert.equal(p.data.top,27);assert.equal(p.data.right,107);assert.equal(p.data.height,32);p.back();assert.equal(url,'/pages/home/home');assert.equal(back,undefined);
});
test('new pages are registered and use compact navigation with original download visible after edition failure',()=>{
 const app=JSON.parse(fs.readFileSync('miniprogram/app.json'));assert.equal(new Set(app.pages).size,app.pages.length);assert.equal(app.tabBar,undefined);for(const name of ['material-library','course-materials','material-edition']){const stem='pages/'+name+'/'+name;assert.ok(app.pages.includes(stem));for(const ext of ['ts','json','wxml','scss'])assert.ok(fs.existsSync('miniprogram/'+stem+'.'+ext));const config=JSON.parse(fs.readFileSync('miniprogram/'+stem+'.json'));assert.equal(config.navigationStyle,'custom');assert.equal(config.usingComponents['compact-nav'],'/components/compact-nav/compact-nav');const wxml=fs.readFileSync('miniprogram/'+stem+'.wxml','utf8');assert.match(wxml,/<compact-nav/);assert.doesNotMatch(wxml,/class="hero|class="title/);}const view=fs.readFileSync('miniprogram/pages/material-edition/material-edition.wxml','utf8');assert.ok(view.indexOf('bindtap="original"')>view.lastIndexOf('</block>'));assert.match(view,/查看整册 PDF/);
});
