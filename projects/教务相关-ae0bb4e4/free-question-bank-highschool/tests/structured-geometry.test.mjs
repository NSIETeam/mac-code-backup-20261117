import test from 'node:test';
import assert from 'node:assert/strict';
import {geometryTemplates,generateGeometryBank,createGeometryQuestion,validateGeometryQuestion,runGeometryBatch,geometrySignature,templateCatalog,renderDiagram} from '../dist/geometry.mjs';
import {validateImport} from '../dist/engine.mjs';
const clone=x=>JSON.parse(JSON.stringify(x));
const first=generateGeometryBank(1);
test('24 canonical templates expose constraints, typed givens, dependency expressions, and independent checked scenes',()=>{
 assert.equal(templateCatalog().templates.length,24);
 for(const q of first){const r=validateGeometryQuestion(q);assert.ok(r.passed,q.templateId);assert.ok(r.checks.length>15);assert.equal(q.model.schema,'siyue.geometry/v2');assert.ok(q.model.givens.every(g=>g.kind==='scalar'));assert.ok(q.model.solution.steps.every(s=>s.expression&&Array.isArray(s.requires)));assert.ok(q.diagram.scene.relations.length);}
});
test('mutation: valid LaTeX wrong answers, explanations, stems, parameters and geometric bindings are rejected for every family',()=>{
 for(const q of first){for(const mutate of [x=>x.answer='$999$',x=>x.analysis='由勾股定理得 $a^2+b^2=999$。',x=>x.stem+='条件变更',x=>x.parameters[Object.keys(x.parameters)[0]]+=1,x=>x.diagram.parameters[Object.keys(x.parameters)[0]]+=1,x=>x.diagram.scene.points[Object.keys(x.diagram.scene.points)[0]][0]+=1,x=>x.geometry.expected=999,x=>x.model.givens[0].value+=1,x=>x.model.solution.steps[0].value+=1,x=>x.model.solution.steps[0].requires=['unknown-step'],x=>x.model.solution.steps[0].expression=999]){const bad=clone(q);mutate(bad);assert.equal(validateGeometryQuestion(bad).passed,false,q.templateId);}}
});
test('mutation: conic and solid display metadata cannot diverge from verified scene',()=>{
 for(const q of first.filter(q=>q.model.scene.curves||q.model.scene.surfaces)){const bad=clone(q);const key=bad.diagram.scene.curves?'curves':'surfaces';bad.diagram.scene[key].kind='wrong-shape';assert.equal(validateGeometryQuestion(bad).passed,false,q.templateId);}
});
test('batch seed is reproducible, bounded, deduplicated against existing questions, covers all families',()=>{
 const options={count:240,seed:'regression-20261102'};const a=runGeometryBatch(options),b=runGeometryBatch(options);
 assert.equal(a.stats.accepted,240);assert.equal(new Set(a.questions.map(q=>q.templateId)).size,24);assert.deepEqual(a,b);assert.equal(new Set(a.questions.map(geometrySignature)).size,240);
 const c=runGeometryBatch({...options,existing:a.questions});const used=new Set(a.questions.map(geometrySignature));assert.ok(c.questions.every(q=>!used.has(geometrySignature(q))));assert.ok(c.stats.duplicates>0);
 assert.throws(()=>runGeometryBatch({count:2001}));assert.throws(()=>runGeometryBatch({templateIds:['bad']}));assert.throws(()=>runGeometryBatch({templateIds:['chord'],parameterRanges:{r:{min:6,max:6}}}));
});
test('exhausted parameter space stops with exact actual count; impossible constraints reject instead of inventing questions',()=>{
 const b=runGeometryBatch({templateIds:['sphere-area'],count:5,parameterRanges:{r:{min:1,max:2}}});assert.equal(b.questions.length,2);assert.equal(b.stats.complete,false);assert.equal(b.stats.stopReason,'capacity-exhausted');assert.ok(b.stats.attempts<50);assert.equal(b.stats.duplicates,b.stats.attempts-2);
 const c=runGeometryBatch({templateIds:['ellipse'],count:1,parameterRanges:{a:{min:1,max:1},b:{min:2,max:2}}});assert.equal(c.questions.length,0);assert.equal(c.stats.attempts,0);assert.equal(c.stats.availableCapacity,0);
});
test('choice batch has exactly four distinct expression values and a unique verifiable key; choice mutations fail',()=>{
 const b=runGeometryBatch({count:76,format:'choice'});assert.equal(new Set(b.questions.map(q=>q.templateId)).size,19);
 for(const q of b.questions){assert.equal(q.type,'选择题');assert.ok(validateGeometryQuestion(q).passed);const bad=clone(q);bad.model.choice.correct='Z';assert.equal(validateGeometryQuestion(bad).passed,false);}
 assert.throws(()=>runGeometryBatch({templateIds:['ellipse'],format:'choice'}),/多个待求量/);
 for(const format of ['fill','long'])assert.ok(runGeometryBatch({count:24,format}).questions.every(q=>q.type===(format==='fill'?'填空题':'解答题')));
});
test('structured JSON roundtrip preserves models, scenes, choices and validation; invalid batch import is atomic',()=>{
 const batch=runGeometryBatch({count:24,format:'choice'}),parsed=JSON.parse(JSON.stringify(batch)),imported=validateImport(parsed);
 assert.equal(imported.questions.length,24);for(let i=0;i<24;i++){assert.deepEqual(imported.questions[i].model,batch.questions[i].model);assert.ok(validateGeometryQuestion(imported.questions[i]).passed);}
 parsed.questions[23].answer='$999$';assert.throws(()=>validateImport(parsed),/校验失败/);
});
test('all structured SVG numeric attributes and paths are finite, including 3D surfaces',()=>{
 const doc={createElementNS(ns,tag){return {tag,attrs:{},children:[],classList:{add(){}},setAttribute(k,v){this.attrs[k]=v;},append(x){this.children.push(x);}};}};
 for(const q of first){const svg=renderDiagram(q.diagram,doc);for(const item of svg.children)for(const value of Object.values(item.attrs))assert.ok(!/NaN|Infinity|undefined/.test(String(value)),q.templateId);}
});
test('canonical integer bounds and cross constraints fail closed',()=>{
 for(const[id,p]of[['ellipse',{a:2,b:2}],['circle-tangent',{r:0}],['perpendicular',{a:0,b:2}],['point-distance',{a:2.5,b:2}],['point-distance',{a:2,b:2,extra:1}]])assert.throws(()=>createGeometryQuestion(id,p));
});
test('structured classification and attribution cannot drift across import/storage',()=>{
 for(const mutate of [q=>q.subject='语文',q=>q.stage='小学',q=>q.difficulty='挑战',q=>q.tags=['伪标签'],q=>q.knowledgeIds=['语文.other'],q=>q.source='无出处']){const q=clone(first[0]);mutate(q);assert.equal(validateGeometryQuestion(q).passed,false);assert.throws(()=>validateImport([q]),/校验失败/);}
});
test('equation multiple-choice options retain the requested equation and avoid sign-equivalent hyperbola duplicates',()=>{
 for(const [id,params,prefix]of [['circle-tangent',{r:3},'x='],['hyperbola',{a:2,b:1},'y=\\pm ']]){const q=createGeometryQuestion(id,params,{format:'choice'});assert.match(q.stem,/所求方程/);assert.ok(q.model.choice.options.every(o=>o.latex.startsWith(prefix)&&o.value>0));assert.equal(new Set(q.model.choice.options.map(o=>Math.abs(o.value))).size,4);}
});

test('balanced generation counts accepted questions rather than rejected samples',()=>{const b=runGeometryBatch({count:240,seed:'balanced'});const counts=geometryTemplates.map(t=>b.questions.filter(q=>q.templateId===t.id).length);assert.equal(Math.max(...counts)-Math.min(...counts),0);});
