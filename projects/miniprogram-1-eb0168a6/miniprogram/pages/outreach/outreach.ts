import { outreachLink } from '../../services/outreach-link'
import { user } from '../../services/api'

const path = '/pages/outreach/outreach'

Page({
  data: { isStaff: false, input: '', verifiedUrl: '', src: '', failed: false, error: '' },
  onLoad(query: Record<string, string>) {
    const isStaff = !!user()
    if (!query.form) {
      this.setData({ isStaff })
      return
    }
    let url = outreachLink(query.form)
    if (!url) {
      try { url = outreachLink(decodeURIComponent(query.form)) } catch { /* Malformed share path. */ }
    }
    this.setData({ src: url, error: url ? '' : '活动链接无效，请联系活动工作人员。' })
  },
  input(e: WechatMiniprogram.Input) {
    this.setData({ input: e.detail.value, verifiedUrl: '', error: '' })
  },
  prepare() {
    const verifiedUrl = outreachLink(this.data.input)
    this.setData({ verifiedUrl, error: verifiedUrl ? '' : '请粘贴 CRM 已发布的公开填写链接。推广链接请完整复制。' })
  },
  diagnosis() {
    if (!this.data.isStaff) return
    const form = outreachLink(this.data.input)
    const route = '/pages/outreach-diagnosis/outreach-diagnosis'
    wx.navigateTo({ url: form ? route + '?form=' + encodeURIComponent(form) : route })
  },
  previewForm() {
    if (this.data.verifiedUrl) this.setData({ src: this.data.verifiedUrl, failed: false })
  },
  failed() { this.setData({ failed: true }) },
  retry() { this.setData({ failed: false }) },
  copyLink() {
    if (this.data.src) wx.setClipboardData({ data: this.data.src })
  },
  onShareAppMessage() {
    const url = this.data.verifiedUrl || this.data.src
    return { title: '思跃教育 · 活动登记与试听', path: url ? path + '?form=' + encodeURIComponent(url) : path }
  }
})
