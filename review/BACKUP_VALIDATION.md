# Android: резервные копии библиотеки — 2026-10-18

- Исходники после APK 0.2.0. По указанию пользователя APK НЕ собирался.
- Проверка: `./android/verify.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin')`.
- Успешны Kotlin/Room KSP, JVM и lint; итоговый запуск BUILD SUCCESSFUL. Лог: `SOURCE_CHECKS.log`, граф: `TASK_GRAPH.log`.
- **57 JVM passed, 1 skipped, 0 failures/errors**. Из них 14 тестов кодека и 7 слияния. Сетевая проба opt-in не запускалась.
- **Lint: 0 errors, 14 warnings**: {'AndroidGradlePluginVersion': 1, 'ExportedService': 1, 'GradleDependency': 4, 'NewerVersionAvailable': 8}. Не подавлялись. ExportedService разобран в REVIEW_RESOLUTION.md.
- **18 instrumentation tests скомпилированы, НЕ выполнены**. Новые: 3 Room merge/rollback/reattach и 6 Compose preview/busy/error/retry. Ранее: DAO, migration, downloads и navigation.
- Проверены JVM: Cyrillic/emoji, порядок, избранное, measured history, стабильные local ID и повторный перенос; отсутствие private paths/подписей; strict JSON/UTF-8/types/version/duplicates/references/depth; размер 16 МБ до JSON DOM; repeat import/idempotence, сохранение URI/метаданных, коллизии source/session.
- Первая компиляция instrumentation обнаружила ошибку matcher hasSibling. Автор исправил её на hasAnySibling; повторная компиляция успешна. Compile warnings: старые createComposeRule/createAndroidComposeRule рекомендовано мигрировать на v2; runtime-тесты ещё не запускались.
- SAF export/import, grant persistence после перезапуска, Room на устройстве, поворот/гибель процесса, звук и непрерывность воспроизведения при импорте **не проверены**. Runtime-скриншотов этого этапа нет.
- Device/profile/downloads пользователя не трогались. Desktop код не менялся на этом этапе. Два субагента писали код формата и UI; основной агент — Room/merge/storage и интеграцию. Это не независимое ревью.
- Исторические два внешних ревью проверяли baseline b8c5f20e…ceb4b12. Новые исходники повторно независимо не проверены.
- Старый APK0.2.0: **не изменён**, 25112688 bytes, SHA256 `2407578fe76b480dae6124f0aaef26d651e0042baa6f2711ed4ff6c9a0a9c603`. Он не содержит резервного переноса. Подтверждение: `SOURCE_CHECKS.json`; local.properties тоже восстановлен побайтово.
- `verify.ps1` теперь по умолчанию выполняет только unit/lint/compile Android tests. Package/assemble не запускались; package*Resources в графе — компиляция ресурсов, не APK.
- Историческая проверка скачиваний/APK сохранена: `DOWNLOADS_VALIDATION.md`, `BUILD.log`. Текущая проверка не выдаёт их за новую сборку.
- Текущий манифест: 89 файлов, SHA256 `d32ed898bdd924b49d13b9001e3dac18d533872d8c2d8401bd99c887341886cf`. Проверить: `./android/review/VERIFY.ps1`.

Текущий этап — **3: библиотека, загрузки, резервные копии**. Реализован перенос
Android → Android, без аудиофайлов, queue/settings/auth и desktop DB. Общая готовность
оценивается примерно в **40%**, осталось около **60%** по объёму реализации.
Runtime-проверки первых этапов ещё не закрыты. Этапы и ограничения — `PORTING_STATUS.md`.
