"""Small-payload safety tests; never writes to the real Trime destinations."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stage_assets

KEYS = (
    "sense.int8.onnx", "sense.tokens.txt", "silero_vad.onnx",
    "libsherpa-onnx-c-api.so", "libonnxruntime.so",
)


class StageAssetsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "offline-asr").mkdir()
        (self.root / "offline-asr/THIRD-PARTY-NOTICES.md").write_text("test notice\n", encoding="utf-8")
        self.sources = {}
        self._write_manifest()
        bridge = self.root / stage_assets.BRIDGE_PATH
        bridge.parent.mkdir(parents=True)
        bridge.write_bytes(b"bridge-small")

    def tearDown(self):
        self.tmp.cleanup()

    def _write_manifest(self, replacements=None):
        replacements = replacements or {}
        files = {}
        for index, key in enumerate(KEYS):
            content = replacements.get(key, f"fixture:{key}:v1".encode())
            source = self.root / "inputs" / f"in-{index}.bin"
            source.parent.mkdir(exist_ok=True)
            source.write_bytes(content)
            self.sources[key] = source
            files[key] = {"source": str(source), "bytes": len(content),
                          "sha256": hashlib.sha256(content).hexdigest()}
        manifest = self.root / stage_assets.MANIFEST_PATH
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps({"files": files}), encoding="utf-8")

    def test_new_and_repeated_stage_is_idempotent(self):
        stage_assets.run(self.root)
        first = (self.root / stage_assets.ASSET_DIR / "models.json").read_bytes()
        stage_assets.run(self.root)
        self.assertEqual(first, (self.root / stage_assets.ASSET_DIR / "models.json").read_bytes())
        doc = json.loads(first)
        self.assertEqual(doc["format"], 1)
        self.assertEqual(doc["model_id"], stage_assets.MODEL_ID)
        self.assertEqual(len(doc["files"]), 3)

    def test_owned_unchanged_target_can_be_updated(self):
        stage_assets.run(self.root)
        key = KEYS[0]
        self._write_manifest({key: b"fixture refreshed payload"})
        stage_assets.run(self.root)
        dest = self.root / stage_assets.ASSET_DIR / "sensevoice.int8.onnx"
        self.assertEqual(dest.read_bytes(), b"fixture refreshed payload")

    def test_unowned_target_refused(self):
        dest = self.root / stage_assets.ASSET_DIR / "sensevoice.int8.onnx"
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b"user data")
        with self.assertRaisesRegex(stage_assets.StageError, "unowned or user-modified"):
            stage_assets.run(self.root)
        self.assertEqual(dest.read_bytes(), b"user data")

    def test_actual_source_hash_is_checked(self):
        self.sources[KEYS[0]].write_bytes(b"tampered after manifest")
        with self.assertRaisesRegex(stage_assets.StageError, "input hash/size mismatch"):
            stage_assets.run(self.root)

    def test_target_symlink_refused(self):
        dest = self.root / stage_assets.ASSET_DIR / "sensevoice.int8.onnx"
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b"sentinel")
        original = Path.is_symlink

        def report_target_symlink(path):
            return path == dest or original(path)

        # Simulate a symlink in pathlib so this safety branch is exercised even
        # on Windows hosts where creating symlinks requires elevated privilege.
        with mock.patch.object(Path, "is_symlink", report_target_symlink):
            with self.assertRaisesRegex(stage_assets.StageError, "symlink path component refused"):
                stage_assets.run(self.root)
        self.assertEqual(dest.read_bytes(), b"sentinel")

    def test_verify_only_checks_native_bridge_without_writing(self):
        result = stage_assets.run(self.root, verify_only=True)
        self.assertIn("no files written", result[0])
        self.assertFalse((self.root / stage_assets.ASSET_DIR).exists())
        (self.root / stage_assets.BRIDGE_PATH).unlink()
        with self.assertRaisesRegex(stage_assets.StageError, "native bridge missing"):
            stage_assets.run(self.root, verify_only=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
