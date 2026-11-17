from pathlib import Path
p=Path('web/material-edition.mjs')
s=p.read_text()
def rep(a,b):
 global s
 assert s.count(a)==1,a[:90]
 s=s.replace(a,b)
rep("if(canCentralReview)right.setAttribute('aria-label','逐题校对与相关候选题');", """if(canCentralReview){
      root.classList.add('me-focused-review');
      right.setAttribute('aria-label','题目校对');
      const menu=el('details',null,'me-more-tools');
      menu.append(el('summary','教材选项'));
      const options=el('div',null,'me-more-items');
      options.append(history,docx,pdf);menu.append(options);tools.append(menu);
      if(reviewSaved!=='1')message.textContent='';
    }""")
rep("let batchBar=null,batchCount=null,batchOpen=null,batchViewing=false;", "let batchBar=null,batchCount=null,batchOpen=null,batchViewing=false,batchMode=false,batchToggle=null;")
rep("batchCount.textContent=`已选择", """batchBar.hidden=!batchMode;batchToggle?.setAttribute('aria-expanded',String(batchMode));
      root.classList.toggle('me-batch-mode',batchMode);
      if(batchToggle){batchToggle.disabled=mutating;batchToggle.textContent=batchMode?'退出批量':'批量校对';}
      batchCount.textContent=`已选择""")
rep("if(canCentralReview){batchBar=el(", """if(canCentralReview){
      batchToggle=button('批量校对',()=>{
        if(mutating)return;
        batchMode=!batchMode;
        if(!batchMode){batchSelected.clear();if(batchViewing&&selected)choose(selected);}
        syncBatch();
      });
      tools.insertBefore(batchToggle,tools.querySelector('.me-more-tools'));
      batchBar=el(""")
rep("mutating=false;history.disabled=false;batchSelected.clear();syncBatch();","mutating=false;history.disabled=false;batchSelected.clear();batchMode=false;batchViewing=false;syncBatch();")
rep("const panel=el('section',null,'me-review');panel.append(el('h4',multiple?'核对所选题目':'本题校对'));","const panel=el('section',null,'me-review');")
rep("panel.append(status,contentLabel,boundaryLabel,boundaryHelp,note,actions);", """const notes=el('details',null,'me-notes');notes.append(el('summary','添加校对说明'),note);
      panel.append(status,contentLabel);
      if(questions.some(item=>!item.replaceable))panel.append(boundaryLabel,boundaryHelp);
      panel.append(notes,actions);""")
rep("multiple?'请核对以上所选原题，再勾选确认。':'请核对以上原题内容，再勾选确认。'", "''")
rep("button('下一项待校对'","button('跳过此题'")
rep("if(!editable){right.append(el('p',edition.isCurrent?", "if(!editable){if(!canCentralReview)right.append(el('p',edition.isCurrent?")
p.write_text(s)
p=Path('web/app.js');s=p.read_text()
rep("material-edition.mjs?v=20261106-review-batch-next","material-edition.mjs?v=20261107-paper-tray")
a=s.index('const edit=async(q,body)=>');b=s.index("document.querySelector('main').dataset.reviewSignature=signature",a)
old=s[a:b]
t=old.replace("dialog.close();await render();};paint", """dialog.close();await render();if(['confirm','verify-integrity'].includes(body.action)){const ordered=[...d.questions.slice(d.questions.indexOf(q)+1),...d.questions.slice(0,d.questions.indexOf(q))];const next=ordered.find(item=>!item.confirmed);if(next)document.querySelector('[data-question-id="'+CSS.escape(next.id)+'"]')?.scrollIntoView({block:'nearest'});}};paint""")
# Place the existing callbacks in two well-labelled native disclosures.
refresh=t[t.index("button('刷新'"):t.index("+((d.handout.state===")]
undo=t[t.index("+(regionHistory.last?button"):t.index("+button('批量校对'")]
start=t.index("+button('一键确认'")
end=t.index("+button(bookId&&courseId",start)
bulk=t[start:end].replace("button('一键确认',async()=>{","button('确认全部可校对题',()=>modal('确认全部可校对题',`<p>将确认本讲所有预览完整且没有阻断问题的题目。</p>${button('确认全部可校对题',async()=>{").replace("},'',d.handout.state", "},'primary')}`),'quiet',d.handout.state")
t=t[:start]+t[end:]
t=t.replace(undo,'').replace(refresh,"reviewTools('更多操作',"+refresh+undo+bulk+")",1)
start=t.index("${i<d.questions.length-1?")
end=t.index("${canVerifyPdfIntegrity(q)?",start)
tools=t[start:end]
t=t[:start]+"${reviewTools('修正题目',`"+tools+"`)}"+t[end:]
t=t.replace("${canVerifyPdfIntegrity(q)?button", "${!q.confirmed&&canVerifyPdfIntegrity(q)?button").replace("},'quiet'):''}${button(q.confirmed?", "},'primary'):button(q.confirmed?").replace("action:'confirm'}),'',q.confirmed", "action:'confirm'}),'primary',q.confirmed")
# Disable hidden publish until all questions are ready; expose the existing action when it is usable.
s=s[:a]+t+s[b:]
pos=s.index('function verifySelected(')
s=s[:pos]+"""function reviewTools(label,actions){
  return '<details class="review-more"><summary>'+e(label)+'</summary><div class="review-more-items">'+actions+'</div></details>';
}
"""+s[pos:]
p.write_text(s)
