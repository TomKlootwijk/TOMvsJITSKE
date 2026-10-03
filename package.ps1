# Build and sign a non-debuggable APK with a persistent, local-only release key.
param()

$ErrorActionPreference = 'Stop'
$recorderRoot = $PSScriptRoot
$signingDirectory = Join-Path $recorderRoot '.signing'
$distributionDirectory = Join-Path $recorderRoot 'dist'
$stagingDirectory = Join-Path $recorderRoot '.tools\release-package'
$jdkDirectory = Join-Path $recorderRoot '.tools\jdk-17.0.20.1+1'
$buildToolsDirectory = Join-Path $recorderRoot '.tools\android-sdk\build-tools\35.0.0'
$keystorePath = Join-Path $signingDirectory 'privaterecorder-release.p12'
$passwordPath = Join-Path $signingDirectory 'password.txt'
$gradleConfiguration = Get-Content -LiteralPath (Join-Path $recorderRoot 'app\build.gradle') -Raw
$versionMatch = [regex]::Match($gradleConfiguration, "versionName\s+'([0-9.]+)'")
$versionCodeMatch = [regex]::Match($gradleConfiguration, 'versionCode\s+(\d+)')
if (-not $versionMatch.Success -or -not $versionCodeMatch.Success) { throw 'Could not determine the APK version.' }
$apkName = "PrivateRecorder-$($versionMatch.Groups[1].Value).apk"
$apkPath = Join-Path $distributionDirectory $apkName
$alignedPath = Join-Path $stagingDirectory 'aligned-release.apk'
$unsignedPath = Join-Path $recorderRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'

& (Join-Path $recorderRoot 'build.ps1') -Tasks @('assembleRelease', 'testReleaseUnitTest', 'lintRelease')
New-Item -ItemType Directory -Force -Path $signingDirectory, $distributionDirectory, $stagingDirectory | Out-Null

if ((Test-Path -LiteralPath $keystorePath) -and -not (Test-Path -LiteralPath $passwordPath)) {
    throw 'The release key exists but its password file is missing. Restore .signing/password.txt; do not replace the existing key.'
}
if (-not (Test-Path -LiteralPath $passwordPath)) {
    $passwordBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($passwordBytes) } finally { $generator.Dispose() }
    $password = [Convert]::ToBase64String($passwordBytes)
    [System.IO.File]::WriteAllText($passwordPath, $password, [System.Text.Encoding]::ASCII)
    $password = $null
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
}
if (-not (Test-Path -LiteralPath $keystorePath)) {
    & (Join-Path $jdkDirectory 'bin\keytool.exe') -genkeypair `
        -keystore $keystorePath -storetype PKCS12 -alias privaterecorder `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Private Recorder' `
        -storepass:file $passwordPath -keypass:file $passwordPath -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
}

& (Join-Path $buildToolsDirectory 'zipalign.exe') -f -p 4 $unsignedPath $alignedPath
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
& (Join-Path $buildToolsDirectory 'apksigner.bat') sign --ks $keystorePath `
    --ks-key-alias privaterecorder --ks-pass "file:$passwordPath" `
    --out $apkPath $alignedPath
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $buildToolsDirectory 'apksigner.bat') verify --verbose --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $buildToolsDirectory 'zipalign.exe') -c -p 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }

$badging = & (Join-Path $buildToolsDirectory 'aapt.exe') dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the packaged APK.' }
if ($badging -match '^application-debuggable') { throw 'Refusing to distribute a debuggable APK.' }
if (-not ($badging -match "^package: name='nl\.privaterecorder'")) { throw 'Packaged application ID did not match.' }
$packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($packageLine -notmatch " versionCode='$($versionCodeMatch.Groups[1].Value)'" -or
    $packageLine -notmatch " versionName='$([regex]::Escape($versionMatch.Groups[1].Value))'") {
    throw 'Packaged APK version did not match app/build.gradle.'
}
Write-Host "Verified: nl.privaterecorder $($versionMatch.Groups[1].Value) (code $($versionCodeMatch.Groups[1].Value)), non-debuggable, signed and aligned."

$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText((Join-Path $distributionDirectory 'SHA256SUMS'),
    "$apkHash  $apkName`n", [System.Text.Encoding]::ASCII)
Write-Host "APK: $apkPath"
Write-Host 'Preserve and back up the entire .signing folder securely. Future updates must use this same key to avoid uninstalling and losing local recordings.'
