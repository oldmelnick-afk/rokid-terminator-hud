param([switch]$Offline)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    $localJbr = Join-Path $env:USERPROFILE 'Documents\Rocket-Glasses\toolchain\android-studio\jbr'
    if (Test-Path -LiteralPath $localJbr) { $env:JAVA_HOME = $localJbr }
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $localSdk = Join-Path $env:USERPROFILE 'Documents\Rocket-Glasses\toolchain\sdk'
    if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk }
}
if ($env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if ($env:JAVA_HOME) {
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') +
        [System.IO.Path]::PathSeparator + $env:Path
}
Push-Location $PSScriptRoot
try {
    $gradleArgs = @(':app:assembleDebug', '--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
    Write-Output (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
}
finally {
    Pop-Location
}
