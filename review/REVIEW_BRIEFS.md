# REVIEW BRIEFS — independent review and arbitration, per area

**Purpose.** This file is the shared brief for the **two independent reviewers** and the
**final arbiter** of every area of the autonomous repair. Read your area's section; the sections
are self-contained.

**Authority.** `docs/audits/independent_verification/` is the ground truth for *which defects are
real*:
* `VERIFICATION_MATRIX.csv` — per-finding verdict, revised severity, confidence, evidence type, and the
  corrections to the original audit. **233 rows, no duplicate ids.**
* `VERIFIED_FINDINGS_PART1.md` (P0/P1 and the pending-selection gate), `PART2.md` (REG P2-17…P2-75 and
  P3-01…P3-55), `PART3.md` (P3-56…P3-110) — the per-finding record.
* `FALSE_POSITIVES.md` — **what is refuted and must NOT be "fixed"** (FP-1…FP-23) and what is
  materially overstated.
* `ROOT_CAUSE_MAP.md` — RC-1…RC-17, how the findings collapse into independent root causes.
* `RECOMMENDED_FIX_ORDER.md` — the recommended action per wave (W0…W5) and §7, the claims that must
  **not** be implemented as written.
* `AUDIT_INCONSISTENCIES.md` — the original audit's own bookkeeping errors (56 of them).
* `docs/REMEDIATION_TRACKER.md` — the working register: the area-status table, the registry of
  confirmed defects, the refuted list, the conditionals list, and the per-area logs.

**Never** treat `docs/audits/FULL_PROJECT_AUDIT.md` or `docs/audits/ANDROID_FINDINGS_REGISTER.md` as
authoritative: they contain refuted claims, two conflicting P2 id namespaces, and stale counts.

## The two reviewer roles

* **Reviewer 2 — adversarial.** Hunt for what is wrong with the repaired code: logic inversions, race
  conditions, deadlocks, exception handling, Android lifecycle, regressions, data loss, security,
  performance, and whether every defect of the area is really gone. **Read-only.**
* **Reviewer 3 — independent verification.** Re-derive the current behaviour from the source, check
  each contract item, look for functional regressions, check the new tests (would they fail on a
  revert?), check the interaction with the neighbouring modules, and try to reproduce the remaining
  defects. **Read-only.**
* The two reviewers must not see each other's output. The **arbiter** receives both.

## Rules that apply to every area

1. **Read-only for reviewers.** Do not create, edit or delete any file in the repository. You may read
   everything and you may run the test suite.
2. **Build/test** from the repo root:
   `powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize`
   The script writes the Gradle output to a **unique** log file under `C:\Temp\nedotify-B3544D227D\`
   and prints its tail plus the `TEST_SUMMARY` line. **Do not pipe the script's output through
   `Select-Object`** — it can hang on a Gradle daemon that inherits the stdout handle; redirect to a
   file instead. **Concurrent Gradle runs corrupt the shared `app/build/test-results` directory** — if
   the summary looks impossible, re-run before believing it.
3. Allowed Gradle tasks: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileDebugKotlin`,
   `:app:compileDebugAndroidTestKotlin`. **APK packaging (`assemble*`, `bundle*`, `package*`) is
   FORBIDDEN by `android/AGENTS.md`** — never run it, and never claim a device result.
4. `android/app/build.gradle:43` sets `unitTests.returnDefaultValues = false` — a JVM test must not
   rely on an unmocked Android API.
5. Do not weaken, delete or disable a test to get a green build; do not replace a real check with an
   always-passing mock; do not swallow exceptions without handling.
6. **Never open, read, write or clear `~/.nedotify/`** and never touch a real user database, a real
   token or a real Keystore.
7. `git diff` will **not** show the Android changes — the `android/` tree is untracked in git. Verify
   against the source, never against a diff.
8. Every claim needs a `file:line` citation. Mark each finding **CONFIRMED** or **HYPOTHESIS**. Do not
   report the same issue twice. Rank by severity.
