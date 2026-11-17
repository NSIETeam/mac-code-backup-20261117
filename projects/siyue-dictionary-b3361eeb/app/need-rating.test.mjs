import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import React from 'react';
import { AccountRequestScope } from './account-request-scope.ts';
import { renderToStaticMarkup } from 'react-dom/server';
import { build } from 'esbuild';
import { defaultNeedScope, isOwnNeed, mergeNeedRefresh, ratingCellText, ratingExportFields, shouldSyncRatingDraft, validateRatingInput, visibleRating } from './need-rating-data.ts';

const rating = { score: 0, comment: '这条评语仅提交人和评审可见', reviewerId: 'reviewer-id', reviewerName: '评审姓名', updatedAt: '2026-10-26T12:34:00.000Z', version: 2 };
const need = { id: 'need-id', ownerId: 'employee-id', author: '提交时的旧姓名', rating, canViewRating: true, canScore: false, canDelete: false, version: 9 };

test('zero is a real score in list and export, distinct from unrated', () => {
  assert.equal(ratingCellText(need), '0分');
  assert.deepEqual(ratingExportFields(need), [0, rating.comment, rating.reviewerName, rating.updatedAt]);
  assert.equal(ratingCellText({ ...need, rating: null }), '待评分');
});
test('denied rating visibility suppresses every rating field even if a response mistakenly includes them', () => {
  const denied = { ...need, canViewRating: false };
  assert.equal(visibleRating(denied), null); assert.equal(ratingCellText(denied), '—');
  assert.deepEqual(ratingExportFields(denied), ['', '', '', '']);
  assert.equal(ratingCellText({ ...denied, rating: null }), '—');
});
test('missing permissions fail closed and cannot make a hidden rating look pending', () => {
  const unknown = { ...need, canViewRating: undefined };
  assert.equal(visibleRating(unknown), null); assert.equal(ratingCellText(unknown), '—');
  assert.deepEqual(ratingExportFields(unknown), ['', '', '', '']);
});
test('my suggestions follow stable employee ID after a name change', () => {
  assert.equal(isOwnNeed(need, 'employee-id'), true);
  assert.equal(isOwnNeed({ ...need, author: '改名后的提交姓名' }, 'employee-id'), true);
  assert.equal(isOwnNeed(need, 'someone-with-the-same-name'), false);
  assert.equal(isOwnNeed(need, undefined), false); assert.equal(isOwnNeed({ ownerId: '' }, ''), false);
});
test('employees default to mine and only an explicit reviewer capability defaults to all', () => {
  assert.equal(defaultNeedScope(true), 'all'); assert.equal(defaultNeedScope(false), 'mine'); assert.equal(defaultNeedScope(undefined), 'mine');
});
test('score validation accepts 0 and 100 but refuses empty, fractional, out-of-range and oversized comments', () => {
  assert.equal(validateRatingInput('0', ''), ''); assert.equal(validateRatingInput('100', '评语'), '');
  for (const score of ['', ' ', '-1', '101', '50.5', 'NaN', 'Infinity']) assert.ok(validateRatingInput(score, ''));
  assert.ok(validateRatingInput('60', '字'.repeat(1501)));
});
test('refreshing score versions does not overwrite an unsaved reviewer draft', () => {
  assert.equal(shouldSyncRatingDraft(true, 2, 3), false);
  assert.equal(shouldSyncRatingDraft(false, 2, 3), true);
  assert.equal(shouldSyncRatingDraft(false, 2, 2), false);
});
test('late refresh cannot revert a newer visible score or re-enable deletion', () => {
  const older = { ...need, rating: { ...rating, score: 99, version: 1 }, canDelete: true };
  const merged = mergeNeedRefresh(need, older);
  assert.equal(merged.rating.score, 0); assert.equal(merged.rating.version, 2); assert.equal(merged.canDelete, false);
  assert.equal(mergeNeedRefresh(need, { ...need, rating: { ...rating, version: 3 } }).rating.version, 3);
});
test('permission revocation clears a cached visible score rather than preserving it during refresh', () => {
  const denied = { ...need, rating: null, canViewRating: false, canScore: false };
  assert.equal(mergeNeedRefresh(need, denied), denied); assert.equal(mergeNeedRefresh(undefined, denied), denied);
});

