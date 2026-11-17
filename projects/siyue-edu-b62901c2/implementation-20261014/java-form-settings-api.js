(function(root){
'use strict';
const id=value=>{if(typeof value!=='string'||!/^[A-Za-z0-9_-]{1,120}$/.test(value))throw TypeError('Invalid form identifier');return encodeURIComponent(value);};
function create({request}){
 if(typeof request!=='function')throw TypeError('Authenticated form transport is required');
 const path=(value,suffix='')=>`crm/forms/${id(value)}${suffix}`;
 const options=(value,suffix,page=1)=>{if(!Number.isSafeInteger(page)||page<1||page>100000)throw TypeError('Invalid form options page');return request('GET',path(value,suffix),{query:{page,size:50}});};
 const write=(value,suffix,body,key)=>{if(typeof key!=='string'||!/^[A-Za-z0-9_-]{16,100}$/.test(key))throw TypeError('Invalid request key');return request('PUT',path(value,suffix),{body,idempotencyKey:key});};
 return Object.freeze({form:value=>request('GET',path(value)),collection:value=>request('GET',path(value,'/collection-settings')),reward:value=>request('GET',path(value,'/reward')),trial:value=>request('GET',path(value,'/trial')),
  coupons:(value,page)=>options(value,'/reward/options',page),slots:(value,page)=>options(value,'/trial/options',page),
  saveCollection:(value,body,key)=>write(value,'/collection-settings',body,key),saveReward:(value,body,key)=>write(value,'/reward',body,key),saveTrial:(value,body,key)=>write(value,'/trial',body,key)});
}
root.siyueJavaFormSettingsApi=Object.freeze({create});
})(typeof window==='object'?window:globalThis);
