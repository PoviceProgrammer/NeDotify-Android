# Android: проверка исправлений — 2026-10-19

- **BUILD SUCCESSFUL**: Kotlin/KSP, unit, lint, compile Android tests.
- Команда: ./android/verify.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin', '--max-workers=2').
- **217 JVM passed, 1 skipped (opt-in live HTTP probe), 0 failures/errors**.
  После предыдущего снимка добавлено 44 JVM-регрессий, включая локальные
  HTTP fixtures, контролируемые coroutine races и настоящий файловый DataStore.
- **Lint: 0 errors, 17 warnings**: {'AndroidGradlePluginVersion': 1, 'ExportedService': 1, 'GradleDependency': 5, 'NewerVersionAvailable': 8, 'UseKtx': 2}. Не подавлены.
- **55 instrumentation tests скомпилированы, НЕ выполнены**;
  новых тестов: 7. Room/Keystore/Compose/HLS runtime не подтверждены.
- APK не собирался. Хэши/размеры/mtime_ns обоих APK неизменны. local.properties
  восстановлен побайтово. Доказательства: BUGFIX_CHECKS.json и BUGFIX_CHECKS.log.
- Реальная сеть провайдеров и живые аккаунты не проверялись. Сетевой probe
  пропущен; тесты MockWebServer не доказывают доступность Spotify/Яндекса/YouTube.

## Исправленный охват

Сценарии и конкретные тесты: [BUGFIX_2026-10-19.md](BUGFIX_2026-10-19.md).
Autoplay/async source mutation/pause/noisy, shuffle append, queued offline assets,
history source boundaries, отмена подготовки UI-очереди; account leases/401/
pagination/scoped stream cache, matcher titles, AtomicFile recovery; сохранение
URI/тегов, history ID conflicts, backup roundtrip, bounded HLS/desktop parsing,
Unicode/null metadata, audio setting concurrency, deadline cancellation,
partial search failures и power-saver fallback.

Первая компиляция обнаружила generic nullability в queue source helper и
неявный CoroutineScope receiver у Flow task; исправлены. Дополнительная правка
локальных тегов потребовала повторной компиляции main/test на одном снимке.
Main исходники были принудительно скомпилированы с --rerun-tasks. В этом проходе
обнаружилась несовместимость Android FileStorage с JVM-заглушкой SDK_INT=0 на
Windows: File.renameTo не заменял существующий файл. Два теста теперь используют
настоящий JVM OkioStorage + PreferencesSerializer и сохраняют все проверки
concurrency/повторного открытия. Добавлена только testImplementation зависимость;
хранилище приложения на Android не заменялось. Предыдущий лог сохранён как
BUGFIX_FORCED_CHECKS.log; он содержит эти исправленные тестовые сбои.
Итоговые результаты относятся к актуальным исходникам после этих исправлений.

## Ограничения

Нет запуска Android, звука, двух реальных декодеров, focus/noisy/BT/FGS,
SAF/grants/Keystore, visual QA и скриншотов. Device-тесты только скомпилированы.
Оценки P1/P2 в отчёте основаны на коде/контролируемых регрессиях, а не на
утверждении о выполнении этих сценариев на устройстве.

Три субагента проверяли другие области и исправляли код; основной агент
интегрировал и проверял VM/repository/UI. Это не два независимых полных ревью.
Старые внешние отчёты относятся к baseline из 39 файлов. Сохранённый APK 0.2.0
не содержит актуальных исходников; новый APK запрещён пользователем.

Предыдущий source-check сохранён как PRE_BUGFIX_VALIDATION.md,
PRE_BUGFIX_TEST_SUMMARY.json, PRE_BUGFIX_SOURCE_SHA256.txt и PRE_BUGFIX_SNAPSHOT.json.
Проверка текущего снимка: ./android/review/VERIFY.ps1. Checksum манифеста —
в SNAPSHOT.json. Ограничения полного переноса — ../PORTING_STATUS.md.
