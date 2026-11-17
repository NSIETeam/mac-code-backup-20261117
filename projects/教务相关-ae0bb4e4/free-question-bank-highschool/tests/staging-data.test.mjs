import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {prepareStagingSearch,filterStagingRecords,mountStagingLibrary} from '../dist/staging-library.mjs';

const directory=new URL('../dist/data/',import.meta.url);
const json=async path=>JSON.parse(await readFile(new URL(path,directory),'utf8'));
const index=await json('staging-index.json');

test('actual source count, solution counts and disabled assembly match the inventory',()=>{
 assert.equal(index.total,19611);assert.equal(index.records.length,index.total);
 assert.equal(index.hasSourceSolution,7023);assert.equal(index.defaultAssemblyEnabled,false);
 assert.equal(index.chinaCurriculumMapped,false);assert.equal(index.sourceBooks,6);
 assert.deepEqual(index.subjects,{mathematics:6026,chemistry:2985,physics:3232,science_general:9,history:3109,biology:2257,geography:1415,politics:578});
 assert.deepEqual(index.sourceSets,{siyavula:7406,exams:9783,mmlu:2422});assert.equal(index.internationalRecords,12205);
 assert.deepEqual(index.statuses,{needs_formula_transcription:6785,needs_content_review:621,needs_translation_curriculum_review:12205});
 assert.equal(new Set(index.records.map(q=>q.id)).size,index.total);
 assert.equal(index.chunks.length,198);assert.equal(index.chunks[74].count,6);assert.equal(index.chunks.at(-1).count,5);
 assert.equal(typeof mountStagingLibrary,'function');
});

test('every lightweight index entry resolves to a real source record and pinned chunk hash',async()=>{
 const indexed=new Map(index.records.map(q=>[q.id,q]));let actual=0,solutions=0;
 const names=await readdir(new URL('staging-chunks/',directory));
 assert.equal(names.filter(n=>/^\d{3}\.json$/.test(n)).length,index.chunks.length);
 for(const meta of index.chunks){
  const buffer=await readFile(new URL('staging-chunks/'+meta.file,directory));
  assert.equal(buffer.length,meta.bytes);assert.equal(createHash('sha256').update(buffer).digest('hex'),meta.sha256);
  const data=JSON.parse(buffer);assert.equal(data.records.length,meta.count);assert.ok(meta.count<=100);
  assert.equal(data.defaultAssemblyEnabled,false);
  for(const q of data.records){
   const entry=indexed.get(q.id);assert.ok(entry,q.id);assert.equal(entry.chunk,meta.file);
   assert.equal(q.hasSourceSolution,entry.hasSourceSolution);assert.equal(q.subject,entry.subject);
   assert.equal(q.validation.eligibleForDefaultAssembly,false);assert.equal(entry.needsTranslation,true);
   if(entry.sourceSet==='siyavula'){
    assert.equal(q.questionType,null);assert.equal(q.answer,null);
    assert.ok(q.source.url.startsWith('https://www.siyavula.com/downloads/books/'));
    assert.equal(q.source.license,'CC-BY-4.0');assert.ok(q.source.archiveSha256.match(/^[a-f0-9]{64}$/));assert.ok(q.stemHtml);
   }else{
    assert.ok(['exams','mmlu'].includes(entry.sourceSet));assert.equal(q.sourceSet,entry.sourceSet);
    assert.equal(q.status,'needs_translation_curriculum_review');assert.equal(q.questionType,'single_choice');
    assert.equal(q.hasSourceSolution,false);assert.equal(q.sourceSolutionPlainText,'');assert.equal(q.sourceSolutionHtml,null);
    assert.equal(q.readiness.teacherReviewed,false);assert.equal(q.chinaCurriculumMapped,false);
    assert.ok(q.options.some(o=>o.label===q.sourceAnswer.key&&o.text===q.sourceAnswer.text));
    assert.equal(q.answer,q.sourceAnswer.key);assert.ok(q.rawSourceRecord);
    assert.equal(q.source.license,entry.sourceSet==='exams'?'CC-BY-SA-4.0':'MIT');
    if(entry.sourceSet==='exams'){assert.ok([10,11,12].includes(q.sourceGrade));assert.equal(q.sourceGrade,q.rawSourceRecord.info.grade);}
    else assert.equal(q.sourceGrade,'高中未细分');
    assert.ok(q.source.sourceRefs.every(ref=>/^[a-f0-9]{64}$/.test(ref.rawRecordSha256)));
   }
   assert.ok(q.stemPlainText);assert.ok(Array.isArray(q.sharedContextPlainText));
   assert.ok(q.assets.every(a=>a.exists));
   if(q.status==='needs_formula_transcription')assert.ok(q.assets.some(a=>a.kind==='formula_png'));
   if(q.hasSourceSolution)assert.ok(q.sourceSolutionPlainText);
   else assert.equal(q.sourceSolutionPlainText,'');
   assert.ok(!('stemHtml' in entry));assert.ok(!('sourceSolutionHtml' in entry));
   solutions+=Number(q.hasSourceSolution);actual++;
  }
 }
 assert.equal(actual,index.total);assert.equal(solutions,7023);
});