9. Minimum API 26 must keep working. Native Kotlin / Compose / Media3 1.11.1 / Room 2.8.5 / OkHttp 4.12.0.
10. The current baseline is **543 JVM tests, 0 failures, 0 errors, 0 skipped**; lint is
    **0 errors, 17 warnings, 1 hint**; `:app:compileDebugAndroidTestKotlin` is green. The 66
    instrumented `@Test` methods **compile but have never been executed** (no device, no emulator, APK
    builds forbidden) — every finding that depends on on-device behaviour is **CANNOT VERIFY**, not
    "confirmed".

---

## A3 — Settings, preferences and profile state

**Files.** `data/PreferencesLayer.kt` (new), `data/Preferences.kt`, `data/AudioSettingsStore.kt`,
`data/ProfileSettings.kt`, `data/SmartRules.kt`, `ui/SettingsScreen.kt`, `ui/AudioSettingsControls.kt`,
`ui/ProfileComponents.kt`, and their tests.
**Area log.** `docs/REMEDIATION_TRACKER.md` → "Area A3 - Settings, preferences and profile state".

**Contract.**
* **P1-02** — a corrupt preferences file was never repaired: the read-side
  `catch { if (it is IOException) emit(emptyPreferences()) }` masked the corruption while the **write**
  path re-read and re-threw the cached `CorruptionException` forever (bytecode: `DataStoreFactory.create`
  substitutes a `NoOpCorruptionHandler`; `DataStoreImpl$handleUpdate$2$1` re-throws the `ReadException`).
  Runtime-confirmed: 3/3 edits threw, the file stayed byte-identical, a fresh store over the same file
  failed to read **and** to write. Required: an explicit `ReplaceFileCorruptionHandler { emptyPreferences() }`
  for **both** delegates and an **announced** reset; and the queue snapshot (the app's only durable
  session state) must **not** be silently destroyed by a settings corruption — it now lives in
  `nedotify_queue.preferences_pb` with a one-time migration out of the settings file.
* **P2-46 / A-6** — 14 cold reads (10 + 3 + 1), 13 independent subscriptions, 19 read points; two
  independent DataStore files; only `AudioSettingsStore` had an injection seam. Required: a seam for
  `Preferences` and a reduction of the independent subscriptions. (A cold `DataStore.first()` is a
  *collection of a shared hot flow*, not a file read — the original cost model is wrong even though the
  structure is right.)
* **P3-60** a failed write leaves the optimistic draft on screen · **P3-61** the dialog closes before
  the write and the UI never filters the control characters the model rejects · **P3-62** a 750 ms
  progress write re-runs the whole settings scope · **P3-59** a slow first emission silently falls back
  to 256 MB and a `runBlocking` blocks the main thread · **P3-35 / P3-64** the avatar-remove branch is
  unreachable and `AsyncImage` has no error painter.
* **Refuted / not to be "fixed":** P3-03 (the message-channel conflation is bounded by the snackbar
  display time — "never shown" is wrong); P3-87 (a false positive); P3-84 (a recommendation); the
  "an interrupted write corrupts the file" story (DataStore writes are atomic); the "12 cold reads"
  figure (it is 14).

**Watch for.** The one-time queue migration: is it idempotent, atomic, and does it survive a kill
between the read and the write? Does an older install (queue under `queue_v2` in `nedotify.preferences_pb`)
still restore? Does the reset announcement survive a restart, and can it be acknowledged without ever
being shown? Is the "one shared snapshot" consistent with what is on disk under two concurrent writes?
Is `DataStore` ever created twice over one file (DataStore forbids it)?

---

## A4 — Library, Room, backup codec, desktop import/export

**Files.** `data/Models.kt`, `data/BackupCodec.kt`, `data/BackupMerge.kt`, `data/DesktopLibraryImport.kt`,
`data/DesktopLibraryConverter.kt`, `data/DesktopWalValidator.kt`, `data/LibraryBackupStorage.kt`,
`data/LibraryWrites.kt`, `data/LibraryRepository.kt`, `data/LocalImport*.kt`, `ui/BackupControls.kt`,
`ui/DesktopImportControls.kt`, `android/app/schemas/**`.
**Area report.** `android/review/REMEDIATION_A4_FIX.md`.

