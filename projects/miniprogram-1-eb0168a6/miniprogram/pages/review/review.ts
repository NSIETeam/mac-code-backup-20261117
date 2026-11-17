import { api, run, HandoutDetail, Question, prompt, confirm, stateLabels } from '../../services/api'
import { formatQueueEstimate, QueueEstimate } from '../../utils/queue-estimate'
import { nextReviewLesson } from '../../utils/review-navigation'
interface ReviewBook { book:{classId:string;courseId:string}; sections:{kind?:string;sequence:number;title:string;handoutId?:string}[]; handouts:{id:string;state:string}[] }
interface ReviewClass { lectures:{id:string;sequence:number;courseId:string;classId:string|null}[]; handouts:{id:string;lectureId:string;state:string;version:number}[] }
Page({
  data: { id: '', bookId: '', classId: '', courseId: '', lectureSequence: 0, handout: null as HandoutDetail['handout'] | null, questions: [] as Question[], targetLabels: [] as string[], stateName: '', queueText: '', sourceUrl: '', busy: false, canPublish: false, canConfirmAll: false },
  timer: 0 as number, visible: false,
  onLoad(q: Record<string, string>) { this.setData({ id: q.id, bookId: q.bookId || '', classId: q.classId || '', courseId: q.courseId || '' }) }, onShow() { this.visible = true; this.load() }, onHide() { this.visible = false; this.stop() }, onUnload() { this.onHide() },
  stop() { if (this.timer) { clearTimeout(this.timer); this.timer = 0 } },
  load() { this.stop(); return run(async () => { const r = await api<HandoutDetail & {queue?:QueueEstimate}>('/handouts/' + this.data.id + (this.data.classId ? '?classId=' + encodeURIComponent(this.data.classId) : '')); wx.setNavigationBarTitle({ title: r.handout.fileName + ' · v' + r.handout.version }); this.setData({ classId: this.data.classId || r.classId || r.lecture?.classId || '', courseId: r.lecture?.courseId || this.data.courseId, lectureSequence: r.lecture?.sequence || 0, handout: r.handout, queueText: r.queue ? formatQueueEstimate(r.queue) : '', questions: r.questions, targetLabels: r.questions.map(q => q.order + '. ' + q.label), stateName: stateLabels[r.handout.state] || r.handout.state, sourceUrl: r.sourceUrl || '', canConfirmAll: r.handout.state === 'draft' && r.questions.some(q => !q.confirmed && q.previews.length > 0 && !q.issues.some(i => i.blocking)), canPublish: r.questions.length > 0 && r.questions.every(q => q.confirmed && q.previews.length > 0 && !q.issues.some(i => i.blocking)) }); if (this.visible && (['queued', 'processing'].indexOf(r.handout.state) >= 0 || r.handout.state === 'draft' && !r.handout.error && r.questions.some(q => !q.previews.length))) this.timer = setTimeout(() => this.load(), 3500) }) },
  retry() { if (this.data.busy) return; this.setData({ busy: true }); return run(async () => { try { await api('/handouts/' + this.data.id + '/retry', 'POST'); await this.load() } finally { this.setData({ busy: false }) } }) },
  edit(action: string, questionId: string, extra: Record<string, unknown> = {}) { if (this.data.busy || !this.data.handout) return; this.setData({ busy: true }); run(async () => { try { await api('/handouts/' + this.data.id + '/edit', 'POST', { action, revision: this.data.handout!.revision, questionId, ...extra }); this.load() } finally { this.setData({ busy: false }) } }) },
  confirm(e: WechatMiniprogram.TouchEvent) { this.edit('confirm', e.currentTarget.dataset.id) },
  confirmAll() { if (this.data.busy || !this.data.handout) return; this.setData({ busy: true }); return run(async () => { try { const r = await api<{ confirmed: number; remaining: number }>('/handouts/' + this.data.id + '/confirm-all', 'POST', { revision: this.data.handout!.revision }); await this.load(); wx.showToast({ title: r.remaining ? '已确认 ' + r.confirmed + ' 题，' + r.remaining + ' 题待处理' : '全部题目已确认', icon: 'none' }) } finally { this.setData({ busy: false }) } }) },
  label(e: WechatMiniprogram.TouchEvent) { run(async () => { const label = await prompt('修改原题号', e.currentTarget.dataset.label); if (label) this.edit('label', e.currentTarget.dataset.id, { label }) }) },
  merge(e: WechatMiniprogram.TouchEvent) { const index = Number(e.currentTarget.dataset.index); const q = this.data.questions[index]; const other = this.data.questions[index + Number(e.currentTarget.dataset.direction)]; if (!other) return; run(async () => { if (await confirm('合并“' + q.label + '”与“' + other.label + '”的全部内容？')) this.edit('merge', q.id, { otherQuestionId: other.id }) }) },
  split(e: WechatMiniprogram.TouchEvent) { run(async () => { const label = await prompt('下一题的题号', '例如：练3、4.'); if (label) this.edit('split', e.currentTarget.dataset.id, { blockIndex: Number(e.currentTarget.dataset.block), label }) }) },
  moveImage(e: WechatMiniprogram.PickerChange) { const from = e.currentTarget.dataset.id; const target = this.data.questions[Number(e.detail.value)]; if (!target || target.id === from) return; const block = Number(e.currentTarget.dataset.block); const imageIndex = Number(e.currentTarget.dataset.image); const source = this.data.questions.find(q => q.id === from)!; const rid = source.blocks[block].images[imageIndex]; const occurrence = source.blocks[block].images.slice(0, imageIndex).filter(x => x === rid).length; this.edit('move-image', from, { otherQuestionId: target.id, relationshipId: rid, blockIndex: block, occurrence }) },
  source() { if (this.data.sourceUrl) wx.navigateTo({ url: '/pages/preview/preview?kind=handout&id=' + encodeURIComponent(this.data.id) }) },
  preview(e: WechatMiniprogram.TouchEvent) { wx.previewImage({ urls: [e.currentTarget.dataset.src] }) },
  async continueAfterPublish() {
    const classId=this.data.classId, courseId=this.data.courseId;
    if (this.data.bookId) {
      const detail=await api<ReviewBook>('/books/'+encodeURIComponent(this.data.bookId));
      if((classId&&classId!==detail.book.classId)||(courseId&&courseId!==detail.book.courseId)||!detail.sections.some(section=>section.handoutId===this.data.id))throw new Error('当前讲义与讲次列表不一致，请返回原讲义列表继续校对');
      const seen=new Set<string>();
      const lessons=(detail.sections||[]).filter(section=>{
        if(section.kind!=='lesson'&&!(section.handoutId&&!section.kind))return false;
        if(section.handoutId){if(seen.has(section.handoutId))return false;seen.add(section.handoutId)}
        return true;
      }).map(section=>({...section,state:detail.handouts?.find(handout=>handout.id===section.handoutId)?.state}));
      const next=nextReviewLesson(lessons,this.data.id,this.data.lectureSequence);
      const context='&classId='+encodeURIComponent(classId||detail.book.classId)+'&courseId='+encodeURIComponent(courseId||detail.book.courseId);
      if(next?.handoutId&&next.state==='draft') {
        wx.redirectTo({url:'/pages/review/review?id='+encodeURIComponent(next.handoutId)+'&bookId='+encodeURIComponent(this.data.bookId)+context});
      } else {
        wx.redirectTo({url:'/pages/book-import/book-import?id='+encodeURIComponent(this.data.bookId)+context+(next?'&next='+encodeURIComponent(next.handoutId||'seq-'+next.sequence):'')});
      }
      return;
    }
    if(!classId||!courseId)return;
    const detail=await api<ReviewClass>('/classes/'+encodeURIComponent(classId)+'?courseId='+encodeURIComponent(courseId));
    const current=detail.handouts.find(handout=>handout.id===this.data.id);
    if(!current||!detail.lectures.some(lecture=>lecture.id===current.lectureId&&lecture.courseId===courseId&&(!lecture.classId||lecture.classId===classId)))throw new Error('当前讲义不在本班本课程中，请返回班级讲义列表继续校对');
    const lessons=detail.lectures.filter(lecture=>lecture.courseId===courseId&&(!lecture.classId||lecture.classId===classId)).map(lecture=>{
      const latest=detail.handouts.filter(handout=>handout.lectureId===lecture.id).sort((a,b)=>b.version-a.version)[0];
      return {...lecture,handoutId:latest?.id,state:latest?.state};
    });
    const next=nextReviewLesson(lessons,this.data.id,this.data.lectureSequence);
    wx.redirectTo({url:next?.handoutId&&next.state==='draft'
      ?'/pages/review/review?id='+encodeURIComponent(next.handoutId)+'&classId='+encodeURIComponent(classId)+'&courseId='+encodeURIComponent(courseId)
      :'/pages/classroom/classroom?classId='+encodeURIComponent(classId)+'&courseId='+encodeURIComponent(courseId)+'&action=materials'});
  },
  continueReview() { if(this.data.busy||this.data.handout?.state!=='published')return;this.setData({busy:true});return run(async()=>{try{await this.continueAfterPublish()}finally{this.setData({busy:false})}}) },
  publish() { if(this.data.busy)return;return run(async () => { if (!this.data.handout || !await confirm('发布后教师即可登记错题。讲义内容将锁定，后续修改需上传新版本。')) return; this.setData({ busy: true }); try { await api('/handouts/' + this.data.id + '/publish', 'POST', { revision: this.data.handout.revision }); await this.load(); await this.continueAfterPublish() } finally { this.setData({ busy: false }) } }) }
})
