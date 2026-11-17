import {printSourceAttrs} from './print-drop.mjs?v=20261105-print-drop';
import {escapeHtml as e} from './core.mjs';
export const materialRoles={handout:'讲义',practice:'练习',solution:'解析',notes:'笔记',source:'源格式',reference:'参考'};
export const materialAudiences={student:'学生用',teacher:'教师用',both:'师生共用'};
export function selectMaterials(items,role='',lectureId=''){
 return items.filter(m=>(!role||m.role===role)&&(!lectureId||m.lectureIds.includes(lectureId)));
}
export function materialContext(material,lectures,items){
 const lessons=material.lectureIds.map(id=>lectures.find(l=>l.id===id)).filter(Boolean).map(l=>'第'+l.sequence+'讲').join('、')||'课程通用';
 const companion=items.find(m=>m.id===material.companionOfMaterialId);
 const previous=items.find(m=>m.id===material.previousMaterialId);
 return [materialRoles[material.role],materialAudiences[material.audience],lessons,'资料版本 '+material.materialVersion,material.format.toUpperCase(),companion?'配套：'+companion.title:'',previous?'前版：v'+previous.materialVersion:''].filter(Boolean).join(' · ');
}
export function courseMaterialRows(items,data,{button,download}){
 return `<div class="stack course-material-rows">${items.map(m=>`<div class="row" ${printSourceAttrs({kind:'material',scope:'course',id:m.id,courseId:data.courseId})}><div class="grow"><strong>${e(m.title)}</strong><div class="muted small">${e(materialContext(m,data.lectures,data.materials))}</div><div class="muted small">${e(m.fileName)}</div></div>${button('下载',()=>download(m),'quiet')}</div>`).join('')||'<p class="muted">当前范围暂无配套资料</p>'}</div>`;
}
export async function renderCourseMaterials({id,classId='',lectureId='',role=''},ui){
 const {api,paint,heading,button,go,openResource}=ui;
 const data=await api('/courses/'+encodeURIComponent(id)+'/materials');
 const route=(next)=>go('course-materials',{id,...(classId?{classId}:{}),role,lectureId,...next});
 const items=selectMaterials(data.materials,role,lectureId);
 const download=async material=>{const file=await api(`/courses/${encodeURIComponent(id)}/materials/${encodeURIComponent(material.id)}/file`);await openResource(file.url,file.fileName);};
 const filters=`<div class="pill-nav">${button('全部',()=>route({role:''}),role?'quiet':'active')}${Object.entries(materialRoles).map(([key,label])=>button(label,()=>route({role:key}),key===role?'active':'quiet')).join('')}</div>`;
 paint(heading(data.courseName,()=>classId?go('class',{id:classId,handouts:'1'}):go('courses'))+(ui.courseHelp||'')+filters+`<label class="field">讲次<select id="course-material-lecture"><option value="">全部讲次与课程通用资料</option>${data.lectures.map(l=>`<option value="${e(l.id)}" ${l.id===lectureId?'selected':''}>第${e(l.sequence)}讲 · ${e(l.title)}</option>`).join('')}</select></label>${courseMaterialRows(items,data,{button,download})}`);
 document.getElementById('course-material-lecture').addEventListener('change',event=>route({lectureId:event.target.value}));
}
