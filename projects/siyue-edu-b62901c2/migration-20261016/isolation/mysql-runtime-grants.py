"""Produce reviewable table grants for a named candidate runtime; never connects or executes SQL."""
import argparse
import importlib.util
import json
import re
from pathlib import Path

CONTROL_TABLES = {
    'core_migration_candidate_gate', 'flyway_schema_history', 'core_migration_checkpoint',
    'core_legacy_identity_migration_checkpoint', 'core_legacy_identity_placeholder_checkpoint',
}

def statements(schema, user, tables, views):
    # Share the guard's explicit view-policy schema, not a permissive list of arbitrary view names.
    loader = importlib.util.spec_from_file_location('cutover_view_policy',
        Path(__file__).with_name('java-cutover-preflight.py'))
    guard = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(guard)
    try:
        guard.validate_views(views, tables)
    except guard.Refused as error:
        raise ValueError(str(error)) from None
    if set(tables).intersection(guard.VIEW_DEPENDENCIES):
        raise ValueError('Views must not appear in the BASE TABLE inventory')
    if not re.fullmatch(r'siyue_migration_candidate_[a-f0-9]{10,16}', schema):
        raise ValueError('Expected an explicitly named candidate schema')
    if not re.fullmatch(r'[a-zA-Z0-9_]{1,32}', user):
        raise ValueError('Invalid runtime account')
    if not tables or len(set(tables)) != len(tables):
        raise ValueError('Expected a nonempty, unique inventory of existing tables')
    if not {'core_migration_candidate_gate', 'flyway_schema_history'}.issubset(tables):
        raise ValueError('Migration control tables must be included in inventory')
    for table in tables:
        if not re.fullmatch(r'[a-zA-Z0-9_]{1,64}', table):
            raise ValueError('Invalid table identifier')
    account = f"'{user}'@'127.0.0.1'"
    result = ['-- DBA must verify no global, role or other schema grants before applying.',
              '-- Grant table access before removing the old schema-level DML grants.']
    for table in sorted(tables):
        privileges = 'SELECT' if table in CONTROL_TABLES else 'SELECT, INSERT, UPDATE, DELETE'
        if table in {'core_principal_credential_reset_operation', 'core_mutation_key_lock'}:
            privileges = 'SELECT, INSERT, UPDATE'
        result.append(f'GRANT {privileges} ON `{schema}`.`{table}` TO {account};')
    for view in sorted(row['name'] for row in views):
        # Establish a table-grant row first so ALL PRIVILEGES can remove prior view DML
        # both for new accounts and for old candidates incorrectly granted writable views.
        result.extend([f'GRANT SELECT ON `{schema}`.`{view}` TO {account};',
                       f'REVOKE ALL PRIVILEGES ON `{schema}`.`{view}` FROM {account};',
                       f'GRANT SELECT ON `{schema}`.`{view}` TO {account};'])
    result.append(f'REVOKE SELECT, INSERT, UPDATE, DELETE ON `{schema}`.* FROM {account};')
    return '\n'.join(result) + '\n'

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', required=True, type=Path)
    args = parser.parse_args()
    spec = json.loads(args.inventory.read_text())
    print(statements(spec['schema'], spec['runtimeUser'], spec['tables'], spec['views']), end='')
