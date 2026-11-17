'use strict';
// Isolated UI acceptance fixture. Never shipped or used as an AI fallback.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../../public');
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>思跃 AI 开发验收 · 模拟响应</title><link rel="stylesheet" href="/ai-assistant.css"><style>body{font:16px system-ui;background:#f5f7fc;padding:40px;color:#263247}.btn{padding:8px 14px;border:1px solid #ccd7eb;border-radius:8px;background:white;color:#3159f5;cursor:pointer}button:disabled{opacity:.5}</style><h1>AI 开发隔离验收</h1><p>仅使用虚构客户和固定模拟模型响应，不连接生产、不调用真实模型。</p><p id="result">可以打开右下角 AI 助手进行交互检查。</p><script>
const user={id:'test-user',version:1,permissions:['lead.read','lead.follow','lead.create']};
window.workflowCurrentActor=()=>user;
window.employeeAuth={subscribe(fn){fn(user)},async request(endpoint,options){
 if(endpoint==='v1/ai/capabilities')return {enabled:true,functions:['summary','draft','query'].map(x=>({id:'ai.'+x}))};
 if(endpoint.startsWith('v1/crm/query'))return {items:[{id:'C1',name:'模拟客户甲'}],total:1};
 if(endpoint==='v1/ai/summary')return {sections:[{topic:'需求',text:'希望周末学习数学，需要确认具体时间。',sourceIds:['S1']}],sources:[{sourceId:'S1',kind:'consultations',recordId:'N1',at:'2026-10-16',text:'想安排周末数学，时间还没定。'}],truncated:false};
 if(endpoint==='v1/ai/draft')return {draft:{note:'客户希望周末学习数学，具体时间待确认。',channel:'文字',courseInterest:'数学',nextFollowAt:'',uncertainties:['请确认可上课的具体时间']},saved:false};
 if(endpoint==='v1/ai/query')return {conditions:{resource:'leads',search:'',inactiveDays:7,includeNeverFollowed:true},items:[{id:'L1',name:'模拟线索甲',status:'跟进中',resource:'leads'}],total:1};
 throw Error('未知测试请求');
}};
window.SiyueBusinessCRM={customer(id){document.getElementById('result').textContent='打开客户：'+id},openQueue(query){document.getElementById('result').textContent='打开原记录：'+query.resource+'/'+query.id}};
window.businessAPI={open(op,input){document.getElementById('result').textContent='已带入原有表单，尚未保存：'+op+'；'+input.note}};
</script><script src="/ai-assistant.js"></script></html>`;
const server=http.createServer((req,res)=>{if(req.url==='/'){res.setHeader('Content-Type','text/html;charset=utf-8');return res.end(html);}const allowed={'/ai-assistant.js':'text/javascript','/ai-assistant.css':'text/css'};if(!allowed[req.url]){res.statusCode=404;return res.end();}res.setHeader('Content-Type',allowed[req.url]);res.end(fs.readFileSync(path.join(root,req.url.slice(1))));});
server.listen(0,'127.0.0.1',()=>console.log('UI fixture http://127.0.0.1:'+server.address().port));
