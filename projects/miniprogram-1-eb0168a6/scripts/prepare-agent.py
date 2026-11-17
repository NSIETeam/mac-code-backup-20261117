"""Fetch the fixed official portable printing component and verify its pinned digest."""
import hashlib, pathlib, urllib.request, zipfile, io
root = pathlib.Path(__file__).resolve().parents[1]
url = 'https://files.sumatrapdfreader.org/software/sumatrapdf/rel/3.6.1/SumatraPDF-3.6.1-64.zip'
with urllib.request.urlopen(url, timeout=120) as response:
    archive = response.read()
binary = zipfile.ZipFile(io.BytesIO(archive)).read('SumatraPDF-3.6.1-64.exe')
expected = (root / 'agent/sumatra.sha256').read_text().strip()
assert hashlib.sha256(binary).hexdigest() == expected, 'SumatraPDF checksum mismatch'
vendor = root / 'agent/vendor'
vendor.mkdir(exist_ok=True)
(vendor / 'SumatraPDF.exe').write_bytes(binary)
(vendor / 'sumatra.sha256').write_text(expected + '\n')
print('SumatraPDF 3.6.1 verified and ready.')
