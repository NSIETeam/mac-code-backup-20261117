// Avoid AbortSignal.timeout: older browser engines can support fetch without it.
export async function fetchWithDeadline(url,options,timeoutMs){
 const controller=typeof AbortController==='function'?new AbortController():null;
 let expired=false;
 const timer=controller?setTimeout(()=>{expired=true;controller.abort();},timeoutMs):null;
 try{return await fetch(url,{...options,...(controller?{signal:controller.signal}:{})});}
 catch(error){
  const message=expired?'请求超时，服务器响应较慢，请稍后重试。已提交的打印任务请先查看打印记录。':typeof navigator!=='undefined'&&navigator.onLine===false?'当前设备未连接网络，请恢复网络后重试。已提交的打印任务请先查看打印记录。':'无法连接服务器，请检查网络后重试。已提交的打印任务请先查看打印记录。';
  throw Object.assign(new Error(message),{connectionKind:expired?'timeout':'network'});
 }finally{if(timer!==null)clearTimeout(timer);}
}
