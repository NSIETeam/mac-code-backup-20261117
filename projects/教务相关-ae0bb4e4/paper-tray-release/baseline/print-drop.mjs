const MIME='application/x-siyue-print-source';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function printSourceAttrs(source){return source?`draggable="true" data-print-source="${esc(JSON.stringify(source))}"`:'';}
export function printDropMarkup(){return `<button type="button" class="top-print print-drop" data-print-drop aria-label="拖入文件或讲义打印，点击选择文件" title="拖入文件或讲义打印 · 点击选择文件"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9V3h12v6M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2M6 14h12v8H6zM18 12h.01"/></svg><span>拖入打印</span></button>`;}
export function validatePrintFiles(files){if(!files.length)throw Error('请选择文件');if(files.length>20)throw Error('每次最多选择20份文件');for(const f of files){if(!f.size||f.size>52428800)throw Error('请选择非空且不超过50MB的文件');if(!/\.(pdf|docx?|png|jpe?g)$/i.test(f.name))throw Error('支持 PDF、Word、PNG 或 JPG 文件');}return files;}
export function cropWindow(box,rotation,top,bottom){
 const {x,y,width:w,height:h}=box;const r=((rotation%360)+360)%360;
 if(r===0)return {x,y:y+h*(1-bottom),width:w,height:h*(bottom-top)};
 if(r===90)return {x:x+w*top,y,width:w*(bottom-top),height:h};
 if(r===180)return {x,y:y+h*top,width:w,height:h*(bottom-top)};
 if(r===270)return {x:x+w*(1-bottom),y,width:w*(bottom-top),height:h};
 throw Error('原稿页面旋转角度暂不支持，请下载后打印');
}
export async function sliceOriginalPdf(blob,windows){
 const {PDFDocument}=await import('./vendor/pdf-lib/pdf-lib.mjs');
 const original=await PDFDocument.load(await blob.arrayBuffer());const result=await PDFDocument.create();
 if(!Array.isArray(windows)||!windows.length||windows.length>500)throw Error('本讲原稿范围尚未准备好');
 for(const w of windows){if(!Number.isInteger(w.page)||w.page<1||w.page>original.getPageCount()||![w.top,w.bottom].every(Number.isFinite)||w.top<0||w.bottom>1||w.top>=w.bottom)throw Error('本讲原稿页码或范围无效');
  const [page]=await result.copyPages(original,[w.page-1]);const box=cropWindow(page.getCropBox(),page.getRotation().angle,w.top,w.bottom);
  // MediaBox as well as CropBox makes downstream print renderers honour the slice.
  page.setMediaBox(box.x,box.y,box.width,box.height);page.setCropBox(box.x,box.y,box.width,box.height);result.addPage(page);
 }
 return new Blob([await result.save()],{type:'application/pdf'});
}
export async function resolvePrintSource(ref,api){
 if(!ref||typeof ref.id!=='string'||!ref.id||ref.id.length>100)throw Error('讲义信息无效');const id=encodeURIComponent(ref.id);
 if(['book','handout'].includes(ref.kind))return api(`/print-files/source/${ref.kind}/${id}`);
 if(ref.kind==='published-book'){const d=await api(`/published-course-books/${id}`);if(!d.sourceUrl)throw Error('原稿暂不可用');return {url:d.sourceUrl,fileName:d.book.fileName};}
 if(ref.kind==='published-handout'){if(typeof ref.bookId!=='string'||!ref.bookId)throw Error('书籍信息无效');return api(`/published-course-books/${encodeURIComponent(ref.bookId)}/lectures/${id}/print-source`);}
 if(ref.kind==='library-upload'){
  if(!['book','handout'].includes(ref.sourceKind))throw Error('讲义类型无效');
  const d=await api(`/handout-library/${ref.sourceKind}/${id}`);
  if(!d.originalUrl)throw Error('原稿暂不可用');return {url:d.originalUrl,fileName:d.item.fileName};
 }
 if(ref.kind==='material'){
  if(typeof ref.courseId!=='string'||!ref.courseId)throw Error('课程信息无效');
  const base=ref.scope==='course'?`/courses/${encodeURIComponent(ref.courseId)}/materials/${id}`:`/course-material-library/${encodeURIComponent(ref.courseId)}/${id}`;const file=await api(base+'/file');
  if(!ref.lectureId)return file;
  const detail=await api(base+'/sections');const lecture=(detail.lectures||[]).find(l=>l.id===ref.lectureId);
  if(!lecture||!Number.isInteger(lecture.startPage)||!Number.isInteger(lecture.endPage)||lecture.startPage<1||lecture.endPage<lecture.startPage||lecture.endPage-lecture.startPage>=500||!(/\.pdf$/i.test(file.fileName)||String(file.contentType).includes('pdf')))throw Error('该讲次尚无原稿页码范围，请先核对拆讲；不会改为打印整本');
  return {...file,fileName:`第${lecture.sequence}讲 ${lecture.title}.pdf`,windows:Array.from({length:lecture.endPage-lecture.startPage+1},(_,i)=>({page:lecture.startPage+i,top:0,bottom:1}))};
 }
 throw Error('这项内容暂不能拖入打印');
}
export function installPrintDrop(ui){
 const doc=ui.document||document;let active=false,depth=0;
 const zones=()=>doc.querySelectorAll('[data-print-drop]');
 const reset=()=>{depth=0;zones().forEach(z=>z.classList.remove('drag-active'));doc.body.classList.remove('print-dragging');};
 const recognizable=dt=>Array.from(dt?.types||[]).some(t=>t==='Files'||t===MIME);
 async function submit(files,ref){if(active||ui.isBusy()){ui.notify('正在处理，请稍候再拖入');return;}active=true;reset();
  try{await ui.run(async()=>{
   if(ref){const source=await resolvePrintSource(ref,ui.api);ui.notify('正在准备讲义原稿…');let data=await ui.fetchResource(source.url);if(source.windows?.length)data=await sliceOriginalPdf(data,source.windows);files=[new File([data],source.fileName||'讲义.pdf',{type:data.type})];}
   validatePrintFiles(files);const uploaded=[];
   try{for(let i=0;i<files.length;i++){ui.notify(`正在准备打印 ${i+1}/${files.length} · ${files[i].name}`);const fd=new FormData();fd.append('file',files[i]);const r=await ui.api('/print-files','POST',fd);uploaded.push({id:r.id,name:files[i].name});}}
   catch(error){if(uploaded.length){await ui.ready(uploaded);ui.notify('部分文件上传未完成，已成功的文件保留在打印列表中');}throw error;}
   await ui.ready(uploaded);
  });}catch(error){ui.onError(error);}finally{active=false;}
 }
 doc.addEventListener('click',event=>{const zone=event.target.closest?.('[data-print-drop]');if(!zone)return;event.preventDefault();event.stopImmediatePropagation();if(active||ui.isBusy())return;const input=doc.createElement('input');input.type='file';input.accept='.pdf,.doc,.docx,.png,.jpg,.jpeg';input.multiple=true;input.onchange=()=>void submit([...input.files]);input.click();},true);
 doc.addEventListener('dragstart',event=>{const source=event.target.closest?.('[data-print-source]');if(!source||event.target.closest('input,textarea,select'))return;try{const value=JSON.parse(source.dataset.printSource);event.dataTransfer.setData(MIME,JSON.stringify(value));event.dataTransfer.effectAllowed='copy';doc.body.classList.add('print-dragging');}catch{event.preventDefault();}});
 doc.addEventListener('dragenter',event=>{if(!recognizable(event.dataTransfer))return;depth++;doc.body.classList.add('print-dragging');},true);
 doc.addEventListener('dragleave',()=>{if(--depth<=0)reset();},true);
 doc.addEventListener('dragover',event=>{if(!recognizable(event.dataTransfer))return;event.preventDefault();const zone=event.target.closest?.('[data-print-drop]');zones().forEach(z=>z.classList.toggle('drag-active',z===zone));if(zone){event.dataTransfer.dropEffect=active||ui.isBusy()?'none':'copy';event.stopImmediatePropagation();}},true);
 doc.addEventListener('drop',event=>{if(!recognizable(event.dataTransfer))return;event.preventDefault();reset();if(!event.target.closest?.('[data-print-drop]'))return;event.stopImmediatePropagation();const raw=event.dataTransfer.getData(MIME);try{if(raw)void submit([],JSON.parse(raw));else void submit([...event.dataTransfer.files]);}catch(error){ui.onError(error);}},true);
 doc.addEventListener('dragend',reset,true);globalThis.addEventListener?.('blur',reset);
}
