// Read-only, image-first previews of existing split questions. No confirmation or publication.
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function pendingLibraryItem(item){return item.canOpen!==false&&(item.status==='review'||item.state==='draft'||item.state==='review');}
export function draftQuestionMarkup(detail,source){
 const questions=Array.isArray(detail.questions)?detail.questions:[],shown=questions.slice(0,3);
 if(!shown.length)return '<p class="muted">尚未拆出题目，请进入校对查看处理状态。</p>';
 return `<p class="draft-preview-count">共拆出 ${questions.length} 题 · 先展示前 ${shown.length} 题的实际切图</p><div class="draft-preview-questions">${shown.map(q=>{const images=(q.previews||[]).map(source).filter(Boolean);return `<article class="draft-preview-question"><strong>${escape(q.label||'未编号题目')}</strong>${images.length?images.map(url=>`<div class="draft-preview-image"><img src="${escape(url)}" alt="${escape(q.label||'本题')}拆分原图" loading="lazy" decoding="async"><p class="muted" hidden>切题图片暂时无法显示，请进入校对重试。</p></div>`).join(''):'<p class="muted">切题图片尚未生成，请进入校对查看。</p>'}</article>`;}).join('')}</div>`;
}
export function bookSectionMarkup(detail){
 const sections=Array.isArray(detail.sections)?detail.sections:[],pending=sections.filter(s=>!s.handoutId),shown=pending.slice(0,6);
 if(!shown.length)return '<p class="muted">尚无待确认的拆讲范围，请进入完整拆分结果查看。</p>';
 return `<p class="draft-preview-count"><strong>待确认讲次，尚未拆题</strong> · ${pending.length} 个待确认分段${pending.length>6?'，展示前 6 个':''}</p><div class="draft-book-sections">${shown.map(s=>{const valid=Number.isInteger(s.start)&&Number.isInteger(s.end)&&s.start>=0&&s.end>s.start;return `<div><strong>${s.kind==='lesson'||!s.kind?'第 '+escape(s.sequence)+' 讲 · ':''}${escape(s.title||'待确认内容')}</strong><small>${valid?'原稿段落 '+s.start+' 至 '+s.end+'（结束段不计入）':'段落范围尚不完整，请核对原稿'}</small></div>`;}).join('')}</div><p class="muted">以上为已有拆讲范围，段落编号从 0 开始；确认讲次后才会生成切题图片。</p>`;
}
export function createDraftPreviewLoader({document,source,api,button,target}){
 let stopped=false,running=0;const jobs=[],pending=new Map();
 const view=document.defaultView||globalThis,Observer=view.IntersectionObserver;
 const drain=()=>{if(stopped)return;while(running<2&&jobs.length){const {element,load}=jobs.shift();if(!element.isConnected)continue;running++;Promise.resolve().then(load).catch(()=>{}).finally(()=>{running--;drain();});}};
 const observer=Observer?new Observer(entries=>{for(const e of entries)if(e.isIntersecting){const load=pending.get(e.target);if(load){pending.delete(e.target);observer.unobserve(e.target);jobs.push({element:e.target,load});}}drain();},{rootMargin:'120px 0px'}):null;
 const schedule=(element,load)=>{if(stopped)return;if(observer){pending.set(element,load);observer.observe(element);}else{jobs.push({element,load});drain();}};
 const mount=(content,children)=>{for(const child of children){if(child.state!=='draft'||!child.canOpen)continue;const route=target(child.openUrl);if(!route||route.name!=='handout'||route.p.id!==child.id)continue;const element=Array.from(content.querySelectorAll('[data-draft-preview]')).find(el=>el.dataset.draftPreview===child.id);if(!element)continue;let loaded=false,loading=false;
   const load=async()=>{if(stopped||loaded||loading||!element.isConnected)return;loading=true;try{const query=new URLSearchParams();if(route.p.classId)query.set('classId',route.p.classId);const result=await api('/handouts/'+encodeURIComponent(child.id)+(query.size?'?'+query:''));if(stopped||!element.isConnected)return;element.innerHTML=draftQuestionMarkup(result,source);for(const img of element.querySelectorAll('img'))img.addEventListener('error',()=>{img.hidden=true;img.nextElementSibling.hidden=false;},{once:true});loaded=true;}catch(error){if(stopped||!element.isConnected||error.message==='stale-view')return;element.innerHTML=`<p class="muted">${escape(error.message||'暂时无法加载拆题预览')}</p>${button('重试预览',load,'quiet')}`;}finally{loading=false;}};
   schedule(element,load);
 }};
 const mountBook=(content,item)=>{if(item.kind!=='book'||item.state!=='review'||!item.canOpen)return;const route=target(item.openUrl);if(!route||route.name!=='book'||route.p.id!==item.id)return;const element=content.querySelector('[data-book-draft-preview]');if(!element)return;let loading=false,loaded=false;const load=async()=>{if(stopped||loading||loaded||!element.isConnected)return;loading=true;try{const result=await api('/books/'+encodeURIComponent(item.id));if(stopped||!element.isConnected)return;element.innerHTML=bookSectionMarkup(result);loaded=true;}catch(error){if(stopped||!element.isConnected||error.message==='stale-view')return;element.innerHTML=`<p class="muted">${escape(error.message||'暂时无法加载拆讲范围')}</p>${button('重试拆讲预览',load,'quiet')}`;}finally{loading=false;}};schedule(element,load);};
 const stop=()=>{stopped=true;observer?.disconnect();pending.clear();jobs.length=0;view.removeEventListener?.('hashchange',stop);};
 view.addEventListener?.('hashchange',stop,{once:true});return {schedule,mount,mountBook,stop};
}
