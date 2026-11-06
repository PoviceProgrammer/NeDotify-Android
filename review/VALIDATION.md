# Android: проверка исходников — 2026-11-06

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

- **JVM: 633 тестов, 0 failures, 0 errors, 0 skipped**
  (87 файлов JUnit XML).
  * Пропущенных тестов **нет**: сетевой `LiveProviderProbeTest` не «пропущен»,
    а **исключён** из прогона (`app/build.gradle`, `tasks.withType(Test)`,
    `exclude '**/LiveProviderProbeTest*'`). Он запускается только с
    `-PliveProviderProbes=true`.
- **Lint: 0 errors, 2 warnings, 0 hints.**
  Разбивка по идентификаторам (из
  `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`):

  | Issue | Count |
  |---|---|
| ExportedService | 1 |
| UseKtx | 1 |

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
- **79 инструментальных тестов скомпилированы, НЕ выполнены**
  (22 файлов в `app/src/androidTest`). Instrumentation не запускался
  ни на устройстве, ни на эмуляторе.
- **Свежесть.** Все числа взяты из прогона, который **новее** самого свежего
  исходника дерева (`app/src/androidTest/java/ru/nedotify/app/testing/IsolatedAndroidTestRunner.kt`).
- **Снимок исходников**: файлов в манифесте — 325; расхождений —
  новых 0, изменённых 0, удалённых 0, не перечисленных 0.

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
