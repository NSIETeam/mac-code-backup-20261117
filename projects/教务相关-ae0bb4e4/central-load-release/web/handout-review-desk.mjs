// Batch decisions belong to the exact handout revision currently on screen.
export function reviewModeMarkup(id, revision) {
  return `<div class="review-segments" role="group" aria-label="校对方式" data-review-desk="${id}" data-revision="${revision}">
    <button type="button" data-mode="single" aria-pressed="true">逐题</button>
    <button type="button" data-mode="batch" aria-pressed="false">批量</button></div>`;
}
export function readReviewSelection(id, revision) {
  const desk=document.querySelector('[data-review-desk]');
  if(desk?.dataset.reviewDesk!==id || desk.dataset.revision!==String(revision) ||
     desk.querySelector('[data-mode=batch]').getAttribute('aria-pressed')!=='true')return null;
  return [...document.querySelectorAll('.review-batch-mark input:checked')].map(b=>b.value);
}
export function mountReviewDesk({main, questions, eligible, restored, submit, run}) {
  const modes=main.querySelector('[data-review-desk]');
  if(!modes)return;
  const cards=main.querySelector('.review-pages'),marks=new Map();
  const eligibleIds=new Set(eligible.map(q=>q.id)),selected=new Set((restored||[]).filter(id=>eligibleIds.has(id)));
  const remaining=questions.filter(q=>!q.confirmed).length;
  const progress=document.createElement('p');
  progress.className='review-progress';
  progress.textContent=remaining?`待校对 ${remaining} / ${questions.length} 题`:`已校对 ${questions.length} 题`;
  const batchBar=document.createElement('div');batchBar.className='review-batch-bar';batchBar.hidden=true;
  const all=document.createElement('input');all.type='checkbox';
  const choice=document.createElement('label');choice.append(all,eligible.length>1000?' 选前 1000 道可校对题':' 全选可校对题');
  const save=document.createElement('button');save.type='button';save.className='primary';
  batchBar.append(choice,save);cards.before(progress,batchBar);
  for(const card of cards.querySelectorAll('.review-question')){
    card.querySelector('.review-batch-mark')?.remove();
    const id=card.dataset.questionId;
    const mark=document.createElement('label');mark.className='review-batch-mark';
    const input=document.createElement('input');input.type='checkbox';input.value=id;input.disabled=!eligibleIds.has(id);
    const label=questions.find(q=>q.id===id)?.label||'本题';
    input.setAttribute('aria-label','选择'+label);mark.append(input);
    card.querySelector('.question-toolbar').prepend(mark);
    input.onchange=()=>{if(input.checked)selected.add(id);else selected.delete(id);sync();};
    marks.set(id,input);
  }
  function sync(){
    for(const [id,box] of marks){box.checked=selected.has(id);box.disabled=!eligibleIds.has(id)||(!box.checked&&selected.size>=1000);box.closest('article').classList.toggle('batch-selected',box.checked);}
    all.checked=eligible.length>0&&selected.size===Math.min(1000,eligible.length);
    all.indeterminate=selected.size>0&&selected.size<eligible.length;
    all.disabled=!eligible.length;save.disabled=!selected.size;
    save.textContent=selected.size?'已核对，确认所选 '+selected.size+' 题':'确认所选题目';
  }
  function setMode(batch){
    main.classList.toggle('review-batching',batch);batchBar.hidden=!batch;
    modes.querySelector('[data-mode=single]').setAttribute('aria-pressed',String(!batch));
    modes.querySelector('[data-mode=batch]').setAttribute('aria-pressed',String(batch));
    if(!batch)selected.clear();
    sync();
  }
  all.onchange=()=>{selected.clear();if(all.checked)eligible.slice(0,1000).forEach(q=>selected.add(q.id));sync();};
  save.onclick=()=>{if(selected.size)void run(()=>submit([...selected]));};
  modes.querySelector('[data-mode=single]').onclick=()=>setMode(false);
  modes.querySelector('[data-mode=batch]').onclick=()=>setMode(true);
  modes.querySelector('[data-mode=batch]').disabled=!eligible.length;setMode(restored!==null&&eligible.length>0);
}
