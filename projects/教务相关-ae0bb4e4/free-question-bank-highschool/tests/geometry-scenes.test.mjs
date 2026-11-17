import test from 'node:test';
import assert from 'node:assert/strict';
import { geometryTemplates } from '../dist/geometry.mjs';
import { buildScene, solveScene } from '../dist/geometry-scenes.mjs';
import { validateScene } from '../dist/geometry-kernel.mjs';

const approx = (actual, expected, label) => {
  const aa = Array.isArray(actual) ? actual : [actual], ee = Array.isArray(expected) ? expected : [expected];
  assert.equal(aa.length, ee.length, label);
  aa.forEach((v, i) => assert.ok(Math.abs(v - ee[i]) <= 1e-8 * Math.max(1, Math.abs(v), Math.abs(ee[i])), `${label}: ${v} != ${ee[i]}`));
};

test('all 24 scenes independently solve original 100 variants and larger boundary instances', () => {
  assert.equal(geometryTemplates.length, 24);
  for (const template of geometryTemplates) for (const n of [...Array.from({ length: 100 }, (_, i) => i + 1), 1000, 10000]) {
    const old = template.make(n), scene = buildScene(template.id, old.parameters);
    assert.ok(scene.relations.length, template.id);
    assert.equal(validateScene(scene).passed, true, template.id);
    assert.equal(scene.parameters, undefined, 'Scene does not retain a second hidden parameter source');
    approx(solveScene(template.id, scene), old.expected, `${template.id}/${n}`);
  }
});

test('scene construction rejects invalid parameters, degenerate ellipses/chords and unknown templates', () => {
  for (const [id, params] of [['ellipse', { a: 3, b: 3 }], ['ellipse', { a: 2, b: 3 }], ['chord', { t: 3, r: 0.5 }], ['perpendicular', { a: 0, b: 3 }], ['circle-equation', { h: 0, k: 0, r: -1 }], ['point-distance', { a: NaN, b: 2 }], ['slope', { m: Infinity, b: 2 }], ['sphere-area', {}], ['sphere-area', { r: 1e10 }], ['point-line', { x: 0, y: 0, c: 0 }]]) assert.throws(() => buildScene(id, params), id);
  assert.throws(() => buildScene('not-a-template', {}));
});

test('conic scenes retain explicit axes, focal geometry, and line equations', () => {
  const ellipse = buildScene('ellipse', { a: 5, b: 3 });
  assert.deepEqual(ellipse.points.F1, [-4, 0]);
  assert.deepEqual(solveScene('ellipse', ellipse), [8, 0.8]);
  assert.equal(ellipse.curves.kind, 'ellipse');
  const hyperbola = buildScene('hyperbola', { a: 3, b: 4 });
  assert.deepEqual(hyperbola.lines.l1, [4, -3, 0]);
  approx(solveScene('hyperbola', hyperbola), 4 / 3);
  const parabola = buildScene('parabola', { p: 6 });
  assert.deepEqual(parabola.points.F, [3, 0]);
  assert.deepEqual(parabola.lines.directrix, [1, 0, 3]);
  assert.deepEqual(solveScene('parabola', parabola), [3, -3]);
});

test('solid models use actual 3D coordinates and surfaces, with vertical radius/height separation', () => {
  for (const id of ['cylinder-volume', 'cone-volume', 'sphere-area', 'sphere-volume']) {
    const scene = buildScene(id, { r: 3, ...(id.startsWith('sphere') ? {} : { h: 4 }) });
    assert.equal(scene.dimension, 3);
    assert.deepEqual(scene.points.O, [0, 0, 0]);
    assert.deepEqual(scene.points.A, [3, 0, 0]);
    assert.deepEqual(scene.points.P, [0, 0, id.startsWith('sphere') ? 3 : 4]);
    assert.ok(scene.surfaces.kind);
    assert.equal(scene.circles, undefined, 'A 3D sphere is never represented as a planar circle');
  }
  const box = buildScene('space-diagonal', { a: 3, b: 4, c: 12 });
  assert.equal(solveScene('space-diagonal', box), 13);
  assert.equal(box.surfaces.faces.length, 6);
  assert.equal(solveScene('pyramid-volume', buildScene('pyramid-volume', { a: 6, h: 4 })), 48);
});

test('scene mutations of circle points, tangent, chord, angle, orthogonality and similarity fail closed', () => {
  const cases = [
    ['circle-tangent', { r: 5 }, s => { s.points.P[1] = 1; }],
    ['circle-tangent', { r: 5 }, s => { s.lines.l[0] = 2; }],
    ['chord', { t: 3, r: 5 }, s => { s.points.A[0] += 1; }],
    ['triangle-area', { a: 5, b: 3 }, s => { s.points.C[1] *= 2; }],
    ['perpendicular', { a: 3, b: 4 }, s => { s.vectors.v[0] += 1; }],
    ['similar', { a: 3, b: 4 }, s => { s.points.F[0] += 1; }],
    ['space-diagonal', { a: 3, b: 4, c: 12 }, s => { s.points.A1[2] = 0; }],
  ];
  for (const [id, params, mutate] of cases) {
    const scene = buildScene(id, params); mutate(scene);
    assert.equal(validateScene(scene).passed, false, id);
    assert.throws(() => solveScene(id, scene), id);
  }
});

test('independent solver does not trust an answer field or curve metadata', () => {
  const triangle = buildScene('triangle-area', { a: 8, b: 5 });
  triangle.expected = 999; triangle.parameters = { a: 999, b: 999 };
  approx(solveScene('triangle-area', triangle), 10);
  const ellipse = buildScene('ellipse', { a: 5, b: 3 });
  ellipse.curves.a = 999; ellipse.curves.b = 998;
  assert.deepEqual(solveScene('ellipse', ellipse), [8, 0.8]);
  // Metadata is for rendering; main model validation must also compare its bindings.
});

test('midpoint and similar-triangle correspondence preserve specified conventions', () => {
  const midpoint = buildScene('midpoint', { a: 2, b: 3 });
  assert.deepEqual(midpoint.points.A, [2, 3]); assert.deepEqual(midpoint.points.B, [8, 11]);
  assert.deepEqual(solveScene('midpoint', midpoint), [5, 7]);
  const similar = buildScene('similar', { a: 3, b: 4 });
  assert.deepEqual(similar.points.A, [0, 3]); assert.deepEqual(similar.points.C, [4, 0]);
  assert.equal(solveScene('similar', similar), 8);
});
