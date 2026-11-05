# Android: итоговый source-check — 2026-10-18

- **BUILD SUCCESSFUL**: Kotlin/Room KSP, JVM, lint, compile Android tests.
- Команда: ./android/verify.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin').
- **173 JVM passed, 1 skipped (opt-in live HTTP probe), 0 failures/errors**.
- **Lint: 0 errors, 16 warnings**: {'AndroidGradlePluginVersion': 1, 'ExportedService': 1, 'GradleDependency': 4, 'NewerVersionAvailable': 8, 'UseKtx': 2}. Не подавлены.
- **48 instrumentation tests скомпилированы, НЕ выполнены**.
- APK не собирался. SHA256, размер и mtime_ns обоих прежних APK неизменны;
  local.properties восстановлен побайтово. Доказательства: FINAL_CHECKS.json/log.
- Живые входы/аккаунты и сеть провайдеров в этом проходе не проверялись.
  Mock HTTP-тесты локальные; skipped probe не доказывает внешнюю доступность.

## Новый охват после этапа 4

Flow/recommendations/ranking и offline, strict smart rules/reactive recovery,
Room v3 migration, JSON v2/v1/smart merge; desktop converter и измеренная история;
audio settings/quality, focus intent, equal-power fade и boundary recovery;
PKCE/state/expiry/replay, cold-start pending, refresh/401/cancel, metadata matcher,
bounded responses/JSON, URL origin/CDN protection, Unicode/token validation;
lyrics cascade/identity/version/duration, дисковый кэш/TTL; combined search,
partial success/timeout/parent cancellation, artist/album groups; queue selection.

Общая компиляция обнаружила и позволила исправить лишнюю скобку AuthPkce,
числовые типы DiscoveryEngine, кириллическую нормализацию lyrics и поглощение
отмены в ProviderHttp.json. Дополнительно исправлены clear queue, stale catalog
finally, rapid Flow, ENDED play и попадание выбранного трека вне исходной очереди.
Итоговый прогон выполнен после всех этих изменений.

## Границы доказательства

Нет запуска Android, настоящего звука, визуального сравнения с референсом,
SAF/Room migration/Keystore/foreground/audio focus/noisy/BT runtime-проверок.
Device-тесты этих сценариев только скомпилированы. Скриншотов нового приложения нет.
Три субагента писали код и проверяли собственную реализацию; это не независимое
ревью. Внешние два отчёта относятся к старому baseline из 39 файлов.

Сохранённый APK 0.2.0 устарел. Приёмка текущего кода требует новой разрешённой
сборки и DEVICE_ACCEPTANCE.md. Полная готовность не объявляется 100%.
Функциональные ограничения перечислены в ../PORTING_STATUS.md.

Снимок всех источников/ресурсов/проверок: SOURCE_SHA256.txt.
Его checksum находится в SNAPSHOT.json; проверка: ./android/review/VERIFY.ps1.
Исторические результаты этапа 4 сохранены как STAGE4_VALIDATION.md,
STAGE4_SOURCE_SHA256.txt и STAGE4_TEST_SUMMARY.json.
