(function(root){
'use strict';
const id=value=>{if(typeof value!=='string'||!/^[A-Za-z0-9_-]{1,120}$/.test(value))throw TypeError('Invalid application identifier');return encodeURIComponent(value);};
function create({request}){
 if(typeof request!=='function')throw TypeError('Authenticated application transport is required');
 return Object.freeze({
  list(page=1){if(!Number.isSafeInteger(page)||page<1||page>100000)throw TypeError('Invalid application page');return request('GET','commerce/checkouts',{query:{page,size:50}});},
  detail:value=>request('GET',`commerce/checkouts/${id(value)}/intake`),
  accept(value,body,key){if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{16,128}$/.test(key))throw TypeError('Invalid request key');return request('POST',`commerce/checkouts/${id(value)}/accept`,{body,idempotencyKey:key});}
 });
}
root.siyueJavaCheckoutIntakeApi=Object.freeze({create});
})(typeof window==='object'?window:globalThis);