test('hierarchical, grade, multilingual keyword and combination searches use only the lightweight index',()=>{
 const records=prepareStagingSearch(index.records);
 const allChemistry=filterStagingRecords(records,{subject:'chemistry'});assert.equal(allChemistry.length,2985);
 assert.equal(filterStagingRecords(records,{subject:'history'}).length,3109);
 assert.equal(filterStagingRecords(records,{subject:'biology'}).length,2257);
 assert.ok(filterStagingRecords(records,{grade:'高中未细分'}).length===2422);
 const geometry=filterStagingRecords(records,{subject:'mathematics',chapter:'解析几何'});assert.ok(geometry.length>100);
 assert.ok(geometry.every(q=>q.subject==='mathematics'&&q.knowledgePath[1]==='解析几何'));
 const section=geometry[0].knowledgePath.slice(2).join(' / ');
 assert.ok(filterStagingRecords(records,{subject:'mathematics',chapter:'解析几何',section}).every(q=>q.knowledgePath.slice(2).join(' / ')===section));
 assert.ok(filterStagingRecords(records,{keyword:'化学平衡'}).length>0);
 assert.ok(filterStagingRecords(records,{subject:'mathematics',keyword:'相似 三角形'}).length>0);
 const results=filterStagingRecords(records,{subject:'physics',grade:'10',keyword:'sound'});
 assert.ok(results.length>0);assert.ok(results.every(q=>q.subject==='physics'&&q.sourceGrade===10));
 assert.equal(filterStagingRecords(records,{keyword:'no-match-987654321'}).length,0);
 const before=performance.now();for(let i=0;i<30;i++)filterStagingRecords(records,{subject:'mathematics',keyword:'triangle'});
 const perSearch=(performance.now()-before)/30;
 assert.ok(perSearch<100,`${index.total}-record filter average ${perSearch.toFixed(2)}ms`);
});

test('16 reviewed samples preserve original sources, explicit option sets and separately authored Chinese explanations',async()=>{
 const data=await json('staging-reviewed-16.json');assert.equal(data.records.length,16);assert.equal(data.defaultAssemblyEnabled,false);
 const subjects={};let mcq=0,tf=0,unclassified=0;
 for(const q of data.records){
  subjects[q.subject]=(subjects[q.subject]||0)+1;assert.ok(q.stemZh);assert.ok(q.explanationZh);assert.ok(q.stemPlainText);
  assert.equal(q.status,'ready_reviewed_sample');assert.equal(q.assets.length,0);assert.equal(q.source.license,'CC-BY-4.0');
  assert.equal(q.validation.contentReviewed,true);assert.equal(q.validation.eligibleForDefaultAssembly,false);
  if(q.questionType==='multiple_choice'){assert.ok(q.optionsOriginal.includes(q.answer));mcq++;}
  else if(q.questionType==='true_false'){assert.equal(typeof q.answer,'boolean');tf++;}
  else{assert.equal(q.questionType,null);unclassified++;}
 }
 assert.deepEqual(subjects,{mathematics:8,chemistry:4,physics:4});assert.equal(mcq,6);assert.equal(tf,8);assert.equal(unclassified,2);
 const period=data.records.find(q=>q.subject==='physics'&&q.id.endsWith(':124'));
 assert.equal(period.answer.unit,'s');assert.equal(period.answer.value*200,1);
});

test('source catalog remains metadata, empty solution nodes are not answers, and UI never renders raw HTML',async()=>{
 const catalog=await json('open-source-catalog.json');assert.equal(catalog.sources.length,9);
 assert.equal(catalog.totals.epub_source_questions,7406);assert.equal(catalog.totals.default_assembly_enabled,false);
 assert.equal(catalog.sources.find(s=>s.id==='siyavula-life-science-grade10').collected_questions,0);
 const ui=await readFile(new URL('../dist/staging-library.mjs',import.meta.url),'utf8');
 assert.ok(!ui.includes('.innerHTML'));assert.ok(!ui.includes('insertAdjacentHTML'));assert.ok(!ui.includes('localStorage'));
 assert.ok(ui.includes('textContent'));assert.ok(ui.includes('loadChunk'));
 await readFile(new URL('staging-provenance/README.txt',directory));
 await readFile(new URL('staging-provenance/licenses/siyavula-maths-grade-10-artifact-license.txt',directory));
 await readFile(new URL('staging-provenance/licenses/EXAMS-LICENSE.txt',directory));
 await readFile(new URL('staging-provenance/licenses/MMLU-LICENSE.txt',directory));
 const international=await json('staging-provenance/international-source-manifest.json');assert.equal(international.readyRecords,12205);
});

test('offline rebuild recovers international records from packaged chunks when the original JSONL is absent',()=>{
 const root=fileURLToPath(new URL('../',import.meta.url));
 const code=`import importlib.util,pathlib,sys,json,collections
app=pathlib.Path(sys.argv[1]);spec=importlib.util.spec_from_file_location('intl',app/'scripts/staging-international.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
rows,source=m.load_records(app/'__missing_external_source_for_offline_test__.jsonl',app/'dist/data')
assert source is None
print(json.dumps({'count':len(rows),'sources':dict(collections.Counter(q['sourceSet'] for q in rows))}))`;
 const result=spawnSync('python3',['-c',code,root],{encoding:'utf8',timeout:15000});
 assert.equal(result.status,0,result.stderr);assert.deepEqual(JSON.parse(result.stdout),{count:12205,sources:{exams:9783,mmlu:2422}});
});