**Contract.**
* **REG P2-16 (P1)** — a routine "import backup" needed **183 MB** of live heap (the org.json DOM alone
  86.2 MB) and `OutOfMemoryError` (an `Error`) escaped every `catch(Exception)` and killed the process.
  Required: a bounded heap cost and `OutOfMemoryError` converted into a `SourceFailure`. The codec is
  now a **streaming** reader/writer — no DOM.
* **REG P2-15** the encoder's and the decoder's error prefixes differ; the 20 000-track cap must be an
  explicit product limit · **REG P2-17** one unusable rule row must not abort the whole import (the
  desktop skips the row) · **REG P2-18** the lost columns must be **visible** · **REG P2-19** the
  export must not fail for a realistic library (the register's own 10 k/40 k figure does **not** trip
  the cap) · **REG P2-20 / P2-26 / P2-14** the cross-platform identity: a machine-specific library id,
  a conflict check that compares **metadata** (not only the source) · **REG P2-21 / P2-25** the WAL
  sidecar must be read (a copy of the `.db` alone passes `quick_check` yet is missing the newest track
  **and the whole history**) · **REG P2-23 / P2-24** pagination and a truthful playability report ·
  **REG P2-31** a delete path for the library · **REG P2-44 / P2-45** the report grouping and the four
  lost import dimensions (`downloaded` is the worst: an imported "downloaded: true" playlist is
  permanently empty) · **REG P2-43** the two comparison windows must have the same length (the app
  reported **-14.3 %** at 00:00) · **MAIN-P2-35** a retention policy for `history` · **P3-15 / P3-16 /
  P3-71…P3-74** the quoted identifiers, the batched import, the swallowed `require`, the picker's MIME
  array, the materialised preview, the failure taxonomy.
* **Room v4** with explicit migrations; **REG P2-53 (P1)** the device tests have never run and are the
  only guard on the migration — APK builds are forbidden, so the device suite **cannot** be executed:
  record that as a blocker, do not claim it.
* **Honest limitations that must not be papered over:** an old desktop copy **without** the persistent
  UUID cannot prove which PC produced it; records with ambiguous metadata cannot be safely merged
  automatically (they must be disclosed, not merged).
* **Watch for.** The fence: a checkpoint and a delete of the same track must not interleave so that the
  row comes back. `data/LibraryWrites.kt` holds the fence; `data/Models.kt`'s `rememberSession` and
  `deleteTrack` are single `@Transaction`s.

---

## A5 — Providers, OAuth / auth, secure credential store

**Files.** `data/ProviderConnections.kt`, `data/ProviderAuth.kt`, `data/ProviderPayload.kt`,
`data/AuthPkce.kt`, `data/SecureProviderStore.kt`, `ui/ConnectionsControls.kt`.
**Area log.** `docs/REMEDIATION_TRACKER.md` → "Area A5 - Providers, OAuth / auth, secure credential store".

**Contract.**
* **P1-03** — a single Yandex 401 destroyed the user's credential. Required: on a 401, **re-validate**
  against `account/status` **first**; delete only if the re-validation also fails; the order is
  re-validate → then remove; `yandexScope()` rotates whenever the credential really is dropped; the
  "disconnected" state reflects the failure immediately. **Corrections to honour:** the delete **is**
  guarded (generation + token equality) — "unconditionally" is wrong; a recovery path **does** exist
  (the "Подключить" flow) — "no recovery path" is wrong; the "retry once" in the original audit
  describes the **Spotify** branch — Yandex never retried, and a retry is **not** the fix.
