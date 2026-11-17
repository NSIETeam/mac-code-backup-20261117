import { api, run, user } from '../../services/api'
interface ModelSettings { revision: number; enabled: boolean; endpoint: string; model: string; protocol: string; vision: boolean; jsonMode: boolean; hasApiKey: boolean; configured: boolean; provider?: string; pdfAssistEnabled?: boolean; pdfAssistMaxPages?: number; timeoutSeconds?: number; reasoningEffort?: string }
Page({
 data: { allowed: false, loaded: false, busy: false, dirty: false, status: '', revision: 0, enabled: false, endpoint: '', model: '', protocol: 'chat-completions', protocolIndex: 0, protocols: ['标准模型接口', '专用中转接口'], providers: ['Moonshot · Kimi','其他兼容服务'], providerIndex: 1, vision: true, jsonMode: true, hasApiKey: false, configured: false, apiKey: '', clearApiKey: false, pdfAssistEnabled: false, pdfAssistMaxPages: 20, timeoutSeconds: 45, reasoningEffort: 'default', effortIndex: 0, efforts: ['服务商默认','低','高','最高'], models: [] as string[] },
 onLoad() { const allowed = user()?.role === 'admin'; this.setData({ allowed }); if (allowed) void this.load() },
 apply(value: ModelSettings) { this.setData({ ...value, providerIndex: value.provider === 'moonshot' ? 0 : 1, protocolIndex: value.protocol === 'structured' ? 1 : 0, effortIndex: ['default','low','high','max'].indexOf(value.reasoningEffort||'default'), pdfAssistEnabled: false, pdfAssistMaxPages: value.pdfAssistMaxPages||20, timeoutSeconds: value.timeoutSeconds||45, reasoningEffort: value.reasoningEffort||'default', apiKey: '', clearApiKey: false, loaded: true, dirty: false, status: '' }) },
 load() { return run(async () => {this.apply(await api<ModelSettings>('/admin/learning-model'))}) },
 changed() { this.setData({dirty:true,status:'配置已修改，请先保存。'}) },
 input(e: WechatMiniprogram.Input) { const field = e.currentTarget.dataset.field; if (['endpoint','model','apiKey'].includes(field)) this.setData({ [field]: e.detail.value, ...(field === 'apiKey' ? { clearApiKey: false } : {}) });if(field==='timeoutSeconds')this.setData({[field]:Number(e.detail.value)});this.changed() },
 toggle(e: WechatMiniprogram.SwitchChange) { const field = e.currentTarget.dataset.field; if (['enabled','vision','jsonMode'].includes(field)) {this.setData({ [field]: e.detail.value });this.changed()} },
 protocol(e: WechatMiniprogram.PickerChange) { const index = Number(e.detail.value); this.setData({ protocolIndex: index, protocol: index === 1 ? 'structured' : 'chat-completions' });this.changed() },
 provider(e: WechatMiniprogram.PickerChange) {const index=Number(e.detail.value);this.setData({providerIndex:index,endpoint:index===0?'https://api.moonshot.cn/v1':'',model:index===0?'kimi-k3.1-pro':'',protocol:'chat-completions',protocolIndex:0,vision:true,jsonMode:true,reasoningEffort:index===0?'low':'default',effortIndex:index===0?1:0,apiKey:'',models:[]});this.changed()},
 effort(e: WechatMiniprogram.PickerChange){const index=Number(e.detail.value);this.setData({effortIndex:index,reasoningEffort:['default','low','high','max'][index]});this.changed()},
 chooseModel(e: WechatMiniprogram.PickerChange){this.setData({model:this.data.models[Number(e.detail.value)]});this.changed()},
 clearKey() { this.setData({ apiKey: '', clearApiKey: true, enabled: false });this.changed() },
 save() {
  if (!this.data.allowed || !this.data.loaded || this.data.busy) return
  return run(async () => { this.setData({ busy: true });try {
   const d = this.data
   const value = await api<ModelSettings>('/admin/learning-model', 'PUT', { revision:d.revision,enabled:d.enabled,endpoint:d.endpoint.trim(),model:d.model.trim(),protocol:d.protocol,vision:d.vision,jsonMode:d.jsonMode,apiKey:d.apiKey||null,clearApiKey:d.clearApiKey,pdfAssistEnabled:false,pdfAssistMaxPages:Math.max(1,Math.min(100,Number(d.pdfAssistMaxPages)||20)),timeoutSeconds:d.timeoutSeconds,reasoningEffort:d.reasoningEffort })
   this.apply(value); wx.showToast({ title: '配置已保存', icon: 'success' })
  } finally { this.setData({ busy: false }) } })
 },
 probe(e:WechatMiniprogram.TouchEvent){
  if(!this.data.allowed||!this.data.loaded||this.data.busy||this.data.dirty)return
  const kind=e.currentTarget.dataset.kind;if(kind!=='test'&&kind!=='models')return
  return run(async()=>{this.setData({busy:true,status:kind==='test'?'正在验证模型连接…':'正在获取模型列表…'});try{
   if(kind==='models'){const r=await api<{models:string[]}>('/admin/learning-model/models','POST',{revision:this.data.revision});this.setData({models:r.models,status:'已获取 '+r.models.length+' 个模型，请在模型名称下选择。'})}
   else {const r=await api<{message:string;milliseconds:number}>('/admin/learning-model/test','POST',{revision:this.data.revision},150000);this.setData({status:r.message+' · '+(r.milliseconds/1000).toFixed(1)+' 秒'})}
  }catch(e){this.setData({status:e instanceof Error?e.message:'测试未完成'});throw e}finally{this.setData({busy:false})}})
 },
 onHide() { this.setData({ apiKey: '' }) },
 onUnload() { this.setData({ apiKey: '' }) }
})
