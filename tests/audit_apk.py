"""Inspect the built APK without executing it or printing any credentials."""
from __future__ import annotations
import hashlib
import json
import sys
from pathlib import Path
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from normalize_apk_paths import assert_android_paths

ROOT = Path(__file__).resolve().parents[1]
APK = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'build/outputs/识谱助手-0.3.0.apk'
ASSETS = ROOT / 'app/src/main/assets'
OUT = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT / 'build/verification/release-apk-audit.json'


def stream_hash(source):
    digest = hashlib.sha256()
    for block in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def main():
    mismatches = []
    with zipfile.ZipFile(APK) as archive:
        canonical_entries = assert_android_paths(archive)
        names = archive.namelist()
        forbidden = [name for name in names if any(value in name.lower() for value in (
            'server-key', 'ca-key', '.keystore', '.p12', '.shipu', 'local-access',
            '用户提供', 'clipboard-', '.part', '__pycache__',
        ))]
        assert not forbidden, f'Private, incomplete, or user source files in APK: {forbidden}'
        local_files = sorted(file for file in ASSETS.rglob('*') if file.is_file())
        for file in local_files:
            name = 'assets/' + file.relative_to(ASSETS).as_posix()
            if name not in names:
                mismatches.append(name + ': missing')
                continue
            with file.open('rb') as source, archive.open(name) as packed:
                if stream_hash(source) != stream_hash(packed):
                    mismatches.append(name + ': stale')
        assert not mismatches, 'APK differs from current assets: ' + str(mismatches)
        models = [name for name in names if name.startswith('assets/local-omr/models/') and name.endswith('.onnx')]
        assert len(models) == 3, 'Three real OMR models must be bundled'
        assert 'assets/local-omr/ort/ort-wasm-simd-threaded.asyncify.wasm' in names
        assert 'assets/local-omr/ort/ort-wasm-simd-threaded.asyncify.mjs' in names
        assert 'assets/local-omr/pyodide/pyodide.asm.wasm' in names
        assert 'assets/local-omr/python/runner.py' in names
        assert 'assets/local-omr/homr.zip' in names
        assert 'assets/local-omr/worker.js' in names
        assert 'assets/local-omr/bootstrap.js' in names
        assert 'assets/local-omr/inference-worker.js' in names
        assert 'assets/local-omr/rpc-codec.js' in names
        assert 'assets/local-omr/ort/ort.wasm.min.mjs' in names
        assert 'assets/local-omr/ort/ort-wasm-simd-threaded.mjs' in names
        assert 'assets/local-omr/ort/ort-wasm-simd-threaded.wasm' in names
        assert 'assets/mobile-omr.js' in names
        assert 'assets/omr-engine.js' in names and 'assets/omr-engine.html' in names
        assert not any('local_omr_ca' in name for name in names)
        assert 'assets/fonts/Bravura.otf' in names
        samples = [name for name in names if name.startswith('assets/piano/') and name.endswith('.mp3')]
        assert len(samples) == 15
        assert all(archive.getinfo(name).compress_type == 0 for name in samples), 'SoundPool samples must support openFd'
        report = {
            'passed': True,
            'apk': APK.relative_to(ROOT).as_posix(),
            'bytes': APK.stat().st_size,
            'sha256': hashlib.sha256(APK.read_bytes()).hexdigest(),
            'allAssetsMatchCurrentSources': True,
            'androidZipPathsCanonical': True,
            'centralAndLocalHeaderPathsVerified': canonical_entries,
            'assetFiles': len(local_files),
            'modelCount': len(models),
            'modelUncompressedBytes': sum(archive.getinfo(name).file_size for name in models),
            'offlinePianoSamples': len(samples),
            'pianoSamplesUncompressedForNativeAudio': True,
            'privateKeysTokensOrUserSourceImages': [],
            'androidDeviceInstallationTested': False,
        }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
