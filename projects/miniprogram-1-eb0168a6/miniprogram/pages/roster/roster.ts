import { openMobileUpload } from '../../services/mobile-upload'
import { api, run, upload, chooseNamedFile, confirm, Named } from '../../services/api'
Page({
  data: { classId: '', students: [] as Named[], editId: '', name: '', code: '', busy: false, showForm: false },
  onLoad(q: Record<string,string>) { this.setData({ classId: q.classId || '' }) },
  onShow() { return this.load() },
  load() { return run(async () => { this.setData({ students: await api<Named[]>('/classes/' + this.data.classId + '/students') }) }) },
  add() { wx.navigateTo({url:'/pages/quick-add/quick-add?kind=student&classId='+this.data.classId}) },
  edit(e: WechatMiniprogram.TouchEvent) { const s = this.data.students.find(x => x.id === e.currentTarget.dataset.id); if (s) this.setData({ editId: s.id, name: s.name, code: s.code || '', showForm: true }, () => wx.pageScrollTo({ selector: '#student-form', duration: 200 })) },
  input(e: WechatMiniprogram.Input) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  save() { if (this.data.busy) return; return run(async () => { this.setData({ busy: true }); try {
    await api('/classes/' + this.data.classId + '/students' + (this.data.editId ? '/' + this.data.editId : ''), this.data.editId ? 'PUT' : 'POST', { name: this.data.name.trim() }); this.setData({ showForm: false }); await this.load();
  } finally { this.setData({ busy: false }) } }) },
  mobileRoster() { if (this.data.busy) return; return run(async () => { this.setData({ busy: true }); try { await openMobileUpload({ mode: 'roster', classId: this.data.classId }) } finally { this.setData({ busy: false }) } }) },
  importRoster() { if (this.data.busy) return; return run(async () => {
    const file = await chooseNamedFile(['xlsx']); if (!file) return;
    if (file.size > 5 * 1024 * 1024) throw new Error('名单不能超过5MB');
    this.setData({ busy: true }); try { const endpoint = '/classes/' + this.data.classId + '/students/import'; const result = await upload<{rows: {name:string;code:string}[]}>(endpoint, file.path);
      if (await confirm('检测到 ' + result.rows.length + ' 名学生：\n' + result.rows.slice(0,5).map(x => x.name).join('\n') + '\n确认导入本班？')) { await upload(endpoint, file.path, { commit: 'true' }); await this.load() }
    } finally { this.setData({ busy: false }) }
  }) }
})
