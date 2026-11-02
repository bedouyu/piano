"""Prepare an official, project-local Chrome 131 test browser; no system install."""
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.toolchain/browser131'
OUT.mkdir(parents=True, exist_ok=True)
catalog = 'https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json'
with urllib.request.urlopen(catalog, timeout=45) as response:
    releases = json.load(response)['versions']
release = next(item for item in reversed(releases) if item['version'].startswith('131.'))
asset = next(item for item in release['downloads']['chrome-headless-shell'] if item['platform'] == 'win64')
assert asset['url'].startswith('https://storage.googleapis.com/chrome-for-testing-public/')
archive = OUT / ('chrome-headless-shell-' + release['version'] + '.zip')
if not archive.is_file():
    partial = archive.with_suffix('.part')
    offset = partial.stat().st_size if partial.is_file() else 0
    probe = urllib.request.Request(asset['url'], headers={'Range':'bytes=0-0'})
    with urllib.request.urlopen(probe, timeout=40) as response:
        assert response.status == 206, 'Official download must support bounded range requests'
        total = int(response.headers['Content-Range'].split('/')[-1])
    assert offset <= total
    ranges = [(start, min(start + 8 * 1024 * 1024, total) - 1) for start in range(offset, total, 8 * 1024 * 1024)]
    def download(bounds):
        start, end = bounds
        piece = OUT / ('chunk-' + str(start) + '-' + str(end))
        if not piece.is_file() or piece.stat().st_size != end - start + 1:
            request = urllib.request.Request(asset['url'], headers={'Range':f'bytes={start}-{end}'})
            with urllib.request.urlopen(request, timeout=45) as response, piece.open('wb') as target:
                assert response.status == 206
                assert response.headers['Content-Range'].startswith(f'bytes {start}-{end}/')
                shutil.copyfileobj(response, target, 256 * 1024)
            assert piece.stat().st_size == end - start + 1
        print('Downloaded', end + 1, '/', total, flush=True)
        return piece
    with ThreadPoolExecutor(max_workers=8) as pool:
        pieces = list(pool.map(download, ranges))
    complete = archive.with_suffix('.complete')
    with complete.open('wb') as target:
        if offset:
            with partial.open('rb') as source: shutil.copyfileobj(source, target)
        for piece in pieces:
            with piece.open('rb') as source: shutil.copyfileobj(source, target)
    assert complete.stat().st_size == total
    complete.replace(archive)
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    for member in bundle.infolist():
        target = (OUT / member.filename).resolve()
        assert target.is_relative_to(OUT.resolve())
    bundle.extractall(OUT)
report = {'version': release['version'], 'source': asset['url'], 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'systemInstalled': False}
(OUT / 'source.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
