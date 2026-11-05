#Requires -RunAsAdministrator
param([string]$ResultPath='C:/Temp/nedotify-hypervisor-result.json')
$ErrorActionPreference = 'Stop'
# Deliberately does not reboot or change boot settings. Only the Windows component below.
try {
    $feature = Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform
    if ($feature.State -eq 'Enabled') {$restart=$false}
    else {$result = Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart; $restart=[bool]$result.RestartNeeded}
    [ordered]@{Success=$true;Feature='HypervisorPlatform';RestartNeeded=$restart} | ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding UTF8
    Write-Output "Windows Hypervisor Platform enabled. Restart needed: $restart"
    Write-Output 'Save your work and restart Windows yourself, then run run-emulator.ps1.'
} catch {
    [ordered]@{Success=$false;Error=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding UTF8
    throw
}
