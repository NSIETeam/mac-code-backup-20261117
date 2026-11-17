(function (root) {
  'use strict';
  const allowedBase = /^\/(?:crm-api\/v2|java-candidate-[A-Za-z0-9-]+\/api\/v2)\/$/;

  function resolve() {
    const location = root.location;
    if (!location || typeof location.origin !== 'string' || typeof location.pathname !== 'string') {
      throw new TypeError('Java API deployment location is unavailable');
    }
    const body = root.document?.body;
    const configured = body?.getAttribute?.('data-java-api-base') ?? body?.dataset?.javaApiBase ?? '';
    const candidate = location.pathname.match(/^(\/java-candidate-[A-Za-z0-9-]+)(?:\/|$)/);
    if (!configured) {
      if (candidate) {
        throw new TypeError('Candidate deployment has no Java API base configuration');
      }
      return '/crm-api/v2/';
    }
    if (typeof configured !== 'string' || !allowedBase.test(configured)
        || (candidate && configured !== `${candidate[1]}/api/v2/`)
        || (!candidate && configured !== '/crm-api/v2/')) {
      throw new TypeError('Java API base must be an approved same-origin path');
    }
    const target = new URL(configured, location.origin);
    if (target.origin !== location.origin || target.pathname !== configured || target.search || target.hash
        || target.username || target.password) {
      throw new TypeError('Java API base must be an approved same-origin path');
    }
    return target.pathname;
  }

  function isCrmPath(pathname) {
    const candidate = root.location?.pathname?.match(/^(\/java-candidate-[A-Za-z0-9-]+)(?:\/|$)/);
    if (candidate) {
      return pathname === `${candidate[1]}/crm/` || pathname === `${candidate[1]}/crm/index.html`;
    }
    return pathname === '/crm/' || pathname === '/crm/index.html';
  }

  root.SiyueJavaApiBase = Object.freeze({ resolve, isCrmPath });
})(globalThis);
