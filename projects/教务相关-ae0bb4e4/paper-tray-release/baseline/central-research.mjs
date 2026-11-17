import {escapeHtml as e} from './core.mjs';
import {openBookReader,bookResourceUrl} from './book-reader.mjs?v=20261105-central-workbench';

const enc=encodeURIComponent,pageSize=30;
const categories=[['all','全部问题','problemTotal'],['failed','处理失败','failed'],['split-review','拆讲待确认','splitReview'],['question-issues','题目疑点','questionIssues'],['review','待校对','review'],['incomplete-material','共享资料不完整','incompleteMaterial']];
const tabs=[['pending','待审核','pending'],['problems','问题讲义 / 教材','problemTotal'],['courses','课程制作','courses']];
const submissionPath=item=>`/central-research/submissions/${enc(item.id)}`;
const fileName=item=>item.fileName||item.title||'未命名讲义';
const isPdf=item=>/\.pdf$/i.test(item.fileName||'');
const validPage=value=>Number.isSafeInteger(Number(value))&&Number(value)>=0?Number(value):0;
const knownCount=value=>Number.isSafeInteger(value)&&value>=0;
const count=(value,fallback=0)=>knownCount(value)?value:fallback;
function submittedTime(value){
 if(!value)return '';
 const date=new Date(value);
 return Number.isNaN(date.getTime())?'':date.toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
}
function itemContext(item){return [item.courseName,item.className,item.uploaderName?`上传：${item.uploaderName}`:'',submittedTime(item.createdAt)].filter(Boolean).join(' · ');}
function scopedParams(params,tab,category){return {...params,central:'1',centralTab:tab,...(tab==='problems'?{centralCategory:category}:{})};}

async function submissionSource(item,ui){
 const detail=await ui.api(submissionPath(item));
 if(!detail?.item||detail.item.id!==item.id)throw Error('讲义信息已更新，请刷新列表后重试');
 if(!detail.sourceUrl)throw Error('原稿暂时无法打开，请稍后重试');
 return {item:detail.item,url:bookResourceUrl(detail.sourceUrl,ui.apiBase,ui.safeResource)};
}
async function problemSource(item,ui){
 if(!['book','handout','material'].includes(item.kind)||!item.id||(item.kind==='material'&&!item.courseId))throw Error('原稿信息不完整，请刷新列表后重试');
 const id=item.kind==='material'?(item.materialId||item.id):item.id;
 const detail=await ui.api(`/central-research/sources/${enc(item.kind)}/${enc(id)}${item.kind==='material'?'?courseId='+enc(item.courseId):''}`);
 if(!detail?.url)throw Error('原稿暂时无法打开，请查看问题说明');
 return {item:{...item,fileName:detail.fileName||fileName(item)},url:bookResourceUrl(detail.url,ui.apiBase,ui.safeResource)};
}
async function sourceData(item,ui,loader,note){
 const fresh=await loader(item,ui);
 const download=async()=>{const latest=await loader(item,ui);return ui.openResource(latest.url,fileName(latest.item));};
 return {title:fileName(fresh.item),file:isPdf(fresh.item)?{url:fresh.url,fileName:fileName(fresh.item)}:null,
  actions:[{label:'下载原稿',run:download}],sources:[],sections:[],versions:[],
  note:[itemContext(fresh.item),isPdf(fresh.item)?note:'此原稿可下载后查看'].filter(Boolean).join(' · ')};
}
// Reading an uploaded original does not start parsing or change approval state.
export const centralResearchSourceData=(item,ui)=>sourceData(item,ui,submissionSource,'审核原稿');
export const centralResearchProblemSourceData=(item,ui)=>sourceData(item,ui,problemSource,'问题讲义原稿');
function openSource(item,ui,problem=false){
 const loader=problem?problemSource:submissionSource;
 if(isPdf(item))return openBookReader({title:fileName(item),returnFocus:globalThis.document?.activeElement,load:()=>problem?centralResearchProblemSourceData(item,ui):centralResearchSourceData(item,ui)},ui);
 return loader(item,ui).then(fresh=>ui.openResource(fresh.url,fileName(fresh.item)));
}

// The server chooses the permitted maintenance target; only existing workbench routes are accepted.
export function centralResearchProblemTarget(item,category='all'){
 if(!['book','handout','course-materials','material-edition','material-library'].includes(item.route))return null;
 const p=item.routeParameters||{},parameters={};
 for(const key of ['id','classId','courseId','bookId','lectureId','editionId','course','book'])if(typeof p[key]==='string'&&p[key])parameters[key]=p[key];
 if(item.route==='material-library'?!parameters.course||!parameters.book:!parameters.id)return null;
 return {name:item.route,p:scopedParams(parameters,'problems',category)};
}

