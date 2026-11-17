import {settings,token} from './api'
export interface FeedbackTicket{id:string;number:string;name:string;body:string;contact:string;context:Record<string,string>;status:string;created:string;updated:string}
export interface FeedbackDetail{ticket:FeedbackTicket;replies:{id:string;author:string;admin:boolean;body:string;created:string}[];images:{id:string;path?:string}[]}
let initializing:Promise<string>|null=null
export async function feedbackKey():Promise<string>{
 const saved=String(wx.getStorageSync('siyue_feedback_key')||'');if(/^[a-f0-9]{64}$/.test(saved))return saved
 if(!initializing)initializing=new Promise<string>((resolve,reject)=>wx.request({url:settings.baseUrl+'/api/support/identity',method:'POST',data:{},success:r=>{const data=r.data as {key?:string};if(r.statusCode===200&&data.key){wx.setStorageSync('siyue_feedback_key',data.key);resolve(data.key)}else reject(Error('暂时无法打开反馈，请稍后重试'))},fail:()=>reject(Error('无法连接反馈服务，请检查网络'))})).finally(()=>{initializing=null})
 return initializing
}
async function headers(anonymous=false):Promise<Record<string,string>>{return {'X-Feedback-Key':await feedbackKey(),...(token()&&!anonymous?{Authorization:'Bearer '+token()}:{}),'Content-Type':'application/json'}}
export async function feedbackApi<T>(path:string,method:'GET'|'POST'|'PUT'='GET',data?:unknown,anonymous=false):Promise<T>{
 const identity=token();const header=await headers(anonymous);if(!anonymous&&token()!==identity)throw Error('账号已切换，请重新打开反馈')
 return new Promise<T>((resolve,reject)=>wx.request({url:settings.baseUrl+'/api/support'+path,method,data:data as WechatMiniprogram.IAnyObject,header,timeout:30000,success:r=>{if(!anonymous&&token()!==identity){reject(Error('账号已切换，请重新打开反馈'));return}if(r.statusCode>=200&&r.statusCode<300)resolve(r.data as T);else reject(Object.assign(Error((r.data as {message?:string})?.message||'反馈请求未完成，请重试'),{status:r.statusCode}))},fail:()=>reject(Error('反馈连接中断，填写的内容已保留，请重试'))}))
}
export async function feedbackUpload(id:string,path:string,anonymous=false):Promise<void>{
 const identity=token();const header=await headers(anonymous);if(!anonymous&&token()!==identity)throw Error('账号已切换，请重新打开反馈');delete header['Content-Type']
 return new Promise<void>((resolve,reject)=>wx.uploadFile({url:settings.baseUrl+'/api/support/tickets/'+id+'/images',filePath:path,name:'file',header,timeout:60000,success:r=>{if(r.statusCode>=200&&r.statusCode<300)resolve();else{let message='截图上传未完成，可在反馈详情里继续补充';try{message=(JSON.parse(r.data) as {message:string}).message||message}catch{}reject(Error(message))}},fail:()=>reject(Error('文字反馈已保存，截图上传中断，可重试或稍后补充'))}))
}
export async function feedbackImage(id:string,image:string,anonymous=false):Promise<string>{
 const identity=token();const header=await headers(anonymous);if(!anonymous&&token()!==identity)throw Error('账号已切换，请重新打开反馈')
 return new Promise<string>((resolve,reject)=>wx.downloadFile({url:settings.baseUrl+'/api/support/tickets/'+id+'/images/'+image,header,success:r=>!anonymous&&token()!==identity?reject(Error('账号已切换')):r.statusCode===200?resolve(r.tempFilePath):reject(Error('截图暂时无法打开，请刷新重试')),fail:()=>reject(Error('截图下载未完成'))}))
}
