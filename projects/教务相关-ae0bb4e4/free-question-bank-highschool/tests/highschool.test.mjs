import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { geometryTemplates, generateGeometryBank, geometryPrompt, renderDiagram } from '../dist/geometry.mjs';
import { knowledgeNodes, knowledgePath, searchKnowledge, isDescendant, labelQuestion } from '../dist/knowledge.mjs';
import { QuestionSearch, normalize } from '../dist/search.mjs';
import { chooseQuestions } from '../dist/engine.mjs';
const generated=generateGeometryBank();
const publicBank=JSON.parse(fs.readFileSync(new URL('../dist/data/questions.json',import.meta.url)));
const bank=[...generated,...publicBank];
const index=new QuestionSearch(bank);
const moduleScope={exports:{}};
vm.runInNewContext(fs.readFileSync(new URL('../dist/vendor/katex/katex.min.js',import.meta.url),'utf8'),{module:moduleScope,exports:moduleScope.exports,console});
const katex=moduleScope.exports;
const near=(a,b)=>assert.ok(Math.abs(a-b)<1e-8*Math.max(1,Math.abs(b)),`${a} != ${b}`);

test('all 2400 original variants have unique IDs/stems, diagrams, known knowledge paths and solvable constraints',()=>{
  assert.equal(geometryTemplates.length,24);assert.equal(generated.length,2400);
  assert.equal(new Set(generated.map(q=>q.id)).size,2400);assert.equal(new Set(generated.map(q=>q.stem)).size,2400);
  const checks={
    'point-distance':p=>Math.sqrt((p.a)**2+(p.b)**2),midpoint:p=>[p.a+3,p.b+4],slope:p=>[p.m,p.b],
    'point-line':p=>Math.abs(3*p.x+4*p.y+p.c)/5,'parallel-distance':p=>(2*p.c+5)/5,
    'circle-equation':p=>[p.h,p.k,p.r*p.r],'circle-tangent':p=>p.r,chord:p=>2*Math.sqrt(5*5-(p.t/(p.t+1))**2),
    ellipse:p=>[2*Math.sqrt(p.a*p.a-p.b*p.b),Math.sqrt(1-(p.b/p.a)**2)],hyperbola:p=>p.b/p.a,parabola:p=>[p.p/2,-p.p/2],
    'space-diagonal':p=>Math.sqrt(p.a*p.a+p.b*p.b+p.c*p.c),'box-area':p=>2*p.a*p.b+2*p.a*p.c+2*p.b*p.c,
    'line-plane-angle':p=>p.c/Math.sqrt(p.a*p.a+p.b*p.b),'pyramid-volume':p=>p.a*p.a*p.h/3,
    'cylinder-volume':p=>Math.PI*p.r*p.r*p.h,'cone-volume':p=>Math.PI*p.r*p.r*p.h/3,
    'sphere-area':p=>4*Math.PI*p.r*p.r,'sphere-volume':p=>4/3*Math.PI*p.r**3,
    'dot-product':p=>2*p.a-p.b,perpendicular:p=>-p.b,'triangle-area':p=>.5*p.a*p.b*Math.sin(Math.PI/6),
    'cosine-law':p=>Math.sqrt(p.a*p.a+p.b*p.b-2*p.a*p.b*Math.cos(Math.PI/3)),similar:p=>2*p.b
  };
  for(const q of generated){
    assert.equal(q.stage,'高中');assert.equal(q.sourceKind,'original');assert.ok(q.diagram);assert.ok(q.geometry.facts.length);assert.ok(q.geometry.steps.length);
    assert.equal(labelQuestion(q).length,1);assert.equal(knowledgePath(q.knowledgeIds[0]).length,4);
    const expected=checks[q.templateId](q.parameters);const actual=q.geometry.expected;
    if(Array.isArray(expected))expected.forEach((v,i)=>near(actual[i],v));else near(actual,expected);
    for(const part of [q.stem,q.answer,q.analysis])for(const match of part.matchAll(/\$([^$]+)\$/g))katex.renderToString(match[1],{throwOnError:true,strict:'ignore',trust:false});
  }
});

test('each diagram produces SVG primitives for all template families without raw HTML',()=>{
  const doc={createElementNS(ns,tag){return {tag,attrs:{},children:[],classList:{add(){}},setAttribute(k,v){this.attrs[k]=v;},append(x){this.children.push(x);}};}};
  for(const q of generated.slice(0,24)){const svg=renderDiagram(q.diagram,doc);assert.equal(svg.tag,'svg');assert.ok(svg.children.length>=4,q.templateId);assert.ok(svg.children.every(n=>['line','circle','ellipse','path','text'].includes(n.tag)));}
});

