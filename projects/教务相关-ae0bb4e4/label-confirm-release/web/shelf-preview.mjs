import {openBookReader} from './book-reader.mjs?v=20261105-page-usability';
import {openLecturePreview} from './lecture-preview.mjs?v=20261105-folders';
import {libraryTarget,sortLibraryChildren} from './handout-library.mjs?v=20261105-print-drop';

const enc=encodeURIComponent;
function printableUI(ui,source){return {...ui,modal(...args){ui.modal(...args);const title=ui.dialog.querySelector('.heading h2');if(title){title.draggable=true;title.dataset.printSource=JSON.stringify(source);title.title='拖到打印区打印原稿';}}};}
const isPdf=m=>String(m.format||'').toLowerCase()==='pdf'||/\.pdf$/i.test(m.fileName||'');
const titleOf=m=>m.title||m.fileName||'讲义';
const originalFile=(m,ui)=>ui.api(`/course-material-library/${enc(m.courseId)}/${enc(m.id)}/file`);
const editionFile=(id,format,ui)=>ui.api(`/material-versions/${enc(id)}/file?format=${format}`);
const download=(load,ui)=>async()=>{const file=await load();return ui.openResource(file.url,file.fileName);};
const navigate=(ui,name,p)=>()=>{ui.dialog.close();ui.go(name,p);};

// Resolve every display from the immutable source or an explicit shared edition.
// An unavailable current edition must never silently display an older revision.
export async function materialBookData({material,related=[material],path='',returnTo={}},ui){
 const sources=[...new Map([material,...related].map(m=>[m.id,m])).values()];
 let selected=material;
 let summary=await ui.api(`/material-versions/by-material/${enc(selected.id)}`);
 if(!summary.currentEditionId&&!isPdf(selected)){
  const companion=sources.find(m=>m.id!==selected.id&&isPdf(m)&&m.materialVersion===selected.materialVersion&&m.role===selected.role&&m.audience===selected.audience&&(m.companionOfMaterialId===selected.id||selected.companionOfMaterialId===m.id));
  if(companion){selected=companion;summary=await ui.api(`/material-versions/by-material/${enc(selected.id)}`);}
 }
 const sourceActions=sources.map(m=>({label:`下载原稿 · ${m.fileName||titleOf(m)}`,run:download(()=>originalFile(m,ui),ui)}));
 const history=summary.history||[];
 const common={title:titleOf(material),sources:sourceActions,sections:[],versions:[]};
 const context=[material.courseName,selected.id!==material.id?`预览配套文件：${selected.fileName||titleOf(selected)}`:''].filter(Boolean);
 const loadOriginal=async m=>({...common,file:await originalFile(m,ui),actions:[],note:[...context,`原稿 · ${m.fileName||titleOf(m)}`].join(' · ')});
 const loadEdition=async edition=>{
  const current=edition.id===summary.currentEditionId;
  const actions=[];
  if(edition.pdfReady)actions.push({label:'下载 PDF',run:download(()=>editionFile(edition.id,'pdf',ui),ui)});
  if(edition.docxReady)actions.push({label:'下载 Word',run:download(()=>editionFile(edition.id,'docx',ui),ui)});
  actions.push({label:current&&summary.canEdit?'换题':'查看题目',run:navigate(ui,'material-edition',{id:selected.id,editionId:edition.id,path,q:returnTo.q||'',course:returnTo.course||'',book:returnTo.book||''})});
  return {...common,file:edition.pdfReady?await editionFile(edition.id,'pdf',ui):null,actions,
   note:[...context,`${current?'当前共享版':'历史版本'} v${edition.number}`,edition.pdfReady?'':'此版本的 PDF 尚未生成，可在版本中查看原稿'].filter(Boolean).join(' · ')};
 };
 common.versions=[...history.map(v=>({label:`${v.id===summary.currentEditionId?'当前共享版':'历史版本'} v${v.number}`,load:()=>loadEdition(v)})),
  ...sources.filter(isPdf).map(m=>({label:`原稿 · ${m.fileName||titleOf(m)}`,load:()=>loadOriginal(m)})),
  ...sources.filter(m=>m.id!==selected.id).map(m=>({label:`配套 / 版本 · ${m.fileName||titleOf(m)}`,load:()=>materialBookData({material:m,related:sources,path,returnTo},ui)}))];
 if(summary.currentEditionId){
  const current=history.find(v=>v.id===summary.currentEditionId);
  if(!current)throw Error('当前版本信息不完整，请刷新重试');
  return loadEdition(current);
 }
 if(isPdf(selected))return loadOriginal(selected);
 return {...common,file:null,actions:[],note:[...context,'此文件尚无可预览的 PDF，可下载原稿查看'].filter(Boolean).join(' · ')};
}

