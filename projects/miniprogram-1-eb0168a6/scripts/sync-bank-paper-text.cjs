const fs=require('fs'),ts=require('typescript');
const source=fs.readFileSync('miniprogram/services/question-bank.ts','utf8').split('// Shared deterministic')[1];
if(!source)throw Error('Shared question renderer missing');
fs.writeFileSync('server/question_bank_text.mjs',ts.transpileModule('// Shared deterministic'+source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.ES2020}}).outputText);
