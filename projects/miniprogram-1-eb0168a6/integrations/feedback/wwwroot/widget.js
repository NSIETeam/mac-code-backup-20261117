(()=>{
 if(window!==window.top||document.querySelector('#siyue-feedback-button'))return;
 const source=document.currentScript?.src||location.origin+'/miniapp/support/widget.js',base=new URL('./',source);
 const style=document.createElement('link');style.rel='stylesheet';style.href=new URL('widget.css?v=20261104-1',base);document.head.append(style);
 const button=document.createElement('button');button.id='siyue-feedback-button';button.type='button';button.textContent='问题反馈';button.setAttribute('aria-label','提交问题或查看反馈回复');document.body.append(button);
 let lastError={};const nativeFetch=window.fetch;
 window.fetch=async function(...args){const result=await nativeFetch.apply(this,args);try{const url=new URL(typeof args[0]==='string'?args[0]:args[0]?.url||String(args[0]),location.href);if(!result.ok&&url.origin===location.origin&&url.pathname.startsWith('/miniapp/api/')&&!url.pathname.startsWith('/miniapp/api/support')){lastError={status:String(result.status),errorPath:url.pathname};const size=Number(result.headers.get('content-length')||0);if(size<16000&&result.headers.get('content-type')?.includes('json'))void result.clone().json().then(data=>{lastError={...lastError,error:String(data.message||'').slice(0,500),traceId:String(data.traceId||result.headers.get('X-Request-Id')||'').slice(0,200)};}).catch(()=>{});}}catch{}return result;};
 button.onclick=()=>{
  if(document.querySelector('#siyue-feedback-panel'))return;
  sessionStorage.setItem('siyue_feedback_context',JSON.stringify({page:location.pathname,scene:location.hash.split('?')[0].slice(0,120),version:'web-20261104',...lastError}));
  const panel=document.createElement('section');panel.id='siyue-feedback-panel';panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-label','问题反馈');
  const close=document.createElement('button');close.type='button';close.className='siyue-feedback-close';close.textContent='关闭反馈 ×';const onReturn=event=>{if(event.origin===location.origin&&event.source===iframe.contentWindow&&event.data?.type==='siyue-feedback-close')close.click();};close.onclick=()=>{window.removeEventListener('message',onReturn);panel.remove();button.focus();};
  const iframe=document.createElement('iframe');iframe.src=base.href;iframe.title='问题反馈与处理进度';panel.append(close,iframe);document.body.append(panel);window.addEventListener('message',onReturn);close.focus();
  panel.addEventListener('keydown',ev=>{if(ev.key==='Escape')close.click();});
 };
})();
