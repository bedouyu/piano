"""Normalize Windows AAPT2 ZIP entry paths before zipalign/signing.

Windows ZipInfo.filename silently normalizes backslashes. Always inspect
orig_filename and local headers: Android's AssetManager uses exact ZIP names.
"""
from __future__ import annotations
from pathlib import Path
import shutil
import struct
import sys
import zipfile


def assert_android_paths(archive: zipfile.ZipFile) -> int:
    count = 0
    for item in archive.infolist():
        assert '\\' not in item.orig_filename, 'Non-Android central ZIP path: ' + repr(item.orig_filename)
        archive.fp.seek(item.header_offset)
        header = struct.unpack('<4s5H3I2H', archive.fp.read(30))
        assert header[0] == b'PK\x03\x04'
        name = archive.fp.read(header[9]).decode('utf-8' if header[2] & 0x800 else 'cp437')
        assert name == item.orig_filename, 'Central/local ZIP path mismatch: ' + repr(name)
        assert '\\' not in name
        count += 1
    return count


def normalize(source: Path, destination: Path) -> int:
    if source.resolve() == destination.resolve():
        raise ValueError('Normalize to a separate unsigned APK')
    names = set()
    with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(destination, 'w') as outgoing:
        for item in incoming.infolist():
            name = item.orig_filename.replace('\\', '/')
            if name.startswith('/') or ':' in name or any(part in ('.', '..') for part in name.split('/')):
                raise ValueError('Invalid APK path: ' + repr(name))
            if name in names:
                raise ValueError('Duplicate canonical APK path: ' + name)
            names.add(name)
            fixed = zipfile.ZipInfo(name, item.date_time)
            fixed.compress_type = item.compress_type
            fixed.create_system = item.create_system
            fixed.external_attr = item.external_attr
            fixed.internal_attr = item.internal_attr
            fixed.file_size = item.file_size
            if item.is_dir():
                outgoing.writestr(fixed, b'')
            else:
                with incoming.open(item) as content, outgoing.open(fixed, 'w') as target:
                    shutil.copyfileobj(content, target, 1024 * 1024)
    with zipfile.ZipFile(destination) as check:
        assert_android_paths(check)
    return len(names)


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    source, destination = (Path(value).resolve() for value in sys.argv[1:])
    if not source.is_relative_to(root / '.build') or not destination.is_relative_to(root / '.build'):
        raise ValueError('Only normalize generated APK files within the project .build directory')
    print('Android ZIP paths:', normalize(source, destination))
