import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { filterQuestions, chooseQuestions, validateImport, fingerprint, paperSummary, safeUrl, TYPES } from '../dist/engine.mjs';

const bank = JSON.parse(await readFile(new URL('../dist/data/questions.json', import.meta.url), 'utf8'));
const manifest = JSON.parse(await readFile(new URL('../dist/data/manifest.json', import.meta.url), 'utf8'));
const q = (stem = '计算 1+1', extra = {}) => ({ id: stem, subject: '数学', stage: '初中', type: '解答题', year: '2026', category: '', tags: ['运算'], difficulty: '基础', stem, answer: '2', issues: [], score: 5, ...extra });

test('the bundled public bank has provenance, unique question IDs and canonical stems', () => {
  assert.equal(bank.length, manifest.count); assert.equal(bank.length + manifest.deduplicated, manifest.rawCount);
  assert.equal(new Set(bank.map(x => x.id)).size, bank.length);
  assert.equal(new Set(bank.map(fingerprint)).size, bank.length);
  assert.equal(new Set(bank.map(x => x.subject)).size, 9);
  assert.equal(bank.filter(x => x.issues.length).length, manifest.flaggedCount);
  for (const row of bank) {
    assert.ok(row.stem.trim()); assert.ok(TYPES.includes(row.type)); assert.equal(row.stage, '高中');
    assert.ok(row.sourceUrl.includes(manifest.revision)); assert.equal(row.difficulty, '未标注');
    assert.ok(Number(row.score) > 0); assert.equal(typeof row.answer, 'string');
  }
});

test('filters respect subject, stage, year, type, tags, difficulty and all search words', () => {
  const qs = [q('运算方程', { year: '2025' }), q('运算方程', { subject: '物理' }), q('运算方程', { stage: '高中' })];
  const matches = filterQuestions(qs, { subject: '数学', stage: '初中', year: '2025', type: '解答题', tag: '运算', difficulty: '基础', keyword: '运算 方程' });
  assert.equal(matches.length, 1); assert.equal(matches[0], qs[0]);
  assert.equal(filterQuestions(qs, { keyword: '不存在' }).length, 0);
});

test('high school data does not silently satisfy a primary or junior grade filter', () => {
  assert.equal(filterQuestions(bank, { stage: '小学' }).length, 0);
  assert.equal(filterQuestions(bank, { stage: '初中' }).length, 0);
});

test('auto-assembly supplies exact type counts and excludes flagged, selected and duplicate questions', () => {
  const candidates = filterQuestions(bank, { subject: '数学', cleanOnly: true });
  const selected = candidates.slice(0, 5);
  const before = JSON.stringify(candidates);
  const counts = { '选择题': 4, '填空题': 4, '解答题': 2, '综合题': 0 };
  const result = chooseQuestions(candidates, counts, selected, () => 0.3);
  assert.equal(result.length, 10);
  for (const [type, count] of Object.entries(counts)) assert.equal(result.filter(q => q.type === type).length, count);
  assert.equal(new Set(result.map(fingerprint)).size, 10);
  assert.ok(!result.some(q => q.issues.length || selected.some(s => fingerprint(q) === fingerprint(s))));
  assert.equal(JSON.stringify(candidates), before);
});

test('shortage is an atomic failure and never fills with unrelated questions', () => {
  const selected = [q('已选')];
  const snapshot = JSON.stringify(selected);
  assert.throws(() => chooseQuestions([q('只有一道')], { '解答题': 2 }, selected), /仅 1 道/);
  assert.equal(JSON.stringify(selected), snapshot);
  assert.throws(() => chooseQuestions([q()], { '解答题': 1.5 }), /1–100/);
  assert.throws(() => chooseQuestions([q()], { '解答题': -1 }), /1–100/);
  assert.throws(() => chooseQuestions([q()], { '解答题': 101 }), /1–100/);
});

test('exclude-used matches normalized content rather than only an imported ID', () => {
  const used = new Set([fingerprint(q('计算 1+1'))]);
  assert.equal(filterQuestions([q('计 算 1+1', { id: 'other' })], { excludeUsed: true }, used).length, 0);
});

test('JSON import is validated completely, deduplicates, preserves plain text and rejects unsafe links', () => {
  const payload = { questions: [{ ...q(), stem: '<img src=x onerror=alert(1)>', sourceUrl: 'javascript:alert(1)' }, q('已有'), q('已 有')] };
  const result = validateImport(payload, [q('已有')]);
  assert.equal(result.questions.length, 1); assert.equal(result.duplicates, 2);
  assert.equal(result.questions[0].stem, '<img src=x onerror=alert(1)>');
  assert.equal(result.questions[0].sourceUrl, '');
  assert.match(result.questions[0].id, /^local-/);
  assert.throws(() => validateImport([q(), { ...q(), type: '未知题型' }]), /第 2 道题/);
  assert.throws(() => validateImport([{ ...q(), score: -1 }]), /分值/);
  assert.throws(() => validateImport([{ ...q(), stage: '大学' }]), /学段/);
  assert.throws(() => validateImport([{ ...q(), difficulty: '随便' }]), /难度/);
});

test('missing answers and images remain visible as issues instead of being invented', () => {
  const result = validateImport([{ ...q('如图所示，求 x。'), answer: '' }]);
  assert.equal(result.questions[0].answer, '');
  assert.ok(result.questions[0].issues.includes('缺少参考答案'));
  assert.ok(result.questions[0].issues.includes('题干可能依赖未附图形'));
  assert.equal(filterQuestions(result.questions, { cleanOnly: true }).length, 0);
});

test('score overrides and source text remain independent', () => {
  const row = q(); const summary = paperSummary([row], { [row.id]: 8 });
  assert.equal(summary.score, 8); assert.equal(row.score, 5);
  assert.equal(safeUrl('https://example.org/source'), 'https://example.org/source');
  assert.equal(safeUrl('https://user:pass@example.org/'), '');
  assert.equal(safeUrl('data:text/html,hello'), '');
});
