import {test} from 'node:test';import assert from 'node:assert/strict';import {parseCsv,previewCsv} from '../wwwroot/collaboration/import-preview.mjs';
test('BOM, quoted commas, multiline notes and escaped quotes are preserved',()=>assert.deepEqual(parseCsv('\uFEFF名称,备注\r\n项目,"第一行,\n第二行"\r\n测试,"他说""好"""'),[['名称','备注'],['项目','第一行,\n第二行'],['测试','他说"好"']]));
test('Blank rows omitted but original status and date label preserved',()=>{const p=previewCsv('干啥,11/12更新状态\n试点,已实现\n,\n');assert.equal(p.rows.length,1);assert.equal(p.headers[1],'11/12更新状态');assert.equal(p.rows[0].cells[1],'已实现');assert.equal(p.rows[0].sourceRow,2);});
test('Reject malformed quoted CSV',()=>assert.throws(()=>parseCsv('名称,备注\n项目,"缺引号')));
