import { api, run, HandoutDetail, Question, Named, Lecture, Handout, confirm } from '../../services/api'
interface ClassDetail { campusName?: string; students: Named[]; lectures: Lecture[]; handouts: Handout[] }
interface HandoutOption { id: string; lectureId: string; label: string }
Page({
  data: { absentCount: 0, activeAbsent: false, markedCount: 0, campusName: '', activeName: '', activeCompleted: false, marks: {} as Record<string,string>, selectedQuestionIds: [] as string[], candidateIds: [] as string[], printableCount: 0, allCandidatesSelected: false, candidateStudents: [] as (Named & { candidate: boolean; wrongCount: number })[], showCandidates: false, id: '', classId: '', courseId: '', title: '', students: [] as (Named & { checked: boolean; completed?: boolean })[], studentIds: [] as string[], questions: [] as (Question & { checked: boolean })[], selectedIds: [] as string[], handouts: [] as HandoutOption[], handoutIndex: 0, batchMode: false, hasNext: false, saveError: false, dirty: false, busy: false, loading: true },
  detail: null as HandoutDetail | null,
  autoTimer: 0 as number, visible: true,
  onShow() { this.visible = true; if (this.data.dirty) this.scheduleSave() },
  onHide() { this.visible = false; clearTimeout(this.autoTimer); if (this.data.dirty && !this.data.busy && !this.data.batchMode) void run(async () => this.persist()) },
  onUnload() { this.onHide() },
  scheduleSave() { clearTimeout(this.autoTimer); if (this.data.batchMode) return; this.autoTimer = setTimeout(() => { if (this.visible && this.data.dirty && !this.data.busy && !this.data.batchMode) void run(async () => this.persist()) }, 500) },
  onLoad(q: Record<string, string>) {
    this.setData({ id: q.id || '', classId: q.classId || '', courseId: q.courseId || '', studentIds: q.studentId ? [q.studentId] : [] });
    return run(async () => {
      try {
        if (q.id) {
          const detail = await api<HandoutDetail>('/handouts/' + q.id + (this.data.classId ? '?classId=' + this.data.classId : ''));
          this.setData({ classId: detail.classId || this.data.classId || detail.lecture.classId || '', courseId: detail.lecture.courseId });
          await this.catalog(q.id, detail.lecture.id);
          this.showDetail(detail);
        } else {
          await this.catalog('', q.lectureId || '');
          if (this.data.id) await this.load();
          else this.applyStudents(this.data.studentIds.length ? this.data.studentIds : this.data.students.slice(0, 1).map(s => s.id));
        }
      } finally { this.setData({ loading: false }) }
    });
  },
  async catalog(handoutId: string, lectureId: string) {
    const r = await api<ClassDetail>('/classes/' + this.data.classId + '?courseId=' + this.data.courseId);
    const handouts: HandoutOption[] = [];
    r.lectures.slice().sort((a, b) => a.sequence - b.sequence).forEach(l => {
      r.handouts.filter(h => h.lectureId === l.id && h.state === 'published').sort((a, b) => b.version - a.version).forEach(h => {
        handouts.push({ id: h.id, lectureId: l.id, label: '第' + l.sequence + '讲 · ' + l.title + ' · v' + h.version });
      });
    });
    let index = handoutId ? handouts.findIndex(h => h.id === handoutId) : handouts.findIndex(h => h.lectureId === lectureId);
    if (index < 0 && !handoutId && !lectureId) index = 0;
    if (index < 0) { this.setData({ campusName: r.campusName || '', handouts: [], handoutIndex: 0, id: '', students: r.students.map(s => ({ ...s, checked: false })) }); return }
    this.setData({ campusName: r.campusName || '', handouts, handoutIndex: index, id: handouts[index]?.id || handoutId, students: r.students.map(s => ({ ...s, checked: false })) });
  },
  async load() {
    this.setData({ loading: true });
    try { this.showDetail(await api<HandoutDetail>('/handouts/' + this.data.id + (this.data.classId ? '?classId=' + this.data.classId : ''))) }
    finally { this.setData({ loading: false }) }
  },
  showDetail(r: HandoutDetail) {
    this.detail = r;
    const ids = this.data.studentIds.filter(id => r.students.some(s => s.id === id));
    if (!ids.length && !this.data.batchMode && r.students.length) ids.push(r.students[0].id);
    this.setData({ absentCount: r.absentStudentIds?.length || 0, title: '第' + r.lecture.sequence + '讲 · ' + r.lecture.title, questions: r.questions.map(q => ({ ...q, checked: false })), students: r.students.map(s => ({ ...s, checked: false, completed: (r.completedStudentIds || r.wrong.map(w => w.studentId)).includes(s.id) })) });
    this.applyStudents(ids);
    this.loadCandidates();
    wx.setNavigationBarTitle({ title: '逐题批改' });
  },
  applyStudents(ids: string[]) {
    const savedIds = ids.length === 1 && this.detail ? this.detail.wrong.filter(x => x.studentId === ids[0]).map(x => x.questionId) : [];
    const draft = this.data.id && ids.length ? wx.getStorageSync(this.draftKey(ids)) : null;
    const dirty = Array.isArray(draft);
    const selectedIds: string[] = dirty ? draft.filter((id: string) => this.data.questions.some(q => q.id === id)) : savedIds;
    const storedMarks = this.data.id && ids.length ? wx.getStorageSync(this.draftKey(ids) + ':marks') : null;
    const baseMarks = ids.length === 1 ? this.detail?.marks?.find(x => x.studentId === ids[0])?.states || {} : {};
    const marks: Record<string,string> = { ...(dirty && storedMarks && typeof storedMarks === 'object' && !Array.isArray(storedMarks) ? storedMarks : baseMarks) };
    this.data.questions.forEach(q => { if (selectedIds.includes(q.id)) marks[q.id] = 'wrong'; else if (marks[q.id] === 'wrong') delete marks[q.id] });
    const index = this.data.students.findIndex(s => s.id === ids[0]);
    this.setData({ saveError: false, selectedQuestionIds: [], activeAbsent: ids.length === 1 && !!this.detail?.absentStudentIds?.includes(ids[0]), markedCount: Object.keys(marks).filter(id => this.data.questions.some(q => q.id === id)).length, activeName: ids.length === 1 ? this.data.students.find(s => s.id === ids[0])?.name || '' : '已选 ' + ids.length + ' 位学生', activeCompleted: ids.length > 0 && ids.every(id => this.data.students.find(s => s.id === id)?.completed), marks, studentIds: ids, selectedIds, dirty, hasNext: !this.data.batchMode && index >= 0 && index < this.data.students.length - 1, students: this.data.students.map(s => ({ ...s, checked: ids.indexOf(s.id) >= 0 })), questions: this.data.questions.map(q => ({ ...q, checked: selectedIds.indexOf(q.id) >= 0, markState: marks[q.id] || 'unmarked', markLabel: this.markLabel(marks[q.id]), selectedForGrading: false })) });
  },
  selectStudent(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id;
    if (this.data.busy || this.data.loading || this.data.studentIds[0] === id) return;
    return run(async () => { if (await this.flush()) this.applyStudents([id]) });
  },
  students(e: WechatMiniprogram.CustomEvent<{ value: string[] }>) {
    if (this.data.busy) return;
    const ids = e.detail.value;
    return run(async () => { if (await this.flush()) this.applyStudents(ids) });
  },
  changeHandout(e: WechatMiniprogram.PickerChange) {
    if (this.data.busy || this.data.loading) return;
    const index = Number(e.detail.value);
    const option = this.data.handouts[index];
    if (!option || option.id === this.data.id) return;
    return run(async () => {
      if (!await this.flush()) return;
      // Keep the old lecture visible if fetching the new one fails.
      this.setData({ loading: true });
      try {
        const detail = await api<HandoutDetail>('/handouts/' + option.id + (this.data.classId ? '?classId=' + this.data.classId : ''));
        this.setData({ id: option.id, handoutIndex: index });
        this.showDetail(detail);
      } finally { this.setData({ loading: false }) }
    });
  },
  toggleBatch() {
    if (this.data.busy || this.data.loading) return;
    return run(async () => {
      if (!await this.flush()) return;
      const batchMode = !this.data.batchMode;
      this.setData({ batchMode, selectedQuestionIds: [] });
      this.applyStudents(this.data.studentIds.slice(0, 1));
    });
  },
  questions(e: WechatMiniprogram.CustomEvent<{ value: string[] }>) {
    if (this.data.busy || !this.data.studentIds.length) return;
    const marks = { ...this.data.marks }; this.data.questions.forEach(q => { if (e.detail.value.includes(q.id)) marks[q.id] = 'wrong'; else if (marks[q.id] === 'wrong') delete marks[q.id] });
    this.setData({ marks, selectedIds: e.detail.value, dirty: true, questions: this.data.questions.map(q => ({ ...q, checked: e.detail.value.indexOf(q.id) >= 0, markState: marks[q.id] || 'unmarked', markLabel: this.markLabel(marks[q.id]), selectedForGrading: false })) });
    wx.setStorageSync(this.draftKey(this.data.studentIds), e.detail.value); wx.setStorageSync(this.draftKey(this.data.studentIds) + ':marks', marks);
  },
  draftKey(ids: string[]): string { return 'siyue_wrong_draft:' + (wx.getStorageSync('siyue_user')?.id || '') + ':' + this.data.id + ':' + (this.data.batchMode ? 'batch:' : 'single:') + ids.slice().sort().join(',') },
  async flush(): Promise<boolean> {
    if (!this.data.dirty) return true;
    if (this.data.batchMode && this.data.studentIds.length > 1 && !await confirm('将为所选学生追加这组错题。已登记的其他错题会保留。')) return false;
    await this.persist();
    return true;
  },
  history() { return run(async () => { if(this.data.dirty) await this.persist(); wx.navigateTo({url:'/pages/history/history?classId='+this.data.classId+'&courseId='+this.data.courseId+'&studentId='+this.data.studentIds[0]}) }) },
  async persist() {
    if (!this.detail || !this.data.id || !this.data.studentIds.length) return;
    const studentIds = this.data.studentIds.slice();
    const questionIds = this.data.selectedIds.slice();
    const marks = { ...this.data.marks };
    const multi = this.data.batchMode && studentIds.length > 1;
    const draftKey = this.draftKey(studentIds);
    this.setData({ busy: true, saveError: false });
    try {
      await api('/wrong-questions', 'PUT', { handoutId: this.data.id, ...(this.data.classId ? { classId: this.data.classId } : {}), studentIds, questionIds, mode: multi ? 'add' : 'replace', ...(!multi ? { marks } : {}) });
      const retained = this.detail.wrong.filter(x => multi || studentIds.indexOf(x.studentId) < 0);
      studentIds.forEach(studentId => questionIds.forEach(questionId => {
        if (!retained.some(x => x.studentId === studentId && x.questionId === questionId)) retained.push({ studentId, questionId });
      }));
      this.detail.wrong = retained;
      if (!multi) this.detail.marks = [...(this.detail.marks || []).filter(x => !studentIds.includes(x.studentId)), ...studentIds.map(studentId => ({ studentId, states: marks }))];
      this.detail.completedStudentIds = Array.from(new Set([...(this.detail.completedStudentIds || []), ...studentIds]));
      this.setData({ activeCompleted: true, dirty: false, students: this.data.students.map(s => ({ ...s, completed: !!s.completed || studentIds.includes(s.id) })) });
      wx.removeStorageSync(draftKey); wx.removeStorageSync(draftKey + ':marks');
    } catch (error) { this.setData({ saveError: true }); throw error } finally { this.setData({ busy: false }) }
  },
  retrySave() { if (this.data.busy || this.data.loading || !this.data.dirty) return; return run(async () => { await this.flush() }) },
  toggleAbsent() { if (this.data.busy || this.data.loading || this.data.studentIds.length !== 1 || !this.detail) return; return run(async () => {
    if (!await this.flush()) return; const absent = !this.data.activeAbsent; const studentId = this.data.studentIds[0];
    this.setData({ busy: true }); try {
      await api('/lectures/' + this.detail!.lecture.id + '/attendance', 'PUT', { studentId, classId: this.data.classId, absent });
      const ids = new Set(this.detail!.absentStudentIds || []); if (absent) ids.add(studentId); else ids.delete(studentId);
      this.detail!.absentStudentIds = Array.from(ids); this.setData({ activeAbsent: absent, absentCount: ids.size });
    } finally { this.setData({ busy: false }) }
  }) },
  markLabel(state?: string) { return state === 'wrong' ? '错误' : state === 'correct' ? '正确' : state === 'unfinished' ? '未完成' : '未标' },
  tool(e: WechatMiniprogram.TouchEvent) {
    if (this.data.busy || this.data.loading || !this.data.selectedQuestionIds.length) return;
    const state = e.currentTarget.dataset.state; if (!['correct', 'wrong', 'unfinished'].includes(state) || this.data.batchMode && state !== 'wrong') return;
    const marks = { ...this.data.marks }; this.data.selectedQuestionIds.forEach(id => { marks[id] = state });
    this.applyMarks(marks);
  },
  mark(e: WechatMiniprogram.TouchEvent) {
    if (this.data.busy || this.data.loading || !this.data.studentIds.length) return;
    const id = e.currentTarget.dataset.id; if (!this.data.questions.some(q => q.id === id)) return;
    const ids = new Set(this.data.selectedQuestionIds); if (ids.has(id)) ids.delete(id); else ids.add(id);
    const selectedQuestionIds = Array.from(ids);
    this.setData({ selectedQuestionIds, questions: this.data.questions.map(q => ({ ...q, selectedForGrading: ids.has(q.id) })) });
  },
  applyMarks(marks: Record<string,string>) {
    const selectedIds = this.data.questions.filter(q => marks[q.id] === 'wrong').map(q => q.id);
    this.setData({ selectedQuestionIds: [], markedCount: Object.keys(marks).length, marks, selectedIds, dirty: true, questions: this.data.questions.map(q => ({ ...q, checked: selectedIds.includes(q.id), markState: marks[q.id] || 'unmarked', markLabel: this.markLabel(marks[q.id]), selectedForGrading: false })) });
    wx.setStorageSync(this.draftKey(this.data.studentIds), selectedIds); wx.setStorageSync(this.draftKey(this.data.studentIds) + ':marks', marks); this.scheduleSave();
  },
  previewQuestion(e: WechatMiniprogram.TouchEvent) { const q = this.data.questions.find(q => q.id === e.currentTarget.dataset.id); if (q?.previews.length) wx.previewImage({ current: q.previews[0], urls: q.previews }); else wx.showToast({title:'题干预览尚未就绪',icon:'none'}) },
  refresh() { if (this.data.busy || this.data.loading) return; return run(async () => { if (await this.flush()) await this.load() }) },
  candidateKey() { return 'siyue_print_candidates:' + (wx.getStorageSync('siyue_user')?.id || '') + ':' + this.data.classId + ':' + this.data.id },
  loadCandidates() {
    const saved = wx.getStorageSync(this.candidateKey());
    const counts = new Map<string, number>();
    this.detail?.wrong.forEach(w => counts.set(w.studentId, (counts.get(w.studentId) || 0) + 1));
    const candidateIds = this.data.students.filter(s => counts.has(s.id) && Array.isArray(saved) && saved.includes(s.id)).map(s => s.id);
    const candidateStudents = this.data.students.map(s => ({ ...s, candidate: candidateIds.includes(s.id), wrongCount: counts.get(s.id) || 0 }));
    const printableCount = candidateStudents.filter(s => s.wrongCount > 0).length;
    if (candidateIds.length) wx.setStorageSync(this.candidateKey(), candidateIds); else wx.removeStorageSync(this.candidateKey());
    this.setData({ candidateIds, candidateStudents, printableCount, allCandidatesSelected: printableCount > 0 && candidateIds.length === printableCount });
  },
  addCandidate() { if (this.data.busy || this.data.loading) return; return run(async () => { if (!await this.flush()) return; if (!this.data.selectedIds.length) { wx.showToast({title:'当前学生暂无错题',icon:'none'}); return } const ids = Array.from(new Set([...this.data.candidateIds, ...this.data.studentIds])); wx.setStorageSync(this.candidateKey(), ids); this.loadCandidates(); wx.showToast({title:'已加入打印候选',icon:'success'}) }) },
  viewCandidates() { if (this.data.busy || this.data.loading) return; return run(async () => { if (!await this.flush()) return; this.loadCandidates(); this.setData({ showCandidates: !this.data.showCandidates }) }) },
  keepCandidatesOpen() {},
  candidateSelection(e: WechatMiniprogram.CustomEvent<{value:string[]}>) { if(this.data.busy)return; wx.setStorageSync(this.candidateKey(),e.detail.value); this.loadCandidates() },
  selectAllCandidates() { if(this.data.busy)return; wx.setStorageSync(this.candidateKey(), this.data.allCandidatesSelected ? [] : this.data.candidateStudents.filter(s => s.wrongCount > 0).map(s => s.id)); this.loadCandidates() },
  createPrint(ids: string[]) { if (this.data.busy || this.data.loading) return; return run(async () => {
    if (!await this.flush()) return; if (!ids.length) throw new Error('请选择要打印的学生');
    this.setData({busy:true}); try { const r = await api<{batchId:string}>('/exports','POST',{classId:this.data.classId,courseId:this.data.courseId,studentIds:ids,lectureIds:this.detail ? [this.detail.lecture.id] : [],handoutIds:[this.data.id]}); wx.navigateTo({url:'/pages/export/export?batchId='+r.batchId}) } finally {this.setData({busy:false})}
  }) },
  progress() { wx.navigateTo({url:'/pages/jobs/jobs'}) },
  backToLecture() { const pages=getCurrentPages(); if (pages.length > 1 && pages[pages.length-2].route === 'pages/classroom/classroom') wx.navigateBack(); else wx.redirectTo({url:'/pages/classroom/classroom?classId='+this.data.classId}) },
  downloadCurrent() { if (this.data.busy || this.data.loading) return; return run(async () => { if (!await this.flush()) return; this.setData({busy:true}); try { const r=await api<{documents:{id:string}[]}>('/exports','POST',{classId:this.data.classId,courseId:this.data.courseId,studentIds:this.data.studentIds,lectureIds:this.detail?[this.detail.lecture.id]:[],handoutIds:[this.data.id]}); wx.navigateTo({url:'/pages/preview/preview?kind=export&id='+r.documents[0].id+'&download=1'}) } finally {this.setData({busy:false})} }) },
  directPrint() { return this.createPrint(this.data.studentIds) },
  printCandidates() { return this.createPrint(this.data.candidateIds) },
  preview(e: WechatMiniprogram.TouchEvent) { const src = e.currentTarget.dataset.src; wx.previewImage({ current: src, urls: this.data.questions.reduce((a, q) => a.concat(q.previews), [] as string[]) }) },
  save() {
    if (this.data.busy || this.data.loading || !this.data.studentIds.length || !this.data.id) return;
    return run(async () => {
      if (this.data.batchMode && this.data.studentIds.length > 1 && !await confirm('将为所选学生追加这组错题。已登记的其他错题会保留。')) return;
      await this.persist();
      wx.showToast({ title: '已保存', icon: 'success' });
      if (!this.data.batchMode && this.data.hasNext) {
        const index = this.data.students.findIndex(s => s.id === this.data.studentIds[0]);
        this.applyStudents([this.data.students[index + 1].id]);
        wx.pageScrollTo({ scrollTop: 0, duration: 200 });
      }
    });
  }
})
