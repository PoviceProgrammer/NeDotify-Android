<# Verifies a completed report; it neither creates nor runs APK/device tasks. #>
param(
    [string]$ReportPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'review/implementation/device-results.json'),
    [string]$SourceSha,
    [string]$ApkSha256,
    [string]$EvidenceRoot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SourceGatePolicy.ps1')
$report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
Assert-DeviceAcceptanceReport $report $SourceSha $ApkSha256 $EvidenceRoot
Write-Output 'Device acceptance verified for the supplied source and APK identity.'
