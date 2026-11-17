import { openMobileUpload } from '../../services/mobile-upload'
import { api, run, user, Workspace, Named, requestKey, chooseNamedFile, upload, confirm } from '../../services/api'
type Kind = 'class' | 'student' | 'lecture'
Page({
  data: { contextClassId: '', contextCourseId: '', kind: 'class' as Kind, loading: true, busy: false, saved: false, savedClassId: '', name: '', names: '', count: 0, manual: false, classIndex: 0, campusIndex: 0, courseIndex: 0, classes: [] as Named[], campuses: [] as Named[], courses: [] as Named[], error: '' },
  workspace: null as Workspace | null,
  pending: { key: '', fingerprint: '' },
  onLoad(query: Record<string, string>) { this.setData({ contextClassId: query.classId || '', contextCourseId: query.courseId || '', kind: query.kind === 'student' || query.kind === 'lecture' ? query.kind : 'class' }); wx.setNavigationBarTitle({title: this.data.kind === 'class' ? '添加班级' : this.data.kind === 'student' ? '添加学生' : '添加讲次'}); return this.load(query.classId) },
  load(classId?: string) { return run(async () => { try { const w = await api<Workspace>('/workspace'); this.workspace = w; if (classId && !w.classes.some(c=>c.id===classId)) throw new Error('当前班级不可访问，请返回重新选择'); const classes = classId ? w.classes.filter(c=>c.id===classId) : this.data.contextCourseId && this.data.kind !== 'class' ? w.classes.filter(c=>w.assignments.some(a=>a.classId===c.id && a.courseId===this.data.contextCourseId)) : w.classes; this.setData({ classes, campuses: w.campuses, classIndex: 0 }); await this.configureSelections() } finally { this.setData({ loading: false }) } }) },
  async configureSelections() {
    const w = this.workspace as Workspace | null; if (!w) return;
    const cl = this.data.classes[this.data.classIndex]; const campus = this.data.campuses[this.data.campusIndex];
    const classIds = this.data.kind === 'class' ? w.classes.filter(c => c.campusId === campus?.id).map(c => c.id) : cl ? [cl.id] : [];
    const selectedCourseId = this.data.contextCourseId || this.data.courses[this.data.courseIndex]?.id;
    const courses = w.courses.filter(c => (!this.data.contextCourseId || c.id === this.data.contextCourseId) && (this.data.kind === 'class' || w.user.role === 'admin' && ( !!this.data.contextCourseId || !w.assignments.some(a=>classIds.indexOf(a.classId)>=0)) || w.assignments.some(a => classIds.indexOf(a.classId) >= 0 && a.courseId === c.id)));
    this.setData({courses, courseIndex: Math.max(0,courses.findIndex(c=>c.id===selectedCourseId))});
  },
  select(e: WechatMiniprogram.PickerChange) { if (this.data.busy) return; const field = e.currentTarget.dataset.field; this.setData({[field]: Number(e.detail.value), error: ''}); return run(async () => { if (field !== 'courseIndex') await this.configureSelections() }) },
  toggleManual() { if (!this.data.busy) this.setData({manual:!this.data.manual,error:''}) },
  uploadStudents(e: WechatMiniprogram.TouchEvent) { if (this.data.busy || this.data.loading) return; return run(async () => {
    const cl = this.data.classes[this.data.classIndex]; if (!cl) throw new Error('请先选择班级');
    this.setData({busy:true,error:''});
    try {
      if (e.currentTarget.dataset.source === 'phone') { await openMobileUpload({mode:'roster',classId:cl.id}); return; }
      const file = await chooseNamedFile(['xlsx']); if (!file) return;
      if (!/\.xlsx$/i.test(file.name) || !file.size || file.size > 5 * 1024 * 1024) throw new Error('请选择不超过5MB的 Excel（.xlsx）名单');
      const endpoint = '/classes/'+cl.id+'/students/import';
      const result = await upload<{rows:{name:string}[]}>(endpoint,file.path);
      if (!await confirm('将 '+result.rows.length+' 名学生添加到 '+cl.name+'：\n'+result.rows.slice(0,8).map(s=>s.name).join('、')+(result.rows.length>8?'…':'')+'\n确认导入？')) return;
      await upload(endpoint,file.path,{commit:'true'});
      this.setData({saved:true,savedClassId:cl.id,count:result.rows.length});wx.showToast({title:'名单已导入',icon:'success'});
    } catch(e) { this.setData({error:e instanceof Error ? e.message : '导入未成功，请重试'}); } finally { this.setData({busy:false}) }
  }) },
  uploadLecture(e: WechatMiniprogram.TouchEvent) { if (this.data.busy || this.data.loading) return; return run(async () => {
    const cl = this.data.classes[this.data.classIndex]; const course = this.data.courses[this.data.courseIndex];
    if (!cl || !course) throw new Error('请先选择班级和课程');
    this.setData({busy:true,error:''});
    try {
      if (e.currentTarget.dataset.source === 'phone') { await openMobileUpload({mode:'book',classId:cl.id,courseId:course.id}); return; }
      const file = await chooseNamedFile(['doc','docx','pdf']); if (!file) return;
      if (!/\.(docx?|pdf)$/i.test(file.name) || !file.size || file.size > 50 * 1024 * 1024) throw new Error('请选择不超过50MB的 Word 或 PDF 讲义');
      const result = await upload<{id:string}>('/classes/'+cl.id+'/books?courseId='+course.id,file.path,{originalName:file.name});
      wx.redirectTo({url:'/pages/book-import/book-import?id='+result.id});
    } catch (e) { this.setData({error:e instanceof Error ? e.message : '上传未成功，请重试'}); } finally { this.setData({busy:false}) }
  }) },
  input(e: WechatMiniprogram.Input) { const field = e.currentTarget.dataset.field; const value = e.detail.value; this.setData({[field]: value, error: '', ...(field === 'names' ? {count: value.split(/\r?\n/).map(x => x.trim()).filter(Boolean).length} : {})}) },
  save() { if (this.data.busy || this.data.loading || this.data.saved || this.data.kind === 'lecture') return; return run(async () => {
    const d = this.data; const cl = d.classes[d.classIndex]; const course = d.courses[d.courseIndex]; const campus = d.campuses[d.campusIndex];
    this.setData({busy:true,error:''});
    try {
      let payload: Record<string, unknown>; let path: string;
      if (d.kind === 'class') { if (!d.name.trim() || !campus || !course) throw new Error('请填写班级名称并选择校区、课程'); path = '/classes'; payload = {name: d.name.trim(),campusId:campus.id,courseId:course.id}; }
      else if (d.kind === 'student') { const names = d.names.split(/\r?\n/).map(x => x.trim()).filter(Boolean); if (!cl) throw new Error('请先添加班级'); if (!names.length || names.length > 200) throw new Error('请输入1–200名学生，每行一位'); if (new Set(names).size !== names.length) throw new Error('名单中有重复姓名，请核对'); path = '/classes/' + cl.id + '/students/batch'; payload = {names}; }
      else return;
      const fingerprint = JSON.stringify({path,payload}); const storageKey = 'siyue_quick_add_' + (user()?.id || '') + '_' + d.kind;
      const stored = wx.getStorageSync(storageKey) as {key: string; fingerprint: string} | undefined;
      if (this.pending.fingerprint !== fingerprint) this.pending = stored?.fingerprint === fingerprint ? stored : {key:requestKey(),fingerprint};
      wx.setStorageSync(storageKey,this.pending);
      const result = await api<{id:string}>(path,'POST',{...payload,requestId:this.pending.key});
      wx.removeStorageSync(storageKey); this.setData({saved:true,savedClassId:d.kind === 'class' ? result.id : cl.id}); wx.showToast({title:'已添加',icon:'success'});
    } catch(e) { this.setData({error:e instanceof Error ? e.message : '添加失败，请重试'}); } finally { this.setData({busy:false}) }
  }) },
  addStudents() { wx.redirectTo({url:'/pages/quick-add/quick-add?kind=student&classId='+this.data.savedClassId}) },
  addLecture() { wx.redirectTo({url:'/pages/quick-add/quick-add?kind=lecture&classId='+this.data.savedClassId}) },
  done() { wx.redirectTo({url:'/pages/home/home?classId='+this.data.savedClassId}) },
  createClass() { wx.redirectTo({url:'/pages/quick-add/quick-add?kind=class'+(this.data.contextCourseId ? '&courseId='+this.data.contextCourseId : '')}) }
})
