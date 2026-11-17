#!/usr/bin/env python3
"""Download an explicitly authorized private rehearsal snapshot; print metadata only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ssh-config', required=True)
    p.add_argument('--destination', required=True)
    a = p.parse_args()
    dest = Path(a.destination)
    if dest.is_symlink():
        raise SystemExit('Destination must not be a symlink')
    dest.mkdir(mode=0o700, parents=True, exist_ok=True)
    if dest.stat().st_uid != os.getuid():
        raise SystemExit('Destination must belong to current user')
    dest.chmod(0o700)
    result = subprocess.run(['ssh', '-F', a.ssh_config, '-o', 'BatchMode=yes', '-o',
                             'ConnectTimeout=10', 'siyue-test',
                             'python3 - --sqlite /var/lib/siyue-crm-auth/business.sqlite'],
                            input=Path(__file__).with_name('export_source.py').read_bytes(),
                            capture_output=True, timeout=40)
    if result.returncode:
        print(json.dumps({'downloaded': False, 'exit': result.returncode,
                          'connectionTimeout': b'timed out' in result.stderr}))
        return 1
    data = json.loads(result.stdout)
    if data.get('schema') != 'siyue.legacy-business/v1' or type(data.get('revision')) is not int:
        raise SystemExit('Invalid snapshot envelope')
    content_hash = hashlib.sha256(result.stdout).hexdigest()
    path = dest / ('business-revision-' + str(data['revision']) + '-' + content_hash[:12] + '.json')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(result.stdout)
        f.flush()
        os.fsync(f.fileno())
    print(json.dumps({'downloaded': True, 'path': str(path), 'revision': data['revision'],
                      'fileSha256': content_hash, 'bytes': len(result.stdout)}))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except subprocess.TimeoutExpired:
        print('{"downloaded":false,"connectionTimeout":true}')
        sys.exit(1)
