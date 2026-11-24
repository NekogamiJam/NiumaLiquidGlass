# Upstream provenance

## Trime frontend

This repository is based on [`osfans/trime`](https://github.com/osfans/trime), release tag `v3.3.13`, commit [`b5cad1a49095a3652e9188982de400ca3a653f37`](https://github.com/osfans/trime/commit/b5cad1a49095a3652e9188982de400ca3a653f37). The upstream source archive and official release APK are recorded, with hashes, in `vendor/native-source-pins.json`.

## Native and Rime data dependencies

The `.gitmodules` file declares ten submodules; exact commits are recorded in `vendor/native-source-pins.json`. The pinned graph includes Snappy, librime, librime-lua, librime-lua-deps, librime-octagram, librime-predict, Rime prelude, Luna Pinyin, stroke, and essay. The source package uses gitlinks, not copied native source trees; clone with `--recurse-submodules`.

The upstream Trime source archive does not expand gitlinks and therefore is not by itself a complete native/JNI source checkout. The project supplements it with its source inventory and provenance notes under `source-packaging/native-inventory/` and `docs/upstream-provenance-source.md`.

## Project and external model data

The Chime model revision and download details are pinned in `chime-check/downloads.json`; offline speech model and native library hashes are recorded in `offline-asr/source-inputs.json` and `tools/binaries.json`. See `docs/licenses.md` and the adjacent license/notices files before redistributing any third-party component or model.
