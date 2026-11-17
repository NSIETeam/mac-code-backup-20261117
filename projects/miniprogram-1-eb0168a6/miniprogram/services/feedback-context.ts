export function feedbackContext():Record<string,string>{
 const pages=getCurrentPages(),page=[...pages].reverse().find(p=>p.route!=='pages/feedback/feedback')?.route||''
 const context:Record<string,string>={page:'/'+page,platform:'微信小程序',version:wx.getAccountInfoSync().miniProgram.version||'开发版'}
 const saved=wx.getStorageSync('siyue_feedback_error') as {owner?:string;at?:number;context?:Record<string,string>}|undefined
 const owner=(wx.getStorageSync('siyue_user') as {id?:string}|undefined)?.id||'guest'
 if(saved?.owner===owner&&saved.at&&Date.now()-saved.at<30*60*1000)Object.assign(context,saved.context)
 return context
}
export function rememberFeedbackError(value:unknown):void{
 const e=value as {message?:string;path?:string;traceId?:string;status?:number}
 const owner=(wx.getStorageSync('siyue_user') as {id?:string}|undefined)?.id||'guest'
 wx.setStorageSync('siyue_feedback_error',{owner,at:Date.now(),context:{error:String(e?.message||value||'').slice(0,500),errorPath:String(e?.path||'').split('?')[0].slice(0,200),traceId:String(e?.traceId||'').slice(0,200),status:String(e?.status||'')}})
}
