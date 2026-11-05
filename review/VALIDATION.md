# Android: проверка исходников — 2026-10-20

_Сгенерировано `android/tools/update-review.ps1` из реальных результатов
прогона. Правка вручную этого файла невозможна: числа должны совпадать с
`review/TEST_SUMMARY.json` и `review/SNAPSHOT.json`, а все три файла
создаются одним разбором одного прогона._

- **BUILD SUCCESSFUL**: `:app:testDebugUnitTest`, `:app:lintDebug`,
  `:app:compileDebugAndroidTestKotlin`. Ни одной задачи `assemble*`/`bundle*`/
  `package*` не выполнялось; APK не собирался; устройство не включалось.
- Команда (из корня репозитория):

      powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest,:app:lintDebug,:app:compileDebugAndroidTestKotlin' -Summarize

- **JVM: 604 тестов, 0 failures, 0 errors, 0 skipped**
  (82 файлов JUnit XML).
  * Пропущенных тестов **нет**: сетевой `LiveProviderProbeTest` не «пропущен»,
    а **исключён** из прогона (`android/app/build.gradle`, `tasks.withType(Test)`,
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
    (`android/local.properties`, `android/lint.xml`); остальные файлы
    свойств проверяются как обычно;
  * три детектора «доступна новая версия» (`NewerVersionAvailable`,
    `GradleDependency`, `AndroidGradlePluginVersion`) — они читают репозиторий
    **по сети**, и результат гейта зависел от состояния кэша (аудит измерил
    15, 13 и 4 предупреждения для одного и того же исходника). Отчёт о новых
    версиях вынесен в отдельную, **не блокирующую** задачу
    `:reportNewerVersions`.
  Все остальные проверки включены, и lint останавливает сборку на ошибке.
- **68 инструментальных тестов скомпилированы, НЕ выполнены**
  (18 файлов в `app/src/androidTest`). Ни одного устройства и ни
  одного эмулятора не было.
- **Свежесть.** Все числа взяты из прогона, который **новее** самого свежего
  исходника дерева (`build.gradle`).
- **Снимок исходников**: файлов в манифесте — 254; расхождений —
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

    powershell -File android/review/VERIFY.ps1

печатает новые, изменённые и удалённые файлы и завершается с ошибкой, пока
манифест не перегенерирован. Перегенерация (после правки всех областей):

    powershell -File android/tools/update-review.ps1 -RefreshSnapshot

Ограничения полного переноса — ../PORTING_STATUS.md.
