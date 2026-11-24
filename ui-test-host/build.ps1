param(
    [Parameter(Mandatory)] [string] $SdkRoot,
    [Parameter(Mandatory)] [string] $JdkRoot
)

$ErrorActionPreference = 'Stop'
$realRoot = $PSScriptRoot
$buildToolsVersion = '36.0.0'

$android36 = Join-Path $sdkRoot 'platforms\android-36\android.jar'
$android37 = Join-Path $sdkRoot 'platforms\android-37.0\android.jar'
if (Test-Path -LiteralPath $android36) {
    $androidJar = $android36
    $compileApi = '36'
} elseif (Test-Path -LiteralPath $android37) {
    $androidJar = $android37
    $compileApi = '37'
} else {
    throw 'Android SDK platform android-36 or android-37.0 is required.'
}
$toolsReal = Join-Path $sdkRoot "build-tools\$buildToolsVersion"
foreach ($path in @(
    $androidJar,
    (Join-Path $toolsReal 'aapt2.exe'),
    (Join-Path $toolsReal 'zipalign.exe'),
    (Join-Path $toolsReal 'lib\d8.jar'),
    (Join-Path $toolsReal 'lib\apksigner.jar'),
    (Join-Path $jdkRoot 'bin\javac.exe'),
    (Join-Path $jdkRoot 'bin\jar.exe'),
    (Join-Path $jdkRoot 'bin\keytool.exe'),
    (Join-Path $jdkRoot 'bin\java.exe')
)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required build tool not found: $path" }
}

$manifestReal = Join-Path $realRoot 'src\main\AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $manifestReal)) { throw 'AndroidManifest.xml is missing.' }
$manifestText = [IO.File]::ReadAllText($manifestReal)
if ($manifestText -match '<uses-permission\b|<service\b|<receiver\b|<provider\b') {
    throw 'The test host manifest must have no permissions or background components.'
}
if ($manifestText -notmatch 'android:debuggable="true"') {
    throw 'The test host manifest must explicitly enable debuggable for its private test-state readback.'
}

