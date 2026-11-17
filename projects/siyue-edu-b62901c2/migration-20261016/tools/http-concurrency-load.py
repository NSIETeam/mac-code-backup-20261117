#!/usr/bin/env python3
"""Bounded loopback HTTP load against a dedicated synthetic Java/MySQL instance.
Uses genuine externally issued bearer tokens; never creates tokens or disables JWT checks.
Prints aggregate metrics only. Does not create/drop databases or alter deployment settings.
"""
import concurrent.futures
import hashlib
import http.client
import json
import math
import os
import pathlib
import secrets
import stat
import sys
import threading
import time
import urllib.parse


class LoadError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise LoadError(message)


def private_text(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                and not info.st_mode & 0o077 and info.st_size <= 131072,
                "Private input must be an owner-only regular file")
        with os.fdopen(descriptor, 'r', encoding='utf-8', closefd=False) as stream:
            return stream.read()
    finally:
        os.close(descriptor)


class Client:
    def __init__(self, base, origin, token=None):
        self.base = base
        self.origin = origin
        self.token = token
        self.connection = http.client.HTTPConnection(base.hostname, base.port, timeout=20)

    def request(self, method, path, body=None, key=None):
        headers = {'Accept': 'application/json', 'Origin': self.origin}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if key:
            headers['Idempotency-Key'] = key
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        started = time.perf_counter()
        try:
            self.connection.request(method, self.base.path.rstrip('/') + path, payload, headers)
            response = self.connection.getresponse()
            raw = response.read(2_000_001)
            elapsed = (time.perf_counter() - started) * 1000
            require(len(raw) <= 2_000_000, 'Oversized HTTP response')
            data = json.loads(raw)
            return response.status, data, elapsed
        except (OSError, ValueError, http.client.HTTPException) as error:
            self.connection.close()
            raise LoadError('HTTP transport or response parsing failed') from error

    def data(self, method, path, body=None, key=None):
        status, result, elapsed = self.request(method, path, body, key)
        require(status == 200 and result.get('code') == 'OK' and result.get('data') is not None,
                'HTTP request rejected (status ' + str(status) + ')')
        return result['data'], elapsed

    def close(self):
        self.connection.close()


def preflight(config, base):
    clients = []
    anonymous = Client(base, config['webOrigin'])
    try:
        require(anonymous.request('GET', '/identity/me')[0] == 401,
                'Unauthenticated identity request must be rejected')
        oidc, _ = anonymous.data('GET', '/public/oidc-config')
        require(oidc['issuer'] == config['issuer'] and oidc['apiAudience'] == config['audience'],
                'Live OIDC settings differ from the acceptance manifest')
        for actor in config['actors']:
            token = private_text(actor['tokenFile']).strip()
            require(len(token) < 16384 and token.count('.') == 2, 'Invalid token file')
            client = Client(base, config['webOrigin'], token)
            clients.append(client)
            identity, _ = client.data('GET', '/identity/me')
            require(identity['principalId'] == actor['principalId'] and identity['scope'] == 'campus'
                    and identity['campusIds'] == [config['campusId']],
                    'Identity is not the dedicated synthetic campus account')
            validate_load_grants(set(identity['permissions']))
        require(len({a['principalId'] for a in config['actors']}) == len(clients), 'Actors must be distinct')
        initial = [client.data('GET', '/academic/students/archive?page=1&size=1')[0]['total'] for client in clients]
        require(len(set(initial)) == 1, 'Actors must see the same isolated campus cohort')
        return [client.token for client in clients], initial[0]
    finally:
        anonymous.close()
        for client in clients:
            client.close()


def validate_load_grants(grants):
    require('student.read' in grants and bool(grants & {'student.create', 'student.write'})
            and grants <= {'student.read', 'student.create', 'student.write'},
            'Synthetic actor must have only the dedicated read/write permissions')


def latency_summary(values):
    return {'requests': len(values), 'p50Ms': percentile(values, .5),
            'p95Ms': percentile(values, .95), 'p99Ms': percentile(values, .99),
            'maxMs': round(max(values), 3)}


def percentile(values, quantile):
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * quantile) - 1)], 3)


