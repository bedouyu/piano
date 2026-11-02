"""Vendor the offline mobile OMR runtime. Network downloads never include scores."""
from __future__ import annotations
import concurrent.futures
import hashlib
import json
from pathlib import Path
import sys
import tarfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/assets/local-omr'
SOURCES = OUT / 'sources'
CACHE = Path(__file__).resolve().parent / 'downloads'
PYODIDE = 'https://cdn.jsdelivr.net/pyodide/v314.0.7/full/'
ORT = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.30.0/dist/'
HOMR_COMMIT = '863eb3c6487f3c6fe50267fb71613f05162bb7c5'
PARTSONG_COMMIT = '18b38f2602ec5a4cf6230ebee391595a4d18d9fd'
MODELS = [
 'segnet_308-3296ccd40960f90ca6ab9c035cca945675d30a0f',
 'encoder_pytorch_model_465-597144cab54c8f6d0f6c9619df5c5312694eadd6',
 'decoder_pytorch_model_465-597144cab54c8f6d0f6c9619df5c5312694eadd6',
]

def get(url: str, dest: Path, expected: str | None = None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and (not expected or hashlib.sha256(dest.read_bytes()).hexdigest() == expected):
        return
    last = None
    for attempt in range(4):
        temp = dest.with_name(dest.name + '.part')
        try:
            request = urllib.request.Request(url, headers={'User-Agent':'ScoreHelper-offline-vendor/1.0'})
            with urllib.request.urlopen(request, timeout=90) as response, temp.open('wb') as output:
                while True:
                    data = response.read(1024 * 1024)
                    if not data: break
                    output.write(data)
            digest = hashlib.sha256(temp.read_bytes()).hexdigest()
            if expected and digest != expected:
                raise ValueError('SHA256 mismatch for ' + url)
            temp.replace(dest)
            print('Downloaded', dest.relative_to(ROOT), dest.stat().st_size, flush=True)
            return
        except Exception as exc:
            last = exc
            print('Retry', attempt + 1, dest.name, str(exc), flush=True)
            time.sleep(2 + attempt * 2)
    raise RuntimeError(f'Cannot download {url}: {last}')

def initial():
    urls = {
      'partsong-fetch-homr.sh': f'https://raw.githubusercontent.com/pzfreo/partsong/{PARTSONG_COMMIT}/scripts/fetch-homr.sh',
      'partsong-worker.js': f'https://raw.githubusercontent.com/pzfreo/partsong/{PARTSONG_COMMIT}/omr-worker.js',
      'partsong-onnxruntime.py': f'https://raw.githubusercontent.com/pzfreo/partsong/{PARTSONG_COMMIT}/omr/onnxruntime.py',
      'partsong-runner.py': f'https://raw.githubusercontent.com/pzfreo/partsong/{PARTSONG_COMMIT}/omr/runner.py',
      'PARTSONG-LICENSE.txt': f'https://raw.githubusercontent.com/pzfreo/partsong/{PARTSONG_COMMIT}/LICENSE',
    }
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(lambda pair:get(pair[1], SOURCES / pair[0]),urls.items()))
    get(PYODIDE+'pyodide-lock.json',OUT/'pyodide/pyodide-lock.json')

def runtime():
    initial()
    CACHE.mkdir(parents=True,exist_ok=True)
    for old in list(SOURCES.glob('*.zip')) + list(SOURCES.glob('*.tgz')):
        resolved = old.resolve()
        destination = (CACHE/old.name).resolve()
        if ROOT.resolve() not in resolved.parents or ROOT.resolve() not in destination.parents:
            raise ValueError('Cache move would leave project directory')
        if destination.exists():
            if hashlib.sha256(old.read_bytes()).digest() != hashlib.sha256(destination.read_bytes()).digest():
                raise ValueError('Refusing to overwrite different cache file: '+str(destination))
            old.unlink()
        else:
            old.rename(destination)
    lock = json.loads((OUT/'pyodide/pyodide-lock.json').read_text())
    packages = set()
    def add(name):
        if name in packages: return
        packages.add(name)
        for dep in lock['packages'][name].get('depends',[]): add(dep)
    for name in ['numpy','opencv-python','pillow','typing-extensions']: add(name)
    tasks = [(PYODIDE+file,OUT/'pyodide'/file,None) for file in ['pyodide.mjs','pyodide.asm.mjs','pyodide.asm.wasm','python_stdlib.zip']]
    for name in packages:
        info=lock['packages'][name]
        tasks.append((PYODIDE+info['file_name'],OUT/'pyodide'/info['file_name'],info['sha256']))
    tasks.extend([
      ('https://codeload.github.com/liebharc/homr/zip/'+HOMR_COMMIT,CACHE/'homr-source.zip',None),
      ('https://registry.npmjs.org/onnxruntime-web/-/onnxruntime-web-1.30.0.tgz',CACHE/'onnxruntime-web-1.30.0.tgz',None),
    ])
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(lambda t:get(*t), tasks))
    with zipfile.ZipFile(CACHE/'homr-source.zip') as archive:
        prefix='homr-'+HOMR_COMMIT+'/'
        with zipfile.ZipFile(OUT/'homr.zip','w',zipfile.ZIP_DEFLATED) as out:
            for item in archive.infolist():
                rel=item.filename.removeprefix(prefix)
                if not item.is_dir() and (rel.startswith('homr/') or rel in ['LICENSE','LICENSE_FAQ.md','pyproject.toml']):
                    if not any(x in rel for x in ['__pycache__','.onnx','coreml_cache']): out.writestr(rel,archive.read(item))
    with tarfile.open(CACHE/'onnxruntime-web-1.30.0.tgz') as archive:
        for item in archive.getmembers():
            rel=item.name.removeprefix('package/')
            if not item.isfile(): continue
            needed=rel in ['dist/ort.webgpu.min.mjs','dist/ort.wasm.min.mjs','dist/ort-wasm-simd-threaded.mjs','dist/ort-wasm-simd-threaded.wasm','dist/ort-wasm-simd-threaded.jsep.mjs','dist/ort-wasm-simd-threaded.jsep.wasm','dist/ort-wasm-simd-threaded.asyncify.mjs','dist/ort-wasm-simd-threaded.asyncify.wasm','LICENSE','ThirdPartyNotices.txt','package.json']
            if not needed: continue
            dest=OUT/'ort'/Path(rel).name
            dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_bytes(archive.extractfile(item).read())
    print('Runtime prepared; package list:',sorted(packages),flush=True)

def models():
    CACHE.mkdir(parents=True,exist_ok=True)
    tasks=[('https://github.com/liebharc/homr/releases/download/onnx_checkpoints/'+model+'.zip',CACHE/(model+'.zip'),None) for model in MODELS]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda t:get(*t),tasks))
    (OUT/'models').mkdir(parents=True,exist_ok=True)
    for model in MODELS:
        with zipfile.ZipFile(CACHE/(model+'.zip')) as archive:
            entries=[i for i in archive.infolist() if not i.is_dir() and i.filename.endswith('.onnx')]
            if len(entries)!=1: raise ValueError('Unexpected model archive: '+model)
            data=archive.read(entries[0])
            (OUT/'models'/(model+'.onnx')).write_bytes(data)
            print('Extracted model',model,len(data),flush=True)

if __name__ == '__main__':
    command=sys.argv[1] if len(sys.argv)>1 else 'initial'
    {'initial':initial,'runtime':runtime,'models':models}[command]()
