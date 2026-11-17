const classResult=classId=>({name:'class',p:{id:classId,handouts:'1'}});
const sequence=item=>Number.isFinite(Number(item.sequence))?Number(item.sequence):0;
function afterCurrent(items,index){return [...items.slice(index+1),...items.slice(0,index+1)];}
export function nextBookReview(detail,{classId,courseId,currentId}){
 const book=detail?.book;
 if(!book||book.classId!==classId||!courseId||book.courseId!==courseId)return classResult(classId);
 const lessons=(detail.sections||[]).filter(s=>s.kind==='lesson'||!s.kind&&s.handoutId).slice().sort((a,b)=>sequence(a)-sequence(b)||(a.start||0)-(b.start||0));
 const current=lessons.findIndex(s=>s.handoutId===currentId);
 if(current<0)return classResult(classId);
 const handouts=new Map((detail.handouts||[]).map(h=>[h.id,h]));
 const next=afterCurrent(lessons,current).find(s=>s.handoutId!==currentId&&handouts.get(s.handoutId)?.state!=='published');
 if(!next)return {name:'book',p:{id:book.id,classId}};
 const handout=handouts.get(next.handoutId);
 if(handout?.state==='draft')return {name:'handout',p:{id:handout.id,classId,bookId:book.id}};
 return {name:'book',p:{id:book.id,classId,next:next.handoutId||'seq-'+next.sequence}};
}
export function nextClassReview(detail,{classId,courseId,currentId,lectureId}){
 if(detail?.schoolClass?.id!==classId||!courseId)return classResult(classId);
 const lectures=(detail.lectures||[]).filter(l=>l.courseId===courseId&&(!l.classId||l.classId===classId)).slice().sort((a,b)=>sequence(a)-sequence(b));
 const current=lectures.findIndex(l=>l.id===lectureId);
 if(current<0||!(detail.handouts||[]).some(h=>h.id===currentId&&h.lectureId===lectureId))return classResult(classId);
 const latest=l=>(detail.handouts||[]).filter(h=>h.lectureId===l.id).sort((a,b)=>(b.version||0)-(a.version||0))[0];
 const next=afterCurrent(lectures,current).map(latest).find(h=>h&&h.id!==currentId&&h.state!=='published');
 if(next?.state==='draft')return {name:'handout',p:{id:next.id,classId}};
 return next?{name:'class',p:{id:classId,handouts:'1',next:next.id}}:classResult(classId);
}
