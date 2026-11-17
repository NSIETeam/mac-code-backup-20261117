#!/usr/bin/env python3
"""Re-fetch only the audited original manifest; TLS and content hashes stay mandatory."""
import base64,hashlib,json,pathlib,tarfile,urllib.request
R=pathlib.Path(__file__).resolve().parent
manifest=json.loads((R/'original-files-manifest.json').read_text())
def sha(b):return hashlib.sha256(b).hexdigest()
for entry in manifest['files']:
    path=R/entry['file']
    if path.exists() and sha(path.read_bytes())==entry['sha256']:continue
    url=entry.get('url');blob=entry.get('gitBlobSha') or entry.get('git_blob_sha')
    if url and 'github.com/' in url:
        repo=url.split('github.com/',1)[1].split('/blob/',1)[0]
        url=f'https://api.github.com/repos/{repo}/git/blobs/{blob}'
    elif not url and 'research-only/cmmlu/' in entry['file']:
        url=f'https://api.github.com/repos/haonan-li/CMMLU/git/blobs/{blob}'
    if not url:raise ValueError('No audited source URL for '+entry['file'])
    request=urllib.request.Request(url,headers={'User-Agent':'Siyue-audited-open-source-collector/1.0'})
    with urllib.request.urlopen(request,timeout=100) as response:data=response.read()
    if '/git/blobs/' in url:
        payload=json.loads(data);assert payload['encoding']=='base64';data=base64.b64decode(payload['content'])
        assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==blob
    assert sha(data)==entry['sha256'],'Original content hash changed; not accepted: '+entry['file']
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print('Verified',entry['file'],len(data),flush=True)
with tarfile.open(R/'raw/mmlu-data.tar') as archive:
    for member in archive.getmembers():
        if member.isfile() and '/high_school_' in member.name and member.name.endswith('.csv') and '/auxiliary_train/' not in member.name:
            assert '..' not in pathlib.PurePosixPath(member.name).parts
            dest=R/'raw/mmlu-highschool'/member.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(archive.extractfile(member).read())
print('Audited originals and explicit MMLU high_school_ CSV files ready. Run normalize_sources.py, then verify_collection.py.')
