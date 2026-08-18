<#
.SYNOPSIS
    ADB Wireless Keepalive & Auto-Discovery for PowerShell.
.EXAMPLE
    .\tools\keepalive-ping.ps1 -Target "192.168.1.13:40845" -Interval 2
#>
param (
    [string]$Target = "192.168.1.13:40845",
    [int]$Interval = 2,
    [string]$PingTarget = "192.168.1.1"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pyScript = Join-Path $scriptDir "adb_keepalive.py"

if (Get-Command python -ErrorAction SilentlyContinue) {
    python $pyScript $Target --ping-target $PingTarget --interval $Interval
    exit $LASTEXITCODE
}

Write-Host "[keepalive-ping] Starting background ping from device to $PingTarget every ${Interval}s..." -ForegroundColor Cyan

while ($true) {
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $res = adb shell "ping -c 1 -W 1 $PingTarget" 2>&1 | Out-String
    if ($res -match "bytes from") {
        $match = [regex]::Match($res, "time=[0-9.]+ ms")
        $timeStr = if ($match.Success) { $match.Value } else { "ok" }
        Write-Host "[$timestamp] OK -> $PingTarget ($timeStr)" -ForegroundColor Green
    } else {
        Write-Host "[$timestamp] FAILED -> $PingTarget : $($res.Trim())" -ForegroundColor Red
    }
    Start-Sleep -Seconds $Interval
}
