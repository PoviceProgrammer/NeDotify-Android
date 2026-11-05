<#
    The source snapshot of the Android tree.

    The manifest (review/SOURCE_SHA256.txt) holds one line per source file:

        <sha256>  <path relative to android/, with forward slashes>

    The paths are relative on purpose: the manifest must be portable, so it
    may not contain a machine-local absolute path. Compare-ReviewSnapshot
    proves that property instead of assuming it.

    Usage
    -----
        powershell -File android/review/VERIFY.ps1              # check the tree
        powershell -File android/tools/update-review.ps1 -RefreshSnapshot
#>

function Get-ReviewFileHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}

function Get-ReviewSources([string]$ProjectRoot) {
    $root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\', '/')
    Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
        if ($relative -match '(^|/)(build|\.gradle|\.kotlin|captures)/' -or
            $relative -eq 'local.properties' -or
            $relative -like 'local.properties.*.bak' -or
            ($relative -like 'review/*' -and $_.Extension -ne '.ps1')) { return }
        # The rule set must cover every file whose content the build or the
        # review consumes - including the documentation of the tree itself.
        # A rule set that only covers the code hides the drift of the files
        # that describe it.
        $extension = $_.Extension.ToLowerInvariant()
        if ($extension -in @('.kt', '.java', '.xml', '.gradle', '.properties', '.toml', '.ps1',
                '.bat', '.pro', '.jar', '.py', '.md', '.json', '.png', '.yml', '.yaml',
                '.txt', '.lockfile') -or
            $_.Name -eq '.gitignore' -or
            $relative -like 'app/schemas/*.json' -or $relative -eq 'gradlew' -or
            $relative -eq 'gradle.lockfile' -or $relative -eq 'app/gradle.lockfile') { $relative }
    } | Sort-Object
}

<#
    The newest file of the tree that the manifest pins. The published numbers
    must come from a run that is newer than this file, otherwise the review
    describes a source tree that no longer exists.
#>
function Get-ReviewNewestSource([string]$ProjectRoot) {
    $root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\', '/')
    $newest = $null
    foreach ($relative in Get-ReviewSources $ProjectRoot) {
        $item = Get-Item -LiteralPath (Join-Path $root $relative)
        if ($null -eq $newest -or $item.LastWriteTimeUtc -gt $newest.LastWriteTimeUtc) {
            $newest = [pscustomobject]@{ path = $relative; LastWriteTimeUtc = $item.LastWriteTimeUtc }
        }
    }
    return $newest
}

function Read-ReviewManifest([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing snapshot manifest: $Path" }
    $entries = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if (-not $line.Trim()) { continue }
        if ($line -notmatch '^([a-f0-9]{64})  ([^\\]+)$') { throw 'Malformed snapshot manifest.' }
        $hash = $Matches[1]; $relative = $Matches[2]
        if ($relative.StartsWith('/') -or $relative -match '(^|/)\.\.(/|$)|:' -or $entries.ContainsKey($relative)) {
            throw "Invalid or duplicate manifest path: $relative"
        }
        # A machine-local absolute path would make the manifest unusable on
        # another machine. The manifest is portable by construction, not by
        # convention.
        if ($relative -match '^[A-Za-z]:' -or $relative -match '\\\\') {
            throw "The manifest holds a machine-local path: $relative"
        }
        $entries[$relative] = $hash
    }
    return $entries
}

<#
    Compares the manifest with the tree and returns the drift:
    new files (in the tree, not in the manifest), changed files (a different
    hash), removed files (in the manifest and no longer on disk) and unlisted
    files (in the manifest, still on disk, but no longer a source under the
    current rules - the manifest was written with an older rule set).
#>
function Compare-ReviewSnapshot([string]$ProjectRoot) {
    $manifestPath = Join-Path $ProjectRoot 'review/SOURCE_SHA256.txt'
    $entries = Read-ReviewManifest $manifestPath
    $actual = @(Get-ReviewSources $ProjectRoot)
    $new = @(); $changed = @()
    foreach ($relative in $actual) {
        if (-not $entries.ContainsKey($relative)) { $new += $relative; continue }
        $hash = Get-ReviewFileHash (Join-Path $ProjectRoot $relative)
        if ($hash -ne $entries[$relative]) { $changed += $relative }
        $entries.Remove($relative)
    }
    $removed = @(); $unlisted = @()
    foreach ($relative in @($entries.Keys | Sort-Object)) {
        if (Test-Path -LiteralPath (Join-Path $ProjectRoot $relative)) { $unlisted += $relative }
        else { $removed += $relative }
    }
    return [pscustomobject]@{
        checked = $actual.Count
        new = @($new | Sort-Object)
        changed = @($changed | Sort-Object)
        removed = @($removed | Sort-Object)
        unlisted = @($unlisted | Sort-Object)
    }
}

function Write-ReviewSnapshot([string]$ProjectRoot) {
    $lines = @(Get-ReviewSources $ProjectRoot | ForEach-Object {
        $hash = Get-ReviewFileHash (Join-Path $ProjectRoot $_)
        "$hash  $_"
    })
    [IO.File]::WriteAllLines((Join-Path $ProjectRoot 'review/SOURCE_SHA256.txt'), $lines, [Text.Encoding]::ASCII)
    return $lines.Count
}

function Test-ReviewSnapshot([string]$ProjectRoot) {
    $drift = Compare-ReviewSnapshot $ProjectRoot
    $problems = $drift.new.Count + $drift.changed.Count + $drift.removed.Count + $drift.unlisted.Count
    if ($problems) {
        Write-Output "The source snapshot does not match the tree ($($drift.checked) files checked)."
        if ($drift.new.Count) {
            Write-Output "New ($($drift.new.Count)):"
            $drift.new | ForEach-Object { Write-Output "  + $_" }
        }
        if ($drift.changed.Count) {
            Write-Output "Changed ($($drift.changed.Count)):"
            $drift.changed | ForEach-Object { Write-Output "  ~ $_" }
        }
        if ($drift.removed.Count) {
            Write-Output "Removed ($($drift.removed.Count)):"
            $drift.removed | ForEach-Object { Write-Output "  - $_" }
        }
        if ($drift.unlisted.Count) {
            Write-Output "No longer a source, but still on disk ($($drift.unlisted.Count)):"
            $drift.unlisted | ForEach-Object { Write-Output "  ? $_" }
        }
        throw "The snapshot is stale: regenerate review/SOURCE_SHA256.txt (tools/update-review.ps1 -RefreshSnapshot)."
    }
    Write-Output "Review snapshot verified ($($drift.checked) files)."
}
