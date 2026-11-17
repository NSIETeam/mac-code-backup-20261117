'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function load(file){const context={AbortController};vm.runInNewContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context);return context;}
test('storefront fixed route adapter keeps optimistic versions and idempotency',async()=>{
 const context=load('java-storefront-api.js'),calls=[],api=context.siyueJavaStorefrontApi.create({request:async(...args)=>{calls.push(args);return {};}});
 await api.coupons({page:2,pageSize:50,arbitrary:'ignored'});assert.deepEqual(JSON.parse(JSON.stringify(calls[0][2].query)),{page:2,pageSize:50});
 await api.options();assert.equal(calls[1][1],'commerce/coupons/options');
 await api.publishCoupon('COUPON_1',{expectedVersion:3},'synthetic-request-key-0001');assert.equal(calls[2][1],'commerce/coupons/COUPON_1/publish');assert.equal(calls[2][2].body.expectedVersion,3);
 await api.saveDecor({decor:{title:'Synthetic'},expectedVersion:0},'synthetic-request-key-0002');assert.equal(calls[3][0],'PUT');assert.equal(calls[3][1],'commerce/storefront/decor');
 await api.publishDecor({expectedVersion:1},'synthetic-request-key-0003');assert.equal(calls[4][1],'commerce/storefront/decor/publish');
 assert.throws(()=>api.updateCoupon('../bad',{},'synthetic-request-key-0004'));assert.throws(()=>api.coupons({page:0}));assert.throws(()=>api.saveDecor({},'short'));
});
test('storefront pages permit published readers and empty initial decoration',async()=>{
 const context=load('java-storefront-workspace.js'),module=context.siyueJavaStorefront;
 const service={coupons:async()=>({items:[],total:0,page:1,pageSize:50}),options:async()=>({courses:[],campuses:[],total:0,page:1,size:100}),decor:async()=>({decor:{},draft:null,version:0,publishedVersion:null})};
 const reader=module.create({api:{storefrontWorkspace:service},identity:{permissions:['commerce.read'],scope:'all'},render(){}});
 assert.equal(module.pages.length,2);await reader.open('mallCoupon');assert.match(reader.html(),/当前范围暂无优惠券/);assert.match(reader.html(),/<details[^>]+data-filter-collapse-native/);assert.match(reader.html(),/<details[^>]+data-js-filter/);assert.doesNotMatch(reader.html(),/data-js-action="new-coupon"/);await assert.rejects(reader.open('mallDecor'),/权限/);reader.dispose();
 const designer=module.create({api:{storefrontWorkspace:service},identity:{permissions:['store.design'],scope:'all'},render(){}});
 await designer.open('mallDecor');assert.match(designer.html(),/尚未发布/);assert.match(designer.html(),/decor-layout/);assert.equal(designer.canLeave(),true);designer.dispose();
});
