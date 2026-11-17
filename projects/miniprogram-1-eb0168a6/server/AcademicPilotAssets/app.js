'use strict';

(() => {
  const TOKEN_KEY = 'siyue.academic-pilot.session.v1';
  const INTENT_PREFIX = 'siyue.academic-pilot.manual-send.v1.';
  const base = location.pathname.replace(/\/academic-pilot(?:\/.*)?$/, '').replace(/\/$/, '');
  const apiRoot = `${base}/api/academic-pilot`;
  const entry = new URLSearchParams(location.search);
  const entryClassId = entry.get('classId') || '';
  const teacherEntry = isTeacherRollCallEntry(location.search);
  const trainingEntry = entry.get('module') === 'training';
  function isTeacherRollCallEntry(search) { const params=new URLSearchParams(search); return !!params.get('classId') && params.get('module')==='attendance'; }
  let attendanceDialog = null;
  let trainingDialog = null;
  const $ = id => document.getElementById(id);
  const state = { token: readStorage(TOKEN_KEY), me: null, sessions: [], session: null, students: [], student: null, draft: null, messageRows: [], epoch: 0, dirty: false, feedbackDirty: false, communicationDirty: new Set(), moduleDirty: new Set(), module: entry.get('module') === 'attendance' ? 'attendance' : entry.get('module') === 'collections' ? 'collections' : 'training', messageMode: false, busy: false };
  const statusNames = { draft: '待教务审核', approved: '已审核 · 待沟通', communicated: '已登记沟通', returned: '已退回' };
  let toastTimer;

  function readStorage(key) { try { return sessionStorage.getItem(key) || ''; } catch { return ''; } }
  function writeStorage(key, value) { try { sessionStorage.setItem(key, value); } catch { /* Session continues in memory. */ } }
  function removeStorage(key) { try { sessionStorage.removeItem(key); } catch { /* Nothing persisted. */ } }
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function append(parent, ...children) { for (const child of children) if (child) parent.append(child); return parent; }
  function button(text, kind, handler) {
    const node = el('button', kind, text); node.type = 'button';
    node.addEventListener('click', handler); return node;
  }
  function field(title, input) { return append(el('label', 'field', title), input); }
  function formatDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.valueOf()) ? String(value) : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
  }
  function showError(message, login = false) {
    const node = $(login ? 'login-error' : 'app-error');
    node.textContent = message || ''; node.hidden = !message;
  }
  function toast(message) {
    clearTimeout(toastTimer); $('toast').textContent = message; $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4500);
  }
  function clearPrivateView() {
    closeAttendanceDialog();closeTrainingDialog();state.trainingClass=null;state.trainingClasses=[];state.trainingOverview=false;
    state.epoch++; state.me = null; state.sessions = []; state.session = null;
    state.students = []; state.student = null; state.draft = null; state.messageRows = []; resetDirty();
    $('app-view').hidden = true; $('login-view').hidden = false;
    $('detail-panel').replaceChildren(); $('student-list').replaceChildren(); $('module-title').replaceChildren(); $('module-nav').replaceChildren(); state.messageMode = false;
    $('session-select').replaceChildren();
    $('account-name').textContent = ''; $('toast').hidden = true;
    showError(''); document.title = '思跃 · 限定试点';
  }
  function logout(message = '') {
    removeStorage(TOKEN_KEY); state.token = ''; clearPrivateView();
    $('login-password').value = ''; showError(message, true);
  }
  async function request(path, options = {}) {
    const token = state.token;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetch(path, {
        method: options.method || 'GET', cache: 'no-store', credentials: 'same-origin',
        headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: controller.signal
      });
      const text = await response.text(); let data = null;
      if (text) { try { data = JSON.parse(text); } catch { /* HTML error responses are not rendered. */ } }
      if (!response.ok) {
        if (response.status === 401 && token && state.token === token) logout('登录已失效，请重新登录。');
        if (response.status === 404 && token && state.token === token && path.startsWith(`${apiRoot}/`)) {
          if (path === `${apiRoot}/me`) logout('当前账号已不在试点开放范围内。');
          else {
            // An absent object is not a revoked account. Recheck access directly,
            // without using request(), so a 404 response cannot recurse.
            try {
              const access = await fetch(`${apiRoot}/me`, { cache: 'no-store', credentials: 'same-origin', headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
              if (state.token === token && access.status === 404) logout('当前账号已不在试点开放范围内。');
              if (state.token === token && access.status === 401) logout('登录已失效，请重新登录。');
            } catch { /* A failed recheck does not establish revoked access. */ }
          }
        }
        const error = new Error(response.status === 428 ? '请先在原工作台完成首次密码修改，再进入试点。' : data?.message || `暂时无法完成操作（${response.status}），请稍后重试。`);
        error.status = response.status; throw error;
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求超时。操作结果可能已保存，请先刷新核对，不要重复确认发送。');
      if (error instanceof TypeError) throw new Error('连接暂时中断，请检查网络后重试。');
      throw error;
    } finally { clearTimeout(timeout); }
  }
  function api(path, options) { return request(apiRoot + path, options); }
  function mayLeave() {
    if (state.busy) { toast('正在处理，请稍候。'); return false; }
    return !state.dirty || window.confirm('当前页面有未保存的修改。确定放弃修改并离开吗？');
  }
  async function action(operation, trigger) {
    if (state.busy) return;
    const controls = Array.from($('app-view').querySelectorAll('button,input,textarea,select')).map(node => [node, node.disabled]);
    state.busy = true; controls.forEach(([node]) => { node.disabled = true; }); showError('');
    try { await operation(); }
    catch (error) { if (state.me) showError(error.message); }
    finally { state.busy = false; controls.forEach(([node, disabled]) => { if (node.isConnected) node.disabled = disabled; }); }
  }
  function resetDirty() { state.dirty = false; state.feedbackDirty = false; state.communicationDirty.clear(); state.moduleDirty.clear(); }
  function syncDirty() { state.dirty = state.feedbackDirty || state.communicationDirty.size > 0 || state.moduleDirty.size > 0; }
  function emptyDetail(title = '选择学生', text = '') {
    const card = el('div', 'empty-panel panel');
    append(card, el('h2', '', title));
    if (text) card.append(el('p', '', text));
    $('detail-panel').replaceChildren(card); return card;
  }
  async function enter() {
    const me = await api('/me'); state.me = me;
    writeStorage(TOKEN_KEY, state.token);
    $('login-view').hidden = true; $('app-view').hidden = false;
    $('account-name').textContent = (me.name || '试点成员')+(base.endsWith('/academic-lab')?' · 隔离验收副本':'');
    document.title = teacherEntry ? '思跃 · 点名 Beta' : trainingEntry ? '思跃 · 强化训练' : '思跃 · 教务 Beta';
    document.body.classList.toggle('teacher-rollcall',teacherEntry);
    $('app-heading').replaceChildren(el('span','',teacherEntry?'点名':trainingEntry?'强化训练':'教务工作台'),el('span','beta-tag','Beta'));
    renderNavigation(); showError('', true);
    if(state.module==='training')await loadModule();else await loadSessions();
  }
  async function loadSessions(preferredId) {
    const epoch = ++state.epoch;
    const sessions = await api('/sessions' + (entryClassId ? `?classId=${encodeURIComponent(entryClassId)}` : ''));
    if (epoch !== state.epoch || !state.me) return;
    state.sessions = (Array.isArray(sessions) ? sessions : sessions?.items || []).filter(row => !entryClassId || row.classId === entryClassId);
    const select = $('session-select'); select.replaceChildren();
    for (const session of state.sessions) {
      const option = el('option', '', `${session.lessonDate || ''} · ${session.className || ''} · ${session.title || session.courseName || ''}`);
      option.value = session.id; select.append(option);
    }
    if (!state.sessions.length) {
      const option = el('option', '', '暂无可查看的课堂记录'); option.value = ''; select.append(option);
      state.session = null; state.students = []; state.student = null; state.draft = null; resetDirty();
      renderStudents();
      emptyDetail(entryClassId ? '这个班级暂无课堂记录' : '暂无课堂记录', entryClassId ? '先在记录错题中建立课次，再点名。' : ''); return;
    }
    const id = state.sessions.some(x => x.id === preferredId) ? preferredId : state.sessions[0].id;
    select.value = id; await loadSession(id);
  }
  async function loadSession(id, preferredStudentId) {
    const epoch = ++state.epoch;
    resetDirty(); state.student = null; state.draft = null; state.students = []; renderStudents();
    emptyDetail('正在读取…');
    const data = await api(`/sessions/${encodeURIComponent(id)}`);
    if (epoch !== state.epoch || !state.me) return;
    state.session = { ...(state.sessions.find(x => x.id === id) || {}), ...(data.session || {}) };
    state.students = data.students || [];
    renderStudents();
    if (state.module === 'feedback') {
      const student = state.students.find(x => x.studentId === preferredStudentId);
      if (student) await selectStudent(student); else emptyDetail();
    } else await loadModule();
  }
  function renderStudents() {
    const list = $('student-list'); list.replaceChildren();
    for (const student of state.students) {
      const item = button('', `student-button${state.student?.studentId === student.studentId ? ' selected' : ''}`, async () => {
        if (!mayLeave()) return;
        showError(''); try { await selectStudent(student); } catch (error) { if (state.me) showError(error.message); }
      });
      item.setAttribute('aria-pressed', String(state.student?.studentId === student.studentId));
      append(item, append(el('span'), el('span', 'student-name', student.name), el('span', 'student-caption', !student.reviewed ? '等待教师批改' : statusNames[student.state] || '可以准备反馈')), el('span', 'student-count-label', student.reviewed ? `${student.wrongCount ?? 0} 道错题` : '待批改'));
      list.append(item);
    }
    if (!state.students.length) list.append(el('p', 'list-empty', '本课次暂无学生记录。'));
  }
  async function selectStudent(student) {
    const epoch = ++state.epoch;
    state.messageMode = false; state.student = student; state.draft = null; resetDirty(); renderStudents();
    if (student.feedbackId) { await loadDraft(student.feedbackId, epoch); return; }
    if (!student.reviewed) { emptyDetail(`${student.name} · 等待教师批改`); return; }
    const card = emptyDetail(student.name, `本课次已登记 ${student.wrongCount ?? 0} 道错题`);
    const create = button('生成反馈草稿', 'primary', () => action(async () => {
      const result = await api('/drafts', { method: 'POST', body: { classSessionId: state.session.id, studentId: student.studentId } });
      if (epoch !== state.epoch) return;
      student.feedbackId = result.id; student.state = 'draft'; renderStudents(); await loadDraft(result.id, epoch);
    }, create));
    card.append(create);
  }
  function rememberEdits(includeFeedback = true, skipCommunicationId = null) {
    const edits = { feedback: null, communications: [] };
    if (includeFeedback && state.feedbackDirty) edits.feedback = { body: $('feedback-body')?.value, recipient: $('feedback-recipient')?.value, channel: $('feedback-channel')?.value };
    for (const card of $('detail-panel').querySelectorAll('[data-communication-id]')) {
      const id = card.dataset.communicationId;
      if (id !== skipCommunicationId && state.communicationDirty.has(id)) edits.communications.push({ id, note: card.querySelector('textarea').value, status: card.querySelector('select').value, date: card.querySelector('input[type=date]').value });
    }
    return edits;
  }
  function restoreEdits(edits) {
    if (!edits) return;
    if (edits.feedback) {
      $('feedback-body').value = edits.feedback.body; $('feedback-recipient').value = edits.feedback.recipient; if ($('feedback-channel') && edits.feedback.channel) $('feedback-channel').value = edits.feedback.channel;
      $('feedback-body').dispatchEvent(new Event('input'));
    }
    for (const old of edits.communications) {
      const card = Array.from($('detail-panel').querySelectorAll('[data-communication-id]')).find(x => x.dataset.communicationId === old.id);
      if (!card) continue;
      card.querySelector('textarea').value = old.note; card.querySelector('select').value = old.status; card.querySelector('input[type=date]').value = old.date;
      card.querySelector('textarea').dispatchEvent(new Event('input'));
    }
  }
  async function loadDraft(id, expectedEpoch = state.epoch, edits = null) {
    const draft = await api(`${state.messageMode ? '/messages' : '/drafts'}/${encodeURIComponent(id)}`);
    if (expectedEpoch !== state.epoch || !state.me) return;
    if (draft.editorKind === 'feedback') state.messageMode = false;
    state.draft = draft; resetDirty();
    if (!state.messageMode) {
      const studentId = draft.studentId || draft.source?.studentId || state.student?.studentId;
      const student = state.students.find(x => x.studentId === studentId);
      if (student) { student.feedbackId = draft.id; student.state = draft.state; }
      if (state.student?.studentId === studentId) { state.student.feedbackId = draft.id; state.student.state = draft.state; }
    }
    renderStudents(); renderDraft(); restoreEdits(edits);
  }
  function renderDraft() {
    const d = state.draft; if (!d) return;
    const resource = state.messageMode ? 'messages' : 'drafts';
    const s = d.source || {};
    if (state.module !== 'feedback') $('roster-panel').hidden = true;
    const detail = $('detail-panel'); detail.replaceChildren();
    const head = el('section', 'panel');
    append(head, append(el('div', 'detail-header'), append(el('div'), el('h2', 'student-title', state.messageMode ? (d.title || messageKinds[d.kind] || '家长沟通') : s.studentName || state.student?.name || '学生反馈'), el('p', 'detail-meta', [s.className, s.courseName, s.lessonDate, s.lectureTitle].filter(Boolean).join(' · '))), el('span', `status-badge ${d.state}`, statusNames[d.state] || '待处理')));
    const approved = ['approved', 'communicated'].includes(d.state);
    const canHandoff = d.state === 'approved';
    if (['review', 'communication'].includes(state.module)) detail.append(button('返回列表', 'text-button review-back', () => { if (mayLeave()) runNavigation(() => loadModule()); }));
    detail.append(head);
    if (d.state === 'draft' && (d.returnReason || d.reviewNote)) head.append(el('p', 'returned-note', `退回原因：${d.returnReason || d.reviewNote}`));
    if (d.sourceStale) {
      const warning = el('div', 'notice', '依据已更新，请重建草稿并重新审核。');
      const refresh = button('更新依据并重建草稿', '', () => action(async () => {
        if (!window.confirm('将根据最新记录重新生成草稿，当前文案和审核状态会被重置。已有沟通记录会保留。继续吗？')) return;
        const edits = rememberEdits(false);
        await api(`/${resource}/${d.id}/refresh`, { method: 'POST', body: { revision: d.revision } });
        await loadDraft(d.id, state.epoch, edits); toast('已更新依据，请重新核对反馈。');
      }, refresh));
      warning.append(refresh); detail.append(warning);
    }
    const grid = el('div', 'content-grid');
    const evidence = el('section', 'panel evidence-panel');
    evidence.append(el('h3', '', '反馈依据'));
    const items = el('div', 'evidence-items');
    const operations = s.operations || {};
    const attendanceLabels = { unrecorded: '未登记', present: '出勤', late: '迟到', leave: '请假', absent: '缺勤', transfer: '临时调班级' };
    const homeworkLabels = { unrecorded: '未登记', missing: '未交', submitted: '已交待批', reviewed: '已批改' };
    if (operations.attendanceStatus || operations.attendanceNote) {
      const attendance = append(el('article', 'evidence-item'), el('strong', '', '考勤'), el('span', '', attendanceLabels[operations.attendanceStatus] || '未登记'));
      if (operations.attendanceNote) attendance.append(el('p', '', operations.attendanceNote));
      items.append(attendance);
    }
    if (operations.homeworkTitle) {
      const homework = append(el('article', 'evidence-item'), el('strong', '', `作业《${operations.homeworkTitle}》`), el('span', '', homeworkLabels[operations.homeworkStatus] || '未登记'));
      if (operations.homeworkNote) homework.append(el('p', '', operations.homeworkNote));
      items.append(homework);
    }
    const practiceByQuestion = new Map((s.practice || []).map(record => [record.questionId, record]));
    for (const q of s.questions || []) {
      const item = append(el('article', 'evidence-item'), el('strong', '', q.label || '题目'));
      if (q.note) item.append(el('p', '', `教师备注：${q.note}`));
      const practice = practiceByQuestion.get(q.id);
      if (practice) {
        item.append(el('span', 'retest', practice.corrected ? '已标记订正' : '未标记订正'));
        if (practice.note) item.append(el('p', '', `练习记录：${practice.note}`));
      }
      const labels = { correct: '复测正确', wrong: '复测仍有错误', unfinished: '复测未完成' };
      item.append(el('span', 'retest', labels[q.retestState] || '暂无复测'));
      if (q.retestAt) item.append(el('p', '', formatDate(q.retestAt)));
      items.append(item);
    }
    if (!(s.questions || []).length) items.append(el('p', 'section-note', '已批改，未登记错题。'));
    evidence.append(items);
    const editor = el('section', 'panel editor-panel');
    const recipient = el('input'); recipient.id = 'feedback-recipient'; recipient.value = d.recipientLabel || ''; recipient.maxLength = 120; recipient.placeholder = state.messageMode ? '填写已核实的家长或群名称' : '核实后填写，例如：学生妈妈'; recipient.autocomplete = 'off';
    const body = el('textarea', 'feedback-body'); body.id = 'feedback-body'; body.value = d.body || ''; body.maxLength = 8000; body.spellcheck = false;
    const channel = selectInput([['wecom_private', '企业微信私聊'], ['wecom_group', '企业微信群']], d.channel || 'wecom_private');
    channel.id = 'feedback-channel';
    const channelLocked = !state.messageMode || d.kind === 'learning_feedback'; channel.disabled = channelLocked;
    append(editor, append(el('div', 'form-grid'), field(state.messageMode ? '接收人／群名称' : '接收家长', recipient), field('沟通渠道', channel)), field('内容', body));
    const dirtyNote = el('span', '', '已保存');
    append(editor, append(el('div', 'editor-toolbar'), dirtyNote));
    const reviewArea = el('div', 'review-area');
    const approvedNote = approved ? el('p', 'approved-note', `审核：${d.approvedByName || '教务'}${d.approvedAt ? ` · ${formatDate(d.approvedAt)}` : ''}`) : null;
    if (approvedNote) reviewArea.append(approvedNote);
    const reviewCheck = el('input'); reviewCheck.type = 'checkbox'; reviewCheck.id = 'review-confirm';
    const checkLabel = append(el('label', 'check-label'), reviewCheck, el('span', '', '我已核对事实、接收对象和内容。'));
    const approvalButton = button('确认审核通过', 'primary', () => action(async () => {
      if (!reviewCheck.checked || state.feedbackDirty || !recipient.value.trim()) return;
      if (!window.confirm(`确认审核这份发给「${recipient.value.trim()}」的内容？审核通过后仍需由教务实际发送。`)) return;
      const edits = rememberEdits(false);
      await api(`/${resource}/${d.id}/approve`, { method: 'POST', body: { revision: d.revision, confirmed: true } });
      await loadDraft(d.id, state.epoch, edits); toast('审核通过，可以准备家长沟通。');
    }, approvalButton));
    approvalButton.disabled = true;
    if (state.me.canReview && !approved && !d.sourceStale) {
      append(reviewArea, checkLabel, append(el('div', 'action-row'), approvalButton));
    } else if (!state.me.canReview) reviewArea.append(el('p', 'review-note', '等待教务审核。'));
    const handoff = el('div', 'handoff-section');
    const copy = button('复制已审核内容', 'mint-button', () => action(async () => {
      const approvedCopy = await api(`/${resource}/${d.id}/handoff`);
      if (state.feedbackDirty || approvedCopy.revision !== d.revision || approvedCopy.body !== body.value || approvedCopy.recipientLabel !== recipient.value || (approvedCopy.channel && approvedCopy.channel !== channel.value)) throw new Error('反馈版本已变化，请刷新核对后再复制。');
      try {
        if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
        await navigator.clipboard.writeText(approvedCopy.body);
        toast('已复制。请核对家长后，在企业微信中发送。');
      } catch {
        body.focus(); body.select(); toast('浏览器未允许自动复制，已选中已审核内容，请手动复制。');
      }
    }, copy));
    const sent = button('我已在企业微信发送', 'secondary', () => action(async () => {
      if (!window.confirm(`请确认：你已将这份已审核的内容实际发送给「${d.recipientLabel}」。此操作只登记结果，不会代你发送消息。`)) return;
      const edits = rememberEdits(false);
      const intentKey = `${INTENT_PREFIX}${d.id}.${d.revision}`;
      let requestKey = readStorage(intentKey);
      if (!requestKey) { requestKey = crypto.randomUUID(); writeStorage(intentKey, requestKey); }
      await api(`/${resource}/${d.id}/communications`, { method: 'POST', body: { revision: d.revision, requestKey, confirmed: true } });
      await loadDraft(d.id, state.epoch, edits); toast('已登记为人工确认发送，可继续记录家长回复。');
    }, sent));
    append(handoff, append(el('div', 'action-row'), copy, sent));
    const save = button('保存修改', 'secondary', () => action(async () => {
      if (!body.value.trim()) throw new Error('请填写内容。');
      const edits = rememberEdits(false);
      await api(`/${resource}/${d.id}`, { method: 'PUT', body: { revision: d.revision, body: body.value, recipientLabel: recipient.value.trim(), channel: channel.value } });
      await loadDraft(d.id, state.epoch, edits); toast('修改已保存，请核对后审核。');
    }, save));
    const returnButton = button('退回修改', 'secondary', () => action(async () => {
      if (state.feedbackDirty) throw new Error('请先保存修改。');
      const reason = window.prompt('请输入退回原因：'); if (reason === null) return; if (!reason.trim()) throw new Error('退回原因不能为空。'); if (reason.trim().length > 1000) throw new Error('退回原因不能超过 1000 字。');
      await api(`/${resource}/${d.id}/return`, {method:'POST', body:{revision:d.revision, reason:reason.trim()}});
      await loadDraft(d.id, state.epoch, rememberEdits(false)); toast('已退回修改。');
    }));
    const editorActions = append(el('div', 'action-row'), save);
    if (state.me.canReview && d.state !== 'communicated') editorActions.append(returnButton);
    append(editor, editorActions, reviewArea);
    if (canHandoff && !d.sourceStale) editor.append(handoff);
    function formChanged() {
      state.feedbackDirty = body.value !== (d.body || '') || recipient.value !== (d.recipientLabel || '') || channel.value !== (d.channel || 'wecom_private'); syncDirty();
      dirtyNote.textContent = state.feedbackDirty ? '有未保存的修改' : '已保存'; dirtyNote.className = state.feedbackDirty ? 'dirty' : '';
      save.disabled = !state.feedbackDirty;
      approvalButton.disabled = state.feedbackDirty || !reviewCheck.checked || !recipient.value.trim();
      handoff.hidden = !canHandoff || d.sourceStale || state.feedbackDirty;
      if (approvedNote) approvedNote.hidden = state.feedbackDirty;
    }
    body.addEventListener('input', formChanged); recipient.addEventListener('input', formChanged); channel.addEventListener('change', formChanged); reviewCheck.addEventListener('change', formChanged); formChanged();
    if (!state.messageMode) grid.append(evidence);
    grid.append(editor); detail.append(grid);
    if ((d.communications || []).length) {
      const history = el('details', 'panel history-panel');
      history.append(el('summary', '', `沟通记录（${d.communications.length}）`));
      const historyList = el('div', 'history-list');
      for (const communication of d.communications) historyList.append(renderCommunication(communication, d.id));
      history.append(historyList); detail.append(history);
    }
    if ((d.legacyDrafts || []).length) {
      const history = el('details', 'panel history-panel');
      history.append(el('summary', '', `合并前版本（${d.legacyDrafts.length}）`));
      const versions = el('div', 'history-list');
      for (const old of d.legacyDrafts) {
        const version = el('article', 'communication-card');
        append(version, append(el('div', 'communication-top'), el('strong', '', old.title || '家长沟通草稿'), el('span', '', `原稿只读 · ${formatDate(old.updatedAt)}`)), el('p', 'section-note', [old.recipientLabel || '接收对象未填写', statusNames[old.state] || old.state].filter(Boolean).join(' · ')), el('p', 'sent-content', old.body || ''));
        for (const communication of old.communications || []) version.append(renderCommunication({ ...communication, editorKind: 'message' }, d.id));
        versions.append(version);
      }
      history.append(versions); detail.append(history);
    }
  }
  function renderCommunication(c, draftId) {
    const communicationResource = (c.editorKind || (state.messageMode ? 'message' : 'feedback')) === 'message' ? 'message-communications' : 'communications';
    const card = el('article', 'communication-card');
    card.dataset.communicationId = c.id;
    append(card, append(el('div', 'communication-top'), el('strong', '', c.recipientLabel || '家长沟通'), el('span', '', `人工确认已发送 · ${formatDate(c.confirmedAt)}`)));
    append(card, append(el('details'), el('summary', '', '查看当时发送的内容'), el('p', 'sent-content', c.body || '')));
    const note = el('textarea'); note.value = c.responseNote || ''; note.maxLength = 4000; note.placeholder = '记录家长实际反馈、约定的下一步和负责人。';
    const status = el('select');
    for (const [value, label] of [['awaiting_reply', '等待家长回复'], ['follow_up', '需要继续跟进'], ['resolved', '已完成沟通与处理']]) {
      const option = el('option', '', label); option.value = value; status.append(option);
    }
    status.value = c.status || 'awaiting_reply';
    const date = el('input'); date.type = 'date'; date.value = c.followUpOn ? String(c.followUpOn).slice(0, 10) : '';
    append(card, field('家长反馈与跟进说明', note), append(el('div', 'followup-grid'), field('当前状态', status), field('下次跟进日期', date)));
    const save = button('保存沟通记录', 'secondary', () => action(async () => {
      const edits = rememberEdits(true, c.id);
      await api(`/${communicationResource}/${c.id}`, { method: 'PUT', body: { revision: c.revision, responseNote: note.value.trim(), followUpOn: date.value || null, status: status.value } });
      await loadDraft(draftId, state.epoch, edits); toast('沟通记录已更新。');
    }, save));
    function changed() {
      const dirty = note.value !== (c.responseNote || '') || status.value !== (c.status || 'awaiting_reply') || date.value !== (c.followUpOn ? String(c.followUpOn).slice(0, 10) : '');
      if (dirty) state.communicationDirty.add(c.id); else state.communicationDirty.delete(c.id);
      syncDirty();
    }
    note.addEventListener('input', changed); status.addEventListener('change', changed); date.addEventListener('change', changed);
    card.append(save); return card;
  }


  const modules = [['training','强化训练'],['preparation','课前准备'],['attendance','点名'],['collections','收作业与批改'],['homework','作业管理'],['wrong','错题管理'],['feedback','学情反馈'],['review','教务审核'],['communication','家长沟通'],['followup','后续跟进'],['statistics','教务统计']];
  const messageKinds = {class_notice:'班级通知',homework_reminder:'作业提醒',learning_feedback:'学习反馈'};
  function renderNavigation() {
    const nav = $('module-nav'); nav.replaceChildren(); nav.hidden=teacherEntry||trainingEntry;
    for (const [id, title] of (teacherEntry||trainingEntry ? [] : modules)) {
      const item = button('', state.module === id ? 'active' : '', () => { if (mayLeave()) runNavigation(() => switchModule(id)); });
      append(item, el('span','',title), el('span','beta-tag','Beta')); item.setAttribute('aria-current', state.module === id ? 'page' : 'false'); nav.append(item);
    }
    $('module-title').replaceChildren(el('span','',modules.find(x => x[0] === state.module)?.[1] || ''), el('span','beta-tag','Beta'));
    if($('session-select').parentElement)$('session-select').parentElement.hidden=['training','collections'].includes(state.module);
    $('roster-panel').hidden = state.module !== 'feedback'; $('work-grid').classList.toggle('full-width', state.module !== 'feedback');
  }
  async function runNavigation(operation) { showError(''); try { await operation(); } catch(error) { if(state.me) showError(error.message); } }
  async function switchModule(id, target = {}) {
    state.module = id; state.messageMode = false; state.student = null; state.draft = null; resetDirty(); renderNavigation();
    if (target.classSessionId && state.session?.id !== target.classSessionId) { $('session-select').value = target.classSessionId; await loadSession(target.classSessionId); }
    else await loadModule(target);
  }
  async function loadModule(target = {}) {
    const epoch = ++state.epoch; resetDirty(); state.messageMode = false; state.draft = null; renderNavigation();
    if(state.module==='training'){await loadTraining(epoch);return;}
    if(state.module==='collections'){await window.SiyueHomework.render({host:$('detail-panel'),api,workspace:()=>request(base+'/api/workspace'),canReview:state.me.canReview,current:()=>current(epoch),photo:async id=>{const r=await fetch(apiRoot+'/collection-photos/'+id,{headers:{Authorization:'Bearer '+state.token}});if(!r.ok)throw Error('照片读取失败');return URL.createObjectURL(await r.blob());}});return;}
    if (!state.session && state.module !== 'statistics') { emptyDetail('暂无课堂记录'); return; }
    emptyDetail('正在读取…');
    const loaders = { preparation:loadPreparation, attendance:loadAttendance, homework:loadHomework, wrong:loadWrong, review:loadReviews, communication:loadMessages, followup:loadFollowup, statistics:loadStatistics };
    if (state.module === 'feedback') {
      renderStudents(); const student = state.students.find(x => x.studentId === target.studentId);
      if (student) await selectStudent(student); else emptyDetail(); return;
    }
    await loaders[state.module](epoch, target);
  }
  function current(epoch) { return state.me && epoch === state.epoch; }
  function sessionPath(suffix) { return `/sessions/${encodeURIComponent(state.session.id)}/${suffix}`; }
  function query(values) { return new URLSearchParams(Object.entries(values).filter(([,v]) => v !== null && v !== undefined && v !== '')).toString(); }
  function selectInput(options, value) { const node = el('select'); for (const [v,label] of options) { const o = el('option','',label); o.value=v; node.append(o); } node.value=value; return node; }
  function textInput(value='', type='text', max=120) { const node=el('input'); node.type=type; node.value=value || ''; node.maxLength=max; return node; }
  function noteInput(value='', max=4000) { const node=el('textarea'); node.value=value || ''; node.maxLength=max; return node; }
  function checkbox(label, checked) { const input=el('input'); input.type='checkbox'; input.checked=Boolean(checked); return {input,node:append(el('label','inline-check'),input,el('span','',label))}; }
  function panel(title) { const node=el('section','panel module-panel'); if(title) node.append(el('h2','',title)); return node; }
  function table(headers) { const wrap=el('div','table-scroll'); const node=el('table','data-table'); const head=el('tr'); headers.forEach(x=>head.append(el('th','',x))); const body=el('tbody'); append(node,append(el('thead'),head),body); wrap.append(node); return {wrap,body}; }
  function cell(node, cls='') { const td=el('td',cls); if(node instanceof Node) td.append(node); else td.textContent=String(node ?? ''); return td; }
  function watchForm(container,key,read) {
    let original=JSON.stringify(read());
    const update=()=>{ if(JSON.stringify(read())!==original) state.moduleDirty.add(key); else state.moduleDirty.delete(key); syncDirty(); };
    container.addEventListener('input',update); container.addEventListener('change',update);
    return ()=>{original=JSON.stringify(read());state.moduleDirty.delete(key);syncDirty();};
  }
  function facts(items) { const row=el('div','fact-list'); for(const [label,value] of items) row.append(append(el('span'),el('span','',`${label} `),el('strong','',value ?? '—'))); return row; }
  function adoptRevision(target, fresh) { if (!fresh) throw new Error('保存后未能取得最新记录，请刷新核对。'); Object.assign(target,fresh); }
  async function loadPreparation(epoch) {
    const path=sessionPath('preparation'), data=await api(path); if(!current(epoch))return;
    const card=panel(); card.append(facts([['讲义',data.title || '未关联'],['学生',data.studentCount],['题目确认',`${data.confirmedQuestions ?? 0}/${data.totalQuestions ?? 0}`],['讲义状态',({published:'已发布',draft:'草稿',ready:'已就绪',confirmed:'已确认',pending:'待确认',missing:'未关联',processing:'处理中'})[data.handoutState] || data.handoutState || '—']]));
    if(data.lessonPlans?.length) card.append(el('p','section-note',`教学计划：${data.lessonPlans.map(x=>x.title).join('、')}`));
    if(data.missing?.length) card.append(el('p','notice',data.missing.join('；')));
    const form=el('div','form-section'); const materials=checkbox('资料已备齐',data.materialsReady), roster=checkbox('学生名单已核对',data.rosterChecked), homework=checkbox('课后作业已安排',data.homeworkPlanned), note=noteInput(data.note,2000);
    append(form,append(el('div','compact-fields'),materials.node,roster.node,homework.node),field('准备备注',note));
    const read=()=>({materialsReady:materials.input.checked,rosterChecked:roster.input.checked,homeworkPlanned:homework.input.checked,note:note.value.trim()}); const saved=watchForm(form,'preparation',read);
    form.append(button('保存准备记录','primary',()=>action(async()=>{await api(path,{method:'PUT',body:{revision:data.revision,...read()}}); adoptRevision(data,await api(path)); saved(); await loadModule(); toast('准备记录已保存。');})));
    card.append(form); $('detail-panel').replaceChildren(card);
  }
  function closeAttendanceDialog() {
    if (!attendanceDialog) return;
    attendanceDialog.close(); attendanceDialog.remove(); attendanceDialog = null;
  }
  async function loadAttendance(epoch) {
    closeAttendanceDialog();
    const path=sessionPath('roll-call'), data=await api(path); if(!current(epoch))return;
    renderRollCall(data, path, epoch);
  }
  function renderRollCall(data, path, epoch) {
    const rows=data.students || [], classes=data.transferClasses || [], exceptions=new Map(), unresolved=new Set();
    for(const row of rows) {
      if(row.status==='leave')exceptions.set(row.studentId,{studentId:row.studentId,status:'leave'});
      else if(row.status==='transfer')exceptions.set(row.studentId,{studentId:row.studentId,status:'transfer',transferClassId:row.transferClassId,transferOn:row.transferOn});
      else if(['late','absent'].includes(row.status))unresolved.add(row.studentId);
    }
    const savedSelection=JSON.stringify([...exceptions.values()]);
    const card=panel(), list=el('div','rollcall-roster'), summary=el('p','rollcall-summary'), warning=el('p','notice');
    append(card,el('p','section-note','只选异常学生，完成后其余记为出勤。'),list,warning);
    const save=button('完成点名','primary',()=>action(async()=>{
      if(!current(epoch))return;
      if(unresolved.size)throw new Error('请先点击标有“待核对”的学生，确认本次到课情况。');
      const fresh=await api(path,{method:'PUT',body:{snapshot:data.snapshot,confirmed:true,exceptions:[...exceptions.values()]}});
      if(!current(epoch))return;
      resetDirty(); renderRollCall(fresh,path,epoch); toast('点名已保存。');
    }));
    const actions=append(el('div','action-row rollcall-actions'),summary,save);
    if(rows.some(row=>['leave','absent'].includes(row.status)))actions.append(button('补课记录','text-button',()=>{
      if(!mayLeave())return;
      runNavigation(()=>loadAttendanceMakeup(++state.epoch));
    }));
    card.append(actions);
    function update() {
      list.replaceChildren();
      for(const row of rows) {
        const choice=exceptions.get(row.studentId), needsCheck=unresolved.has(row.studentId);
        const label=choice?.status==='leave'?'请假':choice?.status==='transfer'?`临时调班 · ${classes.find(c=>c.id===choice.transferClassId)?.name || row.transferClassName || '班级待核对'} · ${choice.transferOn || '日期待核对'}`:needsCheck?`${row.status==='late'?'迟到':'缺勤'} · 待核对`:row.status==='unrecorded'?'正常 · 待提交':'正常';
        const item=button('',`rollcall-student${choice?' exception':''}${needsCheck?' needs-check':''}`,()=>chooseException(row));
        item.dataset.studentId=row.studentId; item.setAttribute('aria-label',`${row.name}，${label}`);
        item.setAttribute('aria-pressed',String(!!choice));
        append(item,el('strong','',row.name),el('span','',label)); list.append(item);
      }
      const values=[...exceptions.values()], leaves=values.filter(x=>x.status==='leave').length, transfers=values.filter(x=>x.status==='transfer').length;
      summary.textContent=`${rows.length} 人 · 请假 ${leaves} 人 · 临时调班 ${transfers} 人`;
      warning.hidden=!unresolved.size;
      warning.textContent=unresolved.size?'旧记录中有迟到或缺勤，请点击对应学生核对。':'';
      save.disabled=!rows.length || unresolved.size>0;
      if(JSON.stringify(values)!==savedSelection || rows.some(row=>['late','absent'].includes(row.status)&&!unresolved.has(row.studentId)))state.moduleDirty.add('rollcall');
      else state.moduleDirty.delete('rollcall');
      syncDirty();
    }
    function chooseException(row) {
      if(state.busy || !current(epoch))return;
      closeAttendanceDialog();
      const dialog=el('dialog','rollcall-dialog'), title=el('h2','',row.name), content=el('div','rollcall-options');
      title.id='rollcall-dialog-title'; dialog.setAttribute('aria-labelledby',title.id);
      attendanceDialog=dialog;
      function finish(choice) {
        if(!current(epoch)){closeAttendanceDialog();return;}
        if(choice)exceptions.set(row.studentId,choice);else exceptions.delete(row.studentId);
        unresolved.delete(row.studentId);closeAttendanceDialog();update();
      }
      function transferForm() {
        const selected=exceptions.get(row.studentId);
        const target=selectInput([['','选择目标班级'],...classes.map(c=>[c.id,c.name])],selected?.transferClassId || '');
        const date=textInput(selected?.transferOn || state.session.lessonDate,'date');
        const error=el('p','form-error');error.setAttribute('role','alert');
        const confirm=button('确定调班','primary',()=>{
          if(!target.value){error.textContent='请选择目标班级。';target.focus();return;}
          if(!date.value || !date.checkValidity()){error.textContent='请选择有效的上课日期。';date.focus();return;}
          finish({studentId:row.studentId,status:'transfer',transferClassId:target.value,transferOn:date.value});
        });
        content.replaceChildren(field('临时上课班级',target),field('上课日期',date),error,confirm);
        if(!classes.length){confirm.disabled=true;error.textContent='暂无有权限的其他班级。';}
      }
      append(content,button('请假','secondary',()=>finish({studentId:row.studentId,status:'leave'})),button('临时调班级','secondary',transferForm));
      if(exceptions.has(row.studentId)||unresolved.has(row.studentId))content.append(button('恢复正常出勤','text-button',()=>finish(null)));
      append(dialog,title,content,button('取消','text-button',closeAttendanceDialog));
      dialog.addEventListener('cancel',event=>{event.preventDefault();closeAttendanceDialog();});
      $('app-view').append(dialog);dialog.showModal();
    }
    update();
    if(!rows.length)list.append(el('p','empty-inline','本课次暂无学生。'));
    if(data.incomingTransfers?.length) {
      const incoming=el('div','rollcall-incoming');incoming.append(el('h3','', '临时调入'));
      for(const row of data.incomingTransfers)incoming.append(el('p','',`${row.name} · ${row.sourceClassName} · ${row.transferOn}`));
      card.append(incoming);
    }
    $('detail-panel').replaceChildren(card);
  }
  async function loadAttendanceMakeup(epoch) {
    const path=sessionPath('attendance'), records=await api(path);if(!current(epoch))return;
    const card=panel('补课记录'), grid=table(['学生','补课安排','补课日期','备注','']);
    card.append(button('返回点名','text-button',()=>{if(mayLeave())runNavigation(()=>loadModule());}));
    for(const row of records.filter(x=>['leave','absent'].includes(x.status))) {
      const tr=el('tr'),makeup=selectInput([['pending','待安排'],['scheduled','已安排'],['done','已完成']],row.makeupStatus || 'pending'),date=textInput(row.makeupOn?.slice(0,10),'date'),note=noteInput(row.note,2000);
      function availability(){date.disabled=makeup.value==='pending';if(date.disabled)date.value='';}
      makeup.addEventListener('change',availability);availability();
      const read=()=>({status:row.status,makeupStatus:makeup.value,makeupOn:date.value || null,note:note.value.trim()});
      append(tr,cell(row.name,'name-cell'),cell(makeup,'select-cell'),cell(date,'date-cell'),cell(note,'note-cell'));
      const saved=watchForm(tr,`attendance:${row.studentId}`,read);
      tr.append(cell(button('保存','secondary',()=>action(async()=>{if(['scheduled','done'].includes(makeup.value)&&!date.value)throw new Error('请填写补课日期。');if(makeup.value==='done'&&!note.value.trim())throw new Error('请记录补课完成情况。');await api(`${path}/${row.studentId}`,{method:'PUT',body:{revision:row.revision,...read()}});const fresh=await api(path);adoptRevision(row,fresh.find(x=>x.studentId===row.studentId));saved();toast(`${row.name}的补课记录已保存。`);})))) ;grid.body.append(tr);
    }
    card.append(grid.wrap);$('detail-panel').replaceChildren(card);
  }
  async function loadHomework(epoch) {
    const path=sessionPath('homework'), data=await api(path); if(!current(epoch))return;
    const card=panel(), title=textInput(data.title), due=textInput(data.dueOn?.slice(0,10),'date'), requirements=noteInput(data.requirements), config=el('div','form-grid');
    append(config,field('作业名称',title),field('截止日期',due));const reqField=field('作业要求',requirements);reqField.classList.add('wide');config.append(reqField);
    const readConfig=()=>({title:title.value.trim(),dueOn:due.value || null,requirements:requirements.value.trim()}); const savedConfig=watchForm(config,'homework:config',readConfig);
    const save=button(data.id?'保存作业安排':'创建作业安排','primary',()=>action(async()=>{
      if(!title.value.trim())throw new Error('请填写作业名称。');
      if(data.id&&!window.confirm('修改作业安排后，本课次学生的作业状态将重新登记，旧记录保留。确认修改吗？'))return;
      if(state.moduleDirty.size>(state.moduleDirty.has('homework:config')?1:0)&&!window.confirm('表格中还有未保存的作业状态，继续会放弃这些修改。继续吗？'))return;
      await api(path,{method:'PUT',body:{revision:data.revision,...readConfig()}});savedConfig();await loadModule();toast('作业安排已保存。');
    }));append(card,config,append(el('div','action-row'),save));
    const studentCard=panel('学生作业'),grid=table(['学生','作业状态','批改／催交备注','']);
    for(const row of data.students || []) {
      const tr=el('tr'), status=selectInput([['unrecorded','未登记'],['missing','未交'],['submitted','已交待批'],['reviewed','已批改']],row.status), note=noteInput(row.note,2000); status.disabled=!data.id;note.disabled=!data.id;
      const read=()=>({status:status.value,note:note.value.trim()});append(tr,cell(row.name,'name-cell'),cell(status,'select-cell'),cell(note,'note-cell'));const saved=watchForm(tr,`homework:${row.studentId}`,read);
      const saveRow=button('保存','secondary',()=>action(async()=>{if(state.moduleDirty.has('homework:config'))throw new Error('请先保存作业安排。');await api(`${path}/${row.studentId}`,{method:'PUT',body:{revision:row.revision,homeworkRevision:data.revision,...read()}});const fresh=await api(path);adoptRevision(row,fresh.students.find(x=>x.studentId===row.studentId));saved();toast(`${row.name}的作业状态已保存。`);})); saveRow.disabled=!data.id;tr.append(cell(saveRow));grid.body.append(tr);
    }
    if(!data.id)studentCard.append(el('p','section-note','创建作业安排后可登记学生作业。'));studentCard.append(grid.wrap);
    $('detail-panel').replaceChildren(card,studentCard);
  }
  function renderRecommendationMath(container) {
    if(typeof window.renderMathInElement==='function')window.renderMathInElement(container,{delimiters:[{left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false},{left:'\\(',right:'\\)',display:false},{left:'\\[',right:'\\]',display:true}],throwOnError:false,trust:false,strict:'ignore',maxExpand:1000});
  }
  function trainingOptionsKey(info) { return `siyue.training.options.v1.${info.classId}.${info.courseId}`; }
  function readTrainingOptions(info) {
    let saved={};try{saved=JSON.parse(localStorage.getItem(trainingOptionsKey(info)) || '{}') || {};}catch{}
    return {stage:['小学','初中','高中'].includes(saved.stage)?saved.stage:info.suggestedStage || '',subject:info.suggestedSubject || saved.subject || '',perError:2,maxQuestions:12};
  }
  function saveTrainingOptions(info, options) {
    try{localStorage.setItem(trainingOptionsKey(info),JSON.stringify({stage:options.stage,subject:options.subject}));}catch{}
  }
  function closeTrainingDialog() {
    if(trainingDialog){trainingDialog.close();trainingDialog.remove();trainingDialog=null;}
    document.body.classList.remove('training-printing');
  }
  function downloadTraining(name, data) {
    const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json;charset=utf-8'}));
    const link=el('a');link.href=url;link.download=name.replace(/[\\/:*?"<>|]/g,'_')+'.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  function showTrainingPaper(info, plan) {
    closeTrainingDialog();const dialog=el('dialog','training-dialog');trainingDialog=dialog;
    const batch=Array.isArray(plan),plans=batch?plan:[plan],title=batch?`${info.className} · 全班强化训练`:`${plan.name} · 强化训练`;
    const heading=append(el('div','panel-top training-no-print'),el('h2','',title),button('关闭','text-button',closeTrainingDialog));
    const teacher=checkbox('教师版（含答案与推荐依据）',false),paper=el('article','training-paper');
    function renderPaper(){
      paper.replaceChildren();
      const shown=batch?plans.filter(p=>p.questions.length):plans;
      for(const person of shown){
        const sheet=el('section','training-person-sheet'),readingMaterials=new Map();sheet.append(el('h1','',`${person.name}的强化训练`),el('p','section-note',`${info.className} · ${info.courseName} · ${person.questions.length}道题`));
        if(!person.questions.length)sheet.append(el('p','empty-inline',trainingStatus(person)));
        for(const [index,item] of person.questions.entries()){
          const q=item.question,block=el('section','training-paper-question');block.append(el('h3','',`第${index+1}题`));if(q.materialText){const key=q.materialId||q.id;if(readingMaterials.has(key))block.append(el('p','section-note',`阅读材料同本卷材料${readingMaterials.get(key)}`));else{readingMaterials.set(key,readingMaterials.size+1);block.append(el('h4','',`阅读材料${readingMaterials.get(key)}`),el('div','recommendation-stem',q.materialText));}}block.append(el('div','recommendation-stem',q.stem));if(q.diagram)block.append(renderBankDiagram(q.diagram));
          if(teacher.input.checked)block.append(el('p','section-note',`依据：${item.origin.lessonDate} ${item.origin.label}（登记出错${item.origin.occurrences}次）`),el('p','recommendation-reason',item.reasons.join('；')),el('h4','','参考答案'),el('div','recommendation-stem',q.answer),el('h4','','解析'),el('div','recommendation-stem',q.analysis || '题源未提供解析，请教师补充。'));
          block.append(el('p','training-provenance',[q.source,q.license,q.attribution,q.licenseUrl,q.sourceUrl].filter(Boolean).join(' · ')));sheet.append(block);
        }
        paper.append(sheet);
      }
      renderRecommendationMath(paper);
    }
    teacher.input.addEventListener('change',renderPaper);renderPaper();
    const print=button('打印 / 保存PDF','primary',()=>{document.body.classList.add('training-printing');try{window.print();}finally{document.body.classList.remove('training-printing');}});print.disabled=!plans.some(p=>p.questions.length);
    const tools=append(el('div','action-row training-no-print'),teacher.node,print,button('下载训练备份','secondary',()=>downloadTraining(batch?`${info.className}_全班强化训练`:`${info.className}_${plan.name}_强化训练`,{className:info.className,courseName:info.courseName,students:plans})));
    dialog.append(heading,tools,el('p','section-note training-no-print',batch?'按学生分别预览，打印时每人另起一页。使用前请教师核对题目与答案。':'使用前请教师核对年级、题目和答案。'),paper);dialog.addEventListener('close',()=>{if(trainingDialog===dialog){dialog.remove();trainingDialog=null;}});document.body.append(dialog);dialog.showModal();
  }
  function renderBankDiagram(diagram) {
    const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 360 270');svg.setAttribute('role','img');svg.setAttribute('aria-label','题目几何示意图，以题干条件为准');svg.setAttribute('class','bank-recommendation-diagram');
    if(diagram.width!==360||diagram.height!==270||!Array.isArray(diagram.shapes))return svg;
    for(const shape of diagram.shapes){const v=shape.values;if(!Array.isArray(v)||v.some(x=>!Number.isFinite(x)))continue;const n=document.createElementNS(ns,shape.kind);const set=(k,value)=>n.setAttribute(k,String(value));
      if(shape.kind==='line'&&v.length===4){['x1','y1','x2','y2'].forEach((k,i)=>set(k,v[i]));}
      else if(shape.kind==='path'&&v.length>=4&&v.length%2===0){set('d',v.reduce((s,x,i)=>s+(i%2?' ':i?' L':'M')+x,''));set('fill','none');}
      else if(shape.kind==='circle'&&v.length===3){['cx','cy','r'].forEach((k,i)=>set(k,v[i]));set('fill',shape.color);}
      else if(shape.kind==='text'&&v.length===2){set('x',v[0]);set('y',v[1]);set('font-size',shape.fontSize);set('fill',shape.color);set('text-anchor',shape.align==='center'?'middle':'start');n.textContent=shape.text;}
      else continue;
      if(shape.kind==='line'||shape.kind==='path'){set('stroke',shape.color);set('stroke-width',shape.width);if(shape.dash)set('stroke-dasharray','4 4');}svg.append(n);
    }return svg;
  }
  function trainingStatus(plan) {
    if(!plan)return '待生成';
    return ({generated:`已生成${plan.questions.length}道`,partial:`已生成${plan.questions.length}道 · 题源不足或依据待补全`,no_wrong:'已批改记录中无错题',unreviewed:'等待教师批改',source_missing:'原错题来源待核对',needs_text:'原题缺少可检索文字',no_match:'同学段题源不足',unavailable:'题库暂不可用'})[plan.status] || '请核对结果';
  }
  async function openTrainingClass(info) {
    const epoch=++state.epoch;state.trainingClass=info;closeTrainingDialog();emptyDetail('正在读取班级错题…');
    const data=await api(`/training/classes/${encodeURIComponent(info.classId)}/courses/${encodeURIComponent(info.courseId)}`);
    if(current(epoch))renderTrainingClass(data,epoch);
  }
  async function loadTraining(epoch) {
    const classes=await api('/training/classes');if(!current(epoch))return;state.trainingClasses=classes;
    const selected=classes.find(x=>state.trainingClass?.classId===x.classId&&state.trainingClass?.courseId===x.courseId)
      || (entryClassId&&!state.trainingOverview?classes.find(x=>x.classId===entryClassId):null);
    if(selected){await openTrainingClass(selected);return;}
    if(entryClassId&&!state.trainingOverview){emptyDetail('这个班级暂无可访问的课程');return;}
    state.trainingClass=null;
    const card=panel('选择班级'),grid=el('div','training-classes');
    const groups=new Map();for(const info of classes){if(!groups.has(info.classId))groups.set(info.classId,[]);groups.get(info.classId).push(info);}
    for(const courses of groups.values()){
      const info=courses[0],choose=button('','training-class-card',()=>runNavigation(()=>openTrainingClass(info)));
      choose.append(el('strong','',info.className),el('span','section-note',`${info.studentCount}名学生 · ${courses.map(c=>c.courseName).join('、')}`));grid.append(choose);
    }
    card.append(el('p','section-note','点击班级，可一键为全班分别生成强化训练，也可点击学生单独生成。'),grid);
    if(!classes.length)card.append(el('p','empty-inline','暂无可访问的班级课程。'));$('detail-panel').replaceChildren(card);
  }
  function renderTrainingClass(info,epoch) {
    const card=panel(),options=readTrainingOptions(info),plans=new Map();let generation=0,inFlight=false;
    const top=append(el('div','panel-top'),el('h2','',`${info.className} · 强化训练`),button('全部班级','text-button',()=>{state.trainingClass=null;state.trainingOverview=true;runNavigation(()=>loadModule());}));
    const header=el('div','training-class-actions'),summary=el('p','training-summary');summary.setAttribute('aria-live','polite');
    const batch=button('一键生成全班训练','primary',()=>generate());
    const exportAll=button('预览全班训练','secondary',()=>showTrainingPaper(info,Array.from(plans.values())));exportAll.disabled=true;
    header.append(batch,exportAll);card.append(top,el('p','section-note',`${info.courseName} · 按已批改登记的错题历史，为每个孩子分别选题。默认每道错题2道，每人最多12道。`),header,summary);
    const settings=el('details','training-settings');settings.append(el('summary','','班级设置'));
    const stage=selectInput([['','首次使用请选择班级学段'],['小学','小学'],['初中','初中'],['高中','高中']],options.stage);
    const subject=selectInput([['','请选择学科'],...['数学','语文','英语','物理','化学','生物','政治','历史','地理'].map(x=>[x,x])],options.subject);
    const courses=(state.trainingClasses || []).filter(x=>x.classId===info.classId);
    const fields=append(el('div','compact-fields'),field('班级学段',stage),field('学科',subject));
    if(courses.length>1){const course=selectInput(courses.map(x=>[x.courseId,x.courseName]),info.courseId);course.addEventListener('change',()=>runNavigation(()=>openTrainingClass(courses.find(x=>x.courseId===course.value))));fields.append(field('课程',course));}
    settings.append(fields,el('p','section-note','学段只需为本班设置一次，当前浏览器会记住；无需逐人选择。'));settings.open=!options.stage||!options.subject;card.append(settings);
    const grid=table(['学生','已登记错题','训练状态','操作']);card.append(grid.wrap);
    function changed(){generation++;inFlight=false;options.stage=stage.value;options.subject=subject.value;saveTrainingOptions(info,options);plans.clear();summary.textContent='班级设置已更新，可重新生成训练。';renderRoster();}
    stage.addEventListener('change',changed);subject.addEventListener('change',changed);
    function renderRoster(){
      batch.disabled=inFlight;exportAll.disabled=inFlight||!Array.from(plans.values()).some(p=>p.questions.length);grid.body.replaceChildren();
      for(const student of info.students){
        const plan=plans.get(student.studentId),row=el('tr');row.dataset.studentId=student.studentId;
        const name=button(student.name,'text-button',()=>plan?showTrainingPaper(info,plan):generate(student.studentId));name.disabled=inFlight;
        const status=append(el('div'),el('span','',trainingStatus(plan)));if(plan?.notices?.length)status.append(el('p','cell-secondary',plan.notices.join(' ')));
        const generateOne=button(plan?'重新生成':'生成训练','secondary',()=>generate(student.studentId));generateOne.disabled=inFlight;
        const actions=cell(generateOne);if(plan?.questions.length)actions.append(button('查看训练','mint-button',()=>showTrainingPaper(info,plan)));
        row.append(cell(name,'name-cell'),cell(`${student.wrongCount}道`),cell(status),actions);grid.body.append(row);
      }
      if(!info.students.length)summary.textContent='本班暂无学生。';batch.disabled=inFlight||!info.students.length;
    }
    async function generate(studentId){
      if(inFlight)return;
      if(!options.stage||!options.subject){settings.open=true;summary.textContent='首次使用请设置本班的学段与学科，之后点击即可生成。';(!options.stage?stage:subject).focus();return;}
      const requestId=++generation;inFlight=true;renderRoster();summary.textContent=studentId?'正在生成这名学生的强化训练…':'正在为全班逐人生成强化训练…';
      try{
        const result=await api(`/training/classes/${encodeURIComponent(info.classId)}/courses/${encodeURIComponent(info.courseId)}/generate`,{method:'POST',body:{...options,...(studentId?{studentId}:{})}});
        if(!current(epoch)||requestId!==generation)return;
        for(const plan of result.students)plans.set(plan.studentId,plan);
        const generated=Array.from(plans.values()).filter(p=>p.questions.length).length;
        summary.textContent=`已为${generated}名学生生成训练；其他学生的情况见下表。`;
        if(studentId){const plan=plans.get(studentId);if(plan)showTrainingPaper(info,plan);}
      }catch(error){if(current(epoch)&&requestId===generation)summary.textContent=error.message;}
      finally{if(current(epoch)&&requestId===generation){inFlight=false;renderRoster();}}
    }
    renderRoster();$('detail-panel').replaceChildren(card);
  }

  async function loadWrong(epoch,target={}) {
    const data=await api(sessionPath('wrong-records')); if(!current(epoch))return;
    const card=panel();let questionCount=0;
    for(const student of data.students || []) {
      if(target.studentId&&student.studentId!==target.studentId)continue;
      const box=el('details','wrong-student');box.open=true;box.append(el('summary','',`${student.name} · ${student.reviewed ? `${student.questions.length} 道错题` : '等待教师批改'}`));
      if(!student.questions.length){card.append(box);continue;}
      const grid=table(['题目依据','订正','复测结果','练习记录','']);
      for(const row of student.questions) {
        questionCount++;const tr=el('tr'), corrected=checkbox('已订正',row.corrected), retest=selectInput([['unrecorded','未登记'],['correct','正确'],['wrong','仍有错误'],['unfinished','未完成']],row.retestState),note=noteInput(row.practiceNote,2000);corrected.input.disabled=!student.reviewed;retest.disabled=!student.reviewed;note.disabled=!student.reviewed;
        const source=append(el('div'),el('strong','',row.label || '题目'));if(row.note)source.append(el('span','cell-secondary',row.note));if(row.legacyRetestState)source.append(el('span','cell-secondary',`原复测：${({correct:'正确',wrong:'错误',unfinished:'未完成'})[row.legacyRetestState] || row.legacyRetestState}${row.legacyRetestAt?` · ${formatDate(row.legacyRetestAt)}`:''}`));
        const retestDate=el('span','cell-secondary');
        function showRetestDate(){retestDate.textContent=row.retestAt?`复测登记：${formatDate(row.retestAt)}`:'';retestDate.hidden=!row.retestAt;}
        showRetestDate();
        const read=()=>({corrected:corrected.input.checked,retestState:retest.value,note:note.value.trim()});append(tr,cell(source,'note-cell'),cell(corrected.node,'select-cell'),cell(append(el('div'),retest,retestDate),'select-cell'),cell(note,'note-cell'));const saved=watchForm(tr,`wrong:${student.studentId}:${row.questionId}`,read);
        async function savePracticeRecord(recordRetest){
          if(recordRetest&&(!student.reviewed||retest.value==='unrecorded'))return;
          await api(sessionPath(`practice/${student.studentId}/${row.questionId}`),{method:'PUT',body:{revision:row.revision,...read(),recordRetest}});
          const fresh=await api(sessionPath('wrong-records'));adoptRevision(row,fresh.students.find(x=>x.studentId===student.studentId)?.questions.find(x=>x.questionId===row.questionId));saved();showRetestDate();toast(recordRetest?'本次复测已记录。':'订正与练习记录已保存。');
        }
        const savePractice=button('保存','secondary',()=>action(()=>savePracticeRecord(false)));savePractice.disabled=!student.reviewed;
        const recordRetest=button('记录本次复测','mint-button',()=>action(()=>savePracticeRecord(true)));
        function updateRetestButton(){recordRetest.disabled=!student.reviewed||retest.value==='unrecorded';}
        retest.addEventListener('change',updateRetestButton);updateRetestButton();
        const actions=cell(savePractice);actions.append(recordRetest);tr.append(actions);grid.body.append(tr);
      }
      box.append(grid.wrap);card.append(box);
    }
    if(!data.students?.length)card.append(el('p','empty-inline','本课次暂无错题记录。'));
    $('detail-panel').replaceChildren(card);
  }


  async function openDocument(id, kind) {
    const epoch=++state.epoch;resetDirty();state.student=null;state.messageMode=kind==='message';
    emptyDetail('正在读取…');await loadDraft(id,epoch);
  }
  async function openMessageDocument(item) {
    if (!item.sharedDraftMissing) { await openDocument(item.id, item.editorKind || 'message'); return; }
    const requestKey = item.sharedRequestKey || (item.sharedRequestKey = crypto.randomUUID());
    const result = await api('/messages', { method: 'POST', body: { classSessionId: item.classSessionId, studentId: item.studentId, kind: 'learning_feedback', requestKey } });
    await openDocument(result.id, result.editorKind || 'feedback');
  }
  async function loadReviews(epoch) {
    const rows=await api(`/reviews?${query({classSessionId:state.session.id})}`);if(!current(epoch))return;
    const card=panel('待审核'),list=el('div','list-rows');
    for(const item of rows){const info=append(el('div'),el('strong','',item.title || [item.studentName,item.kind==='feedback'?'学情反馈':'家长沟通'].filter(Boolean).join(' · ')),el('p','',`${item.recipientLabel || '接收对象未填写'} · ${statusNames[item.state] || item.state}${item.sourceStale?' · 依据已更新':''}`));list.append(append(el('div','list-row'),info,button('查看审核','secondary',()=>{if(mayLeave())runNavigation(()=>openDocument(item.id,item.kind));})));}
    if(!rows.length)list.append(el('p','empty-inline','暂无待审核记录。'));card.append(list);$('detail-panel').replaceChildren(card);
  }
  async function loadMessages(epoch) {
    const rows=await api(`/messages?${query({classSessionId:state.session.id})}`);if(!current(epoch))return;state.messageRows=rows;
    const creator=el('details','panel compact-details');creator.append(el('summary','','新建沟通草稿'));const form=el('div','compact-fields');
    const kind=selectInput(Object.entries(messageKinds),'class_notice'),student=selectInput([['','选择学生'],...state.students.map(x=>[x.studentId,x.name])],'');const studentField=field('学生',student);
    append(form,field('沟通类型',kind),studentField);const requestKey=crypto.randomUUID();const read=()=>({kind:kind.value,studentId:student.value || null});watchForm(form,'message:create',read);
    const create=button('生成草稿','primary',()=>action(async()=>{if(kind.value==='learning_feedback'&&!student.value)throw new Error('请选择学生。');const chosen=state.students.find(x=>x.studentId===student.value);if(kind.value==='learning_feedback'&&!chosen?.reviewed&&!chosen?.feedbackId)throw new Error('该学生尚未完成课堂批改，不能生成学习反馈。');const result=await api('/messages',{method:'POST',body:{classSessionId:state.session.id,...read(),requestKey}});await openDocument(result.id,result.editorKind || (kind.value==='learning_feedback'?'feedback':'message'));}));
    function typeChanged(){studentField.hidden=kind.value!=='learning_feedback';if(kind.value!=='learning_feedback')student.value='';create.textContent=kind.value==='learning_feedback'?'打开／生成学情草稿':'生成草稿';}kind.addEventListener('change',typeChanged);typeChanged();
    form.append(create);creator.append(form);
    const card=panel('沟通记录'),list=el('div','list-rows');
    for(const item of rows){const info=append(el('div'),el('strong','',item.title || messageKinds[item.kind] || '家长沟通'),el('p','',`${item.recipientLabel || '接收对象未填写'} · ${item.channel==='wecom_group'?'企业微信群':'私聊'} · ${statusNames[item.state] || item.state}`));list.append(append(el('div','list-row'),info,button(item.sharedDraftMissing?'打开共用草稿':'打开','secondary',()=>{if(mayLeave())action(()=>openMessageDocument(item));})));}
    if(!rows.length)list.append(el('p','empty-inline','暂无沟通草稿。'));card.append(list);$('detail-panel').replaceChildren(creator,card);
  }
  const taskCategories=[['attendance','考勤补课'],['homework','作业'],['correction','订正'],['retest','复测'],['parent','家长沟通'],['other','其他']];
  async function loadFollowup(epoch) {
    const [todos,tasks]=await Promise.all([api(`/todos?${query({classSessionId:state.session.id})}`),api(`/tasks?${query({classSessionId:state.session.id})}`)]);if(!current(epoch))return;
    const todoCard=panel('待处理'),list=el('div','list-rows');
    function renderTodoList(entries){list.replaceChildren();for(const todo of entries){const info=append(el('div'),el('strong',todo.overdue?'danger-text':'',[todo.studentName,todo.title].filter(Boolean).join(' · ')),el('p','',[todo.dueOn?`截止 ${todo.dueOn.slice(0,10)}`:'',todo.overdue?'已逾期':''].filter(Boolean).join(' · ')));list.append(append(el('div','list-row'),info,button('处理','secondary',()=>{if(!mayLeave())return;action(async()=>{if(todo.action==='followup'){const node=document.querySelector(`[data-task-id="${CSS.escape(todo.originId || '')}"]`);if(node){node.open=true;node.scrollIntoView({block:'center',behavior:'smooth'});}return;}const destination={attendance:'attendance',homework:'homework',wrong:'wrong',review:'review',communication:'communication'}[todo.action] || 'followup';await switchModule(destination,{studentId:todo.studentId,classSessionId:todo.classSessionId});if(['communication','review'].includes(destination)&&todo.originId){if(todo.kind?.startsWith('message_'))await openMessageDocument(state.messageRows.find(row=>row.id===todo.originId) || {id:todo.originId,editorKind:'message'});else await openDocument(todo.originId,'feedback');if(todo.kind?.includes('parent_'))for(const history of $('detail-panel').querySelectorAll('.history-panel'))history.open=true;}});})));}
    if(!entries.length)list.append(el('p','empty-inline','没有待处理事项。'));}renderTodoList(todos);todoCard.append(list);
    const create=el('details','panel compact-details');create.append(el('summary','','新建跟进任务'));const form=el('div','form-grid'),title=textInput(),category=selectInput(taskCategories,'other'),student=selectInput([['','全班／未指定'],...state.students.map(x=>[x.studentId,x.name])],''),due=textInput('','date'),note=noteInput();
    append(form,field('任务',title),field('类别',category),field('学生',student),field('截止日期',due));const noteField=field('说明',note);noteField.classList.add('wide');form.append(noteField);
    const requestKey=crypto.randomUUID(),read=()=>({title:title.value.trim(),category:category.value,studentId:student.value || null,dueOn:due.value || null,note:note.value.trim()});watchForm(form,'task:new',read);
    const createButton=button('创建任务','primary',()=>action(async()=>{if(!title.value.trim())throw new Error('请填写任务。');if([...state.moduleDirty].some(x=>x!=='task:new')&&!window.confirm('其他任务有未保存的修改，创建后会重新读取列表。继续吗？'))return;await api('/tasks',{method:'POST',body:{classSessionId:state.session.id,...read(),requestKey}});await loadModule();toast('任务已创建。');}));append(create,form,append(el('div','action-row'),createButton));
    const taskCard=panel('跟进任务');
    for(const item of tasks){const box=el('details','task-card');box.dataset.taskId=item.id;const summary=el('summary','',[item.status==='done'?'已完成':'待处理',item.studentName,item.title].filter(Boolean).join(' · '));box.append(summary);
      const edit=el('div','form-grid'),taskTitle=textInput(item.title),taskCategory=selectInput(taskCategories,item.category),taskDue=textInput(item.dueOn?.slice(0,10),'date'),status=selectInput([['open','待处理'],['done','已完成']],item.status),taskNote=noteInput(item.note);
      append(edit,field('任务',taskTitle),field('类别',taskCategory),field('截止日期',taskDue),field('状态',status));const taskNoteField=field('处理记录',taskNote);taskNoteField.classList.add('wide');edit.append(taskNoteField);
      const readTask=()=>({title:taskTitle.value.trim(),category:taskCategory.value,dueOn:taskDue.value || null,status:status.value,note:taskNote.value.trim()});const saved=watchForm(edit,`task:${item.id}`,readTask);
      const save=button('保存任务','secondary',()=>action(async()=>{if(!taskTitle.value.trim())throw new Error('请填写任务。');await api(`/tasks/${item.id}`,{method:'PUT',body:{revision:item.revision,classSessionId:item.classSessionId,studentId:item.studentId || null,...readTask()}});const fresh=await api(`/tasks?${query({classSessionId:state.session.id})}`);adoptRevision(item,fresh.find(x=>x.id===item.id));saved();renderTodoList(await api(`/todos?${query({classSessionId:state.session.id})}`));summary.textContent=[item.status==='done'?'已完成':'待处理',item.studentName,item.title].filter(Boolean).join(' · ');toast('任务已保存。');}));append(box,edit,append(el('div','action-row'),save));taskCard.append(box);
    }
    if(!tasks.length)taskCard.append(el('p','empty-inline','暂无手动跟进任务。'));$('detail-panel').replaceChildren(todoCard,create,taskCard);
  }
  function dateString(value){return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(value);}
  let statisticsRange=null;
  async function loadStatistics(epoch) {
    const end=new Date(),start=new Date(end);start.setDate(start.getDate()-6);
    const range=statisticsRange || {from:dateString(start),to:dateString(end),all:true};
    const card=panel(),form=el('div','compact-fields'),from=textInput(range.from,'date'),to=textInput(range.to,'date'),scope=selectInput([['all','所有课次'],['session','当前课次']],range.all?'all':'session');
    append(form,field('开始日期',from),field('结束日期',to),field('统计范围',scope));
    const result=el('div','statistics-result'),report=el('div');
    const load=async()=>{if(!from.value||!to.value)throw new Error('请选择起止日期。');if(from.value>to.value)throw new Error('结束日期不能早于开始日期。');if((new Date(to.value)-new Date(from.value))/86400000>92)throw new Error('一次最多查看 93 天。');const chosen={from:from.value,to:to.value,all:scope.value==='all'};const data=await api(`/statistics?${query({from:chosen.from,to:chosen.to,classSessionId:chosen.all?null:state.session?.id})}`);if(!current(epoch))return;statisticsRange=chosen;renderStatistics(data,result,report);};
    form.append(button('查询','primary',()=>action(load)));card.append(form);$('detail-panel').replaceChildren(card,result,report);await load();
  }
  function renderStatistics(data,container,reportContainer) {
    const grid=el('div','statistics-grid');
    const sections=[['课堂',[['课次',data.sessionCount],['学生课次',data.studentSessions],['已批改',data.marking?.reviewed],['未批改',data.marking?.unreviewed],['错题数',data.marking?.wrongQuestionCount]]],['考勤',[['未登记',data.attendance?.unrecorded],['出勤',data.attendance?.present],['迟到',data.attendance?.late],['请假',data.attendance?.leave],['缺勤',data.attendance?.absent],['临时调班',data.attendance?.transfer]]],['作业',[['已安排课次',data.homework?.configuredSessions],['未登记',data.homework?.unrecorded],['未交',data.homework?.missing],['已交待批',data.homework?.submitted],['已批改',data.homework?.reviewed]]],['学情反馈',[['待审核',data.feedback?.draft],['已审核待沟通',data.feedback?.approved],['已登记沟通',data.feedback?.communicated]]],['家长沟通',[['等待回复',data.communications?.awaitingReply],['继续跟进',data.communications?.followUp],['已完成',data.communications?.resolved]]],['跟进任务',[['待处理',data.tasks?.open],['已完成',data.tasks?.done],['已逾期',data.tasks?.overdue]]]];
    for(const [title,values] of sections){const block=el('section','statistics-block');block.append(el('h3','',title));for(const [label,value] of values)block.append(append(el('div','stat-line'),el('span','',label),el('strong','',value ?? 0)));grid.append(block);}
    const card=panel();card.append(grid);container.replaceChildren(card);
    if(data.daily?.length){const daily=panel('每日记录'),tableData=table(['日期','课次','学生课次','出勤','迟到','请假','缺勤','临时调班','考勤未登记','作业未交','作业已交','作业已批','作业未登记','错题']);tableData.wrap.classList.add('daily-table');for(const row of data.daily){const tr=el('tr');['date','sessionCount','studentSessions','present','late','leave','absent','transfer','attendanceUnrecorded','homeworkMissing','homeworkSubmitted','homeworkReviewed','homeworkUnrecorded','wrongQuestionCount'].forEach(key=>tr.append(cell(row[key] ?? 0)));tableData.body.append(tr);}daily.append(tableData.wrap);container.append(daily);}
    const report=panel('周报文本'),text=noteInput(data.weeklyReport || '');text.className='report-text';text.readOnly=true;
    const copy=button('复制周报','secondary',()=>action(async()=>{try{await navigator.clipboard.writeText(text.value);toast('周报已复制。');}catch{text.focus();text.select();toast('已选中周报，请手动复制。');}}));
    const download=button('下载文本','secondary',()=>{const url=URL.createObjectURL(new Blob([text.value],{type:'text/plain;charset=utf-8'}));const link=el('a');link.href=url;link.download=`教务周报_${data.from}_${data.to}.txt`;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);});append(report,text,append(el('div','action-row'),copy,download));reportContainer.replaceChildren(report);
  }

  $('login-form').addEventListener('submit', async event => {
    event.preventDefault(); if (state.busy) return;
    const submit = event.currentTarget.querySelector('button'); state.busy = true; submit.disabled = true; showError('', true);
    try {
      const result = await request(`${base}/api/auth/password`, { method: 'POST', body: { account: $('login-account').value.trim(), password: $('login-password').value } });
      state.token = result.token; $('login-password').value = '';
      await enter();
    } catch (error) {
      const message = error.status === 404 ? '当前账号尚未开放试点，请联系管理员。' : error.message;
      logout(message);
    } finally { state.busy = false; submit.disabled = false; }
  });
  $('return-class-button').hidden=!entryClassId;
  $('return-class-button').addEventListener('click',()=>{if(mayLeave())location.assign(`${base}/workbench/#home?choose=1`);});
  $('logout-button').addEventListener('click', () => { if (mayLeave()) logout(); });
  $('session-select').addEventListener('change', async event => {
    const value = event.target.value;
    if (!mayLeave()) { event.target.value = state.session?.id || ''; return; }
    showError(''); try { await loadSession(value); } catch (error) { if (state.me) showError(error.message); }
  });
  $('refresh-button').addEventListener('click', event => {
    if (!mayLeave()) return;
    const sessionId = state.session?.id, studentId = state.student?.studentId;
    action(async () => {
      if(state.module==='training')await loadModule();else if (sessionId) await loadSession(sessionId, studentId); else await loadSessions();
      toast('记录已刷新。');
    }, event.currentTarget);
  });
  window.addEventListener('beforeunload', event => { if (state.dirty) { event.preventDefault(); event.returnValue = ''; } });
  if (state.token) enter().catch(error => logout(error.status === 404 ? '当前账号尚未开放试点，请联系管理员。' : error.message));
})();
