import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluateExpression as evaluate, expressionLatex, checkConstraints, validateScene } from '../dist/geometry-kernel.mjs';

const op = (op, ...args) => ({ op, args });
const ref = name => ({ ref: name });
const clone = x => JSON.parse(JSON.stringify(x));
const base = () => ({ dimension: 2, points: { O: [0, 0], A: [3, 0], B: [0, 4], H: [1.5, 2] },
  lines: { l: [4, 3, -12] }, circles: { omega: { center: 'O', radius: 3 } },
  vectors: { u: [3, 0], v: [0, 4], w: [6, 0] }, segments: [['A', 'B']], relations: [
    { id: 'distance', kind: 'distance', points: ['A', 'B'], value: 5 },
    { id: 'circle', kind: 'on_circle', point: 'A', circle: 'omega' },
    { id: 'line', kind: 'on_line', point: 'H', line: 'l' },
    { id: 'right', kind: 'perpendicular', vectors: ['u', 'v'] },
    { id: 'parallel', kind: 'parallel', vectors: ['u', 'w'] },
    { id: 'midpoint', kind: 'midpoint', point: 'H', ends: ['A', 'B'] },
    { id: 'angle', kind: 'angle', points: ['A', 'O', 'B'], degrees: 90 },
  ] });

test('AST evaluates exact-operation structure, parameter references, and previous-step values', () => {
  const context = { parameters: { a: 3, b: 4 }, steps: { squared: { value: 25 } } };
  const expr = op('sqrt', op('add', op('pow', ref('parameters.a'), 2), op('pow', ref('parameters.b'), 2)));
  assert.equal(evaluate(expr, context), 5);
  assert.equal(evaluate(op('sqrt', ref('steps.squared')), context), 5);
  assert.equal(evaluate(ref('steps.x'), { steps: [{ id: 'x', value: 7 }] }), 7);
  assert.equal(evaluate(op('abs', op('neg', 3))), 3);
  assert.equal(evaluate(op('sub', 7, 4)), 3);
  assert.ok(Math.abs(evaluate(op('sin', op('div', op('pi'), 2))) - 1) < 1e-12);
  assert.equal(evaluate(op('cos', 0)), 1);
  assert.equal(expressionLatex(op('div', op('sqrt', ref('parameters.a')), 2), context), '\\frac{\\sqrt{3}}{2}');
});

test('AST rejects unknown operators, unsafe references, invalid domains, overflows and depth', () => {
  const invalid = [NaN, Infinity, '3', {}, { op: 'eval', args: [] }, op('div', 1, 0), op('sqrt', -1), op('pow', 0, 0), op('pow', -1, 0.5), op('pow', 1e90, 2), op('add', 1), op('pi', 2), ref('parameters.constructor'), ref('parameters.a.b'), { ref: 'parameters.a', op: 'neg' }];
  for (const ast of invalid) { assert.throws(() => evaluate(ast, { parameters: { a: 1 } })); assert.throws(() => expressionLatex(ast, { parameters: { a: 1 } })); }
  assert.throws(() => evaluate(ref('parameters.a'), { parameters: { a: '3' } }));
  let deep = 1; for (let i = 0; i < 100; i++) deep = op('neg', deep);
  assert.throws(() => evaluate(deep));
});

test('parameter and independent constraint checks reject missing, extra, non-integer and degenerate domains', () => {
  const model = { parameterSpec: { a: { min: 1, max: 50, integer: true }, b: { min: 1, max: 50, integer: true } }, parameters: { a: 5, b: 3 }, constraints: [{ id: 'ellipse-domain', relation: 'gt', left: ref('parameters.a'), right: ref('parameters.b') }] };
  assert.equal(checkConstraints(model).passed, true);
  for (const parameters of [{ a: 3, b: 3 }, { a: -1, b: 2 }, { a: 5.5, b: 3 }, { a: 5 }, { a: 5, b: 3, extra: 1 }, { a: NaN, b: 3 }]) assert.equal(checkConstraints({ ...model, parameters }).passed, false);
  assert.equal(checkConstraints({ ...model, constraints: [{ ...model.constraints[0], relation: 'bogus' }] }).passed, false);
  assert.equal(checkConstraints({ ...model, constraints: [model.constraints[0], model.constraints[0]] }).passed, false);
  assert.equal(checkConstraints(null).passed, false);
});

