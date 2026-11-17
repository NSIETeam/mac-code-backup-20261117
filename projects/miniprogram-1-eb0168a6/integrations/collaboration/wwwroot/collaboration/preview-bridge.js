/* Runs in an opaque-origin sandbox. Storage and cookies never come from the workbench. */
(()=>{
 const cfg=__SIYUE_PREVIEW_CONFIG__,root=new URL(cfg.root),nativeFetch=window.fetch.bind(window);
 function store(){const map=new Map();return{get length(){return map.size},key:i=>[...map.keys()][i]??null,getItem:k=>map.get(String(k))??null,setItem:(k,v)=>map.set(String(k),String(v)),removeItem:k=>map.delete(String(k)),clear:()=>map.clear()};}
 for(const key of ['localStorage','sessionStorage'])Object.defineProperty(window,key,{value:store()});
 for(const [key,value] of Object.entries(cfg.storage||{}))sessionStorage.setItem(key,value);
 if(cfg.hint)document.addEventListener('DOMContentLoaded',()=>{const hint=document.createElement('aside');hint.textContent=cfg.hint;hint.style.cssText='padding:10px;background:#fff3cf;color:#493600;font:14px sans-serif';document.body.prepend(hint)});
 setInterval(()=>{if(!document.hidden)nativeFetch(root.href+'__siyue_version.json',{credentials:'omit',cache:'no-store'}).catch(()=>{})},15000);
 function rewrite(value){
  const u=new URL(String(value),location.href);
  if(u.origin!==root.origin)throw Error('测试环境不允许连接外部服务');
  if(u.pathname.startsWith(root.pathname))return u.href;
  const p=u.pathname.replace(/^\/(miniapp\/)?/,'');return root.href+p+u.search+u.hash;
 }
 window.fetch=(input,init={})=>{const request=input instanceof Request?input:null;const url=rewrite(request?request.url:input);return nativeFetch(request?new Request(url,request):url,{...init,credentials:'omit'});};
 const open=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(method,url,...rest){return open.call(this,method,rewrite(url),...rest)};
 document.addEventListener('click',ev=>{const a=ev.target.closest('a[href]');if(!a||a.getAttribute('href').startsWith('#'))return;try{a.href=rewrite(a.href);a.target='_self'}catch{ev.preventDefault()}},true);
 document.addEventListener('submit',ev=>{try{ev.target.action=rewrite(ev.target.action)}catch{ev.preventDefault()}},true);
})();
