const MIME='application/x-siyue-print-source';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function printSourceAttrs(source){return source?`draggable="true" data-print-source="${esc(JSON.stringify(source))}"`:'';}
export function printDropMarkup(){
 return '<button type="button" class="top-print print-drop" data-print-drop aria-label="进纸托盘，点击选择文件或拖入讲义" title="进纸托盘"><i class="tray-device" aria-hidden="true"><b></b><i></i></i><span>进纸托盘</span></button>';
}
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
// The current selection is local to the mounted bank. Only its own button can
// request generation. A drag payload from another window cannot invoke it.
const documentButtons = new WeakMap();
export function bindTrayDocument(button, prepare) {
  button.type = 'button';
  button.draggable = true;
  button.dataset.trayDocument = '';
  documentButtons.set(button, prepare);
}

export function installPrintDrop(ui) {
  const doc = ui.document || document;
  const items = [];
  let owner = '', busy = false, depth = 0, dragged = null, group = '';
  const zones = () => doc.querySelectorAll('[data-print-drop]');
  const occupied = () => busy || ui.isBusy();
  function resetDrag() {
    depth = 0;
    doc.body.classList.remove('print-dragging');
    doc.querySelectorAll('.drag-active').forEach(zone=>zone.classList.remove('drag-active'));
  }
  function syncOwner() {
    const current = ui.owner();
    if (owner !== current) {
      owner = current;
      items.length = 0;
      group = '';
    }
    return current;
  }
  function updateCount() {
    zones().forEach(zone => {
      zone.dataset.count = String(items.length);
      zone.setAttribute('aria-label', '进纸托盘，' + (items.length ? '已放入 ' + items.length + ' 份' : '选择文件或拖入讲义'));
    });
  }
  function add(item) {
    if (items.some(row => row.key === item.key)) return;
    if (items.length >= 20) throw Error('进纸托盘最多放入 20 份文件');
    items.push(item);
    group = crypto.randomUUID();
  }
  function show() {
    if (!syncOwner()) return;
    ui.modal('进纸托盘', '<section class="tray-panel"><div class="tray-well" data-print-drop-zone><div class="tray-list"></div><button type="button" data-action="choose" class="quiet">选择文件</button></div><div class="tray-footer"><span role="status"></span><button type="button" data-action="settings" class="primary">打印设置</button></div></section>');
    const panel = doc.querySelector('.tray-panel');
    panel.closest('dialog')?.querySelector('.dialog-actions [data-print-drop]')?.remove();
    const list = panel.querySelector('.tray-list');
    if (!items.length) list.textContent = '将文件或讲义拖到这里';
    for (const item of items) {
      const row = doc.createElement('div');
      row.className = 'tray-row';
      const name = doc.createElement('span');
      name.textContent = item.name;
      const remove = doc.createElement('button');
      remove.type = 'button';
      remove.className = 'quiet';
      remove.textContent = '取出';
      remove.setAttribute('aria-label', '取出 ' + item.name);
      remove.onclick = () => {items.splice(items.indexOf(item), 1); group = crypto.randomUUID(); show();};
      row.append(name, remove);
      list.append(row);
    }
    panel.querySelector('[role=status]').textContent = '已放入 ' + items.length + ' 份';
    panel.querySelector('[data-action=settings]').disabled = !items.length;
    panel.querySelector('[data-action=settings]').onclick = settings;
    panel.querySelector('[data-action=choose]').onclick = () => {
      const input = doc.createElement('input');
      input.type = 'file';
      input.accept = '.pdf,.doc,.docx,.png,.jpg,.jpeg';
      input.multiple = true;
      input.onchange = () => receiveFiles([...input.files]);
      input.click();
    };
    updateCount();
  }
  function receiveFiles(files) {
    if (!syncOwner() || occupied()) return;
    try {
      validatePrintFiles(files);
      const entries = files.map(file => ({key:'file:'+JSON.stringify([file.name,file.size,file.lastModified]), name:file.name, file}));
      if (new Set([...items,...entries].map(item=>item.key)).size > 20) throw Error('进纸托盘最多放入 20 份文件');
      entries.forEach(add);
      show();
    } catch (error) {ui.onError(error);}
  }
  async function receiveDocument(node) {
    const prepare = documentButtons.get(node);
    const current = syncOwner();
    if (!current || !prepare || occupied() || !node.isConnected || node.disabled) return;
    if (items.length >= 20) {ui.notify('进纸托盘已满，请先取出文件');return;}
    busy = true;
    try {
      const paper = await prepare();
      if (paper && syncOwner() === current) {
        add({key:'document:'+paper.id, id:paper.id, name:paper.name});
        show();
      }
    } catch (error) {ui.onError(error);} finally {busy = false;}
  }
  async function settings() {
    if (!syncOwner() || occupied() || !items.length) return;
    busy = true;
    const batch = [...items], current = owner, batchGroup = group;
    try {
      await ui.run(async () => {
        for (const [index,item] of batch.entries()) {
          if (item.verifyPaper) {
            const paper = await ui.api('/material-question-bank/papers/'+encodeURIComponent(item.id));
            if(paper.state==='failed')throw Error('讲义生成失败，请先重试。');
            item.name=paper.title;item.verifyPaper=false;
          }
          if (item.id) continue;
          ui.notify('正在准备打印 '+(index+1)+'/'+batch.length+' · '+item.name);
          let file = item.file;
          if (!file) {
            const source = await resolvePrintSource(item.source,ui.api);
            let blob = await ui.fetchResource(source.url);
            if (source.windows?.length) blob = await sliceOriginalPdf(blob,source.windows);
            file = new File([blob],source.fileName||'讲义.pdf',{type:blob.type});
          }
          validatePrintFiles([file]);
          const form = new FormData();
          form.append('file',file);
          const result = await ui.api('/print-files','POST',form);
          item.id = result.id;
          item.name = file.name;
        }
        if (syncOwner() !== current) return;
        await ui.ready(batch.map(({id,name})=>({id,name})),batchGroup,() => {
          for (const item of batch) {
            const i = items.indexOf(item);
            if (i >= 0) items.splice(i,1);
          }
          group = crypto.randomUUID();
          updateCount();
        });
      });
    } finally {busy=false;}
  }
  const getZone = event => event.target.closest?.('[data-print-drop],[data-print-drop-zone]');
  const recognized = dt => Array.from(dt?.types||[]).some(type=>type==='Files'||type===MIME);
  doc.addEventListener('click',event=>{
    const node=event.target.closest?.('[data-tray-document],[data-print-drop]');
    if (!node) return;
    event.preventDefault();event.stopImmediatePropagation();
    if (occupied()) return;
    if (node.hasAttribute('data-tray-document')) void receiveDocument(node);
    else show();
  },true);
  doc.addEventListener('dragstart',event=>{
    const node=event.target.closest?.('[data-tray-document],[data-print-source]');
    if (!node || event.target.closest('input,select,textarea')) return;
    dragged=node;
    const data=node.hasAttribute('data-tray-document')?'local-selection':node.dataset.printSource;
    event.dataTransfer.setData(MIME,data);
    event.dataTransfer.effectAllowed='copy';
    doc.body.classList.add('print-dragging');
  });
  doc.addEventListener('dragenter',event=>{if(recognized(event.dataTransfer)){depth++;doc.body.classList.add('print-dragging');}},true);
  doc.addEventListener('dragleave',()=>{if(--depth<=0)resetDrag();},true);
  doc.addEventListener('dragover',event=>{
    if(!recognized(event.dataTransfer))return;
    event.preventDefault();
    const zone=getZone(event);
    doc.querySelectorAll('[data-print-drop],[data-print-drop-zone]').forEach(z=>z.classList.toggle('drag-active',z===zone));
    if(zone){event.dataTransfer.dropEffect=occupied()?'none':'copy';event.stopImmediatePropagation();}
  },true);
  doc.addEventListener('drop',event=>{
    if(!recognized(event.dataTransfer))return;
    event.preventDefault();resetDrag();
    if(!getZone(event))return;
    event.stopImmediatePropagation();
    if(!syncOwner()||occupied())return;
    const raw=event.dataTransfer.getData(MIME);
    try {
      if(raw==='local-selection'){if(dragged)void receiveDocument(dragged);}
      else if(raw){
        const source=JSON.parse(raw);
        if(!source || typeof source.id!=='string')throw Error('讲义信息无效');
        if (source.kind==='question-paper') {
          add({key:'document:'+source.id,id:source.id,name:String(source.name||'选题讲义'),verifyPaper:true});
          show();dragged=null;return;
        }
        const name=dragged?.getAttribute('aria-label')||dragged?.querySelector('h2,strong,.book-shelf-caption')?.textContent||dragged?.textContent||'来源讲义';
        add({key:'source:'+JSON.stringify(source),name:name.trim().slice(0,160),source});show();
      }else receiveFiles([...event.dataTransfer.files]);
    }catch(error){ui.onError(error);}
    dragged=null;
  },true);
  doc.addEventListener('dragend',()=>{dragged=null;resetDrag();},true);
  globalThis.addEventListener?.('blur',resetDrag);
  return {reset(){owner='';items.length=0;dragged=null;resetDrag();updateCount();},refresh(){syncOwner();updateCount();}};
}
