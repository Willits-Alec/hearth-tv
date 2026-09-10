<#
.SYNOPSIS
  Build a signed release (tests must be green) and install it on Alec's S26 through Hearth, zero taps.
.EXAMPLE
  .\install-s26.ps1
#>
param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Java\jdk-21" }
$gradle = if ($env:GRADLE_BAT) { $env:GRADLE_BAT } else { "C:\projects\fitness-app\tools\gradle-8.9\bin\gradle.bat" }
if (-not (Test-Path $gradle)) { $gradle = ".\gradlew.bat" }

if (-not $NoBuild) {
    & $gradle assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "build (or its tests) failed" }
    New-Item -ItemType Directory -Force dist | Out-Null
    Copy-Item app\build\outputs\apk\release\app-release.apk dist\hearth-tv.apk -Force
}
& "C:\projects\hearth\gateway\.venv\Scripts\python.exe" tools\hearth_install.py s26
