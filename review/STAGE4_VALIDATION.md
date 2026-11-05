# Android: этап 4 — 2026-10-18

- **Исходники после APK 0.2.0. Новый APK НЕ собирался**, по указанию пользователя.
- Команда: `./android/verify.ps1 -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:compileDebugAndroidTestKotlin')`.
- BUILD SUCCESSFUL: Kotlin/Room KSP, JVM, lint, compile Android tests. `STAGE4_CHECKS.log`.
- **76 JVM passed, 1 skipped (opt-in HTTP probe), 0 failures/errors**. Сеть провайдеров повторно не проверялась. Локальные mock-server tests не обращаются к LRCLIB в интернете.
- **Lint: 0 errors, 14 warnings**: {'AndroidGradlePluginVersion': 1, 'ExportedService': 1, 'GradleDependency': 4, 'NewerVersionAvailable': 8}. Не подавлены. ExportedService разобран в REVIEW_RESOLUTION.md.
- **36 instrumentation tests скомпилированы, НЕ выполнены**. Включены 3 новых DAO отчёта, 4 browse, 9 profile/player, 2 appearance; остальные относятся к стадиям 1–3.
- 10 новых JVM проверок: 7/30 локальных календарных дней, leap day, DST23/25h, разные зоны, zero-fill, ALL365/full totals, нулевая база сравнения, future/zero/negative history, одинаковые названия и разные ID.
- 6 новых JVM проверок текстов: fractions/multiple tags/dedup, malformed/overflow/bounds, null/instrumental, TTL/key metadata, HTTP404 negative cache, HTTP503 not cached, force refresh после отсутствующего текста.
- 3 новых JVM проверки: имя/описание с кириллицей и валидация, история запросов recent-first/case-insensitive/limit20, повреждённый JSON/неверные значения.
- Первая компиляция нашла Alignment.BottomEnd() вместо свойства. Автор исправил; итоговая компиляция успешна. Дополнительно интегрирован принудительный повтор lyrics в обход отрицательного кэша, затем тесты повторены.
- Предупреждения компилятора: AutoMirrored для двух иконок; Compose test rules рекомендуют v2. Новые API тестовых правил пока не мигрированы.
- **Не проверены на Android**: Room-запросы и reactive checkpoints, SAF/фото/сохранение URI после перезапуска, тема/акценты/фон, IME/TalkBack/200%font, график, blur, lyrics auto-scroll/manual-follow, offline cache, фактическая компоновка и звук. Тесты этих компонентов подготовлены и только скомпилированы. Скриншотов нового UI нет.
- Native ExoPlayer/MediaSessionService не пересоздаётся UI-переключателями. Непрерывность воспроизведения при настройках требует runtime-проверки; компиляция этого не доказывает.
- Измеренные сессии группируются по дате начала. Нет выдуманного распределения пауз/переходов через полночь. ALL total — вся история, SQL daily <=365 дней, UI chart последние30. История <=100, top <=10 (UI показывает5). Схема Room осталась v2.
- Профиль локальный (не логин). Поиск ограничен20 запросами. Кэш текстов только в памяти, до20 записей, 24ч для найденного/10мин для отсутствия; общая библиотечная JSON-копия не включает персонализацию.
- Три субагента писали код отчётов, browse и profile/player. Основной агент писал settings/preferences/lyrics/integration. Это **не независимое ревью**. Внешние отчёты относятся к baseline b8c5f20e…ceb4b12.
- Ни debug APK, ни instrumentation APK не изменены: проверены SHA256, размер и mtime_ns. `STAGE4_CHECKS.json`. Старые APK не содержат изменений стадий3backup/4. local.properties также сохранился побайтово.
- Предыдущие результаты: `BACKUP_VALIDATION.md` (библиотечный перенос), `DOWNLOADS_VALIDATION.md`/`BUILD.log` (исторический APK/downloads). Они не считаются новой сборкой.
- Текущий source manifest: 113 файлов, SHA256 `aeffd92eec3fa6093326507e437400649344ece1999eb9d8b7f130e4585ea6ef`. Проверка: `./android/review/VERIFY.ps1`.

Этап4 реализован в исходниках; приёмка на Android ещё впереди. Общая готовность
оценивается примерно в **55%**, осталось около **45%** по функциям, а не по доле
подтверждённых runtime-сценариев. Следующие: Flow/recommendations/smart playlists,
EQ/crossfade, авторизация/провайдеры, полный каскад текстов, desktop import и
системные проверки. Подробности — `PORTING_STATUS.md`.
