import unittest
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from datetime import timezone
from source_preflight import inspect, session_utc, split_minor, order_origin

class PreflightTest(unittest.TestCase):
    def test_direct_enrollment_is_not_an_invented_crm_relationship(self):
        self.assertEqual('direct_enrollment', order_origin({'enrollmentId': 'e'}))
        self.assertEqual('crm_opportunity', order_origin({'customerId': 'c', 'opportunityId': 'o'}))
        self.assertEqual('incomplete_origin', order_origin({'enrollmentId': 'e', 'customerId': 'c'}))
        self.assertEqual('incomplete_origin', order_origin({}))

    def test_unassessed_source_data_prevents_ready_verdict(self):
        result = inspect({'futureCollection': [{'id': 'x'}], 'settings': {'x': True}}, [])
        self.assertEqual({'futureCollection': 1, 'settings': 1}, result['unassessedSourceSections'])
        self.assertFalse(result['safeToImport'])

    def test_snapshot_without_auth_never_claims_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            snapshot = Path(folder) / 'snapshot.json'
            snapshot.write_text(json.dumps({'schema': 'siyue.legacy-business/v1',
                                           'revision': 1, 'state': {}}))
            run = subprocess.run([sys.executable, str(Path(__file__).with_name('source_preflight.py')),
                                  '--snapshot', str(snapshot)], capture_output=True, text=True, check=True)
            result = json.loads(run.stdout)
            self.assertFalse(result['safeToImport'])
            self.assertEqual('not_checked_no_auth_snapshot', result['identityVerification'])
            self.assertEqual({}, result['sourceErrors'])

    def test_minor_units_preserved(self):
        self.assertEqual([34, 34, 33], split_minor(101, 3))
        self.assertEqual(101, sum(split_minor(101, 3)))
        self.assertEqual([0, 0], split_minor(0, 2))
        for amount, count in [(True, 2), (1.1, 2), (-1, 2), (1, 0)]:
            with self.assertRaises(ValueError):
                split_minor(amount, count)

    def test_china_times_converted_to_utc(self):
        start, end = session_utc({'date': '2026-10-16', 'time': '08:30-10:00'})
        self.assertEqual('2026-10-16T00:30:00+00:00', start.isoformat())
        self.assertEqual(5400, (end-start).total_seconds())
        self.assertEqual(timezone.utc, start.tzinfo)
        for separator in ['-', '–', '—']:
            _, midnight = session_utc({'date': '2026-10-16', 'time': '23:00' + separator + '24:00'})
            self.assertEqual('2026-10-16T16:00:00+00:00', midnight.isoformat())
        with self.assertRaises(ValueError):
            session_utc({'date': '2026-10-16', 'time': '10:30-10:00'})

    def test_session_collisions_and_missing_teachers_are_blocked(self):
        state = {'campuses': [{'id': 'cp'}], 'courses': [{'id': 'c', 'lessons': [{'id': 'l'}]}],
                 'classes': [{'id': 'cl', 'courseId': 'c', 'campusId': 'cp', 'teacherId': 'p',
                   'schedule': [{'id': 'ss', 'lessonId': 'l', 'date': '2026-10-16', 'time': '08:00-09:00'},
                                {'id': 'ss', 'lessonId': 'l', 'date': '2026-10-16', 'time': '09:00-10:00'}]}]}
        result = inspect(state, [{'id': 'p'}])
        self.assertEqual(1, result['sourceErrors']['sessions.invalid_or_duplicate_identity'])
        self.assertEqual(1, result['javaMappingGaps']['sessions.repeated_class_lesson'])
        self.assertFalse(result['safeToImport'])

    def test_unmapped_teaching_history_blocks_without_disclosing_source_values(self):
        state = {'campuses': [{'id': 'cp'}], 'courses': [{'id': 'c', 'lessons': [{'id': 'l'}]}],
                 'classes': [{'id': 'cl', 'courseId': 'c', 'campusId': 'cp', 'teacherId': 'p',
                   'teacherIds': ['p'], 'schedule': [{'id': 'ss', 'lessonId': 'l',
                   'date': '2026-10-16', 'time': '08:00-09:00',
                   'record': {'content': 'PRIVATE_FIXTURE', 'actorId': 'private-actor', 'at': '2026-10-16'},
                   'substituteReason': 'PRIVATE_FIXTURE', 'substituteTeacherId': 'private-actor'}]}]}
        result = inspect(state, [{'id': 'p'}])
        self.assertFalse(result['safeToImport'])
        self.assertEqual({'classes.teacherIds_unmapped': 1,
                          'sessions.teaching_record_content_actor_time_unmapped': 1,
                          'sessions.substituteReason_unmapped': 1,
                          'sessions.substituteTeacherId_unmapped': 1}, result['javaMappingGaps'])
        self.assertNotIn('PRIVATE_FIXTURE', json.dumps(result))
        self.assertNotIn('private-actor', json.dumps(result))
        state['classes'][0]['teacherIds'] = []
        session = state['classes'][0]['schedule'][0]
        session.update(record=None, substituteReason=' ', substituteTeacherId=None)
        empty = inspect(state, [{'id': 'p'}])
        self.assertEqual({}, empty['javaMappingGaps'])
        self.assertTrue(empty['safeToImport'])

    def test_malformed_optional_history_is_not_coerced_to_empty(self):
        state = {'campuses': [{'id': 'cp'}], 'courses': [{'id': 'c', 'lessons': [{'id': 'l'}]}],
                 'classes': [{'id': 'cl', 'courseId': 'c', 'campusId': 'cp', 'teacherId': 'p',
                   'teacherIds': {}, 'schedule': [{'id': 'ss', 'lessonId': 'l',
                   'date': '2026-10-16', 'time': '08:00-09:00',
                   'record': {}, 'substituteReason': False, 'substituteTeacherId': 0}]}]}
        result = inspect(state, [{'id': 'p'}])
        self.assertEqual(4, len(result['javaMappingGaps']))
        self.assertFalse(result['safeToImport'])

if __name__ == '__main__':
    unittest.main()
