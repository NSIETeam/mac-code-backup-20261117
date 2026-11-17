#!/usr/bin/env python3
"""Verify fixed repository heads on an existing node before expanding build routing.

Run on the controller with an explicit evidence directory and project keys.
Does not replace worker code, change preview routing, retry failed jobs, or publish.
Interrupted observations reuse the recorded job and its immutable source manifest.
"""
import fcntl,json,os,pathlib,subprocess,sys,time,uuid

FIELDS=('check_image','check_command','build_memory','timeout','output','mode','repository')

def atomic(path,data):
    temp=path.with_name(path.name+'.new');temp.write_text(json.dumps(data,indent=2));temp.chmod(0o600);temp.replace(path)

def main():
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as d,workstation_client as transport,workstation_build as build
    folder=pathlib.Path(sys.argv[1]);keys=sys.argv[2:]
    if not folder.is_absolute() or not keys:raise ValueError('Absolute evidence directory and explicit projects required')
    folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (folder/'operator.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        settings=d.settings;cfg=settings();_,version=d.recipes.snapshot(cfg)
        if not cfg['workstation'].get('enabled'):raise ValueError('Workstation disabled')
        projects={key:{k:v for k,v in cfg['projects'][d.validate_key(key)].items() if k in FIELDS} for key in keys}
        if any(t.get('mode') not in ('static','package') for t in projects.values()):raise ValueError('Unsupported mode')
        # Only extend the existing, verified recipe version; never overwrite code.
        script='''import json,pathlib,subprocess,sys,time
sys.path.insert(0,'/opt/siyue-workstation');from build_sandbox import builder
p=pathlib.Path('/opt/siyue-workstation/build-config.json');raw=p.read_bytes();cfg=json.loads(raw)
assert cfg['recipeVersion']==SPEC['version'],'Recipe version changed'
assert (pathlib.Path('/var/lib/siyue-workstation-build/recipes')/SPEC['version']).is_dir()
for image in {t['check_image'] for t in SPEC['projects'].values()}:
 actual=subprocess.check_output(builder(['image','inspect','--format','{{.Id}}',image]),cwd='/home/siyue-build',text=True).strip().removeprefix('sha256:')
 assert actual==image.removeprefix('sha256:'),'Image mismatch'
for key,t in SPEC['projects'].items():
 if key in cfg['projects']:assert cfg['projects'][key]==t,'Existing project changed'
cfg['projects'].update(SPEC['projects'])
assert p.read_bytes()==raw,'Concurrent configuration change'
backup=p.with_name(p.name+'.before-expand-'+str(time.time_ns()));backup.write_bytes(raw);backup.chmod(0o600)
temp=p.with_name(p.name+'.expand');temp.write_text(json.dumps(cfg));temp.chmod(0o600);temp.replace(p)
print(json.dumps(dict(projects=list(cfg['projects']),recipeVersion=cfg['recipeVersion'],backup=str(backup))))
'''
        code='SPEC='+repr(dict(version=version,projects=projects))+'\n'+script
        result=subprocess.run(transport.command(cfg['workstation'])[:-1]+['python3 -'],input=code,text=True,capture_output=True,timeout=60)
        if result.returncode:raise RuntimeError(result.stderr[-1500:])
        atomic(folder/'provisioned.json',json.loads(result.stdout));print(result.stdout.strip(),flush=True)
        for key in keys:
            record=folder/(key+'.json');current=settings();t=current['projects'][key]
            if {k:v for k,v in t.items() if k in FIELDS}!=projects[key]:raise ValueError('Recipe changed: '+key)
            if d.recipes.snapshot(current)[1]!=version:raise ValueError('Trusted recipes changed')
            head=d.recipes.git_json(d,current,t['repository'],'branches/main')['commit']['id']
            if record.exists():
                proof=json.loads(record.read_text());q=proof['request']
                if q['sha']!=head:raise ValueError('Recorded head changed; use a new evidence batch')
            else:
                q=dict(id=str(uuid.uuid4()),key=key,sha=head,kind='check')
                proof=dict(request=q,recipeVersion=version,createdAt=time.time());atomic(record,proof)
            with d.db() as db:
                row=db.execute('SELECT request,state,result FROM jobs WHERE id=?',(q['id'],)).fetchone()
                if row is None:
                    db.execute('INSERT INTO jobs VALUES(?,?,?,?,?)',(q['id'],'build',json.dumps(q,sort_keys=True),'queued','{"state":"queued"}'))
                elif json.loads(row[0])!=q:raise ValueError('Job identity mismatch')
            if row and row[1]=='failed':
                print(json.dumps(dict(key=key,state='failed',job=q['id'],action='explicit investigation required')),flush=True);continue
            if not row or row[1]!='succeeded':
                # This private verification job is not submitted to the business queue.
                current['workstation']['buildProjects']=list(set(current['workstation'].get('buildProjects',[])+[key]))
                d.settings=lambda:current
                print(json.dumps(dict(key=key,state='starting',job=q['id'],sha=head)),flush=True)
                try:
                    assert build.build(d,current,t,key,q['id'],q)
                except Exception as e:
                    d.update(q['id'],'failed',summary=str(e));print(json.dumps(dict(key=key,state='failed',error=str(e)[-1200:])),flush=True)
                finally:d.settings=settings
            with d.db() as db:row=db.execute('SELECT state,result FROM jobs WHERE id=?',(q['id'],)).fetchone()
            proof.update(state=row[0],result=json.loads(row[1]),observedAt=time.time());atomic(record,proof)
            if row[0]!='succeeded':
                if row[0] in ('running','queued'):print(json.dumps(dict(key=key,state=row[0],action='resume same batch')),flush=True);return
                continue
            receipt=json.loads((d.ROOT/'workstation-builds'/q['id']/'receipt.json').read_text())
            limits=receipt['limits']
            assert receipt['backend']=='workstation' and receipt['commit']==head and receipt['recipeVersion']==version
            assert limits['exitCode']==0 and limits['cpuQuota']==100000 and limits['pidsLimit']==256
            assert limits['memoryLimit']==int(t.get('build_memory','896m')[:-1])*1024**2
            assert d.file_digest(d.ROOT/'artifacts'/(q['id']+'.tar'))==receipt['digest']
            raw=d.CONFIG.read_bytes();live=settings()
            if live['projects'][key]!=t or d.recipes.snapshot(live)[1]!=version:raise ValueError('Configuration drift before promotion')
            if d.recipes.git_json(d,live,t['repository'],'branches/main')['commit']['id']!=head:raise ValueError('Head advanced before promotion')
            if key not in live['workstation'].get('buildProjects',[]):
                live['workstation'].setdefault('buildProjects',[]).append(key)
                backup=folder/(key+'.controller-before.json');backup.write_bytes(raw);backup.chmod(0o600)
                if d.CONFIG.read_bytes()!=raw:raise ValueError('Concurrent controller configuration change')
                atomic(d.CONFIG,live)
            proof.update(promoted=True,promotedAt=time.time(),receipt=receipt);atomic(record,proof)
            print(json.dumps(dict(key=key,state='verified-and-routed',job=q['id'],sha=head)),flush=True)

if __name__=='__main__':main()
