/* Java system, campus, teaching, staff, position and audit pages. */
(function (root) {
  'use strict';

  const pages = [
    { id: 'system', title: '系统配置', permissions: ['settings.read'] },
    { id: 'campus', title: '校区配置', permissions: ['settings.update'] },
    { id: 'teaching', title: '教学配置', permissions: ['settings.update'] },
    { id: 'staff', title: '员工列表', permissions: ['system.access.manage'], scope: 'all' },
    { id: 'positions', title: '职位管理', permissions: ['system.access.manage'], scope: 'all' },
    { id: 'permissionLogs', title: '操作日志', permissions: ['audit.read'] }
  ];
  const pageById = new Map(pages.map(page => [page.id, page]));
  const settingsSections = {
    organization: '机构信息', attendance: '考勤配置', move: '调课配置', transfer: '转班配置',
    enrollment: '进班配置', receipts: '收款设置'
  };
  const settingFields = {
    organization: [['name', '机构名称', 'text'], ['address', '机构地址', 'text'], ['phone', '联系电话', 'text'], ['contact', '联系人', 'text']],
    attendance: [['presentConsume', '出勤确认课消', 'bool'], ['absentConsume', '旷课确认课消', 'bool'],
      ['leaveConsume', '请假确认课消', 'bool'], ['allowMakeup', '允许补课', 'bool']],
    move: [['sameCourse', '限制为同课程', 'bool'], ['maxDays', '办理提前天数', 'integer']],
    transfer: [],
    enrollment: [],
    receipts: [['accountName', '收款账户名称', 'text'], ['bankName', '开户银行', 'text'],
      ['accountNumber', '收款账号', 'text'], ['receiptNote', '收款说明', 'text']]
  };
  const dictionaryKinds = [['subjects', '学科'], ['levels', '等级'], ['terms', '学期'],
    ['grades', '年级'], ['classTypes', '班型']];
  const permissions = [
    'account.update', 'attendance.manage', 'attendance.read', 'audit.read', 'class.assign', 'class.batchCreate',
    'class.create', 'class.read', 'class.write', 'commerce.read', 'commerce.manage', 'course.create', 'course.read',
    'course.update', 'course.write', 'crm.activity.read', 'crm.activity.write', 'crm.attribution.correct.request',
    'crm.attribution.correct.review', 'crm.customer.read', 'crm.customer.write', 'crm.customer.search.manage',
    'crm.customer.identity.write', 'crm.form.publish', 'crm.form.read', 'crm.form.submission.read',
    'crm.form.submission.review', 'crm.form.write', 'crm.lead.assign', 'crm.lead.follow', 'lead.assign',
    'lead.create', 'lead.follow', 'lead.release', 'lead.transfer', 'lead.conversion.read', 'sales.activity.read',
    'crm.lead.policy.manage', 'crm.lead.read',
    'crm.lead.write', 'crm.opportunity.read', 'crm.opportunity.write', 'crm.opportunity.intake',
    'crm.opportunity.assign', 'crm.opportunity.config.manage', 'crm.order.read', 'crm.order.write',
    'crm.receipt.record', 'crm.refund.approve', 'crm.refund.read', 'crm.refund.record', 'crm.refund.request',
    'crm.task.assign', 'crm.task.manage', 'crm.task.read', 'crm.task.write', 'crm.ticket.assign',
    'crm.ticket.read', 'crm.ticket.type.manage', 'crm.ticket.write', 'crm.touchpoint.read', 'crm.touchpoint.write',
    'enrollment.batchTransfer', 'enrollment.create', 'enrollment.manage', 'enrollment.transfer', 'report.read',
    'session.move', 'settings.update', 'student.communicate', 'student.communication.read', 'student.create',
    'student.read', 'student.update', 'student.write'
  ];
  const text = value => String(value ?? '');
  const esc = value => text(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
  const safeSegment = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value);
  const items = result => Array.isArray(result?.items) ? result.items : [];

  function create({ api, identity, render = () => {}, randomUUID = () => root.crypto.randomUUID() }) {
    const settingsApi = api?.settingsWorkspace;
    if (!settingsApi?.settings || !settingsApi?.campuses || !settingsApi?.dictionary
            || !settingsApi?.staff || !settingsApi?.positions || !settingsApi?.audit) {
      throw new TypeError('Settings workspace requires the named Java settings API');
    }
    const state = { page: '', busy: false, loading: false, error: '', notice: '', data: null, edit: null,
      review: null, credentials: null, section: 'organization', pageNumber: 1, auditQuery: { actorId: '', action: '', resourceId: '', fromAt: '', untilAt: '' } };
    let container = null;
    let listenersBound = false;
    let disposed = false;
    let loadEpoch = 0;
    let clickListener = null;
    let submitListener = null;
    const resetStorageKey = `siyue.java.password-reset.${text(identity.principalId || identity.subject || 'admin')}`;

    const key = () => {
      const value = randomUUID();
      if (!/^[A-Za-z0-9._:-]{16,100}$/.test(value)) throw new TypeError('Invalid idempotency key');
      return value;
    };
    const has = code => (identity.permissions || []).includes(code);
    const permitted = page => {
      const spec = pageById.get(page);
      const allowed = page === 'system' ? has('settings.read') || has('settings.update')
        : !!spec && spec.permissions.every(code => has(code));
      return !!spec && allowed
        && (!spec.scope || identity.scope === spec.scope);
    };
    const clear = () => { loadEpoch++; state.page = '';  state.data = null; state.edit = null; state.review = null; state.credentials = null; };
    const paint = () => { if (disposed) { clear(); return; } render(html()); };
    const fail = error => error?.message || '读取或保存失败，请重试';

    async function open(page) {
      if (disposed) return;
      const spec = pageById.get(page);
      if (!spec) throw new TypeError('Unsupported Java settings page');
      if (!permitted(page)) throw new Error('当前账号没有此页面的授权范围');
      state.page = page;
      state.data = null;
      state.edit = null;
      state.review = null;
      state.error = '';
      state.notice = '';
      state.loading = true;
      paint();
      const openingPage = page;
      try { await load(); }
      catch (error) { if (state.page === openingPage) state.error = fail(error); }
      finally { if (state.page === openingPage) { state.loading = false; paint(); } }
    }

    async function load() {
      if (disposed) return;
      const epoch = ++loadEpoch;
      const page = state.page;
      state.error = '';
      state.data = null;
      let result;
      try {
        if (page === 'system') result = await settingsApi.settings.get();
        else if (page === 'campus') result = await settingsApi.campuses.list({ page: state.pageNumber, size: 50 });
        else if (page === 'teaching') result = await settingsApi.dictionary.get();
        else if (page === 'staff') result = await settingsApi.staff.list({ page: state.pageNumber, size: 50 });
        else if (page === 'positions') result = await settingsApi.positions.list({ page: state.pageNumber, size: 50 });
        else if (page === 'permissionLogs') result = await settingsApi.audit.list({ ...state.auditQuery, page: state.pageNumber, size: 50 });
      } catch (error) {
        if (disposed || epoch !== loadEpoch || page !== state.page) return;
        state.error = fail(error);
        paint();
        throw error;
      }
      if (disposed || epoch !== loadEpoch || page !== state.page) return;
      state.data = result;
      paint();
    }

    function prepare(title, operation, args, summary) {
      state.review = { title, operation, args, summary, key: key() };
      state.notice = '';
      paint();
    }

    async function commit() {
      const review = state.review;
      if (!review || state.busy) return;
      if (!canWrite(review.operation)) {
        state.error = '当前账号没有执行此项修改的权限';
        state.review = null;
        paint();
        return;
      }
      state.busy = true;
      state.error = '';
      paint();
      try {
        await execute(review.operation, review.args, review.key);
        state.review = null;
        state.edit = null;
        state.notice = '保存完成';
        await load();
      } catch (error) {
        state.error = fail(error);
      } finally {
        state.busy = false;
        paint();
      }
    }

    function execute(operation, args, idempotencyKey) {
      switch (operation) {
        case 'settings.update': return settingsApi.settings.update(args.section, args.values, idempotencyKey);
        case 'dictionary.update': return settingsApi.dictionary.update(args.key, args.values, idempotencyKey);
        case 'campuses.create': return settingsApi.campuses.create(args.input, idempotencyKey);
        case 'campuses.update': return settingsApi.campuses.update(args.id, args.input, idempotencyKey);
        case 'staff.updateProfile': return settingsApi.staff.updateProfile(args.id, args.input, idempotencyKey);
        case 'positions.create': return settingsApi.positions.create(args.input, idempotencyKey);
        case 'positions.update': return settingsApi.positions.update(args.id, args.input, idempotencyKey);
        case 'positions.members': return settingsApi.positions.members(args.id, args.input, idempotencyKey);
        default: throw new TypeError('Unsupported Java settings operation');
      }
    }

    function canWrite(operation) {
      if (operation.startsWith('positions.')) return has('system.access.manage') && identity.scope === 'all';
      if (operation === 'staff.updateProfile') return has('account.update') && identity.scope === 'all';
      return has('settings.update');
    }

    async function allPrincipals() {
      const size = 100;
      const first = await settingsApi.staff.list({ page: 1, size });
      if (!Array.isArray(first?.items) || !Number.isSafeInteger(first.total) || first.total < 0
              || first.page !== 1 || first.size !== size || first.items.length > size) {
        throw new Error('员工列表回执无效，未进入职位关联编辑');
      }
      if (first.total > 500) throw new Error('当前员工超过职位关联编辑上限 500 人；未加载不完整名单，也未更改关联');
      const rows = [...first.items];
      const pages = Math.ceil(first.total / size);
      for (let page = 2; page <= pages; page++) {
        if (disposed) return [];
        const result = await settingsApi.staff.list({ page, size });
        if (!Array.isArray(result?.items) || result.total !== first.total || result.page !== page
                || result.size !== size || result.items.length > size) {
          throw new Error('员工列表在分页期间发生变化，未进入职位关联编辑');
        }
        rows.push(...result.items);
      }
      if (rows.length !== first.total || new Set(rows.map(row => row.principalId)).size !== rows.length) {
        throw new Error('员工列表不完整或包含重复编号，未更改职位关联');
      }
      return rows;
    }

    function cancelReview() { state.review = null; state.error = ''; paint(); }
    function field(label, name, value, type = 'text', attrs = '') {
      if (type === 'bool') return `<label class="checkrow"><input type="checkbox" name="${esc(name)}" ${value === true ? 'checked' : ''} ${attrs}> ${esc(label)}</label>`;
      const htmlType = ['date', 'datetime-local'].includes(type) ? type : type === 'integer' ? 'number' : 'text';
      return `<label class="field"><span>${esc(label)}</span><input class="input" name="${esc(name)}" type="${htmlType}" value="${esc(value ?? '')}" ${type === 'integer' ? 'min="1" max="365" step="1"' : 'maxlength="500"'} ${attrs}></label>`;
    }
    function formActions(label = '提交前核对') {
      return `<div class="form-actions"><button class="btn" type="button" data-settings-action="cancel-edit">取消</button><button class="btn primary" type="submit">${esc(label)}</button></div>`;
    }
    function common(title, subtitle, body) {
      const error = state.error ? `<p class="notice" role="alert">${esc(state.error)}</p>` : '';
      const notice = state.notice ? `<p class="notice" role="status">${esc(state.notice)}</p>` : '';
      const load = state.loading ? '<p role="status">正在读取…</p>' : '';
      return `<section id="java-settings-workspace" class="java-settings"><div class="heading"><div><h1>${esc(title)}</h1><p class="subtitle">${esc(subtitle)}</p></div><button class="btn" type="button" data-settings-action="refresh" ${state.loading || state.busy ? 'disabled' : ''}>刷新</button></div>${error}${notice}${load}${body}</section>`;
    }
    function reviewHtml() {
      const review = state.review;
      return `<section class="card pad" aria-label="提交前核对"><h2 class="section-title">${esc(review.title)}</h2><ul>${review.summary.map(item => `<li>${esc(item)}</li>`).join('')}</ul><div class="form-actions"><button class="btn" type="button" data-settings-action="cancel-review" ${state.busy ? 'disabled' : ''}>返回修改</button><button class="btn primary" type="button" data-settings-action="confirm" ${state.busy ? 'disabled' : ''}>${state.busy ? '正在保存…' : '确认保存'}</button></div></section>`;
    }

    function systemHtml() {
      const data = state.data?.sections || {};
      const fields = settingFields[state.section] || [];
      const stored = data[state.section] || {};
      const fixed = state.section === 'transfer' ? ['同年级、同学科并要求填写原因']
        : state.section === 'enrollment' ? ['超容量报名关闭、报名必须填写联系电话'] : [];
      const readOnly = !has('settings.update');
      const body = state.review ? reviewHtml() : `<div class="card sys-config"><div class="tabs" role="tablist">${Object.entries(settingsSections).map(([id, label]) => `<button class="tab ${state.section === id ? 'active' : ''}" type="button" role="tab" aria-selected="${state.section === id}" data-settings-section="${id}">${esc(label)}</button>`).join('')}</div><div class="pad"><h2 class="section-title">${esc(settingsSections[state.section])}</h2>${fixed.length ? `<p class="notice">${esc(fixed.join('；'))}。这些约束由服务端固定，不开放修改。</p>` : ''}${readOnly ? '<p class="help">当前身份只可查看配置。</p>' : ''}<form data-settings-form="system"><div class="form-grid">${fields.map(([name, label, type]) => field(label, name, stored[name], type, readOnly ? 'disabled' : '')).join('')}</div>${fields.length ? readOnly ? '' : formActions('核对并保存') : '<p class="empty">此页当前没有开放可编辑项。</p>'}</form></div></div>`;
      return common('系统配置', '机构信息及已接入的办理规则', body);
    }

    function campusHtml() {
      const rows = items(state.data);
      let body;
      if (state.review) body = reviewHtml();
      else if (state.edit) {
        const campus = state.edit;
        const roomLines = (campus.rooms || []).map(room => `${room.name}|${room.capacity}|${room.virtualSeats || 0}`).join('\n');
        body = `<section class="card pad"><h2 class="section-title">${campus.id ? '编辑校区' : '创建校区'}</h2><form data-settings-form="campus"><div class="form-grid">${field('校区名称', 'name', campus.name)}${field('联系电话', 'phone', campus.phone)}${field('详细地址', 'address', campus.address)}${campus.id ? field('是否启用', 'active', campus.active, 'bool') : ''}<label class="field"><span>适用学科（每行一项）</span><textarea class="textarea" name="subjects" maxlength="5000">${esc((campus.subjects || []).join('\n'))}</textarea></label><label class="field"><span>适用年级（每行一项）</span><textarea class="textarea" name="grades" maxlength="5000">${esc((campus.grades || []).join('\n'))}</textarea></label><label class="field full"><span>教室（每行：教室名 | 容量 | 虚拟座位）</span><textarea class="textarea" name="rooms" maxlength="10000">${esc(roomLines)}</textarea></label></div>${formActions('核对校区信息')}</form></section>`;
      } else {
        body = `<div class="card pad"><div class="toolbar"><button class="btn primary" type="button" data-settings-action="new-campus">+ 创建校区</button><span class="count">校区总数：${Number(state.data?.total) || 0}</span></div><div class="table-wrap"><table class="table"><thead><tr><th>校区名称</th><th>地址</th><th>教室数</th><th>坐席容量</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td>${esc(row.name)}</td><td>${esc(row.address || '—')}</td><td>${(row.rooms || []).length}</td><td>${(row.rooms || []).reduce((sum, room) => sum + Number(room.capacity || 0), 0)}</td><td>${row.active ? '已启用' : '已停用'}</td><td><button class="btn small" type="button" data-settings-action="edit-campus" data-id="${esc(row.id)}">编辑</button></td></tr>`).join('') || '<tr><td colspan="6">当前范围没有校区</td></tr>'}</tbody></table></div></div>`;
      }
      return common('校区管理', '管理校区资料、教室和容量', body);
    }

    function dictionaryHtml() {
      const values = state.data?.values || {};
      const selected = values[state.section] || [];
      let body;
      if (state.review) body = reviewHtml();
      else body = `<div class="card"><div class="tabs" role="tablist">${dictionaryKinds.map(([id, label]) => `<button class="tab ${state.section === id ? 'active' : ''}" type="button" role="tab" data-settings-section="${id}">${esc(label)}配置</button>`).join('')}</div><div class="pad"><h2 class="section-title">${esc(dictionaryKinds.find(([id]) => id === state.section)?.[1] || '')}配置</h2><form data-settings-form="dictionary"><label class="field"><span>每行一项，保存后按顺序展示</span><textarea class="textarea" name="values" rows="12" maxlength="20000">${esc(selected.join('\n'))}</textarea></label><p class="help">难度配置尚无已确认的后端字典契约，本页不会用其他分类代替。</p>${formActions('核对并保存')}</form></div></div>`;
      return common('教学配置', '读取和维护课程及班级使用的基础字典', body);
    }

    function staffHtml() {
      const rows = items(state.data);
      let body;
      if (state.credentials) {
        const credential = state.credentials;
        body = `<section class="card pad" aria-label="一次性初始密码"><h2 class="section-title">密码已重置</h2><p>该员工下次登录必须修改密码，现有登录会话已注销。请通过安全渠道交付一次性初始密码。</p><p><strong>员工编号：</strong>${esc(credential.user.principalId)}</p><p><strong>初始密码：</strong><code data-reset-password>${esc(credential.initialPassword)}</code></p><div class="form-actions"><button class="btn" type="button" data-settings-action="copy-reset-password">复制密码</button><button class="btn primary" type="button" data-settings-action="dismiss-reset-password">完成</button></div></section>`;
      }
      else if (state.review) body = reviewHtml();
      else if (state.edit?.profile) {
        const profile = state.edit.profile;
        body = `<section class="card pad"><h2 class="section-title">员工资料 · ${esc(state.edit.principal.principalId)}</h2><p class="help">本页只修改业务档案，不修改身份、启停状态、权限或校区范围。</p>${has('account.update') ? `<form data-settings-form="staff"><div class="form-grid">${field('联系电话', 'phone', profile.phone)}${field('入职日期', 'employmentStart', profile.employmentStart)}${field('离职日期', 'employmentEnd', profile.employmentEnd)}${field('职位名称', 'title', profile.title)}${field('校区编号', 'campusId', profile.campusId)}<label class="field full"><span>备注</span><textarea class="textarea" name="note" maxlength="2000">${esc(profile.note)}</textarea></label></div>${formActions('核对并保存')}</form>` : '<p class="help">当前身份可查看员工档案，但没有修改资料的权限。</p>'}</section>`;
      } else {
        body = `<div class="card pad"><div class="table-wrap"><table class="table"><thead><tr><th>员工编号</th><th>授权范围</th><th>账号状态</th><th>直接权限</th><th>职位继承权限</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td>${esc(row.principalId)}</td><td>${esc(row.scope)}</td><td>${row.active ? '已启用' : '已停用'}</td><td>${(row.permissionCodes || []).length}</td><td>${Math.max(0, (row.effectivePermissionCodes || row.permissionCodes || []).length - (row.permissionCodes || []).length)}</td><td>${has('account.update') ? `<button class="btn small" type="button" data-settings-action="edit-staff" data-id="${esc(row.principalId)}">资料</button>` : ''}${identity.scope === 'all' && has('system.access.manage') && row.active ? ` <button class="btn small" type="button" data-settings-action="reset-password" data-id="${esc(row.principalId)}">重置密码</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="6">没有可见员工账号</td></tr>'}</tbody></table></div></div>`;
      }
      return common('员工管理', '查看账号授权并维护已存在员工的业务档案', body);
    }

    function positionHtml() {
      const rows = items(state.data);
      let body;
      if (state.review) body = reviewHtml();
      else if (state.edit?.type === 'position') {
        const position = state.edit.position;
        body = `<section class="card pad"><h2 class="section-title">${position.id ? '编辑职位' : '创建职位'}</h2><form data-settings-form="position"><div class="form-grid">${field('职位名称', 'name', position.name)}${field('启用职位', 'active', position.active !== false, 'bool')}<label class="field full"><span>说明</span><textarea class="textarea" name="description" maxlength="500">${esc(position.description)}</textarea></label></div><h3>权限模板</h3><div class="checkrow">${permissions.map(code => `<label><input type="checkbox" name="permissionCodes" value="${esc(code)}" ${(position.permissionCodes || []).includes(code) ? 'checked' : ''}> ${esc(code)}</label>`).join('')}</div>${formActions('核对职位')}</form></section>`;
      } else if (state.edit?.type === 'members') {
        const position = state.edit.position;
        body = `<section class="card pad"><h2 class="section-title">${esc(position.name)} · 员工关联</h2><form data-settings-form="members"><div class="checkrow">${(state.edit.principals || []).map(row => `<label><input type="checkbox" name="principalIds" value="${esc(row.principalId)}" ${(position.principalIds || []).includes(row.principalId) ? 'checked' : ''}> ${esc(row.principalId)} · ${row.active ? '已启用' : '已停用'}</label>`).join('')}</div>${formActions('核对员工关联')}</form></section>`;
      } else {
        body = `<div class="card pad"><div class="toolbar"><button class="btn primary" type="button" data-settings-action="new-position">+ 创建职位</button></div><div class="table-wrap"><table class="table"><thead><tr><th>职位</th><th>状态</th><th>权限数</th><th>员工数</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td><strong>${esc(row.name)}</strong><p class="help">${esc(row.description)}</p></td><td>${row.active ? '已启用' : '已停用'}</td><td>${(row.permissionCodes || []).length}</td><td>${(row.principalIds || []).length}</td><td><div class="actions"><button class="btn small" data-settings-action="edit-position" data-id="${esc(row.id)}">编辑</button><button class="btn small" data-settings-action="edit-members" data-id="${esc(row.id)}">员工关联</button></div></td></tr>`).join('') || '<tr><td colspan="5">暂无职位模板</td></tr>'}</tbody></table></div></div>`;
      }
      return common('职位管理', '维护可撤销的权限模板及员工关联', body);
    }

    function auditHtml() {
      const rows = items(state.data);
      const local = value => {
        if (!value) return '';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        const pad = number => String(number).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
      };
      const body = state.review ? reviewHtml() : `<div class="card pad"><form data-settings-form="audit" class="filters">${field('员工编号', 'actorId', state.auditQuery.actorId)}${field('动作', 'action', state.auditQuery.action)}${field('资源编号', 'resourceId', state.auditQuery.resourceId)}${field('开始时间', 'fromAt', local(state.auditQuery.fromAt), 'datetime-local')}${field('结束时间', 'untilAt', local(state.auditQuery.untilAt), 'datetime-local')}<button class="btn primary">筛选</button></form><div class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>员工</th><th>动作</th><th>资源</th><th>Trace</th></tr></thead><tbody>${rows.map(row => `<tr><td>${esc(row.createdAt)}</td><td>${esc(row.actorId)}</td><td>${esc(row.action)}</td><td>${esc(row.resourceId)}</td><td>${esc(row.traceId)}</td></tr>`).join('') || '<tr><td colspan="5">当前范围没有操作记录</td></tr>'}</tbody></table></div><p>共 ${Number(state.data?.total) || 0} 条 · 第 ${Number(state.data?.page) || 1} 页</p></div>`;
      return common('操作日志', '只读查询；服务端按当前身份范围过滤', body);
    }

    function html() {
      if (disposed) return '';
      if (!state.page) return '';
      if (state.page === 'system') return systemHtml();
      if (state.page === 'campus') return campusHtml();
      if (state.page === 'teaching') return dictionaryHtml();
      if (state.page === 'staff') return staffHtml();
      if (state.page === 'positions') return positionHtml();
      return auditHtml();
    }

    function formValues(form) {
      const data = new FormData(form);
      const result = {};
      data.forEach((value, name) => {
        if (name === 'permissionCodes' || name === 'principalIds') (result[name] ||= []).push(value);
        else result[name] = value;
      });
      for (const input of form.querySelectorAll('input[type="checkbox"]')) {
        if (input.name && !Object.hasOwn(result, input.name)) result[input.name] = false;
        else if (input.name && ['active'].includes(input.name)) result[input.name] = input.checked;
      }
      return result;
    }

    function splitLines(value) { return text(value).split(/\r?\n/).map(item => item.trim()).filter(Boolean); }
    function rooms(value) {
      const existingByName = new Map((state.edit?.rooms || []).map(room => [room.name, room]));
      const result = splitLines(value).map(line => {
        const [name, capacityText, virtualText = '0'] = line.split('|').map(part => part.trim());
        const capacity = Number(capacityText), virtualSeats = Number(virtualText);
        if (!name || !Number.isInteger(capacity) || capacity < 1 || capacity > 10000
                || !Number.isInteger(virtualSeats) || virtualSeats < 0 || virtualSeats > 10000) {
          throw new Error('教室行格式无效；请按“教室名 | 容量 | 虚拟座位”填写。');
        }
        return { ...(existingByName.get(name)?.id ? { id: existingByName.get(name).id } : {}), name, capacity, virtualSeats };
      });
      if (new Set(result.map(room => room.name)).size !== result.length || new Set(result.map(room => room.id).filter(Boolean)).size !== result.filter(room => room.id).length) {
        throw new Error('教室名称不能重复，请保留原名称以更新已有教室。');
      }
      return result;
    }

    async function submit(form) {
      const kind = form.dataset.settingsForm;
      const values = formValues(form);
      if (kind === 'audit') {
        state.auditQuery = { ...values,
          fromAt: values.fromAt ? new Date(values.fromAt).toISOString() : '',
          untilAt: values.untilAt ? new Date(values.untilAt).toISOString() : '' };
        state.pageNumber = 1;
        state.loading = true; paint();
        try { await load(); } catch (error) { state.error = fail(error); }
        finally { state.loading = false; paint(); }
        return;
      }
      if (kind === 'system') {
        const schema = Object.fromEntries((settingFields[state.section] || []).map(([name, , type]) => [name, type]));
        const clean = {};
        for (const [name, type] of Object.entries(schema)) {
          if (type === 'bool') clean[name] = values[name] === true;
          else if (type === 'integer') {
            const number = Number(values[name]);
            if (!Number.isInteger(number) || number < 1 || number > 365) throw new Error(`${name}必须是 1 至 365 的整数`);
            clean[name] = number;
          } else clean[name] = text(values[name]).trim();
        }
        prepare('核对系统配置', 'settings.update', { section: state.section, values: clean },
          [`分类：${settingsSections[state.section]}`, `保存 ${Object.keys(clean).length} 项`]);
      } else if (kind === 'dictionary') {
        const list = splitLines(values.values);
        if (!list.length || list.length > 200 || new Set(list).size !== list.length) throw new Error('字典需填写 1 至 200 个不重复项目');
        prepare('核对教学字典', 'dictionary.update', { key: state.section, values: list },
          [`分类：${dictionaryKinds.find(([id]) => id === state.section)?.[1] || state.section}`, `保存 ${list.length} 项`]);
      } else if (kind === 'campus') {
        const campus = state.edit || {};
        const fields = { name: text(values.name).trim(), address: text(values.address).trim(), phone: text(values.phone).trim(),
          rooms: rooms(values.rooms), active: campus.id ? values.active === true : true,
          subjects: splitLines(values.subjects), grades: splitLines(values.grades) };
        if (!fields.name || fields.name.length > 100 || !fields.address || fields.address.length > 500) throw new Error('请填写有效的校区名称和地址');
        const update = !!campus.id;
        const body = update ? { patch: fields, expectedVersion: campus.version } : fields;
        prepare(update ? '核对校区修改' : '核对创建校区', update ? 'campuses.update' : 'campuses.create',
          update ? { id: campus.id, input: body } : { input: body },
          [`校区：${fields.name}`, `教室：${fields.rooms.length} 间`]);
      } else if (kind === 'staff') {
        const profile = state.edit.profile;
        const patch = { phone: text(values.phone).trim() || null, employmentStart: text(values.employmentStart).trim() || null,
          employmentEnd: text(values.employmentEnd).trim() || null, title: text(values.title).trim() || null,
          campusId: text(values.campusId).trim() || null, note: text(values.note).trim() || null };
        prepare('核对员工档案修改', 'staff.updateProfile',
          { id: state.edit.principal.principalId, input: { patch, expectedVersion: profile.version } },
          [`员工编号：${state.edit.principal.principalId}`, '只修改业务档案字段']);
      } else if (kind === 'position') {
        const position = state.edit.position;
        const codes = values.permissionCodes || [];
        if (!text(values.name).trim() || new Set(codes).size !== codes.length || codes.some(code => !permissions.includes(code))) {
          throw new Error('职位名称或权限列表无效');
        }
        const body = { name: text(values.name).trim(), description: text(values.description).trim(),
          active: values.active === true, permissionCodes: codes };
        const update = !!position.id;
        if (update) body.expectedVersion = position.version;
        prepare(update ? '核对职位修改' : '核对创建职位', update ? 'positions.update' : 'positions.create',
          update ? { id: position.id, input: body } : { input: body },
          [`职位：${body.name}`, `权限：${codes.length} 项`]);
      } else if (kind === 'members') {
        const position = state.edit.position;
        const ids = values.principalIds || [];
        if (new Set(ids).size !== ids.length) throw new Error('员工编号不能重复');
        prepare('核对职位员工关联', 'positions.members',
          { id: position.id, input: { expectedVersion: position.version, principalIds: ids } },
          [`职位：${position.name}`, `员工：${ids.length} 人`]);
      }
    }

    async function onClick(event) {
      if (disposed) return;
      const target = event.target.closest('[data-settings-action], [data-settings-section]');
      if (!target || state.busy) return;
      const action = target.dataset.settingsAction;
      const section = target.dataset.settingsSection;
      try {
        if (section) {
          state.section = section;
          paint();
        } else if (action === 'refresh') await open(state.page);
        else if (action === 'confirm') await commit();
        else if (action === 'cancel-review') cancelReview();
        else if (action === 'cancel-edit') { state.edit = null; state.error = ''; paint(); }
        else if (action === 'new-campus') { state.edit = { name: '', address: '', phone: '', rooms: [], subjects: [], grades: [] }; paint(); }
        else if (action === 'edit-campus') { state.edit = items(state.data).find(row => row.id === target.dataset.id) || null; paint(); }
        else if (action === 'new-position') { state.edit = { type: 'position', position: { name: '', description: '', active: true, permissionCodes: [] } }; paint(); }
        else if (action === 'edit-position') { state.edit = { type: 'position', position: items(state.data).find(row => row.id === target.dataset.id) }; paint(); }
        else if (action === 'edit-members') {
          const position = items(state.data).find(row => row.id === target.dataset.id);
          const principals = await allPrincipals();
          state.edit = { type: 'members', position, principals }; paint();
        } else if (action === 'edit-staff') {
          const principal = items(state.data).find(row => row.principalId === target.dataset.id);
          if (!principal || !safeSegment(principal.principalId)) throw new Error('员工编号无效');
          const profile = await settingsApi.staff.profile(principal.principalId);
          state.edit = { principal, profile }; paint();
        } else if (action === 'reset-password') {
          if (identity.scope !== 'all' || !has('system.access.manage')) throw new Error('当前账号无权重置员工密码');
          const principalId = target.dataset.id;
          if (!safeSegment(principalId) || !items(state.data).some(row => row.principalId === principalId && row.active)) {
            throw new Error('员工账号不存在、已停用或已归档');
          }
          if (!root.confirm?.(`确认重置员工 ${principalId} 的密码？完成后其现有登录会话将被注销。`)) return;
          const keys = JSON.parse(root.sessionStorage?.getItem(resetStorageKey) || '{}');
          const requestKey = typeof keys[principalId] === 'string' ? keys[principalId] : key();
          keys[principalId] = requestKey;
          root.sessionStorage?.setItem(resetStorageKey, JSON.stringify(keys));
          state.busy = true; paint();
          try {
            const result = await settingsApi.staff.resetPassword(principalId, requestKey);
            if (result?.user?.principalId !== principalId || result.user.credentialChangeRequired !== true
                    || typeof result.initialPassword !== 'string' || !result.initialPassword) {
              throw new Error('服务端未确认重置结果；请使用相同请求重试。');
            }
            state.credentials = result;
            delete keys[principalId];
            root.sessionStorage?.setItem(resetStorageKey, JSON.stringify(keys));
            state.notice = '';
          } finally { state.busy = false; paint(); }
        } else if (action === 'copy-reset-password') {
          if (!state.credentials?.initialPassword) throw new Error('一次性密码已清除');
          await root.navigator?.clipboard?.writeText(state.credentials.initialPassword);
          state.notice = '密码已复制；请安全交付并关闭此页。'; paint();
        } else if (action === 'dismiss-reset-password') {
          state.credentials = null; state.notice = '一次性密码已从页面清除。'; paint();
        }
      } catch (error) { state.error = fail(error); paint(); }
    }

    function bind(element) {
      if (disposed) return;
      container = element;
      if (listenersBound || !container) return;
      listenersBound = true;
      clickListener = event => { void onClick(event); };
      submitListener = event => {
        if (disposed) return;
        const form = event.target.closest('form[data-settings-form]');
        if (!form) return;
        event.preventDefault();
        void submit(form).catch(error => { state.error = fail(error); paint(); });
      };
      container.addEventListener('click', clickListener);
      container.addEventListener('submit', submitListener);
    }

    function dispose() {
      disposed = true;
      container?.removeEventListener?.('click', clickListener);
      container?.removeEventListener?.('submit', submitListener);
      container = null;
      clickListener = null;
      submitListener = null;
      clear();
    }

    return Object.freeze({ pages, open, html, bind, reload: load, close: clear, dispose });
  }

  root.siyueJavaSettings = Object.freeze({ pages, create });
})(typeof window === 'object' ? window : globalThis);
