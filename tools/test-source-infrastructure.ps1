<# Executable policy checks. Uses isolated temporary fixtures, never user data or Gradle. #>
param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
. (Join-Path $PSScriptRoot 'SourceTasks.ps1')
. (Join-Path $PSScriptRoot 'SourceSnapshot.ps1')
. (Join-Path $PSScriptRoot 'SourceGatePolicy.ps1')
$passed = 0
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Case([string]$Name, [scriptblock]$Body) {
    & $Body
    $script:passed++
    Write-Output "PASS $Name"
}
function Must-Throw([scriptblock]$Body) {
    $threw = $false
    try { & $Body | Out-Null } catch { $threw = $true }
    Assert-True $threw 'Expected rejection, but the policy accepted the input.'
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('nedotify-source-check-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
function Fixture-File([string]$Relative, [string]$Text) {
    $path = Join-Path $fixture $Relative
    [IO.Directory]::CreateDirectory((Split-Path $path -Parent)) | Out-Null
    [IO.File]::WriteAllText($path, $Text, [Text.UTF8Encoding]::new($false))
}
function Generator([switch]$Check) {
    $hostPath = (Get-Process -Id $PID).Path
    $arguments = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'generate-design-tokens.ps1'), '-ProjectRoot', $fixture)
    if ($Check) { $arguments += '-Check' }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $hostPath @arguments 2>&1; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous }
    return [pscustomobject]@{ Exit = $code; Output = ($output -join "`n") }
}
function Job-Results([string]$ColdRequired = 'true') {
    return @{
        policy = @{ result='success' }
        build = @{ result='success' }
        'dependency-plan' = @{ result='success'; outputs=@{ cold=$ColdRequired } }
        cold = @{ result='success' }
    }
}
function Device-Report {
    return [pscustomobject]@{
        sourceSha=('a' * 40); apkSha256=('b' * 64)
        device=@{model='isolated policy fixture (not a real device run)';api=37}
        executedUtc=[DateTimeOffset]::UtcNow.ToString('o')
        scenarios=@(1..22 | ForEach-Object { [pscustomobject]@{
            id=('D{0:d2}' -f $_);status='PASS'
            actualResult='Synthetic fixture exercises report policy only; no Android execution.'
            evidence=@('audio.txt')
        } })
        openDefects=@()
    }
}
try {
    Fixture-File 'design/design-tokens.json' ([IO.File]::ReadAllText((Join-Path $ProjectRoot 'design/design-tokens.json')))
    Case 'generator emits deterministic CSS and Kotlin' {
        $result = Generator
        Assert-True ($result.Exit -eq 0) $result.Output
        $result = Generator -Check
        Assert-True ($result.Exit -eq 0) $result.Output
        $css = [IO.File]::ReadAllText((Join-Path $fixture 'design/ui-prototype/tokens.css'))
        $kt = [IO.File]::ReadAllText((Join-Path $fixture 'app/src/main/java/ru/nedotify/app/ui/tokens/DesignTokens.kt'))
        Assert-True ($css.Contains('--color-primary: #4CC9FF;') -and $kt.Contains('0xFF4CC9FF')) 'Shared primary token was not emitted.'
        Assert-True (-not $css.Contains("`r") -and -not $kt.Contains("`r")) 'Generated outputs must use LF on every platform.'
    }
    Case 'CSS drift fails check without rewriting output' {
        $path = Join-Path $fixture 'design/ui-prototype/tokens.css'
        [IO.File]::AppendAllText($path, '/* drift */')
        $before = Get-ReviewFileHash $path
        $result = Generator -Check
        Assert-True ($result.Exit -ne 0 -and $result.Output.Contains('Generated token drift')) 'CSS drift was accepted.'
        Assert-True ((Get-ReviewFileHash $path) -eq $before) '-Check rewrote CSS.'
        $result = Generator
        Assert-True ($result.Exit -eq 0) $result.Output
    }
    Case 'Kotlin drift fails check' {
        [IO.File]::AppendAllText((Join-Path $fixture 'app/src/main/java/ru/nedotify/app/ui/tokens/DesignTokens.kt'), '// drift')
        $result = Generator -Check
        Assert-True ($result.Exit -ne 0) 'Kotlin drift was accepted.'
        $result = Generator
        Assert-True ($result.Exit -eq 0) $result.Output
    }
    Case 'invalid tokens fail before outputs are written' {
        $path = Join-Path $fixture 'design/design-tokens.json'
        $valid = [IO.File]::ReadAllText($path)
        $invalid = ($valid | ConvertFrom-Json)
        $invalid.layout.touchTarget = 24
        [IO.File]::WriteAllText($path, ($invalid | ConvertTo-Json -Depth 10))
        $result = Generator
        Assert-True ($result.Exit -ne 0 -and $result.Output.Contains('touchTarget')) 'Undersized touch target was accepted.'
        [IO.File]::WriteAllText($path, $valid)
    }
    Case 'source enumeration includes prototype and excludes Git/build/reports' {
        Fixture-File '.git/config.json' '{}'
        Fixture-File 'app/build/generated/Test.kt' 'generated build'
        Fixture-File 'output/playwright/capture.png' 'generated browser screenshot'
        Fixture-File '.playwright-cli/capture.png' 'generated browser screenshot'
        Fixture-File 'hs_err_pid123.log' 'local JVM crash'
        Fixture-File 'review/TEST_SUMMARY.json' '{}'
        Fixture-File 'review/implementation/device-results.json' '{}'
        Fixture-File 'review/implementation/scenarios.json' '{}'
        Fixture-File 'review/DEVICE_ACCEPTANCE.md' 'test scenarios'
        Fixture-File 'design/ui-prototype/index.html' '<html></html>'
        Fixture-File 'design/ui-prototype/app.js' 'const fixture = true;'
        Fixture-File 'design/ui-prototype/cover.svg' '<svg/>'
        Fixture-File '.gitattributes' '* -text'
        Fixture-File '.github/workflows/android-source.yml' 'fixture: true'
        $files = @(Get-ReviewSources $fixture)
        foreach ($path in @('design/ui-prototype/index.html','design/ui-prototype/app.js','design/ui-prototype/cover.svg','.gitattributes','.github/workflows/android-source.yml','review/implementation/scenarios.json','review/DEVICE_ACCEPTANCE.md')) {
            Assert-True ($path -cin $files) "Source omitted: $path"
        }
        foreach ($path in @('.git/config.json','app/build/generated/Test.kt','output/playwright/capture.png','.playwright-cli/capture.png','hs_err_pid123.log','review/TEST_SUMMARY.json','review/implementation/device-results.json')) {
            Assert-True ($path -cnotin $files) "Generated/internal file included: $path"
        }
    }
    Case 'snapshot detects real prototype changes and tree digest changes' {
        Write-ReviewSnapshot $fixture | Out-Null
        $before = Get-ReviewTreeDigest $fixture
        $drift = Compare-ReviewSnapshot $fixture
        Assert-True (($drift.new.Count + $drift.changed.Count + $drift.removed.Count + $drift.unlisted.Count) -eq 0) 'Fresh fixture snapshot is not clean.'
        Fixture-File 'design/ui-prototype/index.html' '<html>changed</html>'
        $drift = Compare-ReviewSnapshot $fixture
        Assert-True ('design/ui-prototype/index.html' -cin $drift.changed) 'Prototype change escaped snapshot.'
        Assert-True ($before -ne (Get-ReviewTreeDigest $fixture)) 'Prototype change escaped source digest.'
    }
    Case 'source task allow-list rejects packaging abbreviations and injected arguments' {
        Assert-SourceTasks @(':app:compileDebugKotlin',':app:compileReleaseKotlin',':app:testDebugUnitTest',':app:lintDebug',':app:compileDebugAndroidTestKotlin')
        foreach ($task in @(':app:assembleDebug',':app:ass',':app:compileDebugKotlin -Punsafe=true',':app:connectedDebugAndroidTest')) {
            Must-Throw { Assert-SourceTasks @($task) }
        }
    }
    Case 'aggregate source check rejects skipped mandatory jobs' {
        Assert-SourceJobResults (Job-Results)
        foreach ($name in @('policy','build','dependency-plan')) {
            $jobs = Job-Results
            $jobs[$name].result = 'skipped'
            Must-Throw { Assert-SourceJobResults $jobs }
        }
        $jobs = Job-Results
        $jobs.cold.result = 'skipped'
        Must-Throw { Assert-SourceJobResults $jobs }
        $jobs = Job-Results 'false'
        $jobs.cold.result = 'skipped'
        Assert-SourceJobResults $jobs
        $jobs.cold.result = 'failure'
        Must-Throw { Assert-SourceJobResults $jobs }
    }
    Case 'device gate rejects NOT_RUN, wrong build and missing evidence' {
        $sha = 'a' * 40; $apk = 'b' * 64
        Fixture-File 'acceptance-evidence/audio.txt' 'Synthetic fixture, not audible Android evidence.'
        $evidenceRoot = Join-Path $fixture 'acceptance-evidence'
        $report = Device-Report
        Assert-DeviceAcceptanceReport $report $sha $apk $evidenceRoot
        $report.scenarios[0].status = 'NOT_RUN'
        Must-Throw { Assert-DeviceAcceptanceReport $report $sha $apk $evidenceRoot }
        $report.scenarios[0].status = 'PASS'
        Must-Throw { Assert-DeviceAcceptanceReport $report ('c' * 40) $apk $evidenceRoot }
        Must-Throw { Assert-DeviceAcceptanceReport $report $sha ('c' * 64) $evidenceRoot }
        $report.scenarios[0].actualResult = ' '
        Must-Throw { Assert-DeviceAcceptanceReport $report $sha $apk $evidenceRoot }
        $report.scenarios[0].actualResult = 'Synthetic fixture only.'
        $report.scenarios[0].evidence = @()
        Must-Throw { Assert-DeviceAcceptanceReport $report $sha $apk $evidenceRoot }
        $report.scenarios[0].evidence = @('audio.txt')
        $report.openDefects = @(@{severity='S1'})
        Must-Throw { Assert-DeviceAcceptanceReport $report $sha $apk $evidenceRoot }
    }
    Case 'device gate rejects missing, empty and escaped evidence files' {
        $report = Device-Report
        $evidenceRoot = Join-Path $fixture 'acceptance-evidence'
        Fixture-File 'outside-evidence/outside.txt' 'Synthetic outside-root artifact.'
        Fixture-File 'acceptance-evidence/empty.txt' ''
        foreach ($reference in @('missing.txt','empty.txt','../outside-evidence/outside.txt',(Join-Path $fixture 'outside-evidence/outside.txt'))) {
            $report.scenarios[0].evidence = @($reference)
            Must-Throw { Assert-DeviceAcceptanceReport $report $report.sourceSha $report.apkSha256 $evidenceRoot }
        }
    }
    Case 'device gate rejects evidence through an outside-root link' {
        $report = Device-Report
        $evidenceRoot = Join-Path $fixture 'acceptance-evidence'
        $link = Join-Path $evidenceRoot 'outside-link'
        $target = Join-Path $fixture 'outside-evidence'
        $linkType = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'Junction' } else { 'SymbolicLink' }
        try {
            New-Item -ItemType $linkType -Path $link -Target $target | Out-Null
            $report.scenarios[0].evidence = @('outside-link/outside.txt')
            Must-Throw { Assert-DeviceAcceptanceReport $report $report.sourceSha $report.apkSha256 $evidenceRoot }
        } finally {
            if (Test-Path -LiteralPath $link) { (Get-Item -LiteralPath $link -Force).Delete() }
        }
    }
    Write-Output "INFRASTRUCTURE_CHECKS=$passed passed; fixture isolation only; no Gradle/APK/device execution."
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -like 'nedotify-source-check-*') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } else { throw 'Refusing to clean an unexpected fixture directory.' }
}

# Expected rejection cases launch a subprocess that exits nonzero. Assertions
# above consume that result; do not leak its LASTEXITCODE into the CI pwsh host.
# An uncaught assertion/cleanup failure terminates the script before this line.
exit 0
