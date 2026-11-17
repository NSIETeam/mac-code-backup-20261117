"""Hold the Java migration lock while checking a reviewed cutover boundary; never activate routes.

Only SELECT statements and connection-scoped advisory locks are sent to MySQL. Credentials,
expected checkpoint hashes, and table inventory come from private, operator-reviewed files.
A READY message is valid only while this process retains the same live MySQL connection.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import time

PHASES = (
    'academic_catalog_v1', 'campus_configuration_v1', 'system_settings_v1',
    'crm_households_v1', 'crm_service_history_v1', 'academic_financial_v1',
    'form_trials_v1', 'academic_schedule_history_v1', 'crm_import_batches_v1',
    'crm_attribution_requests_v1', 'crm_operations_v1', 'campaign_acquisition_v1',
    'auth_ui_annotations_v1', 'crm_lead_reclaim_requests_v1',
)
CONTROL_TABLES = frozenset({
    'core_migration_candidate_gate', 'flyway_schema_history', 'core_migration_checkpoint',
    'core_legacy_identity_migration_checkpoint', 'core_legacy_identity_placeholder_checkpoint',
})
VIEW_DEPENDENCIES = {
    'core_effective_enrollment': frozenset({'core_enrollment', 'core_enrollment_placement'}),
    'core_effective_enrollment_lesson': frozenset({'core_enrollment_lesson',
        'core_enrollment_placement', 'core_enrollment_lesson_delivery'}),
    'core_session_enrollment': frozenset({'core_effective_enrollment', 'core_session',
        'core_effective_enrollment_lesson', 'core_consumption', 'core_attendance',
        'core_lesson', 'core_enrollment_lesson'}),
}
DML = frozenset({'SELECT', 'INSERT', 'UPDATE', 'DELETE'})
IDENTIFIER = re.compile(r'[a-zA-Z0-9_]{1,64}\Z')
HASH = re.compile(r'[a-f0-9]{64}\Z')


class Refused(Exception):
    """Carry a fixed, non-sensitive refusal code, never a database or input value."""


def require(condition, code):
    if not condition:
        raise Refused(code)


def private_json(path):
    """Read an owner-only regular file without following any symlink path component."""
    path = Path(path).absolute()
    for part in [*reversed(path.parents), path]:
        require(not stat.S_ISLNK(part.lstat().st_mode), 'PRIVATE_FILE_SYMLINK')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
                and stat.S_IMODE(info.st_mode) == 0o600, 'PRIVATE_FILE_PERMISSIONS')
        require(info.st_size <= 1024 * 1024, 'PRIVATE_FILE_TOO_LARGE')
        with os.fdopen(fd, 'r', encoding='utf-8', closefd=False) as stream:
            return json.load(stream, object_pairs_hook=unique_object)
    finally:
        os.close(fd)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def number(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


def validate_spec(spec):
    """Require explicit immutable expectations; do not derive approval from the live target."""
    fields = {'serverUuid', 'port', 'schema', 'sourceSha256', 'checkpointId', 'sourceRevision',
              'migrationUser', 'runtimeUser', 'accountHost', 'checkpoints', 'tables', 'views'}
    require(type(spec) is dict and set(spec) == fields, 'MANIFEST_FIELDS')
    require(bool(re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',
                              str(spec['serverUuid']))), 'MANIFEST_INSTANCE')
    require(number(spec['port'], 1, 65535), 'MANIFEST_PORT')
    require(bool(re.fullmatch(r'siyue_[a-z0-9_]{1,57}', str(spec['schema']))), 'MANIFEST_SCHEMA')
    require(bool(HASH.fullmatch(str(spec['sourceSha256']))), 'MANIFEST_SOURCE_HASH')
    require(bool(re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', str(spec['checkpointId']))), 'MANIFEST_CHECKPOINT_ID')
    require(number(spec['sourceRevision'], 0, 2**63 - 1), 'MANIFEST_REVISION')
    for name in ('migrationUser', 'runtimeUser'):
        require(bool(re.fullmatch(r'[A-Za-z0-9_]{1,32}', str(spec[name]))), 'MANIFEST_ACCOUNT')
    require(spec['migrationUser'] != spec['runtimeUser'] and spec['accountHost'] == '127.0.0.1',
            'MANIFEST_ACCOUNT_BOUNDARY')
    rows = spec['checkpoints']
    require(type(rows) is list and len(rows) == len(PHASES), 'MANIFEST_PHASE_COUNT')
    for phase, row in zip(PHASES, rows):
        require(type(row) is dict and set(row) == {'phase', 'revision', 'hash', 'rows'}
                and row['phase'] == phase and row['revision'] == spec['sourceRevision']
                and type(row['revision']) is int and bool(HASH.fullmatch(str(row['hash'])))
                and number(row['rows'], 0, 2**31 - 1), 'MANIFEST_PHASE')
    tables = spec['tables']
    require(type(tables) is list and 5 <= len(tables) <= 1000
            and all(type(t) is str and IDENTIFIER.fullmatch(t) for t in tables)
            and len(tables) == len(set(tables)) and CONTROL_TABLES.issubset(tables)
            and not set(tables).intersection(VIEW_DEPENDENCIES), 'MANIFEST_TABLES')
    validate_views(spec['views'], tables)
    return spec


def validate_views(views, tables):
    """Pin V40/V54's three reviewed views; the manifest hash must come from independent approval."""
    fields = {'name', 'definitionSha256', 'definer', 'securityType', 'checkOption',
              'isUpdatable', 'characterSetClient', 'collationConnection'}
    require(type(views) is list and len(views) == len(VIEW_DEPENDENCIES), 'MANIFEST_VIEWS')
    require(all(type(row) is dict and set(row) == fields for row in views), 'MANIFEST_VIEW_FIELDS')
    require({row['name'] for row in views} == set(VIEW_DEPENDENCIES), 'MANIFEST_VIEW_NAMES')
    for row in views:
        require(bool(HASH.fullmatch(str(row['definitionSha256'])))
                and bool(re.fullmatch(r'[A-Za-z0-9_.-]{1,32}@(localhost|127\.0\.0\.1)', str(row['definer'])))
                and row['securityType'] == 'DEFINER' and row['checkOption'] == 'NONE'
                and row['isUpdatable'] == 'NO'
                and type(row['characterSetClient']) is str and IDENTIFIER.fullmatch(row['characterSetClient'])
                and type(row['collationConnection']) is str and IDENTIFIER.fullmatch(row['collationConnection']),
                'MANIFEST_VIEW_POLICY')
        require(VIEW_DEPENDENCIES[row['name']].issubset(set(tables) | set(VIEW_DEPENDENCIES)),
                'MANIFEST_VIEW_DEPENDENCIES')


