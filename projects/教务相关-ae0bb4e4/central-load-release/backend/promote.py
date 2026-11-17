#!/usr/bin/env python3
"""Schema-compatible release. Default: local validation only; --execute is explicit.

No migrations, login sessions, business-row edits, static edits or data rollback.
API and worker have different original releases and are restored separately.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import time
import urllib.request
import uuid
import socket

HERE = Path(__file__).resolve().parent
ROOT = Path('/opt/siyue-miniapp')
SNIPPET = Path('/etc/nginx/snippets/siyue-miniapp.conf')
STATIC = Path('/opt/siyue-workbench/current')
TAG = '20261107-central-load'
CANARY = 'siyue-central-load-canary'
OWNED_CANARY = None
BASE_API = {'imageTag': 'siyue-miniapp:20261107-label-confirm', 'imageId': 'sha256:c7eab7e268e651f709077e1f3ccfb65e8b810b986374a7b27312ee551bb6dc5b', 'serverSha256': '5d11892505f8e3a2bb95b16ac35c63352b558d6cfb064f6860e7e976def1c3c9'}
BASE_WORKER = {'imageTag': 'siyue-miniapp:20261107-label-confirm', 'imageId': 'sha256:c7eab7e268e651f709077e1f3ccfb65e8b810b986374a7b27312ee551bb6dc5b', 'serverSha256': '5d11892505f8e3a2bb95b16ac35c63352b558d6cfb064f6860e7e976def1c3c9'}
ARTIFACTS = {'Siyue.Server.dll', 'Siyue.Server.pdb'}
SOURCE_DELTA = {'tests/CentralResearchWorkbenchTests.cs', 'server/CentralResearchWorkbench.cs'}
INHERITED = ['Siyue.Printing.dll', 'Siyue.Server.deps.json', 'Siyue.Server.runtimeconfig.json', 'ai_handout_images.py', 'pdf_pixels.py']
DOCKERFILE = 'FROM siyue-miniapp:20261107-label-confirm\nCOPY artifacts/Siyue.Server.dll /app/Siyue.Server.dll\nCOPY artifacts/Siyue.Server.pdb /app/Siyue.Server.pdb\n'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def private_write(path, data):
    path = Path(path)
    temp = path.with_name(path.name + '.tmp-' + uuid.uuid4().hex)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data if isinstance(data, bytes) else data.encode())
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def write_json(path, value):
    private_write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def event(backup, phase, **values):
    with (backup / 'events.jsonl').open('a', encoding='utf-8') as out:
        os.chmod(out.name, 0o600)
        out.write(json.dumps({'at': time.time(), 'phase': phase, **values}, ensure_ascii=False) + '\n')
        out.flush()
        os.fsync(out.fileno())


def run(args, *, env=None, input=None, timeout=30):
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, input=input, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise RuntimeError('external command timed out: ' + Path(args[0]).name) from None
    if result.returncode:
        raise RuntimeError('external command failed: ' + Path(args[0]).name)
    return result.stdout.decode()


def compose(*args, tag=None, timeout=60):
    env = dict(os.environ)
    env.pop('COMPOSE_FILE', None)
    env.pop('COMPOSE_PROJECT_NAME', None)
    env.pop('RELEASE_TAG', None)
    if tag:
        env['RELEASE_TAG'] = tag.split(':', 1)[-1]
    return run(['docker', 'compose', '--project-directory', str(ROOT), '-f', str(ROOT / 'compose.yml'), '--env-file', str(ROOT / '.env'), *args], env=env, timeout=timeout)


def database(sql):
    return run(['sudo', '-u', 'postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-d', 'siyue_miniapp'], input=sql.encode(), timeout=8).strip()


def check_manifest():
    value = json.loads((HERE / 'candidate.manifest.json').read_text())
    if value.get('releaseTag') != TAG or value.get('baseApi') != BASE_API or value.get('baseWorker') != BASE_WORKER or value.get('schemaMigration') is not False:
        raise ValueError('manifest does not match the separately pinned API/worker baseline')
    delta = value.get('sourceDelta', [])
    if len(delta) != len(SOURCE_DELTA) or {item.get('path') for item in delta} != SOURCE_DELTA or any(not re.fullmatch('[0-9a-f]{64}', item.get('sha256', '')) for item in delta):
        raise ValueError('candidate source delta must be exactly the reviewed source files')
    artifacts = value.get('artifacts', [])
    if len(artifacts) != 2 or {item.get('path') for item in artifacts} != ARTIFACTS or {p.name for p in (HERE / 'artifacts').iterdir()} != ARTIFACTS:
        raise ValueError('candidate must contain exactly Server.dll and Server.pdb')
    for item in artifacts:
        path = HERE / 'artifacts' / item['path']
        if sha(path) != item['sha256'] or path.stat().st_size != item['bytes']:
            raise ValueError('candidate artifact changed')
    receipt = HERE / 'candidate.acceptance.json'
    if sha(receipt) != value.get('acceptanceSha256'):
        raise ValueError('candidate acceptance receipt changed')
    accepted = json.loads(receipt.read_text())
    dll = next(item['sha256'] for item in artifacts if item['path'] == 'Siyue.Server.dll')
    if accepted.get('ok') is not True or accepted.get('candidateServerSha256') != dll:
        raise ValueError('candidate has no successful hash-bound acceptance receipt')
    if (HERE / 'Dockerfile').read_text() != DOCKERFILE:
        raise ValueError('Dockerfile must inherit the reviewed fixed-parser worker and copy only two artifacts')
    return value


def env_release(data):
    rows = list(re.finditer(rb'(?m)^(?:export[ \t]+)?RELEASE_TAG[ \t]*=[ \t]*([^\r\n]*)(\r?)$', data))
    if len(rows) != 1:
        raise RuntimeError('expected exactly one RELEASE_TAG line')
    return rows[0], rows[0].group(1).strip().strip(b'\"\'').decode()


def env_tag(data, tag):
    row, _ = env_release(data)
    # Replace only the value; preserve every unrelated byte including CRLF.
    return data[:row.start(1)] + tag.encode() + data[row.end(1):]


def update_env_tag(expected_tags, tag):
    current = (ROOT / '.env').read_bytes()
    _, actual = env_release(current)
    if actual not in expected_tags:
        raise RuntimeError('another release owns RELEASE_TAG; preserve it')
    updated = env_tag(current, tag)
    if (ROOT / '.env').read_bytes() != current:
        raise RuntimeError('environment changed concurrently')
    private_write(ROOT / '.env', updated)


def worker_image(data, tag):
    block = re.search(rb'(?m)^  worker:[ \t]*\r?\n(?:(?!^  [A-Za-z0-9_-]+:).*(?:\n|$))*', data)
    if not block:
        raise RuntimeError('worker compose block not found')
    rows = list(re.finditer(rb'(?m)^    image:[ \t]*([^\r\n]+)', block.group()))
    if len(rows) != 1:
        raise RuntimeError('expected one explicit worker image scalar')
    row = rows[0]
    value = row.group(1).strip().strip(b'\"\'').decode()
    if value not in (BASE_WORKER['imageTag'], 'siyue-miniapp:' + TAG):
        raise RuntimeError('worker image scalar belongs to another release')
    start, end = block.start() + row.start(1), block.start() + row.end(1)
    return data[:start] + tag.encode() + data[end:]


def switch_worker_image(expected, tag):
    path = ROOT / 'compose.yml'
    if path.read_bytes() != expected:
        raise RuntimeError('compose changed concurrently; preserve it')
    updated = worker_image(expected, tag)
    mode = path.stat().st_mode & 0o777
    private_write(path, updated)
    path.chmod(mode)
    return updated


def acquire_drain(backup):
    marker = ROOT / 'data/worker-control/drain'
    receipt = backup / 'owned-drain.json'
    if receipt.exists():
        owned = json.loads(receipt.read_text())['content'].encode()
    else:
        owned = json.dumps({'release': TAG, 'nonce': uuid.uuid4().hex, 'backup': str(backup)}).encode()
        write_json(receipt, {'content': owned.decode(), 'path': str(marker)})
    if marker.exists():
        if marker.read_bytes() != owned:
            raise RuntimeError('another operation owns drain marker; preserve it')
        return
    fd = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, 'wb') as out:
        out.write(owned)
        out.flush()
        os.fsync(out.fileno())
    event(backup, 'owned-drain-created')


def release_drain(backup):
    receipt = backup / 'owned-drain.json'
    if not receipt.exists():
        return
    marker = ROOT / 'data/worker-control/drain'
    if not marker.exists():
        return
    owned = json.loads(receipt.read_text())['content'].encode()
    if marker.is_symlink() or marker.read_bytes() != owned:
        raise RuntimeError('drain marker ownership changed; preserve it')
    marker.unlink()
    event(backup, 'owned-drain-released')


def static_snapshot():
    target = str(STATIC.resolve(strict=True))
    files = {str(p.relative_to(STATIC)): sha(p) for p in sorted(STATIC.rglob('*')) if p.is_file()}
    if any(p.is_symlink() for p in STATIC.rglob('*')): raise RuntimeError('Unexpected static symlink')
    if str(STATIC.resolve(strict=True)) != target:
        raise RuntimeError('static release changed while reading')
    return {'target': target, 'hashes': files}


def inspect_container(container, timeout=30):
    raw = json.loads(run(['docker', 'inspect', '--format', '{{json .}}', container], timeout=timeout))
    values = dict(row.split('=', 1) for row in raw['Config'].get('Env', []) if '=' in row)
    if values.get('Database__Initialize', 'false').lower() in ('true', '1') or '--migrate' in (raw['Config'].get('Cmd') or []):
        raise RuntimeError('unexpected database initialization configuration')
    runtime = {'workerConfig': {key: value for key, value in values.items() if key.startswith('Worker__')},
               'limits': {key: raw['HostConfig'].get(key) for key in ['Memory', 'MemorySwap', 'NanoCpus', 'PidsLimit', 'Tmpfs']},
               'mounts': [{key: mount.get(key) for key in ['Source', 'Destination', 'RW']} for mount in raw['Mounts']]}
    labels = raw['Config'].get('Labels') or {}
    return {'id': raw['Id'], 'imageId': raw['Image'], 'imageTag': raw['Config']['Image'], 'running': raw['State']['Running'], 'runWorker': values.get('RunWorker', 'false').lower() in ('true', '1'), 'runtime': runtime,
            'name': raw.get('Name', '').lstrip('/'), 'oneOff': str(labels.get('com.docker.compose.oneoff', '')).lower() == 'true'}


def services():
    result = {}
    for name in ['api', 'worker']:
        ids = compose('ps', '-a', '-q', name).split()
        instances = [inspect_container(container) for container in ids]
        # Compose also lists its run --name canary as an API service instance.
        # Exclude only this invocation's recorded, non-worker one-off container.
        instances = [item for item in instances if not (name == 'api' and OWNED_CANARY and
            item['id'] == OWNED_CANARY['id'] and item['imageId'] == OWNED_CANARY['imageId'] and
            item.get('name') == CANARY and item.get('oneOff') is True and not item['runWorker'])]
        if len(instances) != 1:
            raise RuntimeError('expected exactly one ' + name + ' container')
        result[name] = instances[0]
    if result['api']['runWorker']:
        raise RuntimeError('API unexpectedly also runs document work')
    return result


def image_id(tag):
    return run(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag]).strip()


def runtime_hashes(image, names):
    result = run(['docker', 'run', '--rm', '--network', 'none', '--entrypoint', 'sha256sum', image, *['/app/' + name for name in names]])
    return {Path(line.split()[1]).name: line.split()[0] for line in result.splitlines()}


def health(port, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:' + str(port) + '/health', timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('health check failed on port ' + str(port))


def switch_nginx(content, expected):
    if SNIPPET.read_bytes() != expected:
        raise RuntimeError('nginx changed concurrently; preserve it')
    mode = SNIPPET.stat().st_mode & 0o777
    private_write(SNIPPET, content)
    SNIPPET.chmod(mode)
    try:
        run(['nginx', '-t'])
        run(['nginx', '-s', 'reload'])
    except Exception:
        if SNIPPET.read_bytes() == content:
            private_write(SNIPPET, expected)
            SNIPPET.chmod(mode)
            run(['nginx', '-t'])
            run(['nginx', '-s', 'reload'])
        raise


class GateBusy(Exception):
    pass


class QueueGate:
    """One local psql connection for the drain window; no 10-process/second loop."""
    def __enter__(self):
        self.proc = subprocess.Popen(['sudo', '-u', 'postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=0', '-d', 'siyue_miniapp'], cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        self.buffer = b''
        return self

    def command(self, sql):
        marker = ('GATE_' + uuid.uuid4().hex).encode()
        self.proc.stdin.write(sql.encode() + b'\n\\echo ' + marker + b'\n')
        self.proc.stdin.flush()
        deadline = time.monotonic() + 3
        while marker + b'\n' not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.proc.stdout], [], [], remaining)[0]:
                raise RuntimeError('private queue-lock connection timed out')
            chunk = os.read(self.proc.stdout.fileno(), 4096)
            if not chunk:
                raise RuntimeError('private queue-lock connection closed')
            self.buffer += chunk
        data, self.buffer = self.buffer.split(marker + b'\n', 1)
        return data.decode().strip()

    @contextlib.contextmanager
    def hold(self):
        # LOCK is NOWAIT; active work immediately releases this lock. The server
        # releases an abandoned transaction within 15 seconds even after a crash.
        try:
            stop_before = time.monotonic() + 3
            result = self.command("BEGIN; SET LOCAL statement_timeout='2s'; SET LOCAL idle_in_transaction_session_timeout='15s'; LOCK TABLE \"Work\" IN SHARE MODE NOWAIT; SELECT count(*) FROM \"Work\" WHERE \"State\"='running';")
            if result != '0':
                raise GateBusy('work active or claim lock unavailable')
            yield stop_before
        finally:
            if self.proc.poll() is None:
                self.command('ROLLBACK;')

    def __exit__(self, *_):
        if self.proc.poll() is None:
            with contextlib.suppress(BrokenPipeError, OSError):
                self.proc.stdin.write(b'ROLLBACK;\n\\q\n')
                self.proc.stdin.flush()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.proc.terminate()  # Only private psql, never document worker.
                self.proc.wait(timeout=2)
        self.proc.stdin.close()
        self.proc.stdout.close()


def graceful_stop(container, timeout=10):
    # -1 sends SIGTERM but never escalates to SIGKILL. The CLI itself is bounded.
    run(['docker', 'stop', '--time', '-1', container], timeout=timeout)
    if inspect_container(container)['running']:
        raise RuntimeError('service did not stop gracefully; no forced termination used')


def drain_and_stop(expected, backup, seconds=120):
    if not 1 <= seconds <= 1800:
        raise ValueError('drain is limited to 1800 seconds')
    deadline = time.monotonic() + seconds
    event(backup, 'drain-start', seconds=seconds)
    with QueueGate() as gate:
        next_identity_check = 0
        while time.monotonic() < deadline:
            if time.monotonic() >= next_identity_check:
                current = services()['worker']
                if current['id'] != expected['id'] or current['imageId'] != expected['imageId']:
                    raise RuntimeError('another deployment changed worker while draining')
                if not current['running']:
                    return
                next_identity_check = time.monotonic() + 5
            try:
                with gate.hold() as stop_before:
                    current = inspect_container(expected['id'], timeout=2)
                    if current['imageId'] != expected['imageId']:
                        raise RuntimeError('worker image changed before stop')
                    if time.monotonic() >= stop_before:
                        raise GateBusy('queue lock budget nearly expired; release and retry without stopping')
                    if current['running']:
                        graceful_stop(expected['id'], timeout=10)
                event(backup, 'worker-drained-and-stopped')
                return
            except GateBusy:
                pass
            time.sleep(min(.1, max(0, deadline - time.monotonic())))
    raise RuntimeError('running work did not drain within the requested window; worker was not interrupted')


def baseline_guard(baseline, expected, *, static=True, env=True, compose_bytes=None):
    current = services()
    for name in ['api', 'worker']:
        if current[name]['id'] != expected[name]['id'] or current[name]['imageId'] != expected[name]['imageId'] or current[name]['runtime'] != expected[name]['runtime']:
            raise RuntimeError('another deployment changed ' + name)
    if (ROOT / 'compose.yml').read_bytes() != (compose_bytes if compose_bytes is not None else (Path(baseline['backup']) / 'compose.yml').read_bytes()) or SNIPPET.read_bytes() != (Path(baseline['backup']) / 'nginx.conf').read_bytes():
        raise RuntimeError('production routing/compose changed; preserve it')
    if env and (ROOT / '.env').read_bytes() != (Path(baseline['backup']) / '.env').read_bytes():
        raise RuntimeError('production environment changed; preserve it')
    if static and static_snapshot() != baseline['static']:
        raise RuntimeError('static release changed before backend switch; recheck latest baseline')
    for pin in [BASE_API, BASE_WORKER]:
        if image_id(pin['imageTag']) != pin['imageId']:
            raise RuntimeError('original image tag changed; do not overwrite another release')


def stop_canary(backup):
    record = backup / 'canary.json'
    if not record.exists():
        return
    expected = json.loads(record.read_text())
    ids = run(['docker', 'ps', '-aq', '--filter', 'name=^/' + CANARY + '$']).split()
    if not ids:
        return
    current = inspect_container(ids[0])
    if current['id'] != expected['id'] or current['imageId'] != expected['imageId']:
        raise RuntimeError('canary belongs to another run; preserve it')
    if current['running']:
        graceful_stop(current['id'], timeout=12)
    run(['docker', 'rm', current['id']])


def rollback(backup, drain_seconds=120):
    global OWNED_CANARY
    record = backup / 'canary.json'
    OWNED_CANARY = json.loads(record.read_text()) if record.exists() else None
    baseline = json.loads((backup / 'baseline.json').read_text())
    candidate = json.loads((backup / 'candidate-image.json').read_text())['imageId']
    original_nginx = (backup / 'nginx.conf').read_bytes()
    candidate_nginx = original_nginx.replace(b'127.0.0.1:5082', b'127.0.0.1:15090')
    current = services()
    for service, pin in [('api', BASE_API), ('worker', BASE_WORKER)]:
        if current[service]['imageId'] not in (pin['imageId'], candidate) or image_id(pin['imageTag']) != pin['imageId']:
            raise RuntimeError('another deployment owns services or original tag; do not roll it back')
    original_compose = (backup / 'compose.yml').read_bytes()
    owned_compose = worker_image(original_compose, 'siyue-miniapp:' + TAG)
    if (ROOT / 'compose.yml').read_bytes() not in (original_compose, owned_compose) or SNIPPET.read_bytes() not in (original_nginx, candidate_nginx):
        raise RuntimeError('routing/compose changed outside this release; preserve it')
    _, tag = env_release((ROOT / '.env').read_bytes())
    if tag not in (TAG, BASE_API['imageTag'].split(':')[1]):
        raise RuntimeError('another release owns environment; preserve it')
    event(backup, 'rollback-start')
    acquire_drain(backup)
    try:
        # Both versions understand this schema. Restore the API first; keep a busy
        # new worker running until it drains rather than cancelling its task.
        if current['api']['imageId'] != BASE_API['imageId'] or not current['api']['running']:
            if image_id(BASE_API['imageTag']) != BASE_API['imageId']:
                raise RuntimeError('original API tag changed before rollback')
            compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'api', tag=BASE_API['imageTag'])
        health(5082)
        if SNIPPET.read_bytes() == candidate_nginx:
            switch_nginx(original_nginx, candidate_nginx)
        stop_canary(backup)
        current = services()
        if current['worker']['imageId'] != BASE_WORKER['imageId']:
            drain_and_stop(current['worker'], backup, drain_seconds)
            after_drain = services()['worker']
            compose_now = (ROOT / 'compose.yml').read_bytes()
            if after_drain['id'] != current['worker']['id'] or after_drain['imageId'] != current['worker']['imageId'] or compose_now not in (original_compose, owned_compose):
                raise RuntimeError('another operation changed worker/compose during rollback drain')
            if image_id(BASE_WORKER['imageTag']) != BASE_WORKER['imageId']:
                raise RuntimeError('original worker tag changed before rollback')
            switch_worker_image(compose_now, BASE_WORKER['imageTag'])
            compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'worker', tag=BASE_WORKER['imageTag'])
        elif not current['worker']['running']:
            switch_worker_image((ROOT / 'compose.yml').read_bytes(), BASE_WORKER['imageTag'])
            compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'worker', tag=BASE_WORKER['imageTag'])
        if (ROOT / 'compose.yml').read_bytes() == owned_compose:
            switch_worker_image(owned_compose, BASE_WORKER['imageTag'])
        health(5083)
        restored = services()
        if restored['api']['imageId'] != BASE_API['imageId'] or restored['worker']['imageId'] != BASE_WORKER['imageId']:
            raise RuntimeError('separate API/worker rollback image mismatch')
        update_env_tag((TAG, BASE_API['imageTag'].split(':')[1]), BASE_API['imageTag'].split(':')[1])
        result = {'ok': True, 'api': restored['api']['imageId'], 'worker': restored['worker']['imageId'], 'businessDataRolledBack': False, 'staticChangedAndPreserved': static_snapshot() != baseline['static'], 'onlyWorkerImageRestored': True}
        write_json(backup / 'rollback-result.json', result)
        event(backup, 'rollback-complete')
        return result
    finally:
        release_drain(backup)


def promote(manifest, drain_seconds=120):
    global OWNED_CANARY
    OWNED_CANARY = None
    if shutil.disk_usage(ROOT).free < 256 * 1024 * 1024:
        raise RuntimeError('insufficient free disk for release')
    mem = Path('/proc/meminfo')
    if mem.exists():
        available = re.search(r'(?m)^MemAvailable:\s+(\d+)\s+kB', mem.read_text())
        if not available or int(available.group(1)) < 512 * 1024:
            raise RuntimeError('wait for at least 512 MiB available memory before starting canary')
    required = database("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename IN ('LibraryMaterialHeads','LibraryEditions','LibraryCanonicalQuestions')")
    if required != '3': raise RuntimeError('Prepare the reviewed additive library schema first')
    retired = database("SELECT data_type || ':' || is_nullable || ':' || column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='Courses' AND column_name='Retired'")
    if retired != 'boolean:NO:false': raise RuntimeError('Prepare Courses.Retired boolean NOT NULL DEFAULT false first')
    old = services()
    for service, pin in [('api', BASE_API), ('worker', BASE_WORKER)]:
        if not old[service]['running'] or old[service]['imageId'] != pin['imageId'] or image_id(pin['imageTag']) != pin['imageId']:
            raise RuntimeError('live ' + service + ' moved from the reviewed baseline')
        if run(['docker', 'exec', old[service]['id'], 'sha256sum', '/app/Siyue.Server.dll']).split()[0] != pin['serverSha256']:
            raise RuntimeError('live ' + service + ' artifact changed')
    worker_runtime = old['worker']['runtime']
    if worker_runtime['workerConfig'].get('Worker__Concurrency') != '2' or worker_runtime['workerConfig'].get('Worker__DrainFile') != '/worker-control/drain' or not any(mount['Destination'] == '/worker-control' and mount['Source'] == str(ROOT / 'data/worker-control') and mount['RW'] is False for mount in worker_runtime['mounts']):
        raise RuntimeError('worker concurrency/drain mount differs from reviewed performance release')
    if sha(ROOT / 'compose.yml') != manifest['composeSha256']:
        raise RuntimeError('live compose changed from the reviewed performance configuration')
    snapshot = static_snapshot()
    if snapshot != manifest['staticBaseline']:
        raise RuntimeError('live static release moved; refresh baseline without overwriting it')
    original_env = (ROOT / '.env').read_bytes()
    if env_release(original_env)[1] != BASE_API['imageTag'].split(':')[1]:
        raise RuntimeError('live environment tag moved')
    original_nginx = SNIPPET.read_bytes()
    if b'127.0.0.1:5082' not in original_nginx or b'127.0.0.1:15090' in original_nginx:
        raise RuntimeError('unexpected nginx route baseline')
    if run(['docker', 'ps', '-aq', '--filter', 'name=^/' + CANARY + '$']).strip():
        raise RuntimeError('canary name already in use; inspect its existing receipt')
    backup = ROOT / 'backups' / (TAG + '-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    backup.mkdir(mode=0o700, parents=True)
    for path, name in [(ROOT / '.env', '.env'), (ROOT / 'compose.yml', 'compose.yml'), (SNIPPET, 'nginx.conf')]:
        private_write(backup / name, path.read_bytes())
    baseline = {'backup': str(backup), 'services': old, 'static': snapshot, 'composeSha256': sha(ROOT / 'compose.yml'), 'manifestSha256': sha(HERE / 'candidate.manifest.json')}
    write_json(backup / 'baseline.json', baseline)
    write_json(backup / 'candidate.manifest.json', manifest)
    report = {'ok': False, 'release': TAG, 'backup': str(backup), 'schemaMigration': False, 'businessDataRolledBack': False}
    original_compose = (ROOT / 'compose.yml').read_bytes()
    owned_compose = worker_image(original_compose, 'siyue-miniapp:' + TAG)
    changed = False
    try:
        event(backup, 'baseline-verified')
        inherited = runtime_hashes(BASE_WORKER['imageTag'], INHERITED)
        existing = run(['docker', 'images', '--no-trunc', '-q', 'siyue-miniapp:' + TAG]).strip()
        if not existing:
            run(['docker', 'build', '--pull=false', '--network=none', '-t', 'siyue-miniapp:' + TAG, '-f', str(HERE / 'Dockerfile'), str(HERE)], timeout=180)
        candidate_id = image_id('siyue-miniapp:' + TAG)
        hashes = runtime_hashes('siyue-miniapp:' + TAG, sorted(ARTIFACTS) + INHERITED)
        if any(hashes.get(item['path']) != item['sha256'] for item in manifest['artifacts']) or any(hashes.get(name) != digest for name, digest in inherited.items()):
            raise RuntimeError('candidate artifacts/runtime mismatch; existing release tag was not overwritten')
        base_layers = json.loads(run(['docker', 'image', 'inspect', '--format', '{{json .RootFS.Layers}}', BASE_WORKER['imageTag']]))
        layers = json.loads(run(['docker', 'image', 'inspect', '--format', '{{json .RootFS.Layers}}', 'siyue-miniapp:' + TAG]))
        if layers[:len(base_layers)] != base_layers or len(layers) != len(base_layers) + 2:
            raise RuntimeError('candidate must inherit all base layers and add exactly the two COPY layers')
        write_json(backup / 'candidate-image.json', {'imageId': candidate_id, 'artifacts': hashes})
        check_manifest()
        baseline_guard(baseline, old)
        with socket.socket() as probe: probe.bind(('127.0.0.1', 15090))
        compose('run', '-d', '--no-deps', '--name', CANARY, '-e', 'ASPNETCORE_URLS=http://127.0.0.1:15090', '-e', 'RunWorker=false', '-e', 'Database__Initialize=false', 'api', tag=TAG)
        canary = inspect_container(CANARY)
        write_json(backup / 'canary.json', {'id': canary['id'], 'imageId': canary['imageId']})
        if canary['imageId'] != candidate_id or canary['runWorker']:
            raise RuntimeError('canary does not match the candidate')
        OWNED_CANARY = {'id': canary['id'], 'imageId': canary['imageId']}
        health(15090)
        if not inspect_container(CANARY)['running']: raise RuntimeError('canary is not running')
        event(backup, 'canary-healthy')
        baseline_guard(baseline, old)
        acquire_drain(backup)
        # Set after marker ownership, before the potentially state-changing graceful stop.
        changed = True
        drain_and_stop(old['worker'], backup, drain_seconds)
        current = services()
        baseline_guard(baseline, old)
        if current['worker']['running']:
            raise RuntimeError('worker must be stopped before replacement')
        check_manifest()
        if image_id('siyue-miniapp:' + TAG) != candidate_id:
            raise RuntimeError('candidate tag changed')
        switch_worker_image(original_compose, 'siyue-miniapp:' + TAG)
        compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'worker', tag=TAG)
        health(5083)
        current = services()
        if current['worker']['imageId'] != candidate_id or current['api']['id'] != old['api']['id'] or current['worker']['runtime'] != old['worker']['runtime']:
            raise RuntimeError('worker switch or API baseline mismatch')
        event(backup, 'new-worker-healthy')
        baseline_guard(baseline, current, compose_bytes=owned_compose)
        candidate_nginx = original_nginx.replace(b'127.0.0.1:5082', b'127.0.0.1:15090')
        switch_nginx(candidate_nginx, original_nginx)
        event(backup, 'canary-receiving-traffic')
        update_env_tag((BASE_API['imageTag'].split(':')[1],), TAG)
        if image_id('siyue-miniapp:' + TAG) != candidate_id:
            raise RuntimeError('candidate tag changed before API replacement')
        compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'api', tag=TAG)
        health(5082)
        health(5083)
        current = services()
        if any(row['imageId'] != candidate_id for row in current.values()):
            raise RuntimeError('new service image mismatch')
        for item in current.values():
            if run(['docker', 'exec', item['id'], 'sha256sum', '/app/Siyue.Server.dll']).split()[0] != hashes['Siyue.Server.dll']:
                raise RuntimeError('new service artifact mismatch')
        switch_nginx(original_nginx, candidate_nginx)
        stop_canary(backup)
        if (ROOT / 'compose.yml').read_bytes() != owned_compose:
            raise RuntimeError('compose changed during deployment; preserve it')
        release_drain(backup)
        report.update(ok=True, imageId=candidate_id, services=current, staticAfter=static_snapshot(), staticChangedAndPreserved=static_snapshot() != baseline['static'])
        event(backup, 'release-complete')
        return report
    except Exception as error:
        report['failureType'] = type(error).__name__
        event(backup, 'release-failed', failureType=type(error).__name__)
        if changed:
            try:
                report['rollback'] = rollback(backup, drain_seconds)
            except Exception as recovery:
                report['rollbackFailureType'] = type(recovery).__name__
                report['operatorAction'] = 'Retry --rollback with this backup after active work drains or concurrent configuration is reviewed. Do not force-stop tasks or revert business data.'
        else:
            with contextlib.suppress(Exception):
                stop_canary(backup)
        raise RuntimeError('release stopped; private report: ' + str(backup / 'deployment.json')) from None
    finally:
        try:
            release_drain(backup)
        except Exception:
            report['drainCleanupBlocked'] = True
        write_json(backup / 'deployment.json', report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--rollback', type=Path)
    parser.add_argument('--drain-seconds', type=int, default=120)
    args = parser.parse_args()
    if not 1 <= args.drain_seconds <= 1800:
        parser.error('drain must be 1..1800 seconds')
    if not args.execute:
        if not args.rollback:
            check_manifest()
        print('Local validation only. No production action performed.')
        return
    if os.geteuid() != 0 or not ROOT.is_dir():
        raise RuntimeError('execute only on the reviewed server as root')
    with (ROOT / '.queue-review-deploy.lock').open('w') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(RuntimeError('termination requested')))
        signal.signal(signal.SIGINT, lambda *_: (_ for _ in ()).throw(RuntimeError('interruption requested')))
        if args.rollback:
            backup = args.rollback.resolve()
            if backup.parent != ROOT / 'backups' or not backup.name.startswith(TAG + '-'):
                raise RuntimeError('unexpected rollback directory')
            rollback(backup, args.drain_seconds)
        else:
            promote(check_manifest(), args.drain_seconds)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Stopped: ' + (str(error) if isinstance(error, (RuntimeError, ValueError)) else type(error).__name__))
        raise SystemExit(1)
