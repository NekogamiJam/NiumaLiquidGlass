# Offline niuma Z-tap transition table

This directory contains a standalone, standard-library-only offline generator for the `niuma_glass` gesture rule. It does not change app/runtime sources and does not connect to Rime, Android, ADB, a device, a microphone, or the network.

## Inputs and checks

`build_z_transitions.py` reads the pinned `../debug-probe-src/generated/niuma/debug_expected.json`. It refuses to run unless that file's SHA-256 is exactly `73f77da4d14535405d020b769b5c6ffe527a4ef8a36afdf052ec02937aa1d44e`, the schema is `niuma_glass`, the code count is 170277, and all three embedded `source_sha256` values equal the pinned hashes. It also hashes the actual bytes of `input-data/niumawb.dict.yaml`, `input-data/niumawb_zc.dict.yaml`, and `input-data/niumawb_fh.dict.yaml` and rejects modified sources, even if the expected fixture is unchanged.

## Generated data

Run from this directory with Python 3:

    python build_z_transitions.py

This generates `generated/niuma_z_transitions.json` and `generated/niuma_code_paths.txt`. The JSON has a fixed shape:

    {"format":1,"schema_id":"niuma_glass","source_expected_sha256":"…","code_count":170277,"extend_z_prefixes":[…]}

`niuma_code_paths.txt` contains only the 170277 unique source code paths in strict sorted order, one lowercase ASCII code per LF-terminated line; it contains no candidate text. `extend_z_prefixes` is the sorted, deduplicated set of nonempty letter prefixes `p` for which `p + "z"` is itself a prefix of at least one complete alphabetic source code. A runtime can use membership of current raw code `p` to decide whether tapping Z must remain an input keystroke; up-swipe-Z is a forced global candidate-3 action per the agreed rule.

`stage-gesture-data.py` at the repository root is a separate, explicit stager. It re-validates the pinned input and actual source bytes, recomputes expected bytes, checks generated files byte-for-byte, and refuses to replace assets unless absent, already identical, or still hash-owned by its prior `vendor/gesture-assets.json` manifest. It is intentionally not executed by this tooling workflow.

## Tests and offline evidence

Run `python -m unittest -v` here. Tests use only Python's standard library and cover noninitial-Z extraction, initial-Z exclusion, deduplication/sort order, output shape, and rejection of mismatched source hashes. The generated table is a static-data artifact; testing it is not Android or device validation.

For the full-table static audit, the generator derives the set from every one of the 170277 source code paths. The associated project audit reports 26 noninitial-Z positions and 16 positions previously vulnerable to third-candidate interception. The generated table allows those positions to be excluded from tap-Z interception exactly when the current raw prefix can still be extended by Z; checking these paths is static analysis only.
