// Coordinates always refer to the original image, not a screen-size screenshot.
export function validateRegions(regions,bounds){
 if(!regions.length)throw Error('请至少框选一道题。');
 for(const r of regions){if(!r.label.trim())throw Error('请填写每个选框的题号。');if(![r.left,r.top,r.right,r.bottom].every(Number.isFinite)||r.left<bounds[0]-1e-7||r.top<bounds[1]-1e-7||r.right>bounds[2]+1e-7||r.bottom>bounds[3]+1e-7||r.right-r.left<.002||r.bottom-r.top<.002)throw Error('选框超出当前内容范围或太小。');}
 for(let i=0;i<regions.length;i++)for(let j=0;j<i;j++){const a=regions[i],b=regions[j];if(Math.min(a.right,b.right)-Math.max(a.left,b.left)>1e-7&&Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)>1e-7)throw Error('选框不能重叠。');}
 return (bounds[2]-bounds[0])*(bounds[3]-bounds[1])-regions.reduce((sum,r)=>sum+(r.right-r.left)*(r.bottom-r.top),0)>1e-6;
}
export function mountRegionEditor(host,{url,bounds,label,onSave,onError,mode='split'}){
 const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const trim=mode==='trim';
 let regions=[],draft=null,drag=null,saving=false;
 host.innerHTML=`<p class="muted">${trim?'在原图上拖动框选要保留的完整正文，框外的页眉、页脚和空白会被排除。题号、条件、图片和所有小问必须框入；每次只清理当前内容块，其他块保持不变。':'在原图上拖动框选每道题，右侧可修改题号和四边位置，按选框顺序排列。框选需要拆出的题目并填写题号后即可保存，无需覆盖整页。请把每道题的条件、图片和小问框入。'}</p><div class="region-toolbar"><button type="button" data-region="zoom">放大原图</button><button type="button" data-region="full">${trim?'保留整块':'整块作为一道题'}</button><button type="button" data-region="undo">撤销选框</button><button type="button" data-region="clear">清空选框</button></div><div class="region-canvas"><img src="${esc(url)}" alt="待框选原稿" draggable="false"><svg viewBox="0 0 1000 1000" preserveAspectRatio="none" aria-label="拖动框选题目"></svg></div><div class="region-list"></div>${trim?'<label class="field">未选区域的排除原因<input class="region-reason" maxlength="300" placeholder="例如：未选部分只有页眉、页码和空白"></label><label class="region-confirm"><input type="checkbox">我已对照原稿核对题目数量，所有图片、小问与跨页条件均完整。</label>':''}<button type="button" class="primary region-save">${trim?'保留正文并重新生成预览':'保存并重新生成预览'}</button>`;
 const canvas=host.querySelector('.region-canvas'),svg=host.querySelector('svg'),list=host.querySelector('.region-list');
 const image=canvas.querySelector('img');
 const fit=()=>{if(image.naturalWidth)canvas.style.setProperty('--region-fit-width',`${Math.max(320,window.innerHeight*.55)*image.naturalWidth/image.naturalHeight}px`);};image.onload=fit;if(image.complete)fit();
 host.querySelector('[data-region="zoom"]').onclick=ev=>{const zoom=canvas.classList.toggle('zoomed');ev.target.textContent=zoom?'适应窗口':'放大原图';};
 const paint=()=>{svg.innerHTML=`<rect x="${bounds[0]*1000}" y="${bounds[1]*1000}" width="${(bounds[2]-bounds[0])*1000}" height="${(bounds[3]-bounds[1])*1000}" class="region-limit"/>`+[...regions,...(draft?[draft]:[])].map((r,i)=>`<rect x="${r.left*1000}" y="${r.top*1000}" width="${(r.right-r.left)*1000}" height="${(r.bottom-r.top)*1000}"/><text x="${r.left*1000+4}" y="${r.top*1000+22}">${i+1}</text>`).join('');};
 const redraw=()=>{paint();list.innerHTML=regions.map((r,i)=>`<section class="region-properties"><label class="field">选框 ${i+1} 题号<input data-label="${i}" value="${esc(r.label)}" maxlength="120" ${trim?'readonly':''}></label><div class="region-coordinates">${[['left','左边'],['top','上边'],['right','右边'],['bottom','下边']].map(([key,title])=>`<label>${title}（%）<input type="number" min="0" max="100" step="0.1" data-coordinate="${key}" data-index="${i}" aria-label="选框 ${i+1} ${title}百分比" value="${Number((r[key]*100).toFixed(3))}"></label>`).join('')}</div><button type="button" data-remove-region="${i}">删除此选框</button></section>`).join('')||'<p class="muted">在左侧原图拖动新建选框。选框建立后，可在此修改题号和四边位置。</p>';};
 const point=ev=>{const b=canvas.getBoundingClientRect();return [Math.max(bounds[0],Math.min(bounds[2],(ev.clientX-b.left)/b.width)),Math.max(bounds[1],Math.min(bounds[3],(ev.clientY-b.top)/b.height))];};
 svg.onpointerdown=ev=>{if(saving)return;ev.preventDefault();drag=point(ev);svg.setPointerCapture(ev.pointerId);};
 svg.onpointermove=ev=>{if(!drag)return;const p=point(ev);draft={left:Math.min(drag[0],p[0]),top:Math.min(drag[1],p[1]),right:Math.max(drag[0],p[0]),bottom:Math.max(drag[1],p[1]),label:''};paint();};
 svg.onpointerup=()=>{if(draft&&draft.right-draft.left>=.002&&draft.bottom-draft.top>=.002){draft.label=trim?label:regions.length?'':label;if(trim)regions=[draft];else regions.push(draft);}drag=null;draft=null;redraw();};
 svg.onpointercancel=()=>{drag=null;draft=null;paint();};
 host.addEventListener('input',ev=>{if(ev.target.dataset.label!=null)regions[Number(ev.target.dataset.label)].label=ev.target.value;});
 host.addEventListener('change',ev=>{const key=ev.target.dataset.coordinate;if(!key)return;const index=Number(ev.target.dataset.index);const value=Number(ev.target.value)/100;const next=regions.map((r,i)=>i===index?{...r,[key]:value}:r);try{validateRegions(next.map(r=>({...r,label:r.label||'待填写'})),bounds);regions=next;paint();}catch(error){ev.target.value=Number((regions[index][key]*100).toFixed(3));onError(error);}});
 host.addEventListener('click',ev=>{const index=ev.target.dataset.removeRegion;if(index!=null&&!saving){regions.splice(Number(index),1);redraw();}});
 host.querySelector('[data-region="undo"]').onclick=()=>{regions.pop();redraw();};
 host.querySelector('[data-region="clear"]').onclick=()=>{regions=[];redraw();};
 host.querySelector('[data-region="full"]').onclick=()=>{regions=[{left:bounds[0],top:bounds[1],right:bounds[2],bottom:bounds[3],label}];redraw();};
 host.querySelector('.region-save').onclick=async()=>{if(saving)return;try{const excluded=validateRegions(regions,bounds);const value={regions};if(trim){const reason=host.querySelector('.region-reason').value.trim();if(excluded&&reason.length<4)throw Error('有未选区域，请补全选框或填写排除原因。');if(!host.querySelector('.region-confirm input').checked)throw Error('请核对原稿完整性后勾选确认。');Object.assign(value,{confirmedCoverage:true,unassignedReason:reason});}saving=true;host.inert=true;await onSave(value);}catch(error){onError(error);}finally{saving=false;host.inert=false;}};
 redraw();
}
