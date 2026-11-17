#!/usr/bin/env python3
"""Run one bounded HTTP acceptance against an explicitly hashed jar and fresh test schema."""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.parse
import zipfile

TOOLS = Path(__file__).resolve().parent
LOAD = TOOLS / 'http-concurrency-load.py'
COUNTS_SOURCE = TOOLS / 'HttpLoadAggregateCounts.java'
PROFILE_CHECK = TOOLS / 'verify-concurrency-profile.py'
spec = importlib.util.spec_from_file_location('concurrency_profile', PROFILE_CHECK)
profile_checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile_checker)


def private_file(path):
    target = Path(path)
    info = target.lstat()
    if not stat.S_ISREG(info.st_mode) or target.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Private inputs must be owner-only regular files')
    return target.resolve(strict=True)


def read_private_json(path):
    return json.loads(private_file(path).read_text())


def validate_inputs(jar, sha256, runtime_path, profile_path, manifest_path, schema_nonce):
    jar_path = Path(jar)
    if not jar_path.is_absolute() or jar_path.is_symlink() or not jar_path.is_file() or jar_path.suffix != '.jar':
        raise ValueError('An absolute local Boot jar is required')
    jar_path = jar_path.resolve(strict=True)
    if not re.fullmatch(r'[a-f0-9]{64}', sha256):
        raise ValueError('An explicit lowercase SHA-256 is required')
    if hashlib.sha256(jar_path.read_bytes()).hexdigest() != sha256:
        raise ValueError('Boot jar SHA-256 does not match')
    runtime_path = private_file(runtime_path)
    profile_path = private_file(profile_path)
    manifest_path = private_file(manifest_path)
    profile_result = profile_checker.verify(jar_path, runtime_path, profile_path)
    if profile_result['artifactSha256'] != sha256:
        raise ValueError('Profile artifact SHA-256 differs from the explicit SHA-256')
    runtime = json.loads(runtime_path.read_text())
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(runtime, dict) or not isinstance(manifest, dict):
        raise ValueError('Runtime and workload manifests must be JSON objects')
    if not re.fullmatch(r'[a-f0-9]{10}', schema_nonce):
        raise ValueError('A fresh ten-character schema nonce is required')
    expected_schema = 'siyue_http_load_' + schema_nonce + '_test'
    jdbc_raw = str(runtime.get('SIYUE_MYSQL_URL', ''))
    jdbc = urllib.parse.urlsplit(jdbc_raw.removeprefix('jdbc:'))
    manifest_jdbc = urllib.parse.urlsplit(str(manifest.get('jdbcUrl', '')).removeprefix('jdbc:'))
    if (jdbc.scheme != 'mysql' or jdbc.hostname != '127.0.0.1' or not jdbc.port or jdbc.username
            or jdbc.path != '/' + expected_schema or manifest_jdbc.scheme != 'mysql'
            or manifest_jdbc.hostname != '127.0.0.1' or manifest_jdbc.port != jdbc.port
            or manifest_jdbc.path != '/' + expected_schema):
        raise ValueError('Runtime and workload must target the same fresh dedicated _test schema')
    if manifest.get('syntheticOnly') is not True or manifest.get('allowSyntheticWrites') is not True:
        raise ValueError('Explicit synthetic-only write authorization is required')
    api = urllib.parse.urlsplit(str(manifest.get('apiBase', '')))
    if (api.scheme != 'http' or api.hostname != '127.0.0.1' or not api.port
            or api.path.rstrip('/') != '/api/v2' or api.query or api.fragment or api.username):
        raise ValueError('Only direct loopback Java HTTP /api/v2 is allowed')
    issuer = urllib.parse.urlsplit(str(manifest.get('issuer', '')))
    origin = urllib.parse.urlsplit(str(manifest.get('webOrigin', '')))
    actors = manifest.get('actors')
    if (issuer.scheme != 'https' or issuer.hostname not in ('127.0.0.1', 'localhost')
            or origin.scheme != 'https' or origin.hostname not in ('127.0.0.1', 'localhost')
            or origin.path not in ('', '/') or origin.query or origin.fragment or origin.username
            or manifest.get('audience') != 'siyue-api' or not str(manifest.get('campusId', '')).startswith('http-load-')
            or not isinstance(actors, list) or not 2 <= len(actors) <= 8
            or any(not isinstance(actor, dict) or not str(actor.get('principalId', '')).startswith('http-load-')
                   for actor in actors)
            or len({actor['principalId'] for actor in actors}) != len(actors)):
        raise ValueError('Manifest must use the dedicated synthetic OIDC actors and campus')
    for actor in actors:
        private_file(actor.get('tokenFile', ''))
    if manifest.get('actorMode', 'multi') not in ('single', 'multi'):
        raise ValueError('Actor mode must be single or multi')
    levels = manifest.get('concurrencyLevels')
    repeats = manifest.get('repeats')
    request_count = manifest.get('requestsPerRound')
    if (type(repeats) is not int or not 1 <= repeats <= 10 or type(request_count) is not int
            or not 32 <= request_count <= 2000 or request_count % 4
            or not isinstance(levels, list) or not 1 <= len(levels) <= 8
            or any(type(level) is not int or not 1 <= level <= min(128, request_count) for level in levels)):
        raise ValueError('Workload exceeds the bounded concurrency, repeat, or request limits')
    if runtime.get('SIYUE_CORE_BIND') not in ('127.0.0.1', 'localhost'):
        raise ValueError('Java must bind only to loopback')
    if str(runtime.get('SIYUE_CORE_PORT')) != str(api.port):
        raise ValueError('Java bind port and workload API port differ')
    if runtime.get('SIYUE_MYSQL_USER') in (None, '') or runtime.get('SIYUE_MYSQL_PASSWORD') in (None, ''):
        raise ValueError('A dedicated database account is required')
    if (runtime.get('SIYUE_OIDC_ISSUER') != manifest.get('issuer')
            or runtime.get('SIYUE_OIDC_AUDIENCE') != manifest.get('audience')
            or runtime.get('SIYUE_WEB_ORIGIN') != manifest.get('webOrigin')):
        raise ValueError('Java runtime and workload OIDC settings differ')
    return jar_path, runtime_path, profile_path, manifest_path, runtime, manifest, api, expected_schema


