#!/usr/bin/env python3
"""Provision administrator-owned recipes and exact rootless build images."""
import io,json,pathlib,subprocess,sys,tarfile
from transfer_image import SSH

def main():
 sys.path.insert(0,'/opt/siyue-collaboration');import delivery_service as d
 cfg=d.settings();recipe,version=d.recipes.snapshot(cfg);keys=sys.argv[1:] or ['siyue-admissions-scheduling','siyue-miniapp-teaching']
 allowed=('check_image','check_command','build_memory','timeout','output','mode','repository')
 projects={key:{k:v for k,v in cfg['projects'][key].items() if k in allowed} for key in keys}
 for t in projects.values():
  if t['mode'] not in ('static','package'):raise ValueError('Unreviewed build mode')
 source=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116/workstation-code')
 payload=io.BytesIO()
 with tarfile.open(fileobj=payload,mode='w') as tar:
  for p in recipe.rglob('*'):
   if p.is_symlink():raise ValueError('Recipe symlink')
   if p.is_file():tar.add(p,arcname='recipes/'+version+'/'+str(p.relative_to(recipe)),recursive=False)
 script='install -d -m 711 /var/lib/siyue-workstation-build /var/lib/siyue-workstation-build/jobs; umask 022; tar -xf - -C /var/lib/siyue-workstation-build; chmod -R a-w /var/lib/siyue-workstation-build/recipes'
 subprocess.run([*SSH,script],input=payload.getvalue(),check=True,timeout=120)
 for name in ('build_sandbox.py','build_worker.py','runner.py'):
  p=source/'workstation'/name
  subprocess.run([*SSH,'cat > /opt/siyue-workstation/'+name+'.new && chmod 644 /opt/siyue-workstation/'+name+'.new && mv /opt/siyue-workstation/'+name+'.new /opt/siyue-workstation/'+name],input=p.read_bytes(),check=True,timeout=20)
 raw=json.dumps(dict(recipeVersion=version,projects=projects)).encode()
 subprocess.run([*SSH,'umask 077; cat > /opt/siyue-workstation/build-config.json.new; mv /opt/siyue-workstation/build-config.json.new /opt/siyue-workstation/build-config.json'],input=raw,check=True,timeout=20)
 check='''import pathlib,json,subprocess,sys
sys.path.insert(0,'/opt/siyue-workstation');from build_sandbox import builder
c=json.loads(pathlib.Path('/opt/siyue-workstation/build-config.json').read_text())
for image in {t['check_image'] for t in c['projects'].values()}:
 out=subprocess.check_output(builder(['image','inspect','--format','{{.Id}}',image]),cwd='/home/siyue-build',text=True).strip()
 assert out.removeprefix('sha256:')==image.removeprefix('sha256:')
print(json.dumps(dict(projects=list(c['projects']),recipeVersion=c['recipeVersion'],rootlessImagesVerified=True)))
'''
 subprocess.run([*SSH,'python3 -'],input=check.encode(),check=True,timeout=30)
if __name__=='__main__':main()
