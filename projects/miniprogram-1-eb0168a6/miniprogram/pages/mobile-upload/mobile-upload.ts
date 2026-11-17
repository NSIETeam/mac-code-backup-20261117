import { settings } from '../../services/api'
Page({
  data: { src: '', error: '' },
  onLoad(q: Record<string,string>) {
    const prefix = settings.baseUrl.replace(/\/$/,'') + '/mobile-upload/'
    let url = q.url || ''
    try { url = decodeURIComponent(url) } catch (_) { url = '' }
    if (!url.startsWith(prefix) || !/^[a-f0-9]{64}$/.test(url.slice(prefix.length))) { this.setData({ src: '', error: '上传入口无效，请返回后重新选择。' }); return }
    this.setData({ src: url, error: '' })
  },
  failed() { this.setData({ error: '手机文件暂不可用，请检查网络或联系管理员完成文件入口配置。' }) }
})
