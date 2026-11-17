#!/usr/bin/env python3
"""Prepare a hash-bound offline migration plan; never connect, import, grant or start a service."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

JAR_SHA256 = 'b43bfbd6a780dacfa81cd37c9136ea0ae573dab3843da95832ac1f43f765d470'
PHASES = [
    ('catalog', 'academic_catalog_v1'), ('campus-configuration', 'campus_configuration_v1'),
    ('system-settings', 'system_settings_v1'), ('crm', 'crm_households_v1'),
    ('service-history', 'crm_service_history_v1'), ('financial', 'academic_financial_v1'),
    ('forms-trials-coupons', 'form_trials_v1'), ('schedule-history', 'academic_schedule_history_v1'),
    ('crm-import-batches', 'crm_import_batches_v1'),
    ('crm-attribution-requests', 'crm_attribution_requests_v1'), ('crm-operations', 'crm_operations_v1'),
    ('campaign-acquisition', 'campaign_acquisition_v1'), ('ui-annotations', 'auth_ui_annotations_v1'),
    ('lead-reclaim-requests', 'crm_lead_reclaim_requests_v1'),
]
FIELDS = {'freezeId', 'sourceRevision', 'sourceWritesStopped', 'serverUuid', 'mysqlPort', 'schema',
          'checkpointId', 'jarPath', 'jarSha256', 'businessSourcePath', 'businessSourceSha256',
          'authSourcePath', 'authSourceSha256', 'combinedSourcePath', 'combinedSourceSha256',
          'retentionPath', 'migrationEnvironmentPath', 'migrationEnvironmentSha256'}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def load_json(data):
    return json.loads(data, object_pairs_hook=unique_object)


def checked_path(value):
    require(isinstance(value, str), 'PATH_TYPE')
    path = Path(value)
    require(path.is_absolute() and '..' not in path.parts, 'ABSOLUTE_CANONICAL_PATH_REQUIRED')
    for part in reversed([path, *path.parents]):
        require(not stat.S_ISLNK(part.lstat().st_mode), 'SYMLINK_REJECTED')
    return path


def read_file(value, private=True, limit=128 * 1024 * 1024):
    path = checked_path(value)
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0, 'ROOT_REGULAR_FILE_REQUIRED')
    require(0 < info.st_size <= limit and not info.st_mode & 0o022, 'FILE_SIZE_OR_WRITABILITY')
    if private:
        require(not info.st_mode & 0o077, 'PRIVATE_FILE_REQUIRED')
    return path.read_bytes()


def prepare(spec):
    require(isinstance(spec, dict) and set(spec) == FIELDS, 'SPEC_FIELDS')
    require(spec['sourceWritesStopped'] is True, 'FINAL_FREEZE_REQUIRED')
    require(re.fullmatch(r'[A-Za-z0-9._:-]{16,128}', spec['freezeId']) is not None, 'FREEZE_ID')
    require(re.fullmatch(r'[A-Za-z0-9._:-]{16,128}', spec['checkpointId']) is not None, 'CHECKPOINT_ID')
    require(type(spec['sourceRevision']) is int and spec['sourceRevision'] >= 0, 'SOURCE_REVISION')
    require(re.fullmatch(r'[a-f0-9-]{36}', spec['serverUuid']) is not None, 'INSTANCE_UUID')
    require(type(spec['mysqlPort']) is int and spec['mysqlPort'] == 50571, 'PRODUCTION_INSTANCE_PORT')
    require(re.fullmatch(r'siyue_migration_candidate_[a-f0-9]{12}', spec['schema']) is not None, 'SCHEMA')
    for field in ('jarSha256', 'businessSourceSha256', 'authSourceSha256',
                  'combinedSourceSha256', 'migrationEnvironmentSha256'):
        require(re.fullmatch(r'[a-f0-9]{64}', spec[field]) is not None, 'SHA256_FORMAT')
    require(spec['jarSha256'] == JAR_SHA256, 'UNVERIFIED_JAR')
    jar = checked_path(spec['jarPath'])
    jar_bytes = read_file(str(jar), private=False, limit=256 * 1024 * 1024)
    require(digest(jar_bytes) == JAR_SHA256, 'JAR_HASH')
    with zipfile.ZipFile(jar) as archive:
        migrations = [name for name in archive.namelist()
                      if name.startswith('BOOT-INF/classes/db/migration/V') and name.endswith('.sql')]
        require(len(migrations) == 90 and any('/V91__' in name for name in migrations), 'V91_MIGRATIONS')
        require('BOOT-INF/classes/cn/siyue/core/LegacyMigrationCandidateCli.class' in archive.namelist(),
                'CLI_MISSING')
    sources = {}
    for kind in ('business', 'auth', 'combined'):
        data = read_file(spec[kind + 'SourcePath'])
        require(digest(data) == spec[kind + 'SourceSha256'], 'SOURCE_HASH')
        if kind != 'auth':
            sources[kind] = load_json(data)
    require(sources['business'].get('revision') == spec['sourceRevision']
            and sources['combined'].get('revision') == spec['sourceRevision'], 'REVISION_MISMATCH')
    feedback = sources['combined'].get('state', {}).get('authFeedback', {})
    require(feedback.get('authSourceSha256') == spec['authSourceSha256']
            and feedback.get('businessSourceSha256') == spec['businessSourceSha256'], 'FEEDBACK_PROVENANCE')
    # The merge may add authFeedback only; reject arbitrary changes to the approved business source.
    combined = sources['combined']
    combined['state'].pop('authFeedback')
    require(combined == sources['business'], 'COMBINED_BUSINESS_DRIFT')
    marker = load_json(read_file(spec['retentionPath']))
    for key in ('schema', 'serverUuid', 'mysqlPort', 'freezeId', 'sourceRevision',
                'businessSourceSha256', 'authSourceSha256', 'jarSha256'):
        require(marker.get(key) == spec[key], 'RETENTION_MISMATCH')
    require(marker.get('state') == 'empty_schema_staged_service_installed_not_started'
            and marker.get('serviceStarted') is False and marker.get('routeChanged') is False,
            'TARGET_NOT_STAGED_EMPTY')
    env_bytes = read_file(spec['migrationEnvironmentPath'], limit=1024 * 1024)
    require(digest(env_bytes) == spec['migrationEnvironmentSha256'], 'MIGRATION_ENV_HASH')
    env = load_json(env_bytes)
    allowed = {'SIYUE_MYSQL_URL', 'SIYUE_MYSQL_USER', 'SIYUE_MYSQL_PASSWORD',
               'SIYUE_PII_KEYS_JSON', 'SIYUE_PII_ACTIVE_KEY_ID', 'SIYUE_LEGACY_REWARD_KEY_HEX'}
    require(isinstance(env, dict) and set(env) == allowed
            and all(isinstance(value, str) and value for value in env.values()), 'MIGRATION_ENV_FIELDS')
    require(env['SIYUE_MYSQL_URL'] == 'jdbc:mysql://127.0.0.1:50571/' + spec['schema']
            + '?sslMode=REQUIRED&serverTimezone=UTC', 'MIGRATION_JDBC_TARGET')
    require(env['SIYUE_MYSQL_USER'] == 'sy_mig_' + spec['schema'].rsplit('_', 1)[1], 'MIGRATION_IDENTITY')
    argv = ['/usr/bin/java', '-Xms64m', '-Xmx512m', '-XX:ActiveProcessorCount=1', '-jar', str(jar),
            'legacy-migration-candidate', '--snapshot', spec['combinedSourcePath'],
            '--target-schema', spec['schema'], '--source-sha256', spec['combinedSourceSha256'],
            '--checkpoint', spec['checkpointId']]
    return {'kind': 'siyue.production-migration-plan/v1', 'state': 'prepared_not_executed',
            'freezeId': spec['freezeId'], 'schema': spec['schema'], 'serverUuid': spec['serverUuid'],
            'mysqlPort': 50571, 'jarSha256': JAR_SHA256,
            'sourceSha256': spec['combinedSourceSha256'], 'checkpointId': spec['checkpointId'],
            'migrationEnvironmentPath': spec['migrationEnvironmentPath'],
            'migrationEnvironmentSha256': spec['migrationEnvironmentSha256'],
            'commands': {'preflight': argv, 'execute': argv + ['--execute'], 'replay': argv + ['--execute']},
            'phases': [{'phase': name, 'checkpoint': checkpoint} for name, checkpoint in PHASES],
            'lockName': 'siyue:mig:' + digest(spec['schema'].encode())[:40],
            'databaseChecked': False, 'executionAuthorized': False,
            'requires': ['approved final freeze remains stopped', 'live instance UUID/port/schema check',
                         'Flyway 90 migrations through V91 validated', 'authorized gate and inactive placeholders',
                         'limited persistent view definer and reviewed complete inventory',
                         'separate root approval before running any listed command']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spec', required=True)
    parser.add_argument('--spec-sha256', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    require(os.geteuid() == 0, 'SERVER_ROOT_REQUIRED')
    source = read_file(args.spec, limit=1024 * 1024)
    require(digest(source) == args.spec_sha256, 'SPEC_APPROVAL_HASH')
    plan = prepare(load_json(source))
    out = Path(args.out)
    require(out.is_absolute() and '..' not in out.parts and not out.exists() and not out.is_symlink(),
            'NEW_OUTPUT_REQUIRED')
    parent = checked_path(str(out.parent))
    require(parent.is_dir() and parent.stat().st_uid == 0 and not parent.stat().st_mode & 0o077,
            'PRIVATE_OUTPUT_PARENT')
    data = (json.dumps(plan, ensure_ascii=False, indent=2) + '\n').encode()
    descriptor = os.open(out, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    print(json.dumps({'status': 'prepared_not_executed', 'planSha256': digest(data), 'phases': 14,
                      'databaseConnected': False, 'importStarted': False, 'servicesChanged': False}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile):
        print('PREPARE_REFUSED: private inputs or approved artifact do not match', file=sys.stderr)
        sys.exit(2)
