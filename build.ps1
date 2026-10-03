param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$recorderRoot = $PSScriptRoot
$jdkDirectory = Join-Path $recorderRoot '.tools\jdk-17.0.20.1+1'
$sdkDirectory = Join-Path $recorderRoot '.tools\android-sdk'

if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\java.exe'))) {
    throw 'Local JDK is missing. Run .\setup-tools.ps1 first, or build with your installed JDK 17 and Android SDK.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sdkDirectory 'platforms\android-35\android.jar'))) {
    throw 'Local Android SDK is missing. Run .\setup-tools.ps1 first.'
}

$env:JAVA_HOME = $jdkDirectory
$env:ANDROID_HOME = $sdkDirectory
$env:ANDROID_SDK_ROOT = $sdkDirectory
$env:GRADLE_USER_HOME = Join-Path $recorderRoot '.tools\gradle-user-home'
$env:PATH = "$jdkDirectory\bin;$env:PATH"

Push-Location $recorderRoot
try {
    & (Join-Path $recorderRoot 'gradlew.bat') @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
