/** Read-only smoke/load test. Usage: node load-read.mjs /private/credential.json [requests=100] [concurrency=4] */
import { readFile } from 'node:fs/promises';
import { SiyueAgent } from './client.mjs';
const [path, rawCount='100', rawConcurrency='4'] = process.argv.slice(2);
const count=Number(rawCount),concurrency=Number(rawConcurrency);
if (!path || !Number.isInteger(count) || count<1 || count>1000 || !Number.isInteger(concurrency) || concurrency<1 || concurrency>8) throw new Error('需凭证文件；请求数 1–1000，并发 1–8');
const credential=JSON.parse(await readFile(path,'utf8'));
const client=new SiyueAgent({baseUrl:credential.baseUrl,token:credential.token,concurrency,maxRetries:0});await client.connect();
let next=0,success=0;const errors={},latency=[];const started=performance.now();
await Promise.all(Array.from({length:concurrency},async()=>{
  for (;;) {
    const i=next++;if(i>=count)return;
    const t=performance.now();
    try {await client.call('list_classes',{query:{page:1,pageSize:10}});success++;}
    catch(e){const key=e.status||e.code||'error';errors[key]=(errors[key]??0)+1;}
    finally{latency.push(performance.now()-t);}
  }
}));
latency.sort((a,b)=>a-b);const percentile=p=>Math.round(latency[Math.min(latency.length-1,Math.ceil(latency.length*p)-1)]);
console.log(JSON.stringify({requests:count,concurrency,success,errors,elapsedMs:Math.round(performance.now()-started),p50Ms:percentile(.5),p95Ms:percentile(.95),p99Ms:percentile(.99),note:'只读客户端观测；429 是保护拒绝，不能作为成功吞吐量。不代表 PDF 转换或真实打印性能。'},null,2));
