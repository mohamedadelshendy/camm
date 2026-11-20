<#
.SYNOPSIS
    Registers the DirectShow Virtual Camera Filter (UnityCapture) on Windows.
.DESCRIPTION
    Downloads and registers the 64-bit DirectShow virtual webcam filter with Windows COM/DirectShow.
    Once registered, any Windows app (Zoom, Discord, Microsoft Teams, OBS, Google Chrome)
    recognizes "Unity Video Capture" natively as a hardware webcam.
#>

param(
    [switch]$Uninstall = $false,
    [string]$TargetDir = "$PSScriptRoot\virtual_cam_driver"
)

$ErrorActionPreference = "Stop"

function Test-Admin {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Warning "Administrator rights required to register DirectShow COM filters."
    Write-Host "Relaunching elevated PowerShell..."
    $params = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($Uninstall) { $params += "-Uninstall" }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $params
    exit
}

Write-Host "=== CAMM Virtual Camera DirectShow Registration ===" -ForegroundColor Cyan

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$dllPath = Join-Path $TargetDir "UnityCaptureFilter64.dll"

if ($Uninstall) {
    if (Test-Path $dllPath) {
        Write-Host "Unregistering DirectShow Filter: $dllPath" -ForegroundColor Yellow
        & regsvr32.exe /u /s "$dllPath"
        Write-Host "Unregistered successfully." -ForegroundColor Green
    } else {
        Write-Host "DLL not found, nothing to unregister." -ForegroundColor Gray
    }
    exit 0
}

# Download prebuilt UnityCapture DirectShow 64-bit filter if not present
if (-not (Test-Path $dllPath)) {
    Write-Host "Downloading UnityCaptureFilter64.dll..." -ForegroundColor Yellow
    $downloadUrl = "https://github.com/schellingb/UnityCapture/releases/download/v1.0.4/UnityCaptureFilter64.dll"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
        Invoke-WebRequest -Uri $downloadUrl -OutFile $dllPath -UseBasicParsing
        Write-Host "Downloaded successfully: $dllPath" -ForegroundColor Green
    } catch {
        Write-Error "Failed to download DirectShow filter from GitHub release: $_"
        exit 1
    }
}

Write-Host "Registering DirectShow Filter with regsvr32..." -ForegroundColor Yellow
$proc = Start-Process -FilePath "regsvr32.exe" -ArgumentList @("/s", "`"$dllPath`"") -Wait -PassThru

if ($proc.ExitCode -eq 0) {
    Write-Host "`n>>> SUCCESS: 'Unity Video Capture' is now registered and active on Windows!" -ForegroundColor Green
    Write-Host "Apps like Zoom, Discord, OBS, and Google Chrome can now select 'Unity Video Capture'." -ForegroundColor White
} else {
    Write-Error "Failed to register filter. regsvr32 exit code: $($proc.ExitCode)"
    exit $proc.ExitCode
}
