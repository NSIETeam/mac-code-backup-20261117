"""Resolve official PyPI SHA-256 metadata for Windows wheels; a mirror may only supply matching bytes."""
import concurrent.futures,json,re,sys,urllib.request
from pathlib import Path
versions=[line.split()[0].split('==') for line in Path(sys.argv[1]).read_text().splitlines() if line.strip()]
versions += [('colorama','0.4.6'),('pyreadline3','3.5.6')]
def entry(pair):
 name,version=pair
 with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json',timeout=30) as f:meta=json.load(f)
 hashes=sorted({x['digests']['sha256'] for x in meta['urls'] if x['filename'].endswith('.whl') and ('win_amd64' in x['filename'] or 'none-any' in x['filename'])})
 assert hashes,(name,version)
 return name+'=='+version+' '+ ' '.join('--hash=sha256:'+h for h in hashes)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
 rows=list(pool.map(entry,versions))
Path(sys.argv[2]).write_text('\n'.join(rows)+'\n');print('Locked',len(rows),'packages against official PyPI hashes')
