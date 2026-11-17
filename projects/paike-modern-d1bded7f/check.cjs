'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const path = require('node:path');
const root = __dirname;
const html = fs.readFileSync(path.join(root,'index.html'),'utf8');
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(js);
new vm.Script(fs.readFileSync(path.join(root,'server.js'),'utf8'));
assert(!/\p{Extended_Pictographic}/u.test(html), 'UI must use SVG instead of emoji');
assert(!/^(?:<<<<<<< |=======\s*$|>>>>>>> )/m.test(html), 'unresolved merge markers');
const definitions = new Set([...html.matchAll(/<symbol id="([^"]+)"/g)].map(m=>m[1]));
for(const match of html.matchAll(/<use href="#(i-[a-z]+)"/g)) assert(definitions.has(match[1]), 'undefined SVG '+match[1]);
assert(!/placeholder="[^"\n]*<svg/.test(html), 'SVG cannot appear inside placeholder');
assert(!/\.(?:placeholder|textContent)\s*=[^\n]*<svg/.test(html), 'SVG cannot be assigned as plain text');
assert(html.includes('expectedVersion: localVersion'));
for(const file of ['index.html','server.js','check.cjs','test.cjs']) {
  const source=fs.readFileSync(path.join(root,file),'utf8');
  assert(!/^(?:<<<<<<< |=======\s*$|>>>>>>> )/m.test(source),file+' has merge conflicts');
}
console.log('PASS syntax, merge markers, SVG references, emoji and version contract');
