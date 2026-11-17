const root=document.querySelector('#app'),base='/miniapp/api/support';
const labels={open:'待处理',processing:'处理中',verify:'待验证',resolved:'已解决'};
const e=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let me=null,view='mine',filter='',offset=0,items=[],more=false,busy=false,guest=false,key='',urls=[],timer;
const token=()=>guest?'':sessionStorage.getItem('siyue_web_token')||sessionStorage.getItem('siyue.question-bank.session.v1')||sessionStorage.getItem('siyue.academic-pilot.session.v1')||'';
const rid=()=>crypto.randomUUID();
const when=x=>new Date(x).toLocaleString('zh-CN',{hour12:false});
const badge=x=>`<span class="badge ${e(x)}">${labels[x]||e(x)}</span>`;
function notice(text){const el=document.querySelector('#notice');el.textContent=text;el.classList.add('visible');clearTimeout(timer);timer=setTimeout(()=>el.classList.remove('visible'),4000);}
function error(err){let box=document.querySelector('#feedback-error');if(!box){box=document.createElement('div');box.id='feedback-error';box.className='error';box.setAttribute('role','alert');root.prepend(box);}box.textContent=err.message||String(err);if(err.status===401){const b=document.createElement('button');b.textContent='通过未登录反馈继续';b.onclick=()=>{guest=true;me={admin:false,user:null};void showList();};box.append(document.createElement('br'),b);}box.scrollIntoView({block:'nearest'});}
async function work(fn){if(busy)return;busy=true;root.inert=true;root.setAttribute('aria-busy','true');root.querySelectorAll('button,input,textarea,select').forEach(x=>x.disabled=true);try{await fn();}catch(err){error(err);}finally{busy=false;root.inert=false;root.removeAttribute('aria-busy');root.querySelectorAll('button,input,textarea,select').forEach(x=>x.disabled=false);}}
async function api(path,method='GET',body){
 const requestedToken=token();let r;try{r=await fetch(base+path,{method,headers:{...(requestedToken?{Authorization:'Bearer '+requestedToken}:{}),'X-Feedback-Key':key,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body),cache:'no-store',signal:AbortSignal.timeout(30000)});}catch{throw Error('反馈连接未完成，请检查网络后重试。填写的内容仍在本页。');}
 if(requestedToken!==token()){location.reload();throw Error('账号已切换，正在刷新反馈');}const data=r.status===204?null:await r.json().catch(()=>null);if(!r.ok)throw Object.assign(Error(data?.message||(r.status===429?'操作较频繁，请稍后重试':'反馈请求未完成，请重试')),{status:r.status});return data;
}
function paint(html){urls.forEach(URL.revokeObjectURL);urls=[];root.innerHTML=html;if(busy)root.querySelectorAll('button,input,textarea,select').forEach(x=>x.disabled=true);}
function header(title='问题反馈'){return `<div class="top"><h1>${e(title)}</h1><button id="new" class="primary">＋ 提交反馈</button></div><p class="muted">${me?.user?e(me.user.name)+' · ':''}${guest||!token()?'未登录反馈记录保存在当前浏览器，请使用同一设备查看回复。':'提交后可随时查看进度、补充信息。'}</p><div class="tabs"><button data-view="mine" class="${view==='mine'?'active':''}">我的反馈</button>${me?.admin?`<button data-view="all" class="${view==='all'?'active':''}">反馈管理</button>`:''}</div>`;}
function bindHeader(){document.querySelector('#new').onclick=()=>void work(showForm);root.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>void work(async()=>{view=b.dataset.view;offset=0;await showList();}));}
async function showList(append=false){
 const data=await api('/tickets?'+new URLSearchParams({scope:view,...(filter?{status:filter}:{}),offset:String(append?offset:0)}));items=append?[...items,...data.items]:data.items;more=data.hasMore;
 paint(header(view==='all'?'反馈管理':'问题反馈')+`<div class="row filter"><span class="muted">${view==='all'?'查看所有用户的问题，回复并更新进度':'我的提交记录'}</span><select id="filter" aria-label="筛选处理状态"><option value="">全部状态</option>${Object.entries(labels).map(([v,l])=>`<option value="${v}" ${filter===v?'selected':''}>${l}</option>`).join('')}</select></div><section>${items.map(t=>`<button class="card link" data-ticket="${e(t.id)}"><div class="row"><strong class="grow">${e(t.number)}${view==='all'?' · '+e(t.name):''}</strong>${badge(t.status)}</div><p>${e(t.body.slice(0,180))}${t.body.length>180?'…':''}</p><small>更新于 ${when(t.updated)}</small></button>`).join('')||'<div class="empty">暂时没有反馈。遇到问题时，点“提交反馈”告诉我们。</div>'}</section>${more?'<button id="more">加载更多</button>':''}`);
 bindHeader();root.querySelectorAll('[data-ticket]').forEach(b=>b.onclick=()=>void work(()=>detail(b.dataset.ticket)));document.querySelector('#filter').onchange=ev=>void work(async()=>{filter=ev.target.value;offset=0;await showList();});if(more)document.querySelector('#more').onclick=()=>void work(async()=>{offset+=50;await showList(true);});
}
function context(){let value={};try{value=JSON.parse(sessionStorage.getItem('siyue_feedback_context')||'{}');}catch{}return {...value,platform:'网页',version:value.version||'web-20261104'};}
async function upload(ticket,files){for(const file of files){const form=new FormData();form.append('file',file);await api('/tickets/'+ticket+'/images','POST',form);}}
function validateFiles(files){if(files.length>3)throw Error('每条反馈最多3张截图');if(files.some(f=>f.size>3*1024*1024))throw Error('每张截图不超过3MB');if(files.some(f=>!['image/png','image/jpeg','image/webp'].includes(f.type)))throw Error('截图只支持PNG、JPEG或WebP图片');}
async function showForm(){
 const ctx=context();const draftKey='siyue_feedback_draft:'+ (me?.user?.id||'guest');let draft={};try{draft=JSON.parse(sessionStorage.getItem(draftKey)||'{}');}catch{}
 paint(`<div class="top"><h1>提交问题反馈</h1><button id="back">返回</button></div><p class="muted">哪里不对？简单说一句，也可以附上截图。</p><form id="submit"><label for="body">遇到了什么问题？</label><textarea id="body" name="body" maxlength="2000" required placeholder="例如：选择班级后一直转圈，无法打开。">${e(draft.body||'')}</textarea><div class="counter muted"><span id="count">${(draft.body||'').length}</span> / 2000</div><label for="screenshots">截图（选填，最多3张，每张3MB）</label><input id="screenshots" type="file" accept="image/png,image/jpeg,image/webp" multiple><label for="contact">方便联系你的方式（选填）</label><input id="contact" name="contact" maxlength="120" value="${e(draft.contact||'')}" placeholder="如：姓名、校区或联系电话"><p class="muted">已记录问题所在页面${ctx.page?'：'+e(ctx.page):''}${ctx.traceId?'，错误编号：'+e(ctx.traceId):''}。</p><div class="actions"><button class="primary" type="submit">提交反馈</button><button id="cancel" type="button">取消</button></div></form>`);
 const body=document.querySelector('#body'),contact=document.querySelector('#contact');function save(){sessionStorage.setItem(draftKey,JSON.stringify({body:body.value,contact:contact.value}));document.querySelector('#count').textContent=body.value.length;}body.oninput=save;contact.oninput=save;
 document.querySelector('#back').onclick=document.querySelector('#cancel').onclick=()=>void work(showList);
 let request=rid(),submitted=null,pending=null;
 document.querySelector('#submit').onsubmit=ev=>{ev.preventDefault();const files=[...document.querySelector('#screenshots').files];const data={requestId:request,body:body.value,contact:contact.value,context:ctx};void work(async()=>{
  validateFiles(files);if(!data.body.trim())throw Error('请描述遇到的问题');
  if(!submitted){pending??=data;submitted=await api('/tickets','POST',pending);}
  try{await upload(submitted.id,files);}catch(err){document.querySelector('#body').disabled=true;throw Error('文字反馈已保存（'+submitted.number+'），截图尚未传完。请点击提交重试，或返回“我的反馈”补充。'+err.message);}
  sessionStorage.removeItem(draftKey);notice('反馈已提交：'+submitted.number);await detail(submitted.id);
 });};body.focus();
}
async function detail(id){
 const d=await api('/tickets/'+id),t=d.ticket;paint(`<div class="top"><button id="back">← 返回列表</button><strong>${e(t.number)}</strong>${badge(t.status)}</div><article class="card"><div class="row"><strong class="grow">${e(t.name)}</strong><small>${when(t.created)}</small></div><p class="body">${e(t.body)}</p><div id="images" class="images"></div>${t.contact?`<p class="muted">联系信息：${e(t.contact)}</p>`:''}<details><summary class="muted">问题发生时的信息</summary><pre>${e(Object.entries(t.context||{}).map(([k,v])=>({page:'所在页面',version:'版本',platform:'使用端',scene:'场景',error:'错误信息',traceId:'错误编号',errorPath:'出错功能',status:'错误状态'}[k]||k)+'：'+v).join('\n'))}</pre></details></article>${me?.admin?`<section class="card"><label for="status">处理进度</label><div class="row"><select id="status">${Object.entries(labels).map(([v,l])=>`<option value="${v}" ${t.status===v?'selected':''}>${l}</option>`).join('')}</select><button id="update">更新状态</button></div></section>`:`<div class="actions">${t.status!=='resolved'?'<button id="resolve">确认已解决</button>':'<button id="reopen">仍有问题，重新打开</button>'}</div>`}<section class="card"><h2>沟通记录</h2>${d.replies.map(r=>`<div class="reply ${r.admin?'admin':''}"><div class="row"><strong class="grow">${r.admin?'管理员 · ':''}${e(r.author)}</strong><small>${when(r.created)}</small></div><p>${e(r.body)}</p></div>`).join('')||'<p class="muted">暂时没有回复，管理员处理后会显示在这里。</p>'}<form id="reply"><label for="reply-body">${me?.admin?'回复用户':'补充说明'}</label><textarea id="reply-body" maxlength="2000" required placeholder="${me?.admin?'说明处理情况或需要用户补充的信息':'补充遇到的情况，或回复管理员'}"></textarea><div class="actions"><button class="primary" type="submit">发送</button><button id="refresh" type="button">刷新回复</button></div></form>${d.images.length<3?'<label for="more-images">补充截图</label><input id="more-images" type="file" accept="image/png,image/jpeg,image/webp" multiple>':''}</section>`);
 document.querySelector('#back').onclick=()=>void work(showList);document.querySelector('#refresh').onclick=()=>void work(()=>detail(id));
 const update=document.querySelector('#update');if(update)update.onclick=()=>void work(async()=>{await api('/tickets/'+id+'/status','PUT',{status:document.querySelector('#status').value});await detail(id);});
 for(const [button,status] of [['resolve','resolved'],['reopen','open']]){const b=document.querySelector('#'+button);if(b)b.onclick=()=>void work(async()=>{await api('/tickets/'+id+'/status','PUT',{status});await detail(id);});}
 const reply=document.querySelector('#reply-body');let request=rid(),pending=null;
 document.querySelector('#reply').onsubmit=ev=>{ev.preventDefault();void work(async()=>{pending??={body:reply.value,requestId:request};await api('/tickets/'+id+'/replies','POST',pending);await detail(id);notice('回复已发送');});};
 const add=document.querySelector('#more-images');if(add)add.onchange=()=>void work(async()=>{const files=[...add.files];validateFiles(files);if(files.length+d.images.length>3)throw Error('每条反馈最多3张截图');await upload(id,files);await detail(id);});
 for(const img of d.images){const r=await fetch(base+'/tickets/'+id+'/images/'+img.id,{headers:{...(token()?{Authorization:'Bearer '+token()}:{}),'X-Feedback-Key':key},cache:'no-store'});if(!r.ok)continue;const url=URL.createObjectURL(await r.blob());urls.push(url);const b=document.createElement('button'),image=document.createElement('img');image.src=url;image.alt='反馈截图';b.append(image);b.onclick=()=>{const overlay=document.createElement('div');overlay.className='preview';const full=document.createElement('img');full.src=url;full.alt='反馈截图大图';const close=document.createElement('button');close.textContent='关闭图片';close.onclick=()=>overlay.remove();overlay.append(full,close);document.body.append(overlay);close.focus();};document.querySelector('#images').append(b);}
}
const returnLink=document.querySelector('#return-link');
if(window.parent!==window){
 returnLink.textContent='← 返回原页面';
 returnLink.addEventListener('click',event=>{event.preventDefault();window.parent.postMessage({type:'siyue-feedback-close'},location.origin);});
}else{
 let candidates=[document.referrer];
 try{const c=JSON.parse(sessionStorage.getItem('siyue_feedback_context')||'{}');if(c.page)candidates.push(c.page+(c.scene||''));}catch{}
 for(const candidate of candidates){
  if(!candidate)continue;
  try{const url=new URL(candidate,location.origin);if(url.origin===location.origin&&['/miniapp/workbench/','/miniapp/question-bank/','/miniapp/academic-pilot/','/miniapp/academic-lab/','/work/','/crm/'].some(path=>url.pathname.startsWith(path))){returnLink.href=url.href;returnLink.textContent='← 返回原页面';break;}}catch{}
 }
}

try{
 key=localStorage.getItem('siyue_feedback_key')||'';if(!/^[a-f0-9]{64}$/.test(key)){key=(await api('/identity','POST',{})).key;localStorage.setItem('siyue_feedback_key',key);}
 me=await api('/me');if(new URLSearchParams(location.search).get('new')==='1')await showForm();else await showList();
}catch(err){paint('<h1>问题反馈</h1><p>暂时无法打开反馈记录。</p><button id="retry">重试</button>');document.querySelector('#retry').onclick=()=>location.reload();error(err);}

window.addEventListener('storage',event=>{if(['siyue_web_token','siyue.academic-pilot.session.v1'].includes(event.key))location.reload();});

