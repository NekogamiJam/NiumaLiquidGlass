"""Unit tests for deterministic, text-only association generation (Python stdlib only)."""
from __future__ import annotations

import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_associations import SOURCE_LABEL, build_associations, serialize


FIXTURE = (
    "---\n"
    "name: fixture\n"
    "columns:\n"
    "  - text\n"
    "  - code\n"
    "  - stem+fh\n"
    "...\n"
    "北京大学\t码长编码\tstem1\n"
    "北京大学\t別的编码\tstem2\n"  # duplicate suffix: retain first occurrence only
    "北京人名\t完全不同\n"  # optional trailing stem/fh field may be absent
    "北方人\txyz\n"
    "一二\t北京大学\n"
    "中A\t符号码\n"
    "中，文\t中文\n"
    "✨中文\t代码\n"
    "𠀀𠀁\t随机\n"
).encode("utf-8")


class AssociationBuilderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.payload = build_associations(FIXTURE)
        self.entries = self.payload["entries"]

    def test_candidates_are_actual_text_suffixes_not_codes(self) -> None:
        self.assertEqual(self.entries["北"], ["京大学", "京人名", "方人"])
        self.assertEqual(self.entries["北京"], ["大学", "人名"])
        self.assertNotIn("码", self.entries)
        self.assertNotIn("码长编码", self.entries["北"])

    def test_cjk_only_filter_rejects_symbols_and_mixed_text(self) -> None:
        self.assertNotIn("中", self.entries)  # mixed Latin / punctuation / emoji words excluded
        self.assertIn("一", self.entries)
        self.assertEqual(self.entries["一"], ["二"])
        self.assertIn("𠀀", self.entries)  # supplementary Han characters are handled
        self.assertEqual(self.entries["𠀀"], ["𠀁"])

    def test_first_seen_order_dedup_and_serialization_are_stable(self) -> None:
        again = build_associations(FIXTURE)
        self.assertEqual(self.payload, again)
        self.assertEqual(serialize(self.payload), serialize(again))
        self.assertEqual(self.entries["北"], ["京大学", "京人名", "方人"])

    def test_source_hash_is_of_exact_fixture_bytes(self) -> None:
        self.assertEqual(
            self.payload["source_sha256"][SOURCE_LABEL],
            hashlib.sha256(FIXTURE).hexdigest(),
        )

    def test_candidate_limit_is_applied_per_prefix(self) -> None:
        limited = build_associations(FIXTURE, max_candidates=2)
        self.assertEqual(limited["entries"]["北"], ["京大学", "京人名"])
        with self.assertRaises(ValueError):
            build_associations(FIXTURE, max_candidates=0)


if __name__ == "__main__":
    unittest.main()
