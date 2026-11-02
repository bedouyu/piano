"""Regression for the Windows AAPT2 subdirectory paths hidden by ZipInfo.filename."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('apk_paths', ROOT / 'tools/normalize_apk_paths.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkPathsTest(unittest.TestCase):
    def test_real_windows_path_failure_is_fixed_without_changing_audio_storage(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.build') as directory:
            source, target = Path(directory) / 'bad.apk', Path(directory) / 'good.apk'
            records = [('assets/index.html', b'<html>', zipfile.ZIP_DEFLATED),
                       ('assets\\local-omr\\worker.js', b'/* actual worker bytes */', zipfile.ZIP_DEFLATED),
                       ('assets\\piano\\C4.mp3', b'ID3\x00sample-data', zipfile.ZIP_STORED)]
            with zipfile.ZipFile(source, 'w') as archive:
                for name, content, method in records:
                    item = zipfile.ZipInfo('placeholder')
                    item.filename = item.orig_filename = name
                    item.compress_type = method
                    archive.writestr(item, content)
            with zipfile.ZipFile(source) as archive:
                with self.assertRaises(AssertionError):
                    module.assert_android_paths(archive)
            self.assertEqual(module.normalize(source, target), 3)
            with zipfile.ZipFile(target) as archive:
                self.assertEqual(module.assert_android_paths(archive), 3)
                for name, content, method in records:
                    self.assertEqual(archive.read(name.replace('\\', '/')), content)
                    self.assertEqual(archive.getinfo(name.replace('\\', '/')).compress_type, method)

    def test_duplicate_canonical_paths_are_rejected(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.build') as directory:
            source, target = Path(directory) / 'bad.apk', Path(directory) / 'good.apk'
            with zipfile.ZipFile(source, 'w') as archive:
                for name in ['assets/a/b.js', 'assets\\a\\b.js']:
                    item = zipfile.ZipInfo('placeholder'); item.filename = item.orig_filename = name
                    archive.writestr(item, b'x')
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                module.normalize(source, target)


if __name__ == '__main__':
    unittest.main()
