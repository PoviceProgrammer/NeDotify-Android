<#
    Compares the shared version catalog (gradle/libs.versions.toml) with the
    dependency lock state (app/gradle.lockfile).

    Why this exists
    ---------------
    Dependency locking wins over the declared version: the lock state pins
    every module of a locked configuration, so a catalog that asks for
    1.17.0 while the lock state says 1.18.0 builds 1.18.0 and says nothing.
    The catalog is the file a reader consults, so it must name the version
    that is actually built.

    What it checks
    --------------
    * Every library that declares a version must be locked at exactly that
      version. A coordinate that is missing, or that is locked at another
      version, is a disagreement.
    * Every library that declares NO version is managed by a BOM. Such a
      library has no version of its own, so the check does not skip it: it
      verifies that the module is in the lock state at all (the BOM really
      governs it) and reports the version the BOM resolved to.
    * Every PLUGIN of the catalog is checked against the lock state of the
      plugin classpath (buildscript-gradle.lockfile). The plugins are applied
      in the root project, so their versions are locked there and not in the
      settings' lock file.
    * A module that is locked at SEVERAL versions is reported, because the
      lock state is then the only place where that fact is written down.

    The check reads the two files only: it resolves nothing, downloads
    nothing and runs no Gradle task.

    Usage
    -----
        powershell -File android/tools/check-catalog-lock.ps1
#>
param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent)
)
$ErrorActionPreference = 'Stop'

$catalogPath = Join-Path $ProjectRoot 'gradle/libs.versions.toml'
$lockPath = Join-Path $ProjectRoot 'app/gradle.lockfile'
$pluginLockPath = Join-Path $ProjectRoot 'buildscript-gradle.lockfile'
if (-not (Test-Path -LiteralPath $catalogPath)) { throw "Missing version catalog: $catalogPath" }
if (-not (Test-Path -LiteralPath $lockPath)) { throw "Missing lock state: $lockPath" }

# --- the catalog -------------------------------------------------------------
$versions = @{}
$libraries = [ordered]@{}
$plugins = [ordered]@{}
$section = ''
foreach ($line in Get-Content -LiteralPath $catalogPath) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed.StartsWith('[')) { $section = $trimmed.Trim('[', ']'); continue }
    if ($section -eq 'versions' -and $trimmed -match '^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"$') {
        $versions[$Matches[1]] = $Matches[2]
        continue
    }
    if ($section -eq 'plugins') {
        if ($trimmed -notmatch '^([A-Za-z0-9_.-]+)\s*=\s*\{(.+)\}$') { continue }
        $alias = $Matches[1]; $body = $Matches[2]
        $id = $null; $ref = $null
        if ($body -match 'id\s*=\s*"([^"]+)"') { $id = $Matches[1] }
        if ($body -match 'version\.ref\s*=\s*"([^"]+)"') { $ref = $Matches[1] }
        $plugins[$alias] = @{ id = $id; ref = $ref }
        continue
    }
    if ($section -ne 'libraries') { continue }
    if ($trimmed -notmatch '^([A-Za-z0-9_.-]+)\s*=\s*\{(.+)\}$') { continue }
    $alias = $Matches[1]; $body = $Matches[2]
    $module = $null; $ref = $null
    if ($body -match 'module\s*=\s*"([^"]+)"') { $module = $Matches[1] }
    if ($body -match 'version\.ref\s*=\s*"([^"]+)"') { $ref = $Matches[1] }
    $libraries[$alias] = @{ module = $module; ref = $ref }
}

# --- the lock state ---------------------------------------------------------
# The lines are `group:name:version=configuration,configuration`. The special
# line `empty=<configuration>` marks a configuration that resolved nothing.
$locked = @{}
foreach ($line in Get-Content -LiteralPath $lockPath) {
    if ($line.StartsWith('#') -or -not $line.Contains('=')) { continue }
    $coordinate = $line.Substring(0, $line.IndexOf('='))
    if ($coordinate -eq 'empty') { continue }
    $locked[$coordinate] = $line.Substring($line.IndexOf('=') + 1).Split(',')
}
$versionsOf = { param([string]$Module)
    $found = @()
    foreach ($coordinate in $locked.Keys) {
        $parts = $coordinate.Split(':')
        if ($parts.Count -ge 2 -and ($parts[0] + ':' + $parts[1]) -eq $Module) { $found += $parts[2] }
    }
    return @($found | Sort-Object -Unique)
}

