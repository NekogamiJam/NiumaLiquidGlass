#!/usr/bin/env python3
"""Build the offline Z-tap extension-prefix table for niuma_glass.

Reads the pinned debug probe output and writes only generated/niuma_z_transitions.json
inside this tool directory. No Rime/Android runtime interaction is performed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOL_DIR.parent
DEFAULT_INPUT = REPO_ROOT / "app" / "src" / "debug" / "assets" / "niuma" / "debug_expected.json"
DEFAULT_OUTPUT = TOOL_DIR / "generated" / "niuma_z_transitions.json"
DEFAULT_CODE_PATHS = TOOL_DIR / "generated" / "niuma_code_paths.txt"
EXPECTED_SHA256 = "73f77da4d14535405d020b769b5c6ffe527a4ef8a36afdf052ec02937aa1d44e"
EXPECTED_CODE_COUNT = 170277
EXPECTED_SCHEMA = "niuma_glass"
SOURCE_SHA256 = {
    "niumawb.dict.yaml": "294e03d15a30f9a53ff78d7b9a5a0060f1dc71a2600ace0091d2765ed6ace92e",
    "niumawb_zc.dict.yaml": "09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9",
    "niumawb_fh.dict.yaml": "05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b",
}
LETTER_CODE = re.compile(r"[a-z]+\Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_sources(source_dir: Path = REPO_ROOT / "input-data") -> None:
    """Check actual source bytes too, not just hashes embedded in the pinned fixture."""
    for name, expected in SOURCE_SHA256.items():
        if sha256_file(source_dir / name) != expected:
            raise ValueError(f"actual input-data source SHA256 mismatch: {name}")


def load_expected(path: Path) -> dict[str, Any]:
    actual_sha = sha256_file(path)
    if actual_sha != EXPECTED_SHA256:
        raise ValueError(f"debug_expected SHA256 mismatch: expected {EXPECTED_SHA256}, got {actual_sha}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("format") != "niuma-debug-expected-v1":
        raise ValueError(f"unexpected input format: {payload.get('format')!r}")
    if payload.get("schema_id") != EXPECTED_SCHEMA:
        raise ValueError(f"unexpected schema_id: {payload.get('schema_id')!r}")
    if payload.get("source_sha256") != SOURCE_SHA256:
        raise ValueError("3 input-data source SHA256 values mismatch")
    verify_sources()
    candidates = payload.get("candidates")
    if not isinstance(candidates, dict) or any(
        not isinstance(code, str) or not isinstance(values, list)
        or any(not isinstance(value, str) for value in values)
        for code, values in candidates.items()
    ):
        raise ValueError("malformed candidates mapping")
    if len(candidates) != EXPECTED_CODE_COUNT or payload.get("code_count") != EXPECTED_CODE_COUNT:
        raise ValueError(f"code_count mismatch: header={payload.get('code_count')} actual={len(candidates)}")
    if any(not LETTER_CODE.fullmatch(code) for code in candidates):
        raise ValueError("expected all source codes to be lowercase ASCII letter strings")
    return payload


def collect_extend_z_prefixes(codes: set[str]) -> list[str]:
    """Return all nonempty p such that p+'z' remains a prefix of a full code."""
    prefixes = {
        code[:index]
        for code in codes
        for index, char in enumerate(code)
        if index > 0 and char == "z"
    }
    return sorted(prefixes)


def build_document(payload: dict[str, Any], input_sha256: str | None = None) -> dict[str, Any]:
    codes = set(payload["candidates"])
    prefixes = collect_extend_z_prefixes(codes)
    return {
        "format": 1,
        "schema_id": EXPECTED_SCHEMA,
        "source_expected_sha256": input_sha256 or EXPECTED_SHA256,
        "code_count": len(codes),
        "extend_z_prefixes": prefixes,
    }


def build_code_paths(payload: dict[str, Any]) -> bytes:
    """Return sorted, code-only UTF-8 paths with LF line endings."""
    codes = payload["candidates"]
    if len(codes) != EXPECTED_CODE_COUNT:
        raise ValueError(f"code_count mismatch: expected {EXPECTED_CODE_COUNT}, got {len(codes)}")
    ordered = sorted(codes)
    if any(not LETTER_CODE.fullmatch(code) for code in ordered):
        raise ValueError("expected all source codes to be lowercase ASCII letter strings")
    return ("".join(f"{code}\n" for code in ordered)).encode("ascii")


def write_generated(input_path: Path = DEFAULT_INPUT, output_path: Path = DEFAULT_OUTPUT,
                   code_paths_path: Path = DEFAULT_CODE_PATHS) -> dict[str, Any]:
    payload = load_expected(input_path)
    input_sha = sha256_file(input_path)
    result = build_document(payload, input_sha)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    code_paths_path.parent.mkdir(parents=True, exist_ok=True)
    code_paths_path.write_bytes(build_code_paths(payload))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="pinned debug_expected.json")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="generated output JSON")
    parser.add_argument("--code-paths", type=Path, default=DEFAULT_CODE_PATHS, help="generated sorted code-only paths")
    args = parser.parse_args()
    document = write_generated(args.input, args.output, args.code_paths)
    print(f"generated={args.output}")
    print(f"code_count={document['code_count']}")
    print(f"extend_z_prefixes={len(document['extend_z_prefixes'])}")


if __name__ == "__main__":
    main()
