'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {list} = require('./java-feature-operations.cjs');
test('precise academic permissions appear without granting broad writes', () => {
  const result = list({permissions: ['student.create', 'course.create']});
  assert.equal(result.find(op => op.id === 'student.create').cli.command, 'academic student-create');
  assert.equal(result.find(op => op.id === 'course.create').cli.command, 'academic course-create');
  assert.equal(result.some(op => op.id === 'course.update'), false);
});
test('academic registry retains required AND permission groups', () => {
  assert.equal(list({permissions: ['enrollment.create']}).some(op => op.id === 'enrollment.create'), false);
  const op = list({permissions: ['enrollment.create', 'crm.order.read']}).find(op => op.id === 'enrollment.create');
  assert.ok(op);
  assert.ok(op.permissionGroups.some(group => group.includes('crm.order.read')));
});
test('commerce registry honors OR grants and global-only storefront scope', () => {
  const checkout = list({scope: 'campus', permissions: ['enrollment.manage']});
  for (const id of ['commerce.checkout.create', 'commerce.checkout.accept', 'commerce.checkout.list', 'commerce.checkout.detail', 'commerce.checkout.intake'])
    assert.ok(checkout.some(op => op.id === id), `missing ${id}`);
  assert.equal(list({scope: 'campus', permissions: ['commerce.read']}).some(op => op.id === 'commerce.checkout.intake'), false);
  const publish = list({scope: 'campus', permissions: ['promotion.publish']});
  for (const id of ['commerce.coupon.list', 'commerce.coupon.options', 'commerce.coupon.publish']) assert.ok(publish.some(op => op.id === id), `missing ${id}`);
  assert.equal(publish.some(op => op.id === 'commerce.coupon.create'), false);
  const campusDesigner = list({scope: 'campus', permissions: ['store.design']});
  assert.equal(campusDesigner.some(op => op.id.startsWith('commerce.storefront.decor.')), false);
});
test('CRM attribution, routing and import workflows are registry-backed with scoped grants', () => {
  const operations = list({scope: 'all', permissions: ['crm.order.read', 'crm.attribution.correct.request', 'crm.attribution.correct.review', 'crm.lead.assign', 'crm.customer.read', 'crm.customer.write', 'crm.lead.read', 'crm.lead.write', 'crm.touchpoint.write']});
  for (const id of ['order.attribution.request', 'order.attribution.review', 'crm.routingRule.save', 'crm.routing.simulate', 'crm.routing.apply', 'crm.import.commit']) assert.ok(operations.some(op => op.id === id), `missing ${id}`);
  assert.equal(list({scope: 'own', permissions: ['crm.lead.assign']}).some(op => op.id === 'crm.routing.apply'), false);
});
test('order commands stay in orders and unknown permissions expose nothing', () => {
  const op = list({permissions: ['crm.order.write', 'crm.opportunity.read']}).find(op => op.id === 'crm.order.create');
  assert.equal(op.cli.command, 'orders create');
  assert.equal(op.preview.required, false);
  assert.deepEqual(list({permissions: []}), []);
});
test('CRM and academic operations are registry-backed with exact commands and grants', () => {
  const crm = list({scope: 'all', permissions: ['crm.customer.write', 'crm.customer.read', 'crm.touchpoint.write', 'crm.activity.write', 'crm.task.write', 'crm.task.assign', 'crm.lead.assign']});
  assert.equal(crm.find(op => op.id === 'crm.customer.create').cli.command, 'crm customer-create');
  assert.equal(crm.find(op => op.id === 'crm.routing.apply').cli.command, 'crm routing-apply');
  assert.equal(crm.some(op => op.id === 'crm.lead.routing.apply'), false);
  assert.equal(crm.some(op => op.id === 'crm.customer.contact.add'), true);
  assert.equal(crm.some(op => op.id === 'crm.customer.student.link'), false);
  const academic = list({permissions: ['session.reschedule.request', 'session.move', 'attendance.manage', 'session.attendance']});
  assert.equal(academic.find(op => op.id === 'session.reschedule.request').cli.command, 'academic session-reschedule-request');
  assert.equal(academic.find(op => op.id === 'session.attendance.exceptions').cli.command, 'academic session-attendance-exceptions');
  assert.equal(list({permissions: ['attendance.manage']}).some(op => op.id === 'session.attendance.exceptions'), true);
});
test('CLI Java list, describe and registry use only v2 identity and accurate commands', t => {
  const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{spawnSync}=require('node:child_process');
  const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'siyue-registry-cli-')));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const env={...process.env,SIYUE_CONFIG_DIR:path.join(root,'config')};
  require('./auth-client.cjs').writeSession({server:'https://synthetic.example/crm-api/v2/',token:'synthetic-only',apiVersion:'v2'},env);
  const log=path.join(root,'requests.json'),preload=path.join(root,'fetch.cjs');
  fs.writeFileSync(preload,`const fs=require('node:fs');global.fetch=async(url)=>{const file=${JSON.stringify(log)};const calls=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):[];calls.push(String(url));fs.writeFileSync(file,JSON.stringify(calls));if(!String(url).endsWith('/crm-api/v2/identity/me'))throw Error('Unexpected endpoint');return {ok:true,status:200,json:async()=>({code:'OK',data:{principalId:'synthetic',scope:'own',permissions:['student.create','crm.lead.read']}})};};`);
  for(const args of [['list','student.create'],['describe','student.create'],['registry','operations','student.create']]) {
    const result=spawnSync(process.execPath,[path.join(__dirname,'siyue-cli.cjs'),...args],{encoding:'utf8',env:{...env,NODE_OPTIONS:`--require=${preload}`}});
    assert.equal(result.status,0,result.stderr);
    const body=JSON.parse(result.stdout),op=Array.isArray(body)?body[0]:body.businessOperations?body.businessOperations[0]:body;
    assert.equal(op.id,'student.create');assert.equal(op.cli.command,'academic student-create');
  }
  const calls=JSON.parse(fs.readFileSync(log));assert.equal(calls.length,3);assert.ok(calls.every(url=>url.endsWith('/crm-api/v2/identity/me')));
});
test('CLI help lists the implemented academic and consultation commands',()=>{
 const {spawnSync}=require('node:child_process'),path=require('node:path');
 const result=spawnSync(process.execPath,[path.join(__dirname,'siyue-cli.cjs'),'help'],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr);
  for(const file of ['./java-academic.cjs','./java-consultations.cjs','./java-crm-operations.cjs','./java-crm-workflows.cjs'])for(const op of require(file).operations)assert.ok(result.stdout.includes(op.name),`Missing help for ${op.name}`);
  assert.match(result.stdout,/batch-assign.*even\/quota\/owner_first/);
 assert.ok(!result.stdout.includes('student-update 尚不可用'));
});
