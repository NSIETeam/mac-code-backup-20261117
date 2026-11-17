'use strict';
const string = (maxLength=2000) => ({type:'string',maxLength});
const object = properties => ({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
const id={type:'string',minLength:1,maxLength:120,pattern:'^[A-Za-z0-9_-]+$'};
const inputs={
 summary:object({customerId:id}),
 draft:object({customerId:id,note:{type:'string',minLength:1,maxLength:5000}}),
 query:object({question:{type:'string',minLength:1,maxLength:1000}})
};
const outputs={
 summary:object({sections:{type:'array',maxItems:12,items:object({topic:{type:'string',enum:['需求','进展','待办','待确认']},text:string(800),sourceIds:{type:'array',minItems:1,maxItems:8,items:string(20)}})}}),
 draft:object({note:string(5000),channel:{type:'string',enum:['电话','企微','文字','未记录']},courseInterest:string(200),nextFollowAt:string(40),uncertainties:{type:'array',maxItems:10,items:string(200)}}),
 query:object({supported:{type:'boolean'},reason:string(300),resource:{type:'string',enum:['customers','leads','opportunities']},search:string(100),inactiveDays:{type:'integer',minimum:0,maximum:365},includeNeverFollowed:{type:'boolean'}})
};
const meta={revision:{type:'integer'},generatedAt:string(40),aiGenerated:{type:'boolean'}};
const responseSchemas={
 summary:object({...outputs.summary.properties,sources:{type:'array',maxItems:30,items:object({kind:{type:'string',enum:['customers','consultations','leads','opportunities']},recordId:id,at:string(40),text:string(1200),sourceId:string(20)})},truncated:{type:'boolean'},customerId:id,...meta}),
 draft:object({draft:outputs.draft,customerId:id,saved:{type:'boolean',const:false},requiresReview:{type:'boolean',const:true},...meta}),
 query:{oneOf:[object({needsClarification:{type:'boolean',const:true},reason:string(300),items:{type:'array',maxItems:0},...meta}),object({conditions:object({...outputs.query.properties,scope:{type:'string',const:'mine'},timeBasis:{type:'string',const:'lastFollowAt'},evaluatedAt:string(40)}),total:{type:'integer'},truncated:{type:'boolean'},items:{type:'array',maxItems:50,items:object({id,name:string(500),status:string(100),lastFollowAt:{type:['string','null']},resource:{type:'string',enum:['customers','leads','opportunities']}})},...meta})]}
};
const functions=Object.keys(inputs).map(name=>({id:'ai.'+name,label:({summary:'客户资料摘要',draft:'沟通草稿',query:'自然语言只读查询'})[name],method:'POST',path:'/v1/ai/'+name,inputSchema:inputs[name],responseSchema:responseSchemas[name],modelOutputSchema:outputs[name],readOnly:true,sideEffects:['调用模型','记录不含正文的调用审计'],authorization:'试点账号 + lead.read；draft 另需 lead.follow；记录范围由服务端校验',cli:'ai '+name+' --input <JSON文件>',status:'pilot-default-off'}));
// Existing business validation treats empty strings as missing. AI schemas use
// explicit empty strings for unknown fields, while still requiring every key.
function validateOutput(value,schema){
 const {assert}=require('./business-common.cjs');
 function walk(v,s){
  if(s.type==='object'){
   assert(v&&typeof v==='object'&&!Array.isArray(v),'AI 对象无效');
   for(const key of s.required||[])assert(Object.hasOwn(v,key),'AI 缺少字段');
   assert(Object.keys(v).every(k=>Object.hasOwn(s.properties,k)),'AI 包含额外字段');
   for(const [k,x]of Object.entries(v))walk(x,s.properties[k]);
  }else if(s.type==='array'){
   assert(Array.isArray(v)&&v.length>=(s.minItems||0)&&v.length<=(s.maxItems||500),'AI 列表无效');v.forEach(x=>walk(x,s.items));
  }else require('./business-store.cjs').validate(v,s);
 }
 walk(value,schema);
}
module.exports={inputs,outputs,functions,validateOutput,responseSchemas};
