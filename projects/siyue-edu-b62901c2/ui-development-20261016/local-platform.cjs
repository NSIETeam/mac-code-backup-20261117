'use strict';
// Loopback-only development gateway. Uses real production source with disposable local stores.
const fs=require('fs'),path=require('path'),http=require('http'),os=require('os');
const {once}=require('events');
(async()=>{
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'siyue-ui-local-'));fs.chmodSync(dir,0o700);
const {AuthStore}=require('./backend/src/auth-store.cjs');const {makeUser}=require('./backend/src/auth-service.cjs');
const store=new AuthStore(path.join(dir,'auth.json'));const created=makeUser({username:'local-preview',name:'本地验收',roleIds:['superadmin'],scope:{type:'all'}},[]);created.user.mustChangePassword=false;store.transact(s=>s.users.push(created.user));store.close();
const {BusinessStore}=require('./backend/src/business-store.cjs');const business=new BusinessStore(path.join(dir,'business.sqlite'));const snap=business.snapshot();const now=new Date().toISOString();
snap.state.campuses=[{id:'local-campus',name:'【模拟】中心校区',active:true}];
snap.state.leads=Array.from({length:8},(_,i)=>({id:'local-lead-'+i,name:'【模拟】学员'+(i+1),phone:'1380000000'+i,createdBy:created.user.id,registrarId:created.user.id,ownerId:created.user.id,campusId:'local-campus',status:'assigned',stage:i%2?'跟进中':'新分配',source:'本地模拟',createdAt:now,updatedAt:now}));
business.db.prepare('UPDATE business_state SET data=?,revision=revision+1 WHERE id=1').run(JSON.stringify(snap.state));business.close();
const backend=require('./backend/src/server.cjs').createServer({auth:{allowInsecureTest:true,storeFile:path.join(dir,'auth.json'),publicOrigin:'http://127.0.0.1:8772',cookiePath:'/crm-api/'},business:{storeFile:path.join(dir,'business.sqlite'),enableWorker:false}});backend.listen(0,'127.0.0.1');await once(backend,'listening');const port=backend.address().port;
const login=await fetch(`http://127.0.0.1:${port}/v1/auth/login`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:created.user.username,password:created.password,client:'cli'})}).then(r=>r.json());if(!login.token)throw Error('Local login failed');
const publicRoot=path.resolve(__dirname,'public');const server=http.createServer((req,res)=>{
 if(!['127.0.0.1:8772','localhost:8772'].includes(req.headers.host)){res.writeHead(403);return res.end();}
 const url=new URL(req.url,'http://127.0.0.1:8772');
 if(url.pathname.startsWith('/crm-api/')){
  const proxy=http.request({host:'127.0.0.1',port,path:url.pathname.slice(8)+url.search,method:req.method,headers:{...req.headers,host:'127.0.0.1:'+port,authorization:'Bearer '+login.token}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)});proxy.on('error',()=>{res.writeHead(502);res.end()});req.pipe(proxy);return;
 }
 if(url.pathname==='/'){res.writeHead(302,{Location:'/crm/'});return res.end();}
 const relative=url.pathname.replace(/^\/crm\//,'')||'index.html';const file=path.resolve(publicRoot,relative);
 if(!file.startsWith(publicRoot+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);return res.end();}
 let data=fs.readFileSync(file);if(relative==='deployment-config.js')data=Buffer.from("window.SIYUE_AUTH_API='/crm-api/';window.SIYUE_JAVA_AUTH_MODE=false;document.title='思跃 · 本地验收（模拟数据）';");
 res.writeHead(200,{'Content-Type':({'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store'});res.end(data);
});server.listen(8772,'127.0.0.1');await once(server,'listening');console.log('Local platform: http://127.0.0.1:8772/crm/ (isolated simulated data)');
process.on('SIGINT',()=>{server.close();backend.close();process.exit(0)});
})().catch(e=>{console.error(e.message);process.exit(1)});
