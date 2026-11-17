const scopeResult=(classId,courseId)=>classId?{name:'class',p:{id:classId,handouts:'1'}}:courseId?{name:'course',p:{id:courseId}}:{name:'courses',p:{}};
const scopeParams=(classId,courseId)=>classId?{classId}:{courseId};
const sequence=item=>Number.isFinite(Number(item.sequence))?Number(item.sequence):0;
function afterCurrent(items,index){return [...items.slice(index+1),...items.slice(0,index+1)];}
export function nextBookReview(detail,{classId,courseId,currentId}){
 const book=detail?.book;
 if(!book||(book.classId||null)!==(classId||null)||!courseId||book.courseId!==courseId)return scopeResult(classId,courseId);
 const context=scopeParams(classId,courseId);
 const lessons=(detail.sections||[]).filter(s=>s.kind==='lesson'||!s.kind&&s.handoutId).slice().sort((a,b)=>sequence(a)-sequence(b)||(a.start||0)-(b.start||0));
 const current=lessons.findIndex(s=>s.handoutId===currentId);
 if(current<0)return scopeResult(classId,courseId);
 const handouts=new Map((detail.handouts||[]).map(h=>[h.id,h]));
 const needsReview=id=>{const handout=handouts.get(id);return handout?.state!=='published'&&(classId||handout?.readyToPublish!==true);};
 const next=afterCurrent(lessons,current).find(s=>s.handoutId!==currentId&&needsReview(s.handoutId));
 if(!next)return {name:'book',p:{id:book.id,...context}};
 const handout=handouts.get(next.handoutId);
 if(handout?.state==='draft')return {name:'handout',p:{id:handout.id,...context,bookId:book.id}};
 return {name:'book',p:{id:book.id,...context,next:next.handoutId||'seq-'+next.sequence}};
}
export function nextClassReview(detail,{classId,courseId,currentId,lectureId}){
 if(!classId||detail?.schoolClass?.id!==classId||!courseId)return scopeResult(classId,courseId);
 const lectures=(detail.lectures||[]).filter(l=>l.courseId===courseId&&(!l.classId||l.classId===classId)).slice().sort((a,b)=>sequence(a)-sequence(b));
 const current=lectures.findIndex(l=>l.id===lectureId);
 if(current<0||!(detail.handouts||[]).some(h=>h.id===currentId&&h.lectureId===lectureId))return scopeResult(classId,courseId);
 const latest=l=>(detail.handouts||[]).filter(h=>h.lectureId===l.id).sort((a,b)=>(b.version||0)-(a.version||0))[0];
 const next=afterCurrent(lectures,current).map(latest).find(h=>h&&h.id!==currentId&&h.state!=='published');
 if(next?.state==='draft')return {name:'handout',p:{id:next.id,classId}};
 return next?{name:'class',p:{id:classId,handouts:'1',next:next.id}}:scopeResult(classId,courseId);
}
