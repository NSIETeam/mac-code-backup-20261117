#!/usr/bin/env python3
"""Refresh public schemas from the production contract; no credentials or business writes."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
URL = 'https://www.siyuejn.com/miniapp/openapi/agent-v1.json'

def digest(value):
    return hashlib.sha256(value).hexdigest()

def main():
    files = ['openapi.json', 'operations.json', 'tools.json']
    before = {name: (ROOT / name).read_bytes() for name in files}
    with urlopen(URL, timeout=20) as response:
        if response.url != URL:
            raise RuntimeError('Unexpected contract redirect')
        raw = response.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise RuntimeError('Contract exceeds size limit')
    spec = json.loads(raw)
    if not spec.get('openapi', '').startswith('3.1.') or spec.get('servers') != [{'url': 'https://www.siyuejn.com/miniapp'}]:
        raise RuntimeError('Unexpected contract version or server')
    operations = []
    for path, methods in spec['paths'].items():
        if not path.startswith('/api/'):
            raise RuntimeError('Unexpected business route')
        for method, operation in methods.items():
            if method not in ('get', 'post', 'put', 'patch', 'delete'):
                raise RuntimeError('Unsupported contract method')
            metadata = operation['x-agent']
            if metadata['scope'] not in ('read', 'teaching:write', 'documents:write', 'printing:write', 'organization:write') or metadata['retry'] not in ('read', 'idempotent', 'revision', 'reconcile', 'never'):
                raise RuntimeError('Unknown authorization or retry semantics')
            operations.append({'id': operation['operationId'], 'method': method.upper(), 'path': path, 'summary': operation['summary'], **metadata})
    if not operations or len({item['id'] for item in operations}) != len(operations):
        raise RuntimeError('Empty or duplicate operations')
    # Preserve the exact prior files; do not overwrite another task's concurrent edits.
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')
    backup = PROJECT / '.local' / 'acceptance' / ('agent-contract-sync-' + stamp)
    backup.mkdir(parents=True, exist_ok=False)
    for name, content in before.items():
        (backup / name).write_bytes(content)
        if (ROOT / name).read_bytes() != content:
            raise RuntimeError('Contract files changed during refresh; retry after review')
    (ROOT / 'openapi.json').write_text(json.dumps(spec, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (ROOT / 'operations.json').write_text(json.dumps(operations, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    subprocess.run([sys.executable, str(ROOT / 'build-tools.py')], check=True)
    tools = json.loads((ROOT / 'tools.json').read_text())
    assert {item['id'] for item in operations} == {item['name'] for item in tools}
    report = {'source': URL, 'operationCount': len(operations), 'credentialIssued': False, 'businessWrites': False,
              'previousHashes': {name: digest(content) for name, content in before.items()},
              'currentHashes': {name: digest((ROOT / name).read_bytes()) for name in files}}
    (backup / 'sync-result.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'operations': len(operations), 'backup': str(backup), 'businessWrites': False}))

if __name__ == '__main__':
    main()