* **REG P2-03** the pending login is removed only **after** the token is stored, and the exchange must
  survive the Activity's teardown · **REG P2-05** no mutex held across HTTP; a single-flight instead; a
  refresh token is spent exactly once · **P3-66 / MAIN-P2-42** a second PKCE start must not invalidate
  the first pending login · **P3-67** `cancelSpotifyLogin()` must have a caller · **P3-68** the confirm
  button must not clear the field before the save completes · **P3-69** a `liveRegion` ·
  **P3-71** the reachable `require` must not be swallowed · **P3-17** the error must name the credential ·
  **P3-18** the cipher fully guarded, a broken alias replaced, no raw crypto exception to the UI ·
  **P3-20** the Spotify import must keep the catalogue metadata · **P3-21** a `maxBytes` seam ·
  **P3-70** the `password()` semantics must be used (the "reads the token aloud" impact is OVERSTATED) ·
  **REG P2-04** the MediaSession trust predicate (already satisfied in `player/PlaybackService.kt`) ·
  **P3-19** no `CertificatePinner` — a recorded decision, not to be implemented.
* **Watch for.** Can the credential be destroyed by a 401 whose re-validation **succeeds**, or by an
  **inconclusive** re-validation, or by a **stale** 401? Can a foreign OAuth callback consume a login
  or a token? Can the same `code` be spent twice? Is a secret bound to its own name?

---

## A6 — Lyrics, search, discovery, catalogue import

**Files.** `data/LyricsCascade.kt`, `data/LyricsClient.kt`, `data/LyricsData.kt`, `data/LyricsDiskCache.kt`,
`data/Discovery*.kt`, `data/CatalogMatcher.kt`, `data/CatalogProviders.kt`, `data/CatalogImportController.kt`,
`data/SearchAggregate.kt`, `ui/CatalogBrowser.kt`.
**Area report.** `android/review/REMEDIATION_A6_FIX.md`.

