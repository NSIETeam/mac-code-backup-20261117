import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import crypto from 'node:crypto';
import {materializePracticeRecipe,validatePracticeQuestion,practiceTemplates}from '../dist/practice.mjs';import{fingerprint,validateImport}from '../dist/engine.mjs';import{compactStored,expandStored}from '../dist/geometry-storage.mjs';import{QuestionSearch}from '../dist/search.mjs';
const root=new URL('../dist/data/expansion/',import.meta.url),manifest=JSON.parse(fs.readFileSync(new URL('manifest.json',root))),base=JSON.parse(fs.readFileSync(new URL('../dist/data/questions.json',import.meta.url)));
const read=part=>{const bytes=fs.readFileSync(new URL(part.file,root));assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),part.sha256);assert.equal(bytes.length,part.bytes);return JSON.parse(bytes);};
test('nine-subject targets count source questions, flags, generated variants and real gaps separately',()=>{
 assert.equal(Object.keys(manifest.subjects).length,9);let total=0,ready=0,flagged=0;for(const r of Object.values(manifest.subjects)){assert.equal(r.ready,r.sourceClean+r.variants);assert.equal(r.total,r.sourceQuestions+r.variants+(r.authoredFlagged||0));assert.equal(r.remaining,Math.max(0,20000-r.ready));total+=r.total;ready+=r.ready;flagged+=r.flagged;}
 assert.equal(manifest.totalMain,total);assert.equal(manifest.totalReady,ready);assert.equal(manifest.totalFlagged,flagged);assert.equal(total,ready+flagged);
 for(const s of ['数学','物理','化学','生物','地理'])assert.equal(manifest.subjects[s].ready,20000);assert.ok(manifest.subjects.语文.ready>=20000);
 for(const s of ['英语','政治','历史'])assert.ok(manifest.subjects[s].remaining>0);assert.ok(manifest.subjects.英语.researchRecords>60000);assert.equal(manifest.subjects.英语.variants,0);
});
test('all new source question shards have provenance, unique IDs, answer keys and material references',()=>{
 const ids=new Set(base.map(q=>q.id)),materials=new Map();let count=0;
 for(const r of Object.values(manifest.subjects)){let source=base.filter(q=>q.subject===r.subject).length;for(const part of r.questionChunks){const data=read(part);assert.equal(data.questions.length,part.count);source+=data.questions.filter(q=>q.sourceKind!=='original').length;for(const q of data.questions){count++;assert.ok(!ids.has(q.id));ids.add(q.id);assert.equal(q.stage,'高中');assert.ok(q.stem&&q.answer&&q.license&&q.sourceUrl);assert.equal(q.reviewed,false);if(q.materialId){if(!materials.has(q.materialChunk))materials.set(q.materialChunk,JSON.parse(fs.readFileSync(new URL(q.materialChunk,root))));const m=materials.get(q.materialChunk).materials.find(m=>m.id===q.materialId);assert.ok(m?.text.length>0);assert.ok(q.attribution.includes('CC BY-SA'));assert.equal(q.analysis,'');}}}assert.equal(source,r.sourceQuestions);}
 assert.ok(count>20000);
});
test('every generated recipe is unique, materializes, and passes independent substitution plus canonical bindings',()=>{
 const ids=new Set(),cores=new Set();let count=0;
 for(const r of Object.values(manifest.subjects)){let generated=0;for(const part of r.recipeChunks){const data=read(part);assert.equal(data.records.length,part.count);for(const recipe of data.records){const q=materializePracticeRecipe(recipe);assert.ok(validatePracticeQuestion(q).passed,q.id);assert.ok(!ids.has(q.id));ids.add(q.id);const spec=practiceTemplates.find(t=>t.id===recipe.t),key=spec.build(recipe.p).stem.replace(/\s+/g,'');assert.ok(!cores.has(key),q.id);cores.add(key);generated++;count++;}}assert.equal(generated+(r.subject==='数学'?2400:0)+(r.classicsQuestions||0)-(r.classicsFlagged||0),r.variants);}
 assert.ok(count>95000);
});
test('numeric recipes preserve checked models and types through import, local storage and JSON backup',()=>{
 const q=materializePracticeRecipe(read(manifest.subjects.物理.recipeChunks[0]).records[0]);const imported=validateImport([q]).questions[0];assert.ok(validatePracticeQuestion(imported).passed);const restored=expandStored(JSON.parse(JSON.stringify(compactStored([imported],'custom'))),'custom')[0];assert.ok(validatePracticeQuestion(restored).passed);assert.equal(restored.type,q.type);
 const bad=structuredClone(q);bad.answer='$999$';assert.throws(()=>validateImport([bad]),/校验失败/);
});
test('shared-material identity and attribution survive export/import; missing material is rejected atomically',()=>{
 const q=read(manifest.subjects.语文.questionChunks[0]).questions[0],data=JSON.parse(fs.readFileSync(new URL(q.materialChunk,root)));q.materialText=data.materials.find(m=>m.id===q.materialId).text;
 const restored=validateImport(JSON.parse(JSON.stringify({questions:[q]}))).questions[0];assert.equal(fingerprint(q),fingerprint(restored));assert.equal(restored.materialText,q.materialText);assert.equal(restored.attribution,q.attribution);const noBody={...q,materialText:''};assert.throws(()=>validateImport([q,noBody]),/阅读材料/);
});
test('search is scoped by subject and empty or flagged other-subject records never fill a query',()=>{
 const part=read(manifest.subjects.生物.recipeChunks[0]);const qs=part.records.map(materializePracticeRecipe),index=new QuestionSearch(qs);assert.ok(index.query({subject:'生物',keyword:'基因',cleanOnly:true}).rows.length>0);assert.equal(index.query({subject:'物理'}).rows.length,0);
});

test('cloze answer offsets match pinned public-domain source and non-BMP variants remain flagged',()=>{const part=manifest.subjects.语文.questionChunks.find(p=>p.file.includes('classics-cloze'));assert.ok(part);const qs=read(part).questions,sources=JSON.parse(fs.readFileSync(new URL('licenses/classics-sources.json',root))).sources;assert.equal(qs.length,206);for(const q of qs){const src=sources.find(s=>s.id===q.sourceMaterialId),o=q.sourceOffsets;assert.equal(crypto.createHash('sha256').update(src.sourceText).digest('hex'),q.sourceTextSha256);assert.ok([src.sourceText.slice(o.leftStart,o.leftEnd),src.sourceText.slice(o.rightStart,o.rightEnd)].includes(q.answer));if([...q.answer].some(c=>c.codePointAt(0)>0xFFFF))assert.ok(q.issues.length);}});
