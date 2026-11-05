# Проверка Android 0.2.0 / офлайн-загрузки, 2026-10-18

- Сборка текущих исходников: `./android/verify.ps1` (unit, lint, debug APK, instrumentation APK).
- BUILD SUCCESSFUL, 1m 27s. Лог: `BUILD.log`. Gradle-скрипты переведены на Groovy DSL для обхода сбоя Kotlin DSL script templates; приложение остаётся Kotlin/Compose.
- JVM: **36 passed**, 1 skipped (opt-in HTTP-проба), 0 failures/errors. Детали: `TEST_SUMMARY.json`.
- Lint: **0 errors, 14 warnings**. {'AndroidGradlePluginVersion': 1, 'ExportedService': 1, 'GradleDependency': 4, 'NewerVersionAvailable': 8}. Предупреждения не подавлялись; ExportedService разобран в REVIEW_RESOLUTION.md.
- JVM: TTL/Policy, метаданные YouTube, форматы SoundCloud, single-flight/401-403, гонки отмены/инвалидации, отсутствующие записи очереди/3000 ID, repeat/auto, паузы, причины ошибок.
- Дополнительно JVM проверяет стабильные ключи download-cache при обновлении подписей, различие версий контента, бюджет retry и остановку старого downloader при отмене.
- Instrumentation APK собран: 9 тестов (4 DAO, миграция Room v1→v2, 3 download-storage, Compose-навигация). Они **НЕ запускались**. DownloadStorageTest проверяет частичный HLS/fresh signatures/cache-only read, отказ для live и пустого VOD; это подготовленные проверки, не свидетельство уже проверенного сценария.
- Метаданные APK: min 26, target/compile 37. `zipalign -c -P 16 4` пройден. Это не запуск на API 26/37.
- local.properties побайтово сохранился после успешной и намеренно неудачной сборки. Failure вернул 1, окружение восстановлено: `VERIFY_RESTORATION.json`.
- HTTP-проба источников перед этапом 0.2.0: SoundCloud — 25 результатов, HLS HTTP 200, первый фрагмент читается. YouTube — 11 результатов, `YouTube: Выполните вход`. `PROVIDER_PROBE.json`, `LIVE_PROBE.log`. Эта проба НЕ проверяет новый downloader.
- Пользователь разрешил установку Windows Hypervisor Platform. Компонент успешно включён, `RestartNeeded=true`: `EMULATOR_SETUP.json`. Windows ещё не перезапускалась, accelerator-check всё ещё отказал. Готов запуск `Run-Android.bat` после перезагрузки, проверен синтаксис PowerShell-скриптов. Загрузка эмулятора/установка APK через launcher пока не подтверждены.
- `adb devices -l` пуст. Download/resume после гибели процесса, cache-only HLS/seek, декодирование/звук, FGS/уведомления, focus/noisy, миграция на Android, UI/blur/TalkBack и скриншоты **не проверены**.
- Два независимых ревью относятся к старому манифесту b8c5f20e…ceb4b12. Оригиналы — `received/`, решения — `REVIEW_RESOLUTION.md`. Исправления НЕ прошли повторное независимое ревью. Субагенты не запускались.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 25112688 bytes.
- APK SHA256: `2407578fe76b480dae6124f0aaef26d651e0042baa6f2711ed4ff6c9a0a9c603`
- Current manifest: 76 файлов, SHA256 `895256c0a02086bfe331982cd0d2bfbc5cb79dfa3a30b97108e6b0b6a16a9c91`.

Полный перенос ещё не завершён. Оценка общей готовности — около **35%**, это
оценка объёма реализации, а не процент пройденных проверок. Резервный перенос,
эквалайзер/crossfade, Flow/рекомендации, smart playlists, auth, Spotify/Яндекс,
полный каскад текстов и отчёты — следующие этапы: `PORTING_STATUS.md`.
