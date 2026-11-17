const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('server/AcademicPilotAssets/app.js','utf8');
class Element{
 constructor(tag,cls,text){Object.assign(this,{tag,className:cls||'',textContent:text||'',children:[],dataset:{},value:'',events:{},classList:{remove(){}}});}
 append(...children){this.children.push(...children);}replaceChildren(...children){this.children=children;}
 setAttribute(key,value){this[key]=value;}addEventListener(key,fn){this.events[key]=fn;}focus(){this.focused=true;}remove(){}close(){}showModal(){}
}
const all=root=>[root,...root.children.flatMap(all)];
const info={classId:'class-a',courseId:'math',className:'高一甲班',courseName:'数学',suggestedStage:'高中',suggestedSubject:'数学',students:[{studentId:'a',name:'同学甲',wrongCount:2},{studentId:'b',name:'同学乙',wrongCount:1}]};
function harness(api,settings=new Map(),data=info){
 const detail=new Element('section'),calls=[],previews=[],scope={trainingDialog:null,entryClassId:'',state:{epoch:1,me:{},trainingClasses:[info]},
 document:{body:new Element('body')},localStorage:{getItem:key=>settings.get(key),setItem:(key,value)=>settings.set(key,value)},
 el:(...args)=>new Element(...args),append:(parent,...children)=>{parent.append(...children);return parent;},
 button:(text,cls,fn)=>{const n=new Element('button',cls,text);n.click=fn;return n;},
 field:(label,input)=>{const n=new Element('label','',label);n.append(input);return n;},
 selectInput:(options,value)=>{const n=new Element('select');n.options=options;n.value=value;return n;},
 panel:label=>new Element('section','panel',label),emptyDetail(){},
 table:()=>{const wrap=new Element('table'),body=new Element('tbody');wrap.append(body);return {wrap,body};},
 cell:(value,cls)=>{const n=new Element('td',cls);if(value instanceof Element)n.append(value);else n.textContent=String(value);return n;},
 current:epoch=>scope.state.me&&scope.state.epoch===epoch,$:()=>detail,runNavigation:fn=>fn(),
 api:async(path,options)=>{calls.push({path,options});return api(path,options);}
 };
 vm.createContext(scope);vm.runInContext(source.slice(source.indexOf('  function trainingOptionsKey('),source.indexOf('  async function loadWrong(')),scope);
 scope.showTrainingPaper=(cls,plan)=>previews.push(plan);scope.renderTrainingClass(data,1);
 const find=text=>all(detail).find(n=>n.tag==='button'&&n.textContent===text);
 return {scope,detail,calls,previews,find,settings};
}
const plan=(id,status='generated')=>({studentId:id,name:id==='a'?'同学甲':'同学乙',status,questions:status==='generated'?[{question:{id:'exercise-'+id}}]:[],notices:[]});
test('one class click shows student roster; batch generation is one request and preserves personal plans',async()=>{
 const h=harness(async()=>({students:[plan('a'),plan('b','unreviewed')]}));assert.equal(h.calls.length,0);
 await h.find('一键生成全班训练').click();assert.equal(h.calls.length,1);
 assert.equal(h.calls[0].path,'/training/classes/class-a/courses/math/generate');assert.equal(h.calls[0].options.method,'POST');
 assert.equal(h.calls[0].options.body.studentId,undefined);assert.equal(h.calls[0].options.body.stage,'高中');
 assert.equal(h.previews.length,0);assert.ok(all(h.detail).some(n=>n.textContent==='等待教师批改'));
 await h.find('同学甲').click();assert.equal(h.previews[0].studentId,'a');assert.equal(h.calls.length,1);
});
test('clicking a person generates only their training and opens that paper',async()=>{
 const h=harness(async()=>({students:[plan('b')]}));await h.find('同学乙').click();
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].options.body.studentId,'b');assert.equal(h.previews[0].studentId,'b');
 assert.equal(h.previews[0].questions[0].question.id,'exercise-b');
});
test('unknown stage is configured once for the class and reused without per-person selections',async()=>{
 const saved=new Map(),unknown={...info,suggestedStage:'',className:'数学甲班'};
 const h=harness(async()=>({students:[plan('a')]}),saved,unknown);await h.find('一键生成全班训练').click();assert.equal(h.calls.length,0);
 const stage=all(h.detail).find(n=>n.tag==='select');assert.equal(stage.focused,true);stage.value='初中';stage.events.change();
 await h.find('同学甲').click();assert.equal(h.calls[0].options.body.stage,'初中');
 const next=harness(async()=>({students:[]}),saved,unknown);assert.equal(all(next.detail).find(n=>n.tag==='select').value,'初中');
 assert.equal(all(next.detail).filter(n=>n.tag==='select').length,2);
 assert.doesNotMatch(Array.from(saved.values()).join(''),/student|同学|token/);
});
test('settings changes and leaving a class discard in-flight results',async()=>{
 let finish;const h=harness(()=>new Promise(resolve=>finish=resolve));const pending=h.find('一键生成全班训练').click();
 const stage=all(h.detail).find(n=>n.tag==='select');stage.value='初中';stage.events.change();finish({students:[plan('a')]});await pending;
 assert.equal(h.find('查看训练'),undefined);assert.equal(h.find('一键生成全班训练').disabled,false);
 const second=h.find('同学甲').click();h.scope.state.epoch++;finish({students:[plan('a')]});await second;assert.equal(h.previews.length,0);
});
test('a failed generation is shown without a success state',async()=>{
 const h=harness(async()=>{throw new Error('题库暂不可用');});await h.find('一键生成全班训练').click();
 assert.ok(all(h.detail).some(n=>n.textContent==='题库暂不可用'));assert.equal(h.find('查看训练'),undefined);assert.equal(h.find('一键生成全班训练').disabled,false);
});
test('class selection loads only the chosen class and course',async()=>{
 const h=harness(async path=>path==='/training/classes'?[{...info,studentCount:2}]:info);
 await h.scope.loadTraining(1);const choose=all(h.detail).find(n=>n.className==='training-class-card');assert.ok(choose);await choose.click();
 assert.equal(h.calls[1].path,'/training/classes/class-a/courses/math');
 assert.equal(h.scope.state.trainingClass.classId,'class-a');assert.ok(h.find('一键生成全班训练'));
});
test('the standard UI starts from classes and no longer repeats recommendation forms for each wrong record',()=>{
 assert.match(source,/entry\.get\('module'\) === 'attendance' \? 'attendance' : entry\.get\('module'\) === 'collections' \? 'collections' : 'training'/);
 assert.doesNotMatch(source,/recommendationPanel\(student,epoch\)/);
 assert.match(source,/if\(state\.module==='training'\)\{await loadTraining\(epoch\);return;\}/);
});