export async function renderCentralResearch(params={},ui){
 const {api,paint,heading,button,go}=ui,me=await api('/central-research/me');
 const tab=tabs.some(([key])=>key===params.tab)?params.tab:'problems';
 const category=categories.some(([key])=>key===params.category)?params.category:'all';
 let page=validPage(params.page);
 const route=next=>{const target={tab,category,page,...next};if(target.tab!=='problems')delete target.category;if(!target.page)delete target.page;go('central-research',target);};
 const refresh=()=>renderCentralResearch({...params,tab,category,page},ui);
 if(!me.canReview&&!me.canManage){
  paint(heading('中央教研室',()=>go('home',{choose:'1'}))+`<section id="central-research" class="central-workbench"><p class="central-empty">此工作台由中央教研室主任和管理员使用。</p></section>`);
  return {me,items:[],total:0,page,tab,category};
 }
 const loadList=index=>api(tab==='pending'?`/central-research/submissions?state=pending&page=${index}&pageSize=${pageSize}`:tab==='courses'?'/central-research/courses':`/central-research/problems?category=${category}&page=${index}&pageSize=${pageSize}`);
 const [dashboard,first]=await Promise.all([api('/central-research/dashboard'),loadList(page)]);
 let data=first,total=count(data.total,(data.items||data.courses||[]).length);
 if(tab!=='courses'&&page>Math.max(0,Math.ceil(total/pageSize)-1)){
  page=Math.max(0,Math.ceil(total/pageSize)-1);data=await loadList(page);total=count(data.total,(data.items||[]).length);
 }
 const items=tab==='courses'?(data.courses||[]):tab==='pending'?(data.items||[]).filter(item=>item.state==='pending'):(data.items||[]);
 const tools=(typeof ui.createCourse==='function'?button('创建课程',()=>ui.createCourse(),'primary central-toolbar-action'):'')+button('讲义库',()=>go('material-library',scopedParams({},tab,category)),'quiet central-toolbar-action');
 const tabMarkup=`<nav class="central-tabs" role="tablist" aria-label="教研工作">${tabs.map(([key,label,countKey])=>button(`<span>${e(label)}</span>${knownCount(dashboard[countKey])?`<b>${dashboard[countKey]}</b>`:''}`,()=>route({tab:key,page:0}),'central-tab'+(tab===key?' is-active':'')).replace('<button ',`<button role="tab" aria-selected="${tab===key}" `)).join('')}</nav>`;
 const busy=new Set(),completed=new Set();
 const submit=async(item,decision,extra={},control)=>{
  if(!me.canReview||busy.has(item.id)||completed.has(item.id))return;
  busy.add(item.id);if(control)control.disabled=true;
  try{
   const result=await api(`${submissionPath(item)}/${decision}`,'POST',{revision:item.revision,...extra});
   completed.add(item.id);if(decision==='reject')ui.dialog.close();
   if(items.length===1&&page>0)page--;
   await refresh();
   ui.notice(decision==='approve'?(result?.item?.state==='reused'?'讲义库已有拆分结果，已返回给上传老师':'已通过审核，开始解析'):'已退回讲义。');
  }finally{busy.delete(item.id);if(control&&!completed.has(item.id))control.disabled=false;}
 };
 const reject=item=>{
  if(!me.canReview||busy.has(item.id)||completed.has(item.id))return;
  ui.modal('退回讲义',`<form id="central-research-reject"><p>${e(fileName(item))}</p><label class="field">退回说明（可选）<textarea name="note" rows="3" maxlength="500" placeholder="填写需要修改的内容，也可直接退回"></textarea></label><div class="actions"><button type="submit" class="primary">退回</button>${button('取消',()=>ui.dialog.close(),'quiet')}</div></form>`);
  ui.bindForm('central-research-reject',async(values,form)=>{const note=String(values.note||'').trim();return submit(item,'reject',note?{note}:{},form?.querySelector('[type="submit"]'));});
 };
 let body='';
 if(tab==='pending'){
  body=`<div class="central-section-line"><span>${total} 份待审核</span>${!me.canReview?'<span>由超级管理员或中央教研室主任审核</span>':''}</div><div class="central-list">${items.map(item=>`<article class="central-item" data-central-submission="${e(item.id)}"><div class="central-item-main"><h3>${e(fileName(item))}</h3><p class="central-item-meta">${e(itemContext(item))}</p></div><div class="central-item-actions">${button('原稿',()=>openSource(item,ui),'quiet')}${me.canReview?button('通过并开始解析',control=>submit(item,'approve',{},control),'primary')+button('退回',()=>reject(item),'quiet'):''}</div></article>`).join('')||'<p class="central-empty">暂无待审核讲义</p>'}</div>`;
 }else if(tab==='problems'){
  const filters=`<nav class="central-filters" aria-label="问题类型">${categories.map(([key,label,countKey])=>button(`${e(label)}${knownCount(dashboard[countKey])?` <span>${dashboard[countKey]}</span>`:''}`,()=>route({category:key,page:0}),'central-filter'+(category===key?' is-active':'')).replace('<button ',`<button aria-pressed="${category===key}" `)).join('')}</nav>`;
  body=filters+`<div class="central-section-line"><span>${total} 份${category==='all'?'问题讲义 / 教材':e(categories.find(([key])=>key===category)[1])}</span></div><div class="central-list">${items.map(item=>{
   const target=centralResearchProblemTarget(item,category),label=categories.find(([key])=>key===item.category)?.[1]||'待处理';
   const metrics=[knownCount(item.problemCount)&&item.problemCount>0?`${item.problemCount} 项问题`:'',knownCount(item.questionCount)&&item.questionCount>0?`${item.questionCount} 题`:'',knownCount(item.blockingQuestionCount)&&item.blockingQuestionCount>0?`${item.blockingQuestionCount} 题存在阻塞问题`:'',knownCount(item.unconfirmedQuestionCount)&&item.unconfirmedQuestionCount>0?`${item.unconfirmedQuestionCount} 题待确认`:''].filter(Boolean);
   const reasons=Array.isArray(item.reasons)?[...new Set(item.reasons.filter(reason=>typeof reason==='string'&&reason.trim()))]:[];
   return `<article class="central-item" data-central-problem="${e(item.id)}"><div class="central-item-main"><div class="central-item-title"><h3>${e(item.title||fileName(item))}</h3><span class="central-status">${e(label)}</span></div><p class="central-item-meta">${e([({book:'整本讲义',handout:'单讲讲义',material:'共享教材'})[item.kind],itemContext(item)].filter(Boolean).join(' · '))}</p>${reasons.length?`<p class="central-reasons">${reasons.map(e).join('；')}</p>`:''}${metrics.length?`<p class="central-counts">${metrics.map(e).join(' · ')}</p>`:''}</div><div class="central-item-actions">${button('原稿',()=>openSource(item,ui,true),'quiet')}${target?button(item.kind==='material'?(target.name==='material-edition'?'校对题目':'查看原稿资料'):'处理问题',()=>go(target.name,target.p),'primary'):''}</div></article>`;
  }).join('')||'<p class="central-empty">当前没有这类问题讲义或教材</p>'}</div>`;
 }else{
  body=`<div class="central-section-line"><span>${items.length} 门可制作课程</span></div><div class="central-list">${items.map(course=>{
   const metrics=[['lectureCount','讲次'],['bookCount','本讲义'],['handoutCount','份分讲'],['pendingCount','份待审核'],['problemCount','份问题资料'],['draftCount','份待校对'],['publishedCount','份已发布']].filter(([key])=>knownCount(course[key])).map(([key,label])=>`${course[key]} ${label}`);
   return `<article class="central-item" data-central-course="${e(course.id)}"><div class="central-item-main"><h3>${e(course.name)}</h3>${metrics.length?`<p class="central-item-meta">${metrics.map(e).join(' · ')}</p>`:''}</div><div class="central-item-actions">${course.canManage===true?button('制作课程',()=>go('course',scopedParams({id:course.id},'courses',category)),'primary'):''}</div></article>`;
  }).join('')||`<div class="central-empty"><p>暂无可制作课程</p></div>`}</div>`;
 }
 const pages=Math.max(1,Math.ceil(total/pageSize));
 const pagination=tab!=='courses'&&pages>1?`<nav class="central-pagination" aria-label="教研列表分页">${button('上一页',()=>route({page:page-1}),'quiet',page===0)}<span>第 ${page+1} / ${pages} 页</span>${button('下一页',()=>route({page:page+1}),'quiet',page+1>=pages)}</nav>`:'';
 paint(heading('中央教研室',()=>go('home',{choose:'1'}),tools,ui.iconButton?.('refresh','刷新教研列表',refresh)||button('刷新',refresh,'quiet')).replace('page-heading','page-heading central-page-heading')+`<section id="central-research" class="central-workbench"><p class="central-director">主任 · ${e(me.directorName||'尚未指定')}</p>${tabMarkup}<section class="central-panel" role="tabpanel">${body}${pagination}</section></section>`);
 return {me,dashboard,items,total,page,tab,category};
}
