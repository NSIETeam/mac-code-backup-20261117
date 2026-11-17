'use strict';
// This fixture runner only addresses the disposable loopback preview.
const base='http://127.0.0.1:8772/crm-api/v1/';
async function request(path,body,extra={}){const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...extra},...(body?{body:JSON.stringify(body)}:{})});const data=await r.json();if(!r.ok)throw Error(path+': '+JSON.stringify(data));return data;}
async function op(id,input){const s=await request('state');const p=await request('operations/'+id+'/preview',{input,expectedRevision:s.revision});return request('operations/'+id+'/execute',{input,expectedRevision:p.revision,previewToken:p.previewToken},{'Idempotency-Key':crypto.randomUUID()});}
(async()=>{
let users=(await request('admin/users')).users;
let teacher=users.find(u=>u.username==='local-ui-teacher');
if(!teacher)teacher=(await request('admin/users',{username:'local-ui-teacher',name:'【模拟】李老师',roleIds:['teacher'],scope:{type:'assigned'}})).user;
let s=(await request('state')).state;const campusId=s.campuses[0].id;
await op('dictionary.update',{key:'subjects',values:['数学','语文','英语']});
if(!s.campuses[0].rooms)await op('campus.update',{campusId,patch:{rooms:[{name:'教室1',capacity:30},{name:'教室2',capacity:30}]}});
for(let i=1;i<=3;i++){
s=(await request('state')).state;
let course=s.courses.find(c=>c.name==='【模拟】数学'+i);
if(!course){await op('course.create',{name:'【模拟】数学'+i,grade:'小学四年级',subject:'数学',priceCents:60000,lessons:[{title:'第一讲',durationMinutes:60},{title:'第二讲',durationMinutes:60}]});s=(await request('state')).state;course=s.courses.find(c=>c.name==='【模拟】数学'+i);}
let cls=s.classes.find(c=>c.name==='【模拟】四年级'+i+'班');
if(!cls){await op('class.create',{name:'【模拟】四年级'+i+'班',courseId:course.id,campusId,room:'教室1',teacherId:teacher.id,capacity:20});s=(await request('state')).state;cls=s.classes.find(c=>c.name==='【模拟】四年级'+i+'班');}
if(!cls.schedule?.length)await op('class.schedule',{classId:cls.id,sessions:course.lessons.map((l,j)=>({lessonId:l.id,date:'2026-10-'+(17+j),time:(8+i*2)+':00-'+(9+i*2)+':00'}))});
let student=s.students.find(c=>c.name==='【模拟】小'+i);
if(!student){await op('student.create',{name:'【模拟】小'+i,phone:'1990000880'+i,grade:'小学四年级',campusId});s=(await request('state')).state;student=s.students.find(c=>c.name==='【模拟】小'+i);}
if(!s.enrollments.some(e=>e.studentId===student.id))await op('enrollment.create',{classId:cls.id,studentId:student.id});
}
console.log('Local academic fixtures ready');
})().catch(e=>{console.error(e.message);process.exitCode=1});
