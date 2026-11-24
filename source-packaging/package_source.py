#!/usr/bin/env python3
"""Select and package the Rime · 液态玻璃 source kit (stdlib only).

Default invocation creates an archive; use --self-test to inspect selection only.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile
from typing import Iterable

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "out"
ARCHIVE_NAME = "RimeLiquidGlass-source-kit.zip"
MANIFEST_NAME = "SOURCE-KIT-MANIFEST.sha256.json"
SIDECAR_NAME = "RimeLiquidGlass-source-kit.sha256.json"
OFFICIAL_APK = "vendor/trime-v3.3.13-arm64-official.apk"
OFFICIAL_APK_SHA256 = "e7535ea421a25962eb2ea37c46ff867838e69d33ba6d935876ab84d108de5020"

# Deliberate root-script allowlist: excludes device-session/ADB and license-consent tooling.
ROOT_SCRIPTS = (
    "build-offline-prepared.ps1",
    "check-custom-components.ps1",
    "integrate-associations.py",
    "prepare-api37.ps1",
    "prepare-baseline.ps1",
    "prepare-gradle.ps1",
    "prepare-sdkmanager.ps1",
    "repair-workspace-sdk.ps1",
    "stage-associations.py",
    "stage-custom-components.ps1",
    "stage-custom-data.py",
    "stage-gesture-data.py",
)
VENDOR_FILES = (
    "vendor/association-assets.json",
    "vendor/gesture-assets.json",
    "vendor/custom-assets.json",
    "vendor/custom-components.json",
    "vendor/native-source-pins.json",
    "vendor/staged-official-assets.json",
    "vendor/upstream-source.json",
)
ROOT_DOCS = ("README.md", "docs/design-route.md", "docs/build.md", "docs/provenance.md", "docs/licenses.md", "docs/data.md", "docs/roadmap.md", "docs/changelog.md", "docs/upstream-delta.md")
PRIVATE_EXTENSIONS = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".pk8"}
PRIVATE_NAMES = {
    "local.properties", "keystore.properties", "sdk-license-consent.json",
    "android-sdk-license.txt", "debug.keystore", "release.keystore",
}
EXCLUDED_DIRS = {
    ".git", ".gradle", ".gradle-cache", ".kotlin", ".cxx", ".externalnativebuild", ".idea", "build", "__pycache__", "out", "keys",
    "keystore", "keystores", "device-evidence",
    "raw-device-evidence", "raw-reference", "raw_references", "private-records",
}
EXCLUDED_FILE_PATTERNS = (
    re.compile(r".*\.log(?:\.\d+)?$", re.I),
    re.compile(r"hs_err_pid.*", re.I),
    re.compile(r".*(?:raw[-_]?reference|device[-_]?evidence|full[-_]?runtime[-_]?failure).*", re.I),
)


def _is_excluded(rel: Path, *, is_dir: bool = False) -> bool:
    """Centralized path denylist; rel is always relative to ROOT."""
    parts = rel.parts
    if any(part in {'..', ''} for part in parts) or rel.is_absolute():
        return True
    low_parts = [part.casefold() for part in parts]
    # `ui/main/log` is a real Kotlin package, not a runtime-log directory.
    # Keep all ordinary source packages under the frontend's src trees.
    source_tree = bool(low_parts and low_parts[0] == 'app' and 'src' in low_parts)
    checked_parts = low_parts if is_dir else low_parts[:-1]
    if not source_tree and any(part in {'log', 'logs'} for part in checked_parts):
        return True
    if any(part in EXCLUDED_DIRS for part in low_parts[:-1] if not is_dir) or any(
        part in EXCLUDED_DIRS for part in low_parts if is_dir
    ):
        return True
    name = rel.name.casefold()
    if name in PRIVATE_NAMES:
        return True
    if not is_dir and rel.suffix.casefold() in PRIVATE_EXTENSIONS:
        return True
    if not is_dir and any(pattern.fullmatch(rel.name) for pattern in EXCLUDED_FILE_PATTERNS):
        return True
    # Never follow or package symlink targets. These can point outside the project.
    try:
        candidate = ROOT / rel
        if candidate.is_symlink() or (hasattr(candidate, "is_junction") and candidate.is_junction()):
            return True
    except OSError:
        return True
    return False


def _add_if_file(selected: dict[str, Path], rel: Path) -> None:
    rel = Path(rel)
    if _is_excluded(rel):
        return
    source = ROOT / rel
    if source.is_file() and not source.is_symlink():
        selected[rel.as_posix()] = source


def _walk_tree(selected: dict[str, Path], rel_dir: str, predicate=None) -> None:
    base = ROOT / rel_dir
    if not base.is_dir():
        return
    for current, dirs, files in os.walk(base, topdown=True, followlinks=False):
        current_path = Path(current)
        rel_current = current_path.relative_to(ROOT)
        dirs[:] = sorted(
            d for d in dirs
            if not _is_excluded(rel_current / d, is_dir=True)
            and not (current_path / d).is_symlink()
        )
        for filename in sorted(files):
            rel = (current_path / filename).relative_to(ROOT)
            if _is_excluded(rel):
                continue
            if predicate is None or predicate(rel):
                _add_if_file(selected, rel)


def _is_small_report(rel: Path) -> bool:
    # Engine fixtures/statistics/anomalies are small static reports. Exclude any
    # oversized runtime/failure dump rather than pulling it into a source kit.
    if rel.suffix.casefold() != ".json":
        return False
    if any(token in rel.name.casefold() for token in ("runtime", "failure", "failed", "trace")):
        return False
    try:
        return (ROOT / rel).stat().st_size <= 10 * 1024 * 1024
    except OSError:
        return False


def select_files() -> dict[str, Path]:
    """Return an archive-name -> source-path map without writing anything."""
    selected: dict[str, Path] = {}

    # Complete frontend checkout, retaining app/prebuilt and all ordinary source,
    # but pruning generated outputs, IDE data, local settings, secrets, and logs.
    for tree in ("app", "build-logic", "codegen", "gradle", "script", "doc", "patches"):
        _walk_tree(selected, tree)

    # Project-authored data and components.
    for directory in ("custom-src", "custom-data", "input-data"):
        _walk_tree(selected, directory)

    # Engine oracle: Python/tests/docs plus bounded static JSON fixtures/reports;
    # avoids large runtime-failure reports and bytecode caches.
    _walk_tree(
        selected,
        "runtime-tests",
        predicate=lambda rel: rel.suffix.casefold() in {".py", ".md"} or _is_small_report(rel),
    )
    _walk_tree(
        selected,
        "association-tools",
        predicate=lambda rel: rel.suffix.casefold() in {".py", ".md"} or rel.as_posix() == "association-tools/generated/niuma_associations.json",
    )
    _walk_tree(
        selected,
        "debug-probe-src",
        predicate=lambda rel: rel.suffix.casefold() in {".py", ".md"} or rel.as_posix() == "app/src/debug/assets/niuma/debug_expected.json",
    )

    # Current gesture sources, deterministic generated assets, and portable build/test tooling.
    _walk_tree(selected, "gesture-tools", predicate=lambda rel: rel.suffix.casefold() in {".py", ".md", ".json", ".txt"})
    _walk_tree(
        selected,
        "runtime-tests",
        predicate=lambda rel: rel.suffix.casefold() == ".py" and rel.name not in {"scan_fixtures.py", "schema_control_check.py"},
    )
    _add_if_file(selected, Path("keyboard-gesture-tests/test_keyboard_gesture_contract.py"))
    # Include the isolated, zero-permission test host source, not its APK/key or device state.
    for test_support in (
        "gesture-input-check/device_smoke.py",
        "gesture-input-check/ui-test-host/build.ps1",
        "gesture-input-check/ui-test-host/src/main/AndroidManifest.xml",
        "gesture-input-check/ui-test-host/src/main/java/local/nium/uitest/MainActivity.java",
    ):
        _add_if_file(selected, Path(test_support))
    for name in (
        "editor-compat-check/test_contracts.py", "editor-compat-check/修复与离线验证.md", "editor-compat-check/record_results.py",
        "qq-compat-check/test_contracts.py", "qq-compat-check/修复与验证.md", "qq-compat-check/record_results.py",
        "runtime-tests/desktop-rime-android-assets-20261113-100405.json",
        "gesture-check/offline-validation.json", "gesture-check/SHA256.txt",
        "gesture-check/signature-verification.txt", "gesture-check/手势接入与离线验证.md",
        "source-packaging/build-frontend-portable.ps1", "docs/build.md",
        "source-packaging/test_package_source.py", "source-packaging/verify_source_kit.py",
        "performance-check/test_source_guards.py", "performance-check/修正说明.md",
        "runtime-tests/desktop-rime-android-assets-20261113-172734.json",
        "runtime-tests/desktop-rime-android-assets-20261113-184248.json",
        "refinement-check/test_layout_contracts.py", "refinement-check/run-device-smoke.ps1",
        "refinement-check/本批修改与验收说明.md",
        "fourcode-check/prepare_engine_check.py", "fourcode-check/test_contracts.py",
        "fourcode-check/run-device-smoke.ps1", "fourcode-check/本批修改与实测.md",
        "fourcode-check/engine-20261113-191456.json",
        "editing-check/test_contracts.py", "editing-check/run-device-smoke.ps1", "editing-check/finish-device-smoke.ps1",
        "editing-check/verify-emoji-final.ps1", "editing-check/record_results.py", "editing-check/修改与验证说明.md",
        "ui-test-host/src/main/AndroidManifest.xml", "ui-test-host/src/main/java/local/nium/uitest/MainActivity.java",
        "ui-test-host/build.ps1",
        "punctuation-check/prepare_probe.py", "punctuation-check/test_contracts.py",
        "punctuation-check/record_results.py", "punctuation-check/修复与离线验证.md",
        "punctuation-check/probe-20261113-224123.json", "punctuation-check/probe-20261113-224125.json",
        "fourcode-check/engine-20261113-224127.json",
    ):
        _add_if_file(selected, Path(name))
    # Chime English model and reproducible public reference tests; no phone snapshots.
    for name in ("stage_assets.py", "test_contracts.py", "prepare_cases.py", "benchmark.py", "create_fixtures.py",
                 "cases.json", "reference-fixtures.json", "desktop-results.json", "staged-files.json", "record_results.py", "使用与验证.md"):
        _add_if_file(selected, Path("chime-check") / name)
    _walk_tree(selected, "chime-check/upstream", predicate=lambda rel: rel.suffix.casefold() in {".py", ".bin", ".md", ".txt", ".json"})

    # Only audited inventory/notice artifacts, never downloaded archives or tool caches.
    _walk_tree(selected, "source-packaging/native-inventory", predicate=lambda rel: rel.suffix.casefold() in {".md", ".json", ".txt", ".py"})

    for name in ROOT_SCRIPTS:
        _add_if_file(selected, Path(name))
    for name in VENDOR_FILES:
        _add_if_file(selected, Path(name))
    for name in ROOT_DOCS:
        _add_if_file(selected, Path(name))
    for name in ("source-packaging/package_source.py", "source-packaging/README.md"):
        _add_if_file(selected, Path(name))

    # Offline speech source/notices and the owned staged ledger only; never phone snapshots/ports/logs.
    for name in ("offline-asr/stage_assets.py", "offline-asr/test_stage_assets.py", "offline-asr/test_contracts.py", "offline-asr/record_results.py",
                 "offline-asr/THIRD-PARTY-NOTICES.md", "offline-asr/build-native.ps1", "offline-asr/staged-files.json", "offline-asr/source-inputs.json",
                 "offline-asr/native/sense_jni.cpp", "offline-asr/native/README.md", "offline-asr/native/vendor/c-api.h",
                 "offline-asr/集成与使用说明.md"):
        _add_if_file(selected, Path(name))
    _walk_tree(selected, "offline-asr/licenses", predicate=lambda rel: rel.suffix.casefold() in {".txt", ".json"})

    # Include analysis review notes only (no raw screenshots/evidence subtrees).
    _walk_tree(selected, "analysis", predicate=lambda rel: rel.suffix.casefold() == ".md")

    # Include existing final test reports, if any, at project root.
    for path in ROOT.iterdir():
        if not path.is_file():
            continue
        low = path.name.casefold()
        if path.suffix.casefold() == ".md" and ("test-report" in low or "测试报告" in path.name):
            _add_if_file(selected, path.relative_to(ROOT))

    # Precisely the official matching arm64 APK used by the local build recipe;
    # reject a same-named but unverified file. Never include other APKs.
    apk_path = ROOT / OFFICIAL_APK
    if apk_path.is_file():
        actual = _sha256(apk_path)
        if actual != OFFICIAL_APK_SHA256:
            raise ValueError(f"Official APK hash mismatch for {OFFICIAL_APK}: {actual}")
        _add_if_file(selected, Path(OFFICIAL_APK))

    return dict(sorted(selected.items()))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _kind(rel: str) -> str:
    if rel == OFFICIAL_APK:
        return "official-release-apk"
    if rel.startswith("app/prebuilt/") and rel.casefold().endswith(".so"):
        return "precompiled-native-library"
    if rel.startswith("") and rel.casefold().endswith(".so"):
        return "precompiled-native-binary"
    if rel.casefold().endswith('.onnx') or rel.endswith('/cifg_int8.bin'):
        return "pretrained-model-weights"
    if rel.casefold().endswith('.f32'):
        return "public-test-audio-fixture"
    return "source-or-project-metadata"


def _manifest(entries: list[dict[str, object]]) -> bytes:
    doc = {
        "schemaVersion": 1,
        "archive": ARCHIVE_NAME,
        "hashAlgorithm": "SHA-256",
        "hashesCover": "Each selected input file's exact archived bytes; the manifest file itself is excluded.",
        "fileCount": len(entries),
        "files": entries,
    }
    return (json.dumps(doc, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def create_archive(force: bool = False) -> tuple[Path, Path, int]:
    """Build archive, embedded and sidecar SHA-256 manifests, then verify CRC/hash."""
    selected = select_files()
    if not selected:
        raise RuntimeError("No source files selected; refusing to create an empty kit")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = OUT_DIR / ARCHIVE_NAME
    sidecar_path = OUT_DIR / SIDECAR_NAME
    temp_path = OUT_DIR / (ARCHIVE_NAME + ".tmp")
    if not force and (archive_path.exists() or sidecar_path.exists() or temp_path.exists()):
        raise FileExistsError("Output already exists; inspect it or rerun with --force")

    entries: list[dict[str, object]] = []
    try:
        with zipfile.ZipFile(temp_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for rel, source in selected.items():
                info = _zip_info(rel)
                hasher = hashlib.sha256()
                byte_count = 0
                with source.open("rb") as src, zf.open(info, "w") as dst:
                    for chunk in iter(lambda: src.read(1024 * 1024), b""):
                        dst.write(chunk)
                        hasher.update(chunk)
                        byte_count += len(chunk)
                entries.append({
                    "path": rel,
                    "bytes": byte_count,
                    "sha256": hasher.hexdigest(),
                    "kind": _kind(rel),
                })
            manifest_bytes = _manifest(entries)
            zf.writestr(_zip_info(MANIFEST_NAME), manifest_bytes)

        with zipfile.ZipFile(temp_path, "r") as check:
            bad = check.testzip()
            if bad is not None:
                raise zipfile.BadZipFile(f"CRC error in archive member: {bad}")
            embedded = check.read(MANIFEST_NAME)
            if embedded != _manifest(entries):
                raise ValueError("Embedded SHA-256 manifest mismatch")
            for row in entries:
                data = check.read(str(row["path"]))
                if hashlib.sha256(data).hexdigest() != row["sha256"]:
                    raise ValueError(f"SHA-256 mismatch after ZIP write: {row['path']}")

        # Replace only the explicitly named output after all verification succeeds.
        if force:
            if archive_path.exists():
                archive_path.unlink()
            if sidecar_path.exists():
                sidecar_path.unlink()
        os.replace(temp_path, archive_path)
        sidecar_path.write_bytes(_manifest(entries))
        return archive_path, sidecar_path, len(entries)
    except Exception:
        if temp_path.exists():
            temp_path.unlink()
        raise


def _self_test() -> int:
    """Check real selection and the CRC verifier in memory; never package/write files."""
    files = select_files()
    required = (
        "README.md",
        "custom-src/NativeGlassRenderer.kt",
        "custom-data/niuma_glass.schema.yaml",
        "input-data/niumawb_zc.dict.yaml",
        "runtime-tests/test_oracle_static.py",
        "association-tools/build_associations.py",
        "association-tools/generated/niuma_associations.json",
        "debug-probe-src/build_expected.py",
        "app/src/debug/assets/niuma/debug_expected.json",
        "docs/design-route.md",
        "docs/provenance.md",
        "vendor/native-source-pins.json",
        "vendor/custom-assets.json",
        "vendor/association-assets.json",
        "source-packaging/package_source.py",
        "source-packaging/build-frontend-portable.ps1",
        "docs/build.md",
        "stage-gesture-data.py",
        "vendor/gesture-assets.json",
        "gesture-tools/build_z_transitions.py",
        "gesture-tools/generated/niuma_code_paths.txt",
        "gesture-tools/generated/niuma_z_transitions.json",
        "app/src/main/java/com/osfans/trime/ime/gesture/NiumaGestureRules.kt",
        "app/src/main/java/com/osfans/trime/ime/gesture/NiumaGestureExecutor.kt",
        "app/src/test/java/com/osfans/trime/ime/gesture/NiumaGestureRulesTest.kt",
        "app/src/test/java/com/osfans/trime/ime/gesture/NiumaGestureExecutorTest.kt",
    )
    missing = [item for item in required if item not in files]
    if missing:
        raise AssertionError("Required source inputs are missing: " + ", ".join(missing))
    if OFFICIAL_APK in files and _kind(OFFICIAL_APK) != "official-release-apk":
        raise AssertionError("Official APK kind classification failed")

    forbidden = (
        "app/build/intermediates/x",
        ".idea/workspace.xml",
        "local.properties",
        "keys/release.jks",
        "app/build/outputs/apk/debug/app-debug.apk",
        "vendor/platform-37.0_r02.zip",
        "vendor/sdk-license-consent.json",
        "test-device-session.ps1",
    )
    wrongly_selected = [item for item in forbidden if item in files]
    if wrongly_selected:
        raise AssertionError("Forbidden paths selected: " + ", ".join(wrongly_selected))
    for item in (*forbidden[:5], forbidden[6]):
        if not _is_excluded(Path(item)):
            raise AssertionError(f"Exclusion rule regression: {item}")
    if "vendor/platform-37.0_r02.zip" in files or "vendor/sdk-license-consent.json" in files:
        raise AssertionError("SDK archives and consent records must be absent from the allowlisted selection")

    # Exercise ZIP CRC verification entirely in memory, without touching out/.
    memory = io.BytesIO()
    with zipfile.ZipFile(memory, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("probe.txt", b"crc-check")
    memory.seek(0)
    with zipfile.ZipFile(memory, "r") as zf:
        if zf.testzip() is not None:
            raise AssertionError("In-memory ZIP CRC test failed")

    print(json.dumps({
        "self_test": "passed",
        "packaging_performed": False,
        "output_created": False,
        "selected_file_count": len(files),
        "required_inputs": len(required),
        "verified_prebuilt_so_count": sum(1 for name in files if _kind(name) == "precompiled-native-library"),
        "official_apk_included": OFFICIAL_APK in files,
        "crc_self_test": "passed (in-memory probe only)",
        "excluded_sensitive_or_generated_samples": len(forbidden),
    }, ensure_ascii=False, indent=2))
    return 0


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--self-test", action="store_true", help="check path selection only; do not create an archive")
    group.add_argument("--list", action="store_true", help="list selected archive paths; do not create an archive")
    parser.add_argument("--force", action="store_true", help="replace only the named output ZIP/manifest after verification")
    args = parser.parse_args(argv)
    try:
        if args.self_test:
            return _self_test()
        if args.list:
            for rel in select_files():
                print(f"{_kind(rel):32} {rel}")
            return 0
        archive, manifest, count = create_archive(force=args.force)
        print(json.dumps({
            "archive": str(archive),
            "manifest": str(manifest),
            "source_files": count,
            "crc_check": "passed",
        }, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"package_source: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
