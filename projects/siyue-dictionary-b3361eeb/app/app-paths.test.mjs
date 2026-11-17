import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { appPath, normalizeBasePath } from './app-paths.mjs';

const appRoutes = [
  '/api/me', '/api/members', '/api/account/register', '/api/account/login',
  '/api/account/profile', '/api/account/logout', '/api/needs', '/api/needs/need-id', '/api/needs/need-id/rating', '/api/needs/need-id/rating/history',
  '/api/recordings', '/api/recordings/recording-id', '/brand-icon.svg',
];

test('root deployment keeps API and public asset paths unchanged', () => {
  assert.equal(normalizeBasePath(), '/');
  for (const route of appRoutes) assert.equal(appPath(route), route);
});
test('work deployment prefixes every API, recording, and logo path once', () => {
  assert.equal(normalizeBasePath('/work'), '/work/');
  for (const route of appRoutes) assert.equal(appPath(route, '/work/'), `/work${route}`);
  assert.equal(appPath('api/me', '/work'), '/work/api/me');
});
test('nested deployment paths preserve encoded IDs and query strings', () => {
  assert.equal(appPath('/api/recordings/recording%20id?download=1', '/tools/work/'), '/tools/work/api/recordings/recording%20id?download=1');
});
test('invalid bases cannot silently point the app to another site or path', () => {
  for (const value of ['https://example.com/work/', '//example.com/work/', 'work/', '/work?x=1', '/work#tab', '/work\\other', '/work/../other', '/work//other']) {
    assert.throws(() => normalizeBasePath(value), /SIYUE_BASE_PATH/);
  }
});
test('the compile-time base is used by actual fetch calls without altering request options', async () => {
  const previousBase = globalThis.__SIYUE_BASE_PATH__;
  const previousFetch = globalThis.fetch;
  const calls = [];
  try {
    globalThis.__SIYUE_BASE_PATH__ = '/work/';
    globalThis.fetch = async (...args) => { calls.push(args); return new Response('{}'); };
    const scoped = await import('./app-paths.mjs?compiled-work-base');
    const options = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"recordingIds":[]}' };
    await scoped.appFetch('/api/needs', options);
    assert.deepEqual(calls, [['/work/api/needs', options]]);
    assert.equal(scoped.appPath('/api/recordings/audio-id'), '/work/api/recordings/audio-id');
  } finally {
    if (previousBase === undefined) delete globalThis.__SIYUE_BASE_PATH__; else globalThis.__SIYUE_BASE_PATH__ = previousBase;
    globalThis.fetch = previousFetch;
  }
});
test('Vite reads the deployment environment for asset base, app base, and dev API proxy', async () => {
  const { loadConfigFromFile } = await import('vite');
  const configFile = fileURLToPath(new URL('../standalone/vite.config.ts', import.meta.url));
  const previous = process.env.SIYUE_BASE_PATH;
  try {
    process.env.SIYUE_BASE_PATH = '/work';
    const work = await loadConfigFromFile({ command: 'build', mode: 'production' }, configFile);
    assert.equal(work.config.base, '/work/');
    assert.equal(work.config.define.__SIYUE_BASE_PATH__, '"/work/"');
    assert.equal(work.config.server.proxy['/work/api'].rewrite('/work/api/me?test=1'), '/api/me?test=1');
    assert.equal(work.config.publicDir, fileURLToPath(new URL('../public', import.meta.url)));
    delete process.env.SIYUE_BASE_PATH;
    const root = await loadConfigFromFile({ command: 'build', mode: 'production' }, configFile);
    assert.equal(root.config.base, '/');
    assert.equal(root.config.define.__SIYUE_BASE_PATH__, '"/"');
    assert.equal(root.config.server.proxy['/api'].rewrite('/api/me'), '/api/me');
  } finally { if (previous === undefined) delete process.env.SIYUE_BASE_PATH; else process.env.SIYUE_BASE_PATH = previous; }
});
test('HTML transformation prefixes the favicon and module entry without creating a release build', async () => {
  const { createServer } = await import('vite');
  const previous = process.env.SIYUE_BASE_PATH;
  let server;
  try {
    process.env.SIYUE_BASE_PATH = '/work/';
    server = await createServer({ configFile: fileURLToPath(new URL('../standalone/vite.config.ts', import.meta.url)), server: { middlewareMode: true }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
    const html = await readFile(new URL('../standalone/index.html', import.meta.url), 'utf8');
    const output = await server.transformIndexHtml('/work/', html);
    assert.match(output, /href="\/work\/brand-icon\.svg"/);
    assert.match(output, /src="\/work\/main\.tsx"/);
    assert.doesNotMatch(output, /%BASE_URL%/);
  } finally {
    await server?.close();
    if (previous === undefined) delete process.env.SIYUE_BASE_PATH; else process.env.SIYUE_BASE_PATH = previous;
  }
});
