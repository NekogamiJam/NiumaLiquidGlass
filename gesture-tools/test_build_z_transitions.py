from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("build_z_transitions.py")
SPEC = importlib.util.spec_from_file_location("build_z_transitions", MODULE_PATH)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PrefixCollectionTests(unittest.TestCase):
    def test_nonempty_prefixes_only_for_noninitial_z(self) -> None:
        codes = {"zoo", "azb", "abz", "xbzc", "abzc"}
        self.assertEqual(module.collect_extend_z_prefixes(codes), ["a", "ab", "xb"])

    def test_duplicate_prefixes_are_deduplicated_and_sorted(self) -> None:
        codes = {"abza", "abzb", "abzc", "aczx", "acyz"}
        self.assertEqual(module.collect_extend_z_prefixes(codes), ["ab", "ac", "acy"])

    def test_empty_and_initial_z_codes_do_not_create_prefix(self) -> None:
        self.assertEqual(module.collect_extend_z_prefixes({"z", "za", "zz"}), ["z"])


class BuildOutputTests(unittest.TestCase):
    def test_pinned_full_table_protects_every_noninitial_z_path(self) -> None:
        payload = module.load_expected(module.DEFAULT_INPUT)
        codes = set(payload["candidates"])
        prefixes = set(module.collect_extend_z_prefixes(codes))
        z_paths = [
            (code, code[:index])
            for code in codes
            for index, char in enumerate(code)
            if index > 0 and char == "z"
        ]
        self.assertEqual(len(codes), 170277)
        self.assertEqual(len(z_paths), 26)
        self.assertTrue(all(prefix in prefixes for _, prefix in z_paths))
        self.assertEqual(sum(len(payload["candidates"][prefix]) >= 3 for _, prefix in z_paths), 16)
        self.assertEqual(len(prefixes), 11)

    def test_input_hash_source_hashes_and_generated_format(self) -> None:
        payload = {
            "format": "niuma-debug-expected-v1",
            "schema_id": "niuma_glass",
            "source_sha256": module.SOURCE_SHA256,
            "code_count": 2,
            "candidates": {"az": ["a"], "abz": ["b"]},
        }
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "debug_expected.json"
            source.write_text(json.dumps(payload), encoding="utf-8")
            # Valid fixture hashing is explicitly injected only for this pure helper test.
            expected_hash = module.sha256_file(source)
            generated = module.build_document(payload, expected_hash)
        self.assertEqual(generated, {
            "format": 1,
            "schema_id": "niuma_glass",
            "source_expected_sha256": expected_hash,
            "code_count": 2,
            "extend_z_prefixes": ["a", "ab"],
        })

    def test_rejects_modified_actual_source_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in module.SOURCE_SHA256:
                (root / name).write_bytes((module.REPO_ROOT / "input-data" / name).read_bytes())
            module.verify_sources(root)
            with (root / "niumawb.dict.yaml").open("ab") as stream:
                stream.write(b"\n# changed fixture\n")
            with self.assertRaisesRegex(ValueError, "actual input-data source SHA256"):
                module.verify_sources(root)

    def test_rejects_unexpected_source_hashes(self) -> None:
        payload = {
            "format": "niuma-debug-expected-v1",
            "schema_id": "niuma_glass",
            "source_sha256": {**module.SOURCE_SHA256, "niumawb.dict.yaml": "0" * 64},
            "code_count": 0,
            "candidates": {},
        }
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "debug_expected.json"
            source.write_text(json.dumps(payload), encoding="utf-8")
            expected_hash = module.sha256_file(source)
            pinned_hash = module.EXPECTED_SHA256
            module.EXPECTED_SHA256 = expected_hash
            try:
                with self.assertRaisesRegex(ValueError, "3 input-data source SHA256"):
                    module.load_expected(source)
            finally:
                module.EXPECTED_SHA256 = pinned_hash


if __name__ == "__main__":
    unittest.main()
