import type {RoleEntry,ProcessEntry,SourceRef,Candidate} from './dictionary-types';
import {extraSources,extraRoles,extraProcesses,extraCandidates} from './data-roles';
import {trainingSources,trainingRoles,trainingProcesses,trainingCandidates} from './data-training';
import {academicSources,academicRoles,academicProcesses,academicCandidates} from './data-academic';
const sourceNotes:Record<string,string>={
  RF06:'原文件使用“店长”，未解释其他岗位称谓。用户本轮已明确旧称“校长”与店长为同一岗位，现统一称店长（US03）；职责依据仍为原表，实际权限和执行待核实。',
  RF29:'保留原组织描述；用户本轮已明确不再单列校长，旧称“校长”并入店长（US03）。这项称谓修正不补充完整职责，也不自动确认当前审批权限。',
};
export const sources:SourceRef[]=[...extraSources,...trainingSources,...academicSources,{id:'US01',file:'用户本轮补充',locator:'网站建设期间的岗位说明',excerpt:'我的岗位是ai部',note:'确认岗位名称；尚未收到AI部的正式职责、编制、汇报关系或考核标准。'}].map(source=>({...source,note:sourceNotes[source.id]??source.note}));
sources.push({id:'US02',file:'用户本轮补充',locator:'HR、行政与负责人登记规则',excerpt:'还有HR、行政人员；点击部门负责人可以接着选择是哪个部门，如果是集团负责人就是老板。',note:'确认岗位选项和显示方式，不代表岗位职责、任命或系统权限。'});
sources.push({id:'US03',file:'用户本轮组织修正',locator:'校区岗位名称与合并说明',excerpt:'没有校长这个岗位了，校长就是店长',note:'当前组织统一使用店长，校长保留为旧称和原资料用语。此说明确认岗位对应关系，不将历史审批配置视为现行授权，也不证明书面职责已经执行。'});
const roleMap=new Map<string,RoleEntry>();
for(const role of [...extraRoles,...trainingRoles,...academicRoles])roleMap.set(role.id,role);
const manager=roleMap.get('manager');
const formerPrincipal=roleMap.get('principal');
if(manager)roleMap.set('manager',{
  ...manager,
  aliases:[...new Set([...manager.aliases,'校长',...(formerPrincipal?.aliases??[])])],
  summary:'店长承担行为流表中的目标管理、招生复盘、培训、校区获客、现场巡场和教务接待支援。用户已确认原称“校长”的岗位统一为店长；书面要求的实际执行仍待核实。',
  scope:'书面职责依据Excel“店长”表及教务表中店长协作要求。岗位合并依据用户最新组织说明（US03），校长只作为旧称及历史资料用语；不据此推定现行审批、人员或考核权限。',
  questions:[...new Set([
    ...manager.questions.filter(question=>!question.startsWith('店长和校长是否同一岗位')),
    ...(formerPrincipal?.questions??[]).filter(question=>!question.startsWith('校长和店长是否为同一角色')).map(question=>question.replaceAll('校长','店长')),
  ])],
  related:[...manager.related,...(formerPrincipal?.related??[])],
  processIds:[...new Set([...manager.processIds,...(formerPrincipal?.processIds??[])])],
});
roleMap.delete('principal');
roleMap.set('ai-dept',{id:'ai-dept',name:'AI部',aliases:['AI 部','人工智能部门','AI部门'],group:'职能支持',summary:'用户已确认的岗位名称。对应的职责分工、汇报关系及考核标准待补充。',status:'组织描述',scope:'依据用户本轮补充（US01）。五份业务资料没有独立AI部岗位说明。',duties:[],schedule:[],checks:[],handoffs:[],questions:['AI部负责流程诊断、系统建设、AI工具开发、落地培训中的哪些事项？','各业务部门如何提交需求，由谁确认价值、优先级和验收？','工具上线后的维护、权限审批和效果跟踪分别由谁负责？'],related:['grade-lead','principal','admissions-lead'],processIds:[]});
roleMap.set('admin',{id:'admin',name:'行政人员',aliases:['行政','行政部'],group:'职能支持',summary:'用户已确认的岗位；正式职责、交接与考核要求待补充。',status:'资料待补',scope:'依据用户补充（US02）。现有五份资料不足以完整还原行政岗位。',duties:[],schedule:[],checks:[],handoffs:[],questions:['行政的服务范围、对接部门和工作频次是什么？','哪些事项由行政负责，哪些由校区教务或店长负责？','使用哪些表单系统，如何验收与记录异常？'],related:['hr','manager','principal'],processIds:[]});
roleMap.set('dept-head',{id:'dept-head',name:'部门负责人',aliases:['集团负责人','老板','负责人'],group:'管理与协同',summary:'登记时选择具体负责部门；选择集团时显示集团负责人（老板）。',status:'组织描述',scope:'依据用户补充（US02）；职责、任命、审批边界与考核待核实。登记岗位不会赋予额外系统权限。',duties:[],schedule:[],checks:[],handoffs:[],questions:['负责哪个部门，是否兼任其他岗位？','哪些事项可以决定，哪些需要集团负责人审批？','部门需求由谁排优先级、验收并跟进效果？'],related:['principal','grade-lead','admissions-lead','ai-dept','hr','admin'],processIds:[]});
const hr=roleMap.get('hr');if(hr)roleMap.set('hr',{...hr,name:'HR',aliases:['人事','人事经理','HR人员']});
const order=['academic','admissions','grade-ops','new-media','teacher','manager','grade-lead','admissions-lead','subject-lead','one-to-one','dept-head','ai-dept','finance','hr','admin','wechat-admin'];
const groupMap:Record<string,string>={teacher:'教学与服务',academic:'教学与服务','subject-lead':'教学与服务','new-media':'招生与运营'};
const currentRoleId=(id:string)=>id==='principal'?'manager':id;
const currentRoleIds=(ids:string[])=>[...new Set(ids.map(currentRoleId))];
export const roles:RoleEntry[]=[...roleMap.values()].map(r=>({...r,group:groupMap[r.id]||r.group,related:currentRoleIds(r.related).filter(id=>id!==r.id),questions:r.questions.map(question=>question.replaceAll('校长','店长'))})).sort((a,b)=>order.indexOf(a.id)-order.indexOf(b.id));
export const processes:ProcessEntry[]=[...extraProcesses,...trainingProcesses,...academicProcesses].map(process=>({...process,roles:currentRoleIds(process.roles),questions:process.questions.map(question=>question==='历史店长与二级指定审批人目前对应什么有效权限，校长是否仅抄送？'?'历史退费流程中的审批和抄送配置，按当前统一店长岗位应如何设置？二级审批由谁承担？':question.replaceAll('校长','店长'))}));
export const candidates:Candidate[]=[...extraCandidates,...trainingCandidates,...academicCandidates].map(candidate=>({...candidate,roleId:currentRoleId(candidate.roleId)}));
