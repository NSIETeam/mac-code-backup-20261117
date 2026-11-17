"""No network, tokens, or business data: validate load safety and metric aggregation."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    'load_runner', pathlib.Path(__file__).with_name('http-concurrency-load.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LoadRunnerTest(unittest.TestCase):
    def test_only_dedicated_student_grants_allowed(self):
        for writer in ('student.create', 'student.write'):
            runner.validate_load_grants({'student.read', writer})
        for grants in ({'student.read'}, {'student.create'},
                       {'student.read', 'student.create', 'account.update'},
                       {'student.read', 'student.write', 'crm.order.read'}):
            with self.assertRaises(runner.LoadError):
                runner.validate_load_grants(grants)

    def test_single_actor_workload_never_rotates_reads_writes_or_replays(self):
        for kind in ('read', 'write', 'replay'):
            self.assertEqual({runner.actor_index('single', kind, i, 2) for i in range(32)}, {0})

    def test_multi_actor_mode_rotates_but_replays_keep_one_owner(self):
        self.assertEqual({runner.actor_index('multi', 'write', i, 2) for i in range(32)}, {0, 1})
        self.assertEqual({runner.actor_index('multi', 'replay', i, 2) for i in range(32)}, {0})
        for mode in ('all', '', None, 1):
            with self.assertRaises(runner.LoadError):
                runner.actor_index(mode, 'write', 0, 2)

    def test_percentiles_use_nearest_rank_and_keep_request_count(self):
        summary = runner.latency_summary(list(range(1, 101)))
        self.assertEqual(summary, {'requests': 100, 'p50Ms': 50, 'p95Ms': 95,
                                   'p99Ms': 99, 'maxMs': 100})

    def test_single_sample_is_not_dropped(self):
        self.assertEqual(runner.latency_summary([1.23456]),
                         {'requests': 1, 'p50Ms': 1.235, 'p95Ms': 1.235,
                          'p99Ms': 1.235, 'maxMs': 1.235})


if __name__ == '__main__':
    unittest.main()
