<#
.SYNOPSIS
    Downloads and extracts Android platform-tools (ADB) for standalone Windows desktop use.
#>

param(
    [string]$TargetDir = "$PSScriptRoot\..\desktop-windows\src-tauri\resources\adb"
)

$ErrorActionPreference = "Stop"

Write-Host "=== CAMM Standalone ADB Setup ===" -ForegroundColor Cyan

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$adbExe = Join-Path $TargetDir "adb.exe"

if (Test-Path $adbExe) {
    Write-Host "ADB already present at: $adbExe" -ForegroundColor Green
    & "$adbExe" version
    exit 0
}

$zipPath = Join-Path $TargetDir "platform-tools.zip"
$url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

Write-Host "Downloading Google Platform-Tools..." -ForegroundColor Yellow
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing

Write-Host "Extracting ADB..." -ForegroundColor Yellow
$tempExtract = Join-Path $TargetDir "temp_extract"
Expand-Archive -Path $zipPath -DestinationPath $tempExtract -Force

$extractedTools = Join-Path $tempExtract "platform-tools"
Get-ChildItem -Path $extractedTools | ForEach-Object {
    Move-Item -Path $_.FullName -Destination $TargetDir -Force
}

Remove-Item -Path $tempExtract -Recurse -Force
Remove-Item -Path $zipPath -Force

if (Test-Path $adbExe) {
    Write-Host "`n>>> ADB successfully bundled at: $adbExe" -ForegroundColor Green
    & "$adbExe" version
} else {
    Write-Error "Failed to install ADB."
    exit 1
}
