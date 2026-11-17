#!/usr/bin/env python3
"""Private logical cluster backup and isolated restore acceptance for PostgreSQL 16.

Run on the controller. --accept-standby captures an immutable standby view by
briefly pausing replay, never pauses the primary, and verifies every restored
table and sequence. Ordinary scheduled backups do not pause replay or writers.
Credentials and dumps are private; stdout contains metadata only.
"""
import argparse,datetime,fcntl,hashlib,json,os,pathlib,pwd,re,secrets,shutil,subprocess,sys,time

ROOT=pathlib.Path('/opt/siyue-workstation-backups/postgres')
PRIVATE=pathlib.Path('/opt/siyue-auxiliary/database-migration-20261117/postgres-private.json')
BIN='/usr/lib/postgresql/16/bin/'
NAME=re.compile(r'^\d{8}T\d{6}Z-[0-9a-f]{6}$')

def run(args,**kw):
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=kw.pop('timeout',180),**kw)
    if result.returncode:
        # Save database diagnostics privately rather than exposing possible row data.
        (ROOT/'last-error.log').write_bytes(result.stderr)
        raise RuntimeError(pathlib.Path(args[0]).name+' failed; see private last-error.log')
    return result.stdout

def quote(value):return '"'+value.replace('"','""')+'"'
def literal(value):return "'"+value.replace("'","''")+"'"

def validate_snapshot(snapshot,manifest):
    for name,expected in manifest['sha256'].items():
        if pathlib.PurePosixPath(name).name!=name or name in ('.','..'):
            raise ValueError('Invalid backup member path')
        p=snapshot/name
        if p.is_symlink() or not p.is_file():raise ValueError('Backup member is not a regular file')
        with p.open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
        if actual!=expected:raise ValueError('Backup checksum mismatch')
    files=[entry['file'] for entry in manifest['databases']]
    if not files or len(files)!=len(set(files)):raise ValueError('Missing or duplicated database dumps')
    if any(f not in manifest['sha256'] for f in files):raise ValueError('Database dump lacks checksum')

def configuration_archive(target):
    sys.path.insert(0,'/opt/siyue-collaboration')
    import delivery_service as delivery,workstation_client as workstation
    command=workstation.command(delivery.settings()['workstation'])[:-1]
    script='''import pathlib,sys,tarfile
r=pathlib.Path('/opt/siyue-workstation/databases')
with tarfile.open(fileobj=sys.stdout.buffer,mode='w|gz') as archive:
 for name in ['supervisor.py','mode.json','enabled','known_hosts','tunnel_key','postgres/postgresql.conf','postgres/pg_hba.conf','postgres/pg_ident.conf','postgres/server.crt','postgres/server.key','postgres/replication.pgpass','postgres/conf.d','postgres/data/postgresql.auto.conf']:
  p=r/name
  if p.exists():archive.add(p,arcname=name)
 service=r.parent/'services/native-postgres'
 for name in ['supervisor.py','prepared.json','staged.json','service-definitions.json','known_hosts','tunnel_key','enabled','gitea-runtime/Containerfile','gitea-runtime/verified.json']:
  p=service/name
  if p.exists():archive.add(p,arcname='services/native-postgres/'+name)
 p=r.parent/'services-start.sh'
 if p.exists():archive.add(p,arcname='services-start.sh')
'''
    target.write_bytes(run(command+['python3 -'],input=script.encode(),timeout=60))

def sql(database,statement,env=None,restore=None):
    prefix=[] if restore is None else ['runuser','-u','postgres','--']
    connection=[] if restore is None else ['-h',str(restore/'run'),'-p','25439','-U','siyue_restore_bootstrap']
    return run(prefix+[BIN+'psql','-XqAt','-v','ON_ERROR_STOP=1',*connection,'-d',database],input=statement.encode(),env=env).decode().strip()

