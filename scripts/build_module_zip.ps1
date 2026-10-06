<#
.SYNOPSIS
Builds the FlutterInspect Zygisk module and packages the flashable Magisk/KernelSU/APatch zip.
#>

param(
    [string]$DistDir = "dist"
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

Write-Host "==> Building native module and packaging Magisk zip via Gradle..." -ForegroundColor Cyan
if (Test-Path "$RootDir\gradlew.bat") {
    & "$RootDir\gradlew.bat" :module:packageMagiskZip
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle :module:packageMagiskZip
} else {
    Write-Error "Neither gradlew.bat nor system gradle found. Please ensure Gradle or Android Studio wrapper is available."
}

if (Test-Path "$RootDir\dist") {
    $zip = Get-ChildItem "$RootDir\dist\*.zip" | Select-Object -Last 1
    if ($zip) {
        Write-Host "==> Flashable module created: $($zip.FullName)" -ForegroundColor Green
    }
}
