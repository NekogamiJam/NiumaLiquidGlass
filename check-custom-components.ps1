# Isolated Kotlin source check only. This is not a full Trime build or Android runtime test.
param(
 [string]$ToolRoot = (Join-Path $PSScriptRoot '../../99-tools/build-environment'),
 [Parameter(Mandatory)][string]$JavaHome
)
$ErrorActionPreference='Stop'
$tools=(Resolve-Path -LiteralPath $ToolRoot).Path
$lib=Join-Path $tools 'gradle-bootstrap/gradle-9.8.1/lib'
$android=Join-Path $tools 'android-sdk/platforms/android-37.0/android.jar'
$stdlib=(Get-ChildItem -LiteralPath $lib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1).FullName
$cp="$android;$stdlib"
$out=Join-Path $PSScriptRoot 'custom-component-check'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sources=@('NativeGlassRenderer.kt','GlassInputPanelController.kt','MaterialApplyGate.kt','GlassDiagnosticWriter.kt','LiquidGlassStyle.kt','PanelEdgeGlints.kt','GlassKeySurface.kt','CandidateStripAppearance.kt','SystemVoiceInput.kt','VoicePermissionActivity.kt','NiumaVoiceController.kt','LocalAsrNative.kt','OfflineAsrModels.kt','OfflineTranscriptPolicy.kt','OfflineVoiceInput.kt') | ForEach-Object {Join-Path $PSScriptRoot ('custom-src\'+$_)}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -language-version 2.3 -api-version 2.3 -jvm-target 17 -no-stdlib -no-reflect -classpath $cp -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Isolated custom Kotlin components failed compilation'}
[ordered]@{scope='Only standalone Kotlin helpers, not Trime integration or Android runtime';language_version='2.3';sdk='Official Android 37.0 android.jar';sources=@($sources | ForEach-Object {@{name=(Split-Path $_ -Leaf);sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}});phone_access=$false;completed_at=(Get-Date).ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'report.json') -Encoding utf8
Write-Output 'Standalone glass/voice helpers compiled; full application and device validation remain separate.'
