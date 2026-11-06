<# Source-only build with evidence tied to the actual input tree. No packaging. #>
param([switch]$RerunTasks)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'SourceTasks.ps1')
. (Join-Path $PSScriptRoot 'SourceSnapshot.ps1')
$tasks = @(':app:compileDebugKotlin', ':app:compileReleaseKotlin', ':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin')
Assert-SourceTasks $tasks
$before = Get-ReviewTreeDigest $project
$started = [DateTime]::UtcNow
$resultPath = Join-Path $project 'app/build/reports/source-gate/run.json'
$exitCode = 1
$failure = $null
try {
    Push-Location $project
    try {
        if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
            $hostPath = (Get-Process -Id $PID).Path
            $arguments = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'run-unit-tests.ps1'), '-Tasks', ($tasks -join ','), '-Summarize')
            if ($RerunTasks) { $arguments += '-RerunTasks' }
            & $hostPath @arguments
        } else {
            $arguments = @('./gradlew', '--console=plain') + $tasks
            if ($RerunTasks) { $arguments += '--rerun-tasks' }
            & bash @arguments
        }
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
} catch { $failure = $_.Exception.Message; $exitCode = 1 }
$after = Get-ReviewTreeDigest $project
if ($before -ne $after) { $failure = 'Source tree changed during the build; rerun after all writers finish.'; $exitCode = 1 }
$record = [ordered]@{
    schemaVersion = 1
    startedUtc = $started.ToString('o')
    finishedUtc = [DateTime]::UtcNow.ToString('o')
    tasks = $tasks
    exitCode = $exitCode
    sourceDigest = $before
    sourceUnchanged = ($before -eq $after)
    failure = $failure
    apkPackaging = 'NOT_RUN'
    instrumentationExecution = 'NOT_RUN'
    deviceAcceptance = 'NOT_RUN'
}
[IO.Directory]::CreateDirectory((Split-Path $resultPath -Parent)) | Out-Null
[IO.File]::WriteAllText($resultPath, ($record | ConvertTo-Json -Depth 4) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output 'SOURCE_BUILD_EVIDENCE=app/build/reports/source-gate/run.json'
if ($failure) { Write-Error $failure }
exit $exitCode
