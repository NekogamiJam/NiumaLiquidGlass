# Trime v3.3.13 native-source provenance

**Snapshot:** upstream Trime `osfans/trime` commit `b5cad1a49095a3652e9188982de400ca3a653f37` (release `v3.3.13`). This note records source references and submodule pins relevant to the frontend and JNI/native payload. It is not a legal opinion or a build/reproducibility attestation.

## Findings at a glance

- The pinned Trime Git tree contains **10 direct gitlinks** under `app/src/main/jni` and `app/data/rime`: six JNI-side modules and four Rime data repositories. Their exact repository URLs and commit IDs are recorded in [`vendor/native-source-pins.json`](../vendor/native-source-pins.json).
- A GitHub-generated Trime source ZIP/TAR contains the frontend repository snapshot, but **does not expand gitlinks into their submodule source contents**. Therefore that source archive alone is not the complete source corresponding to the precompiled JNI/native components. Each pinned submodule must be obtained separately at its recorded commit.
- `app/src/main/jni/librime_jni` is an ordinary directory in the Trime repository at the pinned commit, not a gitlink.
- Direct gitlink pins do not recursively enumerate dependencies or gitlinks inside those upstream repositories. Nor do source pins establish that a release APK was built from precisely these sources.

## Method and evidence

1. Queried the GitHub Git Trees API for the exact Trime commit with `recursive=1`:
   [`GET /repos/osfans/trime/git/trees/b5cad1a49095a3652e9188982de400ca3a653f37?recursive=1`](https://api.github.com/repos/osfans/trime/git/trees/b5cad1a49095a3652e9188982de400ca3a653f37?recursive=1). The API response reported `truncated: false`. Gitlink entries are mode `160000`, type `commit`; their object IDs identify the pinned submodule commits, not expanded source trees.
2. Matched those gitlink paths to `.gitmodules` remote URLs at the same checkout/revision. The local `.gitmodules` and `trime/.gitmodules` copies were checked and list the same ten URLs; `librime-lua-deps` includes the `thirdparty` branch hint. GitHub commit/tree endpoints were used to confirm the pin references. The `librime-lua-deps` SHA is a valid commit in `hchunhui/librime-lua` and is the `thirdparty` branch tip observed during this research; its pinned root tree has `lua5.3`, `lua5.4`, and `lua5.5` directories.
3. Checked pinned repository-root license files when available and read the license text to record best-effort SPDX identifiers. License files and blob IDs are in the JSON. For `librime-lua-deps`, no root `LICENSE` appeared in the pinned root tree listing; the license status is unresolved, not presumed absent for individual files.
4. Checked the official `v3.3.13` release API metadata. It lists four ABI-specific APK assets whose names include `gb5cad1a4`, the short prefix of the pinned source commit; their SHA-256 values and sizes are recorded in the JSON. The APKs were not downloaded or inspected, so asset names and checksums do not prove source-to-binary correspondence.

## Direct upstream pins

All paths below are in the Trime commit above. Resolve the complete list, individual commit links, and license-file blob IDs from [`vendor/native-source-pins.json`](../vendor/native-source-pins.json).

| Trime path | Upstream repository | Pinned commit | Root license observation |
|---|---|---|---|
| `app/src/main/jni/snappy` | [google/snappy](https://github.com/google/snappy) | [`9c28114a38866f6deeaa826db918293bc28ae410`](https://github.com/google/snappy/commit/9c28114a38866f6deeaa826db918293bc28ae410) | `COPYING`: BSD-3-Clause; separately licensed testdata items are noted there. |
| `app/src/main/jni/librime` | [rime/librime](https://github.com/rime/librime) | [`56737cd29a4d393a104a2098ece3697aa2810465`](https://github.com/rime/librime/commit/56737cd29a4d393a104a2098ece3697aa2810465) | `LICENSE`: BSD-3-Clause. |
| `app/src/main/jni/librime-lua` | [hchunhui/librime-lua](https://github.com/hchunhui/librime-lua) | [`6da5d6cedb853380ed1b5b9b6979cb1a9c028353`](https://github.com/hchunhui/librime-lua/commit/6da5d6cedb853380ed1b5b9b6979cb1a9c028353) | `LICENSE`: BSD-3-Clause. |
| `app/src/main/jni/librime-lua-deps` | [hchunhui/librime-lua](https://github.com/hchunhui/librime-lua) | [`9e5bb71db1544913f8005dadc3df8439c00d08b7`](https://github.com/hchunhui/librime-lua/commit/9e5bb71db1544913f8005dadc3df8439c00d08b7) | No root `LICENSE` located in pinned root listing; unresolved. Contains Lua source trees; inspect per-file/upstream notices before redistribution. |
| `app/src/main/jni/librime-octagram` | [lotem/librime-octagram](https://github.com/lotem/librime-octagram) | [`bd12863f45fbbd5c7db06d5ec8be8987b10253bf`](https://github.com/lotem/librime-octagram/commit/bd12863f45fbbd5c7db06d5ec8be8987b10253bf) | `LICENSE`: GPL-3.0-only. |
| `app/src/main/jni/librime-predict` | [rime/librime-predict](https://github.com/rime/librime-predict) | [`67c2881914a0242fc14ec7d6877782771b75800c`](https://github.com/rime/librime-predict/commit/67c2881914a0242fc14ec7d6877782771b75800c) | `LICENSE`: BSD-3-Clause. |
| `app/data/rime/prelude` | [rime/rime-prelude](https://github.com/rime/rime-prelude) | [`3803f09458072e03b9ed396692ce7e1d35c88c95`](https://github.com/rime/rime-prelude/commit/3803f09458072e03b9ed396692ce7e1d35c88c95) | `LICENSE`: LGPL-3.0-only. |
| `app/data/rime/luna-pinyin` | [rime/rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin) | [`46acf03142c12b5aeed7002675046bf7255eed35`](https://github.com/rime/rime-luna-pinyin/commit/46acf03142c12b5aeed7002675046bf7255eed35) | `LICENSE`: LGPL-3.0-only. |
| `app/data/rime/stroke` | [rime/rime-stroke](https://github.com/rime/rime-stroke) | [`7c9874c6b2e0b94947653e9a7de6f99623ff27e4`](https://github.com/rime/rime-stroke/commit/7c9874c6b2e0b94947653e9a7de6f99623ff27e4) | `LICENSE`: LGPL-3.0-only. |
| `app/data/rime/essay` | [rime/rime-essay](https://github.com/rime/rime-essay) | [`0766c929ec3e578c2c80861e988decd3703a1c3d`](https://github.com/rime/rime-essay/commit/0766c929ec3e578c2c80861e988decd3703a1c3d) | `LICENSE`: LGPL-3.0-only. |

`app/src/main/jni/librime_jni` is in-repository source at the Trime commit, not a separately pinned repository. It is not one of the ten gitlinks.

## Source archives and release artifacts

- Frontend source archives: [GitHub ZIP](https://github.com/osfans/trime/archive/refs/tags/v3.3.13.zip) · [GitHub TAR.GZ](https://github.com/osfans/trime/archive/refs/tags/v3.3.13.tar.gz).
- Release: [Trime v3.3.13](https://github.com/osfans/trime/releases/tag/v3.3.13) · [release API metadata](https://api.github.com/repos/osfans/trime/releases/tags/v3.3.13).
- The source archive omits the ten submodule contents. The release API's APK metadata was checked, but binaries were neither fetched nor inspected. Do not treat the archive or the commit-like APK filename suffix as proof of complete native-source delivery or reproducible correspondence.

## Scope and licensing caution

This is a direct-gitlink inventory, not a recursive dependency bill of materials. It does not audit transitive build downloads, nested gitlinks, all per-file notices, the APK's bundled libraries, or legal compatibility of a combined distribution. SPDX identifiers are concise labels based on inspected pinned root license texts; they do not replace the full terms. No license was accepted or changed as part of this research. Before redistributing binaries or sources, review the complete applicable licenses and notices, including vendored Lua/testdata and transitive components.

No source dependencies or APKs were downloaded; no device/ADB interaction, build, or license acceptance was performed.
