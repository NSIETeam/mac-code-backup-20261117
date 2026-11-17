import { settings, user } from '../../services/api'

const fileName = '思跃操作手册-电脑端与小程序端-20261104.pdf'
const url = settings.baseUrl + '/workbench/manuals/siyue-v1.2-20261104.pdf'

Page({
  data: { busy: false, progress: 0, savedPath: '', error: '' },
  onLoad() { if (!user()) wx.reLaunch({ url: '/pages/login/login' }) },
  async file(): Promise<string> {
    const fs = wx.getFileSystemManager()
    const path = wx.env.USER_DATA_PATH + '/siyue-manual-v1.2-20261104.pdf'
    const exists = await new Promise<boolean>(resolve => fs.access({ path, success: () => resolve(true), fail: () => resolve(false) }))
    if (exists) { this.setData({ savedPath: path, progress: 100 }); return path }
    // The manual uses the already registered request domain, like the workspace API.
    // Keep all domain checks enabled; validate PDF bytes before saving the response.
    const data = await new Promise<ArrayBuffer>((resolve, reject) => wx.request<ArrayBuffer>({
      url, responseType: 'arraybuffer', timeout: 120000,
      success: r => r.statusCode === 200 ? resolve(r.data) : reject(new Error('手册暂时无法下载，请稍后重试')),
      fail: () => reject(new Error('下载中断，请检查网络后重试'))
    }))
    const bytes = new Uint8Array(data)
    if (bytes.length < 5 || bytes[0] !== 37 || bytes[1] !== 80 || bytes[2] !== 68 || bytes[3] !== 70 || bytes[4] !== 45)
      throw new Error('手册文件不完整，请稍后重试')
    await new Promise<void>((resolve, reject) => fs.writeFile({ filePath: path, data,
      success: () => resolve(), fail: () => reject(new Error('保存失败，请检查手机可用空间后重试')) }))
    this.setData({ savedPath: path, progress: 100 })
    return path
  },
  async perform(action: 'view' | 'download' | 'share') {
    if (this.data.busy) return
    this.setData({ busy: true, progress: 0, error: '' })
    try {
      const filePath = await this.file()
      if (action === 'view') await new Promise<void>((resolve, reject) => wx.openDocument({ filePath, fileType: 'pdf', showMenu: true,
        success: () => resolve(), fail: () => reject(new Error('暂时无法打开，请使用“转发 PDF”后查看')) }))
      if (action === 'download') wx.showToast({ title: '手册已下载', icon: 'success' })
      if (action === 'share') await new Promise<void>((resolve, reject) => wx.shareFileMessage({ filePath, fileName,
        success: () => resolve(), fail: r => r.errMsg.indexOf('cancel') >= 0 ? resolve() : reject(new Error('转发失败，请重试')) }))
    } catch (e) { this.setData({ error: e instanceof Error ? e.message : '操作失败，请重试' }) }
    finally { this.setData({ busy: false }) }
  },
  view() { return this.perform('view') },
  download() { return this.perform('download') },
  share() { return this.perform('share') }
})