export function openMaterialBook(payload,ui){
 return openBookReader({title:titleOf(payload.material),returnFocus:document.activeElement,load:()=>materialBookData(payload,ui)},printableUI(ui,{kind:'material',id:payload.material.id,courseId:payload.material.courseId}));
}

export function uploadBookData({item,detail},ui){
 const current=detail.item||item,title=current.fileName||'讲义';
 const pdf=detail.sourcePdfUrl||(/\.pdf$/i.test(title)?detail.originalUrl:null);
 const sources=detail.originalUrl?[{label:'下载原稿',run:()=>ui.openResource(detail.originalUrl,title)}]:[];
 const target=current.canOpen&&libraryTarget(current.openUrl,ui.apiBase);
 const actions=target?[{label:current.kind==='book'?'查看拆分结果':'查看题目',run:navigate(ui,target.name,target.p)}]:[];
 if(pdf)actions.unshift({label:'下载 PDF',run:()=>ui.openResource(pdf,title.replace(/\.[^.]+$/,'')+'.pdf')});
 const sections=sortLibraryChildren(detail.children||[]).flatMap(child=>{
  const route=current.canOpen&&child.canOpen&&libraryTarget(child.openUrl,ui.apiBase);
  return route?[{label:`第 ${child.sequence} 讲 · ${child.title||'未命名讲次'}`,run:navigate(ui,route.name,route.p)}]:[];
 });
 return {title,file:pdf?{url:pdf,fileName:title.replace(/\.[^.]+$/,'')+'.pdf'}:null,actions,sections,sources,versions:[],
  note:[current.staffName,current.courseName||current.className,current.canOpen===false?'已归档 · 原稿预览':'原稿预览',pdf?'':'此文件尚无可预览的 PDF'].filter(Boolean).join(' · ')};
}

export function openUploadBook(payload,ui){
 return openBookReader({title:payload.item.fileName||'讲义',returnFocus:document.activeElement,load:()=>uploadBookData(payload,ui)},printableUI(ui,{kind:'library-upload',sourceKind:payload.item.kind,id:payload.item.id}));
}

export async function lectureQuestionData({material,related,path,lecture,returnTo},ui){
 const summary=await ui.api(`/material-versions/by-material/${enc(material.id)}`);
 const edition=summary.currentEditionId?await ui.api(`/material-versions/${enc(summary.currentEditionId)}`):null;
 const wanted=new Set(lecture.questionIds||[]);
 const questions=(edition?.questions||[]).filter(q=>wanted.has(q.id)).sort((a,b)=>a.order-b.order).map(q=>({
  id:q.id,label:q.label,text:q.text,
  loadImages:async()=>{const result=await ui.api(`/material-versions/${enc(edition.id)}/questions/${enc(q.id)}/images`);return result.images||[];}
 }));
 return {questions,note:`${titleOf(material)} · 第 ${lecture.sequence} 讲${edition?' · 当前共享版 v'+edition.number:''} · 拆分内容`,
  actions:[{label:'整本预览',run:()=>openMaterialBook({material,related,path,returnTo},ui)}]};
}

export async function openMaterialLecture(payload,ui){
 const {material,lecture,sourceEditionId}=payload;ui=printableUI(ui,{kind:'material',id:material.id,courseId:material.courseId,lectureId:lecture.id});
 const title=`第${lecture.sequence}讲 · ${lecture.title}`,returnFocus=document.activeElement;
 const questions=()=>openLecturePreview({title,returnFocus,load:()=>lectureQuestionData(payload,ui)},ui);
 const hasRange=isPdf(material)&&Number.isInteger(lecture.startPage)&&Number.isInteger(lecture.endPage);
 if(!hasRange)return questions();
 const summary=await ui.api(`/material-versions/by-material/${enc(material.id)}`);
 // Page boundaries belong to the immutable original; a revised shared edition
 // is shown using its stable occurrence IDs instead of stale original pages.
 if(summary.currentEditionId&&summary.currentEditionId!==sourceEditionId)return questions();
 return openBookReader({title,returnFocus,load:async()=>({title,
  file:{...await originalFile(material,ui),pageRange:{start:lecture.startPage,end:lecture.endPage}},
  actions:[{label:'查看拆题',run:questions},{label:'整本预览',run:()=>openMaterialBook(payload,ui)},
   {label:'下载整本原稿',run:download(()=>originalFile(material,ui),ui)}],
  note:`${titleOf(material)} · 原稿第 ${lecture.startPage}—${lecture.endPage} 页`,sections:[],sources:[],versions:[]
 })},ui);
}