def fingerprint(database,env=None,restore=None):
    names=json.loads(sql(database,"SELECT coalesce(json_agg(json_build_array(n.nspname,c.relname,c.relkind) ORDER BY n.nspname,c.relname),'[]') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r','m','S');",env,restore))
    statements=["SET timezone='UTC'; SET datestyle='ISO, YMD'; SET extra_float_digits=3; SET bytea_output='hex';"]
    for schema,table,kind in names:
        name=quote(schema)+'.'+quote(table)
        source='(SELECT last_value,is_called FROM '+name+')' if kind=='S' else name
        statements.append("SELECT json_build_array("+literal(schema)+','+literal(table)+','+literal(kind)+",count(*),md5(coalesce(string_agg(h, '' ORDER BY h),''))) FROM (SELECT md5(row_to_json(t)::text) h FROM "+source+' t) q;')
    rows=sql(database,'\n'.join(statements),env,restore)
    return [json.loads(row) for row in rows.splitlines() if row]

def restore_acceptance(snapshot,manifest):
    account=pwd.getpwnam('postgres')
    probe=pathlib.Path('/var/lib/postgresql')/('workplace-restore-'+snapshot.name)
    probe.mkdir(mode=0o700);os.chown(probe,account.pw_uid,account.pw_gid)
    socket=probe/'run';socket.mkdir(mode=0o700);os.chown(socket,account.pw_uid,account.pw_gid)
    prefix=['runuser','-u','postgres','--']
    started=False
    try:
        run(prefix+[BIN+'initdb','-D',str(probe/'data'),'-U','siyue_restore_bootstrap','--locale=en_US.UTF-8','--auth-local=peer','--auth-host=reject'])
        # Only the private Unix socket exists. Map OS postgres to temporary superuser.
        ident=probe/'data/pg_ident.conf';ident.write_text('restore postgres siyue_restore_bootstrap\n');os.chown(ident,account.pw_uid,account.pw_gid)
        hba=probe/'data/pg_hba.conf';hba.write_text('local all all peer map=restore\n');os.chown(hba,account.pw_uid,account.pw_gid)
        conf=probe/'data/postgresql.conf'
        with conf.open('a') as f:f.write("\nlisten_addresses=''\nport=25439\nunix_socket_directories="+literal(str(socket))+"\nshared_buffers=64MB\nmax_connections=12\nssl=off\n")
        started=True
        run(prefix+[BIN+'pg_ctl','-D',str(probe/'data'),'-l',str(probe/'restore.log'),'-w','start'])
        sql('postgres',(snapshot/'globals.sql').read_text(),restore=probe)
        results=[]
        for entry in manifest['databases']:
            with (snapshot/entry['file']).open('rb') as dump:
                run(prefix+[BIN+'pg_restore','--exit-on-error','--clean','--if-exists','--create','-h',str(socket),'-p','25439','-U','siyue_restore_bootstrap','-d','template1'],stdin=dump,timeout=300)
            actual=fingerprint(entry['name'],restore=probe)
            if actual!=entry['fingerprint']:raise RuntimeError('Restored data fingerprint differs for '+entry['name'])
            results.append(dict(database=entry['name'],relations=len(actual),rows=sum(x[3] for x in actual)))
        report=dict(at=time.time(),verified=True,databases=results)
        (snapshot/'restore-verified.json').write_text(json.dumps(report,indent=2))
        return report
    finally:
        if started and (probe/'data/postmaster.pid').exists():
            run(prefix+[BIN+'pg_ctl','-D',str(probe/'data'),'-m','fast','-w','stop'])
        # Only this uniquely created, stopped probe is removed, never source data.
        shutil.rmtree(probe)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--accept-standby',action='store_true');parser.add_argument('--include-staged-files',action='store_true');args=parser.parse_args()
    if os.geteuid()!=0:raise PermissionError('Run as root')
    os.umask(0o077);ROOT.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (ROOT/'backup.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if shutil.disk_usage(ROOT).free<5*1024**3:raise RuntimeError('Less than 5 GiB free; preserve existing backups')
        stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+secrets.token_hex(3)
        partial=ROOT/('.partial-'+stamp);partial.mkdir(mode=0o700)
        passfile=partial/'connection.pgpass'
        password=json.loads(PRIVATE.read_text())['administrator']
        passfile.write_text('127.0.0.1:15432:*:postgres:'+password+'\n')
        env=dict(os.environ,PGHOST='127.0.0.1',PGPORT='15432',PGUSER='postgres',PGPASSFILE=str(passfile),PGSSLMODE='require',PGCONNECT_TIMEOUT='10')
        paused=False
        try:
            standby=sql('postgres','SELECT pg_is_in_recovery();',env)=='t'
            file_meta=None
            if not standby or args.include_staged_files:
                import native_pg_files_backup as files_backup
                if args.accept_standby:raise ValueError('Do not hold replay paused while transferring files')
                previous=(ROOT/'latest').resolve() if (ROOT/'latest').exists() else None
                if previous and not (previous/'resident-files-manifest.json').exists():previous=None
                file_meta=files_backup.inventory()
                identity=sql('postgres','SELECT system_identifier FROM pg_control_system();',env)
                if identity!=file_meta['prepared']['systemIdentifier']:raise ValueError('File/database cluster mismatch')
                files_backup.sync(partial,previous,file_meta)
            if args.accept_standby:
                if not standby:raise RuntimeError('Acceptance pause requires a read-only standby')
                sql('postgres','SELECT pg_wal_replay_pause();',env);paused=True
                for _ in range(15):
                    if sql('postgres','SELECT pg_get_wal_replay_pause_state();',env)=='paused':break
                    time.sleep(1)
                else:raise RuntimeError('Replay did not pause')
            manifest=dict(at=time.time(),source='workplace',standby=standby,consistentClusterSnapshot=paused,databases=[])
            names=json.loads(sql('postgres',"SELECT json_agg(datname ORDER BY datname) FROM pg_database WHERE NOT datistemplate AND datallowconn;",env))
            (partial/'globals.sql').write_bytes(run([BIN+'pg_dumpall','--globals-only'],env=env))
            for i,name in enumerate(names):
                entry=dict(name=name,file='database-'+str(i)+'.dump')
                run([BIN+'pg_dump','--format=custom','--create','--file='+str(partial/entry['file']),'--dbname='+name],env=env,timeout=300)
                run([BIN+'pg_restore','--list',str(partial/entry['file'])])
                if paused:entry['fingerprint']=fingerprint(name,env)
                manifest['databases'].append(entry)
            configuration_archive(partial/'workplace-config.tar.gz')
            if file_meta:
                files_backup.sync(partial,previous,file_meta)
                manifest['residentFiles']=files_backup.finish(partial,file_meta)
                manifest['residentFiles']['stagedRehearsal']=standby
            for p in partial.iterdir():
                if p.is_file() and p.name!='connection.pgpass':
                    manifest.setdefault('sha256',{})[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        finally:
            if paused:sql('postgres','SELECT pg_wal_replay_resume();',env)
            passfile.unlink(missing_ok=True)
        (partial/'manifest.json').write_text(json.dumps(manifest,indent=2))
        validate_snapshot(partial,manifest)
        if args.accept_standby:restore_acceptance(partial,manifest)
        (partial/'completed-at.txt').write_text(datetime.datetime.now(datetime.timezone.utc).isoformat())
        final=ROOT/stamp;partial.rename(final)
        latest=ROOT/'latest.pending';latest.unlink(missing_ok=True);latest.symlink_to(stamp);latest.replace(ROOT/'latest')
        snapshots=sorted(p for p in ROOT.iterdir() if NAME.fullmatch(p.name) and p.is_dir() and not p.is_symlink() and (p/'completed-at.txt').exists())
        # Four snapshots per day, plus the separately retained restore-accepted baseline.
        for old in snapshots[:-28]:
            if not (old/'restore-verified.json').exists():shutil.rmtree(old)
        print(json.dumps(dict(backup=str(final),databases=len(manifest['databases']),restoreVerified=args.accept_standby)))

if __name__=='__main__':main()
