"""Temporary unprivileged candidate identity; no existing staff credentials change."""
import hashlib,json,secrets,time,urllib.parse
from crm_candidate_common import *

REALM='siyue-migration-v101'

def create():
    if (STATE/'probe-private.json').exists():
        old=json.loads((STATE/'probe-private.json').read_text())
        if old.get('phase')!='removed':raise ValueError('Existing probe must be inspected')
        (STATE/'probe-private.json').rename(STATE/('probe-removed-'+str(time.time_ns())+'.json'))
    database_identity();client,admin=idp();name='workstation-check-'+secrets.token_hex(8)
    private=dict(clientId=name,principal=name,phase='creating')
    save('probe-private.json',private)
    mapper=dict(name='candidate-audience',protocol='openid-connect',protocolMapper='oidc-audience-mapper',
                config={'included.custom.audience':'siyue-api','access.token.claim':'true','id.token.claim':'false'})
    client.request('/admin/realms/'+REALM+'/clients','POST',dict(clientId=name,enabled=True,publicClient=False,
        serviceAccountsEnabled=True,standardFlowEnabled=False,directAccessGrantsEnabled=False,protocolMappers=[mapper]),admin)
    rows=client.request('/admin/realms/'+REALM+'/clients?clientId='+name,token=admin)
    assert len(rows)==1;private['clientUuid']=rows[0]['id'];save('probe-private.json',private)
    path='/admin/realms/'+REALM+'/clients/'+private['clientUuid']
    private['secret']=client.request(path+'/client-secret',token=admin)['value']
    private['subject']=client.request(path+'/service-account-user',token=admin)['id'];save('probe-private.json',private)
    issuer=json.loads((SOURCE/'state.json').read_text())['issuer']
    # No campus membership or permission grants: this probe cannot read business records.
    sql('INSERT INTO core_principal(public_id,oidc_issuer,oidc_subject,scope_type) VALUES('+','.join(map(literal,[name,issuer,private['subject'],'CAMPUS']))+');')
    private['phase']='ready';save('probe-private.json',private)
    return dict(principalCreated=True,permissions=[],campuses=[])

def token():
    p=json.loads((STATE/'probe-private.json').read_text());assert p['phase']=='ready'
    client,_=idp()
    return client.request('/realms/'+REALM+'/protocol/openid-connect/token','POST',
        dict(grant_type='client_credentials',client_id=p['clientId'],client_secret=p['secret']),form=True)['access_token']

def verify(base=PUBLIC):
    started=time.monotonic();p=json.loads((STATE/'probe-private.json').read_text());access=token()
    assert request(base,'identity/me')[0]==401,'Anonymous identity was not rejected'
    assert request(base,'identity/me','invalid-probe-token')[0]==401,'Malformed token was not rejected'
    status,raw=request(base,'identity/me',access)
    if status!=200:raise ValueError('Authenticated candidate identity status '+str(status))
    v=json.loads(raw);data=v['data']
    assert data['principalId']==p['principal'], 'Candidate subject mapping changed'
    assert not data['permissions'] and not data['campusIds'],'Probe unexpectedly has grants'
    status,_=request(base,'crm/customers',access)
    assert status==403,'Unprivileged business read was not rejected: '+str(status)
    status,raw=request(base,'public/oidc-config');assert status==200
    public=json.loads(raw);public.pop('traceId',None)
    return dict(at=time.time(),anonymousRejected=True,malformedTokenRejected=True,signedTokenAccepted=True,
        databaseIdentityResolved=True,unprivilegedReadRejected=True,subjectPreserved=True,
        publicConfigSha256=hashlib.sha256(json.dumps(public,sort_keys=True).encode()).hexdigest(),seconds=round(time.monotonic()-started,3))

def cleanup():
    private=json.loads((STATE/'probe-private.json').read_text());client,admin=idp()
    # Fail closed rather than removing a principal that acquired any unexpected grants.
    p=literal(private['principal']);subject=literal(private['subject'])
    count=sql('SELECT COUNT(*) FROM core_principal p JOIN core_principal_permission g ON p.id=g.principal_id WHERE p.public_id='+p+';').strip()
    assert count=='0'
    assert sql('SELECT COUNT(*) FROM core_principal p JOIN core_principal_campus g ON p.id=g.principal_id WHERE p.public_id='+p+';').strip()=='0'
    client.request('/admin/realms/'+REALM+'/clients/'+private['clientUuid'],'DELETE',token=admin)
    client.request('/admin/realms/'+REALM+'/users/'+private['subject'],token=admin,expected=(404,))
    sql('DELETE FROM core_principal WHERE public_id='+p+' AND oidc_subject='+subject+';')
    assert sql('SELECT COUNT(*) FROM core_principal WHERE public_id='+p+';').strip()=='0'
    private['phase']='removed';private.pop('secret',None);save('probe-private.json',private)
    save('probe-cleanup.json',dict(at=time.time(),clientDeleted=True,principalDeleted=True))

if __name__=='__main__':
    import sys
    action=sys.argv[1]
    if action=='create':result=create()
    elif action=='verify':result=verify();save('probe-latest.json',result)
    elif action=='cleanup':cleanup();result=dict(removed=True)
    else:raise ValueError('Unknown action')
    print(json.dumps(result))
