'use strict';
const auth=require('./auth-client.cjs');
const {readInput}=require('./account-admin.cjs');
async function run(args,{request=auth.authenticated,readSession=auth.readSession}={}){
 if(readSession().apiVersion==='v2')throw auth.failure('JAVA_V2_UNAVAILABLE','AI 试点尚未迁移至 Java，不会切回旧版会话');
 const [kind,...rest]=args;
 if(kind==='capabilities'&&!rest.length)return request('v1/ai/capabilities');
 if(!['summary','draft','query'].includes(kind)||rest.length!==2||rest[0]!=='--input')throw auth.failure('INVALID_ARGUMENT','用法：siyue-cli ai capabilities | summary|draft|query --input <JSON文件或 ->');
 return request('v1/ai/'+kind,{method:'POST',body:readInput(rest[1])});
}
module.exports={run};