$problems = @()
$notes = @()
$compared = 0
$bomManaged = 0
foreach ($alias in $libraries.Keys) {
    $entry = $libraries[$alias]
    if (-not $entry.module) { continue }
    if ($entry.ref) {
        $declared = $versions[$entry.ref]
        if (-not $declared) { continue }
        $compared++
        $coordinate = "$($entry.module):$declared"
        if ($locked.ContainsKey($coordinate)) {
            # The module may be locked at another version as well: a
            # transitive dependency of the annotation processor pins its own.
            $others = @((& $versionsOf $entry.module) | Where-Object { $_ -ne $declared })
            if ($others.Count) {
                $notes += "${alias}: $coordinate is locked, and the same module is also locked at $($others -join ', ') (another configuration resolves it differently)"
            }
            continue
        }
        $others = @(& $versionsOf $entry.module)
        if ($others.Count) {
            $problems += "${alias}: the catalog declares $coordinate but the lock state builds $($others -join ', ')"
        } else {
            $problems += "${alias}: the catalog declares $coordinate but the lock state does not name the module at all"
        }
        continue
    }
    # No version of its own: the version comes from a BOM. The library must
    # be in the lock state, otherwise the BOM does not govern it.
    $bomManaged++
    $resolved = @(& $versionsOf $entry.module)
    if (-not $resolved.Count) {
        $problems += "${alias}: the catalog gives no version (a BOM manages it) and the lock state does not name $($entry.module) at all"
        continue
    }
    $notes += "${alias}: managed by a BOM, the lock state resolves $($entry.module) at $($resolved -join ', ')"
}

# --- the plugins -------------------------------------------------------------
# The plugins are applied in the root project, so their classpath is locked in
# buildscript-gradle.lockfile and NOT in the settings' lock file.
$pluginLocked = @{}
if (Test-Path -LiteralPath $pluginLockPath) {
    foreach ($line in Get-Content -LiteralPath $pluginLockPath) {
        if ($line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        $coordinate = $line.Substring(0, $line.IndexOf('='))
        if ($coordinate -eq 'empty') { continue }
        $pluginLocked[$coordinate] = $line.Substring($line.IndexOf('=') + 1).Split(',')
    }
} else {
    $problems += 'the lock state of the plugin classpath (buildscript-gradle.lockfile) is missing'
}
$pluginCount = 0
foreach ($alias in $plugins.Keys) {
    $entry = $plugins[$alias]
    if (-not $entry.id -or -not $entry.ref) { continue }
    $declared = $versions[$entry.ref]
    if (-not $declared) { continue }
    $pluginCount++
    # The plugin marker is the artifact the plugin id resolves to.
    $marker = "$($entry.id):$($entry.id).gradle.plugin:$declared"
    if ($pluginLocked.ContainsKey($marker)) { continue }
    $others = @($pluginLocked.Keys | Where-Object { $_ -like "$($entry.id):*" })
    if ($others.Count) {
        $problems += "${alias}: the catalog declares the plugin $($entry.id) at $declared but the lock state builds $($others -join ', ')"
    } else {
        $problems += "${alias}: the catalog declares the plugin $($entry.id) at $declared but the plugin classpath does not lock it"
    }
}

if ($problems.Count) {
    Write-Output 'The version catalog and the dependency lock state disagree:'
    foreach ($problem in $problems) { Write-Output "  $problem" }
    exit 1
}
foreach ($note in $notes) { Write-Output "  note: $note" }
Write-Output ("The version catalog and the dependency lock state agree ({0} libraries with their own version, {1} managed by a BOM, {2} plugins, {3} libraries in the catalog)." -f `
    $compared, $bomManaged, $pluginCount, $libraries.Count)
