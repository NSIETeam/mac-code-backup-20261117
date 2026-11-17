'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const api=require('./java-commerce.cjs');
const key='commerce-cli-idempotency-001';
const product={courseId:'COURSE_1',name:'合成课程',classIds:['CLASS_1'],published:false};
const promotion={type:'group',name:'合成拼团',description:'说明',startDate:'2099-01-01',endDate:'2099-12-31',discountAmount:'10.25',groupSize:2,validHours:24,stackCoupon:true,participants:['在读学员'],selections:[{courseId:'COURSE_1',classIds:['CLASS_1']}],published:false};
const coupon={name:'合成券',description:'说明',template:'通用优惠',campusId:'CAMPUS_1',startDate:'2099-01-01',endDate:'2099-12-31',discountAmount:'10.25',thresholdAmount:'100.00',total:100,maxPerUser:2,courseIds:['COURSE_1']};
const content={title:'合成课程',grade:'一年级',subject:'语文',courseType:'onsite',price:'100.00',image:'https://example.com/image.png',description:'说明',expectedVersion:1};
const decor={title:'合成商城',subtitle:'说明',color:'#3979ed',modules:['hero'],blocks:{hero:{title:'介绍',body:'正文',image:'data:image/png;base64,AAAA',cta:'查看',courseIds:['COURSE_1']}}};
function input(t,value){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'siyue-commerce-cli-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));const file=path.join(dir,'input.json');fs.writeFileSync(file,JSON.stringify(value),{mode:0o600});return file;}
const identity={permissions:['commerce.read','commerce.manage','promotion.create','promotion.publish','store.design'],scope:'all'};

test('all 20 commerce descriptors invoke exact Java routes and preserve immutable retry bodies',async t=>{
 const cases=[
  ['products',[],null,'commerce/products?page=1&pageSize=50','GET'],
  ['course-classes',['COURSE_1'],null,'commerce/courses/COURSE_1/classes?page=1&pageSize=50','GET'],
  ['product-publish',['COURSE_1'],{classIds:['CLASS_1'],expectedVersion:2},'commerce/courses/COURSE_1/publish','POST'],
  ['product-unpublish',['COURSE_1'],{expectedVersion:2},'commerce/courses/COURSE_1/unpublish','POST'],
  ['product-create',[],product,'commerce/products','POST'],
  ['product-update',['PRODUCT_1'],{...product,published:true,expectedVersion:1},'commerce/products/PRODUCT_1','PUT'],
  ['promotions',[],null,'commerce/promotions?page=1&pageSize=50','GET'],
  ['promotion-create',[],promotion,'commerce/promotions','POST'],
  ['promotion-update',['PROMOTION_1'],{...promotion,published:true,expectedVersion:2},'commerce/promotions/PROMOTION_1','PUT'],
  ['content',['PRODUCT_1'],null,'commerce/storefront/products/PRODUCT_1','GET'],
  ['content-update',['PRODUCT_1'],content,'commerce/storefront/products/PRODUCT_1','PUT'],
  ['decor',[],null,'commerce/storefront/decor','GET'],
  ['decor-save',[],{decor,expectedVersion:0},'commerce/storefront/decor','PUT'],
  ['decor-publish',[],{expectedVersion:1},'commerce/storefront/decor/publish','POST'],
  ['coupons',[],null,'commerce/coupons?page=1&pageSize=50','GET'],
  ['coupon-options',[],null,'commerce/coupons/options?page=1&size=50','GET'],
  ['coupon-create',[],coupon,'commerce/coupons','POST'],
  ['coupon-update',['COUPON_1'],{...coupon,expectedVersion:1},'commerce/coupons/COUPON_1','PUT'],
  ['coupon-publish',['COUPON_1'],{expectedVersion:2},'commerce/coupons/COUPON_1/publish','POST'],
  ['coupon-unpublish',['COUPON_1'],{expectedVersion:3},'commerce/coupons/COUPON_1/unpublish','POST'],
 ];
 assert.equal(api.operations.length,cases.length);assert.equal(new Set(api.operations.map(op=>op.id)).size,cases.length);
 for(const [name,target,body,endpoint,method] of cases){let count=0;const client={request:async(actual,options)=>{count++;assert.equal(actual,endpoint);assert.deepEqual(options,{method,...(body?{body,idempotencyKey:key}:{})});return {code:'OK'};}};const args=[name,...target,...(body?['--input',input(t,body),'--key',key]:[])];await api.run(args,client,identity);assert.equal(count,1);}
});

test('coupon alternatives and storefront global scope match backend independently of commerce manage',async()=>{
 const client={request:async()=>({code:'OK'})};
 for(const permission of ['promotion.create','promotion.publish','commerce.read']){await api.run(['coupons'],client,{permissions:[permission]});assert(api.allowed(api.operations.find(op=>op.name==='coupons'),[permission]));}
 await api.run(['coupon-options'],client,{permissions:['store.design']});
 const denied=[['coupons',{permissions:['commerce.manage']}],['decor',{permissions:['store.design'],scope:'campus'}],['decor',{permissions:['commerce.manage'],scope:'all'}],['coupon-publish',{permissions:['promotion.create']}],['coupon-create',{permissions:['promotion.publish']}]];
 for(const [name,actor] of denied)await assert.rejects(api.run([name],{request:async()=>assert.fail('unauthorized network')},actor),{code:'FORBIDDEN'});
});

test('store.read is limited to the published product list in CLI preflight', async () => {
 const calls=[],client={request:async(endpoint,options)=>{calls.push({endpoint,options});return {code:'OK'};}};
 const actor={permissions:['store.read'],scope:'all'};
 await api.run(['products'],client,actor);
 assert.deepEqual(calls,[{endpoint:'commerce/products?page=1&pageSize=50',options:{method:'GET'}}]);
 for(const args of [['course-classes','COURSE_1'],['promotions'],['content','PRODUCT_1'],['product-create']])
  await assert.rejects(api.run(args,{request:async()=>assert.fail('unauthorized network')},actor),{code:'FORBIDDEN'});
});

test('read pagination and promotion filters remain fixed and bounded',async()=>{
 const calls=[],client={request:async(endpoint)=>calls.push(endpoint)};
 await api.run(['promotions','--page','2','--size','100','--type','joint'],client,identity);
 await api.run(['coupon-options','--page','3','--size','5'],client,identity);
 assert.deepEqual(calls,['commerce/promotions?page=2&pageSize=100&type=joint','commerce/coupons/options?page=3&size=5']);
 for(const args of [['products','--page','0'],['products','--size','101'],['products','--page','1','--page','2'],['products','--type','group'],['promotions','--type','other'],['content','../escape'],['decor','--path','any']])await assert.rejects(api.run(args,{request:async()=>assert.fail('invalid network')},identity));
});

test('version, DTO shape, calendar, precise amounts and promotion combinations are checked before dispatch',()=>{
 const bad=[
  ['product-create',{...product,expectedVersion:1}],['product-update',product],['product-update',{...product,expectedVersion:0}],['product-create',{...product,published:'true'}],['product-create',{...product,classIds:['CLASS_1','CLASS_1']}],
  ['promotion-create',{...promotion,discountAmount:'0.001'}],['promotion-create',{...promotion,discountAmount:1e20}],['promotion-create',{...promotion,startDate:'2099-02-29'}],['promotion-create',{...promotion,endDate:'2098-12-31'}],['promotion-create',{...promotion,groupSize:null}],['promotion-create',{...promotion,participants:['在读学员','在读学员']}],['promotion-create',{...promotion,type:'joint',groupSize:null,validHours:null}],
  ['content-update',{...content,expectedVersion:1.5}],['content-update',{...content,price:'-1'}],['content-update',{...content,image:'https://user:secret@example.com/a'}],
  ['coupon-create',{...coupon,published:true}],['coupon-create',{...coupon,discountAmount:'100.00'}],['coupon-create',{...coupon,discountAmount:'1000000000.01'}],['coupon-create',{...coupon,maxPerUser:101}],['coupon-create',{...coupon,courseIds:['COURSE_1','COURSE_1']}],['coupon-publish',{expectedVersion:0}],['coupon-publish',{expectedVersion:1,total:200}],
  ['decor-save',{expectedVersion:0}],['decor-save',{expectedVersion:0,decor:{...decor,modules:['missing']}}],['decor-save',{expectedVersion:0,decor:{...decor,blocks:{constructor:{title:'x'}}}}],['decor-publish',{expectedVersion:-1}],
 ];
 for(const [name,body] of bad)assert.throws(()=>api.validate(name,body),{code:'INVALID_COMMERCE_INPUT'},name);
 const joint={...promotion,type:'joint',groupSize:null,validHours:null,selections:[...promotion.selections,{courseId:'COURSE_2',classIds:['CLASS_2']}]};assert.equal(api.validate('promotion-create',joint),joint);
 assert.equal(api.validate('coupon-create',{...coupon,thresholdAmount:'0.00'}).thresholdAmount,'0.00');
 assert.equal(api.validate('decor-save',{decor:{title:'空页面',modules:[],blocks:{}},expectedVersion:0}).expectedVersion,0);
});

test('write arguments reject missing or malformed idempotency and do not auto-generate retry keys',async t=>{
 const file=input(t,product),client={request:async()=>assert.fail('invalid network')};
 for(const args of [['product-create','--input',file],['product-create','--input',file,'--key','short'],['product-create','--input',file,'--key',key,'--key',key],['product-create','--input',file,'--key',key,'--url','https://example.com']])await assert.rejects(api.run(args,client,identity),{code:'INVALID_ARGUMENT'});
});

 test('legacy publication grant cannot edit catalog or dispatch arbitrary product fields', async t=>{
 const actor={permissions:['product.publish'],scope:'all'};
 const calls=[];const client={request:async(endpoint,options)=>{calls.push(endpoint);return {code:'OK'};}};
 await api.run(['product-publish','COURSE_1','--input',input(t,{expectedVersion:2}),'--key',key],client,actor);
 await api.run(['product-unpublish','COURSE_1','--input',input(t,{expectedVersion:2}),'--key',key],client,actor);
 assert.equal(calls.length,2);
 for(const name of ['product-create','product-update','content-update'])
   await assert.rejects(api.run([name],client,actor),{code:'FORBIDDEN'});
 for(const body of [{expectedVersion:2,name:'overwrite'},{expectedVersion:2,classIds:[]},{expectedVersion:2,classIds:['CLASS','CLASS']},{}])
   assert.throws(()=>api.validate('product-publish',body));
 });
