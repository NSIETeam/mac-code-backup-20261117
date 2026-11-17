'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const source=fs.readFileSync(path.join(__dirname,'../java-api-base.js'),'utf8');
function helper(pathname,configured){
 const sandbox={location:{origin:'https://www.siyuejn.com',pathname},document:{body:{dataset:{javaApiBase:configured||''}}},URL,Object,TypeError};
 sandbox.window=sandbox;
 vm.runInNewContext(source,sandbox);
 return sandbox.SiyueJavaApiBase;
}

test('production pages retain the fixed Java API default while candidate pages require explicit HTML configuration',()=>{
 assert.equal(helper('/crm/').resolve(),'/crm-api/v2/');
 assert.equal(helper('/java-candidate-v101/crm/','/java-candidate-v101/api/v2/').resolve(),'/java-candidate-v101/api/v2/');
 assert.throws(()=>helper('/java-candidate-v101/crm/').resolve(),/no Java API base configuration/);
});

test('Java API base accepts only approved same-origin deployment paths',()=>{
 for(const invalid of ['https://evil.example/java-candidate-v101/api/v2/','//evil.example/x','/java-candidate-v101/api/v2/../api/v2/','/java-candidate-v101\\api\\v2\\','/java-candidate-v101/api/v2/?next=/crm-api/v2/','/anything/v2/']){
  assert.throws(()=>helper('/java-candidate-v101/crm/',invalid).resolve(),/approved same-origin path/,invalid);
 }
 assert.throws(()=>helper('/java-candidate-v101/crm/','/crm-api/v2/').resolve(),/approved same-origin path/);
 assert.throws(()=>helper('/java-candidate-v101/crm/','/java-candidate-v999/api/v2/').resolve(),/approved same-origin path/);
 assert.throws(()=>helper('/crm/','/java-candidate-v101/api/v2/').resolve(),/approved same-origin path/);
});

test('CRM return URLs allow the active candidate CRM route and reject other paths',()=>{
 const api=helper('/java-candidate-v101/crm/','/java-candidate-v101/api/v2/');
 assert.equal(api.isCrmPath('/crm/'),false);
 assert.equal(api.isCrmPath('/crm/index.html'),false);
 assert.equal(api.isCrmPath('/java-candidate-v101/crm/'),true);
 assert.equal(api.isCrmPath('/java-candidate-v101/crm/index.html'),true);
 for(const path of ['/java-candidate-v101/api/v2/','/java-candidate-v101/crm/../other/','/java-candidate-v999/crm-extra/','/other/crm/']){
  assert.equal(api.isCrmPath(path),false,path);
 }
 const production=helper('/crm/');
 assert.equal(production.isCrmPath('/crm/'),true);
 assert.equal(production.isCrmPath('/java-candidate-v101/crm/'),false);
});
