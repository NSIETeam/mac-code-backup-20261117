// Validates the actual standalone page engine without a browser or DOM mock.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('cpu-demo.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const context = {module:{exports:{}}};
vm.runInNewContext(script, context);
const {CPU,assemble} = context.module.exports;
const fixtures = JSON.parse(fs.readFileSync('build/browser-fixtures.json','utf8'));
let total = 0;
for(const test of fixtures.fixtures){
  const cpu = new CPU(test.program.map(([a,w])=>[a,w]).reduce((m,[a,w])=>m.set(a,w), new Map()));
  for(let a=0;a<65536;a++)cpu.mem.set(a,(a*73+19)&65535);
  const actual=[];
  while(!cpu.halted&&cpu.cycles<20000){
    const e=cpu.tick();
    if(e.store)actual.push(['W',e.store.address,e.store.value]);
    if(e.retired)actual.push(['R',e.pc,e.word,[...cpu.regs]]);
  }
  assert.deepEqual(JSON.parse(JSON.stringify(actual)),test.events,test.name);
  assert.deepEqual([cpu.cycles,cpu.retired,Number(cpu.fault)],test.end,test.name+' cycle count');
  total+=cpu.retired;
}
for(const test of fixtures.sources)assert.deepEqual([...assemble(test.source)],test.words,'assembler encoding');
for(const source of ['LDI R8,0','ADDI R1,256','LD R1,R2,-33','JMP 2048','LI R1,65536','LUI R1,256','NOP 1','foo: NOP\nfoo: HALT'])assert.throws(()=>assemble(source),source);
const reset = new CPU(assemble('LDI R0,0\nLDI R1,7\nLD R1,R0,0\nHALT'));
reset.mem.set(0,42);
while(!reset.pending)reset.tick();
reset.reset();
assert.equal(reset.regs[1],7);assert.equal(reset.pc,0);assert.equal(reset.pending,null);assert.equal(reset.mem.get(0),42);
console.log(`PASS HTML engine: ${fixtures.fixtures.length} programs, ${total} instructions, ${fixtures.sources.length} assembler comparisons, invalid input and reset tests`);
