"""Fixed-target helpers for the inventoried v101 Java CRM candidate move."""
import hashlib,json,pathlib,subprocess,sys,time,urllib.error,urllib.request

SOURCE=pathlib.Path('/opt/siyue-java-real-candidate/v101')
RUNTIME=pathlib.Path('/opt/siyue-java-real-runtime/v101')
STATE=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/crm-candidate-migration')
NODE='/opt/siyue-workstation/services/crm-candidate'
UNIT='siyue-java-real-candidate-v101'
PROXY='siyue-workstation-crm-candidate'
CONTAINER='siyue-service-crm-candidate'
JAR='6103a888052a1114d7cfb78fc01ab333d39317da1a83ae835ddb21ac45688df9'
IMAGE='80c241114cb75af42a1568d980a3fcf3f4bd69404317608b602a4864ecc7a319'
PUBLIC='https://www.siyuejn.com/java-candidate-v101/api/v2/'

def digest(path):
    with pathlib.Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def save(name,value):
    STATE.mkdir(mode=0o700,parents=True,exist_ok=True)
    p=STATE/name;pending=p.with_suffix('.pending')
    pending.write_text(json.dumps(value,ensure_ascii=False,indent=2));pending.chmod(0o600);pending.replace(p)

def run(args,timeout=90,**kwargs):
    result=subprocess.run(args,capture_output=True,timeout=timeout,**kwargs)
    if result.returncode:raise RuntimeError('Command failed: '+args[0]+' (exit '+str(result.returncode)+')')
    return result.stdout

def ssh():
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as delivery,workstation_client
    return workstation_client.command(delivery.settings()['workstation'])[:-1]

def node(script,timeout=90):
    result=subprocess.run(ssh()+['python3 -'],input=script,text=True,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError('CRM node operation failed: '+result.stderr[-700:])
    return json.loads(result.stdout)

def sql(statement):
    state=json.loads((SOURCE/'state.json').read_text())
    assert state['schema']=='siyue_migration_candidate_3723926ba8b2'
    return run(['mysql','--defaults-extra-file='+str(SOURCE/'mysql-admin.cnf'),'--batch','--skip-column-names',state['schema']],input=statement,text=True)

def literal(value):return '0x'+str(value).encode().hex()

def database_identity():
    expected=json.loads((SOURCE/'state.json').read_text())['mysqlServerUuid']
    actual=sql('SELECT @@server_uuid;').strip()
    if actual!=expected:raise ValueError('Candidate database server changed')
    return actual

def source_identity():
    if digest(RUNTIME/'siyue-core.jar')!=JAR:raise ValueError('Candidate jar changed')
    pid=int(run(['systemctl','show',UNIT,'-p','MainPID','--value'],text=True).strip())
    if pid<1:raise ValueError('Source Java service is not running')
    command=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes().split(b'\0')[:-1]
    expected=['/usr/bin/java','-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=1',
        '-Djavax.net.ssl.trustStore='+str(RUNTIME/'truststore'),'-Djavax.net.ssl.trustStorePassword=changeit','-jar',str(RUNTIME/'siyue-core.jar')]
    if [x.decode() for x in command]!=expected:raise ValueError('Source Java command changed')
    env={}
    for item in pathlib.Path('/proc/'+str(pid)+'/environ').read_bytes().split(b'\0'):
        if b'=' not in item:continue
        key,value=item.decode().split('=',1)
        if key.startswith(('SIYUE_','SPRING_')):env[key]=value
    state=json.loads((SOURCE/'state.json').read_text())
    assert env['SIYUE_CORE_PORT']=='18086' and env['SIYUE_CORE_BIND']=='127.0.0.1'
    assert env['SIYUE_MYSQL_URL'].startswith('jdbc:mysql://127.0.0.1:50571/'+state['schema']+'?')
    assert env['SIYUE_OIDC_ISSUER']==state['issuer']
    assert env['SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI']=='https://localhost:18449/realms/siyue-migration-v101/protocol/openid-connect/certs'
    assert env['SIYUE_LEGACY_IDENTITY_MIGRATION_ENABLED']=='false'
    if any('\n' in v or '\r' in v for v in env.values()):raise ValueError('Multiline runtime environment')
    receipt=dict(pid=pid,jarSha256=JAR,truststoreSha256=digest(RUNTIME/'truststore'),
        environmentSha256=hashlib.sha256(json.dumps(env,sort_keys=True).encode()).hexdigest(),
        databaseServerUuid=database_identity(),unitSha256=digest('/etc/systemd/system/'+UNIT+'.service'),
        unitFileState=run(['systemctl','show',UNIT,'-p','UnitFileState','--value'],text=True).strip())
    return receipt,env

def request(base,path,token=None):
    headers={'Authorization':'Bearer '+token} if token else {}
    req=urllib.request.Request(base+path,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:return r.status,r.read(1024*1024)
    except urllib.error.HTTPError as error:return error.code,error.read(1024*1024)

def idp():
    root=pathlib.Path('/opt/siyue-idp-candidate/v100');sys.path.insert(0,str(root))
    from probe import Client,admin
    client=Client(root);private=json.loads((root/'private.json').read_text())
    return client,admin(client,private)
