const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
function moduleFor(path){const exports={};vm.runInNewContext(ts.transpileModule(fs.readFileSync(path,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports});return exports;}
const notation=moduleFor('miniprogram/services/question-bank.ts');
function page(api,wx={}){let definition;vm.runInNewContext(ts.transpileModule(fs.readFileSync('miniprogram/pages/question-bank/question-bank.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{Error,Page:x=>definition=x,exports:{},require:name=>name.endsWith('question-bank')?notation:{api,user:()=>({id:'teacher'}),settings:{baseUrl:'https://fixture.example/miniapp'},token:()=> 'fixture-token'},wx:{...wx}});return{...definition,data:structuredClone(definition.data),setData(x){for(const [key,value]of Object.entries(x)){const path=key.replace(/\[(\d+)\]/g,'.$1').split('.');let target=this.data;for(const part of path.slice(0,-1))target=target[part];target[path.at(-1)]=value}}};}
const result=(ids=['a','b','c','d','e'])=>({status:'matched',message:'已返回同类题',matches:ids.map(id=>({question:{id,stem:'求 $\\frac{1}{2}$',answer:'$1$',analysis:'说明',sourceKind:'original',materialText:'完整阅读材料',diagram:{width:360,height:270,shapes:[]}},reasons:['同知识点']}))});
test('topic lookup sends subject stage type and five-question limit; answers and reading bodies start collapsed',async()=>{
 const calls=[],p=page(async(path,method,body)=>{calls.push({path,method,body});return result()});p.data.topic='点线距离';await p.search();
 assert.equal(calls[0].path,'/question-bank/recommendations');assert.equal(calls[0].body.limit,5);assert.equal(calls[0].body.subject,'数学');assert.equal(calls[0].body.stage,'高中');assert.equal(p.data.cards.length,5);
 const cards=p.data.cards,diagram=p.data.cards[0].question.diagram;assert.equal(p.data.cards[0].materialDisplay,'');p.toggle({currentTarget:{dataset:{index:0,field:'materialOpen'}}});assert.equal(p.data.cards[0].materialDisplay,'完整阅读材料');p.toggle({currentTarget:{dataset:{index:0,field:'materialOpen'}}});assert.equal(p.data.cards[0].answerOpen,false);assert.equal(p.data.cards[0].materialOpen,false);assert.equal(p.data.cards[0].question.diagram.width,360);assert.equal(p.data.cards,cards);assert.equal(p.data.cards[0].question.diagram,diagram);
});
test('next batch excludes previous results and field edits reset exclusions and discard stale responses',async()=>{
 const calls=[],p=page(async(_path,_method,body)=>{calls.push(body);return result(calls.length===1?['a','b']:['c','d'])});p.data.topic='椭圆';await p.search();await p.next();assert.deepEqual(Array.from(calls[1].excludeIds),['a','b']);
 let finish;const old=page(()=>new Promise(r=>finish=r));old.data.topic='椭圆';const pending=old.search();old.input({detail:{value:'双曲线'}});finish(result());await pending;assert.equal(old.data.cards.length,0);assert.equal(old.data.topic,'双曲线');assert.equal(old.data.busy,false);
});
test('empty input makes no request; insufficient and failed lookups do not retain prior success',async()=>{
 let calls=0;const p=page(async()=>{calls++;return result()});await p.search();assert.equal(calls,0);p.data.topic='未知';await p.search();p.data.topic='另一个类型';p.invalidate();assert.equal(p.data.cards.length,0);
 const failed=page(async()=>{throw Error('连接失败')});failed.data.topic='椭圆';await failed.search();assert.equal(failed.data.message,'连接失败');assert.equal(failed.data.canNext,false);
});
test('source permission failures prevent recommendations and background requests cannot alter a departed page',async()=>{
 const p=page(async()=>{throw Error('无权访问此班级课程')});await p.loadSource({sessionId:'s',studentId:'u',questionId:'q'});assert.equal(p.data.ready,false);assert.match(p.data.message,/无权/);
 let finish;const old=page(()=>new Promise(r=>finish=r));old.data.topic='点线距离';const pending=old.search();old.onHide();finish(result());await pending;assert.equal(old.data.cards.length,0);
});
test('copying a question preserves original LaTeX material and attribution without revealing hidden answers',async()=>{
 let copied;const p=page(async()=>result(),{setClipboardData:x=>copied=x.data});p.data.topic='比例';await p.search();p.copy({currentTarget:{dataset:{index:0}}});assert.match(copied,/\\frac\{1\}\{2\}/);assert.match(copied,/完整阅读材料/);assert.doesNotMatch(copied,/参考答案/);
});
test('native formula notation handles nested fractions and roots while retaining unknown commands',()=>{
 assert.equal(notation.readableMath('$\\frac{1}{\\sqrt{5}}\\pi$'),'(1)/(√(5))π');assert.equal(notation.readableMath('$x^{12}+\\unknown{a}$'),'x^(12)+\\unknown{a}');
});
test('the topic page is available from the materials library and the wrong-question context',()=>{
 assert.ok(JSON.parse(fs.readFileSync('miniprogram/app.json')).pages.includes('pages/question-bank/question-bank'));
 assert.match(fs.readFileSync('miniprogram/pages/material-library/material-library.wxml','utf8'),/bindtap="questionBank"/);assert.match(fs.readFileSync('miniprogram/pages/session-mark/session-mark.wxml','utf8'),/按知识点找同类题/);
});
test('native diagram draws checked primitives with Canvas 2D after readiness without rebinding canvas dimensions',()=>{
 let definition,strokes=0,texts=0;const lines=[],scales=[],patches=[],canvas={width:0,height:0,getContext:type=>{assert.equal(type,'2d');return context}};
 const context={scale(...v){scales.push(v)},fillRect(){},setLineDash(){},beginPath(){},moveTo(...v){lines.push(v)},lineTo(...v){lines.push(v)},stroke(){strokes++},arc(){},fill(){},fillText(){texts++}};
 vm.runInNewContext(ts.transpileModule(fs.readFileSync('miniprogram/components/bank-diagram/bank-diagram.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,{exports:{},Component:x=>definition=x,wx:{getSystemInfoSync:()=>({windowWidth:390,pixelRatio:2})}});
 const c={...definition.methods,data:structuredClone(definition.data),properties:{diagram:{width:360,height:270,shapes:[{kind:'line',values:[1,2,3,4],color:'#315e52',width:1.6,dash:false},{kind:'text',values:[10,20],text:'P',color:'#315e52',fontSize:11,align:'left'}]}},setData(values){patches.push(values);Object.assign(this.data,values)},createSelectorQuery(){return{select(selector){assert.equal(selector,'#diagram');return this},fields(options,done){assert.equal(options.node,true);done({node:canvas,width:318,height:238.5});return this},exec(){}}}};
 definition.observers.diagram.call(c);assert.equal(strokes,0);definition.lifetimes.ready.call(c);assert.equal(strokes,1);assert.equal(texts,1);assert.deepEqual(lines,[[1,2],[3,4]]);assert.equal(canvas.width,636);assert.equal(canvas.height,477);assert.equal(scales[0][0],2*318/360);assert.equal(patches.length,0);
});

const knowledge=[{id:'数学.unit',name:'解析几何',parentId:'数学',aliases:[]},{id:'数学.unit.line',name:'直线与圆',parentId:'数学.unit',aliases:[]},{id:'数学.unit.line.distance',name:'点到直线距离',parentId:'数学.unit.line',aliases:['点线距离']}];
test('knowledge navigation follows parent ids, searches aliases, and tolerates a malformed cycle',()=>{
 assert.equal(notation.bankKnowledgePath(knowledge,'数学.unit.line.distance'),'解析几何 › 直线与圆 › 点到直线距离');
 assert.deepEqual(Array.from(notation.bankKnowledgeRows(knowledge,'数学').map(x=>x.name)),['解析几何']);
 assert.equal(notation.bankKnowledgeRows(knowledge,'数学','点线距离')[0].id,'数学.unit.line.distance');
 assert.doesNotThrow(()=>notation.bankKnowledgePath([{id:'a',name:'A',parentId:'b',aliases:[]},{id:'b',name:'B',parentId:'a',aliases:[]}],'a'));
});
test('directory drill-down stays local; only choosing a leaf requests five questions',async()=>{
 const calls=[],p=page(async(path,method,body)=>{calls.push({path,body});return path.includes('/knowledge')?knowledge:result()});await p.suggest();p.directory();
 p.knowledgeChoose({currentTarget:{dataset:{id:'数学.unit'}}});assert.equal(p.data.knowledgeParent,'数学.unit');assert.equal(calls.length,1);
 p.knowledgeChoose({currentTarget:{dataset:{id:'数学.unit.line'}}});assert.equal(p.data.knowledgeRows[0].name,'点到直线距离');
 await p.knowledgeChoose({currentTarget:{dataset:{id:'数学.unit.line.distance'}}});assert.equal(p.data.topic,'点到直线距离');assert.equal(p.data.knowledgeOpen,false);assert.equal(calls[1].body.limit,5);
 await p.suggest();assert.equal(calls.length,2);p.knowledgeBack();assert.equal(p.data.knowledgeParent,'数学.unit');
});
test('stale catalog replies cannot replace the selected subject and subject edits clear the old topic',async()=>{
 let finish;const p=page(()=>new Promise(r=>finish=r));const pending=p.suggest();p.data.topic='点线距离';p.onHide();finish(knowledge);await pending;assert.equal(p.data.knowledgeRows.length,0);
 const q=page(async()=>[]);q.data.topic='点线距离';q.filter({currentTarget:{dataset:{field:'subjectIndex'}},detail:{value:'4'}});assert.equal(q.data.topic,'');assert.equal(q.data.subjectIndex,4);
});

test('shared photo questions download only bank-owned images with employee authorization',async()=>{
 const downloads=[],r=result(['photo']);r.matches[0].question.images=['question-bank/intakes/11111111-1111-1111-1111-111111111111/assets/page-1.png','https://other.example/private.png'];
 const p=page(async()=>r,{downloadFile:x=>downloads.push(x)});p.data.topic='图形';await p.search();assert.equal(downloads.length,1);assert.equal(downloads[0].header.Authorization,'Bearer fixture-token');assert.match(downloads[0].url,/^https:\/\/fixture.example\/miniapp\/api\/question-bank\/intakes\//);downloads[0].success({statusCode:200,tempFilePath:'wxfile://photo'});assert.deepEqual(Array.from(p.data.cards[0].imageFiles),['wxfile://photo']);
 const old=p.data.cards[0];p.input({detail:{value:'另一个知识点'}});downloads[0].success({statusCode:200,tempFilePath:'wxfile://stale'});assert.equal(old.imageFiles.length,1);
});
const enrollment=(subject='数学',id='course-m')=>({classId:'class-a',courseId:id,className:'高一班',courseName:'高中'+subject,subject,stage:'高中'});
test('direct learner entry needs no topic, filters or source context; a single subject recommends immediately',async()=>{
 const calls=[],sheets=[],p=page(async(path,method,body)=>{calls.push({path,method,body});if(method!=='POST')return[{id:'s1',name:'学员甲',className:'高一班',courses:[enrollment()]}];return{groups:[{studentId:'s1',studentName:'学员甲',matches:result(['q']).matches}]}},{showActionSheet:o=>sheets.push(o)});
 p.data.ready=false;p.data.subjectIndex=1;p.data.typeIndex=2;p.data.sourceLabel='另一位学员的原题';await p.openLearners();assert.equal(p.data.learnerOpen,true);assert.equal(calls[0].path,'/question-bank/learners?query=');assert.equal(p.data.topic,'');await p.selectLearner({currentTarget:{dataset:{id:'s1'}}});
 assert.equal(sheets.length,0);assert.equal(calls[1].body.subject,'数学');assert.equal(calls[1].body.topic,'');assert.equal(calls[1].body.type,'');assert.deepEqual(Array.from(calls[1].body.studentIds),['s1']);assert.equal(p.data.cards[0].learnerName,'学员甲');assert.equal(p.data.learnerOpen,false);assert.equal(p.data.sourceLabel,'');assert.equal(p.data.ready,true);
});
test('multiple subjects prompt only after choosing a learner, and the selected subject determines the request',async()=>{
 const calls=[],p=page(async(path,method,body)=>{calls.push({path,body});if(method!=='POST')return[{id:'s1',name:'学员甲',courses:[enrollment(),enrollment('物理','course-p')]}];return{groups:[{studentId:'s1',studentName:'学员甲',matches:[] ,message:'暂无已核对错题'}]}},{showActionSheet:o=>{assert.deepEqual(Array.from(o.itemList),['数学','物理']);o.success({tapIndex:1})}});
 await p.openLearners();assert.equal(calls.length,1);await p.selectLearner({currentTarget:{dataset:{id:'s1'}}});assert.equal(calls[1].body.subject,'物理');assert.equal(calls[1].body.courseId,'course-p');assert.match(p.data.message,/暂无已核对错题/);assert.equal(p.data.cards.length,0);
});
test('same-subject courses do not show a subject picker and are searched together',async()=>{
 let sent;const p=page(async(_path,method,body)=>{if(method!=='POST')return[{id:'s1',name:'学员甲',courses:[enrollment(),enrollment('数学','course-extra')]}];sent=body;return{groups:[]}},{showActionSheet:()=>assert.fail('same subject must not prompt')});await p.openLearners();await p.selectLearner({currentTarget:{dataset:{id:'s1'}}});assert.deepEqual(Array.from(sent.courseIds),['course-m','course-extra']);
});
test('cancelling subject selection or closing its learner sheet never requests recommendations',async()=>{
 let sheet,calls=0;const p=page(async()=>{calls++;return[{id:'s1',name:'学员甲',courses:[enrollment(),enrollment('物理','course-p')]}]},{showActionSheet:o=>sheet=o});await p.openLearners();let selecting=p.selectLearner({currentTarget:{dataset:{id:'s1'}}});sheet.fail();await selecting;assert.equal(calls,1);selecting=p.selectLearner({currentTarget:{dataset:{id:'s1'}}});p.closeLearners();sheet.success({tapIndex:0});await selecting;assert.equal(calls,1);
});
test('closing or editing a native learner lookup invalidates late replies and permits a new search',async()=>{
 let finish;const p=page(()=>new Promise(r=>finish=r));p.data.topic='椭圆';const pending=p.openLearners();p.closeLearners();finish([{classId:'old',courseId:'old'}]);await pending;assert.equal(p.data.learners.length,0);
 p.data.learnerBusy=true;p.learnerInput({detail:{value:'学员甲'}});assert.equal(p.data.learnerBusy,false);assert.equal(p.data.selectedLearnerIds.length,0);
});
