# Reproducible build

## Requirements

- Windows or Linux with JDK 17 or later, Android SDK (Android platform and build-tools required by the Gradle project), Python 3, and Git with submodule support.
- Gradle 9.8.1 (the Gradle wrapper is included).
- Internet access for Gradle dependencies, Git submodules, and restoring the pinned large binary assets.

## Checkout and binary inputs

Clone recursively so all native and Rime data submodules match the gitlinks:

```sh
git clone --recurse-submodules https://github.com/NekogamiJam/NiumaLiquidGlass.git
cd NiumaLiquidGlass
python tools/fetch-binaries.py
python tools/fetch-binaries.py --check-only
```

`tools/binaries.json` pins five binary inputs by path, size and SHA-256. The fetch helper downloads them from the `niuma-0.1.0` GitHub release and verifies each temporary file before replacing the destination. For air-gapped testing, use `--from-dir <directory>` with files named as in the manifest.

## Build

Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, then run:

```sh
./gradlew :app:assembleArm64Release
```

On Windows, use `gradlew.bat`. The repository also includes `build-offline-prepared.ps1` for staged asset verification and build orchestration; it accepts `-SdkRoot` and `-GradleExecutable` overrides and places Gradle's local cache in `.gradle-cache/`.

## Tests

Run JVM tests with `./gradlew :app:testArm64ReleaseUnitTest` (or the matching release unit-test task available in the installed Android Gradle Plugin). Python source/contract tests can be run independently, for example:

```sh
python source-packaging/package_source.py --self-test
python keyboard-gesture-tests/test_keyboard_gesture_contract.py
python association-tools/test_associations.py
python offline-asr/test_stage_assets.py
python gesture-tools/test_stage_gesture_data.py
```

`package_source.py --self-test` validates the selected-file list, required inputs, official upstream APK inclusion and archive CRC behavior without producing an archive. Desktop Rime runtime probes require an explicitly configured `NIUMA_RIME_DLL_DIR`; no device/ADB actions are performed by these tests.

## Asset integrity

`app/src/main/assets/checksums.json` records packaged asset checksums. `offline-asr/source-inputs.json`, `vendor/native-source-pins.json`, and `chime-check/downloads.json` record source hashes and revisions for generated or externally fetched inputs. Do not commit local build output, device snapshots, signing keys, or downloaded model binaries to Git.
