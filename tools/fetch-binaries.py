#!/usr/bin/env python3
"""Restore large, intentionally untracked binaries from the pinned release.

Reads tools/binaries.json and verifies SHA-256 before installing any file. Existing
correct files are kept. --from-dir supports offline validation; --check-only verifies
that a checkout already has every required binary without writing anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools" / "binaries.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "NiumaLiquidGlass-binary-restore"})
    with urllib.request.urlopen(request, timeout=180) as response:  # HTTPS URLs come from the checked-in manifest.
        with target.open("wb") as stream:
            shutil.copyfileobj(response, stream, length=1024 * 1024)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true", help="only verify already-restored files")
    parser.add_argument("--from-dir", type=Path, help="copy files from a local directory instead of downloading")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    files = manifest["files"]
    failures: list[str] = []
    restored = 0

    for item in files:
        relative = Path(item["path"])
        target = ROOT / relative
        expected = item["sha256"]
        if target.is_file() and sha256(target) == expected:
            continue
        if args.check_only:
            failures.append(f"missing or mismatched: {relative}")
            continue
        source = args.from_dir / relative.name if args.from_dir else None
        temp_root = Path(tempfile.mkdtemp(prefix="niuma-binary-restore-"))
        temp_file = temp_root / relative.name
        try:
            if source is not None:
                if not source.is_file():
                    raise FileNotFoundError(source)
                shutil.copyfile(source, temp_file)
            else:
                download(item["download"], temp_file)
            actual = sha256(temp_file)
            if actual != expected:
                raise ValueError(f"SHA-256 mismatch: got {actual}, expected {expected}")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(temp_file, target)
            restored += 1
            print(f"verified and restored {relative} ({item['bytes']:,} bytes)")
        except Exception as exc:  # report per-file failure while still checking remaining entries
            failures.append(f"{relative}: {exc}")
        finally:
            temp_file.unlink(missing_ok=True)
            temp_root.rmdir()

    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    print(f"all {len(files)} binaries verified; restored {restored}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
