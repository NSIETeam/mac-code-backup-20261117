from pathlib import Path
p=Path('web/app.js');s=p.read_text()
def rep(a,b):
 global s
 assert s.count(a)==1,a[:90]
 s=s.replace(a,b)
rep("core.mjs?v=20261105-central-resolve","core.mjs?v=20261107-label-confirm")
rep("   if(['label','confirm'].includes(body.action)", "   if(['label','confirm','label-confirm'].includes(body.action)")
rep('     d.handout.revision=saved.revision;', '''     if(saved.verifiedIssues?.length)q.issues=q.issues.filter(issue=>!saved.verifiedIssues.includes(issue.code));
     d.handout.revision=saved.revision;''')
a=s.index("   if(['confirm','verify-integrity'].includes(body.action))");b=s.index(";paint(heading('校对题目'",a)
s=s[:a]+"""   if(['confirm','verify-integrity','label-confirm'].includes(body.action))advanceQuestionReview(d.questions,q.id,reviewRoute);
 }"""+s[b:]
a=s.index("${editButton(()=>{modal('编辑题号'");b=s.index("${reviewTools('修正题目'",a)
s=s[:a]+"${editButton(()=>editQuestionLabel(q,edit))}"+s[b:]
a=s.index('async function handoutPage(')
s=s[:a]+'''function editQuestionLabel(question,edit){
 const verifyIntegrity=canVerifyPdfIntegrity(question);
 const hasPreview=question.previews.length>0;
 const canConfirm=hasPreview && (verifyIntegrity || !question.issues.some(issue=>issue.blocking));
 const hint=!hasPreview?'预览就绪后可确认，当前可先保存题号。'
   :!canConfirm?'本题还有待修复的问题，当前可先保存题号。'
   :verifyIntegrity?'点“保存并确认”表示已对照原稿核对题号、图文和跨页内容。':'题面图保留原有编号。';
 modal('编辑题号',`<form id="label-edit">${field('题号','label',question.label,'text','required maxlength="120"')}
   <small>${e(hint)}</small><div class="actions label-edit-actions">
   <button type="submit" value="label" class="quiet">保存</button>
   <button type="submit" value="label-confirm" class="primary" ${canConfirm?'':'disabled'}>保存并确认</button></div></form>`);
 const form=document.getElementById('label-edit');
 form.onsubmit=event=>{
   event.preventDefault();
   const confirm=event.submitter?.value==='label-confirm';
   if(confirm&&!canConfirm)return;
   void task(()=>edit(question,{action:confirm?'label-confirm':'label',label:form.elements.label.value,
     ...(confirm&&verifyIntegrity?{confirmIntegrity:true}:{})}));
 };
 form.elements.label.focus();form.elements.label.select();
}
function advanceQuestionReview(questions,currentId,routeKey){
 const at=questions.findIndex(q=>q.id===currentId);
 const next=[...questions.slice(at+1),...questions.slice(0,at)].find(q=>!q.confirmed);
 if(!next){notice('本讲题目已全部确认');return;}
 // The task temporarily makes the main view inert. Move focus after it is released.
 requestAnimationFrame(()=>{
   if(location.hash!==routeKey||dialog.open)return;
   const card=document.querySelector('.review-question[data-question-id="'+CSS.escape(next.id)+'"]');
   if(!card)return;
   document.querySelector('.review-question.is-review-target')?.classList.remove('is-review-target');
   card.classList.add('is-review-target');
   const heading=card.querySelector('h2');heading.tabIndex=-1;heading.focus({preventScroll:true});
   card.scrollIntoView({block:'start',behavior:'instant'});
 });
}
'''+s[a:]
a=s.index('  const actions=[current||source?');b=s.index('\n  const group=',a)
s=s[:a]+'''  const caption=(current?.id===next?'下一讲 · ':'')+'第'+l.sequence+'讲 · '+l.title;
  const lectureTitle=button(e(caption),preview,'lecture-open name-link');
  const detailName=current?'v'+current.version:source?'课程共享资料':'待备课';
  const versions=button(e(detailName),()=>lectureMaterials(d,l,classId),'lecture-version bare')
    .replace('<button ','<button aria-label="第'+e(l.sequence)+'讲资料与版本" title="讲次资料与版本" ');
'''+s[b:]
rep("<h2>${current?.id===next?'下一讲 · ':''}第${e(l.sequence)}讲 · ${e(l.title)}</h2>","<h2>${lectureTitle}</h2>")
rep("<small class=\"lecture-meta\">${current?'v'+e(current.version):source?'课程共享资料':'待备课'}</small>",'<small class="lecture-meta">${versions}</small>')
rep('<div class="row-actions lecture-row-actions">${actions}</div></div>${lessons.map','</div>${lessons.map')
p.write_text(s)
p=Path('web/core.mjs');t=p.read_text().replace("'pdf-formula-review']);","'pdf-formula-review','pdf-ai-review']);");assert t!=p.read_text();p.write_text(t)
p=Path('web/workbench-classic.css');p.write_text(p.read_text()+"""
.classic-workbench button.lecture-open {display:inline-block;min-width:0;max-width:100%;min-height:44px;height:auto;padding:0;text-align:left;white-space:normal;overflow-wrap:anywhere;font:inherit;color:inherit;text-decoration:none}
.classic-workbench .lecture-version.bare {min-height:28px;padding:0 3px 0 0;font:inherit;color:#4e6476}
.classic-workbench :is(.lecture-open,.lecture-version):hover {text-decoration:underline}
.label-edit-actions {justify-content:flex-end;margin-top:14px}
.review-question.is-review-target {outline:2px solid #65869f;outline-offset:1px}
""")
p=Path('web/index.html');s=p.read_text().replace('app.js?v=20261107-label-save','app.js?v=20261107-label-confirm').replace('workbench-classic.css?v=20261107-classic','workbench-classic.css?v=20261107-label-confirm');p.write_text(s)