**Contract.**
* **P1-01 (P2)** — the lyrics identity gate was stricter than the desktop oracle in four dimensions:
  the **artist-conditioned prefix rule** (artist-conditioned, *not* unconditional — "AC/DC - Back in
  Black" and "Song - Part 2" must keep their titles), the artist relation must be an **intersection**
  (not a superset), the version relation needs a **base-recording fallback** (the desktop accepts only
  remix/slowed/sped), and there must be a **failure channel** (a 404 primary plus a failed cascade
  produced no exception, so an outage looked like "no lyrics").
* **REG P2-69 / P2-70** — one **global deadline** passed down, the providers **concurrently** (the
  serial budget was 3500 + 4×2200 = **12 300 ms > 12 000 ms**) · **MAIN-P2-28 / P3-79** an oversized
  primary payload must not abort the cascade · **REG P2-67** `forceRefresh` must not overwrite a
  24-hour entry with a 10-minute empty one · **P3-29 / P3-78** the result must carry its **source** ·
  **P3-80 / P3-81** the disk cache must be versioned and must not live in the purgeable `cacheDir` ·
  **REG P2-08 / P2-74 / MAIN-P2-40** the catalogue import must survive the screen closing, with
  persisted progress, bounded concurrency, pause and resume, and a cancel affordance (the cap is **500
  items**, not 1000; the serial worst case was 500 × 20 s = **2 h 46 min 40 s**) · **REG P2-58 / P2-60 /
  P2-61 / P2-62** the provider bodies must run on `Dispatchers.IO` and the error classification must
  survive (`httpCode`) · **REG P2-73** one JSON field reader that distinguishes a **missing** key from a
  **JSON null** (`optString(name)` returns `"null"` for a JSON null on AOSP; `isNull` is also true for a
  missing key, so the defaults must stay) · **P3-20 / P3-27** the matcher must not commit to the first
  candidate · **REG P2-59 / P1-05** the local search needs its own budget and an `addedAt` index.
* **Refuted / not to be "fixed":** "the lyrics are **never** found for 'Artist - Song' tracks" — a live
  LRCLIB probe shows `/api/get` **echoes** the prefixed title, so the lyrics *are* found; the rule was
  absent but the trigger is narrow (non-Latin scripts, the sentinel artist). **Do not loosen the
  similarity threshold** — that is the false-match protection. "The catalogue browser silently ignores
  every input" — it disables the controls and shows a live counter.

---

## A7 — Compose UI, navigation, theming, accessibility, library UI

**Files.** `ui/**` except `ui/CatalogBrowser.kt` (A6), `ui/ConnectionsControls.kt` (A5),
`ui/SettingsScreen.kt`, `ui/AudioSettingsControls.kt`, `ui/ProfileComponents.kt` (A3); `MainActivity.kt`;
`ui/UiPolicies.kt`, `ui/PlaybackCommands.kt`, `ui/PlayerErrorState.kt`, `ui/NowPlayingQueue.kt` (new).
**Area report.** `android/review/REMEDIATION_A7_FIX.md`.

**Contract.** **MAIN-P2-37** a real back stack · **REG P2-37** `rememberSaveable` state must survive a
tab switch · **MAIN-P2-40** the catalogue progress flow must not re-compose the whole tree ·
**MAIN-P2-41** `positionMs` must be read only by a leaf · **REG P2-34 / P2-35** the mirror must not
allocate a pair per element and the queue row must show what the desktop shows · **P3-46** no O(N) scan
at 2 Hz · **P3-103 / P3-104** the error dimension must be **rendered** · **P3-47…P3-50** the per-item
state, the per-tick `indexOfLast`, the lyrics spinner **flash** (the lyrics are restored from cache —
they are not discarded), the scrub cleared in the same frame as the seek · **P3-52 / P3-53** a clear
action for the queue and a header that does not outrun the rows · **P3-36 / P3-38** `remember` vs
`rememberSaveable` · **P3-41 / P3-45** no clock sampling during composition and a `values-night`
override · **P3-42 / P3-43** exactly **2** auto-mirrored icons (`Sort` has none) and no
`contentDescription` that repeats the tab label · **P3-39 / P3-37** the nested glass and the 22 dp blur
(the "18 surfaces" and "4 rows" figures are **not reproducible**) · **P3-57 / P3-58** a `hasSearched`
flag and a truthful EQ clamp · **P3-01 / P3-02** the URI grants and the permission rationale /
already-asked flag / route to the app settings · **P3-13 / MAIN-P2-44, P3-25 / MAIN-P2-43** the data
side (A4) · **REG P2-65 (UI side)** the transport/queue commands must not drop the pending
preparation (`shuffle()` and `repeat()` are the reference behaviour) · **P3-08 / P3-09** the download
re-materialisation and the gain step.
* **Refuted / not to be "fixed":** P3-42's count (2, not 3) · P3-39's "18 surfaces" · P3-50's
  "the lyrics are discarded" · P3-02's "silently does nothing" (a message **is** shown).
* **Device-only, never claimable:** TalkBack, large font, RTL, the system file pickers, the real seek
  behaviour, the frame cost of the queue and the glass, the MediaSession round trip, the SAF grant
  lifecycle, the OAuth destination, the theme before the first frame. Compose state preservation is only
  **partially** JVM-testable.

---

## A8 — Build system, test infrastructure, review manifests, documentation

**Files.** `android/build.gradle`, `android/settings.gradle`, `android/gradle.properties`,
`android/gradle/libs.versions.toml`, `android/gradle/verification-metadata.xml`, `android/verify.ps1`,
`android/tools/**`, `android/review/**`, `android/README.md`, `android/PORTING_STATUS.md`,
`android/lint.xml`, `.github/workflows/android-source.yml`, `docs/REMEDIATION_TRACKER.md`.
**Area report.** `android/review/REMEDIATION_A8_FIX.md`.

**Contract.** A shared version catalog; a dependency lockfile with checksums; a snapshot check that
reports new/changed/removed files; a ban on APK/device tasks in the verification scripts; the Gradle
output saved to a log; an Android CI configuration; a test-summary generator. Plus:
* **REG P2-77** the lint gate must not be a function of the network (the audit measured 15, 13 and 4
  warnings for the **same** source) — the "newer version available" detector must not make the gate
  non-reproducible; a **separate non-blocking** "newer version available" task is the prescribed answer.
* **REG P2-76** a `release` build type with R8, a `proguard-rules.pro`, a `signingConfig` from
  environment properties and an `applicationIdSuffix` for `debug` (today a debug build silently
  replaces a release build — both are `ru.nedotify.app`). **Verify it without building an APK** (the
  effective AGP model / `:app:signingReport`), because APK builds are forbidden.
* **REG P2-79** the SDK must live outside the system temp dir and the build must not depend on a
  globally shared `local.properties`.
* **REG P2-72** the verification script must not abort when its output is captured (it used to execute
  **zero** Gradle tasks in every CI/agent context).
* **REG P2-38 / P2-55 / P2-42** the review manifest must be regenerated from a **directory walk**, must
  include `app/build.gradle`, and the check must fail on any *unlisted* file — and the manifest must not
  embed a machine-local absolute path.
* **P3-83 / P3-92** `VALIDATION.md` and `TEST_SUMMARY.json` must be regenerated from the real XML (the
  old artefacts said 217/17/55; the real figures are 543 tests, lint 0/17/1, 66 instrumented `@Test`
  methods **never executed**).
* **P3-88 / P3-89** the APK ban must be recorded consistently and the verification entry point must not
  dead-end.
* **Watch for.** Does the dependency verification still reject untrusted artifacts (the lint toolchain
  was missing and had to be regenerated)? Is `local.properties` (gitignored, machine-local) excluded
  from lint in a way that survives a fresh checkout? Does the CI job need a local SDK path? Does the
  snapshot detect a removed file? Is the manifest current **at the end of the whole job**?

---

## A9 — The Windows desktop's destructive duplicate-cleanup migration (data safety)

**Files.** `core/database.py` (the dup-cleanup migration and the corrective migration),
`tests/test_dup_cleanup_migration.py`.
**Area log.** `docs/REMEDIATION_TRACKER.md` → "Area A9 - Windows desktop destructive dup-cleanup migration".

**Contract.** **P0-01 (revised P1)** — the only data-loss defect in the project. The old one-shot
startup migration deleted tracks, history rows and playlist entries, silently and non-atomically, and
could remove the playlist entry of a track that **survived**. Required:
1. **merge** rather than delete — never orphan a `history` row; carry `play_count`, `last_played`,
   `is_favorite`, `file_path`, `cover_url`, `source_id`, `duration`;
2. **snapshot** before anything destructive and keep it;
3. **atomic**, with the flag written only after a successful commit;
4. **fix or drop** the statement that can remove a surviving track's playlist entry;
5. **log at warning/error** and name what was touched;
6. a **corrective migration** that restores from the backup where one exists, stating plainly that for
   users who already ran the destructive migration the history is unrecoverable;
7. **idempotent**, and it must not resurrect tracks the user deleted afterwards;
8. `add_track` and the migration must share one identity definition.
**Two refutations to honour:** the identity key `(LOWER(title), LOWER(artist))` **is** the app's own
identity (`core/database.py:529-544`, introduced in the same commit `b6a6553`) — do not "fix" the key;
and the FK cascade is **not** the cause of the playlist loss — do not "fix" it there.
**Acceptance.** A synthetic DB with same-title/same-artist rows differing by album, source, file_path,
case and NULL artist comes out with **no distinct recording deleted**, **every surviving track still in
its playlists**, **every history row still present**, and **a log line naming what was touched**.
**Watch for.** SQLite's `LOWER()` is **ASCII-only** — a Cyrillic case difference is not folded, so the
identity is effectively case-sensitive for non-ASCII titles. The merge's survivor choice must be
deterministic; `play_count` must be **summed**, not overwritten. A group with three or more duplicates,
a `history` row referencing a merged track, a `download_queue` row, a `PRAGMA foreign_keys=OFF`
database, a read-only volume, and a mid-block failure are all edge cases to check.
**Rules.** Python interpreter: `& ".venv_win\Scripts\python.exe"` (bare `python` lacks the deps). Run
from the repo root: `& ".venv_win\Scripts\python.exe" -m pytest tests -q`. **Never** open `~/.nedotify/`
— every check must use a synthetic throwaway database. Do not start the desktop application.
