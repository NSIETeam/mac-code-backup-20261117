const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../ui-annotations.js'), 'utf8');
const position = source.slice(source.indexOf('function position(){'), source.indexOf(' function shell(){'));
function place(rect, { tab = false, width = 1400, height = 850 } = {}) {
  const classes = new Set();
  const button = { style: {}, classList: { add: x => classes.add(x), remove: x => classes.delete(x) } };
  const target = { isConnected: true, matches: () => true, closest: () => tab ? target : null, getBoundingClientRect: () => rect };
  vm.runInNewContext(position + ';position();', { document: { getElementById: () => button }, target, innerWidth: width, innerHeight: height });
  return { left: parseFloat(button.style.left), top: parseFloat(button.style.top), visible: classes.has('is-visible') };
}
test('annotation stays outside logout and compact controls instead of stealing clicks', () => {
  for (const rect of [
    { left: 1325, right: 1375, top: 110, bottom: 142 },
    { left: 200, right: 240, top: 8, bottom: 38 },
    { left: 200, right: 240, top: 8, bottom: 840 },
  ]) {
    const p = place(rect);
    assert.equal(p.visible, true);
    assert.ok(p.left + 30 <= rect.left || p.left >= rect.right || p.top + 30 <= rect.top || p.top >= rect.bottom);
  }
});
test('work tab annotation retains the reserved space away from the close button', () => {
  assert.deepEqual(place({ left: 200, right: 400, top: 150, bottom: 195 }, { tab: true }), { left: 336, top: 154, visible: true });
});
test('no free space hides annotation rather than covering an interactive control', () => {
  assert.equal(place({ left: 0, right: 1400, top: 0, bottom: 850 }).visible, false);
});
