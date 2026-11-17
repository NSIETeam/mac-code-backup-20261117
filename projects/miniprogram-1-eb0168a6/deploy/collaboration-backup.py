#!/usr/bin/env python3
"""Private on-host backups. Off-host replication must be configured separately."""
import datetime,json,os,pathlib,shutil,sqlite3,subprocess,tarfile,sys
root=pathlib.Path('/opt/siyue-collaboration');os.umask(0o077)
if (root/'workplace-primary.json').exists():
 os.execv(sys.executable,[sys.executable,str(root/'postgres_backup.py')])
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
parent=root/'backups/daily';parent.mkdir(parents=True,exist_ok=True);dest=parent/stamp;dest.mkdir()
if shutil.disk_usage(root).free<5*1024**3:raise RuntimeError('Less than 5 GB available for backup')
for database in ['siyue_collaboration','siyue_collaboration_git']:
 with (dest/(database+'.dump')).open('wb') as out:
  subprocess.run(['runuser','-u','postgres','--','pg_dump','-Fc',database],stdout=out,check=True)
if (root/'delivery.db').exists():
 with sqlite3.connect(root/'delivery.db') as source,sqlite3.connect(dest/'delivery.db') as target:source.backup(target)
# Files are immutable or atomically replaced. Bare repositories are saved as verified bundles.
with tarfile.open(dest/'configuration-and-files.tar.gz','w:gz') as archive:
 for name in ['config','keys','attachments','secrets.json','delivery.json','gitea/app.ini','sites','runtime']:
  if (root/name).exists():archive.add(root/name,arcname=name,recursive=True)
bundles=dest/'repositories';bundles.mkdir()
for repo in (root/'gitea/repositories').glob('*/*.git'):
 out=bundles/(repo.parent.name+'--'+repo.name+'.bundle')
 with out.open('wb') as stream:subprocess.run(['runuser','-u','siyue-gitea','--','git','-C',str(repo),'bundle','create','-','--all'],stdout=stream,check=True)
 subprocess.run(['git','-C',str(repo),'-c','safe.directory='+str(repo),'bundle','verify',str(out)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
artifact_copy=dest/'artifacts';artifact_copy.mkdir()
for artifact in (root/'artifacts').glob('*.tar'):os.link(artifact,artifact_copy/artifact.name)
(dest/'manifest.json').write_text(json.dumps({'createdAt':stamp,'scope':'collaboration and Gitea only','artifacts':'Immutable artifact snapshots use hard links on this host; external backups must copy file contents.','restore':'Restore databases, configuration, files and repositories together while these services are stopped. Existing teacher passwords are not included.'},indent=2))
for previous in sorted(parent.iterdir())[:-7]:
 if previous.is_dir() and previous.name!=stamp:shutil.rmtree(previous)
print('Collaboration backup completed: '+stamp)