// Compile only the isolated component in memory; no app build or files are emitted.
const compiled = await build({ entryPoints: [fileURLToPath(new URL('./need-rating.tsx', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'cjs', packages: 'external', jsx: 'automatic', logLevel: 'silent' });
const module = { exports: {} };
new Function('require', 'module', 'exports', compiled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports);
const Panel = module.exports.NeedRatingPanel;
const props = { disabled: false, onBusy() {}, onSaved() {}, onReload: async () => undefined, onUnauthorized: async () => {}, isSessionCurrent: () => true };

test('unauthorized detail renders no score, comment, history control or editor', () => {
  assert.equal(renderToStaticMarkup(React.createElement(Panel, { ...props, need: { ...need, canViewRating: false, canScore: true } })), '');
});
test('author detail renders a read-only zero score and comment without a scoring form', () => {
  const html = renderToStaticMarkup(React.createElement(Panel, { ...props, need }));
  assert.match(html, /0<small>/); assert.ok(html.includes(rating.comment)); assert.ok(html.includes('改分记录'));
  assert.doesNotMatch(html, /<form|name="score"|保存评分|保存修改/);
});
test('reviewer detail exposes integer score constraints and optional comment', () => {
  const html = renderToStaticMarkup(React.createElement(Panel, { ...props, need: { ...need, canScore: true } }));
  assert.match(html, /<form/); const input = html.match(/<input\b[^>]*name="score"[^>]*>/)?.[0] ?? "";
  for (const attribute of ['min="0"', 'max="100"', 'step="1"']) assert.ok(input.includes(attribute));
  assert.match(html, /评语（选填）/); assert.match(html, /保存修改/);
});

test('a late reviewer refresh cannot populate a different employee session', async () => {
  const scope = new AccountRequestScope(); scope.identify('reviewer-id', 'reviewer');
  const token = scope.capture(); let finish; const response = new Promise(resolve => { finish = resolve; }); let displayed = [];
  const pending = response.then(needs => { if (scope.isCurrent(token)) displayed = needs; });
  scope.invalidate(); displayed = []; scope.identify('employee-id', 'employee');
  finish([{ ...need, canViewRating: true }]); await pending;
  assert.deepEqual(displayed, []);
});
test('logout invalidates private responses before the logout request completes', () => {
  const scope = new AccountRequestScope(); scope.identify('reviewer-id', 'reviewer'); const token = scope.capture();
  scope.invalidate(); assert.equal(scope.isCurrent(token), false); assert.equal(scope.capture().employeeId, '');
});
test('logging back into the same account does not revive a previous session request', () => {
  const scope = new AccountRequestScope(); scope.identify('same-account', 'reviewer'); const token = scope.capture();
  scope.invalidate(); scope.identify('same-account', 'reviewer'); assert.equal(scope.isCurrent(token), false);
});
test('a late account lookup cannot restore the logged-out reviewer identity', async () => {
  const scope = new AccountRequestScope(); scope.identify('reviewer-id', 'reviewer'); const token = scope.capture();
  let finish; const response = new Promise(resolve => { finish = resolve; });
  const pending = response.then(employeeId => { if (scope.isCurrent(token)) scope.identify(employeeId, 'reviewer'); });
  scope.invalidate(); scope.identify('employee-id', 'employee'); finish('reviewer-id'); await pending;
  assert.equal(scope.capture().employeeId, 'employee-id');
});
test('reviewer permission changes invalidate old requests even when the employee ID is unchanged', () => {
  const scope = new AccountRequestScope(); scope.identify('employee-id', 'reviewer'); const token = scope.capture();
  assert.equal(scope.identify('employee-id', 'employee'), true); assert.equal(scope.isCurrent(token), false);
});
test('unchanged identity and access preserve same-session requests across a profile rename', () => {
  const scope = new AccountRequestScope(); scope.identify('employee-id', 'employee'); const token = scope.capture();
  assert.equal(scope.identify('employee-id', 'employee'), false); assert.equal(scope.isCurrent(token), true);
});
