// Independent shared-material editor. API JSON is rendered as text, never executable HTML.
import {openBookReader} from './book-reader.mjs?v=20261105-page-usability';
const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
const button = (text, fn) => { const n=el('button',text); n.type='button'; n.onclick=fn; return n; };
const safeUrl = value => { const u=new URL(value,location.href); if(!['https:','http:'].includes(u.protocol))throw new Error('文件链接无效');return u.href; };
const requestId=()=>crypto.randomUUID();
let disposeActive;
function styles() {
  if(document.getElementById('material-edition-style')) return;
  const s=el('style');s.id='material-edition-style';s.textContent=`
  .material-edition{--line:#dfe5e3;--ink:#233d35;color:var(--ink)}.material-edition button,.material-edition select{font:inherit;border:1px solid var(--line);background:white;border-radius:7px;padding:7px 11px;cursor:pointer}.material-edition button:disabled{opacity:.5;cursor:default}.me-tools{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-bottom:14px}.me-tools h2{font-size:20px;margin:0 auto 0 0}.me-grid{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:20px}.me-sidebar{border-left:1px solid var(--line);padding-left:18px;position:sticky;top:10px;align-self:start;max-height:85vh;overflow:auto}.me-question{padding:14px 12px;border-bottom:1px solid var(--line);cursor:pointer}.me-question[aria-selected=true]{background:#edf5f1;box-shadow:inset 3px 0 #2c7655}.me-question p,.me-candidate p{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0}.me-muted{color:#6a746f;font-size:13px}.me-warning{color:#8a5a17;background:#fff8e8;padding:10px;border-radius:5px}.me-candidate{padding:12px 0;border-bottom:1px solid var(--line)}.me-candidate[data-selected=true]{background:#edf5f1}.me-text{margin:8px 0}.me-text summary{font-size:12px;color:#6a746f;cursor:pointer}.me-images img{display:block;max-width:100%;height:auto;margin:8px 0}.me-message{min-height:24px;margin:8px 0}.me-preview{width:100%;height:75vh;border:1px solid var(--line)}.me-primary{background:#245e47!important;color:white}.me-sidebar h3{margin:4px 0 10px}@media(max-width:850px){.me-grid{grid-template-columns:1fr}.me-sidebar{position:static;border-left:0;border-top:1px solid var(--line);padding:16px 0;max-height:none}}
  .me-review{border-bottom:1px solid var(--line);padding-bottom:12px;margin-bottom:14px}.me-review h4{margin:5px 0 8px}.me-review-evidence{max-height:280px;overflow:auto;border:1px solid var(--line);border-radius:5px;padding:8px;margin:8px 0}.me-review-evidence p{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.me-review label{display:flex;gap:7px;align-items:flex-start;font-size:13px;margin:9px 0}.me-review input[type=checkbox]{margin-top:3px;flex:none}.me-review textarea{box-sizing:border-box;width:100%;font:inherit;border:1px solid var(--line);border-radius:5px;padding:6px;resize:vertical}.me-review-actions{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.me-review-status{font-size:13px;margin:7px 0}.me-next{margin-bottom:10px}
  .me-question-heading{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.me-question-heading .me-edit{display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;min-height:44px;padding:10px;flex:0 0 44px}.me-edit svg{width:20px;height:20px}.me-question:focus-visible{outline:2px solid #2c7655;outline-offset:2px}
  .material-edition button,.material-edition select{min-height:44px;font-size:15px}.me-review label{font-size:15px;min-height:44px;align-items:center;cursor:pointer}.me-review input[type=checkbox],.me-batch-select input{width:24px;height:24px;flex:0 0 24px}.me-review-actions{position:sticky;bottom:0;background:#fff;padding:10px 0;z-index:2}.me-review-actions button{min-height:50px;padding:12px 18px;font-size:16px;flex:1}.me-question{scroll-margin-top:90px}.me-batch-tools{display:flex;gap:10px;align-items:center;flex-wrap:wrap;position:sticky;top:0;background:#f7faf8;border:1px solid var(--line);padding:12px;border-radius:8px;z-index:3;margin-bottom:12px}.me-batch-tools p{margin:0;flex:1 1 180px}.me-batch-select{display:inline-flex;align-items:center;gap:6px;min-height:44px;cursor:pointer}.me-review-evidence h5{font-size:15px;margin:12px 0 6px}.me-sidebar{scroll-margin-top:90px}
  `;document.head.append(s);
}
export async function renderMaterialEdition({materialId,editionId,questionId='',reviewSaved=''},ui) {
  disposeActive?.();disposeActive=null;styles();const root=el('section',null,'material-edition');ui.mount.replaceChildren(root);
  const message=el('div','正在读取教材…','me-message');message.setAttribute('role','status');root.append(message);
  const alive=()=>root.isConnected;let generation=0;let selected=null;let candidate=null;let pendingRequest=null;let mutating=false;
  const api=ui.api; const base='/api/material-versions';
  try {
    const summary=await api(`${base}/by-material/${encodeURIComponent(materialId)}`);if(!alive())return;
    if(!summary.currentEditionId){message.textContent='尚未完成题目校对，可返回讲义库下载原稿。';return;}
    let id=editionId||summary.currentEditionId;
    if(!summary.history.some(v=>v.id===id))throw new Error('教材版本不存在');
    const edition=await api(`${base}/${encodeURIComponent(id)}`);if(!alive())return;
    const tools=el('div',null,'me-tools');tools.append(el('h2',edition.title||'教材'));
    const history=el('select');history.setAttribute('aria-label','教材版本');
    for(const v of summary.history){const option=el('option',`第 ${v.number} 版${v.id===summary.currentEditionId?' · 当前共享版':''}`);option.value=v.id;option.selected=v.id===id;history.append(option);}
    const navigate=async next=>{if(ui.onNavigate)await ui.onNavigate(next);else await renderMaterialEdition({materialId,...next},ui);};
    history.onchange=()=>{if(!mutating)return navigate({editionId:history.value});};tools.append(history);
    const download=async format=>{try{const f=await api(`${base}/${id}/file?format=${format}`);const a=el('a');a.href=safeUrl(f.url);a.download=f.fileName||'';a.target='_blank';a.rel='noopener';a.click();}catch(e){message.textContent=e.message||'文件尚未准备好';}};
    const docx=button('下载 Word',()=>download('docx'));docx.disabled=!edition.docxReady;tools.append(docx);
    const pdf=button('下载 PDF',()=>download('pdf'));pdf.disabled=!edition.pdfReady;tools.append(pdf);
    const view=button('预览整册',async()=>{
      if(view.disabled)return;
      view.disabled=true;
      try {
        const reader=openBookReader({title:edition.title||'当前教材版本',returnFocus:view,
          load:async()=>({title:edition.title||'当前教材版本',file:await api(`${base}/${id}/file?format=pdf`),
            note:`第 ${edition.number||summary.history.find(v=>v.id===id)?.number||1} 版`,
            actions:[{label:'下载 PDF',run:()=>download('pdf')}],sources:[],versions:[],sections:[]})
        },ui.readerUi);
        await reader.ready;
      }catch(error){if(alive())message.textContent=error.message||'预览尚未准备好，请重试';}
      finally{if(alive())view.disabled=!edition.pdfReady;}
    });view.disabled=!edition.pdfReady;tools.append(view);
    const grid=el('div',null,'me-grid'),left=el('div'),right=el('aside',null,'me-sidebar');right.setAttribute('aria-label','相关候选题');grid.append(left,right);
    root.replaceChildren(tools,message,grid);
    const editable=summary.canEdit&&edition.isCurrent;
    const canCentralReview=edition.canCentralReview===true&&edition.isCurrent===true;
    message.textContent=reviewSaved==='1'?'校对已保存，已读取最新结果并继续下一项。':canCentralReview?'选择题目，核对原题内容和边界后逐题保存。':editable?'选择一道题，右侧会推荐相关候选。保存后所有老师看到新版，旧版和原稿保留。':edition.isCurrent?'选择题目查看内容。':'正在查看历史版本；历史内容和文件保持不变。';
    if(canCentralReview){
      root.classList.add('me-focused-review');
      right.setAttribute('aria-label','题目校对');
      const menu=el('details',null,'me-more-tools');
      menu.append(el('summary','教材选项'));
      const options=el('div',null,'me-more-items');
      options.append(history,docx,pdf);menu.append(options);tools.append(menu);
      if(reviewSaved!=='1')message.textContent='';
    }
    right.append(el('h3','相关候选题'),el('p','点击左侧题目查看推荐','me-muted'));
    const batchSelected=new Set(),batchBoxes=new Map();let batchBar=null,batchCount=null,batchOpen=null,batchViewing=false,batchMode=false,batchToggle=null,singleToggle=null;
    const nodes=new Map();const imageRequests=new Set();const imageResults=new Map();let observer=null;
    const viewport=globalThis.matchMedia?.('(max-width:850px)');
    const placePanel=()=>{if(!alive()){dispose();return;}if(selected&&viewport?.matches){const node=nodes.get(selected.id);left.insertBefore(right,node.nextSibling);}else if(right.parentNode!==grid)grid.append(right);};
    const dispose=()=>{generation++;observer?.disconnect();viewport?.removeEventListener?.('change',placePanel);};
    viewport?.addEventListener?.('change',placePanel);disposeActive=dispose;
    const loadImages=q=>{if(!imageResults.has(q.id))imageResults.set(q.id,api(`${base}/${id}/questions/${q.id}/images`).catch(error=>{imageResults.delete(q.id);throw error;}));return imageResults.get(q.id);};
    const textReference=text=>{const detail=el('details',null,'me-text');detail.append(el('summary','查看文字识别参考'),el('p',text||'此题以原图为准'));return detail;};
    async function images(q,node) {
      if(imageRequests.has(q.id))return;imageRequests.add(q.id);
      try{const result=await loadImages(q);if(!alive())return;const box=el('div',null,'me-images');for(const item of result.images||[]){const img=el('img');img.src=safeUrl(item.url);img.alt=`${q.label} 原题图`;img.loading='lazy';img.onerror=()=>{node.querySelector('.me-text').open=true;};box.append(img);}node.insertBefore(box,node.querySelector('.me-text'));if(!result.images?.length)node.querySelector('.me-text').open=true;}catch(e){if(!alive())return;imageRequests.delete(q.id);node.querySelector('.me-text').open=true;node.append(el('p','原题图暂未读取成功，请查看整册预览','me-muted'));}
    }
    async function finishReview(savedId,excluded,nextQuestionId){
      if(!ui.onReviewProgress)return navigate({editionId:savedId,questionId:nextQuestionId,reviewSaved:'1'});
      const fresh=await api(`${base}/${encodeURIComponent(savedId)}`);if(!alive())return;
      if(!fresh.isCurrent)throw Error('教材已更新，请刷新当前共享版继续。');
      const byId=new Map((fresh.questions||[]).map(q=>[q.id,q]));if(byId.size!==edition.questions.length||edition.questions.some(q=>!byId.has(q.id)))throw Error('题目列表已变化，请刷新后继续。');
      id=savedId;for(const question of edition.questions)Object.assign(question,byId.get(question.id));
      Object.assign(edition,{...fresh,questions:edition.questions});
      if(!summary.history.some(v=>v.id===id)){summary.history.push({id,number:fresh.number});const option=el('option',`第 ${fresh.number} 版 · 当前共享版`);option.value=id;history.append(option);}history.value=id;
      for(const q of edition.questions){const n=nodes.get(q.id),badge=n.querySelector('.me-question-heading')?.querySelector('.me-muted');if(badge)badge.textContent=q.reviewState==='reviewed'?'已核对':'待核对';const warning=n.querySelector('.me-warning');if(q.replaceable)warning?.remove();else if(warning)warning.textContent=(q.reasons||[]).join('；');}
      mutating=false;history.disabled=false;batchSelected.clear();batchMode=false;batchViewing=false;syncBatch();
      const nextId=nextReviewQuestion(excluded),next=edition.questions.find(q=>q.id===nextId);
      message.textContent=next?'校对已保存，已进入下一道待校对题。':'本轮校对已保存。';
      if(next){await choose(next);nodes.get(next.id)?.scrollIntoView?.({block:'nearest'});right.querySelector('h3')?.setAttribute('tabindex','-1');right.querySelector('h3')?.focus?.({preventScroll:true});}
      else{selected=null;generation++;const remaining=edition.questions.filter(q=>q.reviewState!=='reviewed'||!q.replaceable);right.replaceChildren(el('h3',remaining.length?'本轮校对已保存':'本书题目已全部核对'),el('p',remaining.length?`还有 ${remaining.length} 题需要处理边界。`:'可以返回中央教研室继续下一份资料。'));if(remaining.length)right.append(button('继续剩余题目',()=>choose(remaining[0])));}
      ui.onReviewProgress({editionId:id,questionId:nextId,reviewSaved:'1'});
    }
    function nextReviewQuestion(excluded){const questions=edition.questions||[],last=Math.max(...questions.map((q,i)=>excluded.has(q.id)?i:-1)),ordered=[...questions.slice(last+1),...questions.slice(0,last+1)];return ordered.find(q=>!excluded.has(q.id)&&(q.reviewState!=='reviewed'||!q.replaceable))?.id||'';}
    function syncBatch(){if(!batchBar)return;batchBar.hidden=!batchMode;batchToggle?.setAttribute('aria-expanded',String(batchMode));
      root.classList.toggle('me-batch-mode',batchMode);
      if(batchToggle){batchToggle.disabled=mutating;batchToggle.setAttribute('aria-pressed',String(batchMode));singleToggle.setAttribute('aria-pressed',String(!batchMode));singleToggle.disabled=mutating;}
      batchCount.textContent=`已选择 ${batchSelected.size} 题（每批最多 50 题）`;batchOpen.disabled=mutating||batchSelected.size===0;for(const [qid,box] of batchBoxes){box.checked=batchSelected.has(qid);box.disabled=mutating;}for(const control of batchBar.querySelectorAll('button'))if(control!==batchOpen)control.disabled=mutating;}
    function selectionChanged(){syncBatch();if(batchViewing){if(batchSelected.size)openBatch();else if(selected)choose(selected);}}
    function openBatch(){if(mutating||!alive()||!batchSelected.size)return;batchViewing=true;const questions=edition.questions.filter(q=>batchSelected.has(q.id));selected=questions[0];placePanel();const local=++generation;right.replaceChildren(el('h3',`批量校对 · ${questions.length} 题`));reviewPanel(questions[0],local,questions);right.scrollIntoView?.({block:'start'});}
    if(canCentralReview){
      const modeGroup=el('div',null,'review-segments');
      modeGroup.setAttribute('role','group');modeGroup.setAttribute('aria-label','校对方式');
      singleToggle=button('逐题',()=>{
        if(mutating||!batchMode)return;
        batchMode=false;batchViewing=false;batchSelected.clear();syncBatch();
        const current=selected||edition.questions[0];if(current)choose(current);
      });
      batchToggle=button('批量',()=>{
        if(mutating||batchMode)return;
        batchMode=true;batchViewing=false;generation++;syncBatch();
        right.replaceChildren(el('p','勾选要校对的题目，再打开所选原题。','me-muted'));
      });
      modeGroup.append(singleToggle,batchToggle);tools.insertBefore(modeGroup,view);
      batchBar=el('div',null,'me-batch-tools');batchBar.setAttribute('aria-label','批量校对');batchCount=el('p');batchOpen=button('校对所选题目',openBatch);batchOpen.classList.add('me-primary');batchBar.append(batchCount,button('选择未校对题',()=>{if(mutating)return;batchSelected.clear();for(const q of edition.questions.filter(q=>q.reviewState!=='reviewed'||!q.replaceable).slice(0,50))batchSelected.add(q.id);selectionChanged();}),button('取消选择',()=>{if(mutating)return;batchSelected.clear();selectionChanged();}),batchOpen);root.insertBefore(batchBar,grid);syncBatch();}
    function reviewPanel(q,local,group=null) {
      const questions=group||[q],multiple=!!group,excluded=new Set(questions.map(item=>item.id)),nextQuestionId=nextReviewQuestion(excluded);
      const saveLabel=multiple?`确认这 ${questions.length} 题并继续`:'保存并下一题';
      const panel=el('section',null,'me-review');
      const evidence=el('div',null,'me-review-evidence');panel.append(evidence);
      const status=el('p','正在读取本题原图…','me-review-status');status.setAttribute('role','status');
      const content=el('input');content.type='checkbox';content.name='confirmContent';
      const boundary=el('input');boundary.type='checkbox';boundary.name='confirmBoundary';
      const contentLabel=el('label');contentLabel.append(content,el('span',multiple?'已核对以上所选题目的题干、公式和图形':'已核对题干、公式和图形内容'));
      const boundaryLabel=el('label');boundaryLabel.append(boundary,el('span',multiple?'已核对所选题目的边界，没有漏题、跨题或截断':'已核对题目边界，没有漏题、跨题或截断'));
      const note=el('textarea');note.rows=1;note.maxLength=500;note.placeholder='校对说明（可选）';note.setAttribute('aria-label','校对说明（可选）');
      let ready=false,stale=false,boundaryBlocked=questions.some(item=>item.boundaryCanConfirm===false),savedEditionId='';
      const boundaryHelp=el('p',boundaryBlocked?'当前范围包含其他题目，需要重新拆题；可先确认题目内容。':'未确认边界时，只保存内容校对；边界问题仍会保留。',boundaryBlocked?'me-warning':'me-muted');
      const live=()=>alive()&&generation===local;
      const sync=()=>{save.disabled=!ready||!content.checked||stale||mutating;content.disabled=note.disabled=stale||mutating;boundary.disabled=boundaryBlocked||stale||mutating;syncBatch();};
      const save=button(saveLabel,async()=>{
        if(!live()||mutating||stale||!ready||!content.checked)return;
        mutating=true;history.disabled=true;sync();save.textContent='正在保存…';status.textContent='正在保存本题校对…';
        try {
          const payload={revision:edition.revision,...(multiple?{questions:questions.map(item=>({occurrenceId:item.id,reviewRevision:item.reviewRevision}))}:{reviewRevision:q.reviewRevision}),confirmContent:true,confirmBoundary:!boundaryBlocked&&boundary.checked};if(note.value.trim())payload.note=note.value.trim();
          const result=await api(multiple?`/api/central-research/material-versions/${encodeURIComponent(id)}/review-batch`:`/api/central-research/material-versions/${encodeURIComponent(id)}/questions/${encodeURIComponent(q.id)}/review`,{method:'POST',body:payload});
          if(!live())return;savedEditionId=result.editionId||id;
          await finishReview(savedEditionId,excluded,nextQuestionId);
        } catch(error) {
          if(!live())return;
          if(savedEditionId){status.textContent='本题校对已保存，读取最新结果失败，请刷新后继续。';save.textContent='本题已保存';actions.append(button('读取已保存版本',()=>finishReview(savedEditionId,excluded,nextQuestionId)));return;}
          mutating=false;history.disabled=false;
          const needsSplit=/重新拆题|正文范围重叠/.test(error.message||'');
          stale=!needsSplit&&(error.status===409||error.statusCode===409);if(multiple)status.textContent='本批未保存。';
          if(needsSplit){boundaryBlocked=true;boundary.checked=false;boundaryHelp.className='me-warning';boundaryHelp.textContent=/重叠|包含其他题目/.test(error.message)?'当前范围包含其他题目，需要重新拆题；可先确认题目内容。':`${error.message}；可先确认题目内容。`;}
          status.textContent=needsSplit?boundaryHelp.textContent:stale?`${error.message||'教材或本题校对已更新'}；请刷新最新版本后再核对。`:error.message||'校对未保存，请重试';
          if(stale){const refresh=button('刷新最新版本',()=>navigate({editionId:'',questionId:q.id,reviewSaved:''}));actions.append(refresh);}
          save.textContent=saveLabel;sync();
        }
      });save.classList.add('me-primary');save.disabled=true;
      const actions=el('div',null,'me-review-actions');actions.append(save);
      content.onchange=boundary.onchange=sync;
      const notes=el('details',null,'me-notes');notes.append(el('summary','添加校对说明'),note);
      panel.append(status,contentLabel);
      if(questions.some(item=>!item.replaceable))panel.append(boundaryLabel,boundaryHelp);
      panel.append(notes,actions);right.append(panel);sync();
      let outstanding=questions.length,failed=false;
      const loaded=()=>{if(!live())return;ready=!failed&&outstanding===0;status.textContent=failed?'所选题目有原图或正文读取失败，暂不能确认；请重新打开重试。':outstanding?'正在读取所选题目的原图…':'';sync();};
      const sections=questions.map(item=>{const box=el('div');if(multiple)box.append(el('h5',item.label));if(String(item.text||'').trim())box.append(el('p',item.text));const imageBox=el('div',null,'me-images');box.append(imageBox);evidence.append(box);return {item,imageBox};});
      let cursor=0;
      const worker=async()=>{while(cursor<sections.length&&live()){const {item,imageBox}=sections[cursor++];try{const result=await loadImages(item);if(!live())return;const assets=result.images||[];if(!assets.length&&!String(item.text||'').trim())failed=true;outstanding+=assets.length;
       for(const asset of assets){const img=el('img');img.alt=`${item.label} 原题图`;img.onload=()=>{outstanding--;loaded();};img.onerror=()=>{failed=true;outstanding--;loaded();};img.src=safeUrl(asset.url);imageBox.append(img);}
      }catch{failed=true;}outstanding--;loaded();}};
      for(let i=0;i<Math.min(3,sections.length);i++)void worker();loaded();
    }

    async function choose(q) {
      if(mutating||!alive())return;
      batchViewing=false;selected=q;placePanel();candidate=null;pendingRequest=null;const local=++generation;for(const [qid,n] of nodes)n.setAttribute('aria-selected',String(qid===q.id));if(!canCentralReview)images(q,nodes.get(q.id));
      root.dispatchEvent(new CustomEvent('siyue:material-question-selected',{bubbles:true,detail:{materialVersionId:id,occurrenceId:q.id,revision:edition.revision,canonicalQuestionId:q.canonicalQuestionId}}));
      right.replaceChildren(el('h3',`${q.label} · ${canCentralReview?'逐题校对':'相关候选题'}`));
      if(canCentralReview){const questions=edition.questions||[],at=questions.findIndex(item=>item.id===q.id),ordered=[...questions.slice(at+1),...questions.slice(0,at)];const next=ordered.find(item=>item.reviewState!=='reviewed'||!item.replaceable);if(next){const nextButton=button('跳过此题',()=>{choose(next);nodes.get(next.id)?.scrollIntoView?.({block:'nearest'});});nextButton.classList.add('me-next');right.append(nextButton);}else if(q.reviewState==='reviewed'&&q.replaceable)right.append(el('p','本版题目已全部核对。','me-muted'));reviewPanel(q,local);}
      if(!editable){if(!canCentralReview)right.append(el('p',edition.isCurrent?'当前账号不能更换题目。':'历史版本不可更改，请切换到当前共享版。'));return;}
      if(!q.replaceable){right.append(el('p',(q.reasons||['本题边界需要核对']).join('；'),'me-warning'));return;}
      if(canCentralReview){const show=button('查看替换候选',()=>{if(mutating||!alive()||generation!==local)return;show.remove();return loadCandidates();});right.append(show);return;}
      return loadCandidates();
      async function loadCandidates(){
      const loading=el('p','正在按学科、学段和知识点匹配…','me-muted');right.append(loading);
      try{
        const found=await api(`${base}/${id}/questions/${q.id}/recommendations`);if(!alive()||generation!==local)return;loading.remove();right.append(el('p',found.message,'me-muted'));
        for(const item of found.matches||[]){
          const card=el('div',null,'me-candidate');card.append(el('small',item.source,'me-muted'),item.candidateId.startsWith('bank:')?el('p',item.text):textReference(item.text));card.append(el('p',(item.reasons||[]).join('；'),'me-muted'));
          if(!item.candidateId.startsWith('bank:')) {
            const box=el('div',null,'me-images');card.insertBefore(box,card.querySelector('.me-text'));
            api(`${base}/${id}/questions/${q.id}/candidates/${encodeURIComponent(item.candidateId)}/images`).then(result=>{if(!alive()||generation!==local)return;for(const asset of result.images||[]){const image=el('img');image.src=safeUrl(asset.url);image.alt='候选题原图';image.loading='lazy';image.onerror=()=>{card.querySelector('.me-text').open=true;};box.append(image);}if(!result.images?.length)card.querySelector('.me-text').open=true;}).catch(()=>{card.querySelector('.me-text').open=true;box.append(el('small','候选原图读取失败，请稍后再选用','me-warning'));});
          }
          if(!item.replaceable)card.append(el('p',item.unavailableReason||'暂不能完整导出此题','me-warning'));
          const pick=button('选用此题',()=>{if(mutating)return;candidate=item;pendingRequest=requestId();for(const n of right.querySelectorAll('.me-candidate'))n.dataset.selected='false';card.dataset.selected='true';save.disabled=false;save.textContent=`保存为共享第 ${edition.number+1} 版`;});pick.disabled=!item.replaceable;card.append(pick);right.append(card);
        }
        const save=button('先选择候选题',async()=>{
          if(mutating||!alive()||generation!==local||!candidate||!selected)return;mutating=true;history.disabled=true;save.disabled=true;save.textContent='正在生成新版 Word 和 PDF…';message.textContent='正在生成完整文件，成功后才更新共享版本。';
          try{
            const result=await api(`${base}/${id}/questions/${selected.id}/replace`,{method:'POST',body:{revision:edition.revision,candidateId:candidate.candidateId,requestId:pendingRequest}});
            if(!alive())return;root.dispatchEvent(new CustomEvent('siyue:material-question-replaced',{bubbles:true,detail:{materialVersionId:result.editionId,revision:result.revision,occurrenceId:selected.id}}));
            await navigate({editionId:result.editionId,questionId:q.id});
          }catch(e){if(!alive())return;mutating=false;history.disabled=false;message.textContent=e.message||'未能生成新版，当前版本未改变';save.disabled=false;save.textContent='重试保存共享新版';}
        });save.classList.add('me-primary');save.disabled=true;right.append(save);
      }catch(e){if(generation===local){loading.textContent=e.message||'暂未能读取推荐，请稍后再试';}}
      }
    }
    if(!canCentralReview&&typeof IntersectionObserver!=='undefined')observer=new IntersectionObserver(entries=>{if(!alive()){observer.disconnect();return;}for(const item of entries){if(item.isIntersecting){observer.unobserve(item.target);const q=edition.questions.find(q=>nodes.get(q.id)===item.target);if(q)images(q,item.target);}}},{rootMargin:'180px'});
    for(const q of edition.questions||[]){const n=el('div',null,'me-question');n.tabIndex=0;n.setAttribute('role','group');n.setAttribute('aria-label',q.label);n.setAttribute('aria-selected','false');const title=el('div',null,'me-question-heading');const selectQuestion=button('',event=>{event?.stopPropagation?.();choose(q);});selectQuestion.classList.add('me-edit');selectQuestion.setAttribute('aria-label',(canCentralReview?'校对':'查看')+q.label);selectQuestion.title=(canCentralReview?'校对':'查看')+q.label;selectQuestion.innerHTML='<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m16 3 5 5-12 12-6 1 1-6Z M14 5l5 5"/></svg>';if(canCentralReview){const pick=el('input');pick.type='checkbox';pick.setAttribute('aria-label','选择'+q.label);pick.onchange=()=>{if(mutating){pick.checked=batchSelected.has(q.id);return;}if(pick.checked){if(batchSelected.size>=50){pick.checked=false;message.textContent='每批最多选择50题，请先处理本批。';return;}batchSelected.add(q.id);}else batchSelected.delete(q.id);selectionChanged();};const label=el('label',null,'me-batch-select');label.onclick=event=>event.stopPropagation();label.append(pick,el('span','选择'));title.append(label);batchBoxes.set(q.id,pick);}title.append(el('strong',q.label),selectQuestion,el('span',q.reviewState==='reviewed'?'已核对':'待核对','me-muted'));n.append(title,canCentralReview?el('p',(q.text||'图片题').slice(0,96),'me-brief'):textReference(q.text));
      if(!q.replaceable)n.append(el('p',(q.reasons||[]).join('；'),'me-warning'));const activate=()=>{if(batchMode){batchBoxes.get(q.id)?.click();}else choose(q);};n.onclick=activate;n.onkeydown=e=>{if((!e.target||e.target===n)&&(e.key==='Enter'||e.key===' ')){e.preventDefault();activate();}};nodes.set(q.id,n);left.append(n);if(observer)observer.observe(n);else if(!canCentralReview&&nodes.size<=4)images(q,n);}
    if(!edition.questions?.length)left.append(el('p','暂无边界清楚的题目；正文内容仍保留在整册文件中。'));
    const restored=(edition.questions||[]).find(q=>q.id===questionId);const firstPending=canCentralReview&&reviewSaved!=='1'?(edition.questions||[]).find(q=>q.reviewState!=='reviewed'||!q.replaceable):null;if(restored||firstPending){await choose(restored||firstPending);if(reviewSaved==='1')nodes.get((restored||firstPending).id)?.scrollIntoView?.({block:'start'});}else if(canCentralReview&&reviewSaved==='1'){const remaining=edition.questions.filter(q=>q.reviewState!=='reviewed'||!q.replaceable);right.replaceChildren(el('h3',remaining.length?'本轮校对已保存':'本书题目已全部核对'),el('p',remaining.length?`还有 ${remaining.length} 题需要处理内容或边界。`:'可以返回中央教研室继续下一份资料。'));if(remaining.length)right.append(button('继续剩余题目',()=>choose(remaining[0])));}syncBatch();
    return {destroy(){dispose();root.remove();}};
  }catch(e){if(alive())message.textContent=e.message||'暂未能读取教材版本';}
}
