#!/usr/bin/env python3
"""Read-only local verification of formula XML and ordered image relationship records."""
import json,sqlite3,sys,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else '.local/acceptance/splitting-20261023').resolve()
database=Path(sys.argv[2] if len(sys.argv)>2 else '.local/siyue.db').resolve()
db=sqlite3.connect(database.as_uri()+'?mode=ro',uri=True); db.row_factory=sqlite3.Row
manifest=json.loads((root/'manifest.json').read_text()); run=json.loads((root/'api-run.json').read_text()); checks=[]
ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main','m':'http://schemas.openxmlformats.org/officeDocument/2006/math','a':'http://schemas.openxmlformats.org/drawingml/2006/main','r':'http://schemas.openxmlformats.org/officeDocument/2006/relationships','wp':'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'}
for fixture in manifest:
    if fixture['reject']: continue
    item=next(x for x in run['items'] if x['file']==fixture['file'])
    qs=db.execute('SELECT * FROM Questions WHERE HandoutId=? COLLATE NOCASE ORDER BY "Order"',(item['handoutId'],)).fetchall(); errors=[]
    if len(qs)!=len(fixture['questions']): errors.append('数据库题数与预期不一致')
    for i,(q,expected) in enumerate(zip(qs,fixture['questions'])):
        blocks=json.loads(q['BlocksJson']); xml=[ET.fromstring(b['xml']) for b in blocks]
        formulas=sum(len(e.findall('.//m:oMath',ns)) for e in xml)
        if formulas!=expected['math']: errors.append(f'第{i+1}题公式结构{formulas} != {expected["math"]}')
        if expected['math']:
            for tag in ['f','sSup','sSub']:
                if not any(e.find('.//m:'+tag,ns) is not None for e in xml): errors.append('公式结构缺少'+tag)
        refs=[]
        for block_index,e in enumerate(xml):
            for occurrence,node in enumerate(e.findall('.//a:blip',ns)):
                refs.append((block_index,occurrence,node.get('{'+ns['r']+'}embed')))
        rows=db.execute('SELECT qa.*,a.RelationshipId FROM QuestionAssets qa JOIN Assets a ON a.Id=qa.AssetId WHERE qa.QuestionId=? ORDER BY qa.BlockIndex,qa.Occurrence',(q['Id'],)).fetchall()
        actual=[(r['BlockIndex'],r['Occurrence'],r['RelationshipId']) for r in rows]
        if refs!=actual: errors.append(f'第{i+1}题图片位置/顺序/关联不匹配')
        if len(rows)!=expected['images']: errors.append(f'第{i+1}题图片关系数不匹配')
        if any(r['Width']<=0 or r['Height']<=0 for r in rows): errors.append(f'第{i+1}题图片尺寸丢失')
    checks.append(dict(file=fixture['file'],passed=not errors,errors=errors))
(root/'storage-results.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
print(f'Storage checks: {sum(c["passed"] for c in checks)}/{len(checks)}')
for c in checks:
    if c['errors']: print(c['file'],c['errors'])
assert all(c['passed'] for c in checks)
