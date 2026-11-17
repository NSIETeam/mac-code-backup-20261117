export const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const labels = {preparing:'生成后自动打印',queued:'排队中',processing:'处理中',review:'待划分讲次',draft:'待校对',published:'已发布',split:'已拆分',splitting:'正在拆题',ready:'已生成',failed:'失败',claimed:'设备已接单',submitting:'正在提交',submitted:'已提交打印机',uncertain:'结果待核实',correct:'正确',wrong:'错误',unfinished:'未完成',unmarked:'未批改',approved:'已确认'};
export function canVerifyPdfIntegrity(question) {
  const reviewable = new Set(['pdf-source-review','pdf-number-gap','pdf-duplicate-number','pdf-cross-page','pdf-multiple-labels','pdf-contextual-label','pdf-formula-review']);
  return question.previews.length > 0 && question.issues.some(i => reviewable.has(i.code)) &&
    !question.issues.some(i => i.blocking && !reviewable.has(i.code));
}
export function classCourses(workspace, classId) {
  const ids = new Set(workspace.assignments.filter(a => a.classId === classId).map(a => a.courseId));
  return workspace.courses.filter(c => ids.has(c.id));
}
export function marksFor(detail, studentId) {
  const ids = new Set(detail.questions.map(q => q.id));
  const saved = detail.marks?.find(x => x.studentId === studentId)?.states || {};
  const marks = Object.fromEntries(Object.entries(saved).filter(([id]) => ids.has(id)));
  detail.wrong.filter(w => w.studentId === studentId && ids.has(w.questionId)).forEach(w => { marks[w.questionId] = 'wrong'; });
  return marks;
}
export function gradingPayload(detail, studentId, marks) {
  if (!detail.classId || !detail.students.some(s => s.id === studentId)) throw Error('请选择当前班级学生');
  const ids = new Set(detail.questions.map(q => q.id));
  if (Object.entries(marks).some(([id,state]) => !ids.has(id) || !['correct','wrong','unfinished'].includes(state))) throw Error('批改范围无效');
  return {handoutId:detail.handout.id,classId:detail.classId,studentIds:[studentId],questionIds:Object.keys(marks).filter(id => marks[id] === 'wrong'),mode:'replace',marks};
}
export function printPayload(documentIds, printer, copies, duplex, requestKey) {
  const ids = [...new Set(documentIds)];
  if (!ids.length || !printer) throw Error('请选择文档和打印机');
  if (!Number.isInteger(copies) || copies < 1 || copies > 99) throw Error('份数需为1–99');
  if (duplex && !printer.duplex) throw Error('该打印机不支持双面');
  return {documentIds:ids,printerId:printer.id,copies,duplex,requestKey};
}
export function safeResource(value, base) {
  const url = new URL(value, base), origin = new URL(base);
  if (url.origin !== origin.origin || !['http:','https:'].includes(url.protocol)) throw Error('文件来源无效，请刷新后重试');
  return url.href;
}
