import {mountPlatformInbox} from './platform-inbox.mjs?v=20261115-polish';

import {restoreBrowserSession} from './platform-auth.mjs?v=20261115-polish';

const workbench = typeof location !== 'undefined' && location.pathname.startsWith('/workbench/')
  ? '/workbench/'
  : '/miniapp/workbench/';
const modules = [
  {id: 'printing', label: '错题打印', href: workbench + '#home'},
  {id: 'research', label: '教研库', href: workbench + '#material-library'},
  {id: 'lab', label: 'AI Lab', href: '/ai-lab/assignments/'},
  {id: 'collaboration', label: '项目协作平台', href: '/miniapp/collaboration/'},
];

const researchRoutes = new Set([
  'material-library', 'question-bank', 'central-research', 'book-pages',
  'material-edition', 'handout-library', 'courses', 'course', 'course-materials',
]);

export function activeModule({pathname, hash, search}) {
  if (pathname.startsWith('/ai-lab/') || pathname.startsWith('/training/')) return 'lab';
  if (pathname.startsWith('/ip-workbench/operations') && new URLSearchParams(search).get('experience') === 'ip-studio') return 'lab';
  if (pathname.startsWith('/miniapp/collaboration/')) return 'collaboration';
  if (/^\/(?:miniapp\/)?(?:research|question-bank)\//.test(pathname)) return 'research';
  const [route, query] = hash.slice(1).split('?');
  if (researchRoutes.has(route) || new URLSearchParams(query).get('central') === '1') {
    return 'research';
  }
  return 'printing';
}

function mountPlatformShell() {
  if (document.body.hasAttribute('data-siyue-platform')) return;
  const shell = document.createElement('aside');
  shell.className = 'platform-shell';
  shell.innerHTML = `
    <div class="platform-shell-heading">
      <button type="button" class="platform-shell-toggle" aria-expanded="false"
        aria-controls="platform-shell-nav">
        <span class="platform-shell-menu-icon" aria-hidden="true"></span>模块
      </button>
      <div class="platform-shell-brand">思跃工作台</div>
      <span class="platform-shell-current" aria-hidden="true"></span>
    </div>
    <nav id="platform-shell-nav" aria-label="思跃工作台模块">
      ${modules.map(({id, label, href}) => `<a href="${href}" data-module="${id}">${label}</a>`).join('')}
    </nav>
    <button type="button" class="platform-inbox-button" aria-haspopup="dialog">
      站内通知<span class="platform-inbox-badge" aria-hidden="true" hidden></span>
    </button>`;
  document.body.prepend(shell);
  document.body.setAttribute('data-siyue-platform', '');
  document.documentElement.setAttribute('data-siyue-theme', 'ios7');

  const toggle = shell.querySelector('button');
  const title = shell.querySelector('.platform-shell-current');
  const links = [...shell.querySelectorAll('[data-module]')];
  const setOpen = open => {
    toggle.setAttribute('aria-expanded', String(open));
    shell.toggleAttribute('data-open', open);
  };
  function sync() {
    const current = activeModule(location);
    document.body.dataset.siyuePlatform = current;
    for (const link of links) {
      if (link.dataset.module === current) {
        link.setAttribute('aria-current', 'page');
        title.textContent = link.textContent;
      } else {
        link.removeAttribute('aria-current');
      }
    }
    setOpen(false);
  }
  toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') === 'false'));
  shell.querySelector('nav').addEventListener('click', event => {
    if (event.target.closest('a')) setOpen(false);
  });
  document.addEventListener('pointerdown', event => {
    if (!shell.contains(event.target)) setOpen(false);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') {
      setOpen(false);
      toggle.focus();
    }
  });
  window.addEventListener('hashchange', sync);
  window.addEventListener('pageshow', sync);
  sync();
  void restoreBrowserSession().then(() => mountPlatformInbox(shell.querySelector('.platform-inbox-button')));

}

if (typeof window !== 'undefined') mountPlatformShell();

