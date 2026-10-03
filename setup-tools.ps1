# Portable, workspace-local Android build prerequisites. No global PATH changes.
param()

$ErrorActionPreference = 'Stop'
$recorderRoot = $PSScriptRoot
$toolsDirectory = Join-Path $recorderRoot '.tools'
$downloadsDirectory = Join-Path $toolsDirectory 'downloads'
$jdkDirectory = Join-Path $toolsDirectory 'jdk-17.0.20.1+1'
$sdkDirectory = Join-Path $toolsDirectory 'android-sdk'
New-Item -ItemType Directory -Force -Path $toolsDirectory, $downloadsDirectory | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Name, [string]$Hash, [string]$Algorithm = 'SHA256')
    $archivePath = Join-Path $downloadsDirectory $Name
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Write-Host "Downloading $Name ..."
        & curl.exe -L --fail --show-error --retry 3 --output $archivePath $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name" }
    }
    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm $Algorithm).Hash
    if ($actualHash -ne $Hash) { throw "Checksum mismatch for $archivePath. Remove that archive and retry." }
    return $archivePath
}

if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\java.exe'))) {
    # SHA-256 published by the official Eclipse Adoptium API/release.
    $jdkArchive = Get-VerifiedArchive `
        -Url 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' `
        -Name 'jdk17.zip' `
        -Hash 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    & tar.exe -xf $jdkArchive -C $toolsDirectory
    if ($LASTEXITCODE -ne 0) { throw 'JDK extraction failed.' }
}

$gradleDirectory = Join-Path $toolsDirectory 'gradle-8.11.1'
if (-not (Test-Path -LiteralPath (Join-Path $gradleDirectory 'bin\gradle.bat'))) {
    # https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256
    $gradleArchive = Get-VerifiedArchive `
        -Url 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' `
        -Name 'gradle-8.11.1-bin.zip' `
        -Hash 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
    & tar.exe -xf $gradleArchive -C $toolsDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Gradle extraction failed.' }
}

$commandToolsDirectory = Join-Path $sdkDirectory 'cmdline-tools\13.0'
$sdkManager = Join-Path $commandToolsDirectory 'bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    # This JDK-17-compatible revision is pinned. Google's repository2-1.xml
    # publishes this archive's SHA-1 checksum; HTTPS protects its transport.
    $commandToolsArchive = Get-VerifiedArchive `
        -Url 'https://dl.google.com/android/repository/commandlinetools-win-11479570_latest.zip' `
        -Name 'commandlinetools-win-11479570_latest.zip' `
        -Algorithm 'SHA1' -Hash '938b82035c25633e822bd4e048033a6b7816d3a8'
    New-Item -ItemType Directory -Force -Path $commandToolsDirectory | Out-Null
    & tar.exe -xf $commandToolsArchive --strip-components 1 -C $commandToolsDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Android command-line tools extraction failed.' }
}

$env:JAVA_HOME = $jdkDirectory
$env:ANDROID_HOME = $sdkDirectory
$env:ANDROID_SDK_ROOT = $sdkDirectory
$env:PATH = "$jdkDirectory\bin;$env:PATH"

# sdkmanager displays licenses needed for these local development packages.
# Nothing is installed on a device.
1..10 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkDirectory" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }

# Keep this machine-specific file out of version control.
$sdkProperty = 'sdk.dir=' + $sdkDirectory.Replace('\', '/')
Set-Content -LiteralPath (Join-Path $recorderRoot 'local.properties') -Value $sdkProperty -Encoding ascii
Write-Host 'Portable prerequisites are ready. Run .\build.ps1.'
