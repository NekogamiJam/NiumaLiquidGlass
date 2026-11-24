# Debug-only Android Rime engine probe

This directory is a **source template and offline expected-map generator only**. Nothing here is wired into `trime-stable`; no Gradle build, ADB command, or app run was performed.

## What the probe checks

`EngineProbeReceiver.kt` initializes the existing `Rime` static JNI API in a fresh private `filesDir/engine-probe/<UUID>/` tree, on the isolated `:enginecheck` process. It copies only shared `.yaml`/`.yml`/`.txt` files and `shared/opencc/*.json`, skips any path component named `build`, creates a fresh user directory and `default.custom.yaml`, then explicitly selects `niuma_glass` and sets `ascii_mode=false` through Rime. It does not call the IME service, read an editor, clipboard, or user dictionary, or write outside app-private `filesDir`.

The receiver streams the expected JSON (rather than retaining all 170,277 code entries in memory), verifies the APK shared dictionary SHA-256 values against both the generated manifest and hard-coded pins, then checks every code against `getRimeCandidates(0, expected.size + 1).map { it.text }` in exact order. It traverses sorted codes using shared-prefix typing/backspaces to reduce JNI key calls. It also performs up to 9 deterministic postselection checks across 3 codes: select first/middle/last with `selectRimeCandidate(index, true)`, call and discard `getRimeCommit().text`, then re-query the code and compare its candidate order. Output contains only static dictionary data in at most 20 mismatch examples.

The fixed result is `context.filesDir/android-engine-check.json`. A `status=running` record is written first and progress is atomically replaced every 2,500 codes; final output is written to a same-directory temporary file and atomically renamed. If Android terminates the broadcast process before completion, the last report stays `running` with progress rather than pretending the sweep passed. `goAsync()` still inherits Android broadcast execution limits; the complete 170k-code run may need a different debug entry point if the device stops it. Rime's current native traits set `log_dir` to empty, so native logs go to logcat; a per-run `log/` directory is created but is not a redirected native log sink.

This is a **JNI engine-only** test, not proof of keyboard UI, Android key-event mapping, post-commit prediction UI, or full application behavior. Android still creates the app's `Application` in the isolated process, although the receiver itself does not use an editor or the IME session.

## Generate the expected map

From the project root:

```text
python debug-probe-src/build_expected.py
```

The standard-library script reads only `input-data/niumawb.dict.yaml`, `input-data/niumawb_zc.dict.yaml`, and `input-data/niumawb_fh.dict.yaml`. It refuses to run if their fixed SHA-256 pins differ, checks that the main dictionary imports `niumawb_zc` then `niumawb_fh`, parses named `columns` (ignoring inline `#` comments), and constructs a stable-first unique text list for each code in that source order. It writes only:

`debug-probe-src/generated/niuma/debug_expected.json`

The generated file records source hashes, source-row/duplicate counts, code/candidate counts, max candidates per code, and the code-to-text mapping. Current pinned corpus stats: 170,277 distinct codes; 285,676 stable-unique candidate entries; max 52 candidates per code.

## Debug-only integration (parent action; not done here)

1. Place `EngineProbeReceiver.kt` under `app/src/debug/java/com/osfans/trime/debug/EngineProbeReceiver.kt`.
2. Merge the receiver declaration from `debug-probe-src/AndroidManifest.xml` into the **debug-only** manifest (do not add it to `src/main` or release). It is exported, protected by `android.permission.DUMP`, and isolated in `:enginecheck`; no new `<uses-permission>` is required.
3. Copy the generated JSON to `app/src/debug/assets/niuma/debug_expected.json` (create directories as needed). It must be packaged as an APK asset at `niuma/debug_expected.json`.
4. After the parent builds/installs the debug APK, trigger and read it with:

```text
adb shell am broadcast -n local.niuma.glass.debug/com.osfans.trime.debug.EngineProbeReceiver -a local.niuma.glass.debug.ENGINE_CHECK
adb shell run-as local.niuma.glass.debug cat files/android-engine-check.json
```

A successful full result must say `status=passed`, show all 170,277 codes tested, and have zero candidate-order, key-input, and postselection mismatches. A partial `running` report is not a pass.

## Current offline generator run

The generator completed successfully; output hash: `73f77da4d14535405d020b769b5c6ffe527a4ef8a36afdf052ec02937aa1d44e`. Source rows/duplicates: zc 285,039 rows (46 repeated same-code/text entries removed); fh 684 rows (1 removed). No Android build or device run was performed.
