const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const exportsFor={};vm.runInNewContext(ts.transpileModule(fs.readFileSync('miniprogram/services/question-bank.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports:exportsFor});
const {readableMath:r,normalizeQuestionText:n,questionTextParts:parts}=exportsFor;
test('real generated chemistry shorthand and geography Greek letters are readable',()=>{
 assert.equal(r(String.raw`关系式：$n_{O_2,剩}=n_{O_2,0}-\tfrac12n_{H_2,0}$。`),'关系式：n_(O_2,剩)=n_(O_2,0)-(1)/(2)n_(H_2,0)。');
 assert.equal(r(String.raw`$\eta=\frac{E}{Q}\times100\%,\varphi=30^\circ, a\leqslant b$`),'η=(E)/(Q)×100%,φ=30^°, a≤ b');
});
test('nested and unbraced TeX arguments retain their order and meaning',()=>{
 assert.equal(r(String.raw`$\frac{1}{\sqrt{5}}\pi$`),'(1)/(√(5))π');
 assert.equal(r(String.raw`$\frac12+\vec a+\sqrt[3]{8}$`),'(1)/(2)+→(a)+root[3](8)');
 assert.equal(r(String.raw`$\frac{\frac12}{3}$`),'((1)/(2))/(3)');
 assert.equal(r(String.raw`$x^{12}+\unknown{a}$`),'x^(12)+\\unknown{a}');
});
test('source typography is stripped without losing table cells, relations or line breaks',()=>{
 assert.equal(r(String.raw`\hfill\break 【详解】\\点\emph{P}，$A\Longleftrightarrow B\because a\geqslant b$`),'\n 【详解】\n点P，A⇔ B∵ a≥ b');
 assert.equal(r(String.raw`\begin{array}{cc}\multicolumn{2}{c}{温度}&值\\20&30\end{array}`),'\n温度&值\n20&30\n');
});
test('plain prices, escaped dollars and safe entity text remain readable',()=>{
 assert.equal(r('$2 a$'),'2 a');
 assert.equal(r('$15, X 、 Y$'),'15, X 、 Y');
 assert.equal(r('starting at $300 one night and $3000 for a suite'),'starting at $300 one night and $3000 for a suite');
 assert.equal(r(String.raw`price \$20; $x\leqslant 2$`),'price $20; x≤ 2');
 assert.equal(n('&lt;img src=x&gt; &amp; &#945;'),' <img src=x> & α'.trim());
 assert.equal(parts(String.raw`\[\frac12\]`).filter(p=>p.math).length,1);
 assert.equal(parts('$a+\nb$').filter(p=>p.math).length,1);
});
test('pathological nesting is bounded and raw source is not mutated',()=>{
 const source='$'+'\\frac{1}{'.repeat(80)+'x'+'}'.repeat(80)+'$';assert.doesNotThrow(()=>r(source));const q={analysis:String.raw`$\eta=\frac12$`};r(q.analysis);assert.equal(q.analysis,String.raw`$\eta=\frac12$`);
});
