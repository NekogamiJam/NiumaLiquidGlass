# License and notices

The project-specific Trime-derived application changes and configuration are distributed under GPL-3.0-or-later, consistent with the upstream project license. Review the upstream `LICENSE` and component-level notices before reusing any portion.

The pinned native/source dependencies have their own terms, recorded in `vendor/native-source-pins.json` and each submodule. The recorded identifiers include BSD-3-Clause (Snappy, librime, librime-lua, librime-predict), GPL-3.0-only (librime-octagram), and LGPL-3.0-only (Rime prelude, Luna Pinyin, stroke, essay). No license was found at the pinned root of librime-lua-deps; verify the component's distribution terms independently.

The offline speech integration includes separate notices and source references under `offline-asr/licenses/` and `offline-asr/THIRD-PARTY-NOTICES.md`. The Chime model/data carries its Apache-2.0 notice under `chime-check/upstream/`; see `chime-check/downloads.json` for the pinned source revision. Model terms may differ from source-code terms; consult the original license for each model before use or redistribution.

The official Trime APK and source archive in `vendor/` are upstream reference artifacts and retain their upstream license and redistribution conditions. This notice is informational, not legal advice.
