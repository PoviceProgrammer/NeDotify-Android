<#
    ASCII-safe Android build/test entry point for this repository.

    Why this exists
    ---------------
    The repository lives under a path with non-ASCII characters
    (`C:\Users\<user>\...`). Gradle's test worker cannot start from such a
    path: the worker classpath is built as a file URL and the JVM fails with
    `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`.

    The project therefore needs three ASCII aliases:
      * the project directory  -> <temp>/project
      * the Android SDK        -> <temp>/sdk
      * the Gradle user home   -> <temp>/gradle-home   (MUST be ASCII)

    The aliases are junctions, so the project tree is edited in place; only the
    path used to reach it is ASCII.

    This script does NOT rewrite `local.properties` (unlike `verify.ps1`),
    so several instances may run concurrently without clobbering each other.

    Usage
    -----
        powershell -File android/tools/run-unit-tests.ps1
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest' -Summarize
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest,:app:lintDebug'
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug' -RefreshVerificationMetadata
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':dumpVariantModel' -InitScript 'dump-variant-model.gradle'
        powershell -File android/tools/run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest' -RerunTasks

    A comma separated list is one token, because `powershell -File` binds only
    one value to a [string[]] parameter.
#>
param(
    # Gradle tasks to run. `assemble*` / `bundle*` / `package*` are forbidden
    # by android/AGENTS.md and are rejected below.
    [string[]]$Tasks = @(':app:testDebugUnitTest'),
    # Print the parsed JUnit XML summary of the unit-test run.
    [switch]$Summarize,
    # Add the checksums of the artifacts this run resolves to
    # gradle/verification-metadata.xml (the lint toolchain is resolved by a
    # detached configuration and is therefore not covered by the unit-test run).
    [switch]$RefreshVerificationMetadata,
    # Rewrite the dependency lock files. Never combined with the verification
    # metadata refresh by accident: the two files answer different questions.
    [switch]$RefreshLocks,
    # Execute every task of the run instead of taking it from the Gradle
    # cache. The numbers of a review must come from a run that really ran.
    [switch]$RerunTasks,
    # An init script (for example tools/dump-variant-model.gradle) that adds a
    # read-only model task. It cannot package anything.
    [string]$InitScript
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'SourceTasks.ps1')
# `powershell -File` binds only one value to a [string[]] parameter, so a
# comma separated list is accepted as well.
$Tasks = @($Tasks | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
Assert-SourceTasks $Tasks

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# --- the SDK -----------------------------------------------------------------
# REG P2-79: the environment is the PRIMARY channel, `local.properties` is the
# fallback. AGP prefers the file over the variable, so a stale file wins - but
# the file is machine-local and gitignored, and a fresh checkout has none.
$sdkDir = $null
foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platform-tools'))) { $sdkDir = $candidate; break }
}
if (-not $sdkDir) {
    $props = Join-Path $projectDir 'local.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Get-Content -LiteralPath $props | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($line) {
            $fromFile = $line.Substring(8).Replace('\:', ':')
            if (Test-Path -LiteralPath (Join-Path $fromFile 'platform-tools')) { $sdkDir = $fromFile }
        }
    }
}
if (-not $sdkDir) {
    $default = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    if (Test-Path -LiteralPath (Join-Path $default 'platform-tools')) { $sdkDir = $default }
}
if (-not $sdkDir) {
    throw 'Android SDK not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT), or sdk.dir in android/local.properties.'
}
# The SDK must not live in a temporary directory: a temp cleaner would take
# the build system with it. The junction alias below is what makes an ASCII
# path available - the SDK itself belongs in a permanent place.
if ($sdkDir -match '(?i)(^|[\\/])(temp|tmp)([\\/]|$)') {
    Write-Warning "The Android SDK is inside a temporary directory: $sdkDir. Install it in a permanent location (%LOCALAPPDATA%\Android\Sdk) and set ANDROID_HOME."
}

function Get-CanonicalDirectory([string]$Path) {
    $item = Get-Item -LiteralPath $Path
    $seen = @{}
    while ($item.LinkType -in @('Junction', 'SymbolicLink')) {
        if ($seen.ContainsKey($item.FullName)) { throw "Cyclic directory link: $Path" }
        $seen[$item.FullName] = $true
        $target = @($item.Target)[0]
        # A link whose target is gone (the SDK was moved) must not crash the
        # comparison: the alias is then simply stale and has to be re-pointed.
        if (-not (Test-Path -LiteralPath $target)) { return $target }
        $item = Get-Item -LiteralPath $target
    }
    return $item.FullName
}

$hasher = [System.Security.Cryptography.SHA256]::Create()
try { $identity = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectDir))).Replace('-', '').Substring(0, 10) }
finally { $hasher.Dispose() }

$tempRoot = Join-Path $env:SystemDrive "Temp/nedotify-$identity"
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

$gradleHomeSource = Join-Path $env:USERPROFILE '.gradle'
New-Item -ItemType Directory -Force -Path $gradleHomeSource | Out-Null

