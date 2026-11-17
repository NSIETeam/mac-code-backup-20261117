/* Explicit financial routes through the shared authenticated Java transport. */
(function(root){
  'use strict';
  const segment=value=>{if(typeof value!=='string'||! /^[A-Za-z0-9_-]{1,120}$/.test(value))throw new TypeError('Invalid finance identifier');return encodeURIComponent(value);};
  const query=value=>{
    const clean={page:value?.page??1,size:value?.size??50};
    if(!Number.isSafeInteger(clean.page)||clean.page<1||clean.page>100000||!Number.isSafeInteger(clean.size)||clean.size<1||clean.size>100)throw new TypeError('Invalid finance page');
    for(const key of ['status','orderId','fromAt','untilAt'])if(value?.[key])clean[key]=String(value[key]);return clean;
  };
  function create({request}){
    if(typeof request!=='function')throw new TypeError('Finance API requires authenticated transport');
    const write=(method,path,body,key)=>{if(typeof key!=='string'||! /^[A-Za-z0-9_-]{16,100}$/.test(key))throw new TypeError('Invalid request key');return request(method,path,{body,idempotencyKey:key});};
    return Object.freeze({
      arrears:values=>request('GET','finance/arrears',{query:query(values)}),
      refunds:values=>request('GET','finance/refunds',{query:query(values)}),
      consumptions:values=>request('GET','finance/consumptions',{query:query(values)}),
      orders:values=>request('GET','crm/orders',{query:query(values)}),
      requestRefund:(id,body,key)=>write('POST',`crm/orders/${segment(id)}/refund-requests`,body,key),
      reviewRefund:(id,body,key)=>write('PUT',`crm/refund-requests/${segment(id)}/review`,body,key),
      recordRefund:(id,body,key)=>write('POST',`crm/refund-requests/${segment(id)}/refunds`,body,key),
      refundEvents:id=>request('GET',`crm/refund-requests/${segment(id)}/events`),
      refundLedger:(id,values)=>request('GET',`crm/orders/${segment(id)}/refunds`,{query:query(values)}),
      receive:(id,body,key)=>write('POST',`crm/orders/${segment(id)}/receipts`,body,key),
      receipts:id=>request('GET',`crm/orders/${segment(id)}/receipts`),
      checkout:id=>request('GET',`commerce/checkouts/${segment(id)}`),
      checkoutReceipt:(id,body,key)=>write('POST',`commerce/checkouts/${segment(id)}/receipt`,body,key),
      settings:()=>request('GET','settings'),
      saveSettings:(values,key)=>write('PUT','settings',{section:'receipts',values},key)
    });
  }
  root.siyueJavaFinanceApi=Object.freeze({create});
})(typeof window==='object'?window:globalThis);
