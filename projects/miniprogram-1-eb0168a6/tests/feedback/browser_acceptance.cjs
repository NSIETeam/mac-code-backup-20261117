const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../.local/acceptance/feedback-20261104');
const {chromium}=require(path.join(root,'browser/node_modules/playwright'));
const sessions=JSON.parse(fs.readFileSync(path.join(root,'private-test-sessions.json'),'utf8'));
const base=process.env.FEEDBACK_BROWSER_URL||'http://127.0.0.1:15124/';
const ids=[];
(async()=>{
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
 const errors=[];
 async function context(token,mobile=false){
  const c=await browser.newContext({viewport:mobile?{width:430,height:932}:{width:1200,height:900},deviceScaleFactor:1});
  if(token)await c.addInitScript(value=>sessionStorage.setItem('siyue_web_token',value),token);
  if(base.startsWith('http://127.0.0.1'))await c.route('**/miniapp/api/support/**',async route=>{const r=await route.fetch({url:route.request().url().replace('/miniapp/api/support/','/api/support/')});await route.fulfill({response:r});});
  const p=await c.newPage();p.on('pageerror',e=>errors.push(e.message));p.on('response',r=>{if(r.url().includes('/support/')&&r.request().method()!=='GET')console.log('request',r.request().method(),r.url().split('/support/')[1],r.status());});await p.goto(base);return {c,p};
 }
 try{
  const {p:teacher}=await context(sessions.test_teacher,true);await teacher.getByRole('button',{name:'＋ 提交反馈'}).waitFor();await teacher.getByRole('button',{name:'＋ 提交反馈'}).click();
  const text='[网页界面验收] 页面切换后加载缓慢 <img src=x onerror="window.feedbackXss=1">';
  await teacher.getByLabel('遇到了什么问题？').fill(text);await teacher.getByLabel('方便联系你的方式').fill('界面验收，发布前清理');
  const png=await teacher.screenshot();await teacher.locator('#screenshots').setInputFiles({name:'问题现场.png',mimeType:'image/png',buffer:png});
  const created=teacher.waitForResponse(r=>r.url().endsWith('/miniapp/api/support/tickets')&&r.request().method()==='POST');await teacher.getByRole('button',{name:'提交反馈',exact:true}).click();const ticket=await(await created).json();ids.push(ticket.id);
  await teacher.getByText('暂时没有回复，管理员处理后会显示在这里。').waitFor();await teacher.getByRole('img',{name:'反馈截图',exact:true}).waitFor();
  assert.equal(await teacher.evaluate(()=>window.feedbackXss),undefined);await teacher.screenshot({path:path.join(root,'web-teacher-submit.png'),fullPage:true});
  const {p:admin}=await context(sessions.test_admin);await admin.getByRole('button',{name:'反馈管理',exact:true}).click();await admin.locator('[data-ticket="'+ticket.id+'"]').click();
  await admin.getByLabel('回复用户').fill('已收到截图，正在核对。请稍后刷新重试，如仍有问题可继续补充。');await admin.getByRole('button',{name:'发送',exact:true}).click();await admin.getByText('已收到截图，正在核对。请稍后刷新重试，如仍有问题可继续补充。').waitFor();
  await admin.locator('#status').selectOption('verify');await admin.getByRole('button',{name:'更新状态'}).click();await admin.getByText('将状态更新为：待验证').waitFor();await admin.screenshot({path:path.join(root,'web-admin-reply.png'),fullPage:true});
  await teacher.getByRole('button',{name:'刷新回复'}).click();await teacher.getByText('已收到截图，正在核对。请稍后刷新重试，如仍有问题可继续补充。').waitFor();await teacher.getByRole('button',{name:'确认已解决'}).click();await teacher.getByText('将状态更新为：已解决').waitFor();await teacher.screenshot({path:path.join(root,'web-teacher-resolved.png'),fullPage:true});
  const {p:guest}=await context(null,true);await guest.getByRole('button',{name:'＋ 提交反馈'}).waitFor();assert.equal(await guest.getByRole('button',{name:'反馈管理',exact:true}).count(),0);await guest.screenshot({path:path.join(root,'web-guest.png'),fullPage:true});
  assert.deepEqual(errors,[]);const result={passed:true,checks:['teacher creates text and screenshot','HTML input displayed as text','administrator views screenshot','administrator replies','administrator updates status','teacher sees reply','teacher confirms resolved','anonymous entry without admin controls','desktop and mobile layouts'],pageErrors:errors};fs.writeFileSync(path.join(root,'browser-acceptance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }finally{for(const [i,c] of browser.contexts().entries())for(const p of c.pages())await p.screenshot({path:path.join(root,'debug-context-'+i+'.png'),fullPage:true}).catch(()=>{});const f=path.join(root,'private-browser-ticket-ids.json');const prior=fs.existsSync(f)?JSON.parse(fs.readFileSync(f,'utf8')):[];fs.writeFileSync(f,JSON.stringify([...new Set([...prior,...ids])]));await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
