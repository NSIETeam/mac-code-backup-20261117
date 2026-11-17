import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import net from 'node:net';
test('页面具有移动视口和完整协作流程',()=>{const html=readFileSync(new URL('./site/index.html',import.meta.url),'utf8');assert.match(html,/name="viewport"/);for(const text of ['本地开发','测试验收','管理员发布'])assert.ok(html.includes(text));});
test('构建环境不挂载生产配置与容器管理接口',{skip:process.env.SIYUE_BUILD_SANDBOX!=='1'},()=>{for(const path of ['/config/settings.json','/opt/siyue-collaboration/secrets.json','/var/run/docker.sock','/app/appsettings.Production.json'])assert.equal(existsSync(path),false);});
test('构建无法连接宿主机服务',{skip:process.env.SIYUE_BUILD_SANDBOX!=='1'},async()=>{await new Promise((resolve,reject)=>{const s=net.connect({host:'127.0.0.1',port:5301});s.once('connect',()=>{s.destroy();reject(Error('host network exposed'));});s.once('error',()=>resolve());s.setTimeout(800,()=>{s.destroy();resolve();});});});
