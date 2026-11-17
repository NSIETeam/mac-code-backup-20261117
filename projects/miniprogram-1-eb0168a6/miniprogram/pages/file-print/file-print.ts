import { openMobileUpload } from '../../services/mobile-upload'
import { api, run, upload, chooseNamedFile, DocumentItem } from '../../services/api'
Page({
  data: { classId: '', courseId: '', lectureId: '', materialKind: '', busy: false, documents: [] as DocumentItem[] },
  onLoad(q: Record<string,string>) { this.setData({ classId: q.classId || '', courseId: q.courseId || '', lectureId: q.lectureId || '', materialKind: q.materialKind || '' }); wx.setNavigationBarTitle({ title: q.materialKind === 'homework' ? '上传本班作业' : '文件打印' }) },
  onShow() { return run(async () => { const docs = await api<DocumentItem[]>('/exports'); this.setData({ documents: docs.filter(d => d.kind === 'file') }) }) },
  wechat() { if (this.data.busy) return; return run(async () => { const file = await chooseNamedFile(['pdf','doc','docx','png','jpg','jpeg']); if (file) await this.send(file) }) },
  mobile() { if (this.data.busy) return; return run(async () => { this.setData({ busy: true }); try { await openMobileUpload({ mode: 'print', ...(this.data.classId ? { classId: this.data.classId, courseId: this.data.courseId, lectureId: this.data.lectureId, materialKind: this.data.materialKind } : {}) }) } finally { this.setData({ busy: false }) } }) },
  phone() { if (this.data.busy) return; return run(async () => {
    const file = await new Promise<{path:string;name:string;size:number}|null>((resolve,reject) => wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album','camera'], success: r => { const f=r.tempFiles[0]; const ext=/\.png$/i.test(f.tempFilePath) ? '.png' : '.jpg'; resolve({ path: f.tempFilePath, name: '手机图片-' + Date.now() + ext, size: f.size }) }, fail: r => r.errMsg.includes('cancel') ? resolve(null) : reject(new Error('无法选择手机图片')) }));
    if (file) await this.send(file);
  }) },
  async send(file: {path:string;name:string;size:number}) {
    if (file.size <= 0 || file.size > 50*1024*1024) throw new Error('请选择不超过50MB的文件');
    this.setData({ busy: true }); try {
      const context: Record<string,string> = this.data.classId ? { classId: this.data.classId, courseId: this.data.courseId, lectureId: this.data.lectureId, materialKind: this.data.materialKind } : {};
      const result = await upload<{id:string}>('/print-files', file.path, { ...context, fileName: file.name });
      wx.navigateTo({ url: '/pages/preview/preview?kind=export&id=' + result.id });
    } finally { this.setData({ busy: false }) }
  },
  preview(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: '/pages/preview/preview?kind=export&id=' + e.currentTarget.dataset.id }) }
})
