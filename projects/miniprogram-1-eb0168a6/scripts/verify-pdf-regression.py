#!/usr/bin/env python3
"""Run real PDF imports against an explicitly supplied isolated test class.

Source PDFs and credentials are not committed. This creates draft imports only;
it never publishes handouts or submits print jobs. The manifest fixes input hashes
and expected lecture sequences. Block counts must remain stable across rounds.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--api-base', required=True, help='Isolated service URL, ending in /api')
parser.add_argument('--token-file', required=True, type=Path)
parser.add_argument('--test-class', required=True, type=uuid.UUID)
parser.add_argument('--course', required=True, type=uuid.UUID)
parser.add_argument('--fixtures', required=True, type=Path)
parser.add_argument('--rounds', type=int, default=2)
parser.add_argument('--report', required=True, type=Path)

def main():
    args = parser.parse_args()
    if not 2 <= args.rounds <= 5: parser.error('Use 2–5 rounds to check repeatability.')
    manifest = json.loads((Path(__file__).parents[1] / 'tests/fixtures/pdf-handouts/manifest.json').read_text())
    sources = {}
    for case in manifest:
        content = (args.fixtures / case['file']).read_bytes()
        if hashlib.sha256(content).hexdigest() != case['sha256']:
            raise ValueError('Fixture hash mismatch: ' + case['file'])
        sources[case['file']] = content
    token = args.token_file.read_text().strip()
    def call(path, body=None, content_type='application/json'):
        request = urllib.request.Request(args.api_base.rstrip('/')+path, data=body,
            headers={'Authorization':'Bearer '+token,'Content-Type':content_type})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read())
    results = []
    expected_counts = {}
    try:
        for round_number in range(1, args.rounds+1):
            pending = []
            for case in manifest:
                boundary = 'siyue'+uuid.uuid4().hex
                body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{case["uploadName"]}"\r\nContent-Type: application/pdf\r\n\r\n').encode()+sources[case['file']]+f'\r\n--{boundary}--\r\n'.encode()
                job = call(f'/classes/{args.test_class}/books?courseId={args.course}',body,'multipart/form-data; boundary='+boundary)
                pending.append((case,job['id']))
            deadline = time.monotonic()+1800
            while pending and time.monotonic()<deadline:
                for case, identity in pending[:]:
                    detail = call('/books/'+identity);book = detail['book']
                    if book['state'] not in ('review','failed'):continue
                    sequences = sorted(s['sequence'] for s in detail['sections'] if s['kind']=='lesson')
                    result = {'round':round_number,'file':case['file'],'bookId':identity,
                              'state':book['state'],'blocks':book['blockCount'],
                              'lectures':sequences,'reviewMessage':book.get('error')}
                    results.append(result)
                    assert book['state']=='review', result
                    assert not book.get('error'), result
                    assert sequences==case['lectures'], result
                    previous = expected_counts.setdefault(case['file'],book['blockCount'])
                    assert previous==book['blockCount'] and previous>0, result
                    pending.remove((case,identity));print(json.dumps(result,ensure_ascii=False),flush=True)
                if pending:time.sleep(2)
            if pending:raise TimeoutError('Unfinished imports: '+str([c['file'] for c,_ in pending]))
    finally:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')

if __name__ == '__main__': main()