def literal(value):
    """Encode SQL data without depending on connection backslash or quote modes."""
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


def lock_name(schema):
    return 'siyue:mig:' + hashlib.sha256(schema.encode()).hexdigest()[:40]


class MysqlSession:
    """One non-reconnecting mysql client process; JSON frames separate bounded SELECT results."""
    def __init__(self, executable, credentials, spec):
        require(type(credentials) is dict and set(credentials).issubset({'user', 'password', 'sslCa'})
                and {'user', 'password'}.issubset(credentials), 'CREDENTIAL_FIELDS')
        require(bool(re.fullmatch(r'[A-Za-z0-9_]{1,32}', str(credentials['user'])))
                and credentials['user'] not in (spec['migrationUser'], spec['runtimeUser']), 'CHECKER_ACCOUNT')
        password = credentials['password']
        require(type(password) is str and 0 < len(password) <= 4096 and '\0' not in password, 'CREDENTIAL_FORMAT')
        self.private = tempfile.TemporaryDirectory(prefix='siyue-cutover-check-')
        config = Path(self.private.name) / 'checker.cnf'
        escaped = password.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
        with open(config, 'x', encoding='utf-8') as stream:
            os.chmod(config, 0o600)
            stream.write('[client]\nuser=' + credentials['user'] + '\npassword="' + escaped + '"\n')
        args = [str(executable), '--defaults-file=' + str(config), '--protocol=TCP',
                '--host=127.0.0.1', '--port=' + str(spec['port']), '--database=' + spec['schema'],
                '--batch', '--raw', '--silent', '--skip-column-names', '--unbuffered', '--skip-reconnect',
                '--connect-timeout=5', '--local-infile=0', '--default-character-set=utf8mb4']
        if credentials.get('sslCa'):
            ca = Path(credentials['sslCa'])
            require(ca.is_absolute() and ca.is_file(), 'TLS_CA_PATH')
            args += ['--ssl-mode=VERIFY_IDENTITY', '--ssl-ca=' + str(ca)]
        else:
            args += ['--ssl-mode=REQUIRED']
        env = {k: v for k, v in os.environ.items() if not k.startswith(('MYSQL', 'LIBMYSQL'))}
        # MySQL 8.0 has no --no-login-paths option; isolate its login-file lookup instead.
        env['MYSQL_TEST_LOGIN_FILE'] = str(Path(self.private.name) / 'absent-login.cnf')
        try:
            self.process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.DEVNULL, env=env)
        except Exception:
            self.private.cleanup()
            raise Refused('MYSQL_CLIENT_START_FAILED') from None
        self.buffer = b''
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)

    def query(self, label, sql):
        """Return JSON rows or close the connection on error; never reconnect and lose lock ownership."""
        frame = os.urandom(16).hex()
        require(sql.lstrip().upper().startswith('SELECT '), 'NON_READ_QUERY_REFUSED')
        # Templates contain only fixed double-quoted SQL literals; caller data is hex encoded.
        # Single quotes keep these templates valid when the instance enables ANSI_QUOTES.
        sql = sql.replace(chr(34), chr(39))
        command = sql.rstrip(';') + ";\nSELECT JSON_OBJECT('_frame'," + literal(frame) + ');\n'
        try:
            self.process.stdin.write(command.encode())
            self.process.stdin.flush()
            rows = []
            deadline = time.monotonic() + 15
            received = 0
            while True:
                while b'\n' in self.buffer:
                    line, self.buffer = self.buffer.split(b'\n', 1)
                    row = json.loads(line)
                    if row == {'_frame': frame}:
                        return rows
                    require(type(row) is dict and len(rows) < 5000, 'MYSQL_RESULT_SHAPE')
                    rows.append(row)
                remaining = deadline - time.monotonic()
                require(remaining > 0 and self.selector.select(remaining), 'MYSQL_QUERY_TIMEOUT')
                chunk = os.read(self.process.stdout.fileno(), 65536)
                require(bool(chunk), 'MYSQL_CONNECTION_LOST')
                received += len(chunk)
                require(received <= 2 * 1024 * 1024, 'MYSQL_RESULT_TOO_LARGE')
                self.buffer += chunk
        except Refused:
            self.close()
            raise
        except Exception:
            self.close()
            raise Refused('MYSQL_QUERY_FAILED') from None

    def close(self):
        """Release connection-owned locks by terminating only this client's private process."""
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        self.selector.close()
        for stream in (self.process.stdin, self.process.stdout):
            if stream and not stream.closed:
                try:
                    stream.close()
                except OSError:
                    pass
        self.private.cleanup()