test('class preview reuses generated plans without another generation request',async()=>{
 const h=harness(async()=>({students:[plan('a'),plan('b'),plan('c','no_wrong')]}));
 await h.find('一键生成全班训练').click();await h.find('预览全班训练').click();
 assert.equal(h.calls.length,1);assert.equal(h.previews[0].length,3);
});
test('combined papers stay personal, start without answers and omit empty plans',()=>{
 const body=new Element('body'),classes=new Set();body.classList={add:x=>classes.add(x),remove:x=>classes.delete(x)};
 let printed=0;const scope={trainingDialog:null,document:{body},window:{print:()=>printed++},
 el:(...args)=>new Element(...args),append:(parent,...children)=>{parent.append(...children);return parent;},
 button:(text,cls,fn)=>{const n=new Element('button',cls,text);n.click=fn;return n;},
 checkbox:()=>{const input=new Element('input'),node=new Element('label');input.checked=false;node.append(input);return {input,node};},
 closeTrainingDialog(){},renderRecommendationMath(){},downloadTraining(){},trainingStatus:()=>''};
 vm.createContext(scope);vm.runInContext(source.slice(source.indexOf('  function showTrainingPaper('),source.indexOf('  function trainingStatus(')),scope);
 const question={id:'q',stem:'练习题',answer:'私有参考答案',analysis:'解题过程',source:'自编',license:'自编'};
 const item={question,reasons:['按本人错题匹配'],origin:{label:'例1',lessonDate:'2026-11-01',occurrences:2}};
 scope.showTrainingPaper(info,[{...plan('a'),questions:[item]},{...plan('b'),questions:[item]},plan('c','no_wrong')]);
 assert.equal(all(body).filter(n=>n.className==='training-person-sheet').length,2);
 assert.equal(all(body).some(n=>n.textContent==='私有参考答案'),false);assert.equal(printed,0);
 const checkbox=all(body).find(n=>n.tag==='input');checkbox.checked=true;checkbox.events.change();
 assert.equal(all(body).filter(n=>n.textContent==='私有参考答案').length,2);
 all(body).find(n=>n.textContent==='打印 / 保存PDF').click();assert.equal(printed,1);assert.equal(classes.has('training-printing'),false);
});
