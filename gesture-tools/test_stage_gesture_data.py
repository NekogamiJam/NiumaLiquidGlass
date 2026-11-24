"""Ownership tests use tiny temporary assets only, never modify the real app tree."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('gesture_stager_test', Path(__file__).resolve().parents[1] / 'stage-gesture-data.py')
stager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stager)

class StagerOwnershipTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.app = self.root / 'app'
        self.assets = self.app / 'app/src/main/assets/niuma'
        self.manifest = self.root / 'vendor/gesture-assets.json'
        self.payloads = {'niuma_code_paths.txt': b'a\naz\n', 'niuma_z_transitions.json': b'{"fixture":1}\n'}
        self.patches = [patch.object(stager, 'APP_ROOT', self.app), patch.object(stager, 'ASSET_DIR', self.assets),
                        patch.object(stager, 'MANIFEST_PATH', self.manifest),
                        patch.object(stager, 'expected_payloads', lambda: (self.payloads, {'fixture': 'pinned'}))]
        for p in self.patches: p.start()
    def tearDown(self):
        for p in reversed(self.patches): p.stop()
        self.tmp.cleanup()
    def test_fresh_stage_and_identical_rerun_are_deterministic(self):
        result = stager.stage()
        first = self.manifest.read_bytes()
        stager.stage()
        self.assertEqual(first, self.manifest.read_bytes())
        for name, value in self.payloads.items(): self.assertEqual(value, (self.assets/name).read_bytes())
        self.assertEqual(len(result['files']), 2)
    def test_modified_unowned_target_rejected_before_any_other_write(self):
        self.assets.mkdir(parents=True)
        (self.assets/'niuma_z_transitions.json').write_bytes(b'owned by someone else')
        with self.assertRaisesRegex(ValueError, 'unowned asset'): stager.stage()
        self.assertFalse((self.assets/'niuma_code_paths.txt').exists())
        self.assertFalse(self.manifest.exists())
    def test_owned_previous_asset_can_update_with_manifest(self):
        stager.stage()
        self.payloads['niuma_code_paths.txt'] = b'a\naz\nb\n'
        stager.stage()
        self.assertEqual((self.assets/'niuma_code_paths.txt').read_bytes(), self.payloads['niuma_code_paths.txt'])
        rel = 'app/src/main/assets/niuma/niuma_code_paths.txt'
        self.assertEqual(json.loads(self.manifest.read_text())['files'][rel]['sha256'], stager.sha256_bytes(self.payloads['niuma_code_paths.txt']))
    def test_user_modified_owned_asset_is_not_silently_replaced(self):
        stager.stage()
        first = self.manifest.read_bytes()
        (self.assets/'niuma_code_paths.txt').write_bytes(b'user edits\n')
        with self.assertRaisesRegex(ValueError, 'unowned asset'): stager.stage()
        self.assertEqual(first, self.manifest.read_bytes())
        self.assertEqual((self.assets/'niuma_code_paths.txt').read_bytes(), b'user edits\n')
    def test_unknown_manifest_refuses_before_asset_write(self):
        self.manifest.parent.mkdir(parents=True)
        self.manifest.write_text('{"format":1,"managed_by":"someone-else","files":{}}')
        with self.assertRaisesRegex(ValueError, 'not recognized'): stager.stage()
        self.assertFalse(self.assets.exists())

if __name__ == '__main__': unittest.main()