test('independent coordinates verify distance, incidence, orthogonality, parallelism, midpoint and angle', () => {
  const result = validateScene(base());
  assert.equal(result.passed, true, JSON.stringify(result));
  for (const id of ['distance', 'circle', 'line', 'right', 'parallel', 'midpoint', 'angle']) assert.equal(result.checks.find(c => c.id === id).passed, true);
});

test('mutation: a wrong answer distance and modified circle/line points cannot pass', () => {
  const changes = [s => { s.relations[0].value = 6; }, s => { s.circles.omega.radius = 2; }, s => { s.points.A = [2, 0]; }, s => { s.lines.l[2] = -13; }, s => { s.points.H = [1.5, 3]; }];
  for (const change of changes) { const scene = base(); change(scene); assert.equal(validateScene(scene).passed, false); }
});

test('mutation: wrong vector claims and wrong angle are rejected', () => {
  for (const change of [s => { s.vectors.v = [1, 4]; }, s => { s.vectors.w = [6, 1]; }, s => { s.relations.find(r => r.id === 'angle').degrees = 60; }]) {
    const scene = base(); change(scene); assert.equal(validateScene(scene).passed, false);
  }
});

test('scene rejects NaN, unknown points, malformed entities and degenerate geometry', () => {
  const changes = [s => { s.points.O[0] = NaN; }, s => { s.points.O = [0, 0, 0]; }, s => { s.points.O[0] = 1e12; }, s => { s.vectors.u = [0, 0]; }, s => { s.lines.l = [0, 0, 4]; }, s => { s.circles.omega.radius = 0; }, s => { s.segments = [['O', 'O']]; }, s => { s.relations[0].points = ['unknown', 'A']; }, s => { s.relations.push({ kind: 'unknown' }); }, s => { s.dimension = 4; }];
  for (const change of changes) { const scene = base(); change(scene); assert.equal(validateScene(scene).passed, false); }
  assert.equal(validateScene(null).passed, false);
  assert.equal(validateScene({ dimension: 2, points: {}, relations: [] }).passed, false);
});

test('similarity uses ordered side ratios, rejects altered vertices and collinear triangles', () => {
  const scene = { dimension: 2, points: { A: [0, 0], B: [3, 0], C: [0, 4], D: [10, 0], E: [16, 0], F: [10, 8] }, relations: [{ id: 'similar', kind: 'similar', triangles: [['A', 'B', 'C'], ['D', 'E', 'F']] }] };
  assert.equal(validateScene(scene).passed, true);
  const wrong = clone(scene); wrong.points.F = [10, 7]; assert.equal(validateScene(wrong).passed, false);
  const degenerate = clone(scene); degenerate.points.C = [6, 0]; degenerate.points.F = [22, 0]; assert.equal(validateScene(degenerate).passed, false);
  const duplicate = clone(scene); duplicate.relations[0].triangles[0] = ['A', 'A', 'C']; assert.equal(validateScene(duplicate).passed, false);
});

test('3D checks use spatial coordinates instead of projected SVG geometry', () => {
  const scene = { dimension: 3, points: { O: [0, 0, 0], A: [3, 4, 12], B: [3, 4, 0] }, vectors: { vertical: [0, 0, 12], base: [3, 4, 0] }, relations: [{ kind: 'distance', points: ['O', 'A'], value: 13 }, { kind: 'perpendicular', vectors: ['vertical', 'base'] }, { kind: 'angle', points: ['O', 'B', 'A'], degrees: 90 }] };
  assert.equal(validateScene(scene).passed, true);
  const projected = clone(scene); projected.points.A[2] = 0; assert.equal(validateScene(projected).passed, false);
});

test('scale-normalized incidence rejects line mismatch even for large coefficients', () => {
  const scene = { dimension: 2, points: { A: [3, 4] }, lines: { l: [3e8, 4e8, -25e8] }, relations: [{ kind: 'on_line', point: 'A', line: 'l' }] };
  // Coordinates/coefficients have a common sanity bound.
  scene.lines.l = [3e7, 4e7, -25e7];
  assert.equal(validateScene(scene).passed, true);
  scene.lines.l[2] += 1e5;
  assert.equal(validateScene(scene).passed, false);
});
