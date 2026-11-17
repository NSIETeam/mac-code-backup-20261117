import { openMobileUpload } from '../../services/mobile-upload'
import { api, run, Workspace, Named, Lecture, Handout, chooseFile, upload, user, stateLabels, confirm, openFile, chooseNamedFile, requestKey } from '../../services/api'
interface Classroom { schoolClass: Named & { ownerStaffId?: string | null }; course: Named | null; courses?: Named[]; students: Named[]; lectures: Lecture[]; handouts: Handout[]; teachers?: Named[]; registrations?: { studentId: string; lectureId: string }[]; materials?: { id: string; lectureId: string; sourceFileName: string; state: string; batchId: string; materialKind: string }[] }
interface CourseNamingOptions { minimumYear: number; maximumYear: number; terms: string[]; grades: string[]; subjects: string[]; types: string[] }
interface CourseDraft { year: number; term: string; grade: string; subject: string; type: string }
interface PendingCourse { input: CourseDraft; requestId: string; created?: Named; linked?: boolean }
interface Plan { id: string; staffId: string; title: string; content: string; teacherName: string; wordFileName: string }
Page({
  data: { canCreateCourse: false, showCourseCreate: false, courseCreateBusy: false, courseCreateStatus: '', createdCourseId: '', createdCourseName: '', createdCourseLinked: false, courseYearOptions: [] as number[], courseTermOptions: [] as string[], courseGradeOptions: [] as string[], courseSubjectOptions: [] as string[], courseTypeOptions: [] as string[], coursePickerIndices: { year: 0, term: 0, grade: 0, subject: 0, type: 0 }, courseDraft: { year: 0, term: '', grade: '', subject: '', type: '' } as CourseDraft, courseNamingPreview: '', canLinkCourse: false, linkedCourseNames: '', linkableCourses: [] as Named[], courseChoices: [] as Named[], showCourseLink: false, courseLinkQuery: '', linkingCourse: false, courseFilterId: '', relatedClasses: [] as Named[], loading: true, courses: [] as Named[], courseIndex: 0, manage: false, recordLectureIndex: 0, recordLectureId: '', lectureOptions: [] as { id: string; name: string }[], registrations: [] as { studentId: string; lectureId: string }[], courseOnly: false, shared: true, teachers: [] as Named[], teacherIndex: 0, plans: [] as Plan[], showPlan: false, planId: '', planTitle: '', planContent: '', planWordPath: '', planWordName: '', classId: '', courseId: '', name: '', courseName: '', admin: false, students: [] as (Named & { selected: boolean; completed?: boolean })[], lectures: [] as (Lecture & { handouts: (Handout & { stateName: string })[] })[], studentIds: [] as string[], lectureIds: [] as string[], title: '', sequence: '1', busy: false, showCreate: false },
  initialAction: '',
  pendingCourse: null as PendingCourse | null,
  onLoad(query: Record<string, string>) { this.initialAction = ['roster', 'new-plan', 'materials'].includes(query.action) ? query.action : ''; this.setData({ classId: query.classId || '', courseFilterId: query.courseId || '', courseOnly: query.mode === 'course', courseId: query.courseId || '', manage: query.mode === 'course' || query.action === 'materials' || query.action === 'roster' || query.action === 'new-plan', admin: user()?.role === 'admin', shared: user()?.role === 'admin' }) },
  onShow() { this.load() },
  load() { return run(async () => { this.setData({loading:true}); try { const r = await api<Classroom>(this.data.courseOnly ? '/courses/' + this.data.courseId + '/lectures' : '/classes/' + this.data.classId + ((this.data.manage ? this.data.courseId : this.data.courseFilterId) ? '?courseId=' + (this.data.manage ? this.data.courseId : this.data.courseFilterId) : '')); wx.setNavigationBarTitle({ title: r.schoolClass.name }); if (!this.data.courseOnly) await this.loadCourseLinks(); const recordLectureId = r.lectures.some(l => l.id === this.data.recordLectureId) ? this.data.recordLectureId : r.lectures[0]?.id || '';
    this.setData({ courses: this.data.manage && this.data.courses.length ? this.data.courses : r.courses?.length ? r.courses : r.course ? [r.course] : [], courseId: this.data.manage && this.data.courseId ? this.data.courseId : r.course?.id || '', recordLectureId, recordLectureIndex: Math.max(0, r.lectures.findIndex(l => l.id === recordLectureId)), registrations: r.registrations || [], lectureOptions: r.lectures.map(l => ({ id: l.id, name: '第' + l.sequence + '讲 · ' + l.title })), teachers: r.teachers || [], name: r.schoolClass.name, courseName: r.course?.name || '', students: r.students.map(s => ({ ...s, selected: this.data.studentIds.indexOf(s.id) >= 0, completed: (r.registrations || []).some(x => x.studentId === s.id && x.lectureId === recordLectureId) })), lectures: r.lectures.map(l => ({ ...l, completedCount: new Set((r.registrations || []).filter(x => x.lectureId === l.id).map(x => x.studentId)).size, hasPublished: r.handouts.some(h => h.lectureId === l.id && h.state === 'published'), materials: (r.materials || []).filter(m => m.lectureId === l.id), handouts: r.handouts.filter(h => h.lectureId === l.id).sort((a, b) => b.version - a.version).map(h => ({ ...h, stateName: stateLabels[h.state] || h.state })) })) }); if (!this.data.courseOnly && this.data.manage && this.data.courseId) this.setData({ plans: await api<Plan[]>('/classes/' + this.data.classId + '/plans?courseId=' + this.data.courseId) }); if (this.data.courseOnly) { const w = await api<Workspace>('/workspace'); this.setData({relatedClasses:w.classes.filter(c=>w.assignments.some(a=>a.classId===c.id && a.courseId===this.data.courseId))}) } const action = this.initialAction; this.initialAction = ''; if (!this.data.courseOnly && action === 'new-plan') { this.setData({manage:true}); if (this.data.teachers.length) this.newPlan(); else wx.showToast({ title: '请先分配任课教师', icon: 'none' }) } else if (!this.data.courseOnly && action === 'roster') this.setData({manage:true}, () => wx.pageScrollTo({ selector: '#class-roster', duration: 200 })) } finally { this.setData({loading:false}) } }) },
  async loadCourseLinks() {
    const w = await api<Workspace>('/workspace');
    const cl = w.classes.find(c => c.id === this.data.classId) as (Named & { ownerStaffId?: string | null }) | undefined;
    const linkedIds = new Set(w.assignments.filter(a => a.classId === this.data.classId).map(a => a.courseId));
    const linked = w.courses.filter(c => linkedIds.has(c.id));
    const linkableCourses = w.courses.filter(c => !linkedIds.has(c.id));
    const query = this.data.courseLinkQuery.trim().toLocaleLowerCase();
    this.setData({ canCreateCourse: w.user.role === 'admin' || w.user.role === 'teacher', canLinkCourse: !!cl && (w.user.role === 'admin' || w.user.role === 'teacher' && cl.ownerStaffId === w.user.id), linkedCourseNames: linked.map(c => c.name).join('、'), linkableCourses, courseChoices: linkableCourses.filter(c => c.name.toLocaleLowerCase().includes(query)) });
  },
  chooseCourseLink() { if (!this.data.canLinkCourse || this.data.loading || this.data.linkingCourse || this.data.courseCreateBusy) return; this.setData({ showCourseLink: !this.data.showCourseLink, showCourseCreate: false, courseLinkQuery: '', courseChoices: this.data.linkableCourses }) },
  searchCourseLink(e: WechatMiniprogram.Input) { const courseLinkQuery = e.detail.value; const q = courseLinkQuery.trim().toLocaleLowerCase(); this.setData({ courseLinkQuery, courseChoices: this.data.linkableCourses.filter(c => c.name.toLocaleLowerCase().includes(q)) }) },
  linkCourse(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    if (!this.data.canLinkCourse || this.data.linkingCourse || this.data.courseCreateBusy || !this.data.linkableCourses.some(c => c.id === id)) return;
    this.setData({ linkingCourse: true });
    return run(async () => { try { await api('/classes/' + this.data.classId + '/courses', 'POST', { courseId: id }); this.setData({ showCourseLink: false, courseLinkQuery: '', courses: [] }); await this.load(); wx.showToast({ title: '课程已关联', icon: 'success' }) } finally { this.setData({ linkingCourse: false }) } });
  },
  courseCreationStorageKey() { return 'siyue_course_create_' + (user()?.id || '') + '_' + this.data.classId },
  async chooseCourseCreate() {
    if (!this.data.canCreateCourse || this.data.loading || this.data.courseCreateBusy || this.data.linkingCourse) return;
    if (this.data.showCourseCreate) { this.setData({ showCourseCreate: false }); return; }
    this.setData({ courseCreateBusy: true });
    return run(async () => { try {
      const o = await api<CourseNamingOptions>('/course-naming-options');
      const years = Array.from({ length: o.maximumYear - o.minimumYear + 1 }, (_, i) => o.minimumYear + i);
      const terms = ['请选择学期', ...o.terms], grades = ['请选择年级', ...o.grades], subjects = ['请选择学科', ...o.subjects], types = ['请选择类型', ...o.types];
      const saved = wx.getStorageSync(this.courseCreationStorageKey()) as PendingCourse | undefined;
      const validSaved = saved && saved.requestId && saved.input && years.includes(saved.input.year) && o.terms.includes(saved.input.term) && o.grades.includes(saved.input.grade) && o.subjects.includes(saved.input.subject) && o.types.includes(saved.input.type);
      this.pendingCourse = validSaved ? saved : null;
      const draft: CourseDraft = validSaved ? saved.input : { year: Math.min(o.maximumYear, Math.max(o.minimumYear, new Date().getFullYear())), term: '', grade: '', subject: '', type: '' };
      this.setData({ showCourseCreate: true, showCourseLink: false, courseYearOptions: years, courseTermOptions: terms, courseGradeOptions: grades, courseSubjectOptions: subjects, courseTypeOptions: types, courseDraft: draft, coursePickerIndices: { year: years.indexOf(draft.year), term: Math.max(0, terms.indexOf(draft.term)), grade: Math.max(0, grades.indexOf(draft.grade)), subject: Math.max(0, subjects.indexOf(draft.subject)), type: Math.max(0, types.indexOf(draft.type)) }, createdCourseId: this.pendingCourse?.created?.id || '', createdCourseName: this.pendingCourse?.created?.name || '', createdCourseLinked: !!this.pendingCourse?.linked, courseCreateStatus: this.pendingCourse?.created ? (this.pendingCourse.linked ? '课程已创建并关联本班。' : '课程已创建，尚未确认关联本班。') : '' });
      this.updateCourseNamingPreview();
    } finally { this.setData({ courseCreateBusy: false }) } });
  },
  changeCourseDraft(e: WechatMiniprogram.PickerChange) {
    if (this.data.courseCreateBusy || this.data.createdCourseId) return;
    const field = e.currentTarget.dataset.field as keyof CourseDraft; const index = Number(e.detail.value);
    if (!['year', 'term', 'grade', 'subject', 'type'].includes(field)) return;
    const choices = { year: this.data.courseYearOptions, term: this.data.courseTermOptions, grade: this.data.courseGradeOptions, subject: this.data.courseSubjectOptions, type: this.data.courseTypeOptions };
    if (!Number.isInteger(index) || index < 0 || index >= choices[field].length) return;
    const value = field === 'year' ? choices.year[index] : index > 0 ? choices[field][index] : '';
    this.setData({ courseDraft: { ...this.data.courseDraft, [field]: value }, coursePickerIndices: { ...this.data.coursePickerIndices, [field]: index }, courseCreateStatus: '' });
    this.updateCourseNamingPreview();
  },
  updateCourseNamingPreview() { const d = this.data.courseDraft; this.setData({ courseNamingPreview: d.year && d.term && d.grade && d.subject && d.type ? [d.year, d.term, d.grade, d.subject, d.type].join('·') : '' }) },
  createCourse() {
    if (!this.data.canCreateCourse || this.data.courseCreateBusy || this.data.linkingCourse || !this.data.courseNamingPreview) return;
    if (this.data.createdCourseId && (this.data.createdCourseLinked || !this.data.canLinkCourse)) return;
    this.setData({ courseCreateBusy: true, courseCreateStatus: '' });
    return run(async () => { try {
      const input = { ...this.data.courseDraft }; const key = this.courseCreationStorageKey();
      if (!this.pendingCourse || JSON.stringify(this.pendingCourse.input) !== JSON.stringify(input)) this.pendingCourse = { input, requestId: requestKey() };
      wx.setStorageSync(key, this.pendingCourse);
      if (!this.pendingCourse.created) {
        this.pendingCourse.created = await api<Named>('/courses', 'POST', { ...input, requestId: this.pendingCourse.requestId });
        wx.setStorageSync(key, this.pendingCourse);
      }
      const created = this.pendingCourse.created;
      this.setData({ createdCourseId: created.id, createdCourseName: created.name, courseCreateStatus: '课程已创建，正在刷新关联状态…' });
      await this.loadCourseLinks();
      if (this.data.canLinkCourse) {
        this.setData({ courseCreateStatus: '课程已创建，正在关联本班…' });
        await api('/classes/' + this.data.classId + '/courses', 'POST', { courseId: created.id });
        this.pendingCourse.linked = true; wx.setStorageSync(key, this.pendingCourse);
        this.setData({ createdCourseLinked: true, courses: [], courseCreateStatus: '课程已创建并关联本班。' });
        await this.load();
      } else {
        this.setData({ courseCreateStatus: '课程已创建，尚未关联本班。请联系班级创建者或管理员关联。' });
      }
    } catch (error) {
      this.setData({ courseCreateStatus: this.pendingCourse?.created ? (this.pendingCourse.linked ? '课程已创建并关联本班，页面刷新失败，请重进班级查看。' : '课程已创建，关联尚未确认。可重试关联，不会重复创建课程。') : '创建尚未确认，请重试；相同信息不会重复创建。' });
      throw error;
    } finally { this.setData({ courseCreateBusy: false }) } });
  },
  resetCourseCreation() {
    if (this.data.courseCreateBusy || !this.data.createdCourseId || this.data.canLinkCourse && !this.data.createdCourseLinked) return;
    wx.removeStorageSync(this.courseCreationStorageKey()); this.pendingCourse = null;
    this.setData({ showCourseCreate: false, createdCourseId: '', createdCourseName: '', createdCourseLinked: false });
    return this.chooseCourseCreate();
  },
  courseMaterials() { wx.navigateTo({ url: '/pages/course-materials/course-materials?' + (this.data.courseOnly ? 'courseId=' + this.data.courseId : 'classId=' + this.data.classId + (this.data.courseFilterId ? '&courseId=' + this.data.courseFilterId : '')) }) },
  addLecture() { wx.navigateTo({url:'/pages/quick-add/quick-add?kind=lecture&classId='+this.data.classId+'&courseId='+(this.data.manage || this.data.courseFilterId || this.data.courses.length===1 ? this.data.courseId : '')}) },
  importStudents() { wx.navigateTo({url:'/pages/quick-add/quick-add?kind=student&classId='+this.data.classId+'&courseId='+this.data.courseId}) },
  openRelatedClass(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({url:'/pages/classroom/classroom?classId='+e.currentTarget.dataset.id+'&courseId='+this.data.courseId}) },
  book() { wx.navigateTo({url:'/pages/book-import/book-import?classId='+this.data.classId+'&courseId='+this.data.courseId}) },
  history() { wx.navigateTo({url:'/pages/history/history?classId='+this.data.classId+'&courseId='+this.data.courseId}) },
  toggleManage() { this.setData({manage: !this.data.manage}); return this.load() },
  managementCourse(e: WechatMiniprogram.PickerChange) { const index=Number(e.detail.value); this.setData({courseIndex:index,courseId:this.data.courses[index].id,studentIds:[],lectureIds:[]}); return this.load() },
  startGenerate() { this.setData({manage:true}); void this.load(); this.setData({}, () => wx.pageScrollTo({selector:'#class-roster',duration:200})) },
  openLecture(e: WechatMiniprogram.TouchEvent) { const id=e.currentTarget.dataset.id; const lecture=this.data.lectures.find(l=>l.id===id); if (lecture?.handouts.some(h=>h.state==='published')) wx.navigateTo({url:'/pages/questions/questions?classId='+this.data.classId+'&courseId='+lecture.courseId+'&lectureId='+id}); else { wx.navigateTo({url:'/pages/classroom/classroom?classId='+this.data.classId+'&courseId='+(lecture?.courseId || this.data.courseId)+'&action=materials'}); wx.showToast({title:'请先上传并发布本讲讲义',icon:'none'}) } },
  input(e: WechatMiniprogram.Input) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  students(e: WechatMiniprogram.CustomEvent<{value: string[]}>) { this.setData({ studentIds: e.detail.value, students: this.data.students.map(s => ({ ...s, selected: e.detail.value.indexOf(s.id) >= 0 })) }) }, lectures(e: WechatMiniprogram.CustomEvent<{value: string[]}>) { this.setData({ lectureIds: e.detail.value }) },
  toggle() { if (!this.data.courseOnly) { this.addLecture(); return } this.setData({ showCreate: !this.data.showCreate }) },
  create() { if (this.data.busy) return; this.setData({ busy: true }); run(async () => { try { await api('/lectures', 'POST', { classId: this.data.courseOnly || this.data.admin && this.data.shared ? null : this.data.classId, courseId: this.data.courseId, title: this.data.title, sequence: Number(this.data.sequence) }); this.setData({ title: '', showCreate: false }); this.load() } finally { this.setData({ busy: false }) } }) },
  shareLecture(e: WechatMiniprogram.TouchEvent) { if (this.data.busy) return; return run(async () => {
    if (!await confirm('此讲次及其所有讲义版本将向同课程班级共享，各班的学生名单和错题记录仍独立保存。确认共享？')) return;
    this.setData({ busy: true }); try { await api('/lectures/' + e.currentTarget.dataset.id + '/share', 'POST'); this.load() } finally { this.setData({ busy: false }) }
  }) },
  upload(e: WechatMiniprogram.TouchEvent) { if (this.data.busy) return; run(async () => { const choice = await new Promise<number|null>(resolve => wx.showActionSheet({ itemList: ['微信文件', '手机文件'], success: r => resolve(r.tapIndex), fail: () => resolve(null) })); if (choice === null) return; if (choice === 1) { await openMobileUpload({ mode: 'handout', lectureId: e.currentTarget.dataset.id }); return } const path = await chooseFile(['doc', 'docx', 'pdf']); if (!path) return; this.setData({ busy: true }); try { const r = await upload<{id: string}>('/lectures/' + e.currentTarget.dataset.id + '/handouts', path); wx.navigateTo({ url: '/pages/review/review?id=' + r.id }) } finally { this.setData({ busy: false }) } }) },
  open(e: WechatMiniprogram.TouchEvent) { const { id, state } = e.currentTarget.dataset; if (this.data.courseOnly) { wx.navigateTo({ url: '/pages/review/review?id=' + id }); return } wx.navigateTo({ url: '/pages/' + (state === 'published' ? 'questions/questions' : 'review/review') + '?id=' + id + (this.data.classId ? '&classId=' + this.data.classId : '') }) },
  shared(e: WechatMiniprogram.SwitchChange) { this.setData({ shared: e.detail.value }) },
  newPlan() { this.setData({ showPlan: !this.data.showPlan, planId: '', planTitle: '', planContent: '', planWordPath: '', planWordName: '', teacherIndex: 0 }, () => { if (this.data.showPlan) wx.pageScrollTo({ selector: '#plan-form', duration: 200 }) }) },
  editPlan(e: WechatMiniprogram.TouchEvent) { const plan = this.data.plans.find(p => p.id === e.currentTarget.dataset.id); if (plan) this.setData({ showPlan: true, planId: plan.id, planTitle: plan.title, planContent: plan.content, planWordPath: '', planWordName: plan.wordFileName || '', teacherIndex: Math.max(0, this.data.teachers.findIndex(t => t.id === plan.staffId)) }, () => wx.pageScrollTo({ selector: '#plan-form', duration: 200 })) },
  planTeacher(e: WechatMiniprogram.PickerChange) { this.setData({ teacherIndex: Number(e.detail.value), planId: '' }) },
  choosePlanWord() { if (this.data.busy) return; return run(async () => {
    const file = await chooseNamedFile(['doc', 'docx']); if (!file) return;
    if (!/\.(docx?)$/i.test(file.name) || file.size > 50 * 1024 * 1024 || !file.size) throw new Error('请选择不超过50MB的 doc 或 docx 文件');
    const path = file.path; const fileName = file.name;
    this.setData({ planWordPath: path, planWordName: fileName, ...(this.data.planTitle.trim() ? {} : { planTitle: fileName.replace(/\.(docx?)$/i, '') }) });
  }) },
  cancelPlanWord() { this.setData({ planWordPath: '', planWordName: this.data.plans.find(p => p.id === this.data.planId)?.wordFileName || '' }) },
  openPlanWord(e: WechatMiniprogram.TouchEvent) { return run(async () => {
    const r = await api<{ url: string; format: 'doc' | 'docx' }>('/lesson-plans/' + e.currentTarget.dataset.id + '/word'); await openFile(r.url, r.format);
  }) },
  savePlan() { if (this.data.busy) return; this.setData({ busy: true }); return run(async () => { try {
    const data: Record<string, string> = { courseId: this.data.courseId, title: this.data.planTitle, content: this.data.planContent, ...(this.data.admin ? { staffId: this.data.teachers[this.data.teacherIndex]?.id || '' } : {}), ...(this.data.planId ? { id: this.data.planId } : {}) };
    if (this.data.planWordPath) await upload('/classes/' + this.data.classId + '/plans/word', this.data.planWordPath, { ...data, fileName: this.data.planWordName });
    else await api('/classes/' + this.data.classId + '/plans', 'POST', data);
    this.setData({ showPlan: false }); this.load();
  } finally { this.setData({ busy: false }) } }) },
  recordLecture(e: WechatMiniprogram.PickerChange) { const index = Number(e.detail.value); const id = this.data.lectureOptions[index]?.id || ''; this.setData({ recordLectureIndex: index, recordLectureId: id, students: this.data.students.map(s => ({ ...s, completed: this.data.registrations.some(r => r.studentId === s.id && r.lectureId === id) })) }) },
  roster() { wx.navigateTo({ url: '/pages/roster/roster?classId=' + this.data.classId }) },
  homework(e: WechatMiniprogram.TouchEvent) { const courseId=this.data.lectures.find(l=>l.id===e.currentTarget.dataset.id)?.courseId || this.data.courseId; wx.navigateTo({ url: '/pages/file-print/file-print?classId=' + this.data.classId + '&courseId=' + courseId + '&lectureId=' + e.currentTarget.dataset.id + '&materialKind=homework' }) },
  material(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: '/pages/preview/preview?kind=export&id=' + e.currentTarget.dataset.id }) },
  record(e: WechatMiniprogram.TouchEvent) { const courseId=this.data.lectures.find(l=>l.id===this.data.recordLectureId)?.courseId || this.data.courseId; wx.navigateTo({ url: '/pages/questions/questions?classId=' + this.data.classId + '&courseId=' + courseId + '&studentId=' + e.currentTarget.dataset.id + (this.data.recordLectureId || this.data.lectureIds.length === 1 ? '&lectureId=' + (this.data.recordLectureId || this.data.lectureIds[0]) : '') }) },
  generate() { if (this.data.busy) return; this.setData({ busy: true }); run(async () => { try { const r = await api<{batchId: string; skipped: string[]}>('/exports', 'POST', { classId: this.data.classId, courseId: this.data.courseId, studentIds: this.data.studentIds, lectureIds: this.data.lectureIds }); if (r.skipped.length) wx.showToast({ title: r.skipped.length + '位学生暂无错题', icon: 'none' }); wx.navigateTo({ url: '/pages/export/export?batchId=' + r.batchId }) } finally { this.setData({ busy: false }) } }) }
})
