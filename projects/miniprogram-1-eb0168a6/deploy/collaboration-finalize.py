#!/usr/bin/env python3
"""Install the entry on the current workbench and schedule verified private backups."""
import hashlib,json,os,pathlib,shutil,subprocess,time
root=pathlib.Path('/opt/siyue-collaboration');stamp=time.strftime('%Y%m%d-%H%M%S')
def run(args):return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True)
def write(path,content):path=pathlib.Path(path);path.write_text(content);path.chmod(0o644)
run(['python3',str(root/'backup.py')])
write('/etc/systemd/system/siyue-collaboration-backup.service',f'''[Unit]
Description=Private collaboration and code backups
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {root}/backup.py
Nice=15
IOSchedulingClass=idle
CPUQuota=30%
MemoryMax=256M
UMask=0077
''')
write('/etc/systemd/system/siyue-collaboration-backup.timer','''[Unit]
Description=Daily collaboration backup
[Timer]
OnCalendar=*-*-* 03:40:00
RandomizedDelaySec=10m
Persistent=true
[Install]
WantedBy=timers.target
''')
run(['systemctl','daemon-reload']);run(['systemctl','enable','--now','siyue-collaboration-backup.timer'])
current=pathlib.Path('/opt/siyue-workbench/current');before=current.resolve();stage=current.parent/'releases'/('collaboration-'+stamp)
entry='<script type="module" src="/miniapp/collaboration/workbench-link.mjs?v=20261114-v1"></script>'
if entry not in (before/'index.html').read_text():
 shutil.copytree(before,stage,symlinks=True);index=stage/'index.html';index.write_text(index.read_text().replace('</body>',entry+'</body>'))
 if current.resolve()!=before:raise RuntimeError('Workbench changed concurrently; rerun against its latest release')
 link=current.with_name('current-collaboration-'+stamp);link.symlink_to(stage);link.replace(current)
 (root/('workbench-entry-'+stamp+'.json')).write_text(json.dumps({'previous':str(before),'current':str(stage),'appJsUnchanged':hashlib.sha256((before/'app.js').read_bytes()).hexdigest()==hashlib.sha256((stage/'app.js').read_bytes()).hexdigest()}))
print('Workbench entry added to latest release; daily backup timer enabled')
