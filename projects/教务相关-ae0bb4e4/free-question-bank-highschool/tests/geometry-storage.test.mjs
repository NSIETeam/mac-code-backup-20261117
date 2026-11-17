import test from 'node:test';import assert from 'node:assert/strict';
import {runGeometryBatch,validateGeometryQuestion}from '../dist/geometry.mjs';
import {compactStored,expandStored}from '../dist/geometry-storage.mjs';
test('two thousand complete recipes fit well below local storage and restore checked question types/models',()=>{
 const batch=runGeometryBatch({count:2000,seed:'storage'}).questions;
 const compact=compactStored(batch,'custom');assert.ok(JSON.stringify(compact).length<1000000);
 const restored=expandStored(JSON.parse(JSON.stringify(compact)),'custom');assert.equal(restored.length,2000);
 for(let i=0;i<restored.length;i++){assert.deepEqual(restored[i].model,batch[i].model);assert.equal(restored[i].id,batch[i].id);}
 const choice=runGeometryBatch({count:19,format:'choice'}).questions, papers=[{title:'test',questions:choice}];
 const saved=expandStored(compactStored(papers,'papers'),'papers');assert.ok(saved[0].questions.every(q=>validateGeometryQuestion(q).passed));
 const invalid=structuredClone(compact);invalid[0].version='future';assert.throws(()=>expandStored(invalid,'custom'));
});
