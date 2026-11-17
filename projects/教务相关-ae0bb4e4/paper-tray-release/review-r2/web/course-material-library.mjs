import {escapeHtml as e} from './core.mjs';
import {shelfMarkup} from './bookshelf.mjs?v=20261105-print-drop';
import {materialRoles,materialAudiences} from './course-materials.mjs?v=20261105-print-drop';
export function mergeMaterialCourses(...sources){
 const courses=new Map();
 for(const source of sources)for(const course of source?.courses||[]){
  const existing=courses.get(course.courseId);
  if(existing){existing.materials.push(...(course.materials||[]));if(!existing.courseName)existing.courseName=course.courseName;}
  else courses.set(course.courseId,{...course,materials:[...(course.materials||[])]});
 }
 return [...courses.values()];
}
export function libraryItems(courses){return courses.flatMap(c=>c.materials.map(m=>({...m,courseName:c.courseName,courseId:c.courseId})));}
const isPublished=material=>material.sourceKind==='published-book';
const publisherNames=books=>[...new Set(books.flatMap(book=>book.related.filter(isPublished).map(material=>String(material.publisherName||'').trim()).filter(Boolean)))];
export function folderEntries(items,path='',query=''){
 const prefix=path?path+'/':'';const text=query.trim().toLocaleLowerCase();const folders=new Map(),files=[];
 for(const m of items){
  if(!m.originalRelativePath.startsWith(prefix))continue;
  if(text){if((m.originalRelativePath+' '+m.title+' '+m.courseName+' '+(m.publisherName||'')).toLocaleLowerCase().includes(text))files.push(m);continue;}
  const tail=m.originalRelativePath.slice(prefix.length),slash=tail.indexOf('/');
  if(slash<0)files.push(m);else{const name=tail.slice(0,slash);folders.set(name,(folders.get(name)||0)+1);}
 }
 return {folders:[...folders].sort(([a],[b])=>a.localeCompare(b,'zh-CN')).map(([name,count])=>({name,path:prefix+name,count})),files:files.sort((a,b)=>a.originalRelativePath.localeCompare(b.originalRelativePath,'zh-CN')||a.materialVersion-b.materialVersion)};
}
// Only explicit provenance links form an imported book family; published books stay independent.
export function groupMaterialBooks(items){
 const key=m=>JSON.stringify([m.courseId,isPublished(m)?'published-book':'imported',m.id]),byKey=new Map(items.map(m=>[key(m),m])),parents=new Map([...byKey.keys()].map(k=>[k,k]));
 const root=k=>{while(parents.get(k)!==k){parents.set(k,parents.get(parents.get(k)));k=parents.get(k);}return k;};
 for(const m of items){if(isPublished(m))continue;for(const id of [m.companionOfMaterialId,m.previousMaterialId]){const peer=key({...m,id});if(id&&byKey.has(peer)){const a=root(key(m)),b=root(peer);if(a!==b)parents.set(a,b);}}}
 const groups=new Map();for(const m of byKey.values()){const id=root(key(m));if(!groups.has(id))groups.set(id,[]);groups.get(id).push(m);}
 const number=m=>Number.isFinite(Number(m.materialVersion))?Number(m.materialVersion):0;
 const rank=m=>m.format==='pdf'?2:m.format==='docx'?1:0;
 const role=m=>({handout:6,practice:5,notes:4,reference:3,solution:2,source:1}[m.role]||0);
 return [...groups.values()].map(related=>{const ids=new Set(related.map(m=>m.id));const primary=related.filter(m=>!m.companionOfMaterialId||!ids.has(m.companionOfMaterialId));const candidates=primary.length?primary:related;
  candidates.sort((a,b)=>number(b)-number(a)||role(b)-role(a)||rank(b)-rank(a)||a.id.localeCompare(b.id));
  related.sort((a,b)=>number(b)-number(a)||role(b)-role(a)||rank(b)-rank(a)||a.originalRelativePath.localeCompare(b.originalRelativePath,'zh-CN'));
  const material=candidates[0],versions=new Set(related.filter(m=>!m.companionOfMaterialId||!ids.has(m.companionOfMaterialId)).map(number));
  return {printSource:isPublished(material)?{kind:'published-book',id:material.id}:{kind:'material',id:material.id,courseId:material.courseId},id:related.map(m=>m.id).sort()[0],material,related,versionCount:Math.max(1,versions.size)};
 }).sort((a,b)=>a.material.originalRelativePath.localeCompare(b.material.originalRelativePath,'zh-CN'));
}
export function visibleMaterialBooks(groups,path='',query=''){
 const prefix=path?path+'/':'',q=query.trim().toLocaleLowerCase();
 return groups.filter(g=>g.related.some(m=>m.originalRelativePath.startsWith(prefix)&&(!q||[m.originalRelativePath,m.title,m.courseName,m.fileName,m.publisherName].join(' ').toLocaleLowerCase().includes(q))));
}
export function materialLectureCount(group){return new Set(group.material.lectureIds||[]).size;}
export function groupCourseBooks(books){
 const groups=new Map();
 for(const book of books){const id=book.material.courseId;if(!groups.has(id))groups.set(id,{id,title:book.material.courseName||'未命名课程',books:[]});groups.get(id).books.push(book);}
 return [...groups.values()].sort((a,b)=>a.title.localeCompare(b.title,'zh-CN',{numeric:true})||a.id.localeCompare(b.id));
}
export async function renderMaterialLibrary({path='',q='',book='',course='',bookSource=''},ui){
 const {api,paint,heading,button,go}=ui;
 const [imported,published]=await Promise.all([api('/course-material-library'),api('/published-course-books')]);
 const courses=mergeMaterialCourses(imported,published),items=libraryItems(courses),groups=groupMaterialBooks(items);
 const selectedCourse=course?courses.find(c=>c.courseId===course):null;
 if(course&&!selectedCourse)throw Error('课程已更新，请返回讲义库重新打开');
 const scopedItems=course?items.filter(m=>m.courseId===course):items;
 const entries=folderEntries(scopedItems,path),allBooks=visibleMaterialBooks(groups,path,q),books=course?allBooks.filter(g=>g.material.courseId===course):allBooks;
 const route=next=>go('material-library',{path,q,...(course?{course}:{}),...next});
 const back=()=>course?route({course:'',book:''}):path?route({path:path.split('/').slice(0,-1).join('/'),q:''}):go('home',{choose:'1'});
 const folder=book?groups.find(g=>g.material.id===book&&(!course||g.material.courseId===course)&&(!bookSource||g.material.sourceKind===bookSource)):null;
 if(book&&!folder)throw Error('这本讲义已更新，请返回讲义库重新打开');
 const linkCourseButton=(courseId,courseName)=>typeof ui.linkCourse==='function'?button('关联班级',()=>ui.linkCourse(courseId,courseName),'quiet'):'';
 if(folder){
  const material=folder.material,publishedBook=isPublished(material);
  const detail=await api(publishedBook?`/published-course-books/${encodeURIComponent(material.id)}`:`/course-material-library/${encodeURIComponent(material.courseId)}/${encodeURIComponent(material.id)}/sections`);
  const lectures=[...(detail.lectures||[])].sort((a,b)=>a.sequence-b.sequence);
  const children=lectures.map(l=>({printSource:publishedBook?{kind:'published-handout',id:l.handoutId,bookId:material.id}:{kind:'material',id:material.id,courseId:material.courseId,lectureId:l.id},id:l.id,title:`第${l.sequence}讲 · ${l.title}`,subtitle:'分讲讲义',meta:l.startPage&&l.endPage?`原稿第 ${l.startPage}—${l.endPage} 页`:((l.questionCount??l.questionIds?.length)?`${l.questionCount??l.questionIds.length} 道题`:'讲次资料'),badge:`第 ${l.sequence} 讲`,colorKey:folder.id,lecture:l}));
  const returnTo={path,q,course:material.courseId,book:material.id,...(publishedBook?{bookSource:'published-book'}:{})};
  const toCourse=()=>route({course:material.courseId,book:''});
  const publisherName=publishedBook?String(detail.book?.publisherName||material.publisherName||'').trim():'';
  const previewBook=()=>publishedBook?ui.previewPublishedBook({material,detail,returnTo}):ui.previewMaterial({material,related:folder.related,path,returnTo});
  const previewLecture=item=>publishedBook?ui.previewPublishedLecture({material,lecture:item.lecture,detail,returnTo}):ui.previewLecture({material,related:folder.related,path,lecture:item.lecture,sourceEditionId:detail.sourceEditionId,returnTo});
  paint(heading('讲义库',toCourse,button('题库',()=>go('question-bank',publishedBook?{}:{source:'m:'+material.id}),'quiet'))+`<nav class="bookshelf-breadcrumb" aria-label="资料位置">${button('全部课程',()=>route({course:'',book:''}),'quiet')}<span>›</span>${button(e(material.courseName),toCourse,'quiet')}${linkCourseButton(material.courseId,material.courseName)}</nav><section class="bookshelf-folder-panel"><div class="bookshelf-folder-toolbar"><div><h2 class="bookshelf-folder-title">${e(material.title||material.fileName)}</h2>${publisherName?`<p class="bookshelf-folder-publisher">发布：${e(publisherName)}</p>`:''}</div>${button('整本预览',previewBook,'quiet')}</div><p class="bookshelf-folder-note">${children.length} 讲 · 点击讲义预览</p>${shelfMarkup(children,{button,onOpen:previewLecture,emptyText:'这本讲义暂未拆出讲次，可先打开整本预览'})}</section>`);
  return {bookCount:1,lectureCount:lectures.length};
 }
 const courseGroups=groupCourseBooks(books);
 const bookCards=groups=>groups.map(g=>({...g,kind:isPublished(g.material)||materialLectureCount(g)>1?'folder':'book',children:Array.from({length:materialLectureCount(g)},()=>({title:'讲义',colorKey:g.id})),title:g.material.title||g.material.fileName,publisherName:isPublished(g.material)?String(g.material.publisherName||'').trim():'',subtitle:[materialRoles[g.material.role],materialAudiences[g.material.audience]].filter(Boolean).join(' · '),meta:g.related.length>1?`${g.related.length} 份原稿 / 版本`:'',badge:isPublished(g.material)?'已发布':g.versionCount>1?`${g.versionCount} 个版本`:'v'+g.material.materialVersion,colorKey:g.id}));
 const cards=course?bookCards(books):[];
 const actions=button('题库',()=>go('question-bank'),'quiet')+button('我的课程',()=>go('courses'),'quiet')+(ui.createCourse?button('创建课程',ui.createCourse,'quiet'):'');
 const folders=entries.folders.length?`<select id="bookshelf-folder" aria-label="选择文件夹分类"><option value="">选择分类 · ${entries.folders.length}</option>${entries.folders.map(f=>`<option value="${e(f.path)}">${e(f.name)}（${f.count}）</option>`).join('')}</select>`:'';
 const courseHeading=course?`<nav class="bookshelf-breadcrumb" aria-label="资料位置">${button('全部课程',()=>route({course:'',book:''}),'quiet')}<span>›</span><span aria-current="page">课程资料</span></nav><div class="bookshelf-course-heading"><h2>${e(selectedCourse.courseName)}</h2>${linkCourseButton(selectedCourse.courseId,selectedCourse.courseName)}</div>`:'';
 const openCard=g=>{if(g.folderType==='course')return route({course:g.id,book:''});if(g.kind==='folder')return route({book:g.material.id,course:g.material.courseId,...(isPublished(g.material)?{bookSource:'published-book'}:{})});if(typeof ui.previewMaterial!=='function')throw Error('预览暂未就绪，请刷新重试');return ui.previewMaterial({material:g.material,related:g.related,path,returnTo:{path,q,course,book:''}});};

 const shelf=course?shelfMarkup(cards,{button,onOpen:openCard,emptyText:q?'没有找到相关资料，换个关键词试试':'这个范围还没有讲义'}):courseGroups.map(c=>`<section class="course-book-section"><div class="bookshelf-course-heading"><h2>${e(c.title)}</h2><span class="muted">${c.books.length} 本资料</span></div>${shelfMarkup(bookCards(c.books),{button,onOpen:openCard})}</section>`).join('')||'<p class="muted">当前范围暂无讲义</p>';

 paint(heading('讲义库',back,actions)+`<section id="material-bookshelf">${courseHeading}<form id="material-library-search" class="material-bookshelf-tools"><input id="material-library-query" type="search" aria-label="查找讲义" placeholder="${course?'查找本课程的讲义':'查找课程、书名或年级'}" value="${e(q)}"><button class="quiet" type="submit">查找</button></form><div class="material-bookshelf-location">${path?button('全部目录',()=>route({path:'',q:''}),'quiet')+button('上一级目录',()=>route({path:path.split('/').slice(0,-1).join('/'),q:''}),'quiet'):''}<span>${e(path||'全部目录')}</span>${folders}</div><div class="material-bookshelf-count"><strong>${course?`${books.length} 本资料`:`${courseGroups.length} 门课程 · ${books.length} 本资料`}</strong><span>${course?'点教材文件夹查看分讲 · 点书预览':'按课程直接查看讲义'}</span></div>${shelf}<div class="material-bookshelf-foot">${ui.courseHelp||''}</div></section>`);
 document.getElementById('material-library-search').addEventListener('submit',event=>{event.preventDefault();route({q:document.getElementById('material-library-query').value});});
 document.getElementById('bookshelf-folder')?.addEventListener('change',event=>{if(event.target.value)route({path:event.target.value,q:''});});
 return {bookCount:books.length,courseCount:courseGroups.length,sourceCount:items.length};
}
