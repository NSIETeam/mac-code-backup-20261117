import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { SiyueAgent } from '../integrations/agent/client.mjs';
import { agentTools } from '../integrations/agent/tools.mjs';

test('SDK and tool adapter work against a real isolated HTTP process', {timeout:60000}, async()=>{
  const root=await mkdtemp(join(tmpdir(),'siyue-agent-http-'));await mkdir(join(root,'objects'));
  const developmentKey=randomUUID(),bootstrap=randomUUID();
  const processHandle=spawn(process.env.SIYUE_DOTNET??'dotnet',[resolve('server/bin/Debug/net10.0/Siyue.Server.dll'),'--urls','http://127.0.0.1:0'],{
    cwd:resolve('server'), env:{...process.env,ASPNETCORE_ENVIRONMENT:'Testing',Database__Provider:'sqlite',Database__Initialize:'true',ConnectionStrings__Database:`Data Source=${join(root,'test.db')}`,Storage__Provider:'local',Storage__Directory:join(root,'objects'),RunWorker:'false',BootstrapCode:bootstrap,DevelopmentAuthKey:developmentKey,Logging__LogLevel__Default:'Information'},stdio:['ignore','pipe','pipe']
  });
  const exited=once(processHandle,'exit');
  try {
    const base=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error('isolated server startup timed out')),20000);
      let buffer='';processHandle.stdout.on('data',chunk=>{buffer=(buffer+chunk.toString()).slice(-5000);const match=buffer.match(/Now listening on: (http:\/\/127\.0\.0\.1:\d+)/);if(match){clearTimeout(timer);resolve(match[1]);}});
      processHandle.on('error',e=>{clearTimeout(timer);reject(e);});processHandle.on('exit',()=>{clearTimeout(timer);reject(new Error('isolated server exited before startup'));});
    });
    const json=async(method,path,body,token)=>{
      const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
      assert.ok(r.ok,`${method} ${path}: HTTP ${r.status}`);return r.status===204?null:r.json();
    };
    const session=await json('POST','/api/auth/development',{openId:'external-http-admin',key:developmentKey});
    await json('POST','/api/auth/bootstrap',{code:bootstrap,name:'隔离验收'},session.token);
    const campus=await json('POST','/api/admin/catalog/campuses',{name:'隔离校区'},session.token);
    const course=await json('POST','/api/admin/catalog/courses',{name:'数学'},session.token);
    const credential=await json('POST','/api/automation/credentials',{name:'HTTP验收',scopes:['read','teaching:write'],expiresInDays:1},session.token);
    const client=new SiyueAgent({baseUrl:base,token:credential.token});const capabilities=await client.connect();assert.equal(capabilities.version,'1.0.0');
    const tools=agentTools(client);assert.ok(tools.list().some(t=>t.name==='create_class'));assert.ok(!tools.list().some(t=>t.name==='submit_print'));
    const body={name:'HTTP 接入班',campusId:campus.id,courseId:course.id,requestId:randomUUID()};
    const cl=await tools.execute('create_class',{body});const duplicate=await tools.execute('create_class',{body});assert.equal(cl.id,duplicate.id);
    const students=await tools.execute('add_students',{path:{classId:cl.id},body:{names:['测试甲','测试乙'],requestId:randomUUID()}});assert.ok(students);
    const roster=await tools.execute('students',{path:{classId:cl.id}});assert.equal(roster.length,2);
    const page=await tools.execute('list_classes',{query:{page:1,pageSize:1,query:'HTTP'}});assert.equal(page.items[0].id,cl.id);assert.equal(page.hasMore,false);
    const contract=await json('GET','/openapi/agent-v1.json');assert.equal(contract.paths['/api/class-sessions/{id}/students/{studentId}'].put.operationId,'mark_student');
    await json('DELETE',`/api/automation/credentials/${credential.id}`,undefined,session.token);
    await assert.rejects(()=>client.call('workspace'),e=>e.status===401);
  } finally {
    processHandle.kill('SIGTERM');await exited;await rm(root,{recursive:true,force:true});
  }
});
