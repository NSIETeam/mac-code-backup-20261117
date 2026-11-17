#!/usr/bin/env python3
"""Reviewable, explicit theme-only change for the existing v101 candidate realm.

No action runs by default. Preparation saves the original compose bytes and the
four realm presentation fields. Activation/rollback require --execute. This tool
never writes users, credentials, providers, client settings, nginx or a database.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys

IDP_ROOT = Path('/opt/siyue-idp-candidate/v100')
BUSINESS_STATE = Path('/opt/siyue-java-real-candidate/v101/state.json')
REVIEW = IDP_ROOT / 'theme-siyue-review'
REALM = 'siyue-migration-v101'
CLIENT = 'siyue-candidate-browser'
REDIRECT = 'https://www.siyuejn.com/java-candidate-v101/crm/'
FIELDS = ('loginTheme', 'internationalizationEnabled', 'defaultLocale', 'supportedLocales')
DESIRED = {'loginTheme': 'siyue', 'internationalizationEnabled': True,
           'defaultLocale': 'zh-Hans', 'supportedLocales': ['zh-Hans']}
THEME_FILES = ('login/theme.properties', 'login/messages/messages_zh_Hans.properties',
               'login/resources/css/siyue.css', 'login/resources/img/siyue-peiyou-logo.png')


def digest(data):
    """Return the complete SHA-256 used to bind reviewed bytes."""
    return hashlib.sha256(data).hexdigest()


def validate_version(output):
    """Allow launcher notices but require one exact reviewed version line."""
    versions = [line.strip() for line in output.splitlines()
                if line.strip().startswith('Keycloak ')]
    if versions != ['Keycloak 26.8.1']:
        raise ValueError('Keycloak version differs from reviewed template')


def theme_inventory(path):
    """Reject extra executable templates, symlinks and incomplete theme bundles."""
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Theme directory missing or symlinked')
    entries = list(path.rglob('*'))
    if any(item.is_symlink() for item in entries):
        raise ValueError('Theme symlinks refused')
    files = {str(item.relative_to(path)): digest(item.read_bytes())
             for item in entries if item.is_file()}
    if set(files) != set(THEME_FILES):
        raise ValueError('Unexpected or missing theme resource')
    props = dict(line.split('=', 1) for line in (path / 'login/theme.properties').read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#'))
    if props.get('parent') != 'keycloak.v2' or props.get('styles') != 'css/styles.css css/siyue.css':
        raise ValueError('Native parent template and stylesheet required')
    return files


def compose_with_mount(original, theme_path):
    """Add only one read-only mount; retain every other existing compose byte."""
    text = original.decode('utf-8')
    matches = list(re.finditer(r'^    volumes: (\[[^\n]+\])$', text, re.M))
    if len(matches) != 1 or text.count('\n  idp:\n') != 1:
        raise ValueError('Expected dedicated idp compose shape')
    values = json.loads(matches[0].group(1))
    if values != [str(IDP_ROOT / 'tls') + ':/candidate-tls:ro']:
        raise ValueError('Existing mount inventory differs; review required')
    if theme_path != REVIEW / 'themes/siyue':
        raise ValueError('Theme mount must remain inside fixed candidate review directory')
    values.append(str(theme_path) + ':/opt/keycloak/themes/siyue:ro')
    m = matches[0]
    return (text[:m.start(1)] + json.dumps(values) + text[m.end(1):]).encode()


def realm_fields(realm):
    """Capture effective values, including null for absent optional fields."""
    return {field: realm.get(field) for field in FIELDS}


def validate_identity(candidate, realm, clients):
    """Pin the existing candidate realm and callback without modifying identity."""
    if (candidate.get('realm') != REALM or candidate.get('idpRoot') != str(IDP_ROOT)
            or candidate.get('clientId') != CLIENT or candidate.get('redirectUri') != REDIRECT
            or realm.get('realm') != REALM or not realm.get('id')):
        raise ValueError('Candidate realm/identity boundary mismatch')
    if len(clients) != 1 or clients[0].get('clientId') != CLIENT:
        raise ValueError('Candidate browser client is not unique')
    client = clients[0]
    if (client.get('publicClient') is not True or client.get('standardFlowEnabled') is not True
            or client.get('redirectUris') != [REDIRECT]
            or client.get('attributes', {}).get('login_theme') not in (None, '')):
        raise ValueError('Candidate browser client contract changed')
    return {'realmId': realm['id'], 'clientUuid': client['id']}


def secure(path, directory=False):
    """Require root ownership and reject symlinks before loading candidate helpers."""
    for item in [*reversed(path.parents), path]:
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0:
            raise ValueError('Candidate file owner or path invalid')
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected(info.st_mode) or info.st_mode & 0o077:
        raise ValueError('Candidate file must be private')
    return path


def context():
    """Read existing private configuration in-process; never return it to stdout."""
    if os.geteuid() != 0:
        raise ValueError('Root is required on the reviewed candidate host')
    secure(IDP_ROOT, True)
    for name in ('common.py', 'probe.py', 'operate.py'):
        secure(IDP_ROOT / name)
    sys.path.insert(0, str(IDP_ROOT))
    from common import load, private_file, run
    from probe import Client, admin
    from operate import start
    root, state, private = load(str(IDP_ROOT))
    candidate = json.loads(private_file(BUSINESS_STATE).read_text())
    version = run(['docker', 'exec', state['container'], '/opt/keycloak/bin/kc.sh', '--version']).decode()
    validate_version(version)
    client = Client(root)
    token = admin(client, private)
    realm = client.request('/admin/realms/' + REALM, token=token)
    clients = client.request('/admin/realms/' + REALM + '/clients?clientId=' + CLIENT, token=token)
    identity = validate_identity(candidate, realm, clients)
    identity.update(imageId=state['imageId'], providerSha256=state['providerSha256'])
    return root, state, client, token, realm, identity, start


def private_write(path, data):
    """Atomically replace only a regular review file using mode 0600."""
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError('Unsafe output path')
    tmp = path.with_name(path.name + '.new')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def prepare():
    """Create local candidate backups and a plan; do not recreate or update realm."""
    root, _, _, _, realm, identity, _ = context()
    source = Path(__file__).absolute().parent / 'themes/siyue'
    inventory = theme_inventory(source)
    original = (root / 'compose.yaml').read_bytes()
    updated = compose_with_mount(original, REVIEW / 'themes/siyue')
    REVIEW.mkdir(mode=0o700, exist_ok=False)
    shutil.copytree(source, REVIEW / 'themes/siyue')
    for item in (REVIEW / 'themes').rglob('*'):
        item.chmod(0o755 if item.is_dir() else 0o644)
    (REVIEW / 'themes').chmod(0o755)
    private_write(REVIEW / 'compose.before.yaml', original)
    private_write(REVIEW / 'compose.after.yaml', updated)
    plan = {'kind': 'siyue-candidate-theme-v1', 'identity': identity,
            'beforeRealm': realm_fields(realm), 'afterRealm': DESIRED,
            'beforeComposeSha256': digest(original), 'afterComposeSha256': digest(updated),
            'themeFiles': inventory}
    private_write(REVIEW / 'plan.json', json.dumps(plan, indent=2).encode())
    print(json.dumps({'prepared': True, 'realmChanged': False, 'containerRestarted': False,
                      'planSha256': digest((REVIEW / 'plan.json').read_bytes())}))


def load_plan(expected_hash):
    """Bind execution to the independently reviewed private plan hash."""
    secure(REVIEW, True)
    raw = secure(REVIEW / 'plan.json').read_bytes()
    if digest(raw) != expected_hash:
        raise ValueError('Reviewed plan digest mismatch')
    plan = json.loads(raw)
    if plan.get('kind') != 'siyue-candidate-theme-v1' or plan.get('afterRealm') != DESIRED:
        raise ValueError('Unexpected theme plan')
    for phase in ('before', 'after'):
        raw = secure(REVIEW / ('compose.' + phase + '.yaml')).read_bytes()
        if digest(raw) != plan[phase + 'ComposeSha256']:
            raise ValueError('Compose backup drift')
    if theme_inventory(REVIEW / 'themes/siyue') != plan['themeFiles']:
        raise ValueError('Reviewed theme resource drift')
    return plan


def verify_mount(state, active):
    """Read Docker mount metadata only; never inspect or print container environment."""
    from common import run
    rows = json.loads(run(['docker', 'inspect', '--format', '{{json .Mounts}}', state['container']]))
    found = [row for row in rows if row.get('Destination') == '/opt/keycloak/themes/siyue']
    if active:
        if (len(found) != 1 or found[0].get('Type') != 'bind' or found[0].get('RW') is not False
                or found[0].get('Source') != str(REVIEW / 'themes/siyue')):
            raise ValueError('Reviewed read-only theme mount was not applied')
    elif found:
        raise ValueError('Theme mount still present after rollback')


def change(action, expected_hash):
    """Apply or undo only the reviewed mount and realm presentation fields."""
    plan = load_plan(expected_hash)
    root, state, client, token, realm, identity, start = context()
    if identity != plan['identity']:
        raise ValueError('Candidate image/provider/realm identity changed')
    before = action == 'rollback'
    target = plan['beforeRealm'] if before else plan['afterRealm']
    expected = plan['afterRealm'] if before else plan['beforeRealm']
    # Partial activation can be explicitly rolled back, but external edits are refused.
    if realm_fields(realm) not in (target, expected):
        raise ValueError('Realm presentation fields changed after review')
    current = digest((root / 'compose.yaml').read_bytes())
    allowed = (plan['beforeComposeSha256'], plan['afterComposeSha256']) if before else (plan['beforeComposeSha256'],)
    if current not in allowed:
        raise ValueError('Live compose changed after review')
    if before:
        client.request('/admin/realms/' + REALM, 'PUT', target, token)
        if realm_fields(client.request('/admin/realms/' + REALM, token=token)) != target:
            raise ValueError('Realm rollback readback differs')
    phase = 'before' if before else 'after'
    private_write(root / 'compose.yaml', (REVIEW / ('compose.' + phase + '.yaml')).read_bytes())
    start(root, state, force=True)  # Existing helper verifies TLS and exclusive 127.0.0.1:18449 binding.
    verify_mount(state, not before)
    # Tokens need refreshing after the container is recreated.
    _, _, client, token, realm, identity_after, _ = context()
    if identity_after != identity:
        raise ValueError('Candidate identity changed across recreation')
    if not before:
        if realm_fields(realm) != expected:
            raise ValueError('Realm changed during recreation')
        client.request('/admin/realms/' + REALM, 'PUT', target, token)
    result = client.request('/admin/realms/' + REALM, token=token)
    if realm_fields(result) != target:
        raise ValueError('Theme field readback differs')
    print(json.dumps({'action': action, 'realm': REALM, 'realmFieldsVerified': True,
                      'loopbackTlsVerified': True, 'browserVerified': False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'activate', 'rollback'))
    parser.add_argument('--execute', action='store_true', help='Explicit candidate-only write/recreate approval')
    parser.add_argument('--plan-sha256')
    args = parser.parse_args()
    if not args.execute:
        parser.error('All actions require --execute; no state has been changed')
    if args.action != 'prepare' and not re.fullmatch('[0-9a-f]{64}', args.plan_sha256 or ''):
        parser.error('The independently reviewed --plan-sha256 is required')
    try:
        if args.action == 'prepare':
            prepare()
        else:
            change(args.action, args.plan_sha256)
    except Exception as error:
        # Underlying HTTP/process exceptions may contain private response/configuration data.
        print(json.dumps({'ok': False, 'category': type(error).__name__,
                          'message': 'Candidate theme operation stopped; inspect private state before explicit rollback'}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