$aliases = @(
    @('project', $projectDir),
    @('sdk', (Get-Item -LiteralPath $sdkDir).FullName),
    @('gradle-home', $gradleHomeSource)
)
foreach ($entry in $aliases) {
    $alias = Join-Path $tempRoot $entry[0]
    $target = (Get-Item -LiteralPath $entry[1]).FullName
    if (Test-Path -LiteralPath $alias) {
        $existing = Get-Item -LiteralPath $alias
        $same = $existing.LinkType -eq 'Junction' -and (Get-CanonicalDirectory $alias) -eq (Get-CanonicalDirectory $target)
        if (-not $same) {
            # The SDK or the project moved: re-point the alias instead of refusing to run.
            if ($existing.LinkType -eq 'Junction') { (Get-Item -LiteralPath $alias).Delete() }
            else { Remove-Item -LiteralPath $alias -Recurse -Force }
        }
    }
    if (-not (Test-Path -LiteralPath $alias)) {
        New-Item -ItemType Junction -Path $alias -Target $target | Out-Null
    }
}

$saved = @{}
foreach ($key in @('JAVA_HOME', 'GRADLE_USER_HOME', 'GRADLE_OPTS', 'TEMP', 'TMP', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    if (-not $saved['JAVA_HOME']) {
        $jdk = Get-ChildItem 'C:/Program Files/Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
        if ($jdk) { $env:JAVA_HOME = $jdk }
    }
    $env:GRADLE_USER_HOME = Join-Path $tempRoot 'gradle-home'
    # The ASCII temporary directory is handed to the JVM through TEMP/TMP: the
    # JVM takes java.io.tmpdir from them on Windows. JAVA_TOOL_OPTIONS is NOT
    # used for it - the JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." to
    # stderr, and a captured PowerShell run renders every stderr line as a
    # NativeCommandError, so every log used to open with a rendered error.
    $env:TEMP = $tempRoot
    $env:TMP = $tempRoot
    $env:ANDROID_HOME = Join-Path $tempRoot 'sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $wrapper = Join-Path $tempRoot 'project/gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) { throw "Gradle wrapper not found: $wrapper" }
    Push-Location (Join-Path $tempRoot 'project')
    try {
        # Redirect the Gradle output to a file instead of the console pipe: on Windows the
        # Gradle daemon can inherit the client's stdout handle, and a reader on that pipe
        # then never sees EOF (the wrapper has already returned).
        # A unique log file per run: several agents may run Gradle concurrently.
        $logFile = Join-Path $tempRoot ("gradle-{0}-{1}.log" -f $PID, (Get-Date -Format 'HHmmss'))
        # Gradle writes progress and warnings to stderr; capturing them must not abort the script.
        $ErrorActionPreference = 'Continue'
        $metadataArgs = @()
        if ($RefreshVerificationMetadata) { $metadataArgs += '--write-verification-metadata'; $metadataArgs += 'sha256' }
        if ($RefreshLocks) { $metadataArgs += '--write-locks' }
        if ($RerunTasks) { $metadataArgs += '--rerun-tasks' }
        $initArgs = @()
        if ($InitScript) {
            # The path may be given relative to the caller's directory, to the
            # tools directory or to the Android project directory.
            $candidates = @($InitScript,
                (Join-Path $PSScriptRoot $InitScript),
                (Join-Path $projectDir $InitScript),
                (Join-Path $projectDir "tools/$InitScript"))
            $resolvedInit = $null
            foreach ($candidate in $candidates) {
                if (Test-Path -LiteralPath $candidate) { $resolvedInit = (Resolve-Path -LiteralPath $candidate).Path; break }
            }
            if (-not $resolvedInit) { throw "Init script not found: $InitScript" }
            $initArgs += '--init-script'; $initArgs += $resolvedInit
        }
        & $wrapper '-Pandroid.overridePathCheck=true' '--console=plain' @Tasks @initArgs @metadataArgs *> $logFile
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        Write-Output "GRADLE_LOG=$logFile"
        Get-Content -LiteralPath $logFile -Tail 25 | ForEach-Object { Write-Output $_ }
    }
    finally { Pop-Location }
}
finally {
    foreach ($key in $saved.Keys) {
        if ($null -eq $saved[$key]) { Remove-Item -LiteralPath ("Env:" + $key) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
}

if ($Summarize -and $Tasks -contains ':app:testDebugUnitTest') {
    $resultDir = Join-Path $tempRoot 'project/app/build/test-results/testDebugUnitTest'
    $xml = Get-ChildItem (Join-Path $resultDir '*.xml') -ErrorAction SilentlyContinue
    $total = 0; $fail = 0; $err = 0; $skip = 0
    foreach ($f in $xml) {
        [xml]$d = Get-Content -LiteralPath $f.FullName
        $total += [int]$d.testsuite.tests
        $fail  += [int]$d.testsuite.failures
        $err   += [int]$d.testsuite.errors
        $skip  += [int]$d.testsuite.skipped
    }
    Write-Output "TEST_SUMMARY files=$($xml.Count) tests=$total failures=$fail errors=$err skipped=$skip"
}

exit $buildExit
