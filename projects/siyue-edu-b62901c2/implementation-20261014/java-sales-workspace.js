/* Sales metrics and historical activity pages backed only by the Java API. */
(function (root) {
  'use strict';
  const pages = [
    { id: 'salesMetrics', title: '销售统计', anyPermissions: ['report.read', 'lead.conversion.read'] },
    { id: 'salesActivity', title: '销售活动', anyPermissions: ['sales.activity.read', 'audit.read', 'lead.assign', 'crm.lead.assign', 'system.access.manage'] }
  ];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[char]);
  const currency = value => {
    const amount = Number(value);
    return Number.isFinite(amount) ? amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—';
  };
  const dateInput = value => value instanceof Date ? value.toISOString().slice(0, 10) : '';
  const responseData = value => value?.data ?? value;

  function create({ api, identity, render = () => {}, now = () => new Date() }) {
    const sales = api?.salesWorkspace;
    if (!sales?.report || !sales?.activity || !sales?.followups) throw new TypeError('Sales workspace requires the named Java sales API');
    const today = dateInput(now());
    const state = { mode: 'report', from: `${today.slice(0, 7)}-01`, to: today, report: null,
      activity: null, followups: null, error: '', loading: false, cursor: null, employeeId: '' };
    let container = null, bound = false;
    const has = permission => (identity.permissions || []).includes(permission);
    const canRead = () => has('report.read') || has('lead.conversion.read');
    const selfOnly = () => !has('report.read') || identity.scope === 'own';
    const activitySelfOnly = () => identity.scope === 'own' || (!has('audit.read') && !has('lead.assign')
      && !has('crm.lead.assign') && !has('system.access.manage'));
    const paint = () => render(html());
    const fail = error => error?.message || '销售数据读取失败，请重试';

    async function open(page = 'salesMetrics') {
      if (page === 'salesActivity') {
        if (!has('sales.activity.read') && !has('audit.read') && !has('lead.assign')
            && !has('crm.lead.assign') && !has('system.access.manage')) throw new Error('当前账号没有销售历史记录权限');
        state.cursor = null;
        return loadActivity('activity');
      }
      if (page !== 'salesMetrics') throw new TypeError('Unsupported Java sales page');
      if (!canRead()) throw new Error('当前账号没有销售统计权限');
      state.mode = 'report'; state.error = ''; state.loading = true; paint();
      try { state.report = responseData(await sales.report({ from: state.from, to: state.to })); }
      catch (error) { state.error = fail(error); }
      finally { state.loading = false; paint(); }
    }
    async function loadActivity(mode, next = false) {
      if (!has('sales.activity.read') && !has('audit.read') && !has('lead.assign') && !has('crm.lead.assign')
          && !has('system.access.manage')) throw new Error('当前账号没有销售历史记录权限');
      state.mode = mode; state.error = ''; state.loading = true; paint();
      try {
        const query = { employeeId: activitySelfOnly() ? identity.principalId : state.employeeId || undefined,
          limit: mode === 'activity' ? 100 : 50,
          ...(mode === 'activity' ? { from: state.from, to: state.to } : {}),
          ...(next && state.cursor ? { cursor: state.cursor } : {}) };
        const result = responseData(mode === 'activity' ? await sales.activity(query) : await sales.followups(query));
        const key = mode;
        state[key] = next ? { ...result, items: [...(state[key]?.items || []), ...(result.items || [])] } : result;
        state.cursor = result.nextCursor || null;
      } catch (error) { state.error = fail(error); }
      finally { state.loading = false; paint(); }
    }
    async function changeRange(from, to) {
      if (from && to && from > to) { state.error = '开始日期不能晚于结束日期'; paint(); return; }
      state.from = from; state.to = to;
      if (state.mode === 'activity') { state.cursor = null; await loadActivity('activity'); }
      else await open();
    }
    function html() {
      const report = state.report?.items || [];
      const title = state.mode === 'report' ? '销售统计' : state.mode === 'activity' ? '销售活动' : '历史跟进';
      const activityAllowed = has('sales.activity.read') || has('audit.read') || has('lead.assign')
        || has('crm.lead.assign') || has('system.access.manage');
      const tabs = `<div class="actions">${canRead() ? `<button class="btn ${state.mode === 'report' ? 'primary' : ''}" data-sales-action="report">销售统计</button>` : ''}${activityAllowed ? `<button class="btn ${state.mode === 'activity' ? 'primary' : ''}" data-sales-action="activity">销售活动</button><button class="btn ${state.mode === 'followups' ? 'primary' : ''}" data-sales-action="followups">历史跟进</button>` : ''}</div>`;
      const range = ['report', 'activity'].includes(state.mode) ? `<form data-sales-range class="filters"><label>开始日期 <input type="date" name="from" value="${esc(state.from)}"></label><label>结束日期 <input type="date" name="to" value="${esc(state.to)}"></label><button class="btn" type="submit">查询</button></form>` : '';
      const rows = state.mode === 'report' ? report.map(row => `<tr><td>${esc(row.employeeId)}</td><td>${row.convertedUnique} / ${row.assignedUnique}</td><td>${row.lowSample ? '样本不足 · 不参与排名' : `${(Number(row.conversionRate) * 100).toFixed(1)}%`}</td><td>${currency(row.receivedAmount)}</td><td>${currency(row.refundedAmount)}</td><td>${currency(row.netReceivedAmount)}</td><td>${row.averageHoursToConversion == null ? '—' : `${esc(row.averageHoursToConversion)} 小时`}</td></tr>`).join('')
        : (state[state.mode]?.items || []).map(item => `<tr><td>${esc(item.at)}</td><td>${esc(item.employeeId)}</td><td>${esc(item.leadId)}</td><td>${esc(state.mode === 'activity' ? item.action : item.channel)}</td><td>${esc(state.mode === 'activity' ? item.outcome : item.note)}</td></tr>`).join('');
      const headers = state.mode === 'report' ? '<th>员工</th><th>转化 / 分配线索</th><th>转化率</th><th>实收</th><th>退款</th><th>净实收</th><th>平均成交时长</th>' : '<th>时间</th><th>员工</th><th>线索</th><th>类型</th><th>内容</th>';
      const summary = state.mode === 'report' && state.report ? `<p class="muted">${esc(state.report.scope)} · 按分配时间统计唯一线索。${esc(state.report.cohortDescription)}。样本少于 ${Math.max(0, ...report.map(item => item.minimumSample || 10))} 条时隐藏比率排名。</p>` : '';
      return `<section class="java-sales-workspace"><div class="heading"><div><h1>${title}</h1></div>${tabs}</div>${state.error ? `<p class="notice" role="alert">${esc(state.error)}</p>` : ''}${range}${summary}${state.loading ? '<p role="status">正在读取销售数据…</p>' : ''}<div class="table-wrap"><table class="table"><thead><tr>${headers}</tr></thead><tbody>${rows || `<tr><td colspan="${state.mode === 'report' ? 7 : 5}">${state.loading ? '正在读取…' : '暂无记录'}</td></tr>`}</tbody></table></div>${state[state.mode]?.hasMore ? '<button class="btn" data-sales-action="more">加载更多</button>' : ''}</section>`;
    }
    function bind(element) {
      if (!element || bound) return;
      container = element; bound = true;
      container.addEventListener('click', event => {
        const button = event.target.closest('[data-sales-action]');
        if (!button) return;
        const action = button.dataset.salesAction;
        if (action === 'report') void open('salesMetrics');
        else if (action === 'activity' || action === 'followups') { state.cursor = null; void loadActivity(action); }
        else if (action === 'more') void loadActivity(state.mode, true);
      });
      container.addEventListener('submit', event => {
        const form = event.target.closest('[data-sales-range]');
        if (!form) return;
        event.preventDefault();
        const data = new FormData(form);
        void changeRange(data.get('from'), data.get('to'));
      });
    }
    return Object.freeze({ pages, open, html, bind, canLeave: () => true, dispose: () => { container = null; bound = false; } });
  }
  root.siyueJavaSales = Object.freeze({ pages, create });
})(typeof window === 'object' ? window : globalThis);
