'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const api=require('./java-checkouts.cjs');

const key='checkout-cli-idempotency-001';
function inputFile(value){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'siyue-checkout-cli-'));const file=path.join(dir,'input.json');fs.writeFileSync(file,JSON.stringify(value),{mode:0o600});return {dir,file};}

test('checkout commands map to Java-only endpoints with permission and retry boundaries',async t=>{
 const calls=[],client={request:async(endpoint,options)=>{calls.push({endpoint,options});return {code:'OK'};}};
 const permissions=['enrollment.create','crm.receipt.record','order.paymentLink'];
 const cases=[
  ['create',[],{type:'standard',buyers:[{studentId:'STUDENT_1'}],items:[{buyerIndex:0,classId:'CLASS_1',couponId:'COUPON_1'}]},'commerce/checkouts','POST'],
  ['accept',['CHECKOUT_1'],{students:[{buyerIndex:0,studentId:'STUDENT_1'}],expectedVersion:1},'commerce/checkouts/CHECKOUT_1/accept','POST'],
  ['confirm-receipt',['CHECKOUT_1'],{amount:'100.00',method:'bank',reference:'synthetic-receipt',expectedVersion:2},'commerce/checkouts/CHECKOUT_1/receipt','POST'],
 ];
 for(const [name,target,body,endpoint,method] of cases){const saved=inputFile(body);t.after(()=>fs.rmSync(saved.dir,{recursive:true,force:true}));await api.run([name,...target,'--input',saved.file,'--key',key],client,{permissions});}
 await api.run(['payment-link','CHECKOUT_2'],client,{permissions});
 assert.deepEqual(calls.map(call=>call.endpoint),cases.map(row=>row[3]).concat('commerce/checkouts/CHECKOUT_2/payment-link'));
 assert.deepEqual(calls.map(call=>call.options.method),['POST','POST','POST','POST']);
 assert(calls.slice(0,3).every(call=>call.options.idempotencyKey===key));
 assert.equal(Object.hasOwn(calls[3].options,'idempotencyKey'),false);
 assert.equal(Object.hasOwn(calls[3].options,'body'),false);
 assert.equal(calls[0].options.body.items[0].couponId,'COUPON_1');
});

test('checkout command rejects unsupported fields, invalid amount, and missing permissions',async()=>{
 assert.throws(()=>api.validate('create',{type:'standard',buyers:[{}],items:[{buyerIndex:0,classId:'CLASS_1'}],paid:true}),{code:'INVALID_CHECKOUT_INPUT'});
 assert.throws(()=>api.validate('confirm-receipt',{amount:'1.001',method:'cash',reference:'x',expectedVersion:1}),{code:'INVALID_CHECKOUT_INPUT'});
 await assert.rejects(api.run(['payment-link','CHECKOUT_1'],{request:async()=>{}},{permissions:[]}),{code:'FORBIDDEN'});
 await assert.rejects(api.run(['create','--input','missing'],{request:async()=>{}},{permissions:['enrollment.create']}),{code:'INVALID_ARGUMENT'});
});

test('commerce management does not grant checkout receipt recording',async()=>{
 await assert.rejects(api.run(['confirm-receipt','CHECKOUT_1'],{request:async()=>{throw Error('must not reach network');}},{permissions:['commerce.manage']}),{code:'FORBIDDEN'});
});

test('checkout read and intake commands enforce distinct permission alternatives and exact pagination',async()=>{
 const calls=[],client={request:async(endpoint,options)=>{calls.push({endpoint,options});return {code:'OK'};}};
 for(const permission of ['commerce.read','crm.order.read','enrollment.create','enrollment.manage','crm.receipt.record']){
  const identity={permissions:[permission]};await api.run(['list','--page','2','--size','25'],client,identity);await api.run(['detail','CHECKOUT_1'],client,identity);
  assert(api.allowed(api.operations.find(op=>op.name==='list'),identity.permissions));
 }
 for(const permission of ['enrollment.create','enrollment.manage'])await api.run(['intake','CHECKOUT_1'],client,{permissions:[permission]});
 assert.equal(calls.length,12);assert(calls.every(call=>call.options.method==='GET'&&!Object.hasOwn(call.options,'body')&&!Object.hasOwn(call.options,'idempotencyKey')));
 assert.equal(calls[0].endpoint,'commerce/checkouts?page=2&size=25');assert.equal(calls[1].endpoint,'commerce/checkouts/CHECKOUT_1');assert.equal(calls[10].endpoint,'commerce/checkouts/CHECKOUT_1/intake');
 for(const permission of ['commerce.manage','commerce.read','crm.order.read','crm.receipt.record'])await assert.rejects(api.run(['intake','CHECKOUT_1'],client,{permissions:[permission]}),{code:'FORBIDDEN'});
 for(const args of [['list','--size','101'],['list','--page','0'],['list','--page','1','--page','2'],['list','--url','https://example.com'],['detail','CHECKOUT_1','--size','2'],['intake','../escape']])await assert.rejects(api.run(args,{request:async()=>assert.fail('invalid network')},{permissions:['enrollment.manage']}),{code:'INVALID_ARGUMENT'});
 assert.equal(calls.length,12);
});


test('legacy order reader and cashier keep exact checkout privileges',async t=>{
 const calls=[],client={request:async(endpoint,options)=>{calls.push({endpoint,options});return {code:'OK'};}};
 for(const permission of ['order.read','order.confirmReceipt']){
  await api.run(['list'],client,{permissions:[permission]});
  await api.run(['detail','CHECKOUT_1'],client,{permissions:[permission]});
 }
 const saved=inputFile({amount:'100.00',method:'bank',reference:'synthetic-receipt',expectedVersion:2});
 t.after(()=>fs.rmSync(saved.dir,{recursive:true,force:true}));
 const args=['confirm-receipt','CHECKOUT_1','--input',saved.file,'--key',key];
 await api.run(args,client,{permissions:['order.confirmReceipt']});
 assert.equal(calls.length,5);
 await assert.rejects(api.run(args,client,{permissions:['order.read']}),{code:'FORBIDDEN'});
 await assert.rejects(api.run(['intake','CHECKOUT_1'],client,{permissions:['order.confirmReceipt']}),{code:'FORBIDDEN'});
 assert.equal(calls.length,5);
});
