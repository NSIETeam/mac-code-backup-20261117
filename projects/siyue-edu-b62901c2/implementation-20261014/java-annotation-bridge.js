/* Annotation-only adapter: Java OIDC/MySQL, never a legacy authentication fallback. */
(function(root){
 'use strict';
 function create({identity,api,randomId=()=>root.crypto.randomUUID()}){
  const versions=new Map(),pending=new Map();let owner=null;
  const current=()=>{const actor=identity();if(actor?.principalId!==owner){versions.clear();pending.clear();owner=actor?.principalId||null}return actor};
  const isSuperadmin=()=>{const actor=current();return actor?.scope==='all'&&actor.permissions?.includes('system.access.manage')};
  const flatten=row=>{versions.set(row.id,row.version);return {...row.request,id:row.id,status:row.status,version:row.version,createdBy:row.createdBy,createdByName:row.createdByName||row.createdBy,createdAt:row.createdAt,updatedAt:row.updatedAt}};
  async function request(path,options={}){
   if(!isSuperadmin())throw new Error('当前账号无全局管理员权限');
   const principal=owner,client=api(),method=options.method||'GET';
   if(!client)throw new Error('Java会话尚未就绪');
   const valid=()=>{if(current()?.principalId!==principal||!isSuperadmin())throw new Error('管理员会话已变化，请重新打开清单')};
   if(path==='v1/admin/ui-annotations'&&method==='GET'){
    const rows=[];
    for(const status of ['open','resolved'])for(let page=1;page<=1000;page++){
     const result=await client.list({status,page,size:100});valid();
     if(!Array.isArray(result.annotations)||!Number.isSafeInteger(result.total)||result.total<0)throw new Error('清单响应格式无效');
     rows.push(...result.annotations.map(flatten));
     if(page*100>=result.total)break;
     if(!result.annotations.length||page===1000)throw new Error('清单过大或分页发生变化，请重新加载');
    }
    return {annotations:rows};
   }
   const match=path.match(/^v1\/admin\/ui-annotations\/([A-Za-z0-9_-]{1,120})$/);
   let body=options.body;
   if(method==='PATCH'&&match){
    const version=versions.get(match[1]);if(!Number.isSafeInteger(version))throw new Error('请先刷新清单再修改');
    body={status:body.status,expectedVersion:version,verified:body.verified===true};
   }else if(!(method==='POST'&&path==='v1/admin/ui-annotations'))throw new Error('不支持的管理员接口');
   const fingerprint=JSON.stringify([principal,path,method,body]);
   if(!pending.has(fingerprint)){if(pending.size>=100)throw new Error('待确认请求过多，请先确认已有提交');pending.set(fingerprint,randomId())}
   try{
    const key=pending.get(fingerprint),result=method==='POST'?await client.create(body,key):await client.update(match[1],body,key);
    valid();pending.delete(fingerprint);return {annotation:flatten(result)};
   }catch(error){if(error.status>=400&&error.status<500)pending.delete(fingerprint);throw error}
  }
  return Object.freeze({isSuperadmin,request});
 }
 root.SiyueJavaAnnotationBridge={create};
 if(root.SIYUE_JAVA_AUTH_MODE)root.siyueJavaAnnotations=create({identity:()=>root.SIYUE_JAVA_CRM?.getIdentity(),api:()=>root.SIYUE_JAVA_CRM?.getAnnotationApi()});
})(typeof window==='object'?window:globalThis);
