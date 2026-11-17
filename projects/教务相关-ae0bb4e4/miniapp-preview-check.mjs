import {chromium} from '/Users/king/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import assert from 'node:assert/strict';
const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
const errors=[];
try{for(const width of [736,320]){
const p=await browser.newPage({viewport:{width,height:1000}});p.on('pageerror',e=>errors.push(e.message));
await p.goto('file:///Users/king/Documents/ChatGPT/教务相关/新版小程序-预览验收.html');const f=p.frameLocator('iframe');await f.getByRole('heading',{name:'我的班级'}).waitFor();
await p.screenshot({path:'/Users/king/Documents/ChatGPT/教务相关/miniapp-home-'+width+'.png',fullPage:true});
await f.getByRole('button',{name:'初二数学 A+班 万博校区 · 今天 16:30',exact:true}).click();await f.getByRole('heading',{name:'讲次',exact:true}).waitFor();
const frame=p.frames().find(x=>x!==p.mainFrame());assert.ok(await frame.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no horizontal overflow');
const icons=await f.locator('.sy-tools .sy-icon').evaluateAll(ns=>ns.map(n=>getComputedStyle(n).borderWidth));assert.ok(icons.every(x=>x==='0px'));
await f.getByRole('button',{name:'删除讲次 一次函数的应用',exact:true}).click();await f.getByRole('button',{name:'确认删除',exact:true}).click();assert.equal(await f.getByRole('button',{name:'删除讲次 一次函数的应用',exact:true}).count(),0);await f.getByRole('button',{name:'撤销',exact:true}).click();await f.getByRole('button',{name:'删除讲次 一次函数的应用',exact:true}).waitFor();
await f.getByRole('button',{name:'编辑讲次 一次函数',exact:true}).click();await f.getByRole('textbox',{name:'讲次标题'}).fill('一次函数复习');await f.getByRole('button',{name:'保存',exact:true}).click();
await p.screenshot({path:'/Users/king/Documents/ChatGPT/教务相关/miniapp-class-'+width+'.png',fullPage:true});
await f.getByRole('button',{name:'第6讲 · 一次函数复习 已发布 · 3 道题',exact:true}).click();await f.getByRole('button',{name:'课前校对',exact:true}).click();assert.equal(await f.getByRole('button',{name:'保存并下一题'}).isDisabled(),true);
await f.getByRole('checkbox',{name:'已核对题干、公式和图形'}).check();await f.getByRole('checkbox',{name:'题目完整，没有漏题或多框内容'}).check();await f.getByRole('button',{name:'保存并下一题'}).click();await f.getByRole('heading',{name:'练1 · 校对原题'}).waitFor();
await f.getByRole('button',{name:'批量',exact:true}).click();await f.getByRole('button',{name:'选择全部'}).click();await f.getByRole('checkbox',{name:'已核对题干、公式和图形'}).check();await f.getByRole('checkbox',{name:'题目完整，没有漏题或多框内容'}).check();await f.getByRole('button',{name:'确认 3 题并继续'}).click();
await f.getByRole('button',{name:'课堂标记',exact:true}).click();await f.getByRole('button',{name:'保存并下一位'}).click();await f.getByRole('button',{name:'保存并下一位'}).click();await f.getByRole('button',{name:'保存并预览'}).click();await f.getByRole('heading',{name:'先看一眼，再生成'}).waitFor();
await p.screenshot({path:'/Users/king/Documents/ChatGPT/教务相关/miniapp-paper-'+width+'.png',fullPage:true});
await f.getByRole('button',{name:'生成 2 份错题本'}).click();await f.getByRole('heading',{name:'错题本已就绪'}).waitFor();await f.getByRole('button',{name:'模拟批量打印'}).click();await f.getByRole('status').filter({hasText:'没有发送真实打印任务'}).waitFor();
assert.ok(await frame.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));console.log(JSON.stringify({width,editAndUndo:true,batchReview:true,studentMarking:true,previewAndGenerate:true,noOverflow:true}));await p.close();
}assert.deepEqual(errors,[]);}finally{await browser.close();}
