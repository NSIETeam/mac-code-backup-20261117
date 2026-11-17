#!/usr/bin/env python3
"""Verify existing current-head preview artifacts before promoting workstation routes.

Controller-only operator. No rebuilds, formal releases, service restarts or secrets
are transferred. Only the existing miniapp/collaboration runtime recipes qualify.
"""
import base64,fcntl,hashlib,json,os,pathlib,subprocess,sys,time
FIELDS=('runtime_image','runtime_command','environment','health_path','proxy_command')
def atomic(path,value):
    st=path.stat();temp=path.with_name(path.name+'.expand-preview')
    temp.write_text(json.dumps(value,ensure_ascii=False,indent=2));temp.chmod(st.st_mode&0o777);os.chown(temp,st.st_uid,st.st_gid);temp.replace(path)
def main():
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as d,workstation_client as w
    from importlib.util import spec_from_file_location,module_from_spec
    spec=spec_from_file_location('preview_signer',pathlib.Path(__file__).with_name('enable_preview.py'));signer=module_from_spec(spec);spec.loader.exec_module(signer)
    manifest=pathlib.Path(sys.argv[1]);folder=pathlib.Path(sys.argv[2]);assert manifest.is_absolute() and folder.is_absolute()
    records=json.loads(manifest.read_text());folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (folder/'operator.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        settings=d.settings;cfg=settings();ws=cfg['workstation'];assert ws.get('enabled')
        reference={k:cfg['projects']['siyue-miniapp-teaching'][k] for k in FIELDS if k in cfg['projects']['siyue-miniapp-teaching']}
        additions={}
        for p in records:
            key=d.validate_key(p['key']);t=cfg['projects'][key]
            recipe={k:t[k] for k in FIELDS if k in t}
            normalized=json.loads(json.dumps(recipe))
            kind=normalized['environment'].get('SIYUE_PREVIEW_KIND')
            assert kind in ('miniapp','collaboration'),'Unsupported preview kind'
            normalized['environment']['SIYUE_PREVIEW_KIND']='miniapp'
            assert normalized==reference,'New runtime recipe needs separate review: '+key
            assert d.recipes.git_json(d,cfg,t['repository'],'branches/main')['commit']['id']==p['sha'],'Head changed'
            d.completed_build(p['buildId'],key,p['digest'])
            meta=json.loads((d.ROOT/'sites'/key/'versions'/p['buildId']/'__siyue_version.json').read_text())
            assert all(meta[k]==p[k] for k in ('buildId','digest','sha'))
            additions[key]=recipe
        script='''import json,pathlib,os,subprocess,time
p=pathlib.Path('/opt/siyue-workstation/runtime/projects.json');raw=p.read_bytes();cfg=json.loads(raw);ref=cfg['siyue-miniapp-teaching']
assert ref['source_image']==SPEC['reference']['runtime_image']
assert all(ref.get(k)==SPEC['reference'].get(k) for k in ('runtime_command','environment','health_path','proxy_command'))
actual=subprocess.check_output(['docker','image','inspect','--format','{{.Id}}',ref['runtime_image']],text=True).strip().removeprefix('sha256:');assert actual==ref['runtime_image']
for key,recipe in SPEC['projects'].items():
 expected={**recipe,'source_image':recipe['runtime_image'],'runtime_image':ref['runtime_image']}
 if key in cfg:assert cfg[key]==expected,'Configured recipe changed'
 else:cfg[key]=expected
assert p.read_bytes()==raw
backup=p.with_name(p.name+'.before-preview-expand-'+str(time.time_ns()));backup.write_bytes(raw);backup.chmod(0o600)
st=p.stat();temp=p.with_name(p.name+'.expand-preview');temp.write_text(json.dumps(cfg));temp.chmod(st.st_mode&0o777);os.chown(temp,st.st_uid,st.st_gid);temp.replace(p)
print(json.dumps(dict(keys=SPEC['keys'],image=actual,backup=str(backup))))
'''
        result=subprocess.run(w.command(ws)[:-1]+['python3 -'],input='SPEC='+repr(dict(reference=reference,keys=list(additions),projects=additions))+'\n'+script,text=True,capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError(result.stderr[-1200:])
        (folder/'node-provisioned.json').write_text(result.stdout)
        for p in records:
            key=p['key'];live=settings();t=live['projects'][key]
            assert {k:t[k] for k in FIELDS if k in t}==additions[key]
            trial=json.loads(json.dumps(live));trial['workstation']['projects']=list(dict.fromkeys(trial['workstation']['projects']+[key]));d.settings=lambda:trial
            try:
                release=d.ROOT/'sites'/key/'versions'/p['buildId']
                row=w.ensure(d,t,key,p['buildId'],release,viewed=True)
                health=w.request(d,row,dict(path=t['health_path'],method='GET'));assert health['status']==200
                entry=t.get('entry','index.html');page=w.request(d,row,dict(path=entry,method='GET'))
                expected=(release/'wwwroot'/entry).read_bytes();actual=base64.b64decode(page['body'])
                assert page['status']==200 and b'<html' in actual.lower() and actual==expected,'Page differs from verified artifact'
                assert w.touch(d,key,p['buildId'],p['digest'])['touched']
            finally:d.settings=settings
            inspect='''import json,subprocess
v=json.loads(subprocess.check_output(['docker','inspect',NAME],text=True))[0];h=v['HostConfig'];c=v['Config']
assert v['State']['Running'] and c['User']=='1000:1000'
assert h['Memory']==384*1024**2 and h['CpuQuota']==50000 and h['PidsLimit']==128 and h['NetworkMode']=='none' and h['ReadonlyRootfs']
print(json.dumps(dict(user=c['User'],running=True,limits={k:h[k] for k in ('Memory','CpuQuota','PidsLimit','NetworkMode','ReadonlyRootfs')})))
'''
            x=subprocess.run(w.command(ws)[:-1]+['python3 -'],input='NAME='+repr(row['container'])+'\n'+inspect,text=True,capture_output=True,timeout=30)
            if x.returncode:raise RuntimeError(x.stderr[-1000:])
            proof=dict(**p,health=200,page=200,pageSha256=hashlib.sha256(actual).hexdigest(),isolation=json.loads(x.stdout),checkedAt=time.time(),container=row['container'])
            (folder/(key+'.verified.json')).write_text(json.dumps(proof,indent=2))
            raw=d.CONFIG.read_bytes();live=settings()
            assert live['projects'][key]==t and d.recipes.git_json(d,live,t['repository'],'branches/main')['commit']['id']==p['sha']
            old=d.ROOT/'preview-runtime.json'
            if old.exists():
                state=json.loads(old.read_text())
                assert not(state.get('key')==key and time.time()-state.get('viewerAt',0)<90),'An existing primary preview is being viewed'
            if key not in live['workstation']['projects']:
                backup=folder/(key+'.controller-before.json');assert not backup.exists();backup.write_bytes(raw);backup.chmod(0o600)
                live['workstation']['projects'].append(key);assert d.CONFIG.read_bytes()==raw;atomic(d.CONFIG,live)
                try:
                    q=dict(key=key,buildId=p['buildId'],digest=p['digest'],path=t['health_path'],method='GET',viewer='workstation-route-expansion')
                    assert signer.signed(live,'/preview',q)['status']==200
                    response=signer.signed(live,'/preview',dict(q,path='__siyue_version.json'));assert response['status']==200
                    version=json.loads(base64.b64decode(response['body']));assert all(version[k]==p[k] for k in ('buildId','sha','digest'))
                except Exception:
                    current=settings();assert current==live,'Concurrent change; inspect before rollback';atomic(d.CONFIG,json.loads(raw));raise
            proof.update(routed=True,verifiedAt=time.time());(folder/(key+'.json')).write_text(json.dumps(proof,indent=2));print(json.dumps(dict(key=key,state='verified-and-routed',sha=p['sha'],buildId=p['buildId'])),flush=True)
if __name__=='__main__':main()
