'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const auth=require('../cli/auth-client.cjs'),business=require('../cli/business-client.cjs');
const object=(properties={},required=[])=>({type:'object',properties,required,additionalProperties:false});
const string={type:'string'};
const taskSourceSchema={type:'object',additionalProperties:false,properties:{
 type:{type:'string',enum:['lead','touchpoint']},id:{type:'string',pattern:'^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$'},
 version:{type:['integer','null'],minimum:1}},required:['type','id'],oneOf:[
 {properties:{type:{const:'lead'},version:{type:'integer',minimum:1}},required:['version']},
 {properties:{type:{const:'touchpoint'},version:{type:'null'}}}
]};
function record(value){if(!value||typeof value!=='object'||Array.isArray(value))throw auth.failure('INVALID_ARGUMENT','参数必须为 JSON 对象');return value;}
function exact(value,keys){record(value);if(Object.keys(value).some(k=>!keys.includes(k)))throw auth.failure('INVALID_ARGUMENT','包含不支持的参数');}
function opName(id){return 'siyue_preview_'+id.replaceAll('.','_');}
function result(data){return {content:[{type:'text',text:JSON.stringify(data)}],structuredContent:data};}
function errorResult(error){return {content:[{type:'text',text:JSON.stringify({code:error.code||'TOOL_FAILED',message:error.code?error.message:'工具执行失败，请检查连接及参数'})}],isError:true};}
function createBridge({env=process.env}={}){
 function privateFile(folder,id){if(!/^[0-9a-f-]{36}$/.test(id))throw auth.failure('INVALID_PLAN','计划编号无效');const base=auth.configDirectory(env),directory=auth.configDirectory({SIYUE_CONFIG_DIR:path.join(base,folder)});return path.join(directory,id+'.json');}
 async function context(){const client=auth.sessionClient(env),apiVersion=client.apiVersion,me=apiVersion==='v2'?await require('../cli/java-query-client.cjs').identity(client):await client.request('v1/auth/me');return {client,me,apiVersion};}
 async function catalog(client){const response=await client.request('v1/catalog');return (response.operations||[]).filter(o=>o.implemented===true);}
 const baseTools=[
  {name:'siyue_status',title:'当前员工身份',description:'检查 CLI 登录状态和当前权限。不返回会话令牌；先在终端运行 siyue-cli login 或 login-java。',inputSchema:object(),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_operations',title:'查找平台操作',description:'实时查询当前员工有权执行的全部业务操作及输入字段；关键词可用中文。',inputSchema:object({keyword:string,operationId:string}),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_query',title:'查询平台资料',description:'按授权范围查询档案、课程、班级、日志、报表、补课候选和考勤名单。使用 query 名称与其参数；不会取得额外权限。',inputSchema:object({query:{type:'string',enum:require('../cli/query-client.cjs').queries.map(q=>q.id)},input:{type:'object'}},['query']),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_queries',title:'查询参数说明',description:'列出命名查询及 JSON 输入字段。数据查询在服务端执行权限隔离。',inputSchema:object(),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_execute',title:'提交已预览的业务',description:'提交此前预览返回的 planId；需先核对预览与用户意图。超时只重试原 planId，不创建新计划。不能跨员工或跨服务器提交。',inputSchema:object({planId:string},['planId']),annotations:{readOnlyHint:false,destructiveHint:true,idempotentHint:true,openWorldHint:false}}
 ];
 const javaTools=[
 {name:'siyue_status',title:'当前员工身份',description:'通过 Java OIDC 身份映射检查当前员工和服务端权限；不返回会话令牌。',inputSchema:object(),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_queries',title:'Java 查询参数',description:'列出当前接入 Java v2 的 CRM、教务、经营及审计查询资源和固定字段白名单。',inputSchema:object(),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_query',title:'查询 Java 业务资料',description:'使用 Java v2 固定资源和字段白名单查询；数据范围、敏感字段投影和分页由服务端按 OIDC 身份授权。',inputSchema:object({query:{type:'string',enum:require('../cli/java-query-client.cjs').queries().map(q=>q.id)},input:{type:'object'}},['query']),annotations:{readOnlyHint:true,openWorldHint:false}},
  {name:'siyue_preview_task_batch',title:'预览 CRM 任务批次',description:'按当前 Java v2 身份预览任务批次及逐学生需求负责人分配，不写入业务数据。返回私有 planId；检查学生需求与分配后，再调用 siyue_execute_task_batch。',inputSchema:object({input:{type:'object',additionalProperties:false,properties:{title:{type:'string'},taskType:{type:'string',enum:['marketing','service']},serviceType:{type:['string','null']},customerIds:{type:'array',items:{type:'string'},minItems:1,maxItems:500},targets:{type:'array',minItems:1,maxItems:500,items:{type:'object',additionalProperties:false,properties:{customerId:{type:'string'},studentId:{type:['string','null']},subject:{type:['string','null']},term:{type:['string','null']},needIdentity:{type:['object','null'],additionalProperties:false,properties:{departmentId:{type:'string'},projectId:{type:['string','null']}},required:['departmentId']},sources:{type:'array',maxItems:100,items:taskSourceSchema}},required:['customerId']}},activityId:{type:['string','null']},subject:{type:['string','null']},term:{type:['string','null']},ownerIds:{type:'array',items:{type:'string'},minItems:1,maxItems:100},strategy:{type:'string',enum:['even','quota','owner_first']},quotas:{type:['array','null'],items:{type:'object',additionalProperties:false,properties:{ownerId:{type:'string'},count:{type:'integer',minimum:0}},required:['ownerId','count']}},dueAt:{type:'string'},note:{type:'string'}},required:['title','taskType','ownerIds','strategy','dueAt'],oneOf:[{required:['customerIds'],not:{required:['targets']}},{required:['targets'],not:{required:['customerIds']}}]}},['input']),annotations:{readOnlyHint:true,destructiveHint:false,idempotentHint:true,openWorldHint:false}},
  {name:'siyue_execute_task_batch',title:'提交已预览任务批次',description:'用预览返回的 planId 提交；服务端在写入事务内重新计算并校验每个学生需求的资格与负责人，变化时拒绝提交。超时只重试原 planId。',inputSchema:object({planId:string},['planId']),annotations:{readOnlyHint:false,destructiveHint:true,idempotentHint:true,openWorldHint:false}},
 ];
 async function tools(){
  let ctx;try{ctx=await context()}catch(error){if(['UNAUTHENTICATED','SESSION_EXPIRED','AUTH_REQUIRED','INVALID_SESSION'].includes(error.code))return [baseTools[0]];throw error;}
  if(ctx.apiVersion==='v2')return [...javaTools.map(tool=>tool.name==='siyue_query'?{...tool,inputSchema:{...tool.inputSchema,properties:{...tool.inputSchema.properties,query:{type:'string',enum:require('../cli/java-query-client.cjs').queries(ctx.me).map(query=>query.id)}}}}:tool),...require('./java-need-tools.cjs').tools(ctx.me.permissions),...require('./java-identity-tools.cjs').tools(ctx.me),...require('./java-task-routing-tools.cjs').tools(ctx.me)];
  if(ctx.me.user.mustChangePassword)return [baseTools[0]];
  const operations=await catalog(ctx.client),names=new Set(baseTools.map(t=>t.name));
  const generated=operations.map(op=>{const name=opName(op.id);if(names.has(name))throw auth.failure('INVALID_CATALOG','操作工具名称重复');names.add(name);return {name,title:op.label,description:`预览「${op.label}」（${op.id}），不会提交业务。返回 planId 和计算结果；确认后使用 siyue_execute 提交。`,inputSchema:op.inputSchema,annotations:{readOnlyHint:false,destructiveHint:false,idempotentHint:false,openWorldHint:false}}});
  const admin=ctx.me.user.roleIds.includes('superadmin')?require('../cli/admin-service.cjs').operations.map(op=>({name:'siyue_admin_'+op.action,title:op.title,description:`${op.title}。立即提交账号/角色管理操作，仅超级管理员可用；初始密码只保存到本机私有凭据文件，不返回模型。未知结果请先核对账号和日志，勿自动重试。`,inputSchema:op.credentialFileRequired?{...op.inputSchema,properties:{...op.inputSchema.properties,requestId:{type:'string',format:'uuid',description:'本次请求的唯一 UUID；重试必须使用原值，不能换号重新签发密码'}},required:[...op.inputSchema.required,'requestId']}:op.inputSchema,annotations:{readOnlyHint:false,destructiveHint:true,idempotentHint:false,openWorldHint:false}})):[];
  return [...baseTools,...generated,...admin];
 }
 async function call(name,input={}){
  record(input);
  if(name==='siyue_status'){
   exact(input,[]);try{const {me,client,apiVersion}=await context();return result({authenticated:true,server:client.server,apiVersion,...(apiVersion==='v2'?{identity:me}:{user:me.user,expiresAt:me.expiresAt,...(me.user.mustChangePassword?{next:'运行 siyue-cli passwd 修改初始密码后重新登录'}:{})})})}catch(error){if(['UNAUTHENTICATED','SESSION_EXPIRED','AUTH_REQUIRED'].includes(error.code))return result({authenticated:false,next:'先在 CLI 完成登录；Java v2 使用 siyue-cli login-java'});throw error;}
  }
  const {client,me,apiVersion}=await context();
  if(apiVersion==='v2'){
   const javaQuery=require('../cli/java-query-client.cjs');
   if(name==='siyue_queries'){exact(input,[]);return result({queries:javaQuery.queries(me)});}
   if(name==='siyue_query'){exact(input,['query','input']);return result(await javaQuery.query(input.query,input.input||{},client));}
   const routing=require('../cli/java-task-routing.cjs');
   if(name==='siyue_preview_task_routing'){
    exact(input,['operation','batchId','input']);const {operation,...request}=input;
    const prepared=await routing.prepare(operation,request,{client,me});
    if(!prepared.plan)return result({preview:prepared.preview,planId:null,commit:false,next:prepared.next});
    const id=crypto.randomUUID();business.write(privateFile('mcp-java-task-routing-plans',id),prepared.plan);
    return result({operationId:prepared.plan.operationId,preview:prepared.preview,planId:id,commit:false,
     next:'核对逐条实际分配后调用 siyue_execute_task_routing，传入原 planId'});
   }
   if(name==='siyue_execute_task_routing'){
    exact(input,['planId']);const plan=business.read(privateFile('mcp-java-task-routing-plans',input.planId));
    return result(await routing.executePlan(plan,{client,me}));
   }
   const identity=require('../cli/java-customer-identity.cjs');
   if(name==='siyue_preview_identity_operation'){
    exact(input,['operation','customerId','studentId','identifierId','input']);const {operation,...request}=input;
    const prepared=await identity.prepare(operation,request,{client,me}),id=crypto.randomUUID();
    business.write(privateFile('mcp-java-identity-plans',id),prepared.plan);
    return result({operationId:prepared.plan.operationId,preview:prepared.preview,planId:id,commit:false,
     next:'核对后调用 siyue_execute_identity_operation，并传入相同 planId'});
   }
   if(name==='siyue_execute_identity_operation'){
    exact(input,['planId']);const plan=business.read(privateFile('mcp-java-identity-plans',input.planId));
    return result(await identity.executePlan(plan,{client,me}));
   }
   const needs=require('../cli/java-crm-needs.cjs');
   if(name==='siyue_preview_need_operation'){
    exact(input,['operation','input','targetId','decision']);const {operation,...request}=input;
    const prepared=await needs.prepare(operation,request,{client,me});
    if(!prepared.plan)return result({preview:prepared.preview,planId:null,commit:false,next:prepared.next});
    const id=crypto.randomUUID();business.write(privateFile('mcp-java-need-plans',id),prepared.plan);
    return result({operationId:prepared.plan.operationId,preview:prepared.preview,decision:request.decision||null,
     planId:id,commit:false,next:'核对后调用 siyue_execute_need_operation，并传入相同 planId'});
   }
   if(name==='siyue_execute_need_operation'){
    exact(input,['planId']);const plan=business.read(privateFile('mcp-java-need-plans',input.planId));
    return result(await needs.executePlan(plan,{client,me}));
   }
   const taskBatch=require('../cli/java-task-batch.cjs');
   if(name==='siyue_preview_task_batch'){
    exact(input,['input']);const preview=await taskBatch.fetchPreview(input.input,client),plan=taskBatch.buildPlan({input:input.input,preview,client,me});
    const id=crypto.randomUUID();business.write(privateFile('mcp-java-plans',id),plan);
    return result({operationId:plan.operationId,preview,planId:id,commit:false,next:'核对预览后调用 siyue_execute_task_batch，并传入相同 planId'});
   }
   if(name==='siyue_execute_task_batch'){
    exact(input,['planId']);const plan=business.read(privateFile('mcp-java-plans',input.planId));
    return result(await taskBatch.executePlan(plan,{client,me}));
   }
   throw auth.failure('JAVA_V2_UNAVAILABLE','该操作尚未迁移至 Java v2；请使用当前已公布的查询与核对办理工具。');
  }
  if(me.user.mustChangePassword)throw auth.failure('PASSWORD_CHANGE_REQUIRED','请先运行 siyue-cli passwd 并重新登录');
  if(name==='siyue_queries'){exact(input,[]);return result({queries:require('../cli/query-client.cjs').queries});}
  if(name==='siyue_query'){exact(input,['query','input']);const data=await require('../cli/query-client.cjs').query(input.query,input.input||{},client.request);return result(record(data));}
  if(name==='siyue_operations'){exact(input,['keyword','operationId']);for(const key of Object.keys(input))if(typeof input[key]!=='string')throw auth.failure('INVALID_ARGUMENT','查询词必须为文本');const ops=await catalog(client);return result({operations:ops.filter(o=>(!input.operationId||o.id===input.operationId)&&(!input.keyword||[o.id,o.label,...o.aliases||[]].join(' ').includes(input.keyword)))});}
  if(name==='siyue_execute'){
   exact(input,['planId']);const p=business.read(privateFile('mcp-plans',input.planId));
   if(p.format!=='siyue-plan-v1'||p.server!==client.server||p.actorId!==me.user.id)throw auth.failure('WRONG_ACTOR','计划不属于当前员工或服务器');
   if(!/^[a-zA-Z][a-zA-Z0-9.]+$/.test(p.operationId))throw auth.failure('INVALID_PLAN','计划操作编号无效');
   const ops=await catalog(client);if(!ops.some(o=>o.id===p.operationId))throw auth.failure('FORBIDDEN','当前账号已无权提交此操作');
   return result(await client.request('v1/operations/'+p.operationId+'/execute',{method:'POST',idempotencyKey:p.idempotencyKey,body:{input:p.input,expectedRevision:p.expectedRevision,previewToken:p.previewToken}}));
  }
  if(name.startsWith('siyue_preview_')){
   const ops=await catalog(client),op=ops.find(o=>opName(o.id)===name);if(!op)throw auth.failure('FORBIDDEN','操作不存在或当前账号无此权限');
   const snapshot=await client.request('v1/state');const preview=await client.request('v1/operations/'+op.id+'/preview',{method:'POST',body:{input,expectedRevision:snapshot.revision}});
   const id=crypto.randomUUID();business.write(privateFile('mcp-plans',id),{format:'siyue-plan-v1',server:client.server,actorId:me.user.id,operationId:op.id,input,expectedRevision:preview.revision,previewToken:preview.previewToken,idempotencyKey:crypto.randomUUID(),expiresAt:preview.expiresAt});
   return result({operationId:op.id,preview:preview.result,planId:id,expiresAt:preview.expiresAt,commit:false,next:'核对预览后调用 siyue_execute，传入相同 planId'});
  }
  if(name.startsWith('siyue_admin_')){
   if(!me.user.roleIds.includes('superadmin'))throw auth.failure('FORBIDDEN','仅超级管理员可以管理账号与权限');
   const service=require('../cli/admin-service.cjs'),action=name.slice('siyue_admin_'.length);if(!service.operations.some(op=>op.action===action))throw auth.failure('INVALID_ARGUMENT','账号操作不存在');
   const credentials=['create','resetPassword'].includes(action),{requestId,...body}=input;
   const credentialFile=credentials?privateFile('credentials',requestId):undefined;
   if(!credentials&&requestId!==undefined)throw auth.failure('INVALID_ARGUMENT','此操作不接受 requestId');
   return result(await service.execute(action,credentials?body:input,{request:client.request,credentialFile}));
  }
  throw auth.failure('UNKNOWN_TOOL','工具不存在，请重新读取工具列表');
 }
 return {tools,call};
}
module.exports={createBridge,opName,errorResult};
