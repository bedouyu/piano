"""Offline integrity check; does not initialize the recognition models."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/src/main/assets/local-omr'
manifest=json.loads((OUT/'manifest.json').read_text(encoding='utf-8'))
for item in manifest['files']:
    path=(OUT/item['path']).resolve()
    assert OUT.resolve() in path.parents, 'Path leaves runtime root'
    assert path.is_file(), 'Missing: '+item['path']
    assert path.stat().st_size==item['bytes'], 'Size mismatch: '+item['path']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==item['sha256'], 'Hash mismatch: '+item['path']
required=['bootstrap.js','worker.js','inference-worker.js','rpc-codec.js','python/runner.py','python/onnxruntime.py','homr.zip',
 'pyodide/pyodide.mjs','pyodide/pyodide.asm.mjs','pyodide/pyodide.asm.wasm','pyodide/python_stdlib.zip',
 'ort/ort.wasm.min.mjs','ort/ort-wasm-simd-threaded.mjs','ort/ort-wasm-simd-threaded.wasm',
 'ort/ort.webgpu.min.mjs','ort/ort-wasm-simd-threaded.jsep.mjs','ort/ort-wasm-simd-threaded.jsep.wasm',
 'ort/ort-wasm-simd-threaded.asyncify.mjs','ort/ort-wasm-simd-threaded.asyncify.wasm']
for name in required: assert (OUT/name).is_file(), 'Missing runtime file: '+name
for file in OUT.rglob('*'):
    if not file.is_file(): continue
    assert file.suffix not in ['.part','.pyc','.tgz'], 'Download/cache artifact in APK: '+str(file)
    if file.suffix=='.zip': assert file.name in ['homr.zip','python_stdlib.zip'], 'Redundant archive in APK: '+str(file)
assert len(list((OUT/'models').glob('*.onnx')))==3
worker=(OUT/'worker.js').read_text(encoding='utf-8')
assert 'https://' not in worker and 'http://' not in worker, 'Worker has a remote endpoint'
assert 'recognise.callPromising' not in worker and 'run_sync(' not in worker
assert "transport:'native-rpc'" in worker and "rpc('echo'" in worker
inference=(OUT/'inference-worker.js').read_text(encoding='utf-8')
assert 'ort.env.wasm.numThreads=1' in inference and 'ort.wasm.min.mjs' in inference
import ast
for name in ['runner.py','onnxruntime.py']:
    tree=ast.parse((OUT/'python'/name).read_text(encoding='utf-8'))
    assert not any(isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id=='run_sync' for node in ast.walk(tree)), 'JSPI call in active Python bridge'
assert '_split_false_grand_staffs' not in (OUT/'python/runner.py').read_text(encoding='utf-8')
total=sum(path.stat().st_size for path in OUT.rglob('*') if path.is_file())
print(json.dumps({'passed':True,'files':len(manifest['files'])+1,'assetBytes':total,'assetMiB':round(total/1048576,3),'modelsBytes':sum(path.stat().st_size for path in (OUT/'models').glob('*.onnx')),'cacheExcluded':True},ensure_ascii=False))
