'use strict';
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
fs.mkdirSync('/data/private',{mode:0o700});
const {bootstrap}=require('/app/site/backend/src/bootstrap-auth.cjs');bootstrap('/data/private/auth.json','/data/private/initial.json');
const credentials=JSON.parse(fs.readFileSync('/data/private/initial.json'));
fs.writeFileSync('/data/preview-meta.json',JSON.stringify({hint:`隔离 CRM · 模拟账号：${credentials.username}；初始密码：${credentials.initialPassword}。首次请修改为测试密码，数据随环境回收。`}));
const backend=require('/app/site/backend/src/server.cjs').createServer({auth:{storeFile:'/data/private/auth.json',publicOrigin:'https://preview.invalid',cookiePath:'/'},business:{storeFile:'/data/private/business.sqlite',enableWorker:false}});
backend.listen(8081,'127.0.0.1');
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2'};
http.createServer((req,res)=>{
 const u=new URL(req.url,'http://localhost');
 if(u.pathname.startsWith('/crm-api/')){const upstream=http.request({host:'127.0.0.1',port:8081,path:u.pathname.slice('/crm-api'.length)+u.search,method:req.method,headers:{...req.headers,origin:'https://preview.invalid'}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)});upstream.on('error',()=>{res.writeHead(502);res.end()});req.pipe(upstream);return;}
 if(u.pathname==='/health'){http.get('http://127.0.0.1:8081/healthz',r=>{res.writeHead(r.statusCode,{'Content-Type':'application/json'});r.pipe(res)}).on('error',()=>{res.writeHead(503);res.end()});return;}
 if(u.pathname==='/deployment-config.js'){res.writeHead(200,{'Content-Type':'text/javascript'});res.end("window.SIYUE_AUTH_API = new URL('crm-api/',location.href).pathname;");return;}
 let file;try{file=path.resolve('/app/site','.'+decodeURIComponent(u.pathname==='/'?'/index.html':u.pathname));if(!file.startsWith('/app/site/')||!fs.statSync(file).isFile())throw Error()}catch{res.writeHead(404);res.end();return;}
 res.writeHead(200,{'Content-Type':types[path.extname(file)]||'application/octet-stream'});fs.createReadStream(file).pipe(res);
}).listen(8080,'127.0.0.1');