def extract_boot(jar, destination):
    destination.mkdir(mode=0o700)
    with zipfile.ZipFile(jar) as archive:
        names = [item for item in archive.infolist()
                 if item.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/'))]
        for item in names:
            relative = Path(item.filename)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Unsafe path inside Boot jar')
            target = destination / relative
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Unsafe path inside Boot jar')
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                target.write_bytes(archive.read(item))
    classes = destination / 'BOOT-INF/classes'
    libraries = destination / 'BOOT-INF/lib'
    if not classes.is_dir() or not list(libraries.glob('*.jar')):
        raise ValueError('Boot jar is missing application classes or dependencies')
    return classes, libraries


def aggregate(java_home, classes, libraries, runtime_path, manifest_path, result_dir, label):
    helper_dir = result_dir / 'aggregate-helper'
    helper_dir.mkdir(mode=0o700, exist_ok=True)
    classpath = str(classes) + ':' + str(libraries / '*') + ':' + str(helper_dir)
    compiled = subprocess.run([str(java_home / 'bin/javac'), '--release', '17', '-cp', classpath,
                               '-d', str(helper_dir), str(COUNTS_SOURCE)],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if compiled.returncode:
        raise RuntimeError('Aggregate-count helper compilation failed')
    result = subprocess.run([str(java_home / 'bin/java'), '-cp', classpath,
                             'HttpLoadAggregateCounts', str(runtime_path), str(manifest_path)],
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Aggregate-count query failed at ' + label)
    counts = json.loads(result.stdout)
    if set(counts) != {'students', 'idempotency', 'audit'} or any(type(value) is not int or value < 0 for value in counts.values()):
        raise RuntimeError('Aggregate-count response is invalid')
    return counts


def wait_ready(api, process, log, expected_issuer, expected_audience):
    path = api.path.rstrip('/') + '/public/oidc-config'
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Java process exited before readiness; inspect private server log')
        try:
            connection = http.client.HTTPConnection('127.0.0.1', api.port, timeout=2)
            connection.request('GET', path, headers={'Accept': 'application/json'})
            response = connection.getresponse()
            payload = json.loads(response.read(131073))
            connection.close()
            data = payload.get('data', {})
            if response.status == 200 and payload.get('code') == 'OK':
                if data.get('issuer') != expected_issuer or data.get('apiAudience') != expected_audience:
                    raise RuntimeError('Live OIDC settings differ from the private workload manifest')
                return
        except RuntimeError:
            raise
        except Exception:
            time.sleep(1)
    raise RuntimeError('Java readiness deadline expired; inspect private server log')


def run(args):
    jar, runtime_path, profile_path, manifest_path, runtime, manifest, api, schema = validate_inputs(
        args.jar, args.sha256, args.runtime, args.profile, args.manifest, args.schema_nonce)
    java_home = Path(args.java_home).resolve(strict=True)
    if not (java_home / 'bin/java').is_file() or not (java_home / 'bin/javac').is_file():
        raise ValueError('Java 17 JDK home must contain java and javac')
    jvm_args = []
    if args.jvm_args:
        jvm_args = read_private_json(args.jvm_args)
        if not isinstance(jvm_args, list) or any(not isinstance(value, str) for value in jvm_args):
            raise ValueError('JVM args file must be a private JSON string array')
        permitted = ('-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2')
        allowed = all(value in permitted or value.startswith(('-Djavax.net.ssl.trustStore=',
                                                             '-Djavax.net.ssl.trustStorePassword='))
                      for value in jvm_args)
        if not allowed or len([value for value in jvm_args if value.startswith('-Djavax.net.ssl.trustStore=')]) > 1:
            raise ValueError('JVM args may only set bounded memory, CPU count, and the private OIDC trust store')
    result_dir = Path(args.results_dir)
    if not result_dir.is_absolute() or result_dir.exists():
        raise ValueError('Result directory must be a new absolute path')
    result_dir.mkdir(mode=0o700, parents=False)
    os.chmod(result_dir, 0o700)
    server_log = result_dir / 'java-server.log'
    load_log = result_dir / 'http-load.log'
    boot_dir = result_dir / 'boot-extracted'
    classes, libraries = extract_boot(jar, boot_dir)
    args_file = result_dir / 'java.args'
    args_file.write_text('\n'.join(json.dumps(value) for value in [*jvm_args, '-jar', str(jar)]) + '\n')
    os.chmod(args_file, 0o600)
    api_port = api.port
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', api_port)) == 0:
            raise ValueError('Dedicated Java HTTP port is already in use')
    process_env = {key: value for key, value in os.environ.items()
                   if not key.startswith(('SIYUE_', 'SPRING_'))
                   and key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    for key, value in runtime.items():
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key) or not isinstance(value, (str, int)) or isinstance(value, bool):
            raise ValueError('Runtime environment contains an unsupported setting')
        if '\n' in str(value) or '\r' in str(value) or '\x00' in str(value):
            raise ValueError('Runtime environment contains an invalid setting')
        process_env[key] = str(value)
    with server_log.open('x') as log_stream:
        os.chmod(server_log, 0o600)
        process = subprocess.Popen([str(java_home / 'bin/java'), '@' + str(args_file)],
                                   env=process_env, stdout=log_stream, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            wait_ready(api, process, server_log, manifest['issuer'], manifest['audience'])
            before = aggregate(java_home, classes, libraries, runtime_path, manifest_path, result_dir, 'before')
            if before != {'students': 0, 'idempotency': 0, 'audit': 0}:
                raise RuntimeError('Dedicated schema is not fresh; no HTTP load was sent')
            with load_log.open('x') as output:
                os.chmod(load_log, 0o600)
                load = subprocess.run([sys.executable, str(LOAD), str(manifest_path)],
                                      stdout=output, stderr=subprocess.STDOUT, check=False)
            if load.returncode:
                raise RuntimeError('HTTP load failed; inspect private load log')
            lines = [line for line in load_log.read_text().splitlines() if line.strip()]
            if not lines:
                raise RuntimeError('HTTP load produced no final summary')
            try:
                summary = json.loads(lines[-1])
            except json.JSONDecodeError:
                raise RuntimeError('HTTP load final summary is not JSON') from None
            if not isinstance(summary, dict) or summary.get('passed') is not True:
                raise RuntimeError('HTTP load did not produce a passing summary')
            repeats = manifest.get('repeats')
            levels = manifest.get('concurrencyLevels')
            requests = manifest.get('requestsPerRound')
            if (type(repeats) is not int or type(requests) is not int or not isinstance(levels, list)
                    or not levels or summary.get('rounds') != repeats * len(levels)
                    or summary.get('uniqueWriteDelta') != repeats * len(levels) * (requests // 4 + 1)
                    or summary.get('actorMode', 'multi') != manifest.get('actorMode', 'multi')):
                raise RuntimeError('HTTP load rounds or unique-write summary differs from the manifest')
            after = aggregate(java_home, classes, libraries, runtime_path, manifest_path, result_dir, 'after')
            delta = summary.get('uniqueWriteDelta')
            if type(delta) is not int or delta < 1 or after != {key: delta for key in ('students', 'idempotency', 'audit')}:
                raise RuntimeError('Student/idempotency/audit deltas do not match unique creates')
            report = {'passed': True, 'artifactSha256': args.sha256, 'schema': schema,
                      'profileVerified': True, 'actorMode': manifest.get('actorMode', 'multi'), 'uniqueWriteDelta': delta, 'rounds': summary['rounds'],
                      'loadSummary': summary, 'capacityClaim': False}
            report_path = result_dir / 'summary.json'
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            os.chmod(report_path, 0o600)
            return report
        finally:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, help='Absolute frozen Boot jar path')
    parser.add_argument('--sha256', required=True, help='Expected lowercase SHA-256 of that jar')
    parser.add_argument('--runtime', required=True, help='0600 JSON app environment, including all five concurrency settings')
    parser.add_argument('--profile', required=True, help='0600 concurrency profile with the same jar SHA and five limits')
    parser.add_argument('--manifest', required=True, help='0600 synthetic workload manifest and private token file references')
    parser.add_argument('--schema-nonce', required=True, help='Ten-hex nonce used in the new siyue_http_load_<nonce>_test database')
    parser.add_argument('--java-home', required=True, help='Absolute Java 17 JDK path')
    parser.add_argument('--results-dir', required=True, help='New absolute private output directory; must not exist')
    parser.add_argument('--jvm-args', help='Optional 0600 JSON string array, e.g. trust-store arguments')
    args = parser.parse_args()
    try:
        report = run(args)
        print(json.dumps({key: report[key] for key in ('passed', 'artifactSha256', 'schema', 'profileVerified',
                                                       'uniqueWriteDelta', 'rounds', 'capacityClaim')}))
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print('HTTP acceptance stopped: ' + str(error), file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
