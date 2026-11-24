#!/usr/bin/env python3
"""Build deterministic prefix-to-text-continuation associations from the local Rime dictionary."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "input-data" / "niumawb_zc.dict.yaml"
DEFAULT_OUTPUT = ROOT / "association-tools" / "generated" / "niuma_associations.json"
SOURCE_LABEL = "input-data/niumawb_zc.dict.yaml"
DESCRIPTION = (
    "Deterministic prefix-to-suffix associations derived only from the local user dictionary; "
    "not a statistical language model and not a quality-equivalent replacement for Baidu suggestions."
)

# Unicode Han ideograph blocks, including extensions and compatibility ideographs.
_CJK_RANGES = (
    (0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF),
    (0x20000, 0x2A6DF), (0x2A700, 0x2B73F), (0x2B740, 0x2B81F),
    (0x2B820, 0x2CEAF), (0x2CEB0, 0x2EBEF), (0x2EBF0, 0x2EE5F),
    (0x2F800, 0x2FA1F), (0x30000, 0x3134F), (0x31350, 0x323AF),
)


def is_pure_cjk(text: str) -> bool:
    """Return true only for non-empty strings made entirely of Han ideographs."""
    return bool(text) and all(any(lo <= ord(char) <= hi for lo, hi in _CJK_RANGES) for char in text)


def _column_names(lines: Iterable[str]) -> list[str]:
    in_columns = False
    names: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped == "columns:":
            in_columns = True
            continue
        if in_columns:
            if stripped == "...":
                break
            match = re.match(r"^\s*-\s*([^\s#]+)", line)
            if match:
                names.append(match.group(1))
    if not names:
        raise ValueError("YAML header has no parseable columns list")
    if "text" not in names or "code" not in names:
        raise ValueError(f"YAML columns must include text and code; found {names!r}")
    return names


def parse_source(raw: bytes) -> tuple[list[str], list[tuple[str, ...]]]:
    """Parse the Rime header's named columns and tab-delimited rows without external YAML libs."""
    text = raw.decode("utf-8-sig")
    lines = text.splitlines()
    columns = _column_names(lines)
    try:
        header_end = next(i for i, line in enumerate(lines) if line.strip() == "...")
    except StopIteration as exc:
        raise ValueError("YAML header terminator '...' not found") from exc

    required_fields = max(columns.index("text"), columns.index("code")) + 1
    rows: list[tuple[str, ...]] = []
    for line_number, line in enumerate(lines[header_end + 1 :], start=header_end + 2):
        if not line or line.startswith("#"):
            continue
        fields = tuple(line.split("\t"))
        # Optional trailing columns (for example stem/fh metadata) may be omitted by a row.
        if len(fields) < required_fields:
            raise ValueError(f"Malformed dictionary row at line {line_number}: expected at least {required_fields} tab-separated columns")
        rows.append(fields)
    return columns, rows


def build_associations(raw: bytes, max_candidates: int = 12) -> dict:
    """Build first-seen-stable suffix candidates for prefixes of length 1..min(4, len-1)."""
    if max_candidates < 1:
        raise ValueError("max_candidates must be at least 1")
    columns, rows = parse_source(raw)
    text_index = columns.index("text")
    entries: dict[str, list[str]] = {}
    seen: dict[str, set[str]] = {}

    for fields in rows:
        word = fields[text_index]
        if not (2 <= len(word) <= 16 and is_pure_cjk(word)):
            continue
        for prefix_length in range(1, min(4, len(word) - 1) + 1):
            prefix, suffix = word[:prefix_length], word[prefix_length:]
            if prefix not in entries:
                entries[prefix] = []
                seen[prefix] = set()
            candidates = entries[prefix]
            if suffix not in seen[prefix] and len(candidates) < max_candidates:
                candidates.append(suffix)
                seen[prefix].add(suffix)

    return {
        "format": 1,
        "description": DESCRIPTION,
        "source_sha256": {SOURCE_LABEL: hashlib.sha256(raw).hexdigest()},
        "entries": entries,
    }


def serialize(payload: dict) -> bytes:
    """Serialize with stable insertion order, UTF-8 characters, compact separators, and LF."""
    return (json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=False) + "\n").encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="source Rime dictionary")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="generated JSON path")
    parser.add_argument("--limit", type=int, default=12, help="maximum continuations per prefix (default: 12)")
    args = parser.parse_args()
    if args.limit < 1:
        parser.error("--limit must be at least 1")

    raw = args.source.read_bytes()
    payload = build_associations(raw, max_candidates=args.limit)
    encoded = serialize(payload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    candidate_count = sum(len(values) for values in payload["entries"].values())
    print(f"keys={len(payload['entries'])} entries={candidate_count} bytes={len(encoded)}")
    print(f"source_sha256={payload['source_sha256'][SOURCE_LABEL]}")
    print(f"output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
