<#
    Regenerates the facts of the review from the last successful source-only
    checks. It never runs Gradle and never packages an APK.

    What it reads
    -------------
    * app/build/test-results/testDebugUnitTest/*.xml  - the JUnit results of
      the JVM gate,
    * app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/
      lint-results-debug.txt - the text report of the lint gate,
    * app/src/androidTest/**/*.kt - the instrumented tests (compiled, never run),
    * review/SOURCE_SHA256.txt - the source snapshot.

    What it writes
    --------------
    review/TEST_SUMMARY.json - the numbers, with the place they come from.
    review/VALIDATION.md     - the same numbers, rendered for a reader.
    review/SNAPSHOT.json     - the current state of the tree.

    The three artefacts are written from ONE parse, so they cannot disagree
    with each other (the hand-written VALIDATION.md of 2026-10-20 disagreed
    with TEST_SUMMARY.json of the same run).

    The freshness rule
    ------------------
    Every published number must come from a run that is NEWER than every
    source file of the tree. A result that predates a source file describes a
    tree that no longer exists, so the generator refuses to publish it. This
    applies to the JUnit XML AND to the lint report: the lint report used to
    be read without any freshness check at all.

    Usage
    -----
        powershell -File android/tools/update-review.ps1
        powershell -File android/tools/update-review.ps1 -RefreshSnapshot
#>
param([switch]$RefreshSnapshot)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'SourceSnapshot.ps1')

# --- the unit tests ----------------------------------------------------------
$resultDir = Join-Path $project 'app/build/test-results/testDebugUnitTest'
$files = @(Get-ChildItem -LiteralPath $resultDir -Filter '*.xml' -ErrorAction SilentlyContinue)
if (-not $files.Count) { throw 'No JUnit XML results. Run the JVM gate first.' }
$counts = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
$classes = @()
foreach ($file in $files) {
    [xml]$xml = [IO.File]::ReadAllText($file.FullName)
    $suite = $xml.testsuite
    foreach ($key in @($counts.Keys)) { $counts[$key] += [int]$suite.GetAttribute($key) }
    $classes += [ordered]@{
        class = [string]$suite.GetAttribute('name')
        tests = [int]$suite.GetAttribute('tests')
        failures = [int]$suite.GetAttribute('failures')
        errors = [int]$suite.GetAttribute('errors')
        skipped = [int]$suite.GetAttribute('skipped')
    }
}
$classes = @($classes | Sort-Object class)

# --- the freshness of the results --------------------------------------------
# The newest file the manifest pins. Everything that is published must be
# newer than this file.
$newestSource = Get-ReviewNewestSource $project
if (-not $newestSource) { throw 'No source files found under the Android project.' }
$invocationPath = Join-Path $project 'app/build/reports/source-gate/run.json'
if (-not (Test-Path -LiteralPath $invocationPath)) {
    throw 'No source build evidence. Run tools/run-source-build.ps1 -RerunTasks first.'
}
$invocation = Get-Content -LiteralPath $invocationPath -Raw | ConvertFrom-Json
$requiredTasks = @(':app:compileDebugKotlin', ':app:compileReleaseKotlin', ':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin')
if ($invocation.exitCode -ne 0 -or -not $invocation.sourceUnchanged -or
    $invocation.sourceDigest -ne (Get-ReviewTreeDigest $project)) {
    throw 'Source build evidence is failed or belongs to another source tree. Rerun tools/run-source-build.ps1.'
}
foreach ($task in $requiredTasks) {
    if ($task -cnotin $invocation.tasks) { throw "Source evidence is missing task $task." }
}
if ([DateTime]::Parse($invocation.finishedUtc).ToUniversalTime() -lt $newestSource.LastWriteTimeUtc) {
    throw 'Source build evidence predates the current sources.'
}
$oldestResult = $files | Sort-Object LastWriteTimeUtc | Select-Object -First 1
if ($newestSource.LastWriteTimeUtc -gt $oldestResult.LastWriteTimeUtc) {
    throw "The JUnit XML predates the sources (newest source: $($newestSource.path)). Rerun the JVM gate with -RerunTasks."
}
if ($counts.failures -or $counts.errors) { throw 'JUnit XML contains failures.' }

# --- the lint report ---------------------------------------------------------
$lintReport = Join-Path $project 'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt'
if (-not (Test-Path -LiteralPath $lintReport)) {
    throw 'No lint report. Run :app:lintDebug first.'
}
$lintTime = (Get-Item -LiteralPath $lintReport).LastWriteTimeUtc
if ($newestSource.LastWriteTimeUtc -gt $lintTime) {
    throw "The lint report predates the sources (newest source: $($newestSource.path)). Rerun the gate with -RerunTasks."
}
$text = [IO.File]::ReadAllText($lintReport)

