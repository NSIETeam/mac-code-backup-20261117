import { api, run, saveDocument, DocumentItem } from '../../services/api'
interface PreviewPage { number: number; width: number; height: number; url: string }
interface PreviewResult { title: string; state: 'processing' | 'ready' | 'failed'; error?: string; phase?: 'export'; pages: PreviewPage[] }
Page({
  data: { batchDocuments: [] as DocumentItem[], batchIndex: 0, batchId: '', id: '', kind: 'export', pages: [] as PreviewPage[], state: 'processing', error: '', phase: '', retrying: false, zoom: 100, downloadRequested: false, downloading: false, savedPath: '', fileName: '', downloadProgress: 0, downloadUnknown: false, downloadLabel: '下载PDF' },
  timer: 0 as number, active: false, sequence: 0,
  onLoad(q: Record<string, string>) { this.setData({ batchId: q.batchId || '', id: q.id || '', kind: q.kind === 'handout' ? 'handout' : 'export', downloadRequested: q.download === '1' }) },
  onShow() { this.active = true; return run(async()=>{if(this.data.batchId){const docs=await api<DocumentItem[]>('/exports?batchId='+encodeURIComponent(this.data.batchId));this.setData({batchDocuments:docs,batchIndex:Math.max(0,docs.findIndex(d=>d.id===this.data.id))})}await this.load(true)}) },
  student(e:WechatMiniprogram.PickerChange){const index=Number(e.detail.value),doc=this.data.batchDocuments[index];if(!doc)return;this.sequence++;this.setData({id:doc.id,batchIndex:index,pages:[],state:'processing',error:'',phase:'',savedPath:'',fileName:'',downloadProgress:0,downloadUnknown:false,downloadLabel:'下载PDF'});return this.load(true)},
  onHide() { this.stop() }, onUnload() { this.stop() },
  stop() { this.active = false; this.sequence++; if (this.timer) clearTimeout(this.timer); this.timer = 0 },
  async load(start = false) {
    if (this.timer) clearTimeout(this.timer);
    this.timer = 0; const sequence = ++this.sequence;
    try {
      const r = await api<PreviewResult>('/previews/' + this.data.kind + '/' + encodeURIComponent(this.data.id), start ? 'POST' : 'GET');
      if (!this.active || sequence !== this.sequence) return;
      wx.setNavigationBarTitle({ title: r.title });
      this.setData({ pages: r.pages, state: r.state, error: r.error || '', phase: r.phase || '' });
      if (r.state === 'ready' && this.data.downloadRequested) { this.setData({ downloadRequested: false }); await this.downloadPDF() }
      if (r.state === 'processing') this.timer = setTimeout(() => this.load(r.phase === 'export'), 1500);
    } catch (e) {
      if (this.active && sequence === this.sequence) this.setData({ state: 'failed', error: e instanceof Error ? e.message : '预览加载失败，请重试' });
    }
  },
  downloadPDF() { if (this.data.downloading || this.data.kind !== 'export' || this.data.state !== 'ready') return; return run(async () => {
    this.setData({ downloading: true, downloadProgress: 0, downloadUnknown: true, downloadLabel: '准备下载…' });
    try {
      const id = this.data.id;
      const r = await api<{ url: string; fileName?: string }>('/exports/' + encodeURIComponent(id) + '/download?format=pdf');
      const fileName = r.fileName || '错题本-' + id + '.pdf';
      const savedPath = await saveDocument(r.url, fileName, 'pdf', value => { if(this.data.id===id)this.setData({downloadProgress:value.percent || 0,downloadUnknown:value.percent===null,downloadLabel:value.phase==='done'?'已下载PDF':value.phase==='saving'?'正在保存…':value.percent===null?'下载中 '+Math.ceil(value.received/1024)+' KB':'下载 '+value.percent+'%'}) });
      if (this.data.id !== id) return;
      this.setData({ savedPath, fileName }); wx.showToast({ title: 'PDF已下载', icon: 'success' });
    } catch(e) { this.setData({downloadProgress:0,downloadUnknown:false,downloadLabel:'重试下载PDF'}); throw e }
    finally { this.setData({ downloading: false }) }
  }) },
  sharePDF() { if (this.data.savedPath) wx.shareFileMessage({ filePath: this.data.savedPath, fileName: this.data.fileName }) },
  print() { if (this.data.kind === 'export' && this.data.state === 'ready') wx.navigateTo({ url: '/pages/export/export?' + (this.data.batchId ? 'batchId=' + encodeURIComponent(this.data.batchId) : 'documentId=' + encodeURIComponent(this.data.id)) }) },
  retry() {
    if (this.data.retrying) return;
    return run(async () => {
      this.setData({ retrying: true });
      try {
        // A failed export must be regenerated before the PDF renderer can retry.
        if (this.data.kind === 'export' && this.data.phase === 'export') await api('/exports/' + encodeURIComponent(this.data.id) + '/retry', 'POST');
        this.setData({ state: 'processing', error: '' }); await this.load(true);
      } finally { this.setData({ retrying: false }) }
    });
  },
  zoomIn() { this.setData({ zoom: Math.min(250, this.data.zoom + 25) }) },
  zoomOut() { this.setData({ zoom: Math.max(100, this.data.zoom - 25) }) },
  imageError() { this.setData({ error: '页面图片未能加载，请刷新预览。' }) }
})
