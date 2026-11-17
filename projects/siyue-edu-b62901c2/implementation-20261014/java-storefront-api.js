/* Fixed storefront administration routes using the authenticated Java transport. */
(function(root){
'use strict';
const id=value=>{if(typeof value!=='string'||!/^[A-Za-z0-9_-]{1,120}$/.test(value))throw TypeError('Invalid storefront identifier');return encodeURIComponent(value);};
const integer=(value,max)=>{if(!Number.isSafeInteger(value)||value<1||value>max)throw TypeError('Invalid storefront page');return value;};
function create({request}){
 if(typeof request!=='function')throw TypeError('Authenticated transport is required');
 const write=(method,path,body,key)=>{if(typeof key!=='string'||!/^[A-Za-z0-9_-]{16,100}$/.test(key))throw TypeError('Invalid request key');return request(method,path,{body,idempotencyKey:key});};
 return Object.freeze({
  coupons:({page=1,pageSize=50}={})=>request('GET','commerce/coupons',{query:{page:integer(page,100000),pageSize:integer(pageSize,100)}}),
  options:({page=1,size=100}={})=>request('GET','commerce/coupons/options',{query:{page:integer(page,100000),size:integer(size,100)}}),
  createCoupon:(body,key)=>write('POST','commerce/coupons',body,key),
  updateCoupon:(value,body,key)=>write('PUT',`commerce/coupons/${id(value)}`,body,key),
  publishCoupon:(value,body,key)=>write('POST',`commerce/coupons/${id(value)}/publish`,body,key),
  unpublishCoupon:(value,body,key)=>write('POST',`commerce/coupons/${id(value)}/unpublish`,body,key),
  decor:()=>request('GET','commerce/storefront/decor'),
  saveDecor:(body,key)=>write('PUT','commerce/storefront/decor',body,key),
  publishDecor:(body,key)=>write('POST','commerce/storefront/decor/publish',body,key)
 });
}
root.siyueJavaStorefrontApi=Object.freeze({create});
})(typeof window==='object'?window:globalThis);
