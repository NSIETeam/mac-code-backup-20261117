import {printSourceAttrs} from './print-drop.mjs?v=20261105-print-drop';
import {escapeHtml as e} from './core.mjs';
export function bookTone(value='') {let hash=0;for(const ch of String(value))hash=(hash*31+ch.codePointAt(0))>>>0;return hash%8;}
export function bookMarkup(item,{button,onOpen}) {
 const title=String(item.title||'未命名讲义').replace(/\.(pdf|docx?|wsd)$/i,'');
 const contents=`<span class="book-cover book-tone-${bookTone(item.colorKey||item.id||title)}"><span class="book-cover-imprint">TEACHING LIBRARY</span><span class="book-cover-rule"></span><strong class="book-cover-title">${e(title)}</strong><span class="book-cover-subtitle">${e(item.subtitle||'教学讲义')}</span><span class="book-cover-bottom">${e(item.badge||'讲义')}</span></span><span class="book-shelf-caption">${e(title)}</span>${item.meta?`<span class="book-shelf-meta">${e(item.meta)}</span>`:''}`;
 const control=button(contents,()=>onOpen(item),'bookshelf-book');
 return `<article class="bookshelf-item" ${printSourceAttrs(item.printSource)} title="${e(title)}">${control.replace('<button ','<button aria-label="预览：'+e(title)+'" ')}</article>`;
}
export function folderMarkup(item,{button,onOpen}) {
 const title=String(item.title||'未命名讲义').replace(/\.(pdf|docx?|wsd)$/i,'');
 const isCourse=item.folderType==='course';
 const children=Array.isArray(item.children)?item.children:[];
 const countLabel=item.countLabel||(isCourse?`${children.length} 本资料`:`共 ${children.length} 讲`);
 const previews=children.slice(0,9).map((child,index)=>`<span class="bookshelf-folder-mini book-tone-${bookTone(child.colorKey||item.colorKey||child.title||`${item.id||title}-${index}`)}"><span class="bookshelf-folder-mini-title">${e(child.title||`第 ${index+1} 讲`)}</span><span class="bookshelf-folder-mini-rule"></span></span>`).join('');
 const contents=`<span class="bookshelf-folder-cover" aria-hidden="true"><span class="bookshelf-folder-well">${previews}</span>${item.badge?`<span class="bookshelf-folder-badge">${e(item.badge)}</span>`:''}</span><span class="book-shelf-caption">${e(title)}</span>${item.publisherName?`<span class="book-shelf-publisher">发布：${e(item.publisherName)}</span>`:''}<span class="book-shelf-meta bookshelf-folder-meta">${e(countLabel)}${item.meta?`<span class="bookshelf-folder-detail"> · ${e(item.meta)}</span>`:''}</span>`;
 const control=button(contents,()=>onOpen(item),'bookshelf-book bookshelf-folder');
 return `<article class="bookshelf-item bookshelf-folder-item${isCourse?' book-course-folder':''}" title="${e(title)}" ${item.kind==='folder'?printSourceAttrs(item.printSource):''}>${control.replace('<button ','<button aria-label="打开文件夹：'+e(title)+'" ')}</article>`;
}
export function shelfMarkup(items,ctx) {return `<div class="bookshelf-case">${items.length?`<div class="bookshelf-grid">${items.map(item=>item.kind==='folder'?folderMarkup(item,ctx):bookMarkup(item,ctx)).join('')}</div>`:`<div class="bookshelf-empty">${e(ctx.emptyText||'这里还没有讲义')}</div>`}</div>`;}
