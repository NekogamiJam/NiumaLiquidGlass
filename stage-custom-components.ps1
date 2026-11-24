# Stage only the owned Kotlin additions. No ADB/phone or global environment changes.
$ErrorActionPreference='Stop'
$root=Join-Path $PSScriptRoot 'app\src\main\java\com\osfans\trime\ime'
$manifest=Join-Path $PSScriptRoot 'vendor\custom-components.json'
$previous=@{}
if(Test-Path -LiteralPath $manifest){foreach($row in (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)){$previous[$row.name]=$row.sha256}}
$rows=@()
foreach($pair in @(@('NativeGlassRenderer.kt','glass'),@('GlassInputPanelController.kt','glass'),@('MaterialApplyGate.kt','glass'),@('GlassDiagnosticWriter.kt','glass'),@('LiquidGlassStyle.kt','glass'),@('PanelEdgeGlints.kt','glass'),@('GlassKeySurface.kt','glass'),@('CandidateStripAppearance.kt','glass'),@('SystemVoiceInput.kt','voice'),@('VoicePermissionActivity.kt','voice'),@('NiumaVoiceController.kt','voice'),@('LocalAsrNative.kt','voice'),@('OfflineAsrModels.kt','voice'),@('OfflineTranscriptPolicy.kt','voice'),@('OfflineVoiceInput.kt','voice'))){
 $src=Join-Path $PSScriptRoot ('custom-src\'+$pair[0])
 $dst=Join-Path $root ($pair[1]+'\'+$pair[0])
 $sha=(Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
 if(Test-Path -LiteralPath $dst){
  $old=(Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
  if($old -ne $sha -and $old -ne $previous[$pair[0]]){throw "Refusing to replace an unowned or externally modified Kotlin file: $dst"}
 }
 $rows+=@{name=$pair[0];source=$src;destination=$dst;sha256=$sha}
}
foreach($row in $rows){
 New-Item -ItemType Directory -Force -Path (Split-Path $row.destination) | Out-Null
 if(-not(Test-Path -LiteralPath $row.destination) -or (Get-FileHash -LiteralPath $row.destination -Algorithm SHA256).Hash -ne $row.sha256){Copy-Item -LiteralPath $row.source -Destination $row.destination -Force}
 if((Get-FileHash -LiteralPath $row.destination -Algorithm SHA256).Hash -ne $row.sha256){throw 'Kotlin staging digest mismatch'}
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifest -Encoding utf8
$rows | Select-Object name,sha256