# A finding line looks like
#   C:\path\to\file.kt:12: Warning: some message [IssueId]
# The path holds the drive letter, so the file part is matched GREEDILY and
# the severity keyword anchors the line. A non-greedy path cannot cross the
# ':' of the drive letter, which is why the breakdown used to be empty on
# Windows while the same report looked complete by hand.
$finding = '(?m)^(?<file>.+):(?<line>\d+): (?<severity>Error|Warning|Hint): (?<message>.*?)\s*\[(?<id>[A-Za-z0-9_.-]+)\]\s*$'
$byId = @{}
$bySeverity = @{ Error = 0; Warning = 0; Hint = 0 }
$findings = @()
foreach ($match in [regex]::Matches($text, $finding)) {
    $id = $match.Groups['id'].Value
    $severity = $match.Groups['severity'].Value
    if (-not $byId.ContainsKey($id)) { $byId[$id] = 0 }
    $byId[$id]++
    $bySeverity[$severity]++
    # The report carries the path of the ASCII junction, not the path of the
    # tree. The published artefact must not hold a machine-local path.
    $file = $match.Groups['file'].Value.Replace('\', '/')
    $marker = '/project/'
    $index = $file.LastIndexOf($marker)
    $relative = if ($index -ge 0) { $file.Substring($index + $marker.Length).Replace('\', '/') } else { $file }
    $findings += [ordered]@{
        file = $relative; line = [int]$match.Groups['line'].Value
        severity = $severity; id = $id
    }
}
$totals = [regex]::Match($text, '(?m)^(?<errors>\d+) errors?, (?<warnings>\d+) warnings?(?:, (?<hints>\d+) hints?)?\s*$')
if (-not $totals.Success) { throw 'The lint report has no totals line; it cannot be read.' }
$reportedErrors = [int]$totals.Groups['errors'].Value
$reportedWarnings = [int]$totals.Groups['warnings'].Value
$reportedHints = if ($totals.Groups['hints'].Success) { [int]$totals.Groups['hints'].Value } else { 0 }
if ($reportedErrors -ne $bySeverity.Error -or $reportedWarnings -ne $bySeverity.Warning) {
    throw ("The lint report disagrees with itself: the totals line says $reportedErrors errors and $reportedWarnings warnings, " +
        "the report holds $($bySeverity.Error) errors and $($bySeverity.Warning) warnings. The parser is broken.")
}
if ($reportedHints -gt 0 -and $reportedHints -ne $bySeverity.Hint) {
    throw ("The lint report disagrees with itself about the hints: the totals line says $reportedHints, the report holds $($bySeverity.Hint).")
}
# An empty breakdown next to a non-empty finding list is a broken parser, not
# a clean run. It must never be published as "no findings".
if (($bySeverity.Error + $bySeverity.Warning + $bySeverity.Hint) -gt 0 -and $byId.Count -eq 0) {
    throw 'The lint breakdown is empty although the report holds findings.'
}
$lint = [ordered]@{
    errors = $bySeverity.Error; warnings = $bySeverity.Warning; hints = $bySeverity.Hint
    by_id = [ordered]@{}; report = 'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt'
    generated_utc = (Get-Item -LiteralPath $lintReport).LastWriteTimeUtc.ToString('o')
    findings = $findings
}
foreach ($id in ($byId.Keys | Sort-Object)) { $lint.by_id[$id] = $byId[$id] }

# --- the instrumented tests --------------------------------------------------
$deviceFiles = @(Get-ChildItem -LiteralPath (Join-Path $project 'app/src/androidTest') -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue)
$deviceCount = 0
foreach ($file in $deviceFiles) { $deviceCount += ([regex]::Matches([IO.File]::ReadAllText($file.FullName), '@Test\b')).Count }

# --- the snapshot ------------------------------------------------------------
$manifest = Join-Path $project 'review/SOURCE_SHA256.txt'
if ($RefreshSnapshot) { Write-ReviewSnapshot $project | Out-Null }
$drift = Compare-ReviewSnapshot $project
$manifestHash = if (Test-Path -LiteralPath $manifest) { Get-ReviewFileHash $manifest } else { '' }

$summary = [ordered]@{
    generated_utc = [DateTime]::UtcNow.ToString('o')
    source = @(
        'app/build/test-results/testDebugUnitTest/*.xml (JUnit XML of :app:testDebugUnitTest)',
        'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt (:app:lintDebug)',
        'app/src/androidTest/**/*.kt (@Test methods, compiled only)'
    )
    freshness = [ordered]@{
        newest_source = $newestSource.path
        newest_source_utc = $newestSource.LastWriteTimeUtc.ToString('o')
        junit_xml_utc = $oldestResult.LastWriteTimeUtc.ToString('o')
        lint_report_utc = $lintTime.ToString('o')
        newer_than_every_source = $true
    }
    build_evidence = $invocation
    unit = [ordered]@{
        tests = $counts.tests; passed = $counts.tests - $counts.skipped
        failures = $counts.failures; errors = $counts.errors; skipped = $counts.skipped
        test_files = $files.Count; classes = $classes
    }
    lint = $lint
    instrumentation = [ordered]@{
        compiled_tests = $deviceCount; test_files = $deviceFiles.Count; executed = $false
    }
    snapshot = [ordered]@{
        files = $drift.checked; manifest_sha256 = $manifestHash
        new = $drift.new; changed = $drift.changed; removed = $drift.removed; unlisted = $drift.unlisted
    }
    apk_packaging_executed = $false
    runtime_checked = $false
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $project 'review/TEST_SUMMARY.json') -Encoding UTF8

# --- the current snapshot ----------------------------------------------------
$snapshot = [ordered]@{
    generated_utc = [DateTime]::UtcNow.ToString('o')
    date = (Get-Date).ToString('yyyy-MM-dd')
    stage = 'standalone-ui-foundation'
    files = $drift.checked
    manifest_sha256 = $manifestHash
    unit_tests = $counts.tests
    unit_passed = $counts.tests - $counts.skipped
    unit_failures = $counts.failures
    lint_errors = $lint.errors
    lint_warnings = $lint.warnings
    lint_hints = $lint.hints
    instrumentation_compiled_only = $deviceCount
    instrumentation_executed = 0
    apk_built = $false
    runtime_checked = $false
}
$snapshot | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $project 'review/SNAPSHOT.json') -Encoding UTF8

# --- the human readable rendering --------------------------------------------
$breakdown = @()
foreach ($id in ($byId.Keys | Sort-Object)) {
    $breakdown += "| $id | $($byId[$id]) |"
}
$breakdownText = if ($breakdown.Count) { $breakdown -join "`n" } else { '| (нет находок) | 0 |' }
$driftText = "новых $($drift.new.Count), изменённых $($drift.changed.Count), удалённых $($drift.removed.Count), не перечисленных $($drift.unlisted.Count)"
$validation = @'
# Android: проверка исходников — {{DATE}}

_Сгенерировано `tools/update-review.ps1` из реальных результатов
прогона. Правка вручную этого файла невозможна: числа должны совпадать с
`review/TEST_SUMMARY.json` и `review/SNAPSHOT.json`, а все три файла
создаются одним разбором одного прогона._

- **BUILD SUCCESSFUL**: `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`,
  `:app:testDebugUnitTest`, `:app:lintDebug`,
  `:app:compileDebugAndroidTestKotlin`. Вызваны только эти пять разрешённых
  source-задач. Упаковка APK/AAB и запуск Android не выполнялись. Вспомогательные
  задачи упаковки ресурсов и JAR выполняются Gradle автоматически.
- Команда (из корня репозитория):

      powershell -NoProfile -File tools/run-source-build.ps1 -RerunTasks

  Успешный запуск и digest исходников зафиксированы в
  `app/build/reports/source-gate/run.json`. Изменение дерева во время или после
  запуска делает доказательство непригодным для этого отчёта.

- **JVM: {{TESTS}} тестов, {{FAILURES}} failures, {{ERRORS}} errors, {{SKIPPED}} skipped**
  ({{FILES}} файлов JUnit XML).
  * Пропущенных тестов **нет**: сетевой `LiveProviderProbeTest` не «пропущен»,
    а **исключён** из прогона (`app/build.gradle`, `tasks.withType(Test)`,
    `exclude '**/LiveProviderProbeTest*'`). Он запускается только с
    `-PliveProviderProbes=true`.
- **Lint: {{LINT_ERRORS}} errors, {{LINT_WARNINGS}} warnings, {{LINT_HINTS}} hints.**
  Разбивка по идентификаторам (из
  `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`):

  | Issue | Count |
  |---|---|
{{BREAKDOWN}}

  Подавлены только две вещи, и обе поименно:
  * **PropertyEscape** — для одного машинно-локального файла
    (`local.properties`, `lint.xml`); остальные файлы
    свойств проверяются как обычно;
  * три детектора «доступна новая версия» (`NewerVersionAvailable`,
    `GradleDependency`, `AndroidGradlePluginVersion`) — они читают репозиторий
    **по сети**, и результат гейта зависел от состояния кэша (аудит измерил
    15, 13 и 4 предупреждения для одного и того же исходника). Отчёт о новых
    версиях вынесен в отдельную, **не блокирующую** задачу
    `:reportNewerVersions`.
  Все остальные проверки включены, и lint останавливает сборку на ошибке.
- **{{DEVICE}} инструментальных тестов скомпилированы, НЕ выполнены**
  ({{DEVICE_FILES}} файлов в `app/src/androidTest`). Instrumentation не запускался
  ни на устройстве, ни на эмуляторе.
- **Свежесть.** Все числа взяты из прогона, который **новее** самого свежего
  исходника дерева (`{{NEWEST_SOURCE}}`).
- **Снимок исходников**: файлов в манифесте — {{FILES_TREE}}; расхождений —
  {{DRIFT}}.

## Ограничения

Нет запуска Android, звука, двух реальных декодеров, focus/noisy/BT/FGS,
SAF/grants/Keystore, OAuth с живым аккаунтом, visual QA и скриншотов.
Device-тесты только скомпилированы. Реальная сеть провайдеров не проверялась:
сетевой probe по умолчанию выключен, тесты MockWebServer не доказывают
доступность Spotify/Яндекса/YouTube.

Сборка и host HTTP-проба **не** доказывают звук на Android. Полный список
нереализованного и непроверенного — [../PORTING_STATUS.md](../PORTING_STATUS.md).

## Снимок исходников

`review/SOURCE_SHA256.txt` — SHA-256 каждого исходника дерева; пути
относительные, машинно-локальный путь в манифесте быть не может
(проверяется при чтении манифеста). Проверка:

    powershell -File review/VERIFY.ps1

печатает новые, изменённые и удалённые файлы и завершается с ошибкой, пока
манифест не перегенерирован. Перегенерация (после правки всех областей):

    powershell -File tools/update-review.ps1 -RefreshSnapshot

Ограничения полного переноса — ../PORTING_STATUS.md.
'@
$validation = $validation.Replace('{{DATE}}', (Get-Date).ToString('yyyy-MM-dd'))
$validation = $validation.Replace('{{TESTS}}', [string]$counts.tests)
$validation = $validation.Replace('{{FAILURES}}', [string]$counts.failures)
$validation = $validation.Replace('{{ERRORS}}', [string]$counts.errors)
$validation = $validation.Replace('{{SKIPPED}}', [string]$counts.skipped)
$validation = $validation.Replace('{{FILES}}', [string]$files.Count)
$validation = $validation.Replace('{{LINT_ERRORS}}', [string]$lint.errors)
$validation = $validation.Replace('{{LINT_WARNINGS}}', [string]$lint.warnings)
$validation = $validation.Replace('{{LINT_HINTS}}', [string]$lint.hints)
$validation = $validation.Replace('{{BREAKDOWN}}', $breakdownText)
$validation = $validation.Replace('{{DEVICE}}', [string]$deviceCount)
$validation = $validation.Replace('{{DEVICE_FILES}}', [string]$deviceFiles.Count)
$validation = $validation.Replace('{{NEWEST_SOURCE}}', $newestSource.path)
$validation = $validation.Replace('{{FILES_TREE}}', [string]$drift.checked)
$validation = $validation.Replace('{{DRIFT}}', $driftText)
Set-Content -LiteralPath (Join-Path $project 'review/VALIDATION.md') -Value $validation -Encoding UTF8

Write-Output ("TEST_SUMMARY files={0} tests={1} failures={2} errors={3} skipped={4} lint={5}/{6}/{7} snapshot_files={8} drift={9}" -f `
    $files.Count, $counts.tests, $counts.failures, $counts.errors, $counts.skipped,
    $lint.errors, $lint.warnings, $lint.hints, $drift.checked,
    ($drift.new.Count + $drift.changed.Count + $drift.removed.Count + $drift.unlisted.Count))
if ($drift.new.Count -or $drift.changed.Count -or $drift.removed.Count -or $drift.unlisted.Count) {
    Write-Output 'The snapshot is stale. Rerun with -RefreshSnapshot after every area has finished.'
}
