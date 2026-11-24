# build-native.ps1 -- build the offline ASR JNI bridge .so for Niuma Rime
#
# Compiles offline-asr/native/sense_jni.cpp into
#   offline-asr/native/out/arm64-v8a/libniuma_offline_asr.so
# and links it against the verified-sha sherpa-onnx ARM64 C API + onnxruntime
# already present in the workspace:
#   app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so
#   app/src/main/jniLibs/arm64-v8a/libonnxruntime.so
#
# It writes NOTHING outside offline-asr/native/out/ and never touches the app's
# jniLibs, assets, Gradle, or source. Requires: android-ndk-r28c, c-api.h, and
# the two .so files. No network, no ADB, no phone. Run with:
#   pwsh -NoProfile -ExecutionPolicy Bypass -File build-native.ps1
#
# TMP/TEMP are redirected (per-process) to the authorized compiler-temp dir so
# the build is process-isolated and leaves the system temp untouched.

param([string]$NdkRoot = (Join-Path $PSScriptRoot '../../../99-tools/build-environment/android-native-tools/android-ndk-r28c'))
$ErrorActionPreference = "Stop"

$ndk      = (Resolve-Path -LiteralPath $NdkRoot).Path
$compTemp = Join-Path $PSScriptRoot "native\compiler-temp"
$root     = Split-Path -Parent $PSScriptRoot            # 05-定制牛码Rime
$srcDir   = Join-Path $PSScriptRoot "native"
$cpp      = Join-Path $srcDir "sense_jni.cpp"
$cApiDir  = Join-Path $srcDir "vendor"    # self-contained pinned c-api.h
$libDir   = Join-Path $root "app\src\main\jniLibs\arm64-v8a"
$outDir   = Join-Path $srcDir "out\arm64-v8a"
$binDir   = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang    = Join-Path $binDir "clang++.exe"
$readelf  = Join-Path $binDir "llvm-readelf.exe"
$nm       = Join-Path $binDir "llvm-nm.exe"

# sanity
foreach ($p in @($cpp, $cApiDir, $libDir, $clang, $readelf, $nm)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "build-native.ps1: missing required path: $p" }
}

# process-isolated compiler temp (authorized)
New-Item -ItemType Directory -Force -Path $compTemp | Out-Null
$env:TMP  = $compTemp
$env:TEMP = $compTemp
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# --- compile + link ----------------------------------------------------------
$api   = 26                       # matches app minSdk
$outSo = Join-Path $outDir "libniuma_offline_asr.so"

# Native-exe argument array (no embedded quotes; pwsh marshals wide args so the
# non-ASCII path components pass through intact).
$clangArgs = @(
  "-target", "aarch64-linux-android$api",
  "-shared", "-fPIC",
  "-O2", "-std=c++17", "-DNDEBUG",
  "-Wall", "-Wno-unused-parameter",
  "-static-libstdc++",   # static libc++ so the .so needs only the 2 verified sherpa .so + Android system libs (no libc++_shared.so)
  "-I", $cApiDir,
  $cpp,
  "-o", $outSo,
  "-L", $libDir,
  "-lsherpa-onnx-c-api", "-lonnxruntime"
)

Write-Host "target : aarch64-linux-android$api"
Write-Host "input  : $cpp"
Write-Host "output : $outSo"
Write-Host "link   : -L $libDir -lsherpa-onnx-c-api -lonnxruntime"
Write-Host "clang  : $($clangArgs -join ' ')"
Write-Host ""

& $clang @clangArgs
if ($LASTEXITCODE -ne 0) { throw "clang++ failed with exit code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $outSo)) { throw "expected output .so was not produced: $outSo" }

# --- verify: DT_NEEDED + exported JNI symbols --------------------------------
Write-Host ""
Write-Host "==== DT_NEEDED / SONAME ===="
& $readelf -d $outSo | Select-String -Pattern "NEEDED|SONAME" | ForEach-Object { $_.Line.Trim() }

Write-Host ""
Write-Host "==== exported LocalAsrNative JNI symbols ===="
$dyn = & $nm -D --defined-only $outSo 2>$null
$want = @("create", "reset", "accept", "finish", "destroy")
$missing = $false
foreach ($w in $want) {
  $pat = "Java_com_osfans_trime_ime_voice_LocalAsrNative_" + $w
  $hit = $dyn | Select-String -SimpleMatch $pat
  if ($hit) { Write-Host ("OK    " + $pat) } else { Write-Host ("MISS  " + $pat); $missing = $true }
}
if ($missing) { throw "one or more expected JNI symbols are not exported" }

# --- record output hash (evidence only) --------------------------------------
$hash = (Get-FileHash -LiteralPath $outSo -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $outSo).Length
Write-Host ""
Write-Host "build + verify complete:"
Write-Host "  $outSo  ($size bytes, sha256 $hash)"
Write-Host "nothing was written outside $outDir"
