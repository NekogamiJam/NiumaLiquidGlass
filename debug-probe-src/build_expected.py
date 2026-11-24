#!/usr/bin/env python3
"""Build a deterministic expected candidate-order map from pinned source tables.

Reads only the three original input-data YAML files. It validates their exact
SHA-256 digests before parsing, follows niumawb.dict.yaml's zc -> fh import
order, uses the dictionaries' named `columns`, and writes only under this
probe directory.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INPUT_DIR = ROOT / "input-data"
OUTPUT = Path(__file__).resolve().parent / "generated" / "niuma" / "debug_expected.json"

PINNED_SHA256 = {
    "niumawb.dict.yaml": "294e03d15a30f9a53ff78d7b9a5a0060f1dc71a2600ace0091d2765ed6ace92e",
    "niumawb_zc.dict.yaml": "09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9",
    "niumawb_fh.dict.yaml": "05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b",
}
EXPECTED_IMPORTS = ["niumawb_zc", "niumawb_fh"]
CODE_PATTERN = re.compile(r"^[a-z]{1,5}$")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strip_yaml_comment(line: str) -> str:
    """Drop an inline YAML comment for simple scalar header declarations."""
    return line.split("#", 1)[0].rstrip()


def read_pinned_inputs() -> dict[str, bytes]:
    contents: dict[str, bytes] = {}
    for name, expected in PINNED_SHA256.items():
        path = INPUT_DIR / name
        try:
            raw = path.read_bytes()
        except OSError as exc:
            raise RuntimeError(f"cannot read pinned input {path}: {exc}") from exc
        actual = sha256_bytes(raw)
        if actual != expected:
            raise RuntimeError(
                f"pinned input SHA-256 mismatch for {name}: expected {expected}, got {actual}"
            )
        contents[name] = raw
    return contents


def parse_columns(lines: list[str], filename: str) -> list[str]:
    start = next((i for i, line in enumerate(lines) if line.strip() == "columns:"), None)
    if start is None:
        raise RuntimeError(f"{filename}: missing named columns: header")
    columns: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() == "...":
            break
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if not stripped.startswith("-"):
            if line[:1].isspace():
                continue
            break
        name = strip_yaml_comment(stripped[1:]).strip().strip("\"'")
        if name:
            columns.append(name)
    if not columns or len(columns) != len(set(columns)):
        raise RuntimeError(f"{filename}: invalid or duplicate named columns: {columns!r}")
    return columns


def parse_imports(main_text: str) -> list[str]:
    lines = main_text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == "import_tables:"), None)
    if start is None:
        raise RuntimeError("niumawb.dict.yaml: missing import_tables section")
    imports: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() == "...":
            break
        stripped = line.strip()
        if not stripped:
            continue
        if not line[:1].isspace():
            break
        if stripped.startswith("-"):
            value = strip_yaml_comment(stripped[1:]).strip().strip("\"'")
            if value:
                imports.append(value)
    return imports


def parse_rows(raw: bytes, filename: str) -> tuple[list[str], list[list[str]]]:
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise RuntimeError(f"{filename}: source is not valid UTF-8: {exc}") from exc
    lines = text.splitlines()
    columns = parse_columns(lines, filename)
    try:
        body_start = next(i for i, line in enumerate(lines) if line.strip() == "...") + 1
    except StopIteration as exc:
        raise RuntimeError(f"{filename}: missing YAML body separator ...") from exc

    rows: list[list[str]] = []
    for line_no, line in enumerate(lines[body_start:], start=body_start + 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) > len(columns):
            raise RuntimeError(
                f"{filename}:{line_no}: {len(fields)} cells for {len(columns)} named columns"
            )
        # These Rime tables omit optional trailing cells on some rows (notably stem).
        fields.extend([""] * (len(columns) - len(fields)))
        rows.append(fields)
    return columns, rows


def add_table(
    ordered: OrderedDict[str, list[str]],
    seen_by_code: dict[str, set[str]],
    columns: list[str],
    rows: list[list[str]],
    *,
    filename: str,
    code_column: str,
    text_column: str,
) -> tuple[int, int]:
    if code_column not in columns or text_column not in columns:
        raise RuntimeError(
            f"{filename}: expected columns {code_column!r}/{text_column!r}; found {columns!r}"
        )
    code_index = columns.index(code_column)
    text_index = columns.index(text_column)
    duplicate_count = 0
    accepted_count = 0
    for row_number, fields in enumerate(rows, start=1):
        code = fields[code_index].strip()
        text = fields[text_index]
        if not CODE_PATTERN.fullmatch(code):
            raise RuntimeError(f"{filename}: row {row_number} has invalid code {code!r}")
        if not text:
            raise RuntimeError(f"{filename}: row {row_number} has empty text for {code!r}")
        if code not in ordered:
            ordered[code] = []
            seen_by_code[code] = set()
        if text in seen_by_code[code]:
            duplicate_count += 1
            continue
        seen_by_code[code].add(text)
        ordered[code].append(text)
        accepted_count += 1
    return accepted_count, duplicate_count


def main() -> int:
    raw_inputs = read_pinned_inputs()
    main_text = raw_inputs["niumawb.dict.yaml"].decode("utf-8", errors="strict")
    imports = parse_imports(main_text)
    if imports != EXPECTED_IMPORTS:
        raise RuntimeError(
            f"niumawb.dict.yaml import order changed: expected {EXPECTED_IMPORTS!r}, got {imports!r}"
        )

    ordered: OrderedDict[str, list[str]] = OrderedDict()
    seen_by_code: dict[str, set[str]] = {}
    source_stats: dict[str, dict[str, int]] = {}

    zc_columns, zc_rows = parse_rows(raw_inputs["niumawb_zc.dict.yaml"], "niumawb_zc.dict.yaml")
    zc_accepted, zc_duplicates = add_table(
        ordered,
        seen_by_code,
        zc_columns,
        zc_rows,
        filename="niumawb_zc.dict.yaml",
        code_column="code",
        text_column="text",
    )
    source_stats["niumawb_zc.dict.yaml"] = {
        "source_rows": len(zc_rows),
        "stable_unique_rows_added": zc_accepted,
        "duplicate_code_text_rows_skipped": zc_duplicates,
    }

    fh_columns, fh_rows = parse_rows(raw_inputs["niumawb_fh.dict.yaml"], "niumawb_fh.dict.yaml")
    fh_accepted, fh_duplicates = add_table(
        ordered,
        seen_by_code,
        fh_columns,
        fh_rows,
        filename="niumawb_fh.dict.yaml",
        code_column="code",
        text_column="text",
    )
    source_stats["niumawb_fh.dict.yaml"] = {
        "source_rows": len(fh_rows),
        "stable_unique_rows_added": fh_accepted,
        "duplicate_code_text_rows_skipped": fh_duplicates,
    }

    candidates = {code: ordered[code] for code in sorted(ordered)}
    maximum = max((len(items) for items in candidates.values()), default=0)
    result = {
        "format": "niuma-debug-expected-v1",
        "schema_id": "niuma_glass",
        "source_order": ["niumawb_zc.dict.yaml", "niumawb_fh.dict.yaml"],
        "source_sha256": {name: sha256_bytes(raw_inputs[name]) for name in PINNED_SHA256},
        "source_stats": source_stats,
        "code_count": len(candidates),
        "unique_candidate_count": sum(len(items) for items in candidates.values()),
        "max_candidates_for_code": maximum,
        "candidates": candidates,
    }
    encoded = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(OUTPUT.suffix + ".tmp")
    temporary.write_bytes(encoded)
    temporary.replace(OUTPUT)
    print(f"output={OUTPUT}")
    print(f"sha256={sha256_bytes(encoded)}")
    print(f"codes={len(candidates)} candidates={result['unique_candidate_count']} max_per_code={maximum}")
    for name, stats in source_stats.items():
        print(f"{name}: {stats}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
