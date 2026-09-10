<#
.SYNOPSIS
  Test -> build a signed release -> GitHub release -> update docs/version.json -> push (GitHub Pages redeploys).

.EXAMPLE
  .\publish.ps1 -Notes "Setup wizard and PIN pairing"
  Reads versionCode/versionName from app\build.gradle.kts (bump them there first).
#>
param(
    [string]$Notes = "",
    [switch]$SkipTests   # never for a real release; only to re-publish an already-tested build
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Java\jdk-21" }
$gradle = if ($env:GRADLE_BAT) { $env:GRADLE_BAT } else { "C:\projects\fitness-app\tools\gradle-8.9\bin\gradle.bat" }
if (-not (Test-Path $gradle)) { $gradle = ".\gradlew.bat" }

$gradleKts = Get-Content app\build.gradle.kts -Raw
$versionCode = [int][regex]::Match($gradleKts, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$versionName = [regex]::Match($gradleKts, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $versionName) { throw "could not read versionName from app\build.gradle.kts" }
Write-Host "Publishing Hearth TV v$versionName ($versionCode)"

# 1. Tests + signed release. assembleRelease already depends on testDebugUnitTest (TDD gate).
$tasks = if ($SkipTests) { @("assembleRelease", "-x", "testDebugUnitTest") } else { @("testDebugUnitTest", "assembleRelease") }
& $gradle @tasks
if ($LASTEXITCODE -ne 0) { throw "tests or build failed - nothing published" }

New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item app\build\outputs\apk\release\app-release.apk dist\hearth-tv.apk -Force

# 2. GitHub release with the APK as the asset (the /releases/latest/download/hearth-tv.apk link follows it).
$tag = "v$versionName"
# PS 5.1: a native command's stderr under ErrorActionPreference=Stop can abort the script, so probe leniently.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
gh release view $tag *> $null
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEap
$utf8 = New-Object Text.UTF8Encoding $false
if ($exists) {
    gh release upload $tag dist\hearth-tv.apk --clobber
} else {
    $notesFile = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($notesFile, $Notes, $utf8)
    gh release create $tag dist\hearth-tv.apk --title "Hearth TV $versionName" --notes-file $notesFile
    Remove-Item $notesFile
}
if ($LASTEXITCODE -ne 0) { throw "gh release failed" }

# 3. version.json for the page + the in-app update check. No BOM (PS 5.1 Set-Content would add one).
$json = @{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl      = "https://github.com/Willits-Alec/hearth-tv/releases/latest/download/hearth-tv.apk"
    notes       = $Notes
    publishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $PSScriptRoot "docs\version.json"), $json + "`n", $utf8)

git add docs/version.json
$msg = [IO.Path]::GetTempFileName()
[IO.File]::WriteAllText($msg, "release: v$versionName ($versionCode)", $utf8)
git commit -F $msg | Out-Null
Remove-Item $msg
git push
Write-Host "Published: https://willits-alec.github.io/hearth-tv/  (Pages redeploys in ~1 min)"
