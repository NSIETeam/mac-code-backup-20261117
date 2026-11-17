import {printSourceAttrs} from './print-drop.mjs?v=20261105-print-drop';
import {shelfMarkup} from './bookshelf.mjs?v=20261105-print-drop';
import {pendingLibraryItem,createDraftPreviewLoader} from './draft-preview.mjs?v=20261105-split-preview';
let stopPreviewLoading=()=>{};
import {formatQueueEstimate} from './queue-estimate.mjs?v=20261104-queue-review-v4';
const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const text=value=>String(value??'').trim();
const count=value=>Number.isSafeInteger(value)&&value>=0?value:0;
const statusLabels={queued:'待处理',processing:'处理中',review:'待校对',published:'已发布',failed:'处理失败',other:'其他',draft:'待校对',split:'已拆讲',splitting:'拆分中',waiting:'等待处理'};
const filterKeys=['q','staffId','classId','courseId','status'];
const allowedStatuses=new Set(['queued','processing','review','published','failed','other']);
export function libraryFilters(params={}){
 const result={};for(const key of filterKeys){const value=text(params[key]);if(value&&(key!=='status'||allowedStatuses.has(value)))result[key]=value.slice(0,key==='q'?120:100);}
 const page=Number(params.page);result.page=Number.isSafeInteger(page)&&page>0?page:1;result.pageSize=20;return result;
}
export function libraryQuery(params={},exporting=false){const filters=libraryFilters(params);if(exporting){delete filters.page;delete filters.pageSize;}return new URLSearchParams(filters).toString();}
export function libraryState(value){return statusLabels[value]||'状态待核对';}
export function libraryReused(reuse){return reuse?.state==='reused';}
export function sortLibraryChildren(children=[]){return children.slice().sort((a,b)=>((Number(a.sequence)||0)-(Number(b.sequence)||0))||((Number(b.version)||0)-(Number(a.version)||0)));}
export function libraryTarget(raw,apiBase){
 if(!raw)return null;
 try{const base=new URL('../workbench/',apiBase),url=new URL(raw,base);if(url.origin!==base.origin||url.pathname!==base.pathname||!url.hash)return null;const [name,query='']=url.hash.slice(1).split('?');if(!['book','handout','grade','class'].includes(name))return null;const p=Object.fromEntries(new URLSearchParams(query));if(!p.id)return null;return {name,p};}catch{return null;}
}
export function librarySource(raw,apiBase){if(!raw)return null;try{const base=new URL(apiBase),url=new URL(raw,base);return url.origin===base.origin&&['https:','http:'].includes(url.protocol)?url.href:null;}catch{return null;}}
function dateLabel(value){if(!value)return '上传时间未记录';const date=new Date(value);return Number.isNaN(date.getTime())?'上传时间未记录':date.toLocaleDateString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit'});}
function archived(value,flag){return escape(value||'未记录')+(flag?'（已归档）':'');}
function options(items,current,label){return `<option value="">${escape(label)}</option>`+items.map(x=>`<option value="${escape(x.id??x.value)}" ${String(x.id??x.value)===String(current)?'selected':''}>${escape(x.name??x.label)}${x.archived?'（已归档）':''}</option>`).join('');}
function badge(value){return `<span class="badge">${escape(value)}</span>`;}
export function libraryItemMarkup(item){
 const lessons=count(item.lectureCount),questions=count(item.questionCount);
 return `<details class="library-item" ${printSourceAttrs({kind:'library-upload',sourceKind:item.kind,id:item.id})} ${pendingLibraryItem(item)?'open':''} data-library-kind="${escape(item.kind)}" data-library-id="${escape(item.id)}"><summary><svg aria-hidden="true" viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6zM14 3v5h4M9 12h6M9 16h6"/></svg><div class="library-file"><strong>${escape(item.fileName||'未命名讲义')}</strong><div class="library-meta"><span>${archived(item.staffName||'来源未记录',item.staffArchived)}</span><span>${item.shared?'课程共享':archived(item.className,item.classArchived)}</span><span>${archived(item.courseName,item.courseArchived)}</span><span>${dateLabel(item.createdAt)}</span></div><div class="library-meta"><span>${item.kind==='book'?'原始整份上传':'原始单讲上传'}</span><span>${lessons} 讲${questions?' · '+questions+' 题':''}</span></div></div><div class="library-status">${badge(libraryState(item.status||item.state))}${libraryReused(item.reuse)?badge('已复用解析'):''}${item.canOpen===false?badge('仅归档信息'):''}</div><svg aria-hidden="true" class="library-chevron" viewBox="0 0 24 24"><path d="m9 5 7 7-7 7"/></svg></summary><div class="library-details" aria-live="polite"><p class="library-detail-status muted">${pendingLibraryItem(item)?'正在准备拆题预览…':'展开后加载讲次'}</p></div></details>`;
}
export function libraryDetailMarkup(detail,ctx){
 const item=detail.item||{},children=sortLibraryChildren(detail.children||[]),button=ctx.button;
 const open=(row,label)=>{const target=row.canOpen&&libraryTarget(row.openUrl,ctx.apiBase);return target?button(label,()=>ctx.go(target.name,target.p),'quiet'):'';};
 const source=librarySource(detail.originalUrl,ctx.apiBase),pdf=librarySource(detail.sourcePdfUrl,ctx.apiBase);
 const actions=open(item,item.kind==='book'?'查看拆分结果':'打开讲义')+(item.canOpen&&item.classId?button('回班级',()=>ctx.go('class',{id:item.classId}),'quiet'):'')+(source?button('下载原稿',()=>ctx.openResource(source,item.fileName||'原始讲义'),'quiet'):'')+(pdf&&pdf!==source?button('查看原 PDF',()=>ctx.openResource(pdf,item.fileName||'原始讲义'),'quiet'):'');
 return `${actions?`<div class="actions">${actions}</div>`:''}${item.canOpen===false?'<p class="library-detail-status muted">原班级或课程已归档，可查看记录。</p>':''}${item.kind==='book'&&item.state==='review'&&item.canOpen?'<div class="draft-preview" data-book-draft-preview aria-label="待确认拆讲范围"><p class="muted">正在加载已有拆讲范围…</p></div>':''}<div class="library-lessons">${children.map(h=>{const action=open(h,h.state==='published'?'查看已发布':h.state==='draft'?'继续校对':'查看处理'),queue=formatQueueEstimate(h.queue,h.state);return `<div class="library-lesson-group" ${printSourceAttrs({kind:'handout',id:h.id})}><div class="library-lesson"><div><strong>第 ${escape(h.sequence)} 讲 · ${escape(h.title||'未命名讲次')}</strong><small>${escape(libraryState(h.state))} · v${count(h.version)||1} · ${count(h.questionCount)} 题 · 已确认 ${count(h.confirmedCount)} 题</small>${queue?`<small>${escape(queue)}</small>`:''}${h.error?`<small>${escape(h.error)}</small>`:''}</div><div class="actions">${action||badge('仅归档信息')}</div></div>${h.state==='draft'&&h.canOpen?`<div class="draft-preview" data-draft-preview="${escape(h.id)}" aria-label="第${escape(h.sequence)}讲拆题预览"><p class="muted">正在准备拆题原图…</p></div>`:''}</div>`;}).join('')||'<p class="library-detail-status muted">尚未拆出讲次，可在拆分结果中查看处理状态。</p>'}</div>`;
}
export function uploadShelfPartition(items=[]){
 const pending=[],books=[];for(const item of items)(pendingLibraryItem(item)?pending:books).push(item);return {pending,books};
}
export function uploadShelfCard(item){
 const fileName=text(item.fileName)||'未命名讲义',title=fileName.replace(/\.(pdf|docx?|wsd)$/i,'');
 return {printSource:{kind:'library-upload',sourceKind:item.kind,id:item.id},id:item.kind+':'+item.id,title,subtitle:item.courseName||item.className||'教学讲义',meta:[item.staffName||'来源未记录',count(item.lectureCount)+' 讲'].join(' · '),badge:item.canOpen===false?'已归档':libraryState(item.status||item.state),colorKey:item.courseId||item.classId||item.id};
}
export async function renderHandoutLibrary(params,ctx){
 stopPreviewLoading();
 if(ctx.role!=='admin')throw Object.assign(Error('仅管理员可查看全部讲义'),{status:403});
 const filters=libraryFilters(params),data=await ctx.api('/handout-library?'+libraryQuery(filters));
 const items=data.items||[],stats=data.stats||{},pagination=data.pagination||{},available=data.filters||{},active=['staffId','classId','courseId','status'].filter(k=>filters[k]).length;
 const reset=()=>ctx.go('handout-library'),change=next=>ctx.go('handout-library',{...filters,...next});
 const actions=ctx.button('刷新',()=>ctx.go('handout-library',filters),'quiet')+ctx.button('导出清单',()=>ctx.downloadCsv(libraryQuery(filters,true)),'quiet');
 const summary=`<div class="library-summary bookshelf-upload-summary"><span><strong>${count(pagination.total)}</strong> 份讲义</span><span class="muted">${count(stats.teachers)} 位老师 · ${count(stats.classes)} 个班级 · ${count(stats.lectures)} 讲</span></div>`;
 const search=`<form id="library-filter-form" class="bookshelf-upload-tools"><div class="library-search"><input name="q" type="search" value="${escape(filters.q||'')}" placeholder="搜索文件名或讲次题名" aria-label="搜索讲义"><button class="primary" type="submit">查找</button></div><details class="library-filter-panel" ${active?'open':''}><summary class="bookshelf-filter-toggle">筛选${active?' · '+active:''}</summary><div class="library-filters"><label>上传老师<select name="staffId">${options(available.teachers||[],filters.staffId,'全部老师')}</select></label><label>班级<select name="classId">${options(available.classes||[],filters.classId,'全部班级')}</select></label><label>课程<select name="courseId">${options(available.courses||[],filters.courseId,'全部课程')}</select></label><label>状态<select name="status">${options(available.statuses||[],filters.status,'全部状态')}</select></label></div></details>${filterKeys.some(k=>filters[k])?`<div class="actions" style="margin-top:14px">${ctx.button('清空筛选',reset,'quiet')}</div>`:''}</form>`;
 const paginationHtml=count(pagination.totalPages)>1?`<div class="library-page-nav">${ctx.button('上一页',()=>change({page:Math.max(1,count(pagination.page)-1)}),'quiet',count(pagination.page)<=1)}<span class="muted">${count(pagination.page)} / ${count(pagination.totalPages)}</span>${ctx.button('下一页',()=>change({page:count(pagination.page)+1}),'quiet',count(pagination.page)>=count(pagination.totalPages))}</div>`:'';
 const {pending,books}=uploadShelfPartition(items),bookItems=new Map(books.map(item=>[item.kind+':'+item.id,item]));
 const openBook=async card=>{
  const item=bookItems.get(card.id);if(!item||!['book','handout'].includes(item.kind))throw Error('无法读取这本讲义');
  const detail=await ctx.api('/handout-library/'+item.kind+'/'+encodeURIComponent(item.id));
  if(typeof ctx.previewUpload!=='function')throw Error('预览入口未加载，请刷新页面');
  return ctx.previewUpload({item,detail});
 };
 const shelf=books.length?shelfMarkup(books.map(uploadShelfCard),{button:ctx.button,onOpen:openBook,emptyText:'暂无讲义'}):'';
 const review=pending.length?`<section class="bookshelf-review-section" aria-label="待校对讲义"><div class="bookshelf-review-heading"><h2>待校对</h2><span>${pending.length} 份 · 直接查看拆分效果</span></div><div class="library-items">${pending.map(libraryItemMarkup).join('')}</div></section>`:'';
 ctx.paint(ctx.heading('全部讲义',()=>ctx.go('home',{choose:'1'}),actions)+`<section id="handout-library" class="bookshelf-upload-library">${summary}${search}${shelf}${review}${!items.length?'<div class="library-empty">没有找到符合条件的讲义</div>':''}${paginationHtml}</section>`);
 const document=ctx.document||globalThis.document,form=document.querySelector('#library-filter-form');
 const previewLoader=createDraftPreviewLoader({document,api:ctx.api,button:ctx.button,source:raw=>librarySource(raw,ctx.apiBase),target:raw=>libraryTarget(raw,ctx.apiBase)});stopPreviewLoading=previewLoader.stop;
 const apply=event=>{event?.preventDefault();const next={page:1};for(const key of filterKeys){const value=text(form.elements[key]?.value);if(value)next[key]=value;}ctx.go('handout-library',next);};
 form.addEventListener('submit',apply);for(const select of form.querySelectorAll('select'))select.addEventListener('change',apply);
 for(const row of document.querySelectorAll('[data-library-id]')){
  let loaded=false,loading=false;
  const load=async()=>{if(!row.open||loaded||loading)return;loading=true;const content=row.querySelector('.library-details');content.innerHTML='<p class="library-detail-status muted">正在加载讲次…</p>';try{const kind=row.dataset.libraryKind,id=row.dataset.libraryId;if(!['book','handout'].includes(kind))throw Error('未知讲义类型');const detail=await ctx.api('/handout-library/'+kind+'/'+encodeURIComponent(id));if(!row.isConnected)return;content.innerHTML=libraryDetailMarkup(detail,ctx);loaded=true;previewLoader.mount(content,detail.children||[]);previewLoader.mountBook(content,detail.item||{});}catch(error){if(error.message==='stale-view'||!row.isConnected)return;content.innerHTML=`<p class="library-detail-status muted">${escape(error.message||'暂时无法加载')}</p><div class="actions">${ctx.button('重试',load,'quiet')}</div>`;}finally{loading=false;}};
  row.addEventListener('toggle',()=>{if(row.open)previewLoader.schedule(row,load);});if(row.open)previewLoader.schedule(row,load);
 }
 return data;
}
