#!/usr/bin/env python3
"""Safely stage the verified offline ASR runtime assets for Android."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
from typing import Any

MODEL_ID = "sensevoice-2024-07-17-int8"
MANIFEST_PATH = Path("offline-asr/source-inputs.json")
OWNER_PATH = Path("offline-asr/staged-files.json")
ASSET_DIR = Path("app/src/main/assets/offline-asr")
JNI_DIR = Path("app/src/main/jniLibs/arm64-v8a")
BRIDGE_PATH = Path("offline-asr/native/out/arm64-v8a/libniuma_offline_asr.so")

# Manifest keys are intentionally explicit. Never stage bench recordings or
# unrelated files just because they happen to occur in a source manifest.
ASSETS = (
    ("sense.int8.onnx", ASSET_DIR / "sensevoice.int8.onnx", "model"),
    ("sense.tokens.txt", ASSET_DIR / "tokens.txt", "model"),
    ("silero_vad.onnx", ASSET_DIR / "silero_vad.onnx", "model"),
    ("libsherpa-onnx-c-api.so", JNI_DIR / "libsherpa-onnx-c-api.so", "native"),
    ("libonnxruntime.so", JNI_DIR / "libonnxruntime.so", "native"),
)
BRIDGE_NAME = "libniuma_offline_asr.so"

class StageError(Exception):
    pass


def _sha_file(path: Path) -> tuple[int, str]:
    h = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            h.update(chunk)
    return size, h.hexdigest()


def _regular_file(path: Path, label: str) -> None:
    try:
        mode = path.lstat().st_mode
    except OSError as exc:
        raise StageError(f"{label} missing/unreadable: {path}: {exc}") from exc
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        raise StageError(f"{label} must be a regular non-symlink file: {path}")


def _assert_no_symlink(root: Path, relative: Path) -> None:
    """Reject symlinks in every existing component below root (and root)."""
    if root.exists() or root.is_symlink():
        if root.is_symlink():
            raise StageError(f"symlink path component refused: {root}")
    current = root
    for part in relative.parts:
        if part in ("", ".", ".."):
            raise StageError(f"unsafe relative path: {relative}")
        current = current / part
        if current.is_symlink():
            raise StageError(f"symlink path component refused: {current}")


def _load_sources(root: Path) -> list[dict[str, Any]]:
    manifest_path = root / MANIFEST_PATH
    _regular_file(manifest_path, "source manifest")
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise StageError(f"cannot parse source manifest {manifest_path}: {exc}") from exc
    files = data.get("files")
    if not isinstance(files, dict):
        raise StageError("source manifest has no files object")

    planned: list[dict[str, Any]] = []
    for key, rel_dest, kind in ASSETS:
        entry = files.get(key)
        if not isinstance(entry, dict) or not isinstance(entry.get("source"), str):
            raise StageError(f"source manifest entry missing: {key}")
        source = Path(entry["source"])
        if not source.is_absolute():
            source = root / source
        source = Path(os.path.abspath(source))
        expected_size, expected_hash = entry.get("bytes"), entry.get("sha256")
        if not isinstance(expected_size, int) or expected_size < 0 or not isinstance(expected_hash, str):
            raise StageError(f"invalid size/hash in source manifest entry: {key}")
        if len(expected_hash) != 64:
            raise StageError(f"invalid SHA-256 in source manifest entry: {key}")
        _regular_file(source, f"input {key}")
        actual_size, actual_hash = _sha_file(source)
        if actual_size != expected_size or actual_hash.lower() != expected_hash.lower():
            raise StageError(f"input hash/size mismatch for {key}: expected {expected_size}/{expected_hash}, got {actual_size}/{actual_hash}")
        planned.append({"source": source, "relative": rel_dest, "filename": rel_dest.name,
                        "bytes": actual_size, "sha256": actual_hash, "kind": kind})

    bridge = root / BRIDGE_PATH
    _regular_file(bridge, "native bridge")
    size, digest = _sha_file(bridge)
    planned.append({"source": bridge, "relative": JNI_DIR / BRIDGE_NAME,
                    "filename": BRIDGE_NAME, "bytes": size, "sha256": digest, "kind": "native"})
    return planned


def _render_models(planned: list[dict[str, Any]]) -> bytes:
    models = [{k: item[k] for k in ("filename", "bytes", "sha256")}
              for item in planned if item["kind"] == "model"]
    doc = {
        "format": 1,
        "model_id": MODEL_ID,
        "files": models,
        "licenses": {
            "model": {
                "name": "SenseVoiceSmall / sherpa-onnx converted model (2024-07-17 int8)",
                "license": "FunASR Model Open Source License Agreement v1.1 (custom; not MIT/Apache-2.0)",
                "source": "https://huggingface.co/FunAudioLLM/SenseVoiceSmall",
                "license_url": "https://raw.githubusercontent.com/modelscope/FunASR/main/MODEL_LICENSE",
            },
            "engine": {
                "name": "sherpa-onnx",
                "license": "Apache-2.0",
                "source": "https://github.com/k2-fsa/sherpa-onnx",
            },
            "vad": {
                "name": "Silero VAD",
                "license": "See upstream license and notices; packaged artifact provenance is sherpa-onnx",
                "source": "https://github.com/snakers4/silero-vad",
            },
        },
    }
    return (json.dumps(doc, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def _load_ownership(root: Path) -> dict[str, str]:
    p = root / OWNER_PATH
    if not p.exists() and not p.is_symlink():
        return {}
    _regular_file(p, "ownership ledger")
    try:
        doc = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise StageError(f"cannot parse ownership ledger {p}: {exc}") from exc
    owned = doc.get("files") if isinstance(doc, dict) else None
    if not isinstance(owned, dict):
        raise StageError("ownership ledger must contain a files object")
    return {str(k): str(v) for k, v in owned.items()}


def _preflight(root: Path, planned: list[dict[str, Any]], generated: dict[Path, bytes], owned: dict[str, str]) -> None:
    all_targets: list[tuple[Path, str]] = [(item["relative"], item["sha256"]) for item in planned]
    for rel, payload in generated.items():
        all_targets.append((rel, hashlib.sha256(payload).hexdigest()))
    ledger_rel = OWNER_PATH
    _assert_no_symlink(root, ledger_rel)
    for rel, expected_hash in all_targets:
        _assert_no_symlink(root, rel)
        target = root / rel
        if not target.exists():
            continue
        _regular_file(target, "target")
        _, current_hash = _sha_file(target)
        if current_hash == expected_hash:
            continue
        previous = owned.get(rel.as_posix())
        if previous is None or current_hash != previous:
            raise StageError(f"refusing to replace unowned or user-modified target: {rel.as_posix()}")


def _stage(root: Path, planned: list[dict[str, Any]], generated: dict[Path, bytes], owned: dict[str, str]) -> None:
    outputs: list[tuple[Path, Path | None, bytes | None, str]] = []
    for item in planned:
        outputs.append((item["relative"], item["source"], None, item["sha256"]))
    for rel, payload in generated.items():
        outputs.append((rel, None, payload, hashlib.sha256(payload).hexdigest()))

    # Prepare every output to sibling temporary files and verify before any replace.
    temps: list[tuple[Path, Path, str]] = []
    try:
        for rel, source, payload, expected_hash in outputs:
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".stage-tmp", dir=str(target.parent))
            os.close(fd)
            temp = Path(tmp_name)
            if source is not None:
                with source.open("rb") as src, temp.open("wb") as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
            else:
                temp.write_bytes(payload or b"")
            _, actual = _sha_file(temp)
            if actual != expected_hash:
                raise StageError(f"staged temporary hash mismatch: {rel.as_posix()}")
            temps.append((target, temp, expected_hash))
        for target, temp, _ in temps:
            os.replace(temp, target)
    finally:
        for _, temp, _ in temps:
            try:
                temp.unlink(missing_ok=True)
            except OSError:
                pass


def run(root: Path, verify_only: bool = False) -> list[str]:
    root = Path(os.path.abspath(root))
    planned = _load_sources(root)
    models_rel = ASSET_DIR / "models.json"
    notice_rel = ASSET_DIR / "THIRD-PARTY-NOTICES.md"
    notice_source = root / "offline-asr/THIRD-PARTY-NOTICES.md"
    _regular_file(notice_source, "third-party notices")
    notice_payload = notice_source.read_bytes()
    generated = {models_rel: _render_models(planned), notice_rel: notice_payload}
    owned = _load_ownership(root)
    _preflight(root, planned, generated, owned)
    if verify_only:
        return ["verified inputs and target safety; no files written"]

    _stage(root, planned, generated, owned)
    ledger = {"format": 1, "files": {rel.as_posix(): digest for rel, _, _, digest in
              [(item["relative"], item.get("source"), None, item["sha256"]) for item in planned] +
              [(rel, None, payload, hashlib.sha256(payload).hexdigest()) for rel, payload in generated.items()]}}
    ledger_payload = (json.dumps(ledger, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    ledger_target = root / OWNER_PATH
    ledger_target.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".staged-files.", suffix=".tmp", dir=str(ledger_target.parent))
    os.close(fd)
    temp = Path(tmp_name)
    try:
        temp.write_bytes(ledger_payload)
        os.replace(temp, ledger_target)
    finally:
        temp.unlink(missing_ok=True)
    return [f"staged {len(planned)} verified inputs and 2 generated files", f"ownership ledger: {OWNER_PATH.as_posix()}"]


def verify_staged(root: Path) -> list[str]:
    root = Path(os.path.abspath(root))
    ledger = root / OWNER_PATH
    _regular_file(ledger, "staged ownership ledger")
    owned = _load_ownership(root)
    expected = {p.as_posix() for _, p, _ in ASSETS} | {(JNI_DIR / BRIDGE_NAME).as_posix(), (ASSET_DIR / "models.json").as_posix(), (ASSET_DIR / "THIRD-PARTY-NOTICES.md").as_posix()}
    if set(owned) != expected:
        raise StageError("staged ownership membership mismatch")
    for rel, digest in owned.items():
        _assert_no_symlink(root, Path(rel))
        p = root / rel
        _regular_file(p, "staged file")
        if _sha_file(p)[1] != digest:
            raise StageError(f"staged file mismatch: {rel}")
    manifest = json.loads((root / ASSET_DIR / "models.json").read_text("utf-8"))
    if manifest.get("format") != 1 or manifest.get("model_id") != MODEL_ID:
        raise StageError("staged model identity mismatch")
    for item in manifest["files"]:
        if item["filename"] not in {"sensevoice.int8.onnx", "tokens.txt", "silero_vad.onnx"}:
            raise StageError("staged model path mismatch")
        if _sha_file(root / ASSET_DIR / item["filename"]) != (item["bytes"], item["sha256"]):
            raise StageError("staged model manifest mismatch")
    return ["verified all 8 staged offline ASR assets/runtime files; no writes"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: parent of offline-asr)")
    parser.add_argument("--verify-only", action="store_true", help="validate inputs and destinations without writing")
    parser.add_argument("--verify-staged", action="store_true", help="verify packaged staged inputs without original workspace source manifests")
    args = parser.parse_args(argv)
    try:
        lines = verify_staged(args.root) if args.verify_staged else run(args.root, args.verify_only)
        for line in lines:
            print(line)
        return 0
    except StageError as exc:
        print(f"stage_assets: ERROR: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
