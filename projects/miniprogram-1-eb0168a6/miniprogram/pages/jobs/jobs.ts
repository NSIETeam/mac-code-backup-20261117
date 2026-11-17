import { api, run, confirm, requestKey, stateLabels } from '../../services/api'
import { readExecution, executionLabel, executionActive } from '../../services/execution-status'
interface Job { id: string; batchId: string; documentId: string; studentName: string; printerName: string; state: string; detail?: string; copies: number; duplex: boolean; lastSeen?: string; executionJson?:string; monitorLastSeen?:string; monitorError?:string; statusDetail?:string; deviceReachable?:boolean; deviceCheckedAt?:string }
Page({
  data: { jobs: [] as (Job & { stateName: string; offline: boolean })[], loading: true, busy: false }, timer: 0 as number,
  visible:false, fetching:false,
  onShow() { this.visible=true; this.load() }, onHide() { this.visible=false;this.stop() }, onUnload() { this.onHide() }, stop() { if (this.timer) clearTimeout(this.timer); this.timer = 0 },
  load() { if(this.fetching)return;this.fetching=true;this.stop(); return run(async () => { try { const jobs = await api<Job[]>('/print-jobs'); this.setData({ jobs: jobs.map(j => { const execution=readExecution(j.executionJson);return { ...j,execution,executionActive:executionActive(execution),networkOffline:j.deviceReachable===false && !!j.deviceCheckedAt && Date.now()-Date.parse(j.deviceCheckedAt)<45000,offline: !j.lastSeen || Date.now() - Date.parse(j.lastSeen) > 90000, stateName: executionLabel(execution,j.monitorLastSeen)||stateLabels[j.state]||j.state } }) }); } finally { this.fetching=false;this.setData({ loading: false });if(this.visible)this.timer=setTimeout(()=>this.load(),5000) } }) },
  copyJob(e: WechatMiniprogram.TouchEvent) { const job = this.data.jobs.find(j => j.id === e.currentTarget.dataset.id); if (!job) return; wx.setClipboardData({ data: ['打印任务：' + job.id, '学生 / 文件：' + job.studentName, '打印机：' + job.printerName, '状态：' + (job.state === 'queued' && job.offline ? '等待设备上线' : job.stateName), '份数：' + job.copies + ' · ' + (job.duplex ? '双面' : '单面'), ...(job.detail ? ['说明：' + job.detail] : [])].join('\n') }) },
  exports() { wx.navigateTo({ url: '/pages/export/export' }) },
  reprint(e: WechatMiniprogram.TouchEvent) { if (this.data.busy) return; run(async () => { const id = e.currentTarget.dataset.id; const job = this.data.jobs.find(j => j.id === id)!; if (!await confirm(job.state === 'uncertain' ? '原任务可能已经出纸。请现场核实，确认仍需补打时继续。补打会生成一个新任务。' : '确认补打这位学生的错题本？将创建新的打印任务。', '补打确认')) return;
    const saved = wx.getStorageSync('siyue_reprint_' + id) as string; const key = saved || requestKey(); wx.setStorageSync('siyue_reprint_' + id, key); this.setData({ busy: true }); try { await api('/print-jobs/' + id + '/reprint', 'POST', { requestKey: key, acknowledgeUncertain: true }); wx.removeStorageSync('siyue_reprint_' + id); this.load() } finally { this.setData({ busy: false }) }
  }) }
})
