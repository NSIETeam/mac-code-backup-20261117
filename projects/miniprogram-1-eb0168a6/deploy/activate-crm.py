#!/usr/bin/env python3
"""Run on the authorized CRM host as root after building the reviewed image."""
import datetime, hashlib, json, os, pathlib, secrets, shutil, subprocess, time, urllib.request, urllib.error
ROOT = pathlib.Path('/opt/siyue-miniapp')
TAG = '20261023-1709'
RELEASE = ROOT / 'releases' / TAG
NGINX = pathlib.Path('/etc/nginx/sites-available/siyue-crm.conf')
def run(args, data=None):
    return subprocess.run(args,input=data,text=True,check=True,capture_output=True).stdout.strip()
def sql(command):
    return run(['sudo','-u','postgres','psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1'], command)
def get(path, method='GET', body=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(path,method=method,data=body,headers={'Content-Type':'application/json'}),timeout=15) as r: return r.status, r.read()
    except urllib.error.HTTPError as e:return e.code,e.read()
def main():
    assert os.geteuid()==0
    assert get('https://www.siyuejn.com/crm/')[0]==200, 'CRM preflight failed'
    run(['docker','image','inspect','siyue-miniapp:'+TAG])
    config=ROOT/'.env'
    if not config.exists():
        password=secrets.token_hex(32)
        values={'POSTGRES_PASSWORD':password,'RELEASE_TAG':TAG,'WeChat__AppId':'wx7ca8803899e3583f','WeChat__Secret':'','BootstrapCode':secrets.token_hex(24)}
        with os.fdopen(os.open(config,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:f.write(''.join(k+'='+v+'\n' for k,v in values.items()))
    values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
    password=values['POSTGRES_PASSWORD']; assert len(password)==64 and all(c in '0123456789abcdef' for c in password)
    if not sql("SELECT 1 FROM pg_roles WHERE rolname='siyue_miniapp';"):
        sql("CREATE ROLE siyue_miniapp LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password+"';")
    if not sql("SELECT 1 FROM pg_database WHERE datname='siyue_miniapp';"):
        sql('CREATE DATABASE siyue_miniapp OWNER siyue_miniapp;')
    assert sql("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='siyue_miniapp';")=='siyue_miniapp'
    data=ROOT/'data'/'objects';data.mkdir(parents=True,exist_ok=True);os.chown(data,1654,1654);data.chmod(0o700)
    shutil.copy2(RELEASE/'deploy'/'compose.crm.yml',ROOT/'compose.yml')
    compose=['docker','compose','--project-directory',str(ROOT),'-f',str(ROOT/'compose.yml')]
    run(compose+['config','--quiet'])
    # Never alter CRM's database, credentials, process or existing routes.
    run(compose+['up','-d','api','worker'])
    for attempt in range(30):
        try:
            if get('http://127.0.0.1:5082/health')[0]==200 and get('http://127.0.0.1:5083/health')[0]==200: break
        except (OSError,urllib.error.URLError):pass
        time.sleep(2)
    else:raise RuntimeError('Application health check failed; public routes unchanged')
    before=NGINX.read_bytes(); backup=ROOT/('nginx-before-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.conf');backup.write_bytes(before)
    marker=' include /etc/nginx/snippets/siyue-miniapp.conf;'
    snippet=pathlib.Path('/etc/nginx/snippets/siyue-miniapp.conf');old_snippet=snippet.read_bytes() if snippet.exists() else None
    text=before.decode(); assert text.count('server_name www.siyuejn.com;')==1
    if marker not in text:text=text.replace('server_name www.siyuejn.com;','server_name www.siyuejn.com;\n'+marker)
    try:
        shutil.copy2(RELEASE/'deploy'/'nginx-miniapp.conf',snippet)
        assert NGINX.read_bytes()==before,'Nginx changed concurrently'
        NGINX.write_text(text);run(['nginx','-t']);run(['systemctl','reload','nginx'])
        for attempt in range(5):
            if get('https://www.siyuejn.com/miniapp/health')[0]==200:break
            time.sleep(1)
        else:raise RuntimeError('Public mini-program health check failed')
        assert get('https://www.siyuejn.com/crm/')[0]==200,'CRM postflight failed'
        assert get('https://www.siyuejn.com/miniapp/api/auth/preview','POST',b'{}')[0]==404,'Development preview must remain closed'
        if not values.get('WeChat__Secret'):
            assert get('https://www.siyuejn.com/miniapp/api/auth/wechat','POST',b'{"code":"deployment-check"}')[0]==503,'WeChat login must explicitly await AppSecret'
    except Exception:
        NGINX.write_bytes(before)
        if old_snippet is None:snippet.unlink(missing_ok=True)
        else:snippet.write_bytes(old_snippet)
        run(['nginx','-t']);run(['systemctl','reload','nginx']);raise
    result={'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'release':TAG,'image':run(['docker','image','inspect','siyue-miniapp:'+TAG,'--format','{{.Id}}']),'publicHealth':200,'crmHealth':200,'developmentPreview':404,'wechatLogin':'configured_not_login_tested' if values.get('WeChat__Secret') else 'awaiting_appsecret','nginxBackup':str(backup)}
    (ROOT/'deployment.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
