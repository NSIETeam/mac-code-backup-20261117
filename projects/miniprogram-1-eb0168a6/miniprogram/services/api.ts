import {rememberFeedbackError} from './feedback-context'
import {errorHelp} from './error-help'
// Simulator, phone preview and released builds use the same campus data and devices.
export const settings = { baseUrl: 'https://www.siyuejn.com/miniapp' }
export interface User { id: string; name: string; role: 'admin' | 'teacher'; mustChangePassword?: boolean }
export interface Named { id: string; name: string; campusId?: string; classId?: string; code?: string }
export interface Assignment { id: string; staffId: string; classId: string; courseId: string }
export interface Lecture { id: string; title: string; sequence: number; classId: string | null; courseId: string }
export interface Handout { id: string; lectureId: string; version: number; state: string; revision: number; fileName: string; error?: string }
export interface Question { id: string; label: string; order: number; confirmed: boolean; previews: string[]; blocks: { index: number; text: string; kind: string; images: string[] }[]; issues: { code: string; message: string; blocking: boolean }[] }
export interface HandoutDetail { classId?: string | null; handout: Handout; lecture: Lecture; questions: Question[]; students: Named[]; wrong: { studentId: string; questionId: string }[]; sourceUrl?: string; absentStudentIds?: string[]; completedStudentIds?: string[]; marks?: { studentId: string; states: Record<string,string> }[] }
export interface Workspace { user: User; classes: Named[]; courses: Named[]; campuses: Named[]; printCampuses?: Named[]; assignments: Assignment[] }
export interface DocumentItem { id: string; batchId: string; studentName: string; state: string; error?: string; selected?: boolean; templateVersion?: string; needsReflow?: boolean; kind?: string; canDownloadWord?: boolean }
export interface Printer { id: string; name: string; campusId: string; duplex: boolean; lastSeen?: string; status?: string; statusDetail?: string; statusAt?: string; jobCount?: number; queueName?: string; deviceName?: string; deviceReachable?: boolean; deviceCheckedAt?: string; serverQueuedCount?: number; serverActiveCount?: number; loadObservedAt?: string }
export const stateLabels: Record<string, string> = { preparing: '生成后自动打印', queued: '排队中', processing: '处理中', draft: '待校对', published: '已发布', failed: '处理失败', ready: '已生成', claimed: '设备已接单', submitting: '正在提交打印机', submitted: '已提交，待确认出纸', uncertain: '结果待核实' }
export function token(): string { return wx.getStorageSync('siyue_token') || '' }
export function user(): User | null { return wx.getStorageSync('siyue_user') || null }
export function error(value: unknown): void { rememberFeedbackError(value);const h=errorHelp(value);wx.showModal({title:h.title,content:h.message+'\n\n'+h.steps,confirmText:h.label,cancelText:'复制信息',showCancel:true,success:r=>{if(r.cancel){wx.setClipboardData({data:h.details});return}const path=h.action==='login'?'/pages/login/login':h.action==='password'?'/pages/change-password/change-password':h.action==='jobs'?'/pages/jobs/jobs':'';if(path)wx.reLaunch({url:path})}}) }
export async function run(action: () => Promise<void>): Promise<void> { try { await action() } catch (e) { error(e) } }
export function api<T>(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET', data?: unknown, timeout = 30000): Promise<T> {
  return new Promise((resolve, reject) => wx.request({ url: settings.baseUrl + '/api' + path, method, data: data as WechatMiniprogram.IAnyObject, timeout,
    header: { Authorization: 'Bearer ' + token(), 'Content-Type': 'application/json' },
    success: res => { if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data as T); else { if(res.statusCode===428){const u=user();if(u)wx.setStorageSync('siyue_user',{...u,mustChangePassword:true});wx.reLaunch({url:'/pages/change-password/change-password'})} if (res.statusCode === 401 && path.indexOf('/auth/') !== 0) { wx.removeStorageSync('siyue_token'); wx.removeStorageSync('siyue_user'); wx.reLaunch({ url: '/pages/login/login' }) } reject(Object.assign(new Error((res.data as { message?: string })?.message || '请求失败（' + res.statusCode + '）'), {status:res.statusCode,path,traceId:(res.data as {traceId?:string})?.traceId})) } },
    fail: () => reject(Object.assign(new Error('连接失败，请检查网络后重试'),{path})) }))
}
export function upload<T>(path: string, filePath: string, formData: Record<string, string> = {}): Promise<T> {
  return new Promise((resolve, reject) => wx.uploadFile({ url: settings.baseUrl + '/api' + path, filePath, name: 'file', formData, header: { Authorization: 'Bearer ' + token() }, timeout: 60000,
    success: r => { try { const data = JSON.parse(r.data); if (r.statusCode >= 200 && r.statusCode < 300) resolve(data as T); else reject(new Error(data.message || '上传失败')) } catch { reject(new Error('上传失败，请检查文件大小和服务器配置')) } }, fail: () => reject(new Error('上传中断，请重试')) }))
}
export function chooseNamedFile(extension: string[]): Promise<{ path: string; name: string; size: number } | null> {
  return new Promise((resolve, reject) => wx.chooseMessageFile({ count: 1, type: 'file', extension, success: r => resolve({ path: r.tempFiles[0].path, name: r.tempFiles[0].name, size: r.tempFiles[0].size }), fail: r => { if (r.errMsg.indexOf('cancel') >= 0) resolve(null); else reject(new Error('无法选择文件')) } }));
}
export function chooseFile(extension: string[]): Promise<string> { return new Promise((resolve, reject) => wx.chooseMessageFile({ count: 1, type: 'file', extension, success: r => resolve(r.tempFiles[0].path), fail: r => { if (r.errMsg.indexOf('cancel') < 0) reject(new Error('无法选择文件')); else resolve('') } })) }
export function confirm(content: string, title = '请确认'): Promise<boolean> { return new Promise(resolve => wx.showModal({ title, content, success: r => resolve(r.confirm), fail: () => resolve(false) })) }
export function prompt(title: string, placeholderText = ''): Promise<string> { return new Promise(resolve => wx.showModal({ title, editable: true, placeholderText, success: r => resolve(r.confirm ? r.content.trim() : ''), fail: () => resolve('') })) }
export interface DocumentProgress { percent: number | null; received: number; phase: 'download' | 'saving' | 'done' }
export async function saveDocument(url: string, fileName: string, fileType: 'doc' | 'docx' | 'pdf', progress?: (value: DocumentProgress) => void): Promise<string> {
  const chunks: Uint8Array[] = []; let received = 0, total = 0;
  progress?.({percent:null,received:0,phase:'download'});
  const data = await new Promise<ArrayBuffer>((resolve, reject) => {
    const options: WechatMiniprogram.RequestOption<ArrayBuffer> & {enableChunked: boolean} = {
      url, responseType: 'arraybuffer', timeout: 120000, enableChunked: true,
      success: r => {
        if (r.statusCode !== 200) { reject(new Error('下载链接已过期，请重新下载')); return }
        if (chunks.length) {
          if (total && total !== received) { reject(new Error('文档内容不完整，请重新下载')); return }
          const combined = new Uint8Array(received); let offset = 0;
          chunks.forEach(chunk => { combined.set(chunk,offset); offset += chunk.length }); resolve(combined.buffer);
        } else resolve(r.data);
      },
      fail: () => reject(new Error('下载中断，请检查网络后重试'))
    };
    const task = wx.request<ArrayBuffer>(options) as WechatMiniprogram.RequestTask & {onChunkReceived?: (listener: (r: {data:ArrayBuffer}) => void) => void};
    task?.onHeadersReceived?.(r => { const key = Object.keys(r.header).find(k=>k.toLowerCase()==='content-length');total=key?Number(r.header[key]):0;if(!Number.isFinite(total)||total<0)total=0 });
    task?.onChunkReceived?.(r=>{const chunk=new Uint8Array(r.data);chunks.push(chunk);received+=chunk.length;progress?.({percent:total?Math.min(99,Math.floor(received*100/total)):null,received,phase:'download'})});
  });
  const bytes = new Uint8Array(data);
  const signature = fileType === 'pdf' ? [37, 80, 68, 70, 45] : fileType === 'docx' ? [80, 75, 3, 4] : [208, 207, 17, 224, 161, 177, 26, 225];
  if (bytes.length < signature.length || !signature.every((value, i) => bytes[i] === value)) throw new Error('文档内容不完整，请重新下载');
  const path = wx.env.USER_DATA_PATH + '/' + fileName.replace(/[\\/]/g, '_');
  progress?.({percent:99,received:data.byteLength,phase:'saving'});
  await new Promise<void>((resolve, reject) => wx.getFileSystemManager().writeFile({ filePath: path, data,
    success: () => resolve(), fail: () => reject(new Error('保存失败，请检查手机可用空间后重试')) }));
  progress?.({percent:100,received:data.byteLength,phase:'done'});
  return path;
}
export async function openFile(url: string, fileType: 'doc' | 'docx' | 'pdf' = 'docx', fileName?: string, progress?: (value: DocumentProgress) => void): Promise<void> {
  wx.showLoading({ title: '打开文档' });
  try { const filePath = await saveDocument(url, fileName || '思跃文档-' + requestKey() + '.' + fileType, fileType, progress); await new Promise<void>((resolve, reject) => wx.openDocument({ filePath, fileType, showMenu: true, success: () => resolve(), fail: () => reject(new Error('无法打开文档，请重试')) })) } finally { wx.hideLoading() }
}
export function requestKey(): string { return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + Math.random().toString(36).slice(2) }
export function selectedValues(e: WechatMiniprogram.CustomEvent<{ value: string[] }>): string[] { return e.detail.value }
