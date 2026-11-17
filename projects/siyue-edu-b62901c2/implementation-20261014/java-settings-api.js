/* Narrow adapter for system settings endpoints; never exposes arbitrary paths. */
(function (root) {
  'use strict';

  const segment = value => {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,120}$/.test(value)) {
      throw new TypeError('Invalid settings resource identifier');
    }
    return encodeURIComponent(value);
  };
  const pageQuery = query => {
    const page = query?.page ?? 1, size = query?.size ?? 50;
    if (!Number.isInteger(page) || page < 1 || page > 100000 || !Number.isInteger(size) || size < 1 || size > 100) {
      throw new TypeError('Invalid settings page');
    }
    return { page, size };
  };
  const key = value => {
    if (typeof value !== 'string' || !/^[A-Za-z0-9._:-]{16,100}$/.test(value)) throw new TypeError('Invalid idempotency key');
    return value;
  };

  function create({ request }) {
    if (typeof request !== 'function') throw new TypeError('Settings API requires the authenticated request adapter');
    const write = (method, path, body, idempotencyKey) => request(method, path, { body, idempotencyKey: key(idempotencyKey) });
    return Object.freeze({
      settings: Object.freeze({
        get: () => request('GET', 'settings'),
        update: (section, values, idempotencyKey) => write('PUT', 'settings', { section, values }, idempotencyKey)
      }),
      campuses: Object.freeze({
        list: query => request('GET', 'settings/campuses', { query: pageQuery(query) }),
        get: id => request('GET', `settings/campuses/${segment(id)}`),
        create: (input, idempotencyKey) => write('POST', 'settings/campuses', input, idempotencyKey),
        update: (id, input, idempotencyKey) => write('PUT', `settings/campuses/${segment(id)}`, input, idempotencyKey)
      }),
      dictionary: Object.freeze({
        get: () => request('GET', 'settings/dictionary'),
        update: (dictionaryKey, values, idempotencyKey) => write('PUT', 'settings/dictionary', { key: dictionaryKey, values }, idempotencyKey)
      }),
      staff: Object.freeze({
        list: query => request('GET', 'system/access/principals', { query: pageQuery(query) }),
        profile: id => request('GET', `system/staff/${segment(id)}/profile`),
        updateProfile: (id, input, idempotencyKey) => write('PUT', `system/staff/${segment(id)}/profile`, input, idempotencyKey),
        resetPassword: (id, idempotencyKey) => write('POST', `system/access/principals/${segment(id)}/reset-password`, {}, idempotencyKey)
      }),
      positions: Object.freeze({
        list: query => request('GET', 'system/positions', { query: pageQuery(query) }),
        create: (input, idempotencyKey) => write('POST', 'system/positions', input, idempotencyKey),
        update: (id, input, idempotencyKey) => write('PUT', `system/positions/${segment(id)}`, input, idempotencyKey),
        members: (id, input, idempotencyKey) => write('PUT', `system/positions/${segment(id)}/members`, input, idempotencyKey)
      }),
      audit: Object.freeze({
        list: query => {
          const allowed = {};
          for (const name of ['actorId', 'action', 'resourceId', 'fromAt', 'untilAt']) {
            if (query?.[name]) allowed[name] = String(query[name]);
          }
          return request('GET', 'audit', { query: { ...allowed, ...pageQuery(query) } });
        }
      })
    });
  }

  root.siyueJavaSettingsApi = Object.freeze({ create });
})(typeof window === 'object' ? window : globalThis);
