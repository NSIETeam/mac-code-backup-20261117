'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path'),crypto=require('node:crypto');
function load(file){const context={AbortController,crypto,FormData:class extends Map{constructor(form){super(Object.entries(form.values));}}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context);return context;}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('form settings adapter uses fixed scoped form routes',async()=>{
 const context=load('java-form-settings-api.js'),calls=[],api=context.siyueJavaFormSettingsApi.create({request:async(...args)=>{calls.push(args);return {};}});
 await api.form('FORM_1');await api.collection('FORM_1');await api.coupons('FORM_1',2);await api.slots('FORM_1',1);
 await api.saveTrial('FORM_1',{trial:null,expectedVersion:4},'synthetic-form-request01');
 assert.equal(calls[0][1],'crm/forms/FORM_1');assert.equal(calls[1][1],'crm/forms/FORM_1/collection-settings');assert.equal(calls[2][1],'crm/forms/FORM_1/reward/options');assert.equal(calls[2][2].query.page,2);
 assert.equal(calls[3][1],'crm/forms/FORM_1/trial/options');assert.equal(calls[4][2].body.expectedVersion,4);assert.throws(()=>api.form('../bad'));assert.throws(()=>api.slots('FORM_1',0));
});
test('settings re-read latest version, preserve unknown writes and block navigation until retry resolves',async()=>{
 const context=load('java-form-settings.js'),writes=[],listeners={};let version=7,attempts=0;
 const service={form:async()=>({id:'FORM_1',title:'合成表单',status:'draft',version,fields:[]}),collection:async()=>({start:null,end:null,quantity:1000,maxPerIdentity:1,source:'合成渠道',ownerId:null}),reward:async()=>null,trial:async()=>null,saveCollection:async(id,body,key)=>{writes.push(JSON.parse(JSON.stringify({id,body,key})));attempts++;if(attempts===1)throw Error('连接中断');version=8;return {id,version};}};
 const editor=context.siyueJavaFormSettings.create({api:{formSettingsWorkspace:service},identity:{permissions:['crm.form.read','crm.form.write']},render(){}});
 await editor.open({id:'FORM_1',version:1});assert.equal(editor.isOpen(),true);assert.match(editor.html(),/收集设置/);
 const container={addEventListener:(name,handler)=>{listeners[name]=handler;},contains:()=>true};editor.bind(container);
 const form={dataset:{jfsForm:'collection'},values:{start:'',end:'',quantity:'3',maxPerIdentity:'1',source:'合成渠道'}};
 listeners.submit({target:{closest:()=>form},preventDefault(){}});assert.equal(editor.canLeave(),false);assert.match(editor.html(),/当前版本 7/);
 const click=action=>listeners.click({target:{closest:()=>({dataset:{jfsAction:action},disabled:false})},preventDefault(){}});
 click('commit');await flush();await flush();assert.equal(editor.close(),false);assert.match(editor.html(),/原请求重试/);click('back');assert.match(editor.html(),/确认保存/);
 click('commit');await flush();await flush();assert.equal(writes.length,2);assert.deepEqual(writes[0],writes[1]);assert.equal(writes[0].body.expectedVersion,7);assert.equal(writes[0].body.settings.quantity,3);
 assert.equal(editor.canLeave(),true);assert.equal(editor.close(),true);assert.equal(editor.isOpen(),false);editor.dispose();
});
