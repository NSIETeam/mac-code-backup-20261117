import { renderCentralResearch } from '../web/central-research.mjs';
import assert from 'node:assert/strict';
globalThis.document={querySelector:()=>null};
const summary={problemTotal:1,pending:1,courses:1};
for(const tab of ['problems','pending','courses']){
 const paths=[];let html='';
 const ui={paint:h=>{html=h},heading:()=>'',button:label=>'<button >'+label+'</button>',api:async path=>{
  paths.push(path);
  if(path.endsWith('/me'))return {canReview:true};
  if(path.endsWith('/dashboard'))return summary;
  if(tab==='pending')return {items:[{id:'s',state:'pending',fileName:'审核样例'}],total:1};
  return tab==='courses'?{courses:[{id:'c',name:'课程样例'}],dashboard:summary}:{items:[{id:'h',title:'问题样例'}],total:1,dashboard:summary};
 }};
 const result=await renderCentralResearch({tab},ui);
 assert.equal(result.total,1);assert.match(html,/样例/);assert.equal(paths.filter(p=>p.endsWith('/dashboard')).length,tab==='pending'?1:0);
}
console.log('three tabs passed');
