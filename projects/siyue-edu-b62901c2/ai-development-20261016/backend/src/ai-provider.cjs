'use strict';
const {assert}=require('./business-common.cjs');
// No browser keys, redirects, tools, retrieval, or arbitrary model-selected URL.
function createProvider({apiKey,model,fetcher=globalThis.fetch,timeoutMs=12000}={}){
 assert(typeof apiKey==='string'&&apiKey.length>0&&typeof model==='string'&&model.length>0,'AI 尚未配置','AI_UNAVAILABLE',503);
 return async function generate({kind,instructions,input,schema}){
  let response;
  const signal=AbortSignal.timeout(timeoutMs);
  try{
   response=await fetcher('https://api.openai.com/v1/responses',{method:'POST',redirect:'error',signal,headers:{Authorization:'Bearer '+apiKey,'Content-Type':'application/json'},body:JSON.stringify({model,store:false,instructions,input:JSON.stringify(input),max_output_tokens:2500,text:{format:{type:'json_schema',name:'siyue_'+kind,strict:true,schema}}})});
   if(!response.ok)throw Error('Provider failed');
   // Bound the actual stream, including non-2xx/oversized/misconfigured gateways.
   const reader=response.body.getReader();const chunks=[];let size=0;
   for(;;){const part=await reader.read();if(part.done)break;size+=part.value.byteLength;if(size>128*1024){await reader.cancel();throw Error('Response too large');}chunks.push(Buffer.from(part.value));}
   const data=JSON.parse(Buffer.concat(chunks).toString('utf8'));
   if(data.status!=='completed')throw Error('Incomplete response');
   const content=(data.output||[]).filter(x=>x.type==='message').flatMap(x=>x.content||[]);
   if(content.some(x=>x.type==='refusal'))throw Error('Refused');
   const text=content.filter(x=>x.type==='output_text').map(x=>x.text).join('');
   return {output:JSON.parse(text),usage:{inputTokens:data.usage?.input_tokens||0,outputTokens:data.usage?.output_tokens||0}};
  }catch{throw Object.assign(Error('AI 暂时无法完成，请稍后重试；原有功能仍可使用'),{code:'AI_PROVIDER_FAILED',status:503});}
 };
}
module.exports={createProvider};
