"""Run as administrator on primary. Does not restart or replace business API."""
import hashlib,json,os,secrets,shutil,subprocess,sys,time
from pathlib import Path
if subprocess.run(['getent','group','1654'],stdout=subprocess.DEVNULL).returncode: subprocess.run(['groupadd','--gid','1654','siyue-objects'],check=True)
if subprocess.run(['getent','passwd','1654'],stdout=subprocess.DEVNULL).returncode: subprocess.run(['useradd','--uid','1654','--gid','1654','--no-create-home','--shell','/usr/sbin/nologin','siyue-aux'],check=True)
source=Path(sys.argv[1]); root=Path('/opt/siyue-auxiliary'); root.mkdir(exist_ok=True)
(root/'state').mkdir(exist_ok=True)
for name in ('hub.py','pixels_dispatch.py','pdf_pixels.local.py','native_contract.py'): shutil.copy2(source/name,root/name)
private=root/'config.json'
if private.exists(): c=json.loads(private.read_text())
else: c=dict(nodeToken=secrets.token_hex(32),dispatchToken=secrets.token_hex(32))
c.update(state=str(root/'state'),objects='/opt/siyue-miniapp/data/objects',port=5096,scriptHash=hashlib.sha256((root/'pdf_pixels.local.py').read_bytes()).hexdigest())
private.write_text(json.dumps(c));private.chmod(0o640);os.chown(private,0,1654)
os.chown(root/'state',1654,1654)
for directory in ('inputs','native-results'):
 p=root/'state'/directory;p.mkdir(exist_ok=True);os.chown(p,1654,1654)
(root/'dispatch.json').write_text(json.dumps(dict(url='http://127.0.0.1:5096',token=c['dispatchToken'])))
(root/'dispatch.json').chmod(0o640);os.chown(root/'dispatch.json',0,1654)
unit='''[Unit]
Description=Siyue auxiliary compute and replica hub
After=network-online.target
[Service]
Type=simple
User=1654
Group=1654
ExecStart=/usr/bin/python3 /opt/siyue-auxiliary/hub.py /opt/siyue-auxiliary/config.json
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/opt/siyue-auxiliary/state
ReadOnlyPaths=/opt/siyue-miniapp/data/objects
MemoryMax=256M
CPUQuota=40%
TasksMax=32
[Install]
WantedBy=multi-user.target
'''
Path('/etc/systemd/system/siyue-auxiliary.service').write_text(unit)
# A separate, exact prefix exposes only node routes. Internal submission/status remain loopback-only.
snippet=Path('/etc/nginx/snippets/siyue-miniapp.conf'); backup=root/('nginx-before-'+str(int(time.time()))+'.conf');shutil.copy2(snippet,backup)
ng='''\n# BEGIN SIYUE AUXILIARY NODE
location ^~ /miniapp/aux-node/ {
    if ($uri !~ "^/miniapp/aux-node/node/(heartbeat|claim|result|manifest|object|input)$") { return 404; }
    rewrite ^/miniapp/aux-node/(.*)$ /$1 break;
    proxy_pass http://127.0.0.1:5096;
    proxy_set_header Authorization $http_authorization;
    client_max_body_size 18m;
    proxy_read_timeout 100s;
    access_log off;
    add_header X-Robots-Tag "noindex, nofollow" always;
}
# END SIYUE AUXILIARY NODE
'''
text=snippet.read_text()
if '# BEGIN SIYUE AUXILIARY NODE' not in text: snippet.write_text(text+ng)
try: subprocess.run(['nginx','-t'],check=True)
except Exception: shutil.copy2(backup,snippet);raise
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now','siyue-auxiliary'],check=True)
subprocess.run(['systemctl','reload','nginx'],check=True)
print(json.dumps(dict(installed=True,scriptHash=c['scriptHash'])))
