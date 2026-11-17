// Confirming a single box only updates the local list; only Save submits it.
export function validateRegions(regions,bounds){
 if(!regions.length)throw Error('请至少框选一道题。');
 for(const r of regions){if(!r.label.trim())throw Error('请填写每个选框的题号。');if(![r.left,r.top,r.right,r.bottom].every(Number.isFinite)||r.left<bounds[0]-1e-7||r.top<bounds[1]-1e-7||r.right>bounds[2]+1e-7||r.bottom>bounds[3]+1e-7||r.right-r.left<.002||r.bottom-r.top<.002)throw Error('选框超出当前内容范围或太小。');}
 for(let i=0;i<regions.length;i++)for(let j=0;j<i;j++){const a=regions[i],b=regions[j];if(Math.min(a.right,b.right)-Math.max(a.left,b.left)>1e-7&&Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)>1e-7)throw Error('选框不能重叠。');}
 return (bounds[2]-bounds[0])*(bounds[3]-bounds[1])-regions.reduce((sum,r)=>sum+(r.right-r.left)*(r.bottom-r.top),0)>1e-6;
}
export function mountRegionEditor(host,{url,bounds,label,onSave,onError,mode='split'}){
 const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const trim=mode==='trim',defaultLabel=index=>trim?(String(label||'题目').trim()||'题目'):'题'+(index+1);
 let regions=[],pending=null,editing=null,draft=null,drag=null,beforeDrag=null,saving=false;const history=[];
 const copy=value=>JSON.parse(JSON.stringify(value)),state=()=>copy({regions,pending,editing});
 const remember=(value=state())=>history.push(value);
 host.classList.add('region-simple');host.classList.toggle('region-simple-trim',trim);
 host.innerHTML=`<p class="muted">${trim?'框选要保留的完整正文，再确认保留范围。':'先框选一道题并点“确认单题”，再继续框下一题。所有题目都在右侧列表后，点“确认全部框选结果”保存。'}</p><div class="region-toolbar"><button type="button" data-region="zoom">放大原图</button><button type="button" data-region="undo">撤销上一步</button></div><div class="region-canvas"><img src="${esc(url)}" alt="待框选原稿" draggable="false"><svg viewBox="0 0 1000 1000" preserveAspectRatio="none" aria-label="${trim?'拖动框选保留内容':'拖动框选题目'}"></svg></div><div class="region-side">${trim?'':`<section class="region-current"><h3>当前单题</h3><p class="region-current-status" aria-live="polite"></p><label class="field">题号<input class="region-current-label" maxlength="120" aria-label="当前单题题号"></label><button type="button" class="primary region-confirm-one">确认单题</button><p class="muted">只加入下方列表，仍可继续框选。</p></section><h3>已确认单题</h3><div class="region-list"></div>`}</div><div class="region-submit"><p class="region-save-status" aria-live="polite"></p><button type="button" class="primary region-save"></button><p class="muted">${trim?'保存后可在校对页撤销此次框选。':'保存时只提交列表中的题目，未框选部分不会作为题目保存。保存后可在校对页撤销此次拆题。'}</p></div>`;
 const canvas=host.querySelector('.region-canvas'),svg=host.querySelector('svg'),list=host.querySelector('.region-list'),undo=host.querySelector('[data-region="undo"]'),save=host.querySelector('.region-save');
 const one=host.querySelector('.region-confirm-one'),input=host.querySelector('.region-current-label'),status=host.querySelector('.region-current-status'),summary=host.querySelector('.region-save-status');
 const image=canvas.querySelector('img');
 const fit=()=>{if(image.naturalWidth)canvas.style.setProperty('--region-fit-width',`${Math.max(320,window.innerHeight*.55)*image.naturalWidth/image.naturalHeight}px`);};image.onload=fit;if(image.complete)fit();
 host.querySelector('[data-region="zoom"]').onclick=ev=>{const zoom=canvas.classList.toggle('zoomed');ev.target.textContent=zoom?'适应窗口':'放大原图';};
 const paint=()=>{
  const boxes=(trim&&draft?[]:regions).map((r,i)=>({...r,index:i+1})).filter((_,i)=>i!==editing);
  if(draft||pending)boxes.push({...draft||pending,index:editing==null?regions.length+1:editing+1,pending:true});
  svg.innerHTML=`<rect x="${bounds[0]*1000}" y="${bounds[1]*1000}" width="${(bounds[2]-bounds[0])*1000}" height="${(bounds[3]-bounds[1])*1000}" class="region-limit"/>`+boxes.map(r=>`<rect ${r.pending?'class="region-pending-box"':''} x="${r.left*1000}" y="${r.top*1000}" width="${(r.right-r.left)*1000}" height="${(r.bottom-r.top)*1000}"/>${trim?'':`<text x="${r.left*1000+4}" y="${r.top*1000+22}">${r.index}${r.pending?' 待确认':''}</text>`}`).join('');
 };
 const redraw=()=>{
  paint();undo.disabled=!history.length||saving;
  if(list)list.innerHTML=regions.map((r,i)=>`<section class="region-properties"><span>${i+1}. ${esc(r.label)}</span><div><button type="button" data-edit-region="${i}">修改</button><button type="button" data-remove-region="${i}">移除</button></div></section>`).join('')||'<p class="muted">尚未确认单题。先在原图上拖框。</p>';
  if(one){one.disabled=!pending||saving;input.disabled=!pending||saving;input.value=pending?.label||'';status.textContent=pending?(editing==null?'选框待确认；可重画调整当前范围。':'正在修改这道题；重画后再确认单题。'):'拖动原图，框选下一道题。';}
  save.textContent=trim?'确认保留范围':`确认全部框选结果（${regions.length}题）`;save.disabled=saving||(!trim&&!!pending)||!regions.length;
  summary.textContent=trim?(regions.length?'当前保留范围已选好。':'请先框选保留范围。'):pending?'当前单题尚未确认，请先确认单题。':`已确认 ${regions.length} 道题，尚未保存。`;
 };
 const point=ev=>{const b=canvas.getBoundingClientRect();return [Math.max(bounds[0],Math.min(bounds[2],(ev.clientX-b.left)/b.width)),Math.max(bounds[1],Math.min(bounds[3],(ev.clientY-b.top)/b.height))];};
 svg.onpointerdown=ev=>{if(saving)return;ev.preventDefault();beforeDrag=state();drag=point(ev);svg.setPointerCapture(ev.pointerId);};
 svg.onpointermove=ev=>{if(!drag)return;const p=point(ev);draft={left:Math.min(drag[0],p[0]),top:Math.min(drag[1],p[1]),right:Math.max(drag[0],p[0]),bottom:Math.max(drag[1],p[1]),label:pending?.label||defaultLabel(editing??regions.length)};paint();};
 svg.onpointerup=()=>{if(draft&&draft.right-draft.left>=.002&&draft.bottom-draft.top>=.002){remember(beforeDrag);if(trim){regions=[{...draft,label:defaultLabel(0)}];pending=null;}else pending={...draft};}drag=null;draft=null;beforeDrag=null;redraw();};
 svg.onpointercancel=()=>{drag=null;draft=null;beforeDrag=null;paint();};
 if(input)input.onchange=()=>{if(!pending||saving)return;remember();pending.label=input.value;redraw();};
 if(one)one.onclick=()=>{if(!pending||saving)return;try{const next=regions.map(r=>({...r}));const box={...pending,label:(pending.label||'').trim()||defaultLabel(editing??regions.length)};if(editing==null)next.push(box);else next[editing]=box;validateRegions(next,bounds);remember();regions=next;pending=null;editing=null;redraw();}catch(error){onError(error);}};
 host.addEventListener('click',ev=>{if(saving)return;const edit=ev.target.dataset.editRegion,remove=ev.target.dataset.removeRegion;if(edit!=null&&regions[Number(edit)]){remember();editing=Number(edit);pending={...regions[editing]};redraw();}if(remove!=null&&regions[Number(remove)]){remember();const i=Number(remove);regions.splice(i,1);if(editing===i){pending=null;editing=null;}else if(editing!=null&&editing>i)editing--;redraw();}});
 undo.onclick=()=>{if(saving||!history.length)return;({regions,pending,editing}=history.pop());draft=null;drag=null;redraw();};
 save.onclick=async()=>{if(saving)return;try{if(!trim&&pending)throw Error('请先点“确认单题”，再确认全部框选结果。');validateRegions(regions,bounds);saving=true;host.inert=true;await onSave({regions:copy(regions)});}catch(error){onError(error);}finally{saving=false;host.inert=false;redraw();}};
 redraw();
}
