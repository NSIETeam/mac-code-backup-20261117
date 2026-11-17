from pathlib import Path
p=Path('web/app.js');s=p.read_text()
def rep(a,b):
 global s
 assert s.count(a)==1,a[:120]
 s=s.replace(a,b)
rep("async function render(){if(bookSplitView", "async function render(savedReview){if(bookSplitView")
rep("await (pages[name]||home)(p);","await (pages[name]||home)(p,name==='handout' && savedReview?.route===current?savedReview:undefined);")
rep("async function handoutPage({id,classId,bookId,courseId}){const d=await api(", "async function handoutPage({id,classId,bookId,courseId},savedReview){const d=savedReview?.data ?? await api(")
rep("const regionHistory=await api(`/handouts/${id}/regions/history`);const needsPolling", "const regionHistory=savedReview?.history ?? await api(`/handouts/${id}/regions/history`);const needsPolling")
a=s.index('const edit=async(q,body)=>{');b=s.index("if(['confirm','verify-integrity'].includes(body.action))",a)
s=s[:a]+'''const reviewRoute=location.hash;
 const edit=async(q,body)=>{
   if(body.action==='label' && body.label.trim()===q.label){dialog.close();return;}
   const previousRevision=d.handout.revision;
   const saved=await api('/handouts/'+id+'/edit','POST',{revision:previousRevision,questionId:q.id,...body});
   if(location.hash!==reviewRoute)return;
   dialog.close();
   const patch=saved?.question;
   if(['label','confirm'].includes(body.action) && saved.revision===previousRevision+1 &&
      patch?.id===q.id && typeof patch.label==='string' && typeof patch.confirmed==='boolean' && Number.isInteger(patch.revision)){
     const renamed=q.label!==patch.label;
     Object.assign(q,{label:patch.label,confirmed:patch.confirmed,revision:patch.revision});
     d.handout.revision=saved.revision;
     const history={...regionHistory,revision:saved.revision,checkOnUndo:regionHistory.checkOnUndo||Boolean(renamed&&regionHistory.last)};
     await render({route:reviewRoute,data:d,history});
   }else{
     await render();
   }
   '''+s[b:]
rep("()=>undoLastRegion(id,d,regionHistory.last),'quiet',!regionHistory.last.canUndo","async()=>{const latest=regionHistory.checkOnUndo?await api('/handouts/'+id+'/regions/history'):regionHistory;if(!latest.last?.canUndo){notice(latest.last?.reason||'当前没有可撤销的框选');return;}undoLastRegion(id,d,latest.last);},'quiet',!regionHistory.checkOnUndo&&!regionHistory.last.canUndo")
rep("regionHistory.last&&!regionHistory.last.canUndo?`<p", "regionHistory.last&&!regionHistory.checkOnUndo&&!regionHistory.last.canUndo?`<p")
rep("${field('题号','label',q.label,'text','required')}<button", "${field('题号','label',q.label,'text','required maxlength=\"120\"')}<small>题面图保留原有编号。</small><button")
# Retained image elements must get the accessible name for the updated question.
rep('if(previous&&target)target.replaceWith(previous);',"""if(previous&&target){
      const newImages=target.querySelectorAll('img');
      previous.querySelectorAll('img').forEach((image,i)=>{if(newImages[i])image.alt=newImages[i].alt;});
      target.replaceWith(previous);
    }""")
p.write_text(s)
p=Path('web/index.html');s=p.read_text().replace('app.js?v=20261107-classic','app.js?v=20261107-label-save');p.write_text(s)
