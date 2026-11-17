"""Prepare a reviewed CSV import. Outputs transaction SQL + per-project source files.
No network access or database mutation occurs here. Re-running the SQL is a no-op
for the same source; it never overwrites subsequent edits. Existing IDs from a
changed source fail the transaction instead of producing duplicates.
"""
import argparse,csv,hashlib,io,json,pathlib,uuid
from datetime import datetime,timezone

NAMESPACE=uuid.UUID('d0738e21-9cc6-4585-a5e4-8047b209621e')
def uid(key):return str(uuid.uuid5(NAMESPACE,key))
def literal(value):
    if value is None:return 'NULL'
    if isinstance(value,bool):return 'TRUE' if value else 'FALSE'
    if isinstance(value,int):return str(value)
    return "'"+str(value).replace("'","''")+"'"
def insert(table,row):
    columns=','.join('"'+key+'"' for key in row)
    return f'INSERT INTO "{table}" ({columns}) VALUES ('+','.join(map(literal,row.values()))+');'

def prepare(source,plan,actor,output):
    actor=str(uuid.UUID(actor));output.mkdir(parents=True,exist_ok=True)
    raw=source.read_bytes();digest=hashlib.sha256(raw).hexdigest()
    reader=list(csv.reader(io.StringIO(raw.decode('utf-8-sig'))));headers=reader[0]
    assert headers[:7]==['干啥','为啥','怎么干','涉及的部门','当前状态','11/12更新状态','11/12状态下的需求表单（包括物料和决策）'],'Unexpected CSV headers'
    rows=[(i+1,r) for i,r in enumerate(reader[1:],1) if any(c.strip() for c in r)]
    assert len(rows)==len(plan),'Plan must cover every source row'
    stamp=datetime.now(timezone.utc).isoformat();batch=uid(source.name+':'+digest)
    records=[];sql=[];files=output/'attachments';files.mkdir(exist_ok=True)
    for (line,row),spec in zip(rows,plan):
        assert (row[0].startswith(spec['match']) if spec.get('prefix') else row[0]==spec['match']),f'Row {line} does not match plan'
        assert spec['status'] in ['planned','active','blocked']
        pid=uid(source.name+':project:'+row[0]);name=spec.get('name',row[0]);assert len(name)<=120
        summary=(f'原表 11/12 更新（年份待补充）：{row[5]}' if row[5] else row[4] or '原表仅记录项目名称，目标、方案、部门和进展待补充。')
        p=dict(Id=pid,Name=name,Goal=row[1],Approach=row[2],Department=row[3],OwnerId=None,Confidential=False,Status=spec['status'],Summary=summary,Revision=1,UpdatedAt=stamp,RepositoryId=None,TestingBuildId=None,LockedBuildId=None,ProductionBuildId=None)
        sql.append(insert('Projects',p));taskids=[]
        for i,task in enumerate(spec['tasks']):
            title,state,criteria,*support=task;assert state in ['planned','in_progress','review']
            tid=uid(pid+':task:'+str(i));taskids.append(tid)
            t=dict(Id=tid,ProjectId=pid,MilestoneId=None,Kind='milestone',Title=title,Description='根据原表整理的推进节点，范围和验收标准待负责人确认。\n原表进展：'+(row[5] or row[4] or '待补充'),OwnerId=None,Collaborators='[]',State=state,StartsOn=None,DueOn=None,Criteria=criteria,Blocker=support[0] if support else '',NeedsStaffId=None,CompletedAt=None,Revision=1,UpdatedAt=stamp)
            sql.append(insert('Tasks',t))
        notes=[('original','原表记录（记录日期待补充）\n'+'\n\n'.join(headers[i]+'：'+(row[i] or '待补充') for i in [0,1,2,3,4]))]
        if row[5] or row[6]:notes.append(('update','原表 11/12 更新（年份待补充）\n'+(row[5] or '状态待补充')+'\n\n所需支持：'+(row[6] or '原表未填写，待补充')))
        for key,body in notes:
            sql.append(insert('Progress',dict(Id=uid(pid+':progress:'+key),ProjectId=pid,TaskId=None,AuthorId=actor,Body=body,Next='',Mentions='[]',RequestId='csv:'+batch+':'+str(line)+':'+key,CreatedAt=stamp)))
        stream=io.StringIO(newline='');writer=csv.writer(stream);writer.writerow(headers);writer.writerow(row);data=stream.getvalue().encode('utf-8-sig');aid=uid(pid+':source')
        (files/uuid.UUID(aid).hex).write_bytes(data)
        sql.append(insert('Attachments',dict(Id=aid,ProjectId=pid,TaskId=None,AuthorId=actor,Name=f'原表第{line}行.csv',Hash=hashlib.sha256(data).hexdigest(),Size=len(data),CreatedAt=stamp)))
        sql.append(insert('Audits',dict(Id=uid(pid+':import:'+digest),StaffId=actor,ProjectId=pid,Action='project.csv_import',Detail=json.dumps({'source':source.name,'sha256':digest,'row':line,'unknown':['owner','dates'],'taskPlanning':'原表整理，待负责人确认'},ensure_ascii=False),CreatedAt=stamp)))
        records.append(dict(id=pid,name=name,sourceRow=line,taskIds=taskids,progressCount=len(notes),attachmentId=aid))
    # Preserve the pilot and its code/release evidence, outside the default business list.
    sql.append('UPDATE "Projects" SET "Status"=\'archived\',"Revision"="Revision"+1,"UpdatedAt"=now() WHERE "Id"=\'95cde76e-9d8e-4222-af4c-f7e52c8f3bba\' AND "Name"=\'项目协作试点\' AND "Status"<>\'archived\';')
    sql.append(insert('Audits',dict(Id=batch,StaffId=actor,ProjectId=None,Action='csv.import_batch',Detail=json.dumps({'source':source.name,'sha256':digest,'projects':len(records)},ensure_ascii=False),CreatedAt=stamp)))
    script='\\set ON_ERROR_STOP on\nSET standard_conforming_strings=on;\nBEGIN;\nSELECT pg_advisory_xact_lock(617240115);\nSELECT EXISTS(SELECT 1 FROM "Audits" WHERE "Id"='+literal(batch)+') AS imported \\gset\n\\if :imported\n\\echo Already imported; preserving current records.\n\\else\n'+'\n'.join(sql)+'\n\\endif\nCOMMIT;\n'
    (output/'import.sql').write_text(script)
    manifest=dict(source=source.name,sha256=digest,batchId=batch,actor=actor,projects=records,taskCount=sum(len(r['taskIds']) for r in records),progressCount=sum(r['progressCount'] for r in records))
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    (output/'source.csv').write_bytes(raw)
    return manifest
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--plan',type=pathlib.Path,required=True);p.add_argument('--actor',required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
    result=prepare(a.source,json.loads(a.plan.read_text()),a.actor,a.output)
    print(json.dumps({k:result[k] for k in ['sha256','taskCount','progressCount']},ensure_ascii=False))