class Gate:
    """Check the exact expected instance, migration evidence and both database account boundaries."""
    def __init__(self, connection, spec):
        self.db, self.spec = connection, validate_spec(spec)
        self.lock = lock_name(spec['schema'])
        self.connection_id = None
        self.held = False
        self.ready = False

    def one(self, label, sql):
        rows = self.db.query(label, sql)
        require(len(rows) == 1, 'QUERY_CARDINALITY')
        return rows[0]

    def acquire(self):
        self.instance(first=True)
        row = self.one('acquire', 'SELECT JSON_OBJECT("ok",GET_LOCK(' + literal(self.lock) + ',0))')
        require(row.get('ok') == 1, 'MIGRATION_LOCK_BUSY')
        self.held = True

    def instance(self, first=False):
        row = self.one('instance', 'SELECT JSON_OBJECT("uuid",@@server_uuid,"port",@@port,"schema",DATABASE(),'
                       '"connection",CONNECTION_ID(),"autocommit",@@autocommit,"performanceSchema",@@performance_schema,"mandatoryRoles",@@mandatory_roles)')
        require(row.get('uuid') == self.spec['serverUuid'] and row.get('port') == self.spec['port']
                and row.get('schema') == self.spec['schema'], 'INSTANCE_MISMATCH')
        require(row.get('performanceSchema') == 1 and row.get('mandatoryRoles') == ''
                and row.get('autocommit') == 1, 'INSTANCE_MONITORING_OR_ROLES')
        if first:
            self.connection_id = row['connection']
        else:
            require(row.get('connection') == self.connection_id, 'CONNECTION_CHANGED')
        monitor = self.one('monitor', 'SELECT JSON_OBJECT("count",COUNT(User)) FROM mysql.user '
                           'WHERE CONCAT(User,"@",Host)=CURRENT_USER() AND Process_priv="Y"')
        require(monitor.get('count') == 1, 'CHECKER_PROCESS_PRIVILEGE_REQUIRED')

    def assert_lock(self):
        require(self.held, 'LOCK_NOT_HELD')
        row = self.one('lock_owner', 'SELECT JSON_OBJECT("owner",IS_USED_LOCK(' + literal(self.lock) + '))')
        require(row.get('owner') == self.connection_id, 'LOCK_OWNERSHIP_LOST')

    def check(self):
        self.instance()
        self.assert_lock()
        spec = self.spec
        schema = '`' + spec['schema'] + '`'
        rows = self.db.query('gate', 'SELECT JSON_OBJECT("schema",target_schema,"hash",source_sha256,'
                             '"checkpoint",checkpoint_id,"status",status,"verified",verified_at IS NOT NULL,'
                             '"deleted",deleted) FROM ' + schema + '.core_migration_candidate_gate')
        require(rows == [{'schema': spec['schema'], 'hash': spec['sourceSha256'],
                          'checkpoint': spec['checkpointId'], 'status': 'migration_verified',
                          'verified': 1, 'deleted': 0}], 'MIGRATION_GATE_MISMATCH')
        checkpoints = self.db.query('checkpoints', 'SELECT JSON_OBJECT("phase",phase,"revision",source_revision,'
                                   '"hash",source_hash,"rows",imported_rows,"deleted",deleted) FROM ' + schema
                                   + '.core_migration_checkpoint ORDER BY id')
        require(checkpoints == [dict(row, deleted=0) for row in spec['checkpoints']], 'CHECKPOINT_MISMATCH')
        tables = self.db.query('tables', 'SELECT JSON_OBJECT("table",TABLE_NAME,"type",TABLE_TYPE) FROM '
                              'information_schema.TABLES WHERE TABLE_SCHEMA=' + literal(spec['schema']))
        expected_objects = {(table, 'BASE TABLE') for table in spec['tables']}
        expected_objects.update((row['name'], 'VIEW') for row in spec['views'])
        require(len(tables) == len(expected_objects)
                and {(row.get('table'), row.get('type')) for row in tables} == expected_objects,
                'TABLE_INVENTORY_MISMATCH')
        self.views()
        self.account(spec['migrationUser'], migration=True)
        self.account(spec['runtimeUser'], migration=False)
        self.assert_lock()
        self.ready = True
        return {'status': 'ready_lock_held', 'checkpoints': len(checkpoints), 'tables': len(spec['tables']), 'views': len(spec['views']),
                'migrationAccountSealed': True, 'runtimeTableGrantsOnly': True, 'routeActivated': False}

    def views(self):
        """Verify exact definitions, non-updatability, local dependencies and no stored routines.

        The frozen migrations use DEFINER. Its exact reviewed local account must exist;
        no runtime permission is inferred from that account. Runtime receives SELECT only.
        Built-in function expressions are covered by the independently reviewed definition hash.
        """
        schema = literal(self.spec['schema'])
        rows = self.db.query('views', 'SELECT JSON_OBJECT("name",TABLE_NAME,'
            '"definitionSha256",SHA2(VIEW_DEFINITION,256),"definer",DEFINER,"securityType",SECURITY_TYPE,'
            '"checkOption",CHECK_OPTION,"isUpdatable",IS_UPDATABLE,"characterSetClient",CHARACTER_SET_CLIENT,'
            '"collationConnection",COLLATION_CONNECTION) FROM information_schema.VIEWS WHERE TABLE_SCHEMA=' + schema)
        require(len(rows) == len(self.spec['views'])
                and sorted(rows, key=lambda row: row.get('name', ''))
                == sorted(self.spec['views'], key=lambda row: row['name']), 'VIEW_DEFINITION_OR_POLICY_MISMATCH')
        references = self.db.query('view_dependencies', 'SELECT JSON_OBJECT("view",VIEW_NAME,'
            '"schema",TABLE_SCHEMA,"table",TABLE_NAME) FROM information_schema.VIEW_TABLE_USAGE '
            'WHERE VIEW_SCHEMA=' + schema)
        expected = {(view, self.spec['schema'], table)
                    for view, tables in VIEW_DEPENDENCIES.items() for table in tables}
        require(len(references) == len(expected)
                and {(row.get('view'), row.get('schema'), row.get('table')) for row in references} == expected,
                'VIEW_DEPENDENCY_MISMATCH')
        routines = self.one('view_routines', 'SELECT JSON_OBJECT("count",COUNT(*)) '
            'FROM information_schema.VIEW_ROUTINE_USAGE WHERE TABLE_SCHEMA=' + schema)
        require(routines.get('count') == 0, 'VIEW_ROUTINE_REFUSED')
        for definer in sorted({row['definer'] for row in self.spec['views']}):
            user, host = definer.split('@')
            existing = self.one('view_definer_' + definer, 'SELECT JSON_OBJECT("count",COUNT(User)) '
                'FROM mysql.user WHERE User=' + literal(user) + ' AND Host=' + literal(host))
            require(existing.get('count') == 1, 'VIEW_DEFINER_MISSING')

    def account(self, user, migration):
        quoted = literal(user)
        host = self.spec['accountHost']
        rows = self.db.query('account_' + user, 'SELECT JSON_OBJECT("user",User,"host",Host,"locked",account_locked) '
                            'FROM mysql.user WHERE User=' + quoted)
        require(rows == [{'user': user, 'host': host, 'locked': 'Y' if migration else 'N'}], 'ACCOUNT_STATE_MISMATCH')
        grantee = literal("'" + user + "'@'" + host + "'")
        globals_ = self.db.query('globals_' + user, 'SELECT JSON_OBJECT("privilege",PRIVILEGE_TYPE,"grantable",IS_GRANTABLE) '
                                'FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=' + grantee)
        require(all(row == {'privilege': 'USAGE', 'grantable': 'NO'} for row in globals_), 'GLOBAL_GRANTS_PRESENT')
        for table, where in (
            ('global_grants', 'USER=' + quoted), ('db', 'User=' + quoted),
            ('columns_priv', 'User=' + quoted), ('procs_priv', 'User=' + quoted),
            ('role_edges', 'FROM_USER=' + quoted + ' OR TO_USER=' + quoted),
            ('default_roles', 'USER=' + quoted + ' OR DEFAULT_ROLE_USER=' + quoted),
            ('proxies_priv', 'User=' + quoted + ' OR Proxied_user=' + quoted),
        ):
            row = self.one(table + '_' + user, 'SELECT JSON_OBJECT("count",COUNT(*)) FROM mysql.' + table + ' WHERE ' + where)
            require(row.get('count') == 0, 'FORBIDDEN_ACCOUNT_GRANT_' + table.upper())
        grants = self.db.query('table_grants_' + user, 'SELECT JSON_OBJECT("schema",Db,"table",Table_name,'
                              '"privileges",Table_priv,"columns",Column_priv) FROM mysql.tables_priv WHERE User=' + quoted)
        if migration:
            require(not grants, 'MIGRATION_TABLE_GRANTS_PRESENT')
            sessions = self.one('migration_sessions', 'SELECT JSON_OBJECT("count",COUNT(THREAD_ID)) '
                                'FROM performance_schema.threads WHERE PROCESSLIST_USER=' + quoted)
            require(sessions.get('count') == 0, 'MIGRATION_SESSION_PRESENT')
            transactions = self.one('migration_transactions', 'SELECT JSON_OBJECT("count",COUNT(t.trx_id)) '
                                    'FROM information_schema.innodb_trx t LEFT JOIN performance_schema.threads p '
                                    'ON p.PROCESSLIST_ID=t.trx_mysql_thread_id WHERE p.PROCESSLIST_USER=' + quoted
                                    + ' OR p.THREAD_ID IS NULL')
            require(transactions.get('count') == 0, 'MIGRATION_OR_UNRESOLVED_TRANSACTION_PRESENT')
        else:
            if not self.ready:
                sessions = self.one('runtime_sessions', 'SELECT JSON_OBJECT("count",COUNT(THREAD_ID)) '
                                    'FROM performance_schema.threads WHERE PROCESSLIST_USER=' + quoted)
                require(sessions.get('count') == 0, 'OLD_RUNTIME_SESSION_PRESENT')
            objects = set(self.spec['tables']) | {row['name'] for row in self.spec['views']}
            require(len(grants) == len(objects), 'RUNTIME_TABLE_GRANT_COUNT')
            seen = set()
            for row in grants:
                table = row.get('table')
                require(row.get('schema') == self.spec['schema'] and table in objects
                        and table not in seen and row.get('columns') == '', 'RUNTIME_TABLE_GRANT_BOUNDARY')
                seen.add(table)
                expected = {'SELECT'} if table in CONTROL_TABLES or table in VIEW_DEPENDENCIES else DML
                if table in {'core_principal_credential_reset_operation', 'core_mutation_key_lock'}:
                    expected = {'SELECT', 'INSERT', 'UPDATE'}
                actual = str(row.get('privileges', '')).upper().split(',')
                require(len(actual) == len(set(actual)) and set(actual) == expected, 'RUNTIME_TABLE_PRIVILEGES')

    def release(self):
        if self.held:
            self.assert_lock()
            row = self.one('release', 'SELECT JSON_OBJECT("ok",RELEASE_LOCK(' + literal(self.lock) + '))')
            self.held = False
            require(row.get('ok') == 1, 'LOCK_RELEASE_FAILED')


