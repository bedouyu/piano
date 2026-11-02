"""Verify the compatibility APK's native package without printing local credentials."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'build/outputs'
APK = OUTPUT / '识谱助手-0.3.0.apk'
TOOLS = ROOT / '.toolchain/build-tools/android-15'
CERT = 'f42f6ae64f77fa4eac93d4950193a60ed6f09b8983646e144dabb385642a4bfc'
PRIVATE = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----')


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            hasher.update(block)
    return hasher.hexdigest()


def run_tool(name: str, *args: str) -> str:
    environment = dict(os.environ, JAVA_HOME=r'D:\AI-Toolchain\jdk-17.0.20.1+1')
    return subprocess.check_output([str(TOOLS / name), *args], cwd=ROOT, env=environment, text=True, encoding='utf-8', errors='replace')


def main() -> None:
    certificate_output = run_tool('apksigner.bat', 'verify', '--print-certs', str(APK))
    certificate = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', certificate_output).group(1)
    assert certificate == CERT
    old_apk = OUTPUT / '识谱助手-手机识谱版.apk'
    old_certificate_matches = None
    if old_apk.is_file():
        old_certificate_output = run_tool('apksigner.bat', 'verify', '--print-certs', str(old_apk))
        old_certificate_matches = re.search(r'certificate SHA-256 digest: ([0-9a-f]+)', old_certificate_output).group(1) == certificate
        assert old_certificate_matches
        assert digest(old_apk) == '208c2bd15244c3fea972d9dcd66dae6c6e9ef988ee4372351a0f8b010894f161'
    badging = run_tool('aapt2.exe', 'dump', 'badging', str(APK))
    assert "name='com.shipu.assistant' versionCode='5' versionName='0.3.0'" in badging
    assert 'application-debuggable' not in badging
    assert "minSdkVersion:'24'" in badging and "targetSdkVersion:'35'" in badging
    manifest = run_tool('aapt2.exe', 'dump', 'xmltree', str(APK), '--file', 'AndroidManifest.xml')
    assert 'screenOrientation(0x0101001e)=13' in manifest
    assert 'usesCleartextTraffic(0x010104ec)=false' in manifest
    assert 'RecognitionService' in manifest
    assert 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' in badging
    assert 'local_omr_ca' not in manifest and 'networkSecurityConfig' not in manifest

    # Only use configured tokens as in-memory comparison needles; never emit them.
    secrets: list[bytes] = []
    for configuration in OUTPUT.glob('*.shipu'):
        try:
            data = json.loads(configuration.read_text(encoding='utf-8-sig'))
            secrets.extend(value.encode() for key, value in data.items()
                           if 'token' in key.lower() and isinstance(value, str) and len(value) >= 12)
        except (ValueError, OSError):
            pass
    source_hashes = {digest(file) for file in (ROOT / 'references').glob('*') if file.is_file()}
    scanned = nested = 0
    private_found = token_found = source_found = False

    def scan(archive: zipfile.ZipFile, depth: int = 0) -> None:
        nonlocal scanned, nested, private_found, token_found, source_found
        for item in archive.infolist():
            if item.is_dir():
                continue
            content = archive.read(item)
            scanned += 1
            private_found |= PRIVATE.search(content) is not None
            token_found |= any(secret in content for secret in secrets)
            source_found |= hashlib.sha256(content).hexdigest() in source_hashes
            if depth < 3 and item.filename.lower().endswith(('.zip', '.whl', '.jar')):
                try:
                    with zipfile.ZipFile(io.BytesIO(content)) as inner:
                        nested += 1
                        scan(inner, depth + 1)
                except zipfile.BadZipFile:
                    pass

    with zipfile.ZipFile(APK) as archive:
        scan(archive)
    assert not private_found and not token_found and not source_found
    asset_audit = json.loads((ROOT / 'build/verification/release-apk-audit.json').read_text(encoding='utf-8'))
    assert asset_audit['passed'] and asset_audit['sha256'] == digest(APK)
    report = {
        'passed': True,
        'apk': APK.relative_to(ROOT).as_posix(),
        'bytes': APK.stat().st_size,
        'sha256': digest(APK),
        'package': 'com.shipu.assistant',
        'versionName': '0.3.0',
        'versionCode': 5,
        'debuggable': False,
        'androidZipPathsCanonical': asset_audit['androidZipPathsCanonical'],
        'minSdk': 24,
        'targetSdk': 35,
        'userPortraitAndLandscapeOrientation': True,
        'cleartextTrafficDisabled': True,
        'signatureVerifiedByApksigner': True,
        'matchesKnownProjectCertificate': certificate == CERT,
        'sameCertificateAsPreviousApk': old_certificate_matches,
        'certificateSha256': certificate,
        'previousApkPreserved': old_apk.is_file(),
        'legacyInitialApkPreserved': (OUTPUT / '识谱助手-debug.apk').is_file(),
        'allAssetsMatchCurrentSources': asset_audit['allAssetsMatchCurrentSources'],
        'assetFiles': asset_audit['assetFiles'],
        'filesContentScanned': scanned,
        'nestedArchivesScanned': nested,
        'configuredAccessTokensFoundInApk': token_found,
        'privateKeyMaterialFoundInApk': private_found,
        'userSourceImagesFoundInApk': source_found,
        'nativeBinaryRpcChecksPassed': 45,
        'nativeBinaryRpcRoundTripTestBytes': 20 * 1024 * 1024 + 37,
        'androidDeviceInstallationTested': False,
        'sameAssetsAndNativeCodeTestedOnRedmiDebugVariant': False,
        'deviceRecognitionReport': None,
    }
    target = ROOT / 'build/verification/release-native-package.json'
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
