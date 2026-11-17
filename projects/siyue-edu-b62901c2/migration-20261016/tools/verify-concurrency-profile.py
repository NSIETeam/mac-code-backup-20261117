#!/usr/bin/env python3
"""Check the exact artifact and explicit runtime limits before repeating or deploying a load-tested profile."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex

KEYS = {'SIYUE_JDBC_MAX_POOL_SIZE', 'SIYUE_HTTP_MAX_THREADS', 'SIYUE_HTTP_MIN_SPARE_THREADS',
        'SIYUE_HTTP_MAX_CONNECTIONS', 'SIYUE_HTTP_ACCEPT_COUNT'}


def verify(jar, runtime, profile):
    specification = json.loads(Path(profile).read_text())
    if specification.get('schema') != 'siyue.concurrency-profile/v1':
        raise ValueError('Unsupported concurrency profile')
    expected = specification.get('limits')
    if not isinstance(expected, dict) or set(expected) != KEYS:
        raise ValueError('All five explicit concurrency limits are required')
    if any(type(value) is not int or value < 1 for value in expected.values()):
        raise ValueError('Concurrency limits must be positive integers')
    if expected['SIYUE_HTTP_MIN_SPARE_THREADS'] > expected['SIYUE_HTTP_MAX_THREADS']:
        raise ValueError('Spare threads exceed the maximum')
    if expected['SIYUE_HTTP_MAX_CONNECTIONS'] < expected['SIYUE_HTTP_MAX_THREADS']:
        raise ValueError('Connection limit is below the thread limit')
    expected_hash = specification.get('artifactSha256', '')
    if not isinstance(expected_hash, str) or not re.fullmatch('[a-f0-9]{64}', expected_hash):
        raise ValueError('Verified artifact hash is required')
    actual_hash = hashlib.sha256(Path(jar).read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        raise ValueError('Artifact differs from the profile')
    runtime_text = Path(runtime).read_text()
    actual = {}
    if runtime_text.lstrip().startswith('{'):
        runtime_values = json.loads(runtime_text)
        if not isinstance(runtime_values, dict):
            raise ValueError('Runtime JSON must be an object')
        for key in KEYS:
            if key in runtime_values:
                value = runtime_values[key]
                if isinstance(value, str) and re.fullmatch('[0-9]+', value):
                    value = int(value)
                if type(value) is not int or value < 1:
                    raise ValueError('Invalid concurrency setting: ' + key)
                actual[key] = value
    else:
        for line in runtime_text.splitlines():
            if not line.strip() or line.lstrip().startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            key = key.strip()
            if key not in KEYS:
                continue  # Never parse, execute or include credentials in the result.
            if key in actual:
                raise ValueError('Duplicate concurrency setting: ' + key)
            values = shlex.split(value, comments=True)
            if len(values) != 1 or not re.fullmatch('[0-9]+', values[0]):
                raise ValueError('Invalid concurrency setting: ' + key)
            actual[key] = int(values[0])
    if actual != expected:
        mismatch = sorted(key for key in KEYS if actual.get(key) != expected[key])
        raise ValueError('Runtime concurrency profile mismatch: ' + ', '.join(mismatch))
    return {'schema': specification['schema'], 'artifactSha256': actual_hash,
            'limits': actual, 'configurationMatches': True,
            'capacityVerified': False, 'note': 'Configuration check only; matching load results remain required'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jar', 'runtime', 'profile'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.jar, args.runtime, args.profile)))
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, 'Concurrency profile rejected: ' + str(error) + '\n')
