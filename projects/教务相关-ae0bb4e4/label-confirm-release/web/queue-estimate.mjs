const activeStates=new Set(['queued','processing','running','splitting','waiting','retrying']);
const seconds=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0?value:null;
const queueState=(queue,state)=>!queue?.state||queue.state==='unknown'?state:queue.state;
export function queueActive(queue,state){return activeStates.has(queueState(queue,state));}
export function queueDuration(value){
 const valid=seconds(value);if(valid===null)return '';
 const minutes=Math.max(1,Math.ceil(valid/60));
 return minutes<60?`${minutes} 分钟`:`${Math.floor(minutes/60)} 小时${minutes%60?` ${minutes%60} 分钟`:''}`;
}
export function formatQueueEstimate(queue,state){
 const current=queueState(queue,state);if(!activeStates.has(current))return '';
 if(current==='waiting')return '等待前置步骤完成，暂无法估算等待时间';
 if(current==='retrying')return '正在等待重试，暂无法估算等待时间';
 const processing=['processing','running','splitting'].includes(current);
 const ahead=Number.isSafeInteger(queue?.ahead)&&queue.ahead>=0?`前面还有 ${queue.ahead} 个任务`:'正在排队';
 if(queue?.delayed)return processing?'耗时超过预估，仍在继续处理':`${ahead} · 等待时间超过预估，仍在排队`;
 const known=queue?.basis==='observed'||queue?.basis==='initial';
 const value=known?seconds(processing?queue.remainingSeconds:queue.waitSeconds):null;
 if(value===0&&processing)return '正在完成收尾';
 if(value===0&&queue.ahead===0)return `即将开始${queue.basis==='initial'?'（初步估算）':''}`;
 const duration=value!==null?queueDuration(value):'';
 const suffix=duration&&queue.basis==='initial'?'（初步估算）':'';
 // updatedAt 是服务器本次估算时间，不代表估算过期时间。
 // 不在客户端扣秒，也不将缺失的估算改成零秒。
 if(processing)return duration?`预计还需约 ${duration}${suffix}`:'正在处理，暂无法估算剩余时间';
 return duration?`${ahead} · 预计约 ${duration}开始${suffix}`:`${ahead} · 暂无法估算开始时间`;
}
