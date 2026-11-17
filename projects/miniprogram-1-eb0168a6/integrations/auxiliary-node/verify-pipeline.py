"""Upload a real-lecture derivative to an isolated admin-owned class; archive it after acceptance."""
import hashlib,json,os,re,sqlite3,subprocess,time,urllib.request,uuid
from pathlib import Path
root=Path('/opt/siyue-auxiliary');phase=__import__('sys').argv[1];base='https://www.siyuejn.com/miniapp/api';token=None;class_id=None;forced_sha=None

def sql(text):return subprocess.run(['runuser','-u','postgres','--','psql','-d','siyue_miniapp','-At'],input=text,text=True,check=True,capture_output=True).stdout.strip()
def call(path,value=None,method=None,ctype='application/json'):
 data=value if isinstance(value,bytes) else None if value is None else json.dumps(value).encode();headers={'Content-Type':ctype}
 if token:headers['Authorization']='Bearer '+token
 request=urllib.request.Request(base+path,data=data,headers=headers,method=method)
 with urllib.request.urlopen(request,timeout=60) as response:
  content=response.read();return json.loads(content) if content else None

def queue_count(hid):return int(sql('SELECT count(*) FROM "Work" WHERE "EntityId"=\''+str(uuid.UUID(hid))+'\' AND "State" IN (\'running\',\'queued\');'))
try:
 login=call('/auth/password',dict(account='test_admin',password=os.environ['SIYUE_ADMIN_PASSWORD']));token=login['token']
 workspace=call('/workspace');campus=next((x for x in workspace['campuses'] if '12B' in x['name']),workspace['campuses'][0]);course=workspace['courses'][0]
 created=call('/classes',dict(name='辅助节点迁移验收-'+phase+'-'+uuid.uuid4().hex[:5],campusId=campus['id'],courseId=course['id'],requestId='aux-acceptance-'+uuid.uuid4().hex));class_id=created['id']
 lecture=call('/lectures',dict(classId=class_id,courseId=course['id'],title='真实讲义解析验收',sequence=1))
 work=Path('/opt/siyue-miniapp/data/worker-scratch/native-acceptance');target=work/('pipeline-'+phase+'.pdf')
 text=subprocess.run(['docker','exec','siyue-miniapp-worker-1','pdftotext','-layout','/work/native-acceptance/source0.pdf','-'],check=True,capture_output=True,text=True).stdout.split('\f')
 selected=next((i+1 for i,t in enumerate(text) if re.search(r'例\s*[0-9一二三四五六七八九十]',t)),1)
 last=min(selected+1,len(text)-1)
 prefix='/work/native-acceptance/part-'+phase
 subprocess.run(['docker','exec','siyue-miniapp-worker-1','pdfseparate','-f',str(selected),'-l',str(last),'/work/native-acceptance/source0.pdf',prefix+'-%d.pdf'],check=True,capture_output=True)
 subprocess.run(['docker','exec','siyue-miniapp-worker-1','pdfunite']+[prefix+'-'+str(n)+'.pdf' for n in range(selected,last+1)]+['/work/native-acceptance/'+target.name],check=True,capture_output=True)
 with target.open('ab') as f:f.write(('\n% AUX-ACCEPTANCE:'+uuid.uuid4().hex+'\n').encode())
 if phase=='native':
  forced_sha=hashlib.sha256(target.read_bytes()).hexdigest();p=root/'dispatch.json';c=json.loads(p.read_text());c['forceSources']=c.get('forceSources',[])+[forced_sha];p.write_text(json.dumps(c))
 boundary='aux-'+uuid.uuid4().hex
 data=('--'+boundary+'\r\nContent-Disposition: form-data; name="recognitionMode"\r\n\r\nlegacy\r\n--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="aux-acceptance.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+target.read_bytes()+('\r\n--'+boundary+'--\r\n').encode()
 started=time.monotonic();h=call('/lectures/'+lecture['id']+'/handouts',data,ctype='multipart/form-data; boundary='+boundary);hid=h['id']
 for _ in range(240):
  result=call('/handouts/'+hid+'?classId='+class_id);handout=result['handout']
  if handout['state']=='failed':raise AssertionError(handout.get('error'))
  if handout['state'] in ('draft','published') and queue_count(hid)==0:break
  time.sleep(.5)
 else:raise AssertionError('Parsing did not finish within acceptance window')
 questions=result['questions'];assert questions,'No questions extracted'
 preview=next(q['previews'][0] for q in questions if q.get('previews'))
 with urllib.request.urlopen(preview,timeout=30) as f:preview_bytes=f.read()
 assert preview_bytes.startswith(b'\x89PNG\r\n\x1a\n')
 with urllib.request.urlopen(result['sourceUrl'],timeout=30) as f:source_bytes=f.read()
 assert source_bytes.startswith(b'%PDF') and hashlib.sha256(source_bytes).digest()==hashlib.sha256(target.read_bytes()).digest()
 native_jobs=0
 if forced_sha:
  with sqlite3.connect(root/'state/queue.sqlite') as d: events=d.execute('SELECT kind,detail FROM events').fetchall()
  ids={v.split(':')[1] for k,v in events if k.startswith('native.request.') and v.startswith(forced_sha+':')}
  finished={v for k,v in events if k.startswith('native.') and not k.startswith('native.request.')}
  native_jobs=len(ids & finished);assert native_jobs>0,'No actual native result used'
 summary=dict(previewDownloaded=True,sourcePdfDownloadedUnchanged=True,nativeJobs=native_jobs,phase=phase,seconds=round(time.monotonic()-started,2),classId=class_id,handoutId=hid,questions=[dict(label=q['label'],blocks=len(q.get('blocks',[])),previews=len(q.get('previews',[])),issues=[i.get('code') for i in q.get('issues',[])]) for q in questions],error=handout.get('error'))
 (root/('pipeline-'+phase+'.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(summary,ensure_ascii=False))
finally:
 if forced_sha:
  p=root/'dispatch.json';c=json.loads(p.read_text());c['forceSources']=[x for x in c.get('forceSources',[]) if x!=forced_sha];p.write_text(json.dumps(c))
 if class_id:call('/admin/catalog/classes/'+class_id,method='DELETE')
 if token:call('/logout',{},method='POST')
