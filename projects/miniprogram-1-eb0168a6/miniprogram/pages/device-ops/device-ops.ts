import { api, run, user, confirm, prompt, requestKey, Named } from '../../services/api'
import { readExecution, executionLabel } from '../../services/execution-status'
interface LivePrinter extends Named { statusDetail?:string; jobCount?:number; driver?:string; port?:string; deviceReachable?:boolean; deviceCheckedAt?:string }
interface Op { id: string; action: string; state: string; createdAt: string; result?: { message?: string; [key: string]: unknown }; label?: string; stateName?: string; report?: string }
const labels: Record<string,string> = { diagnose:'诊断电脑与打印机', 'enable-print-log':'启用打印详细日志', 'start-spooler':'启动打印服务', 'restart-spooler':'重启打印服务', 'resume-printer':'恢复暂停队列', 'cancel-job':'取消指定打印任务', 'desktop-enable':'启用远程桌面', 'desktop-disable':'关闭远程桌面', 'install-update':'更新终端程序' }
const states: Record<string,string> = {queued:'等待电脑执行',running:'电脑正在处理',succeeded:'操作已完成',failed:'操作失败',uncertain:'结果待核实',expired:'已过期，未执行'}
function formatReport(result: NonNullable<Op['result']>): string {
 const rows: string[] = []
 if(result.targetVersion) rows.push('目标版本：'+result.targetVersion)
 if(result.installedVersion) rows.push('安装版本：'+result.installedVersion)
 if(result.machine) rows.push('电脑：'+result.machine)
 if(result.spooler) rows.push('打印服务：'+result.spooler)
 if(result.serviceAccount) rows.push('执行身份：'+result.serviceAccount)
 for(const n of (result.network || []) as {adapter:string;ipv4:string[];gateway:string[]}[]) rows.push('网络：'+n.adapter+' · '+n.ipv4.join(', ')+' · 网关 '+n.gateway.join(', '))
 for(const p of (result.printers || []) as {name:string;driver:string;port:string;status:string;hostAddress?:string;portNumber?:number;tcpReachable?:boolean;jobs:{id:number;status:string;pages:number;printed:number}[]}[]) {
  rows.push('\n打印机：'+p.name,'驱动：'+p.driver,'状态：'+p.status,'端口：'+p.port)
  if(p.hostAddress) rows.push('连接：'+p.hostAddress+':'+p.portNumber+' · '+(p.tcpReachable?'端口可达':'端口不可达'))
  rows.push('排队任务：'+p.jobs.length)
  for(const j of p.jobs) rows.push('任务 '+j.id+'：'+j.status+' · '+j.printed+'/'+j.pages+' 页')
 }
 for(const note of (result.recommendations || []) as string[]) rows.push('\n建议：'+note)
 for(const ev of (result.events || []) as {id:number;level:string;time:string;codes:string[]}[]) rows.push('打印日志 '+ev.id+' · '+ev.level+' · '+ev.time+(ev.codes?.length?' · 错误码 '+ev.codes.join(', '):''))
 return rows.join('\n')
}
Page({
 data:{id:'',name:'校区电脑',version:'',monitorState:'监控信息读取中',monitorError:'',livePrinters:[] as LivePrinter[],executions:[] as unknown[],printerIndex:0,printers:[] as Named[],operations:[] as Op[],busy:false,desktop:null as null|{remoteId:string;password:string;server:string;key:string;config:string},actions:Object.keys(labels).map(key=>({key,name:labels[key]}))},
 timer:0 as number,visible:false,loading:false,
 onLoad(q:Record<string,string>){this.setData({id:q.id||''})},
 onShow(){if(user()?.role!=='admin'){wx.navigateBack();return}this.visible=true;this.load();this.schedule()},
 onHide(){this.visible=false;clearTimeout(this.timer);this.setData({desktop:null})},onUnload(){this.onHide()},
 schedule(){clearTimeout(this.timer);if(this.visible)this.timer=setTimeout(async()=>{try{await this.load()}finally{this.schedule()}},5000)},
 load(){if(this.loading)return;this.loading=true;return run(async()=>{try{
  const c=await api<{device:Named & {agentVersion?:string;monitorVersion?:string;monitorLastSeen?:string;monitorError?:string};printers:LivePrinter[];executions?:{id:string;executionJson:string}[]}>('/admin/devices/'+this.data.id+'/overview');const d=c.device
  const operations=await api<Op[]>('/admin/devices/'+this.data.id+'/operations')
  this.setData({name:d?.name||'校区电脑',version:d?.agentVersion||'未知',monitorState:!d.monitorVersion?'尚未安装执行监控补丁':d.monitorLastSeen && Date.now()-Date.parse(d.monitorLastSeen)<30000?'执行监控在线 · 每约 5 秒更新':'执行监控离线或状态待更新',monitorError:d.monitorError||'',livePrinters:c.printers.map(p=>({...p,networkLabel:!p.deviceCheckedAt || Date.now()-Date.parse(p.deviceCheckedAt)>45000?'网络状态未确认':p.deviceReachable===true?'网络端口可达':p.deviceReachable===false?'网络端口不可达':'网络状态未知'})),executions:(c.executions||[]).map(j=>{const x=readExecution(j.executionJson);return {...j,...x,label:executionLabel(x,d.monitorLastSeen),windowsJobs:x?.windowsJobIds?.join(', ')||'尚未捕获'}}),printers:[{id:'',name:'全部打印机'},...c.printers],operations:operations.map(o=>({...o,label:labels[o.action]||o.action,stateName:states[o.state]||o.state,report:o.result?formatReport(o.result):''}))})
 }finally{this.loading=false}})},
 printer(e:WechatMiniprogram.PickerChange){this.setData({printerIndex:Number(e.detail.value)})},
 action(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;return run(async()=>{
  const action=e.currentTarget.dataset.action as string;const printerId=this.data.printers[this.data.printerIndex]?.id||null;let jobId:number|null=null
  if((action==='resume-printer'||action==='cancel-job')&&!printerId)throw new Error('请先选择具体打印机')
  if(action==='cancel-job'){const value=await prompt('取消指定任务','输入诊断报告里的 Windows 任务编号');if(!value)return;jobId=Number(value);if(!Number.isInteger(jobId)||jobId<1)throw new Error('任务编号无效')}
  const message=action==='install-update'?'将下载并校验服务器发布的新版本，短暂重启助手后台服务。校区、设备身份和服务器地址会保留；若 Windows 队列还有任务，本次更新将拒绝执行。':action==='desktop-enable'?'将在这台电脑安装并启用远程桌面，管理员可查看屏幕并操作鼠标键盘。再次启用会更新密码。':action==='restart-spooler'?'重启会影响 Windows 打印服务。助手仅在所有队列为空时执行。':action==='cancel-job'?'将取消选定打印机的 '+jobId+' 号任务，已出纸部分无法撤回。':'确认对“'+this.data.name+'”执行“'+labels[action]+'”？'
  if(action!=='diagnose'&&!await confirm(message,'远程操作确认'))return
  const payload={action,printerId,jobId,acknowledge:action!=='diagnose'};const storage='device-op-'+this.data.id;const prior=wx.getStorageSync(storage);const signature=JSON.stringify(payload);const key=prior?.signature===signature?prior.key:requestKey();wx.setStorageSync(storage,{signature,key});this.setData({busy:true,desktop:null});try{await api('/admin/devices/'+this.data.id+'/operations','POST',{...payload,requestKey:key});wx.removeStorageSync(storage);await this.load()}finally{this.setData({busy:false})}
 })},
 desktop(e:WechatMiniprogram.TouchEvent){return run(async()=>{const d=await api<{remoteId:string;password:string;server:string;key:string;config:string}>('/admin/operations/'+e.currentTarget.dataset.id+'/desktop-access','POST');this.setData({desktop:d})})},
 copy(e:WechatMiniprogram.TouchEvent){const key=e.currentTarget.dataset.key as 'remoteId'|'password'|'config';const value=this.data.desktop?.[key];if(value)wx.setClipboardData({data:value})},
 clearDesktop(){this.setData({desktop:null})}
})
