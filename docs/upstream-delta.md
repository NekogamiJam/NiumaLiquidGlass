# Upstream delta

Baseline: `osfans/trime` v3.3.13, commit `b5cad1a49095a3652e9188982de400ca3a653f37` (see [provenance](provenance.md)). Newline-normalized comparison recorded 470 upstream files versus 602 production frontend files: 132 added, 0 removed, and 40 changed. Seventeen changed asset paths are upstream symlinks materialized as ordinary files from pinned data commits; the other changes are frontend and build integration edits.

## Main areas of change

- Updated Trime input service, Rime session/data handling, candidate/preedit rendering, keyboard event handling, symbol panel, and application/build configuration.
- Added project-owned Kotlin under `ime/glass/`, `ime/gesture/`, `ime/voice/`, `ime/english/`, `ime/association/`, and `ime/ui/`; included vector assets and theme resources.
- Added debug-only engine/English/offline-ASR probes and JVM test suites; upstream tests remain present.
- Added Niuma schema, Lua processor, dictionaries, OpenCC/config assets, associations, code-path and Z-transition data.
- Added offline-ASR and Chime integration, license notices, source/hash manifests, and reproducible staging/test tools.

## Large binaries

SenseVoice weights, Silero VAD weights, ONNX Runtime, Sherpa-ONNX, and the prebuilt Rime JNI library are intentionally excluded from Git. Restore them with `tools/fetch-binaries.py` using the exact path, byte-size and SHA-256 pins in `tools/binaries.json`. The upstream official APK/source archive remain small reference artifacts under `vendor/`.

The detailed file-level comparison was performed before publication; generated build output, device captures and machine-local files are excluded from this repository.
