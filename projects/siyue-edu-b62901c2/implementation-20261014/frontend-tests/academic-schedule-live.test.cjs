const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const fixed=Date.parse('2026-10-14T02:00:00Z');
class FixedDate extends Date {constructor(...args){super(...(args.length?args:[fixed]));}static now(){return fixed;}}
const script=name=>fs.readFileSync(path.join(__dirname,'..',name),'utf8');
const plain=value=>JSON.parse(JSON.stringify(value));
function planner(options={}){
 const course={id:'Q',name:'数学',lessons:[1,2,3].map(i=>({id:'L'+i,title:'知识点'+i})),...options.course};
 const cls={id:'C',name:'数学班',courseId:'Q',campusId:'A',teacherId:'T',room:'101',durationMinutes:90,schedule:[],...options.class};
 const operation={id:options.operation||'class.create'},key=operation.id==='class.schedule'?'sessions':'schedule';
 const draft=operation.id==='class.schedule'?{classId:'C',sessions:plain(cls.schedule)}:{...(operation.id==='class.update'?{classId:'C'}:{}),...plain(cls)};
 const ctx={operation,draft,data:{courses:[course],classes:operation.id==='class.create'?[]:[cls],attendance:options.attendance||[],consumptions:[],directory:[],campuses:[]},disabled:false,key,path:[key]};
 const calls=[];const sandbox={Date:FixedDate,businessAPI:{academicDraft:()=>ctx,academicSet:(p,value,redraw=true)=>{if(p.length)ctx.draft[p[0]]=plain(value);else ctx.draft=plain(value);calls.push({path:p,redraw});return true;}}};
 vm.runInNewContext(script('academic-planner.js'),sandbox);const api=sandbox.SiyueAcademicPlanner;api.field(ctx);return {api,ctx,calls,rows:()=>ctx.draft[key],render:()=>api.field(ctx)};
}
test('new courses need no lesson duration: weekly generation uses class duration and holiday switch',()=>{
 const p=planner();p.api.repeatField('date','2027-01-01');p.api.repeatField('time','09:30');p.api.repeatField('holidays','2027-01-02');
 assert.equal(p.api.generate(),true);assert.deepEqual(p.rows().map(r=>r.date),['2027-01-09','2027-01-16','2027-01-23']);assert.ok(p.rows().every(r=>r.time==='09:30–11:00'));
 p.api.repeatField('strategy','replace');p.api.repeatField('skipHolidays',false);assert.equal(p.api.generate(),true);assert.equal(p.rows()[0].date,'2027-01-02');assert.equal(p.api.validate(p.ctx),'');
});
test('replacement keeps locked attendance and original session IDs, filling never overlaps existing slots',()=>{
 const p=planner({operation:'class.schedule',class:{schedule:[{id:'S1',lessonId:'L1',date:'2027-01-02',time:'09:00–10:30'},{id:'S2',lessonId:'L2',date:'2027-01-09',time:'09:00–10:30'}]},attendance:[{sessionId:'S1'}]});
 p.api.repeatField('date','2027-01-02');p.api.repeatField('time','10:00');p.api.repeatField('strategy','replace');p.api.generate();
 assert.deepEqual(p.rows()[0],{id:'S1',lessonId:'L1',date:'2027-01-02',time:'09:00–10:30'});assert.equal(p.rows()[1].id,'S2');assert.equal(p.rows()[1].time,'10:00–11:30');
 const snapshot=JSON.stringify(p.rows());p.api.repeatField('strategy','fill');p.api.generate();assert.equal(JSON.stringify(p.rows()),snapshot);
});
test('single lesson invalid edits return feedback instead of throwing and block stale preview',()=>{
 const p=planner();p.api.repeatField('date','2027-01-02');p.api.generate();const before=plain(p.rows());
 assert.doesNotThrow(()=>assert.equal(p.api.rowField('L1','date','2027-02-30'),false));assert.deepEqual(p.rows(),before);assert.match(p.api.validate(p.ctx),/完整填写/);
 assert.equal(p.api.rowField('L1','date','2027-01-03'),true);assert.equal(p.api.validate(p.ctx),'');assert.equal(p.rows()[0].date,'2027-01-03');assert.equal(p.calls.at(-1).redraw,false);
 assert.equal(p.api.rowField('L2','time','09:99'),false);assert.match(p.api.validate(p.ctx),/完整填写/);
 p.api.removeLesson('L2');assert.equal(p.api.validate(p.ctx),'');
});
test('partial dates never silently save; cross-day schedules reject, exact midnight end succeeds',()=>{
 const p=planner();p.api.rowField('L1','time','11:00');assert.match(p.api.validate(p.ctx),/完整填写/);assert.equal(p.rows().length,0);p.api.removeLesson('L1');
 p.api.repeatField('date','2027-01-02');p.api.repeatField('time','23:00');assert.equal(p.api.generate(),false);assert.equal(p.rows().length,0);
 p.api.repeatField('time','22:30');assert.equal(p.api.generate(),true);assert.equal(p.rows()[0].time,'22:30–24:00');assert.equal(p.api.validate(p.ctx),'');
});
test('class duration overrides legacy lessons and duration changes require schedule refresh',()=>{
 const p=planner({course:{lessons:[{id:'L1',title:'第一讲',durationMinutes:60}]}});p.api.repeatField('date','2027-01-02');p.api.generate();assert.equal(p.rows()[0].time,'09:00–10:30');
 p.ctx.draft.durationMinutes=120;assert.match(p.api.validate(p.ctx),/时长已修改/);p.api.repeatField('strategy','replace');p.api.generate();assert.equal(p.rows()[0].time,'09:00–11:00');
});
test('invalid holiday or empty weekdays leaves submitted draft intact; disabled context cannot mutate',()=>{
 const p=planner();p.api.repeatField('holidays','2027-02-30');assert.equal(p.api.generate(),false);assert.equal(p.rows().length,0);p.api.repeatField('holidays','');p.api.repeatDay(6,false);assert.equal(p.api.generate(),false);
 p.ctx.disabled=true;assert.equal(p.api.rowField('L1','date','2027-01-02'),false);assert.equal(p.api.generate(),false);
});
function schedule(){
 const data={courses:[{id:'Q',name:'数学',courseType:'onsite',lessons:[{id:'L1',title:'第一讲'}]}],campuses:[{id:'A',name:'城中'},{id:'B',name:'园区'}],directory:[{id:'T',name:'甲老师',roleIds:['teacher']},{id:'U',name:'乙老师',roleIds:['teacher']}],students:[],attendance:[],classes:[
  {id:'C1',name:'甲班',campusId:'A',room:'101',courseId:'Q',teacherId:'T',schedule:[{id:'S1',lessonId:'L1',date:'2027-01-04',time:'09:00–10:30',studentIds:['ST']}]},
  {id:'C2',name:'乙班',campusId:'B',room:'101',courseId:'Q',teacherId:'U',schedule:[{id:'S2',lessonId:'L1',date:'2027-01-04',time:'09:00–10:30',studentIds:[]}]},
  {id:'C3',name:'甲老师另一班',campusId:'A',room:'102',courseId:'Q',teacherId:'T',schedule:[{id:'S3',lessonId:'L1',date:'2027-01-04',time:'10:00–11:30',studentIds:[]}]},
  {id:'C4',name:'取消班',campusId:'A',room:'103',courseId:'Q',teacherId:'T',schedule:[{id:'S4',lessonId:'L1',date:'2027-01-04',time:'10:00–11:30',studentIds:[],status:'cancelled'}]}
 ]};
 let blob;const sandbox={Date:FixedDate,Blob,URL:{createObjectURL:b=>(blob=b,'blob:ok'),revokeObjectURL(){}},document:{createElement:()=>({click(){},remove(){}}),body:{append(){}}},businessAPI:{snapshot:()=>data,redraw(){},status:()=>({})}};
 vm.runInNewContext(script('business-schedule.js'),sandbox);const api=sandbox.SiyueBusinessSchedule,ctx={page:'schedule',data,user:{id:'T',permissions:['schedule.read']}};api.render(ctx);api.filter('from','2027-01-04');api.filter('to','2027-01-10');return {api,ctx,render:()=>api.render(ctx),exported:()=>blob};
}
test('teacher/room weekly views render, preserve compound filters, and navigate whole weeks',()=>{
 const s=schedule();s.api.filter('teacher','T');s.api.filter('campus','A');s.api.filter('mode','teacher');let html=s.render();assert.match(html,/2027-01-04 — 2027-01-10/);assert.match(html,/甲老师另一班/);assert.doesNotMatch(html,/<th scope="row"[^>]*>乙老师/);assert.match(html,/时间冲突|<em>冲突/);assert.match(html,/value="teacher"/);
 s.api.filter('room',JSON.stringify(['A','101']));s.api.filter('mode','room');html=s.render();assert.match(html,/<span>甲班<\/span>/);assert.doesNotMatch(html,/<span>乙班<\/span>|<span>甲老师另一班<\/span>|<span>取消班<\/span>/);
 s.api.filter('week','next');assert.equal(s.api.snapshot().from,'2027-01-11');assert.equal(s.api.snapshot().to,'2027-01-17');assert.equal(s.api.filter('weekDate','2027-02-30'),false);
});
test('weekly role and cancelled-state filters select real role IDs and actual matching rows',()=>{
 const s=schedule();s.api.filter('mode','teacher');s.api.filter('role','teacher');s.api.filter('status','cancelled');let html=s.render();assert.match(html,/<span>取消班<\/span>/);assert.doesNotMatch(html,/<span>甲班<\/span>|<span>乙班<\/span>|<em>冲突/);
 s.api.filter('role','sales');assert.match(s.render(),/当前周没有可显示/);
});
test('personal timetable and CSV keep current teacher scope despite broad server permissions',async()=>{
 const s=schedule();s.ctx.page='teacherCourses';s.render();s.api.filter('from','2027-01-04');s.api.filter('to','2027-01-10');const html=s.render();assert.match(html,/甲班/);assert.doesNotMatch(html,/乙班|乙老师|老师课表|教室课表/);assert.equal(s.api.filter('mode','room'),false);
 s.api.filter('export','');const csv=await s.exported().text();assert.match(csv,/甲班/);assert.doesNotMatch(csv,/乙班|乙老师/);
});
test('time table uses actual room, safe labels, status and validated dates',()=>{
 const s=schedule();s.ctx.data.classes[0].name='<img src=x>';const html=s.render();assert.match(html,/&lt;img src=x&gt;/);assert.doesNotMatch(html,/<img src=x>/);assert.match(html,/<th>教室<\/th>/);assert.match(html,/面授课/);assert.equal(s.api.filter('from','2027-02-30'),false);
});

test('legacy mixed lesson durations remain valid until the class is assigned a unified duration',()=>{
 const p=planner({class:{durationMinutes:undefined},course:{lessons:[{id:'L1',title:'讲一',durationMinutes:60},{id:'L2',title:'讲二',durationMinutes:90}]}});p.api.repeatField('date','2027-01-02');p.api.generate();assert.equal(p.rows()[0].time,'09:00–10:00');assert.equal(p.rows()[1].time,'09:00–10:30');assert.equal(p.api.validate(p.ctx),'');
});
