const fs=require('fs'),ts=require('typescript');
const p='server/QuestionBankAssets/app.mjs',s=fs.readFileSync(p,'utf8'),source=fs.readFileSync('miniprogram/services/question-bank.ts','utf8').split('// Shared deterministic')[1];
const shared=ts.transpileModule('// Shared deterministic'+source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.ES2020}}).outputText;
let app=s.replace(/\/\/ BEGIN SHARED QUESTION TEXT[\s\S]*?\/\/ END SHARED QUESTION TEXT\n/,'');
app='// BEGIN SHARED QUESTION TEXT\n'+shared+'// END SHARED QUESTION TEXT\n'+app;
const start=app.indexOf('function math(root)'),end=app.indexOf('\nfunction diagram',start);
app=app.slice(0,start)+`function math(root){for(const target of (root.matches?.('.stem')?[root]:root.querySelectorAll('.stem'))){if(target.dataset.mathRendered)continue;const source=target.textContent;target.replaceChildren();for(const part of questionTextParts(source)){if(!part.math){target.append(document.createTextNode(readableMath(part.text)));continue;}const span=document.createElement('span');try{if(!window.katex)throw Error('Formula renderer unavailable');span.innerHTML=window.katex.renderToString(part.text,{displayMode:part.display,trust:false,throwOnError:true,strict:'ignore',maxExpand:1000});}catch{span.textContent=readableMath('$'+part.text+'$');span.className='math-fallback';span.title='公式使用线性记号显示，请结合原题核对';}target.append(span);}target.dataset.mathRendered='1';}}`+app.slice(end);
fs.writeFileSync(p,app);
const html='server/QuestionBankAssets/index.html';fs.writeFileSync(html,fs.readFileSync(html,'utf8').replace('app.mjs?v=20261102-black1','app.mjs?v=20261102-text1'));
