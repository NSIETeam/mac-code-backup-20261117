/* Fixed Java sales-report adapter; callers cannot choose arbitrary paths. */
(function (root) {
  'use strict';
  const date = value => {
    if (value == null || value === '') return undefined;
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)
      || new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) !== value) throw new TypeError('Invalid sales date');
    return value;
  };
  const employee = value => {
    if (value == null || value === '') return undefined;
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(value)) throw new TypeError('Invalid employee identifier');
    return value;
  };
  const paging = input => {
    const limit = input?.limit ?? 100;
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) throw new TypeError('Invalid sales page size');
    const query = { limit };
    const id = employee(input?.employeeId);
    if (id) query.employeeId = id;
    if (input?.action) query.action = String(input.action);
    if (input?.leadId) query.leadId = employee(input.leadId);
    if (input?.from) query.from = date(input.from);
    if (input?.to) query.to = date(input.to);
    if (input?.cursor) {
      if (typeof input.cursor !== 'string' || input.cursor.length > 160) throw new TypeError('Invalid sales cursor');
      query.cursor = input.cursor;
    }
    return query;
  };
  function create({ request }) {
    if (typeof request !== 'function') throw new TypeError('Sales API requires the authenticated request adapter');
    return Object.freeze({
      report: query => {
        const safe = {};
        const id = employee(query?.employeeId);
        if (id) safe.employeeId = id;
        if (query?.from) safe.from = date(query.from);
        if (query?.to) safe.to = date(query.to);
        return request('GET', 'crm/reports/sales', { query: safe });
      },
      activity: query => request('GET', 'crm/sales/activity', { query: paging(query) }),
      followups: query => request('GET', 'crm/sales/followups', { query: paging(query) })
    });
  }
  root.siyueJavaSalesApi = Object.freeze({ create });
})(typeof window === 'object' ? window : globalThis);
