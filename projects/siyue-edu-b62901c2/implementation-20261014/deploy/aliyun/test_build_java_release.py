"""Isolated packaging validation; no database, network or deployment."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("java_package", Path(__file__).with_name("build-java-release.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "source"
        self.migrations = self.source / "src/main/resources/db/migration"
        self.migrations.mkdir(parents=True)
        (self.migrations / "V1__base.sql").write_text("CREATE TABLE example(id BIGINT);\n")
        self.jar = self.root / "candidate.jar"
        self.write_jar()

    def tearDown(self):
        self.temporary.cleanup()

    def write_jar(self, executable=True):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: cn.siyue.core.SiyueApplication\n" if executable else "Manifest-Version: 1.0\n")
            archive.writestr("BOOT-INF/classes/db/migration/V1__base.sql", (self.migrations / "V1__base.sql").read_bytes())
        self.sha = hashlib.sha256(self.jar.read_bytes()).hexdigest()

    def build(self, **changes):
        options = dict(jar=self.jar, source=self.source, output=self.root,
                       release_id="candidate-01", expected_sha256=self.sha)
        options.update(changes)
        return module.build(**options)

    def test_package_has_only_artifact_identity_and_no_activation(self):
        result = self.build()
        target = Path(result["releaseDirectory"])
        self.assertEqual(set(p.name for p in target.iterdir()), {"siyue-core.jar", "release-manifest.json", "SHA256SUMS"})
        self.assertFalse(result["activationPerformed"])
        self.assertEqual(json.loads((target / "release-manifest.json").read_text())["jarSha256"], self.sha)
        self.assertEqual((target / "siyue-core.jar").read_bytes(), self.jar.read_bytes())
        self.assertEqual((target.stat().st_mode & 0o777), 0o700)

    def test_wrong_hash_rejected_before_output(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.build(expected_sha256="0" * 64)
        self.assertFalse((self.root / "candidate-01").exists())

    def test_different_migration_rejected(self):
        (self.migrations / "V1__base.sql").write_text("changed")
        with self.assertRaisesRegex(ValueError, "migrations differ"):
            self.build()

    def test_non_executable_jar_rejected(self):
        self.write_jar(False)
        with self.assertRaisesRegex(ValueError, "executable"):
            self.build()

    def test_existing_release_never_overwritten(self):
        self.build()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.build()

    def test_path_traversal_rejected(self):
        with self.assertRaisesRegex(ValueError, "release ID"):
            self.build(release_id="../escape")

    def test_symlink_jar_rejected(self):
        link = self.root / "link.jar"
        link.symlink_to(self.jar)
        with self.assertRaisesRegex(ValueError, "Regular JAR"):
            self.build(jar=link)

    def test_missing_source_migrations_rejected(self):
        (self.migrations / "V1__base.sql").unlink()
        with self.assertRaisesRegex(ValueError, "migration source"):
            self.build()


if __name__ == "__main__":
    unittest.main()