def actor_index(mode, kind, index, count):
    require(mode in ('single', 'multi'), 'Actor mode must be single or multi')
    return 0 if mode == 'single' or kind == 'replay' else (index // 4) % count


def round_load(config, base, tokens, concurrency, round_index, run_id):
    total = config['requestsPerRound']
    # Mix: 50% reads, 25% distinct writes, 25% same-key retries (one real write).
    barrier = threading.Barrier(concurrency + 1)
    results = []
    lock = threading.Lock()
    stop = threading.Event()
    next_index = 0
    replay_key = 'http-load-' + run_id + '-replay-' + str(round_index)
    prefix = '并发合成' + run_id + '-' + str(round_index) + '-'

    def student(index):
        return {'name': prefix + str(index), 'grade': '四年级',
                'phone': '139' + str(index % 100000000).zfill(8),
                'campusId': config['campusId'], 'originLeadId': None}

    def worker():
        nonlocal next_index
        connections = [Client(base, config['webOrigin'], token) for token in tokens]
        try:
            barrier.wait(timeout=30)
            while not stop.is_set():
                with lock:
                    index = next_index
                    next_index += 1
                if index >= total:
                    return
                kind = 'read' if index % 4 < 2 else 'write' if index % 4 == 2 else 'replay'
                actor = actor_index(config.get('actorMode', 'multi'), kind, index, len(tokens))
                client = connections[actor]
                if kind == 'read':
                    result, elapsed = client.data('GET', '/academic/students/archive?page=1&size=20')
                    require(isinstance(result.get('total'), int), 'Archive result is incomplete')
                    value = None
                else:
                    result, elapsed = client.data('POST', '/academic/students',
                        student(total if kind == 'replay' else index),
                        replay_key if kind == 'replay' else 'http-load-' + run_id + '-' + str(round_index) + '-' + str(index))
                    value = result.get('id')
                    require(isinstance(value, str) and value, 'Write result is incomplete')
                with lock:
                    results.append((kind, value, elapsed))
        except Exception:
            stop.set()
            raise
        finally:
            for client in connections:
                client.close()

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(worker) for _ in range(concurrency)]
        barrier.wait(timeout=30)
        for future in futures:
            future.result(timeout=180)
    seconds = time.perf_counter() - started
    require(len(results) == total, 'Not all bounded requests completed')
    writes = {value for kind, value, _ in results if kind == 'write'}
    replay = {value for kind, value, _ in results if kind == 'replay'}
    require(len(writes) == total // 4 and len(replay) == 1 and not writes & replay,
            'Idempotency or unique-write count failed')
    latencies = [elapsed for _, _, elapsed in results]
    return {'actorMode': config.get('actorMode', 'multi'), 'concurrency': concurrency, 'requests': total, 'uniqueWrites': len(writes) + 1,
            'elapsedSeconds': round(seconds, 3), 'completedRequestsPerSecond': round(total / seconds, 2),
            'p50Ms': percentile(latencies, .5), 'p95Ms': percentile(latencies, .95),
            'p99Ms': percentile(latencies, .99), 'maxMs': round(max(latencies), 3), 'errors': 0,
            'byOperation': {kind: latency_summary([elapsed for item, _, elapsed in results if item == kind])
                            for kind in ('read', 'write', 'replay')}}


def main():
    require(len(sys.argv) == 2, 'Usage: http-concurrency-load.py PRIVATE_MANIFEST_JSON')
    config = json.loads(private_text(sys.argv[1]))
    require(config.get('syntheticOnly') is True and config.get('allowSyntheticWrites') is True,
            'Explicit isolated synthetic-write manifest is required')
    base = urllib.parse.urlsplit(config['apiBase'])
    require(base.scheme == 'http' and base.hostname == '127.0.0.1' and base.port
            and base.path.rstrip('/') == '/api/v2' and not base.query and not base.fragment
            and not base.username, 'Only direct loopback Java HTTP /api/v2 is allowed')
    db = urllib.parse.urlsplit(config['jdbcUrl'].removeprefix('jdbc:'))
    require(db.scheme == 'mysql' and db.hostname == '127.0.0.1' and db.port
            and db.path.endswith('_test') and db.path.startswith('/siyue_http_load_'),
            'Dedicated loopback siyue_http_load_*_test database is required')
    require(config['campusId'].startswith('http-load-') and config['issuer'].startswith('https://')
            and all(a['principalId'].startswith('http-load-') for a in config['actors'])
            and 2 <= len(config['actors']) <= 8, 'Dedicated synthetic identities are required')
    require(isinstance(config['repeats'], int) and isinstance(config['requestsPerRound'], int)
            and 1 <= config['repeats'] <= 10 and config['requestsPerRound'] % 4 == 0
            and 32 <= config['requestsPerRound'] <= 2000 and config['concurrencyLevels']
            and len(config['concurrencyLevels']) <= 8
            and all(isinstance(level, int) and 1 <= level <= min(128, config['requestsPerRound']) for level in config['concurrencyLevels']),
            'Load settings exceed the bounded limits')
    require(config.get('actorMode', 'multi') in ('single', 'multi'), 'Actor mode must be single or multi')
    tokens, before = preflight(config, base)
    run_id = secrets.token_hex(6)
    metrics = []
    for concurrency in config['concurrencyLevels']:
        for _ in range(config['repeats']):
            metric = round_load(config, base, tokens, concurrency, len(metrics), run_id)
            metrics.append(metric)
            print(json.dumps({'round': len(metrics), **metric}), flush=True)
    reader = Client(base, config['webOrigin'], tokens[0])
    try:
        after = reader.data('GET', '/academic/students/archive?page=1&size=1')[0]['total']
    finally:
        reader.close()
    expected = sum(row['uniqueWrites'] for row in metrics)
    require(after - before == expected, 'Final scoped student delta differs from unique writes')
    print(json.dumps({'passed': True, 'actorMode': config.get('actorMode', 'multi'), 'rounds': len(metrics), 'uniqueWriteDelta': expected,
                      'scriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                      'scope': 'loopback HTTP + actual JWT validation + isolated MySQL; not production capacity'}))


if __name__ == '__main__':
    try:
        main()
    except (LoadError, KeyError, TypeError, ValueError, OSError, concurrent.futures.TimeoutError,
            threading.BrokenBarrierError) as failure:
        # Never serialize request bodies, tokens, credentials, or raw response/error objects.
        print(json.dumps({'passed': False, 'errorType': type(failure).__name__,
                          'message': str(failure) if isinstance(failure, LoadError) else 'Load setup or bounded execution failed'}))
        sys.exit(1)
