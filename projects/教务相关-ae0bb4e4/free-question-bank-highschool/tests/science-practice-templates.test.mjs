import test from 'node:test';
import assert from 'node:assert/strict';
import { sciencePracticeTemplates as templates } from '../dist/science-practice-templates.mjs';
import { evaluateExpression } from '../dist/geometry-kernel.mjs';
import { knowledgeNodes, knowledgeById } from '../dist/knowledge.mjs';
const cases={
 'phy-uniform-distance':[{v:3,t:4},12],
 'phy-accelerated-displacement':[{u:2,a:4,t:3},24],
 'phy-braking-distance':[{v:20,a:5},40],
 'phy-newton-force':[{m:6,a:3},18],
 'phy-kinetic-energy':[{m:4,v:3},18],
 'phy-gravitational-energy':[{m:5,h:6},300],
 'phy-series-current':[{U:12,R1:2,R2:4},2],
 'phy-joule-heat':[{I:2,R:3,t:5},60],
 'phy-wave-speed':[{f:50,lambdaCm:40},20],
 'phy-medium-wavelength':[{n100:150,fTHz:400},500],
 'phy-photoelectric-energy':[{photon:10,work:4},6],
 'phy-magnetic-force':[{BmT:200,I:5,Lcm:50},0.5],
 'chem-amount-from-mass':[{m:18,M:18},1],
 'chem-particle-count':[{nMilli:500},3.01],
 'chem-standard-gas-volume':[{nMilli:250},5600],
 'chem-molar-concentration':[{nMilli:500,VmL:250},2],
 'chem-solution-dilution':[{c10:20,V1:50,V2:200},0.5],
 'chem-solution-mixture':[{c1:10,V1:100,c2:20,V2:100},1.5],
 'chem-acid-base-neutralisation':[{cAcid:100,VAcid:25,cBase:50},50],
 'chem-magnesium-hydrogen':[{m:48},44.8],
 'chem-limiting-oxygen':[{H2:4,O2:5},3],
 'chem-carbonate-purity':[{m:200,purity:50},22.4],
 'chem-copper-electrolysis':[{I:10,t:1930},6.4],
 'chem-reaction-heat':[{q:100,nMilli:500},50],
};
const close=(a,b)=>Math.abs(a-b)<=1e-8*Math.max(1,Math.abs(a),Math.abs(b));
function* parameters(entries,index=0,p={}){if(index===entries.length){yield {...p};return;}const[k,r]=entries[index];for(let n=r.min;n<=r.max;n+=r.step||1){p[k]=n;yield* parameters(entries,index+1,p);}}

test('physics and chemistry each expose at least ten original high-school numerical families',()=>{
 assert.equal(new Set(templates.map(t=>t.id)).size,templates.length);
 for(const subject of ['物理','化学'])assert.ok(templates.filter(t=>t.subject===subject).length>=10);
 for(const t of templates){assert.ok(knowledgeById.has(t.knowledgeId),t.id);assert.equal(knowledgeById.get(t.knowledgeId).subject,t.subject);assert.equal(knowledgeNodes.some(n=>n.parentId===t.knowledgeId),false,'Leaf knowledge id required');assert.ok(t.id.startsWith(t.subject==='物理'?'phy-':'chem-'));}
});

test('hand-calculated examples agree with AST and independent substitution checks',()=>{
 for(const t of templates){const[p,expected]=cases[t.id];const built=t.build(p),answer=evaluateExpression(built.expression,{parameters:p});assert.ok(close(answer,expected),`${t.id}: ${answer} vs ${expected}`);assert.equal(t.verify(p,answer),true,t.id);assert.ok(built.stem.length>30);for(const key of ['unit','formula','explanation'])assert.ok(built[key],`${t.id}/${key}`);}
});

test('mutated answers and invalid numeric/physical parameter domains are rejected',()=>{
 for(const t of templates){const[p,expected]=cases[t.id];assert.equal(t.verify(p,expected+Math.max(1,Math.abs(expected)*0.1)),false,t.id);for(const value of [NaN,Infinity,'3'])assert.equal(t.verify(p,value),false,t.id);const key=Object.keys(p)[0];for(const bad of [{...p,[key]:NaN},{...p,[key]:p[key]+0.5},{...p,[key]:t.parameters[key].max+1},{...p,extra:1},{}]){assert.equal(t.constraints(bad),false,t.id);assert.throws(()=>t.build(bad),undefined,t.id);assert.equal(t.verify(bad,expected),false,t.id);}}
 for(const [id,p]of[['phy-photoelectric-energy',{photon:4,work:5}],['chem-limiting-oxygen',{H2:10,O2:2}],['chem-molar-concentration',{nMilli:1000,VmL:10}]]){const t=templates.find(t=>t.id===id);assert.equal(t.constraints(p),false);assert.throws(()=>t.build(p));}
});

test('each subject has at least 50,000 valid, distinct stems without replacing names or question numbers',()=>{
 for(const subject of ['物理','化学']){const stems=new Set();let checked=0;outer:for(const t of templates.filter(t=>t.subject===subject)){for(const p of parameters(Object.entries(t.parameters))){if(!t.constraints(p))continue;const built=t.build(p),answer=evaluateExpression(built.expression,{parameters:p});assert.equal(t.verify(p,answer),true,t.id);stems.add(built.stem);checked++;if(stems.size>=50000)break outer;}}assert.equal(stems.size,50000,subject);assert.equal(checked,50000,'No duplicate stems in enumerated subject corpus');}
});