test('parent knowledge selection includes descendants exactly once',()=>{
  const parent='数学.analytic',leaf='数学.analytic.line.point-line';
  const rows=index.query({subject:'数学',stage:'高中',knowledgeId:parent,cleanOnly:true}).rows;
  const child=index.query({subject:'数学',stage:'高中',knowledgeId:leaf,cleanOnly:true}).rows;
  assert.ok(child.length>=100);assert.ok(rows.length>child.length);assert.equal(new Set(rows.map(q=>q.id)).size,rows.length);
  assert.ok(child.every(q=>rows.includes(q)));
  assert.ok(child.every(q=>(index.labels.get(q.id)||[]).some(id=>isDescendant(id,leaf))));
  assert.ok(!index.query({subject:'物理',knowledgeId:parent}).rows.length);
});

test('hierarchy path search, aliases and repeated queries give exact matches',()=>{
  assert.ok(searchKnowledge('解析几何 / 圆 / 切线').some(n=>n.id==='数学.analytic.line.tangent'));
  assert.ok(searchKnowledge('点线距离').some(n=>n.id==='数学.analytic.line.point-line'));
  const filter={subject:'数学',stage:'高中',keyword:'数学 / 解析几何 / 点线距离',cleanOnly:true};
  const first=index.query(filter),second=index.query(filter);
  assert.ok(first.rows.length>=100);assert.equal(second.cacheHit,true);assert.deepEqual(second.rows,first.rows);
});

test('indexed search matches an exhaustive reference across combined filters and unseen keywords',()=>{
  const configs=[{subject:'数学',keyword:'椭圆'},{subject:'物理',keyword:'电场'},{subject:'数学',knowledgeId:'数学.solid',type:'填空题',cleanOnly:true},{subject:'数学',stage:'高中',sourceKind:'original',difficulty:'基础',keyword:'圆 切线'},{subject:'英语',year:'2020',keyword:'the'},{keyword:'不存在的检索词'}, {subject:'数学',keyword:'点'}, {subject:'数学',stage:'初中'}];
  for(const f of configs){
    const expected=bank.filter((q,i)=>(!f.subject||q.subject===f.subject)&&(!f.stage||q.stage===f.stage)&&(!f.type||q.type===f.type)&&(!f.year||q.year===f.year)&&(!f.difficulty||q.difficulty===f.difficulty)&&(!f.sourceKind||(q.sourceKind||'public')===f.sourceKind)&&(!f.cleanOnly||!q.issues.length)&&(!f.knowledgeId||(index.labels.get(q.id)||[]).some(id=>isDescendant(id,f.knowledgeId)))&&normalize(f.keyword).split(' ').filter(Boolean).every(t=>index.text[i].includes(t)));
    assert.deepEqual(index.query(f).rows.map(q=>q.id),expected.map(q=>q.id),JSON.stringify(f));
  }
});

test('AI reader cards omit the reference answer and separate mathematical givens from layout',()=>{
  const q=generated.find(q=>q.templateId==='point-line');const card=geometryPrompt(q);
  assert.equal(card.goal,q.geometry.goal);assert.deepEqual(card.mathematicalCoordinates,q.geometry.coordinates);
  assert.equal(card.answer,undefined);assert.equal(card.expected,undefined);assert.equal(card.steps,undefined);
  assert.match(card.diagram,/布局坐标不可当作数学坐标/);
  const raw=geometryPrompt(publicBank[0]);assert.deepEqual(raw.givens,[]);assert.match(raw.diagram,/尚无可信/);
});

test('automatic assembly prefers distinct template families before extra variants',()=>{
  const result=chooseQuestions(generated,{'填空题':8,'解答题':8},[],()=>.4);
  assert.equal(result.length,16);assert.equal(new Set(result.map(q=>q.templateId)).size,16);
});

test('knowledge counts are union counts and invalidate when excluded-used contents change',()=>{
  const filter={subject:'数学',stage:'高中',sourceKind:'original',excludeUsed:true};const a=generated[0];
  const without=index.counts(filter),withUsed=index.counts(filter,new Set([`${a.subject}|${a.stem.replace(/\s+/g,'')}`]));
  assert.equal(without.get(a.knowledgeIds[0])-withUsed.get(a.knowledgeIds[0]),1);
  assert.equal(new Set(knowledgeNodes.map(n=>n.id)).size,knowledgeNodes.length);
});
