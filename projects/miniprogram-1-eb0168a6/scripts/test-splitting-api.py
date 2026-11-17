#!/usr/bin/env python3
"""Upload synthetic fixtures to a local server; compare boundaries and resource ownership."""
import json,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path
ROOT=Path(sys.argv[1] if len(sys.argv)>1 else '.local/acceptance/splitting-20261023').resolve()
BASE='http://127.0.0.1:5080'; token=None

def request(path,data=None,method=None,raw=False,ctype=None):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if data is not None and not isinstance(data,bytes): data=json.dumps(data).encode(); ctype='application/json'
    if ctype: headers['Content-Type']=ctype
    req=urllib.request.Request(path if path.startswith('http') else BASE+path,data,headers,method=method)
    with urllib.request.urlopen(req,timeout=60) as r:
        b=r.read(); return b if raw else (json.loads(b) if b else {})

def main():
    global token
    token=request('/api/auth/preview',{})['token']
    state_path=ROOT/'api-run.json'
    if state_path.exists(): run=json.loads(state_path.read_text())
    else:
        campus=request('/api/admin/catalog/campuses',{'name':'临时拆题测试校区'})
        cl=request('/api/admin/catalog/classes',{'name':'拆题测试班 · 20261023','campusId':campus['id']})
        course=request('/api/admin/catalog/courses',{'name':'临时讲义拆题测试'})
        workspace=request('/api/workspace')
        request('/api/admin/catalog/assignments',{'staffId':workspace['user']['id'],'classId':cl['id'],'courseId':course['id']})
        run=dict(classId=cl['id'],courseId=course['id'],items=[]); state_path.write_text(json.dumps(run,ensure_ascii=False,indent=2))
    fixtures=json.loads((ROOT/'manifest.json').read_text())
    failed=set()
    if '--retry-failed' in sys.argv and (ROOT/'results.json').exists():
        failed={r['file'] for r in json.loads((ROOT/'results.json').read_text()) if not r['passed']}
    for index,fixture in enumerate(fixtures):
        existing=next((x for x in run['items'] if x['file']==fixture['file']),None)
        if existing and fixture['file'] not in failed: continue
        lecture={'id':existing['lectureId']} if existing else request('/api/lectures',dict(classId=run['classId'],courseId=run['courseId'],title=fixture['title'],sequence=index+1))
        boundary=uuid.uuid4().hex; name=fixture['file']; binary=(ROOT/name).read_bytes()
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+binary+f'\r\n--{boundary}--\r\n'.encode())
        upload=request('/api/lectures/'+lecture['id']+'/handouts',body,ctype='multipart/form-data; boundary='+boundary)
        if existing:
            run.setdefault('previousVersions',[]).append(dict(existing)); run['items'].remove(existing)
        run['items'].append(dict(file=name,lectureId=lecture['id'],handoutId=upload['id']))
        state_path.write_text(json.dumps(run,ensure_ascii=False,indent=2)); print('uploaded '+name,flush=True)
    results=[]
    for fixture in fixtures:
        item=next(x for x in run['items'] if x['file']==fixture['file']); deadline=time.time()+900
        while True:
            detail=request('/api/handouts/'+item['handoutId']); state=detail['handout']['state']
            if state in ('draft','published','failed'): break
            if time.time()>deadline: raise TimeoutError(fixture['file'])
            time.sleep(2)
        errors=[]; qs=detail['questions']; out=ROOT/'results'/Path(fixture['file']).stem; out.mkdir(parents=True,exist_ok=True)
        if fixture['reject']:
            if state!='failed' or fixture['reject'] not in (detail['handout']['error'] or ''): errors.append('未按预期拒绝：'+str(detail['handout']))
        else:
            if state not in ('draft','published'): errors.append('处理状态：'+state+' '+str(detail['handout']['error']))
            if len(qs)!=len(fixture['questions']): errors.append(f"题数期望{len(fixture['questions'])}，实际{len(qs)}")
            for i,(actual,expected) in enumerate(zip(qs,fixture['questions'])):
                if actual['label']!=expected['label']: errors.append(f"第{i+1}题题号：{actual['label']} != {expected['label']}")
                text='\n'.join(b['text'] for b in actual['blocks'])
                for marker in expected['tokens']:
                    if marker not in text: errors.append(f'第{i+1}题缺少内容：{marker}')
                images=sum(len(b['images']) for b in actual['blocks']); tables=sum(b['kind']=='table' for b in actual['blocks'])
                if images!=expected['images']: errors.append(f"第{i+1}题图片引用：{images} != {expected['images']}")
                if tables!=expected['tables']: errors.append(f"第{i+1}题表格：{tables} != {expected['tables']}")
                if expected['issue'] and not any(issue['code']==expected['issue'] for issue in actual['issues']): errors.append(f"第{i+1}题缺少提醒：{expected['issue']}")
                if expected['issue']=='unsupported-object':
                    try:
                        request('/api/handouts/'+item['handoutId']+'/edit',dict(action='confirm',revision=detail['handout']['revision'],questionId=actual['id']))
                        errors.append('不支持对象竟能确认')
                    except urllib.error.HTTPError as e:
                        if e.code!=400: errors.append('确认阻止状态异常：'+str(e.code))
                if not actual['previews']: errors.append(f'第{i+1}题无预览')
                for page,url in enumerate(actual['previews'],1):
                    image=request(url,raw=True)
                    if not image.startswith(bytes.fromhex('89504e470d0a1a0a')): errors.append(f'第{i+1}题不是PNG')
                    (out/f'q{i+1}-{page}.png').write_bytes(image)
        if detail.get('sourceUrl'): (out/'source.pdf').write_bytes(request(detail['sourceUrl'],raw=True))
        safe=dict(handout=detail['handout'],questions=[{k:v for k,v in q.items() if k!='previews'} for q in qs])
        (out/'detail.json').write_text(json.dumps(safe,ensure_ascii=False,indent=2))
        result=dict(file=fixture['file'],handoutId=item['handoutId'],state=state,expected=len(fixture['questions']),actual=len(qs),passed=not errors,errors=errors)
        results.append(result); (ROOT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        print(('PASS ' if not errors else 'FAIL ')+fixture['file']+': '+ '; '.join(errors),flush=True)
    print(f"SUMMARY {sum(r['passed'] for r in results)}/{len(results)}",flush=True)
if __name__=='__main__': main()
