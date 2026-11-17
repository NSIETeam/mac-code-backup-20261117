#!/usr/bin/env python3
"""Combine only feedback and matching audit facts with a private business snapshot, never auth credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def read_private(path):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError('A regular absolute private source is required')
    stat = path.stat()
    if stat.st_uid != os.geteuid() or stat.st_mode & 0o077 or stat.st_size > 64 * 1024 * 1024:
        raise ValueError('Source owner, permissions or size invalid')
    return path.read_bytes()


def reject_credentials(value):
    if isinstance(value, dict):
        if {'passwordHash', 'sessions', 'accessToken', 'refreshToken'}.intersection(value):
            raise ValueError('Authentication credentials are not business data')
        for child in value.values():
            reject_credentials(child)
    elif isinstance(value, list):
        for child in value:
            reject_credentials(child)


def combine(business_bytes, auth_bytes):
    business = json.loads(business_bytes)
    auth = json.loads(auth_bytes)
    reject_credentials(business)
    if business.get('schema') != 'siyue.legacy-business/v1' or not isinstance(business.get('state'), dict):
        raise ValueError('Unsupported business envelope')
    if auth.get('schemaVersion') != 1 or not isinstance(auth.get('uiAnnotations', []), list):
        raise ValueError('Unsupported auth source')
    if 'authFeedback' in business['state']:
        raise ValueError('Business envelope already contains feedback')
    fields = {'id', 'page', 'pageTitle', 'elementKind', 'elementLabel', 'selector', 'issue', 'category',
              'workflowName', 'flowDefinition', 'desiredFlowDefinition', 'status', 'createdBy',
              'createdByName', 'createdAt', 'updatedAt'}
    annotations = auth.get('uiAnnotations', [])
    if any(not isinstance(row, dict) or set(row) - fields for row in annotations):
        raise ValueError('Unexpected annotation fields')
    audit = []
    for event in auth.get('audit', []):
        if event.get('action') not in {'ui.annotation.create', 'ui.annotation.update'} or event.get('outcome') != 'success':
            continue
        allowed_details = {'page', 'elementKind'} if event['action'] == 'ui.annotation.create' else {'status'}
        if not isinstance(event.get('details'), dict) or set(event['details']) - allowed_details:
            raise ValueError('Unexpected feedback audit details')
        # Only provenance needed to reconstruct the history; omit account snapshots, IP and request bodies.
        audit.append({key: event[key] for key in ('id', 'at', 'requestId', 'actorId', 'action', 'outcome', 'targetId', 'details')})
    business['state']['authFeedback'] = {
        'schema': 'siyue.legacy-auth-feedback/v1',
        'authSourceSha256': hashlib.sha256(auth_bytes).hexdigest(),
        'businessSourceSha256': hashlib.sha256(business_bytes).hexdigest(),
        'annotations': annotations, 'audit': audit,
    }
    return business


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('business', 'auth', 'out'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    combined = combine(read_private(args.business), read_private(args.auth))
    out = Path(args.out)
    if not out.is_absolute() or out.parent.is_symlink() or not out.parent.is_dir():
        raise ValueError('Existing private output directory required')
    if out.parent.stat().st_uid != os.geteuid() or out.parent.stat().st_mode & 0o077:
        raise ValueError('Output directory must be private and owned by the operator')
    data = (json.dumps(combined, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    feedback = combined['state']['authFeedback']
    print(json.dumps({'sha256': hashlib.sha256(data).hexdigest(), 'annotations': len(feedback['annotations']),
                      'auditEvents': len(feedback['audit']), 'credentialsExported': False}))


if __name__ == '__main__':
    main()