def emit(value):
    print(json.dumps(value, separators=(',', ':')), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--mysql', required=True, type=Path)
    parser.add_argument('--hold-seconds', type=int, default=600)
    args = parser.parse_args(argv)
    connection = gate = None
    try:
        require(10 <= args.hold_seconds <= 3600, 'HOLD_BOUND_INVALID')
        require(args.mysql.is_absolute() and args.mysql.is_file(), 'MYSQL_CLIENT_PATH')
        spec = validate_spec(private_json(args.manifest))
        connection = MysqlSession(args.mysql, private_json(args.credentials), spec)
        gate = Gate(connection, spec)
        gate.acquire()
        result = gate.check()
        deadline = time.monotonic() + args.hold_seconds
        emit(result)
        while time.monotonic() < deadline:
            # Recheck all boundaries on the same connection before accepting an explicit release.
            import select
            readable, _, _ = select.select([sys.stdin], [], [], min(2, max(0, deadline - time.monotonic())))
            if readable:
                command = sys.stdin.readline()
                if command == 'release\n':
                    gate.check()
                    gate.release()
                    emit({'status': 'lock_released', 'routeActivated': False})
                    return 0
                if command == 'check\n':
                    emit(gate.check())
                    continue
                require(command == '', 'UNKNOWN_OPERATOR_COMMAND')
                time.sleep(min(2, max(0, deadline - time.monotonic())))
            gate.check()
        raise Refused('LOCK_HOLD_EXPIRED')
    except Refused as error:
        emit({'status': 'refused', 'code': str(error), 'routeActivated': False})
        return 2
    except (KeyboardInterrupt, SystemExit):
        emit({'status': 'aborted', 'routeActivated': False})
        return 130
    except Exception:
        emit({'status': 'refused', 'code': 'CHECK_FAILED', 'routeActivated': False})
        return 2
    finally:
        if gate and gate.held:
            try:
                gate.release()
            except Exception:
                pass
        if connection:
            connection.close()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    sys.exit(main())
