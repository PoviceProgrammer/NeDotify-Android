<#
Source-only gate. Creates validated ASCII junctions for the project, SDK and
Gradle home. Temporarily replaces local.properties and process environment;
both are restored byte-for-byte in finally, including on Gradle failure.
Gradle stdout/stderr go to a unique log so captured PowerShell output cannot
turn a native warning into an early terminating NativeCommandError.
APK/package/install/device tasks are rejected while the repository ban holds.

REG P2-79: the SDK is taken from the environment first (ANDROID_HOME,
ANDROID_SDK_ROOT) and from `local.properties` only as the fallback. AGP
prefers the file over the variable, so the file is rewritten with the ASCII
alias for the duration of the run and restored byte-for-byte afterwards.
#>
param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    # Source checks are safe by default: APK packaging now requires explicit tasks.
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/SourceTasks.ps1')
# `powershell -File` binds only one value to a [string[]] parameter, so a
# comma separated list is accepted as well.
$Tasks = @($Tasks | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
Assert-SourceTasks $Tasks
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
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -and (Test-Path "$PSScriptRoot/local.properties")) {
    $line = Get-Content "$PSScriptRoot/local.properties" | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
    if ($line) { $SdkPath = $line.Substring(8).Replace('\:', ':') }
}
if (-not $SdkPath) { $SdkPath = "$env:LOCALAPPDATA/Android/Sdk" }
if (-not (Test-Path -LiteralPath "$SdkPath/platform-tools")) { throw 'Set -SdkPath to an installed Android SDK.' }
if (-not $JavaHome) {
    $JavaHome = Get-ChildItem 'C:/Program Files/Microsoft' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if (-not (Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw 'Set -JavaHome to JDK 17 or 21.' }
# The version catalog and the lock state must name the same versions. The
# check belongs to the gate: a catalog that lies is a silent downgrade.
& (Join-Path $PSScriptRoot 'tools/check-catalog-lock.ps1')
if ($LASTEXITCODE -ne 0) { throw 'The version catalog and the dependency lock state disagree.' }
$hasher = [System.Security.Cryptography.SHA256]::Create()
try { $identity = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($PSScriptRoot))).Replace('-', '').Substring(0, 10) }
finally { $hasher.Dispose() }
$tempRoot = Join-Path $env:SystemDrive "Temp/nedotify-$identity"
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$gradleHome = Join-Path $env:USERPROFILE '.gradle'
New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
foreach ($entry in @(@('project', $PSScriptRoot), @('sdk', (Get-Item -LiteralPath $SdkPath).FullName), @('gradle-home', $gradleHome))) {
    $alias = Join-Path $tempRoot $entry[0]
    if (Test-Path -LiteralPath $alias) {
        $existing = Get-Item -LiteralPath $alias
        $same = $existing.LinkType -eq 'Junction' -and (Get-CanonicalDirectory $alias) -eq (Get-CanonicalDirectory $entry[1])
        if (-not $same) {
            # The SDK or the project moved: re-point the alias instead of
            # refusing to run. A gate that stops working after the SDK was
            # updated is worse than no gate.
            if ($existing.LinkType -eq 'Junction') { (Get-Item -LiteralPath $alias).Delete() }
            else { Remove-Item -LiteralPath $alias -Recurse -Force }
        }
    }
    if (-not (Test-Path -LiteralPath $alias)) { New-Item -ItemType Junction -Path $alias -Target $entry[1] | Out-Null }
}
# ASCII aliases avoid Windows Java argument-file and Android SDK path failures.
$sdkAlias = (Join-Path $tempRoot 'sdk').Replace('\', '/').Replace(':', '\:')
$propertiesPath = Join-Path $PSScriptRoot 'local.properties'
$hadProperties = Test-Path -LiteralPath $propertiesPath
$originalProperties = if ($hadProperties) { [IO.File]::ReadAllBytes($propertiesPath) } else { $null }
$buildExit = 1
$saved = @{}
foreach ($key in @('JAVA_HOME', 'GRADLE_USER_HOME', 'GRADLE_OPTS', 'TEMP', 'TMP', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $wrapper = Join-Path $tempRoot 'project/gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) { throw "Gradle wrapper not found: $wrapper" }
    [IO.File]::WriteAllText($propertiesPath, "sdk.dir=$sdkAlias`n", [Text.Encoding]::ASCII)
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = Join-Path $tempRoot 'gradle-home'
    # The ASCII temporary directory reaches the JVM through TEMP/TMP (the JVM
    # takes java.io.tmpdir from them on Windows). JAVA_TOOL_OPTIONS is not
    # used: the JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." to stderr and a
    # captured run renders every stderr line as a NativeCommandError.
    $env:TEMP = $tempRoot
    $env:TMP = $tempRoot
    $env:ANDROID_HOME = Join-Path $tempRoot 'sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    Push-Location (Join-Path $tempRoot 'project')
    try {
        $logFile = Join-Path $tempRoot ("verify-{0}-{1}.log" -f $PID, [Guid]::NewGuid().ToString('N'))
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat '-Pandroid.overridePathCheck=true' '--console=plain' @Tasks *> $logFile
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        Write-Output "GRADLE_LOG=$logFile"
        Get-Content -LiteralPath $logFile -Tail 40
    } finally { Pop-Location }
} finally {
    if ($hadProperties) { [IO.File]::WriteAllBytes($propertiesPath, $originalProperties) }
    elseif (Test-Path -LiteralPath $propertiesPath) { Remove-Item -LiteralPath $propertiesPath -Force }
    foreach ($key in $saved.Keys) {
        if ($null -eq $saved[$key]) { Remove-Item -LiteralPath ("Env:" + $key) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
}
exit $buildExit
