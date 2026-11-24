# Niuma Liquid Glass

An Android Rime input method based on [Trime](https://github.com/osfans/trime), with a Niuma four-code schema and dictionaries, a translucent glass keyboard interface, gesture editing, local English completion, and optional on-device speech recognition.

## What is included

- Trime v3.3.13 frontend, pinned to upstream commit `b5cad1a49095a3652e9188982de400ca3a653f37`.
- Niuma input schema, Lua processor, dictionaries, completion/association data and keyboard behavior.
- Glass-style keyboard UI, gesture controls, clipboard and candidate-bar improvements.
- Optional local English suggestion model and offline speech-recognition integration.
- Source and contract tests, asset staging tools, license notices, and pinned dependency metadata.

Large neural-network and prebuilt native binaries are intentionally not committed: the 228 MiB SenseVoice model exceeds GitHub's 100 MiB per-file limit. Restore all five pinned files after cloning with `python tools/fetch-binaries.py`; the helper verifies SHA-256 before installing them. See [Build](docs/build.md).

## Build and test

Use JDK 17+, Android SDK with the required platform/build-tools, Python 3, and Gradle 9.8.1. Clone with submodules, restore binary assets, then follow [the reproducible build notes](docs/build.md). The release APK is arm64-v8a. Device testing is not performed by the source-package self-tests.

## Repository map

- `app/` — Android app and assets.
- `custom-src/`, `custom-data/`, `input-data/` — project-owned Kotlin, Rime configuration, and dictionaries.
- `runtime-tests/`, `keyboard-gesture-tests/`, `app/src/test/` — probes and contract/unit tests.
- `association-tools/`, `gesture-tools/`, `offline-asr/`, `chime-check/` — data/model build and validation tools.
- `vendor/`, `docs/` — dependency pins, source provenance, build, licensing, and change notes.
- `tools/binaries.json` — expected paths, sizes, hashes and release download URLs for omitted binary inputs.

## Attribution and license

This is a Trime-derived project; see [upstream provenance](docs/provenance.md), [license notes](docs/licenses.md), and the upstream license files. Project-specific code and configuration are published under GPL-3.0-or-later, subject to the licenses of bundled third-party components and data.
