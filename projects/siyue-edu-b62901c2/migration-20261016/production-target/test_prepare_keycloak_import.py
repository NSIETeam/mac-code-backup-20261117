import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare-keycloak-import.py")
SPEC = importlib.util.spec_from_file_location("prepare_keycloak_import", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class KeycloakImportSpecTest(unittest.TestCase):
    def test_reviewed_profile_keeps_security_attributes_admin_only(self):
        profile = json.loads(SCRIPT.with_name("approved-user-profile.json").read_text())
        self.assertEqual(profile["unmanagedAttributePolicy"], "ADMIN_EDIT")
        self.assertEqual({x["name"] for x in profile["attributes"]},
                         {"username", "firstName", "lastName", "email"})
        for attribute in profile["attributes"]:
            self.assertFalse(attribute["name"].startswith(("legacy_credential_", "credential_change_pending_")))

    def test_generation_mapper_is_identical_and_fail_closed_in_both_generators(self):
        stage_spec = importlib.util.spec_from_file_location("stage_generation_check", SCRIPT.with_name("production-idp-stage.py"))
        stage = importlib.util.module_from_spec(stage_spec)
        stage_spec.loader.exec_module(stage)
        self.assertEqual(MODULE.credential_generation_mapper(), stage.credential_generation_mapper())
        for module, validate in ((MODULE, MODULE.validate), (stage, stage.validate_realm)):
            realm = module.build_realm()
            validate(realm)
            mappers = realm["clients"][0]["protocolMappers"]
            self.assertEqual(mappers[-1]["config"]["jsonType.label"], "long")
            for field, value in (("jsonType.label", "String"), ("access.token.claim", "false"),
                                 ("multivalued", "true"), ("user.attribute", "untrusted")):
                broken = json.loads(json.dumps(realm))
                broken["clients"][0]["protocolMappers"][-1]["config"][field] = value
                with self.subTest(generator=module.__name__, field=field), self.assertRaises(ValueError):
                    validate(broken)
            for replacement in (mappers[:-1], mappers + [mappers[-1]]):
                broken = json.loads(json.dumps(realm))
                broken["clients"][0]["protocolMappers"] = replacement
                with self.assertRaises(ValueError):
                    validate(broken)

    def test_reviewed_spec_is_auth_code_pkce_only(self):
        realm = MODULE.build_realm()
        MODULE.validate(realm)
        client = realm["clients"][0]
        self.assertEqual(realm["realm"], "siyue-production")
        self.assertEqual(realm["defaultLocale"], "zh-Hans")
        self.assertEqual(client["clientId"], "siyue-crm-browser")
        self.assertEqual(client["redirectUris"], ["https://www.siyuejn.com/crm/"])
        self.assertEqual(client["webOrigins"], ["https://www.siyuejn.com"])
        self.assertFalse(client["directAccessGrantsEnabled"])
        self.assertFalse(client["implicitFlowEnabled"])
        self.assertFalse(client["serviceAccountsEnabled"])
        self.assertEqual(client["attributes"]["pkce.code.challenge.method"], "S256")

    def test_wildcard_redirect_is_rejected(self):
        realm = MODULE.build_realm()
        realm["clients"][0]["redirectUris"] = ["https://www.siyuejn.com/*"]
        with self.assertRaisesRegex(ValueError, "wildcard"):
            MODULE.validate(realm)

    def test_candidate_realm_and_user_import_are_rejected(self):
        realm = MODULE.build_realm()
        realm["realm"] = "siyue-migration-v101"
        with self.assertRaises(ValueError):
            MODULE.validate(realm)
        realm = MODULE.build_realm()
        realm["users"] = [{"username": "synthetic-only"}]
        with self.assertRaisesRegex(ValueError, "separate trusted identity migration"):
            MODULE.validate(realm)

    def test_password_or_client_secret_fields_are_rejected(self):
        realm = MODULE.build_realm()
        realm["clients"][0]["secret"] = "never-allowed"
        with self.assertRaisesRegex(ValueError, "credentials"):
            MODULE.validate(realm)

    def test_write_creates_private_non_executable_package_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "spec"
            MODULE.write_private(output, MODULE.build_realm())
            self.assertEqual(os.stat(output).st_mode & 0o777, 0o700)
            for name in ("realm-import.json", "review-manifest.json"):
                path = output / name
                self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
            manifest = json.loads((output / "review-manifest.json").read_text())
            self.assertFalse(manifest["executeAutomatically"])
            self.assertEqual(manifest["requiredNginxPath"], "/crm-auth/")
            with self.assertRaisesRegex(ValueError, "new directory"):
                MODULE.write_private(output, MODULE.build_realm())

    def test_cli_write_is_local_and_emits_only_safe_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "spec"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--write", str(output)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertFalse(summary["contactsKeycloak"])
            self.assertFalse(summary["containsCredentials"])
            self.assertEqual(summary["userCount"], 0)
            self.assertTrue((output / "realm-import.json").is_file())


if __name__ == "__main__":
    unittest.main()
