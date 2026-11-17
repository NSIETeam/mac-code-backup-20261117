const tokenKey = 'siyue_web_token';
const changeKey = 'siyue_web_session_change';
const apiRoot = new URL('../api/', import.meta.url);
export const browserSessionHeaders = {'X-Siyue-Web-Session': '1'};
export const currentBrowserToken = () => sessionStorage.getItem(tokenKey) || '';
let pending;

async function request(path, token) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    return await fetch(new URL('auth/' + path, apiRoot), {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {...browserSessionHeaders, ...(token ? {Authorization: 'Bearer ' + token} : {})},
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timeout);
  }
}

export function restoreBrowserSession() {
  if (pending) return pending;
  const previous = currentBrowserToken();
  pending = (async () => {
    try {
      const response = await request('browser-session', previous);
      if (response.ok) {
        const {token} = await response.json();
        if (token && token !== previous && currentBrowserToken() === previous) {
          sessionStorage.setItem(tokenKey, token);
          window.dispatchEvent(new Event('siyue:session-change'));
        }
      } else if (response.status === 401 && currentBrowserToken() === previous) {
        sessionStorage.removeItem(tokenKey);
        if (previous) window.dispatchEvent(new Event('siyue:session-change'));
      }
    } catch {
      // A temporary network failure does not invalidate a session.
    }
    return currentBrowserToken();
  })().finally(() => { pending = undefined; });
  return pending;
}

export function updateBrowserSession(token = '') {
  if (token) sessionStorage.setItem(tokenKey, token);
  else sessionStorage.removeItem(tokenKey);
  try { localStorage.setItem(changeKey, (token ? 'in:' : 'out:') + crypto.randomUUID()); } catch {}
  window.dispatchEvent(new Event('siyue:session-change'));
}

export async function logoutBrowserSession() {
  const response = await request('browser-logout', currentBrowserToken());
  if (!response.ok) throw Error('暂时无法退出，请重试');
  updateBrowserSession();
}

export function watchBrowserSession(onChange) {
  async function refresh(signedOut = false) {
    const previous = currentBrowserToken();
    if (signedOut) sessionStorage.removeItem(tokenKey);
    const current = await restoreBrowserSession();
    if (current !== previous) onChange(current);
  }
  window.addEventListener('storage', event => {
    if (event.key !== changeKey || !event.newValue) return;
    void refresh(event.newValue.startsWith('out:'));
  });
  window.addEventListener('pageshow', event => {
    if (event.persisted) void refresh();
  });
}

export function returnToWorkbenchForPassword() {
  const workbench = new URL('./', import.meta.url);
  workbench.searchParams.set('platform_return', location.pathname + location.search + location.hash);
  workbench.hash = 'home';
  location.replace(workbench);
}
