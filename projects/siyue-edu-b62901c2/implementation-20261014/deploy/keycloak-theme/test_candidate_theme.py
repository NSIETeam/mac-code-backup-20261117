"""Offline theme/plan boundary tests; these do not claim a browser or remote rollout."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location('candidate_theme', HERE / 'candidate-theme.py')
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


class ThemeTests(unittest.TestCase):
    def test_version_allows_launcher_notice_but_rejects_drift(self):
        m.validate_version('Appending additional Java properties to JAVA_OPTS\nKeycloak 26.8.1\nJVM: 21\n')
        for output in ('Keycloak 26.8.2\n', 'Keycloak 26.8.1-extra\n',
                       'notice Keycloak 26.8.1\n', 'Keycloak 26.8.1\nKeycloak 26.8.1\n'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                m.validate_version(output)

    def test_no_auth_template_or_javascript_is_added(self):
        self.assertEqual(set(m.theme_inventory(HERE / 'themes/siyue')), set(m.THEME_FILES))
        self.assertFalse(list((HERE / 'themes').rglob('*.ftl')))
        self.assertFalse(list((HERE / 'themes').rglob('*.js')))

    def test_existing_logo_is_byte_identical(self):
        original = HERE.parent.parent / 'assets/siyue-peiyou-logo.png'
        self.assertEqual(original.read_bytes(), (HERE / 'themes/siyue/login/resources/img/siyue-peiyou-logo.png').read_bytes())

    def test_theme_inherits_native_styles_and_chinese_messages(self):
        props = (HERE / 'themes/siyue/login/theme.properties').read_text()
        for value in ('parent=keycloak.v2', 'styles=css/styles.css css/siyue.css', 'locales=zh-Hans', 'darkMode=false'):
            self.assertIn(value, props)
        messages = (HERE / 'themes/siyue/login/messages/messages_zh_Hans.properties').read_text()
        self.assertIn('loginAccountTitle=员工登录', messages)
        self.assertNotIn('invalidPassword', messages)

    def test_css_preserves_visibility_and_uses_local_resources(self):
        css = (HERE / 'themes/siyue/login/resources/css/siyue.css').read_text()
        for color in ('#EAF2F6', '#82AEC3', '#FFF9F2', '#DCA477'):
            self.assertIn(color, css)
        for unsafe in ('display: none', 'visibility: hidden', 'https:', 'http:', '@import'):
            self.assertNotIn(unsafe, css)
        self.assertIn(':focus-visible', css)
        self.assertIn('prefers-reduced-motion', css)

    def test_extra_template_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'siyue'
            shutil.copytree(HERE / 'themes/siyue', target)
            (target / 'login/login.ftl').write_text('form override')
            with self.assertRaises(ValueError):
                m.theme_inventory(target)

    def test_symlink_asset_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'siyue'
            shutil.copytree(HERE / 'themes/siyue', target)
            (target / 'login/foreign').symlink_to('/etc/hosts')
            with self.assertRaises(ValueError):
                m.theme_inventory(target)

    def test_parent_template_drift_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'siyue'
            shutil.copytree(HERE / 'themes/siyue', target)
            (target / 'login/theme.properties').write_text('parent=base\nstyles=css/siyue.css')
            with self.assertRaises(ValueError):
                m.theme_inventory(target)

    def compose(self):
        return b'name: siyue-idp-candidate-v100\nservices:\n  idp:\n    image: candidate\n    volumes: ["/opt/siyue-idp-candidate/v100/tls:/candidate-tls:ro"]\n    read_only: true\n'

    def test_compose_changes_only_volume_line(self):
        before = self.compose()
        after = m.compose_with_mount(before, m.REVIEW / 'themes/siyue')
        old = next(line for line in before.splitlines() if b'volumes:' in line)
        new = next(line for line in after.splitlines() if b'volumes:' in line)
        self.assertEqual(before.replace(old, new), after)
        mounts = json.loads(new.decode().split('volumes: ', 1)[1])
        self.assertEqual(len(mounts), 2)
        self.assertTrue(mounts[1].endswith(':/opt/keycloak/themes/siyue:ro'))

    def test_unreviewed_mount_refused(self):
        with self.assertRaises(ValueError):
            m.compose_with_mount(self.compose().replace(b'/candidate-tls:ro', b'/candidate-tls:rw'), m.REVIEW / 'themes/siyue')

    def test_theme_path_cannot_target_production(self):
        with self.assertRaises(ValueError):
            m.compose_with_mount(self.compose(), Path('/opt/production/themes/siyue'))

    def identity(self):
        return ({'realm': m.REALM, 'idpRoot': str(m.IDP_ROOT), 'clientId': m.CLIENT, 'redirectUri': m.REDIRECT},
                {'realm': m.REALM, 'id': 'isolated-realm-id'},
                [{'clientId': m.CLIENT, 'id': 'isolated-client-id', 'publicClient': True,
                  'standardFlowEnabled': True, 'redirectUris': [m.REDIRECT]}])

    def test_only_existing_candidate_identity_is_accepted(self):
        self.assertEqual(m.validate_identity(*self.identity()), {'realmId': 'isolated-realm-id', 'clientUuid': 'isolated-client-id'})

    def test_production_realm_is_refused(self):
        candidate, realm, clients = self.identity()
        candidate['realm'] = 'production'
        with self.assertRaises(ValueError):
            m.validate_identity(candidate, realm, clients)

    def test_production_or_wildcard_callback_is_refused(self):
        for redirect in ('https://www.siyuejn.com/crm/', 'https://www.siyuejn.com/*'):
            with self.subTest(redirect=redirect):
                candidate, realm, clients = self.identity()
                clients[0]['redirectUris'] = [redirect]
                with self.assertRaises(ValueError):
                    m.validate_identity(candidate, realm, clients)

    def test_client_theme_override_is_refused(self):
        candidate, realm, clients = self.identity()
        clients[0]['attributes'] = {'login_theme': 'other'}
        with self.assertRaises(ValueError):
            m.validate_identity(candidate, realm, clients)

    def test_capture_does_not_copy_credentials_or_other_realm_settings(self):
        fields = m.realm_fields({'loginTheme': None, 'internationalizationEnabled': False,
                                 'credential': 'never-retain', 'registrationAllowed': False})
        self.assertEqual(set(fields), set(m.FIELDS))
        self.assertIsNone(fields['defaultLocale'])
        self.assertNotIn('credential', fields)

    def test_no_execution_without_explicit_flag(self):
        result = subprocess.run([sys.executable, str(HERE / 'candidate-theme.py'), 'activate'], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'no state has been changed', result.stderr)

    def test_atomic_write_preserves_bytes_and_private_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'compose.yaml'
            m.private_write(target, b'original\n')
            m.private_write(target, b'replacement\n')
            self.assertEqual(target.read_bytes(), b'replacement\n')
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertFalse(target.with_name('compose.yaml.new').exists())

    def test_output_symlink_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'compose.yaml'
            target.symlink_to(Path(tmp) / 'other')
            with self.assertRaises(ValueError):
                m.private_write(target, b'replacement')


class ChangeBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.review = self.root / 'theme-siyue-review'
        self.review.mkdir()
        shutil.copytree(HERE / 'themes/siyue', self.review / 'themes/siyue')
        self.original = b'original compose bytes\n'
        self.after = b'reviewed theme compose bytes\n'
        (self.root / 'compose.yaml').write_bytes(self.original)
        (self.review / 'compose.before.yaml').write_bytes(self.original)
        (self.review / 'compose.after.yaml').write_bytes(self.after)
        self.realm = dict(zip(m.FIELDS, (None, False, None, None)))
        self.original_fields = copy.deepcopy(self.realm)
        self.identity = {'realmId': 'same-candidate', 'clientUuid': 'same-client',
                         'imageId': 'same-image', 'providerSha256': 'same-provider'}
        plan = {'kind': 'siyue-candidate-theme-v1', 'identity': self.identity,
                'beforeRealm': self.original_fields, 'afterRealm': m.DESIRED,
                'beforeComposeSha256': m.digest(self.original), 'afterComposeSha256': m.digest(self.after),
                'themeFiles': m.theme_inventory(self.review / 'themes/siyue')}
        raw = json.dumps(plan).encode()
        (self.review / 'plan.json').write_bytes(raw)
        self.hash = m.digest(raw)
        self.writes = []
        test = self
        class FakeClient:
            def request(self, path, method='GET', body=None, token=None):
                test.assertEqual(path, '/admin/realms/' + m.REALM)
                if method == 'PUT':
                    test.writes.append(copy.deepcopy(body))
                    test.realm.update(body)
                return copy.deepcopy(test.realm)
        self.client = FakeClient()
        self.start_patch = patch.object(m, 'verify_mount')
        self.mount = self.start_patch.start()
        self.addCleanup(self.start_patch.stop)
        from unittest.mock import Mock
        self.start = Mock()
        def ctx():
            return (self.root, {}, self.client, 'synthetic-only', copy.deepcopy(self.realm),
                    copy.deepcopy(self.identity), self.start)
        for item in (patch.object(m, 'REVIEW', self.review), patch.object(m, 'context', side_effect=ctx),
                     patch.object(m, 'secure', side_effect=lambda path, directory=False: path)):
            item.start()
            self.addCleanup(item.stop)

    def test_activate_and_rollback_restore_exact_original(self):
        m.change('activate', self.hash)
        self.assertEqual((self.root / 'compose.yaml').read_bytes(), self.after)
        self.assertEqual(self.writes, [m.DESIRED])
        self.mount.assert_called_with({}, True)
        m.change('rollback', self.hash)
        self.assertEqual((self.root / 'compose.yaml').read_bytes(), self.original)
        self.assertEqual(self.realm, self.original_fields)
        self.assertEqual(self.writes[-1], self.original_fields)
        self.mount.assert_called_with({}, False)
        self.assertEqual(self.start.call_count, 2)

    def test_external_realm_change_blocks_activation_before_restart(self):
        self.realm['loginTheme'] = 'another-reviewed-theme'
        with self.assertRaises(ValueError):
            m.change('activate', self.hash)
        self.start.assert_not_called()
        self.assertEqual(self.writes, [])

    def test_external_compose_change_blocks_activation_before_restart(self):
        (self.root / 'compose.yaml').write_bytes(b'concurrent configuration change')
        with self.assertRaises(ValueError):
            m.change('activate', self.hash)
        self.start.assert_not_called()

    def test_wrong_plan_digest_blocks_all_actions(self):
        with self.assertRaises(ValueError):
            m.change('activate', '0' * 64)
        self.start.assert_not_called()

    def test_resource_drift_blocks_activation(self):
        (self.review / 'themes/siyue/login/resources/css/siyue.css').write_text('changed')
        with self.assertRaises(ValueError):
            m.change('activate', self.hash)
        self.start.assert_not_called()

    def test_partial_mount_activation_can_be_rolled_back(self):
        (self.root / 'compose.yaml').write_bytes(self.after)
        m.change('rollback', self.hash)
        self.assertEqual((self.root / 'compose.yaml').read_bytes(), self.original)
        self.assertEqual(self.realm, self.original_fields)


if __name__ == '__main__':
    unittest.main()
