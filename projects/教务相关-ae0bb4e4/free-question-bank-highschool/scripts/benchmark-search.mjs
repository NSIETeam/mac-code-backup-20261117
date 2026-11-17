import fs from 'node:fs';
import { performance } from 'node:perf_hooks';
import { generateGeometryBank } from '../dist/geometry.mjs';
import { QuestionSearch } from '../dist/search.mjs';
const base=[...generateGeometryBank(),...JSON.parse(fs.readFileSync(new URL('../dist/data/questions.json',import.meta.url)))];
const configs=[{subject:'数学',keyword:'点线距离'},{subject:'数学',keyword:'圆 切线'},{subject:'数学',knowledgeId:'数学.analytic.conic.ellipse'},{subject:'数学',knowledgeId:'数学.solid',type:'填空题',cleanOnly:true},{subject:'数学',keyword:'数学 / 解析几何 / 椭圆'},{subject:'数学',keyword:'不存在的关键词'},{subject:'物理',keyword:'电场'},{subject:'英语',keyword:'the'}];
const reports=[];
for(const target of [base.length,50000]){
  const qs=Array.from({length:target},(_,i)=>({...base[i%base.length],id:`bench-${i}`}));
  let start=performance.now();const index=new QuestionSearch(qs);const buildMs=performance.now()-start;
  const times=[];let total=0;
  for(let round=0;round<30;round++)for(const config of configs){index.cache.clear();start=performance.now();const rows=index.query({...config,stage:'高中',cleanOnly:true}).rows;times.push(performance.now()-start);total+=rows.length;}
  times.sort((a,b)=>a-b);const report={questions:target,indexBuildMs:+buildMs.toFixed(1),coldQueries:times.length,queryP50Ms:+times[Math.floor(times.length*.5)].toFixed(3),queryP95Ms:+times[Math.floor(times.length*.95)].toFixed(3),queryMaxMs:+times.at(-1).toFixed(3),totalMatched:total};reports.push(report);console.log(JSON.stringify(report));
}
fs.mkdirSync(new URL('../.local/',import.meta.url),{recursive:true});fs.writeFileSync(new URL('../.local/search-benchmark.json',import.meta.url),JSON.stringify({environment:'Local Node.js; timings exclude network and browser rendering',reports},null,2));
