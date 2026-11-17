import importlib.util
from pathlib import Path
import unittest
import contextlib
import io
import json
import subprocess
import sys
spec=importlib.util.spec_from_file_location('grants',Path(__file__).with_name('mysql-runtime-grants.py'))
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
loader=importlib.util.spec_from_file_location('guard_tests',Path(__file__).with_name('test_java_cutover_preflight.py'))
f=importlib.util.module_from_spec(loader);loader.loader.exec_module(f)
def generate(tables=None):
    spec=f.fixture()
    return g.statements(spec['schema'],'runtime_1',spec['tables'] if tables is None else tables,spec['views'])
class RuntimeGrantTests(unittest.TestCase):
    def test_control_readonly_business_preserved(self):
        text=generate()
        for table in g.CONTROL_TABLES:
            self.assertIn(f'GRANT SELECT ON `siyue_migration_candidate_1234567890`.`{table}`',text)
        self.assertIn('GRANT SELECT, INSERT, UPDATE, DELETE ON `siyue_migration_candidate_1234567890`.`core_student`',text)
        self.assertTrue(text.splitlines()[-1].startswith('REVOKE '))
    def test_reset_operations_cannot_be_deleted(self):
        text=generate()
        self.assertIn('GRANT SELECT, INSERT, UPDATE ON `siyue_migration_candidate_1234567890`.`core_principal_credential_reset_operation`',text)
    def test_mutation_key_locks_cannot_be_deleted(self):
        text=generate()
        matching=[line for line in text.splitlines() if '`core_mutation_key_lock`' in line]
        self.assertEqual(len(matching),1)
        self.assertTrue(matching[0].startswith('GRANT SELECT, INSERT, UPDATE ON '))
        self.assertNotIn('DELETE',matching[0])
    def test_reject_unsafe_targets(self):
        for schema in ['production','mysql','siyue_migration_candidate_1234567890`;DROP']:
            with self.assertRaises(ValueError):g.statements(schema,'runtime',f.fixture()['tables'],f.fixture()['views'])
        for tables in [[],['core_student'],list(g.CONTROL_TABLES)+['bad`table'],list(g.CONTROL_TABLES)*2]:
            with self.assertRaises(ValueError):generate(tables)
    def test_views_are_select_only_and_previous_view_privileges_are_revoked(self):
        text=generate()
        for view in f.g.VIEW_DEPENDENCIES:
            matching=[line for line in text.splitlines() if f'`{view}`' in line]
            self.assertEqual(len(matching),3)
            self.assertTrue(matching[0].startswith('GRANT SELECT ON '))
            self.assertTrue(matching[1].startswith('REVOKE ALL PRIVILEGES ON '))
            self.assertTrue(matching[2].startswith('GRANT SELECT ON '))
        with self.assertRaises(ValueError):generate(f.fixture()['tables']+list(f.g.VIEW_DEPENDENCIES))
    def test_missing_unknown_or_unreviewed_view_manifest_rejected(self):
        for views in ([],[{'name':'unknown'}],None):
            with self.assertRaises(ValueError):
                g.statements(f.fixture()['schema'],'runtime',f.fixture()['tables'],views)
    def test_historical_applier_explicitly_refuses_without_reading_inputs(self):
        loader=importlib.util.spec_from_file_location('retired',Path(__file__).with_name('apply-current-candidate-grants.py'))
        retired=importlib.util.module_from_spec(loader);loader.loader.exec_module(retired)
        output=io.StringIO()
        with contextlib.redirect_stdout(output):self.assertEqual(retired.main(),2)
        result=json.loads(output.getvalue())
        self.assertEqual(result['code'],'HISTORICAL_GRANT_APPLIER_RETIRED')
        self.assertFalse(result['databaseChanged'])
        self.assertFalse(result['routeActivated'])
    def test_historical_applier_cli_returns_nonzero_with_migration_guidance(self):
        result=subprocess.run([sys.executable,str(Path(__file__).with_name('apply-current-candidate-grants.py'))],
                              capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,2)
        self.assertIn('mysql-runtime-grants.py --inventory',json.loads(result.stdout)['next'])
        self.assertEqual(result.stderr,'')
if __name__=='__main__' :unittest.main()
