<#
    The allow-list of the source-only gate.

    An allow-list also rejects Gradle task abbreviations and option injection:
    the caller may not smuggle `-P` or `--init-script` past the check by
    spelling a forbidden task in an abbreviated form.

    Every task named here must EXIST. A task that was removed from the build
    would leave the documented recovery command ("run this to refresh the
    checksums") failing with "Task not found", which is worse than not
    offering it at all.
#>
function Assert-SourceTasks([string[]]$Tasks) {
    $allowed = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugKotlin',
        ':app:compileReleaseKotlin', ':app:compileDebugAndroidTestKotlin', ':app:help',
        ':app:dependencies', ':app:resolveAndLockDependencies',
        ':app:signingReport', ':dumpVariantModel', ':reportNewerVersions')
    if (-not $Tasks.Count) { throw 'Select at least one source-only task.' }
    foreach ($task in $Tasks) {
        if ($task -cnotin $allowed) { throw "Task '$task' is outside the source-only gate. APK and device tasks are forbidden by android/AGENTS.md." }
    }
}
