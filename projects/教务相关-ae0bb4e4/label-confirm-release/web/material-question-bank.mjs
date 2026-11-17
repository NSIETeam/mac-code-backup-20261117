import {escapeHtml as escape} from './core.mjs';
import {bindTrayDocument} from './print-drop.mjs?v=20261107-paper-tray';

const filterKeys = ['q','subject','grade','tag','source','scope'];
function readCart(key) {
  try {
    const stored = JSON.parse(sessionStorage.getItem(key) || '[]');
    return stored.filter(q=>typeof q.id==='string' && typeof q.revision==='string').slice(0,50);
  } catch {return [];}
}
function options(items,value,all) {
  return '<option value="">'+all+'</option>'+items.map(item=>{
    const {id,name}=typeof item==='string'?{id:item,name:item}:item;
    return `<option value="${escape(id)}" ${id===value?'selected':''}>${escape(name)}</option>`;
  }).join('');
}
function control(text,handler,label) {
  const b=document.createElement('button');
  b.type='button'; b.className='quiet'; b.textContent=text;
  if(label)b.setAttribute('aria-label',label);
  b.onclick=handler;
  return b;
}
export async function renderMaterialQuestionBank(params,ui) {
  const {api,paint,heading,button,go,modal,openResource,userId}=ui;
  const storageKey='siyue.material-bank.cart:'+userId;
  let selected=readCart(storageKey);
  const page=Number(params.page)||1,query=new URLSearchParams({page});
  for(const key of filterKeys)if(params[key])query.set(key,params[key]);
  const [data,papers]=await Promise.all([api('/material-question-bank?'+query),api('/material-question-bank/papers')]);
  const segments=data.subjects.length<=4;
  const select=(label,key,items,all)=>`<label>${label}<select name="${key}">${options(items,params[key],all)}</select></label>`;
  const extraFilters=['grade','tag','source','scope',...(segments?[]:['subject'])].filter(k=>params[k]).length;
  const subjectControl=segments ? `<div class="qb-segments" role="group" aria-label="学科">
    <input name="subject" type="hidden" value="${escape(params.subject||'')}">
    ${['',...data.subjects].map(s=>`<button type="button" data-subject="${escape(s)}" aria-pressed="${(params.subject||'')===s}">${escape(s||'全部')}</button>`).join('')}
    </div>`:'';
  paint(heading('题库',()=>go('material-library'))+`<section id="material-question-bank">
    <form id="qb-filter" class="qb-filters" role="search">
      <div class="qb-search"><input type="search" name="q" aria-label="查找题目" placeholder="查找题目或教材" value="${escape(params.q||'')}"><button aria-label="搜索" type="submit">⌕</button></div>
      ${subjectControl}
      <details class="qb-advanced"><summary>筛选${extraFilters?'（'+extraFilters+'）':''}</summary>
        <div class="qb-filter-options">${segments?'':select('学科','subject',data.subjects,'全部学科')}
          ${select('年级','grade',data.grades,'全部年级')}${select('知识点','tag',data.tags,'全部知识点')}
          ${select('来源教材','source',data.sources,'全部教材')}
          ${select('资料范围','scope',[{id:'shared',name:'共享教材'},{id:'mine',name:'班级及可访问资料'}],'全部可见资料')}
        </div>
      </details>${filterKeys.some(k=>params[k])?'<button type="button" id="qb-reset" class="quiet">清除筛选</button>':''}
    </form>
    <div class="qb-summary"><span>校对题目 · ${data.total} 道</span><button type="button" id="qb-selected-count" aria-controls="qb-cart" aria-expanded="false"></button></div>
    <div class="qb-layout"><div class="qb-catalog"><div id="qb-results" class="qb-results"></div>
      <div class="qb-pages">${button('上一页',()=>go('question-bank',{...params,page:String(page-1)}),'quiet',page<=1)}<span>${page} / ${Math.max(1,Math.ceil(data.total/data.pageSize))}</span>${button('下一页',()=>go('question-bank',{...params,page:String(page+1)}),'quiet',page*data.pageSize>=data.total)}</div>
    </div><aside id="qb-cart" class="qb-cart">
      <span class="qb-tab">选题夹</span><div class="qb-cart-heading"><h2>已选题目</h2><span id="qb-quantity"></span></div>
      <ol id="qb-cart-list"></ol><p id="qb-empty">点题卡选入讲义。</p>
      <form id="qb-generate"><label>讲义标题<input name="title" value="专项练习" required maxlength="120"></label>
        <div class="qb-meta-fields"><label>讲次<input name="lecture" placeholder="第几讲" maxlength="80"></label><label>日期<input name="date" type="date" value="${new Date(Date.now()+8*3600000).toISOString().slice(0,10)}" required></label></div>
        <div class="qb-create-actions"><button class="primary" id="qb-create">生成讲义</button><button type="button" id="qb-tray-paper" class="qb-file-link" title="生成讲义并放入进纸托盘，也可拖到托盘">放入托盘</button></div>
      </form></aside></div><p id="qb-error" role="alert"></p>
    <section class="qb-papers"><h2>我生成的讲义</h2><div id="qb-paper-list"></div></section></section>`);
  const mount=document.getElementById('material-question-bank');
  const find=s=>mount.querySelector(s);
  const filterForm=find('#qb-filter'),form=find('#qb-generate'),toggle=find('#qb-selected-count');
  const narrow=matchMedia('(max-width:760px)');
  let inCart=false,returnY=0,generating=false,lastInput='',requestId=crypto.randomUUID();
  const cardButtons=new Map();
  const shortText=q=>(q.text||'图片题，展开查看原图').slice(0,260);
  function notifyError(message) {
    const error=find('#qb-error');error.textContent=message;error.scrollIntoView({block:'nearest'});
  }
  function cartView(open,scroll=true) {
    inCart=open && narrow.matches;
    mount.classList.toggle('qb-cart-open',inCart);
    toggle.setAttribute('aria-expanded',String(inCart));
    toggle.textContent=inCart?'‹ 继续选题':'已选 '+selected.length+' 道';
    if(scroll) {
      if(inCart)mount.scrollIntoView({block:'start'});
      else window.scrollTo({top:returnY,behavior:'instant'});
    }
  }
  toggle.onclick=()=>{if(!inCart)returnY=window.scrollY;cartView(!inCart);};
  const applyFilters=()=>{
    const next=Object.fromEntries([...new FormData(filterForm)].filter(([,value])=>value));
    go('question-bank',next);
  };
  filterForm.onsubmit=event=>{event.preventDefault();applyFilters();};
  filterForm.onchange=event=>{if(event.target.matches('select'))applyFilters();};
  for(const b of filterForm.querySelectorAll('[data-subject]'))b.onclick=()=>{filterForm.elements.subject.value=b.dataset.subject;applyFilters();};
  const reset=find('#qb-reset');if(reset)reset.onclick=()=>go('question-bank');
  function syncPick(id) {
    const b=cardButtons.get(id);if(!b)return;
    const checked=selected.some(q=>q.id===id);
    b.setAttribute('aria-pressed',String(checked));b.querySelector('.qb-mark').textContent=checked?'✓':'+';
    b.closest('article').classList.toggle('qb-picked',checked);
  }
  function changed(id) {
    sessionStorage.setItem(storageKey,JSON.stringify(selected));
    find('#qb-error').textContent='';
    if(id)syncPick(id);else cardButtons.forEach((_,key)=>syncPick(key));
    drawCart();
  }
  function pick(q) {
    if(generating)return;
    const i=selected.findIndex(item=>item.id===q.id);
    if(i>=0)selected.splice(i,1);
    else if(selected.length<50)selected.push({id:q.id,revision:q.revision,label:q.label,sourceTitle:q.sourceTitle,text:shortText(q),topic:q.tags?.[0]||''});
    else {notifyError('每份讲义最多选 50 道题。');return;}
    changed(q.id);
  }
  function addCard(q) {
    const card=document.createElement('article');card.className='qb-question';card.dataset.questionId=q.id;
    const info=[q.subject,q.grade,...(q.tags||[])].filter(Boolean).join(' / ');
    card.innerHTML=`<button type="button" class="qb-pick"><span class="qb-kicker">${escape(info)}</span>
      <span class="qb-mark" aria-hidden="true"></span><span class="qb-question-text">${escape(shortText(q))}</span>
      <span class="qb-origin">${escape([q.sourceTitle,q.lecture,q.label].filter(Boolean).join(' · '))}</span></button>
      <details><summary>查看完整题目与原图</summary><div class="qb-original"></div></details>`;
    const b=card.querySelector('.qb-pick');b.onclick=()=>pick(q);cardButtons.set(q.id,b);syncPick(q.id);
    const details=card.querySelector('details'),original=card.querySelector('.qb-original');
    let loaded=false;
    details.ontoggle=async()=>{
      if(!details.open||loaded)return;
      loaded=true;original.textContent='正在读取原题…';
      try{
        const detail=await api('/material-question-bank/question?id='+encodeURIComponent(q.id));
        if(!mount.isConnected)return;
        original.innerHTML='<p class="qb-full-text">'+escape(detail.question.text||'请查看原题图片')+'</p>';
        for(const url of detail.images){const img=document.createElement('img');img.loading='lazy';img.src=ui.safeResource(url);img.alt='题目原图';original.append(img);}
        const a=document.createElement('a');a.href=detail.question.openRoute;a.textContent='查看来源教材';original.append(a);
      }catch(error){loaded=false;original.textContent=error.message;}
    };
    find('#qb-results').append(card);
  }
  function move(i,direction){const other=i+direction;[selected[i],selected[other]]=[selected[other],selected[i]];changed();}
  function drawCart() {
    cartView(inCart,false);
    find('#qb-quantity').textContent=selected.length+' / 50';
    find('#qb-empty').hidden=!!selected.length;
    const list=find('#qb-cart-list');list.replaceChildren();
    selected.forEach((q,i)=>{
      const li=document.createElement('li');
      li.innerHTML=`<span class="qb-no">${String(i+1).padStart(2,'0')}</span><div class="qb-picked-title"><strong>${escape([q.label,q.topic].filter(Boolean).join(' · '))}</strong><small>${escape(q.sourceTitle)}</small></div><div class="qb-order"></div>`;
      const up=control('↑',()=>move(i,-1),'上移第'+(i+1)+'题'),down=control('↓',()=>move(i,1),'下移第'+(i+1)+'题');
      up.disabled=!i;down.disabled=i===selected.length-1;
      li.lastElementChild.append(up,down,control('×',()=>{selected.splice(i,1);changed(q.id);},'移除第'+(i+1)+'题'));
      list.append(li);
    });
    for(const b of form.querySelectorAll('button'))b.disabled=generating||!selected.length;
  }
  async function generate(forTray=false) {
    if(generating||!selected.length||!form.reportValidity())return;
    const values=Object.fromEntries(new FormData(form));
    const input={...values,questions:selected.map(({id,revision})=>({id,revision}))};
    const signature=JSON.stringify(input);
    if(lastInput&&signature!==lastInput)requestId=crypto.randomUUID();
    lastInput=signature;generating=true;form.inert=true;find('#qb-cart-list').inert=true;
    find('#qb-create').textContent='正在提交…';find('#qb-error').textContent='';
    try{
      const doc=await api('/material-question-bank/papers','POST',{...input,requestId});
      if(!mount.isConnected)return;
      const old=papers.findIndex(p=>p.id===doc.id);if(old>=0)papers.splice(old,1);
      papers.unshift({...doc,title:values.title});selected=[];changed();drawPapers();
      requestId=crypto.randomUUID();lastInput='';
      if(!forTray){cartView(false,false);find('.qb-papers').scrollIntoView({block:'start'});}
      return {id:doc.id,name:values.title};
    }catch(error){if(mount.isConnected)notifyError(error.message);}
    finally{generating=false;form.inert=false;find('#qb-cart-list').inert=false;find('#qb-create').textContent='生成讲义';drawCart();}
  }
  form.onsubmit=event=>{event.preventDefault();void generate();};
  bindTrayDocument(find('#qb-tray-paper'),()=>generate(true));
  function drawPapers() {
    const list=find('#qb-paper-list');list.replaceChildren();
    if(!papers.length){list.innerHTML='<p class="muted">暂无已生成的讲义。</p>';return;}
    for(const p of papers){
      const row=document.createElement('div');row.className='qb-paper-row';
      const state={queued:'排队中',processing:'正在生成',ready:'已生成',failed:p.error||'生成失败'}[p.state]||p.state;
      row.innerHTML=`<div><strong>${escape(p.title)}</strong><small>${escape(state)}</small></div><div class="qb-paper-actions"></div>`;
      const actions=row.lastElementChild;
      if(p.state==='ready'){
        for(const [label,format] of [['预览','preview'],['Word','docx'],['PDF','pdf']]){
          const b=control(label,async()=>{
            b.disabled=true;
            try{
              const f=await api('/exports/'+encodeURIComponent(p.id)+'/download?format='+(format==='preview'?'pdf':format));
              if(format==='preview')modal(p.title,'<iframe class="qb-pdf" title="讲义预览" src="'+escape(ui.safeResource(f.url))+'"></iframe>',true);
              else await openResource(f.url,f.fileName);
            }catch(error){notifyError(error.message);}finally{b.disabled=false;}
          });actions.append(b);
        }
      }
      if(['queued','processing','ready'].includes(p.state)){
        const b=control('放入托盘',null,'将'+p.title+'放入进纸托盘');b.title='点击或拖到进纸托盘';
        const prepare=()=>({id:p.id,name:p.title});
        bindTrayDocument(b,prepare);actions.append(b);
        const name=row.querySelector('strong');name.draggable=true;name.dataset.printSource=JSON.stringify({kind:'question-paper',id:p.id,name:p.title});
      }
      if(p.state==='failed'){
        const b=control('重试生成',async()=>{b.disabled=true;try{await api('/material-question-bank/papers/'+p.id+'/retry','POST',{});p.state='queued';drawPapers();}catch(error){notifyError(error.message);b.disabled=false;}});actions.append(b);
      }
      list.append(row);
    }
  }
  if(data.items.length)data.items.forEach(addCard);else find('#qb-results').innerHTML='<p>当前筛选下暂无已校对题目。</p>';
  drawCart();drawPapers();
  async function poll(){
    if(!mount.isConnected)return;
    const pending=papers.filter(p=>['queued','processing'].includes(p.state));
    for(const p of pending){try{Object.assign(p,await api('/material-question-bank/papers/'+p.id));}catch{if(!mount.isConnected)return;}}
    if(!mount.isConnected)return;if(pending.length)drawPapers();setTimeout(poll,pending.length?4000:15000);
  }
  setTimeout(poll,4000);
}
