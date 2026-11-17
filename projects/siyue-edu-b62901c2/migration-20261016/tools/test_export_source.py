from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest


class ExportSourceTest(unittest.TestCase):
    def test_exports_only_minimal_success_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'business.sqlite'
            with closing(sqlite3.connect(source)) as db:
                db.execute('CREATE TABLE business_state(id INTEGER, revision INTEGER, data TEXT)')
                db.execute('CREATE TABLE business_audit(seq INTEGER, event TEXT)')
                db.execute('INSERT INTO business_state VALUES(1,7,?)', (json.dumps({'refunds': []}),))
                event = {'id': 'event-1', 'actorId': 'actor-1', 'actorName': 'not exported',
                         'at': '2026-10-16T00:00:00Z', 'action': 'enrollment.withdraw',
                         'outcome': 'success', 'requestId': 'not exported',
                         'details': {'changes': [{'collection': 'refunds', 'id': 'refund-1',
                                                 'before': None, 'after': {'phone': 'not exported'}}]}}
                db.execute('INSERT INTO business_audit VALUES(1,?)', (json.dumps(event),))
                event['outcome'] = 'failure'
                db.execute('INSERT INTO business_audit VALUES(2,?)', (json.dumps(event),))
                db.commit()
            run = subprocess.run([sys.executable, str(Path(__file__).with_name('export_source.py')),
                                  '--sqlite', str(source)], check=True, capture_output=True, text=True)
            result = json.loads(run.stdout)
            self.assertEqual(7, result['revision'])
            self.assertEqual(1, len(result['auditProvenance']))
            self.assertEqual([{'collection': 'refunds', 'id': 'refund-1'}],
                             result['auditProvenance'][0]['changes'])
            self.assertNotIn('not exported', run.stdout)
            with closing(sqlite3.connect(source)) as db:
                self.assertEqual(2, db.execute('SELECT COUNT(*) FROM business_audit').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