const publicationPath=material=>`/published-course-books/${enc(material.id)}`;
const publicationReturn=(material,returnTo={})=>({path:'',q:'',course:material.courseId||'',book:material.id,...returnTo,bookSource:'published-book'});
const pdfName=name=>String(name||'讲义').replace(/\.[^.]+$/,'')+'.pdf';
const publicationPdf=detail=>detail.sourcePdfUrl||(/\.pdf$/i.test(detail.book?.fileName||'')?detail.sourceUrl:null);
const publicationNote=(book,extra='')=>[book.courseName,book.publisherName?'发布：'+book.publisherName:'',extra].filter(Boolean).join(' · ');

async function publishedFile(material,kind,ui){
 const fresh=await ui.api(publicationPath(material));
 const url=kind==='pdf'?publicationPdf(fresh):fresh.sourceUrl;
 if(!url)throw Error(kind==='pdf'?'此书暂时没有可预览的 PDF':'此书暂时没有可下载的原稿');
 return {url,fileName:kind==='pdf'?pdfName(fresh.book?.fileName):fresh.book?.fileName||titleOf(material)};
}

// Never reuse folder detail or a previous signed URL when reopening a publication.
export async function publishedBookData({material,returnTo},ui){
 const fresh=await ui.api(publicationPath(material)),book=fresh.book;
 if(!book||book.id!==material.id)throw Error('书籍信息已更新，请返回书架重新打开');
 const title=book.title||book.fileName||titleOf(material),pdf=publicationPdf(fresh);
 const actions=[{label:'返回书架',run:navigate(ui,'material-library',publicationReturn(material,returnTo))}];
 if(pdf)actions.unshift({label:'下载 PDF',run:download(()=>publishedFile(material,'pdf',ui),ui)});
 return {title,file:pdf?{url:pdf,fileName:pdfName(book.fileName||title)}:null,actions,
  sections:[...(fresh.lectures||[])].sort((a,b)=>a.sequence-b.sequence).map(lecture=>({label:`第 ${lecture.sequence} 讲 · ${lecture.title||'未命名讲次'}`,run:()=>openPublishedLecture({material,lecture,returnTo},ui)})),
  sources:fresh.sourceUrl?[{label:'下载原稿',run:download(()=>publishedFile(material,'source',ui),ui)}]:[],versions:[],
  note:publicationNote(book,pdf?'整本原稿':'此文件尚无可预览的 PDF，可下载原稿查看')};
}

export function openPublishedBook(payload,ui){
 return openBookReader({title:titleOf(payload.material),returnFocus:document.activeElement,load:()=>publishedBookData(payload,ui)},printableUI(ui,{kind:'published-book',id:payload.material.id}));
}

function handoutQuestions(detail){
 return [...(detail.questions||[])].map((question,index)=>({question,index})).sort((a,b)=>(a.question.order??a.index)-(b.question.order??b.index)).map(({question,index})=>({
  id:question.id,label:question.label||`第 ${index+1} 题`,text:question.text||(question.blocks||[]).map(block=>block.text||'').filter(Boolean).join('\n'),
  loadImages:async()=>[...(question.previews||[])].map(url=>({url}))
 }));
}

export async function publishedLectureData({material,lecture,returnTo},ui){
 if(!lecture?.handoutId)throw Error('本讲信息不完整，请返回书架重新打开');
 const fresh=await ui.api(`${publicationPath(material)}/lectures/${enc(lecture.handoutId)}`);
 return {questions:handoutQuestions(fresh),note:`${titleOf(material)} · 第 ${fresh.sequence??lecture.sequence} 讲 · ${fresh.title||lecture.title||'分讲内容'}`,
  actions:[{label:'整本预览',run:()=>openPublishedBook({material,returnTo},ui)},
   {label:'返回书架',run:navigate(ui,'material-library',publicationReturn(material,returnTo))}]};
}

export function openPublishedLecture(payload,ui){
 const lecture=payload.lecture||{};
 return openLecturePreview({title:`第${lecture.sequence}讲 · ${lecture.title||'分讲预览'}`,returnFocus:document.activeElement,load:()=>publishedLectureData(payload,ui)},printableUI(ui,{kind:'published-handout',id:lecture.handoutId,bookId:payload.material.id}));
}

export async function courseLectureData({handoutId,title},ui){
 if(!handoutId)throw Error('本讲信息不完整，请返回课程重新打开');
 const fresh=await ui.api(`/handouts/${enc(handoutId)}`);
 return {questions:handoutQuestions(fresh),actions:[],note:[title||fresh.lecture?.title||'课程讲义',fresh.lecture?.sequence?`第 ${fresh.lecture.sequence} 讲`:'',fresh.handout?.version?`v${fresh.handout.version}`:''].filter(Boolean).join(' · ')};
}

export function openCourseLecturePreview(payload,ui){
 return openLecturePreview({title:payload.title||'分讲预览',returnFocus:document.activeElement,load:()=>courseLectureData(payload,ui)},printableUI(ui,{kind:'handout',id:payload.handoutId}));
}
