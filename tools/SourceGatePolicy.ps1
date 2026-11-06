function Assert-SourceJobResults([object]$Results) {
    foreach ($name in @('policy','build','dependency-plan')) {
        if ($Results.$name.result -ne 'success') { throw "Mandatory source job '$name' did not succeed: $($Results.$name.result)" }
    }
    $required = [string]$Results.'dependency-plan'.outputs.cold
    if ($required -notin @('true','false')) { throw 'Cold-cache requirement was not determined.' }
    $result = [string]$Results.cold.result
    if ($required -eq 'true' -and $result -ne 'success') { throw "Required cold-cache job did not succeed: $result" }
    if ($required -eq 'false' -and $result -notin @('success','skipped')) { throw "Optional cold-cache job failed: $result" }
}

function Assert-DeviceAcceptanceReport([object]$Report, [string]$SourceSha, [string]$ApkSha256, [string]$EvidenceRoot) {
    if ($SourceSha -cnotmatch '^[a-f0-9]{40}$' -or $ApkSha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Source/APK identity is missing or invalid.' }
    if ($Report.sourceSha -cne $SourceSha -or $Report.apkSha256 -cne $ApkSha256) { throw 'Device evidence belongs to another source or APK.' }
    if ([string]::IsNullOrWhiteSpace([string]$Report.device.model) -or [int]$Report.device.api -lt 26 -or -not $Report.executedUtc) { throw 'Device stand and execution date are required.' }
    $executed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$Report.executedUtc, [ref]$executed) -or $executed -gt [DateTimeOffset]::UtcNow.AddMinutes(5)) {
        throw 'A valid, non-future device execution timestamp is required.'
    }
    if ([string]::IsNullOrWhiteSpace($EvidenceRoot) -or -not (Test-Path -LiteralPath $EvidenceRoot -PathType Container)) {
        throw 'An existing, explicitly approved evidence directory is required.'
    }
    $root = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $EvidenceRoot).Path).TrimEnd('\', '/')
    $rootItem = Get-Item -LiteralPath $root -Force
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'The approved evidence directory must not be a link.' }
    $prefix = $root + [IO.Path]::DirectorySeparatorChar
    $comparison = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    $rows = @($Report.scenarios)
    if ($rows.Count -ne 22) { throw 'Device report must contain exactly D01-D22.' }
    foreach ($number in 1..22) {
        $id = 'D{0:d2}' -f $number
        $row = @($rows | Where-Object { $_.id -eq $id })
        if ($row.Count -ne 1 -or $row[0].status -ne 'PASS' -or [string]::IsNullOrWhiteSpace([string]$row[0].actualResult) -or -not $row[0].evidence) {
            throw "Device scenario $id has not passed with evidence."
        }
        foreach ($reference in @($row[0].evidence)) {
            if ($reference -isnot [string] -or [string]::IsNullOrWhiteSpace($reference) -or
                [IO.Path]::IsPathRooted($reference) -or $reference -match '(^|[\\/])\.\.([\\/]|$)|:') {
                throw "Device scenario $id has an invalid evidence path. Use a relative local file inside the approved evidence directory."
            }
            $candidate = [IO.Path]::GetFullPath((Join-Path $root $reference))
            if (-not $candidate.StartsWith($prefix, $comparison)) { throw "Evidence for $id escapes the approved directory." }
            # Inspect each ancestor before opening the final file. Reject all
            # reparse points (including junctions), so a link cannot escape the
            # lexical root even when the final path appears to be inside it.
            $current = $root
            foreach ($segment in ($reference -split '[\\/]')) {
                if (-not $segment -or $segment -eq '.') { continue }
                $current = Join-Path $current $segment
                if (-not (Test-Path -LiteralPath $current)) { throw "Evidence for $id does not exist: $reference" }
                $item = Get-Item -LiteralPath $current -Force
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked evidence is not accepted for $id`: $reference" }
            }
            if ($item.PSIsContainer -or $item.Length -le 0) { throw "Evidence for $id must be a nonempty file: $reference" }
        }
    }
    if (@($Report.openDefects | Where-Object { $_.severity -in @('S0','S1') }).Count) { throw 'Open S0/S1 defects block device acceptance.' }
}
