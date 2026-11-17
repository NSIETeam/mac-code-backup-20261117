'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function load(file){const context={};vm.runInNewContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context);return context;}
test('finance adapter calls only the explicit financial routes and keeps receipt versions',async()=>{
 const context=load('java-finance-api.js'),calls=[];
 const api=context.siyueJavaFinanceApi.create({request:async(...args)=>{calls.push(args);return {};}});
 await api.arrears({page:2,size:50,status:'pending',orderId:'ORDER_1',unexpected:'/admin'});
 assert.equal(calls[0][1],'finance/arrears');assert.equal(calls[0][2].query.page,2);assert.equal(calls[0][2].query.unexpected,undefined);
 await api.checkoutReceipt('CHECKOUT_1',{amount:'90.00',expectedVersion:2},'request_key_synthetic_01');
 assert.equal(calls[1][1],'commerce/checkouts/CHECKOUT_1/receipt');assert.equal(calls[1][2].body.expectedVersion,2);
 await api.reviewRefund('REFUND_1',{decision:'reject',expectedVersion:3},'request_key_synthetic_02');
 assert.equal(calls[2][0],'PUT');assert.equal(calls[2][1],'crm/refund-requests/REFUND_1/review');
 await api.saveSettings({accountName:'Synthetic'},'request_key_synthetic_03');assert.equal(calls[3][2].body.section,'receipts');
 assert.throws(()=>api.receive('../escape',{},'request_key_synthetic_04'));
 assert.throws(()=>api.arrears({page:0}));assert.throws(()=>api.recordRefund('REFUND_1',{},'short'));
});
test('finance module exposes all four pages and a collapsible scoped arrears list',async()=>{
 const context=load('java-finance-workspace.js');context.AbortController=AbortController;
 const module=context.siyueJavaFinance;assert.equal(module.pages.length,4);
 const workspace=module.create({api:{financeWorkspace:{arrears:async()=>({items:[],total:0,page:1,size:50})}},identity:{principalId:'STAFF_1',scope:'all',permissions:['crm.order.read']},render(){}});
 await workspace.open('financeArrears');assert.match(workspace.html(),/<details[^>]+data-filter-collapse-native/);assert.match(workspace.html(),/<details[^>]+data-jf-filters/);assert.match(workspace.html(),/当前范围暂无记录/);
 await assert.rejects(workspace.open('financeRefunds'),/权限/);assert.equal(workspace.canLeave(),true);workspace.dispose();
});


test('legacy finance readers can open their own page without unrelated native grants',async()=>{
 for(const [permission,page,method] of [['order.read','financeArrears','arrears'],['order.confirmReceipt','financeArrears','arrears'],['refund.read','financeRefunds','refunds'],['refund.approve','financeRefunds','refunds'],['refund.submit','financeRefunds','refunds']]){
  const context=load('java-finance-workspace.js');context.AbortController=AbortController;
  const workspace=context.siyueJavaFinance.create({api:{financeWorkspace:{[method]:async()=>({items:[],total:0,page:1,size:50})}},identity:{principalId:'STAFF_1',scope:'campus',permissions:[permission]},render(){}});
  await workspace.open(page);assert.match(workspace.html(),/当前范围暂无记录/);
  await assert.rejects(workspace.open(page==='financeArrears'?'financeRefunds':'financeArrears'),/权限/);
  workspace.dispose();
 }
});
