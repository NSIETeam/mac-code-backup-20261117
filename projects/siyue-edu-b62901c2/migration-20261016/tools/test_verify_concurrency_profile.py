import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('profile_check', Path(__file__).with_name('verify-concurrency-profile.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ConcurrencyProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar, self.env, self.profile = [self.root / name for name in ('synthetic.jar', 'runtime.env', 'profile.json')]
        self.jar.write_bytes(b'synthetic-artifact')
        self.limits = dict(zip(sorted(module.KEYS), [100, 8192, 64, 4, 10]))
        self.config = {'schema': 'siyue.concurrency-profile/v1', 'limits': self.limits,
                       'artifactSha256': hashlib.sha256(self.jar.read_bytes()).hexdigest()}
        self.profile.write_text(json.dumps(self.config))
        self.env.write_text('\n'.join(f'{k}={v}' for k, v in self.limits.items()))

    def verify(self):
        return module.verify(self.jar, self.env, self.profile)

    def test_exact_match_never_claims_capacity(self):
        self.env.write_text(self.env.read_text() + '\nSIYUE_MYSQL_PASSWORD=synthetic-secret\n')
        result = self.verify()
        self.assertTrue(result['configurationMatches'])
        self.assertFalse(result['capacityVerified'])
        self.assertNotIn('synthetic-secret', json.dumps(result))

    def test_different_artifact_rejected(self):
        self.jar.write_bytes(b'different-artifact')
        with self.assertRaisesRegex(ValueError, 'Artifact differs'):
            self.verify()

    def test_defaults_and_duplicates_rejected(self):
        original = self.env.read_text()
        self.env.write_text(original.split('\n', 1)[1])
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            self.verify()
        self.env.write_text(original + '\nSIYUE_JDBC_MAX_POOL_SIZE=10\n')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.verify()

    def test_runtime_drift_and_shell_expressions_rejected(self):
        for value in ('20', '$(echo 10)'):
            self.env.write_text('\n'.join(f'{k}={value if k == "SIYUE_JDBC_MAX_POOL_SIZE" else v}' for k, v in self.limits.items()))
            with self.assertRaises(ValueError):
                self.verify()

    def test_invalid_profile_limits_rejected(self):
        self.config['limits']['SIYUE_HTTP_MIN_SPARE_THREADS'] = 65
        self.profile.write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, 'Spare threads'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
