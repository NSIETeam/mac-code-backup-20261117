import {escapeHtml as e} from './core.mjs';
export function courseRelationshipMarkup(){return '<details class="course-relationship"><summary>课程与班级有什么区别？</summary><p><strong>课程</strong>保存共享的讲次安排和讲义；<strong>班级</strong>保存学生名单、实际课次和授课记录。</p><p>班级关联课程后，可选择课程讲次、查看讲义。各班的学生名单和错题记录独立；共享教材换题会更新所有关联班级使用的版本，同时保留旧版。</p></details>';}
export function courseNamePreview(values){return [values.year||'年份',values.term||'学期',values.grade||'年级',values.subject||'学科',values.type||'类型'].join('·');}
export function validateCourseInput(values,rules){
 const year=Number(values.year);if(!Number.isInteger(year)||year<rules.minimumYear||year>rules.maximumYear)throw Error(`年份须为 ${rules.minimumYear} 至 ${rules.maximumYear} 的整数`);
 for(const [field,collection] of [['term','terms'],['grade','grades'],['subject','subjects'],['type','types']])if(!rules[collection]?.includes(values[field]))throw Error('请完整选择学期、年级、学科和课程类型');
 return {year,term:values.term,grade:values.grade,subject:values.subject,type:values.type};
}
export async function showCourseCreate({api,modal,button,bindForm,document=globalThis.document,onSave,onCancel,className=''}){
 const rules=await api('/course-naming-options'),requestId=crypto.randomUUID(),currentYear=Math.min(rules.maximumYear,Math.max(rules.minimumYear,new Date().getFullYear()));
 const choice=(label,key,values)=>`<label class="field">${label}<select name="${key}" required><option value="">请选择${label}</option>${values.map(value=>`<option value="${e(value)}">${e(value)}</option>`).join('')}</select></label>`;
 modal(className?'创建课程并关联班级':'创建课程',`<form id="create-standard-course"><p class="muted">${className?'保存后关联到“'+e(className)+'”。':'创建后可在班级中关联使用。'}课程名称按统一规则生成。</p><div class="course-create-fields"><label class="field">年份<input name="year" type="number" required min="${rules.minimumYear}" max="${rules.maximumYear}" step="1" value="${currentYear}"></label>${choice('学期','term',rules.terms)}${choice('年级','grade',rules.grades)}${choice('学科','subject',rules.subjects)}${choice('课程类型','type',rules.types)}</div><div class="course-name-preview"><span class="muted">课程名称</span><strong id="standard-course-name" aria-live="polite"></strong></div>${courseRelationshipMarkup()}<div class="actions"><button type="submit" class="primary">${className?'创建并关联':'创建课程'}</button>${button('取消',onCancel,'quiet')}</div></form>`);
 const form=document.getElementById('create-standard-course');const update=()=>{const values=Object.fromEntries(['year','term','grade','subject','type'].map(key=>[key,form.elements[key].value]));document.getElementById('standard-course-name').textContent=courseNamePreview(values);};form.addEventListener('input',update);form.addEventListener('change',update);update();
 bindForm('create-standard-course',async values=>{const input=validateCourseInput(values,rules),course=await api('/courses','POST',{...input,requestId});await onSave(course);});
}
