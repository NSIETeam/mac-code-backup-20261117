"""Administrator-owned CI recipes. Executed only inside the rootless network-isolated builder."""
import pathlib,subprocess,sys,shutil,json,re,os,urllib.parse
os.environ["NODE_PATH"]="/siyue-ci/node_modules"
ROOT=pathlib.Path.cwd();(ROOT/".ci-tmp").mkdir(exist_ok=True);os.environ["TMPDIR"]=str(ROOT/".ci-tmp");kind=sys.argv[1];DIST=ROOT/'dist';TRUSTED=pathlib.Path(__file__).parent

def run(*args):print('+',' '.join(args),flush=True);subprocess.run(args,check=True)
def copytree(source,target):shutil.copytree(source,target,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.git','node_modules','__pycache__','bin','obj','dist','.local','.ci-tmp'))
def syntax(paths):
 count=0
 for base in paths:
  for f in sorted(pathlib.Path(base).rglob('*')):
   if f.suffix not in {'.js','.mjs','.cjs'} or any(x in {'node_modules','bin','obj','vendor'} for x in f.parts):continue
   subprocess.run(['node','--check',str(f)],check=True,stdout=subprocess.DEVNULL);count+=1
   if f.suffix in ('.mjs','.js'):
    for target in re.findall(r'''(?:from\s*|import\s*)['"](\.[^'"]+)['"]''',f.read_text()):
     if not (f.parent/urllib.parse.unquote(urllib.parse.urlsplit(target).path)).is_file():raise ValueError('Missing module import: '+str(f)+' -> '+target)
 print('JavaScript syntax checked:',count,flush=True)
DIST.mkdir(exist_ok=True)
if kind in ('miniapp','collaboration'):
 if kind=='collaboration':
  run('dotnet','restore','integrations/collaboration/tests/Collaboration.Tests.csproj','-r','linux-x64','--locked-mode','--source','/nuget')
  run('dotnet','test','integrations/collaboration/tests/Collaboration.Tests.csproj','--no-restore','-r','linux-x64','-c','Release','--logger','console;verbosity=minimal')
  run('dotnet','publish','integrations/collaboration/Collaboration.csproj','--no-restore','-r','linux-x64','--self-contained','false','-c','Release','-o',str(DIST))
  syntax(['integrations/collaboration/wwwroot'])
 else:
  syntax(['server/wwwroot'])
  run('node','--test','tests/queue-estimate.test.mjs','tests/academic-rollcall-ui.test.cjs','tests/agent-contract.test.mjs')
  run('dotnet','restore','server/Siyue.Server.csproj','--locked-mode','--source','/nuget')
  run('dotnet','publish','server/Siyue.Server.csproj','--no-restore','--self-contained','false','-c','Release','-o',str(DIST))
 shutil.copyfile(TRUSTED/'runtime.py',DIST/'__preview_runtime.py')
elif kind=='admissions':
 run('node','check.cjs');run('node','test.cjs');run('node','test-client.cjs')
 copytree(ROOT,DIST/'site');shutil.copyfile(TRUSTED/'runtime.py',DIST/'__preview_runtime.py')
elif kind=='crm':
 tests=[str(p) for p in sorted((ROOT/'backend/test').glob('*.test.cjs'))]
 run('node','--test','--test-concurrency=1',*tests)
 syntax(['backend/src']);copytree(ROOT,DIST/'site');shutil.copyfile(TRUSTED/'crm-preview.cjs',DIST/'__preview_runtime.cjs')
elif kind=='information':
 run('python3','-m','unittest','discover','-s','operations/tests','-p','test_*.py')
 run('node','operations/tests/test_workspace_js.cjs')
 copytree(ROOT,DIST/'site');shutil.copyfile(TRUSTED/'runtime.py',DIST/'__preview_runtime.py')
elif kind=='calendar':
 files=list(ROOT.glob('*.html'))
 if len(files)!=1:raise SystemExit('Expected one calendar HTML source')
 html=files[0].read_text();scripts=re.findall(r'<script\b[^>]*>(.*?)</script\s*>',html,re.S|re.I)
 if not scripts:raise SystemExit('Calendar has no application script')
 for i,code in enumerate(scripts):
  temp=pathlib.Path('/tmp')/('calendar-'+str(i)+'.js');temp.write_text(code);run('node','--check',str(temp))
 shutil.copyfile(files[0],DIST/'index.html')
elif kind=='wecom':
 run('node','--check','bot.mjs');run('node','--test');shutil.copyfile('routing.mjs',DIST/'routing.mjs');shutil.copyfile(TRUSTED/'wecom-preview.html',DIST/'index.html')
else:raise SystemExit('Unknown trusted recipe')
print('CHECKS_PASSED recipe='+kind,flush=True)
