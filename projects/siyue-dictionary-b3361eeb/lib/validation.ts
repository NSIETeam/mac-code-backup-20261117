import {z} from 'zod';
import {roles,processes} from './dictionary-data';
import {needCategories,needStatuses,departments} from './dictionary-types';
const opt=(max:number)=>z.string().trim().max(max,'填写内容过长').optional().default('');
export const normalizeRoleId=(id:string)=>id==='principal'?'manager':id;
export const normalizeRoleIds=(ids:string[])=>[...new Set(ids.map(normalizeRoleId))];
const roleIdSchema=z.string().transform(normalizeRoleId).refine(id=>roles.some(r=>r.id===id),'请选择已有岗位');
const profileFields=z.object({name:z.string().trim().min(2,'姓名至少2个字符').max(30,'姓名最多30个字符'),roleIds:z.array(z.string()).min(1,'请选择至少一个岗位').max(10).transform(normalizeRoleIds).refine(ids=>ids.every(id=>roles.some(r=>r.id===id)),'请选择已有岗位'),department:opt(60),campus:opt(60),grade:opt(40)});
function checkDepartment(p:{roleIds:string[];department:string},ctx:z.RefinementCtx){if(p.roleIds.includes('dept-head')&&!departments.includes(p.department))ctx.addIssue({code:z.ZodIssueCode.custom,path:['department'],message:'请选择负责的部门'});}
export const profileSchema=profileFields.superRefine(checkDepartment).transform(p=>({...p,department:p.roleIds.includes('dept-head')?p.department:''}));
export const registerSchema=profileFields.extend({password:z.string().min(10,'密码至少10个字符').max(128,'密码最多128个字符')}).superRefine(checkDepartment).transform(p=>({...p,department:p.roleIds.includes('dept-head')?p.department:''}));
const amount=(max:number)=>z.number().finite().nonnegative('请输入非负数').max(max,'数值过大，请核对').nullable().optional().default(null);
function titleFromProblem(problem:string){
 const firstLine=problem.split(/[\r\n\u2028\u2029]/u,1)[0].trim();
 const firstSentence=firstLine.split(/[。！？!?]|\.(?=\s|$)/u,1)[0].trim()||firstLine;
 // Keep a literal prefix and avoid splitting a surrogate pair at the limit.
 return firstSentence.slice(0,100).replace(/[\uD800-\uDBFF]$/u,'');
}
export const needSchema=z.object({requestId:z.string().uuid('提交标识无效，请刷新'),title:z.string().trim().max(100,'标题最多100字').optional().default(''),roleId:roleIdSchema,processId:opt(80).refine(id=>!id||processes.some(p=>p.id===id),'请选择已有流程'),campus:opt(60),grade:opt(40),category:z.string().refine(s=>needCategories.includes(s),'请选择需求类型').optional().default('待分类'),problem:opt(2500),currentMethod:opt(1500),desiredOutcome:opt(1500),evidence:opt(1500),frequencyPerWeek:amount(10000),minutesPerOccurrence:amount(10080),affectedPeople:z.number().int('人数须为整数').min(1).max(100000).nullable().optional().default(null),urgency:z.enum(['一般','较急','紧急']).optional().default('一般'),recordingIds:z.array(z.string().uuid('录音标识无效')).max(5,'每条反馈最多5段录音').refine(ids=>new Set(ids).size===ids.length,'请勿重复添加同一段录音').optional().default([])}).superRefine((p,ctx)=>{if(p.problem.length<5&&!p.recordingIds.length)ctx.addIssue({code:z.ZodIssueCode.custom,path:['problem'],message:'请用至少5个字描述问题，或添加一段录音'});}).transform(p=>({...p,title:p.title||(p.problem?titleFromProblem(p.problem):'语音反馈')}));
export const updateNeedSchema=z.object({status:z.string().refine(s=>needStatuses.includes(s),'需求状态无效'),version:z.number().int().positive()});
export const ratingSchema=z.object({score:z.number().int('评分须为0到100的整数').min(0,'评分不能低于0分').max(100,'评分不能高于100分'),comment:opt(1500),version:z.number().int('评分版本无效，请刷新').min(0,'评分版本无效，请刷新').max(Number.MAX_SAFE_INTEGER-1)});
