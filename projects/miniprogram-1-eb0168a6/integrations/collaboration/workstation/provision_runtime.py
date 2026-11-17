#!/usr/bin/env python3
"""Install only reviewed controller code and test recipes onto the dedicated WSL node."""
import io,json,pathlib,subprocess,tarfile,sys
from transfer_image import SSH

def main():
 source=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/workstation-code')
 cfg=json.loads(pathlib.Path('/opt/siyue-collaboration/delivery.json').read_text())
 keys=sys.argv[1:] or ['siyue-admissions-scheduling']
 allowed=('runtime_image','runtime_command','environment','health_path','proxy_command')
 projects={key:{k:v for k,v in cfg['projects'][key].items() if k in allowed} for key in keys}
 bundle=io.BytesIO()
 with tarfile.open(fileobj=bundle,mode='w') as tar:
  for name in ('delivery_service.py','delivery_recipes.py','workstation_client.py','workstation_build.py'):
   tar.add(source/name,arcname='controller/'+name,recursive=False)
  for name in ('runner.py','receive_build.py'):
   tar.add(source/'workstation'/name,arcname=name,recursive=False)
  for name,value in [('runtime/projects.json',projects),('runtime/delivery.json',dict(projects=projects,workstation=dict(enabled=False)))]:
   data=json.dumps(value).encode();info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o600;tar.addfile(info,io.BytesIO(data))
 subprocess.run([*SSH,'install -d -m 700 /opt/siyue-workstation/runtime /opt/siyue-workstation/controller && tar -xf - -C /opt/siyue-workstation'],input=bundle.getvalue(),check=True)
 # Docker-compatible CLI is used only by the reviewed existing preview runtime.
 script='''set -eu
test ! -e /usr/local/bin/docker || test "$(readlink /usr/local/bin/docker)" = /usr/bin/podman
ln -sf /usr/bin/podman /usr/local/bin/docker
mkdir -p /etc/containers
test ! -e /etc/containers/containers.conf || cp -n /etc/containers/containers.conf /opt/siyue-workstation/containers.conf.before
cat >/etc/containers/containers.conf <<'CONFIG'
[engine]
cgroup_manager="cgroupfs"
events_logger="file"
CONFIG
python3 -m py_compile /opt/siyue-workstation/runner.py /opt/siyue-workstation/controller/*.py
python3 - <<'PYTHON'
import json,pathlib,subprocess
root=pathlib.Path('/opt/siyue-workstation/runtime')
images=json.loads(subprocess.check_output(['podman','images','--no-trunc','--format','json']))
projects=json.loads((root/'projects.json').read_text())
for t in projects.values():
 expected=t['runtime_image']
 matches=[i for i in images if i.get('Digest')==expected or i['Id']==expected.removeprefix('sha256:')]
 if len(matches)!=1:raise RuntimeError('Pinned runtime image is not loaded: '+expected)
 # Docker's containerd image store reports manifest IDs; Podman runs config IDs.
 # Keep the source digest and use only the config ID proven by the loaded manifest.
 t['source_image']=expected;t['runtime_image']=matches[0]['Id']
(root/'projects.json').write_text(json.dumps(projects))
PYTHON
stat -fc %T /sys/fs/cgroup
podman info --format '{{.Host.CgroupsVersion}} {{.Host.CgroupManager}} {{.Host.OCIRuntime.Name}}'
'''
 subprocess.run([*SSH,'/bin/bash -s'],input=script.encode(),check=True)
 print(json.dumps(dict(provisioned=keys,productionCredentials=False)))
if __name__=='__main__':main()