$env:PATH = (Join-Path $jdkRoot 'bin') + ';' + $env:PATH
$tempBase = $env:TEMP
if ([string]::IsNullOrWhiteSpace($tempBase) -or $tempBase -match '[^\x00-\x7F]') {
    throw 'TEMP must be an ASCII path for the temporary junction build.'
}
$temp = Join-Path $tempBase ('NiumaUiTestHost-' + [guid]::NewGuid().ToString('N'))
$rootAlias = Join-Path $temp 'project'
$sdkAlias = Join-Path $temp 'sdk'
New-Item -ItemType Directory -Path $temp | Out-Null
$success = $false
try {
    New-Item -ItemType Junction -Path $rootAlias -Target $realRoot | Out-Null
    New-Item -ItemType Junction -Path $sdkAlias -Target $sdkRoot | Out-Null

    $build = Join-Path $rootAlias 'build'
    $classes = Join-Path $build 'classes'
    $dex = Join-Path $build 'dex'
    New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
    $src = @(Get-ChildItem -LiteralPath (Join-Path $rootAlias 'src\main\java') -Recurse -Filter '*.java' -File | ForEach-Object FullName)
    if ($src.Count -ne 1) { throw "Expected one Java source file, found $($src.Count)." }
    $buildTools = Join-Path $sdkAlias "build-tools\$buildToolsVersion"
    $android = Join-Path $sdkAlias "platforms\android-$compileApi\android.jar"
    if ($compileApi -eq '37') { $android = Join-Path $sdkAlias 'platforms\android-37.0\android.jar' }
    $manifest = Join-Path $rootAlias 'src\main\AndroidManifest.xml'

    & (Join-Path $jdkRoot 'bin\javac.exe') -encoding UTF-8 --release 8 -classpath $android -d $classes $src
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    $classesJar = Join-Path $build 'classes.jar'
    & (Join-Path $jdkRoot 'bin\jar.exe') cf $classesJar -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }
    & (Join-Path $jdkRoot 'bin\java.exe') -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --lib $android --min-api 31 --output $dex $classesJar
    if ($LASTEXITCODE -ne 0) { throw "D8 failed: $LASTEXITCODE" }

    $unsigned = Join-Path $build 'unsigned.apk'
    & (Join-Path $buildTools 'aapt2.exe') link -I $android --manifest $manifest --min-sdk-version 31 --target-sdk-version 35 --version-code 7 --version-name 1.6 -o $unsigned
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }
    & (Join-Path $jdkRoot 'bin\jar.exe') uf $unsigned -C $dex classes.dex
    if ($LASTEXITCODE -ne 0) { throw "Adding classes.dex failed: $LASTEXITCODE" }

    $aligned = Join-Path $build 'aligned.apk'
    & (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

    # Keep one private, stable local debug key for upgrade-compatible test rebuilds.
    $key = Join-Path $rootAlias 'keys\NiumaUiTestHost-debug.p12'
    New-Item -ItemType Directory -Force -Path (Split-Path $key) | Out-Null
    if (-not (Test-Path -LiteralPath $key)) {
        & (Join-Path $jdkRoot 'bin\keytool.exe') -genkeypair -noprompt -storetype PKCS12 -keystore $key -storepass android -keypass android -alias uitestdebug -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Niuma UI Test Host,O=Local,C=CN'
        if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the local test key: $LASTEXITCODE" }
    }
    & (Join-Path $jdkRoot 'bin\keytool.exe') -list -storetype PKCS12 -keystore $key -storepass android -alias uitestdebug | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The persistent local test key is unreadable or lacks alias uitestdebug.' }
    $apkName = 'NiumaUiTestHost.apk'
    $apkAlias = Join-Path $temp $apkName
    & (Join-Path $jdkRoot 'bin\java.exe') -jar (Join-Path $buildTools 'lib\apksigner.jar') sign --ks $key --ks-key-alias uitestdebug --ks-pass pass:android --key-pass pass:android --out $apkAlias $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed: $LASTEXITCODE" }
    & (Join-Path $jdkRoot 'bin\java.exe') -jar (Join-Path $buildTools 'lib\apksigner.jar') verify --verbose $apkAlias
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed: $LASTEXITCODE" }

    $badging = @(& (Join-Path $buildTools 'aapt2.exe') dump badging $apkAlias 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "package: name='local\.niuma\.uitest'") {
        throw 'APK package identity check failed.'
    }
    $permissionDump = @(& (Join-Path $buildTools 'aapt2.exe') dump permissions $apkAlias 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK permissions.' }
    if (($permissionDump -join "`n") -match 'uses-permission|permission:') {
        throw 'APK unexpectedly declares a permission.'
    }

    $apkReal = Join-Path $realRoot "out\$apkName"
    New-Item -ItemType Directory -Force -Path (Split-Path $apkReal) | Out-Null
    Copy-Item -LiteralPath $apkAlias -Destination $apkReal -Force
    $hash = (Get-FileHash -LiteralPath $apkReal -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(($apkReal + '.sha256'), ($hash + '  ' + $apkName + "`n"), [Text.Encoding]::ASCII)
    $success = $true
    Write-Output ("API platform: android-" + $compileApi)
    Write-Output ("APK: " + $apkReal)
    Write-Output ("SHA-256: " + $hash)
} finally {
    foreach ($alias in @($rootAlias, $sdkAlias)) {
        if (Test-Path -LiteralPath $alias) {
            $item = Get-Item -LiteralPath $alias -Force
            if ($item.FullName -ne $alias -or $item.LinkType -ne 'Junction') {
                throw "Temporary alias cleanup guard failed: $alias"
            }
            [IO.Directory]::Delete($alias)
        }
    }
    if (Test-Path -LiteralPath $temp) {
        if ((Get-Item -LiteralPath $temp).FullName -ne $temp) { throw 'Temporary directory cleanup guard failed.' }
        Remove-Item -LiteralPath $temp -Recurse -Force
    }
    if (-not $success) { Write-Warning 'Build did not complete; inspect the error above. No device interaction was performed.' }
}
