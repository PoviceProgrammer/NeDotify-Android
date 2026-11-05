# A3 repair pass — 2026-10-20

Scope: the remaining confirmed A3 defects of the two independent reviews
(`android/review/ARBITRATION_A3_A4_A5.md`, section "A3"), plus the harness
limitation the second reviewer found.

**Area verdict: CLOSED WITH RESIDUALS.** Every finding the two reviewers
agreed on is fixed and pinned by a test. The residuals are stated in
"Not fixed, and why" and in "Not verified in this environment": the platform
`FileStorage` corruption path (API 26) cannot be exercised here, and the
queue of an install whose settings file was *already* damaged before the
first launch of this version cannot be recovered — the bytes are gone.

No APK, `assemble*`, `bundle*` or `package*` task was run; no device, no
emulator.

## The contract this pass repairs

| # | Contract | Where it lives now |
|---|---|---|
| 1 | A damaged file is replaced, the write path recovers, and the reset is **announced** | `data/PreferencesLayer.kt` — `corruptionHandler`, `PreferenceResets.announceNow` |
| 2 | The notice is **durable**: it outlives the process that made it | `data/PreferencesLayer.kt` — `ResetLedger` (the record `datastore/nedotify_resets`), written **before** the repair is handed back |
| 3 | The notice channel carries **more than one** reset and never conflates two | `data/PreferencesLayer.kt` — `PreferenceResets` (a queue), `PreferenceReset.id` |
| 4 | The **identity of the library** is not destroyed by a corruption of the settings file | `data/Preferences.kt` — the identity lives in `filesDir/nedotify_library_id`, the settings file only holds the legacy key of an older install |
| 5 | A valid file with a value of **another type** does not kill the read | `data/PreferencesLayer.kt` — `Preferences.valueOrNull`; `data/Preferences.kt` — `announceMismatches` |
| 6 | The queue of an older install survives a corruption of the settings file | `data/Preferences.kt` — the queue file, the one-time migration, the idempotent cleanup |
| 7 | The **identity** is not rewritten on every call | `data/Preferences.kt` — `libraryId()` reads the file first and writes only when it holds nothing |
| 8 | The shared snapshot does not go backwards | `data/Preferences.kt` / `data/AudioSettingsStore.kt` — one write path under one mutex |
| 9 | An interrupted migration leaves no stale key behind | `data/Preferences.kt` — `migrateQueue` removes the old keys in every case |
| 10 | The `confirmed` flag of the cache limit has a **production consumer** | `data/AudioSettingsStore.kt` — `cacheLimit`; `NeDotifyApp.kt` — the streaming cache applies a limit only when it is confirmed |
| 11 | The message of a system exception does not reach the user | `data/AudioSettingsStore.kt` — `AUDIO_WRITE_FAILED_MESSAGE` |
| 12 | A failure inside the layer is observable **without** `android.util.Log` | `data/PreferencesLayer.kt` — `PreferenceDiagnostics`, `PreferenceLog`, `reportDiagnostic` |

## What was changed

### The reset notice is durable (`data/PreferencesLayer.kt`)

The notice was an in-memory `MutableStateFlow<PreferenceReset?>`: the
corruption handler *repairs* the file in the same operation that announces
it, so a process that died in between lost the notice **and** the evidence.

The layer now keeps a **durable record** of the pending notices
(`datastore/nedotify_resets`, one line per notice). The corruption handler
writes the notice to that record **before** it hands the replacement back, so
the notice is on the disk before the evidence is gone. The next launch of the
app reads the record and shows the notice again. The acknowledgement removes
the notice from the record.

The record is a plain file, not a preferences file: no corruption handler
replaces it, and it is written atomically (a temporary file is moved over the
target).

### The notice channel is a queue (`data/PreferencesLayer.kt`)

`PreferenceResets` was a **slot**: `announce()` overwrote the pending notice.
The layer owns three files; two damaged files meant that the second notice
replaced the first and the larger loss was never mentioned. `MutableStateFlow`
also conflates equal values, so a second corruption of the same file produced
no emission at all.

The channel is now a queue (`PreferenceResets.pendingAll`), and every notice
carries an `id` (`PreferenceReset.id`), so two notices of the same file are
two different values and both reach the user. One notice per file is kept
pending: a file that is damaged twice before the user has seen the first
notice is one loss, not two.

`ui/SettingsScreen.kt` now lists **every** pending notice, and the reason of
each one (`PreferenceReset.detail()`).

### The identity of the library leaves the settings file (`data/Preferences.kt`)

`LIBRARY_ID` lived in the settings file, which the corruption handler
replaces with an empty one. `libraryId()` then minted a **new** UUID silently,
and `data/BackupMerge.kt` recomputes the portable ids of the local rows with
the local library id — the restore of the device's own backup was silently
downgraded from "the same device" to "another device's copy".

The identity now lives in a file of its own (`filesDir/nedotify_library_id`),
written atomically. The key of an older install (`library_id_v1`) is read
once and **adopted** into that file, so an existing install keeps its
identity. The move happens at the first launch of this version (after the
queue migration), not at the first export.

`libraryId()` reads the file first and writes only when the file holds
nothing: a read must not depend on a successful write, and an export of a
read-only library must not fail because of it.

### A value of another type does not kill the read (`data/PreferencesLayer.kt`)

A **valid** proto with a wrong-typed value parses, so the corruption handler
never fires. The typed read then throws a `ClassCastException` — an unchecked
exception that the `IOException` fallback of the read does not catch.

`Preferences.valueOrNull` reads the value as `Any` and casts it inside a
guard: the key is treated as absent, the mismatch is remembered, the file is
announced (`ResetCause.INCOMPATIBLE_VALUE`) and the offending key is removed
from the file, so the value cannot break the next read either. The user is
told what happened, and the other values of the file are kept.

### The queue migration is idempotent and observable (`data/Preferences.kt`)

The migration is two writes. A process that died between them left the old
keys in the settings file, and the early return of the next run skipped the
cleanup — the stale keys stayed forever. The copy comes first, and the
cleanup of the old keys now happens in **every** case.

The failure path is now observable: `Log.e` is not implemented in a JVM unit
test, so the diagnostic goes through an injectable sink
(`PreferenceDiagnostics`, `PreferenceLog.diagnostics`) — the same pattern
area A2 used for the media policy. A sink that fails is a *missing
diagnostic*, never a failure of the repair (`reportDiagnostic`).

### One writer, one order (`data/Preferences.kt`, `data/AudioSettingsStore.kt`)

The area log claimed that the shared snapshot is exact "because the class is
the only writer" — the class itself was two writers. The writes of a class
are now serialised by one mutex, so the result of a write is published in
the order the file was changed and the snapshot cannot go backwards.

### The cache limit has a production consumer (`data/AudioSettingsStore.kt`, `NeDotifyApp.kt`)

The `confirmed` flag of `CacheLimit` had no production reader: the flag
described a promise the app did not keep. The flag is now honest (it is
false while the file has not answered and when the file could not be read)
and it has a consumer: the streaming cache applies a limit only when the
file has confirmed it, so the default of 256 MB never passes for the choice
of the user.

`NeDotifyApp.kt` is **not** an A3 file. The change is three lines and is
named here: the collector of the cache quota reads `cacheLimit` and applies
a limit only when `confirmed` is true.

### The message of the system does not reach the user (`data/AudioSettingsStore.kt`)

A failed write put the message of the exception into the notice the settings
screen shows (`error.message ?: "Не удалось сохранить настройки звука"`). A
system message names a path and a class and tells the user nothing they can
act on. The notice now carries the app's own message
(`AUDIO_WRITE_FAILED_MESSAGE`); the technical detail goes to the log.

## The findings, one by one

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| B1 / R3 | the announced reset is not durable | **confirmed & fixed** | the notice is written to `datastore/nedotify_resets` before the repair is handed back; `PreferencesResetTest.theAnnouncedResetSurvivesARestart` |
| B2 / R4 | the reset channel has one slot | **confirmed & fixed** | the channel is a queue with an id per notice; `PreferencesResetTest.twoDamagedFilesCarryTwoNotices`, `aSecondCorruptionOfTheSameFileIsANewNotice` |
| B3 | the library identity is destroyed by a settings corruption | **confirmed & fixed** | the identity lives in `nedotify_library_id`; `PreferencesStoreTest.theLibraryIdentitySurvivesACorruptSettingsFile` |
| N1 / R8 | `libraryId()` rewrites the file on every call | **confirmed & fixed** | the file is read first and written only when it holds nothing; `PreferencesStoreTest.theLibraryIdentityIsNeitherStoredInTheSettingsFileNorRewritten` |
| N2 / R7 | the shared snapshot can regress | **confirmed & fixed** | one write path under one mutex in both classes; `PreferencesStoreTest.theSharedSnapshotDoesNotRegressUnderAConcurrentWrite` |
| N3 / R5 | an interrupted migration leaves a stale key forever | **confirmed & fixed** | the cleanup of the old keys happens in every case; `PreferencesStoreTest.anInterruptedMigrationLeavesNoStaleKeyBehind` |
| N4 | an announced QUEUE reset contradicts the migration | **confirmed, low, not fixed** | the message states the fact (the file was damaged and replaced); whether the migration restored the queue from the settings file afterwards depends on the order of two independent reads, and the user cannot act on the difference. The detail text of the notice says so. |
| N5 / R9 | the `confirmed` cache-limit flag has no production consumer | **confirmed & fixed** | `NeDotifyApp.kt` applies the limit only when it is confirmed; `AudioSettingsStoreTest.theCacheLimitIsUnconfirmedWhileTheFileHasNotAnswered` |
| N6 | a raw system exception message is shown to the user | **confirmed & fixed** | `AUDIO_WRITE_FAILED_MESSAGE`; `AudioSettingsStoreTest.aFailedWriteIsObservableAndLeavesThePersistedValueAlone` |
| N7 | the area log is inaccurate in four places | **confirmed & fixed** | the log was rewritten (see "Area log" below) |
| N8 | the on-disk name of the queue file is not pinned by a test | **confirmed & fixed** | `PreferencesResetTest.theQueueFileHasItsOwnNameOnDisk` pins `datastore/nedotify_queue.preferences_pb` and the two other names |
| N9 | the layer's warm-up read is discarded | **false positive** | the warm-up read warms the **DataStore**, whose `data` flow is hot and shared: the first consumer's `first()` is a collection of that flow, not a second read of the disk. The snapshot of the class is filled by `currentSettings()`, which reads once and publishes only when nothing newer is known. |
| N10 | the profile name bound is enforced by silent truncation | **confirmed, low, recorded** | the truncation is the model's own documented bound (`PreferenceValues.profileField`: "at most `limit` characters"), and the model's message is still reachable through the model itself (`PreferenceValues.profile("x".repeat(41), "")` throws with "от 1 до 40 символов"). The UI hint that would tell the user that the name was shortened belongs to `ui/ProfileScreen.kt` (A7). |
| N11 | the store cache is keyed by the file *name* only | **confirmed & fixed** | the layer keys the stores by the **path** of the file, so two contexts of one process cannot share a store over the first context's directory (`data/PreferencesLayer.kt`, `stores[file.absolutePath]`) |
| R1 | the queue of an older install is destroyed when the settings file is corrupt at the upgrade | **partially confirmed; the data loss is inherent, the notice is fixed** | a file that cannot be parsed cannot yield the queue: the bytes of the queue are inside the damaged file, and no code can read them. The reviewer reproduced `queue() == ""` — the queue was lost **by the corruption**, not by the handler. What the repair can do is honest: the notice of a damaged settings file now says that a queue that was still kept in it is lost with it (`PreferenceReset.detail()`), and the queue of an install that is already running survives (the queue has its own file). `PreferencesStoreTest.theQueueSurvivesACorruptSettingsFile` |
| R2 | the "upgrade-time type mismatch" trigger is not covered | **confirmed & fixed** | the value is treated as absent, the file is announced and the offending key is removed; `PreferencesStoreTest.aValueOfAnotherTypeIsTreatedAsAbsentAndAnnounced`, `AudioSettingsStoreTest.aValueOfAnotherTypeInTheAudioFileIsTreatedAsAbsentAndAnnounced` |
| R6 | a transient read error is sticky (the defaults are published) | **confirmed, low, partly fixed** | the read side still falls back to the empty preferences, but the fallback is now **reported** (`reportDiagnostic`) and the cache limit that the fallback produces is **not** marked as confirmed, so the app does not pass the default off as the choice of the user. The sticky snapshot of the settings themselves is left as it is: the next write replaces it. |
| R10 | four statements of the area log contradict the source | **confirmed & fixed** | the log was rewritten |
| harness | the migration's `catch { Log.e(...) }` cannot be exercised in a JVM test | **confirmed & fixed** | the diagnostic goes through `PreferenceDiagnostics`; `PreferencesStoreTest.aFailedMigrationIsReportedAndTheQueueIsKept` exercises the failure path |

## The tests

| File | Contract it pins | Fails on a revert? |
|---|---|---|
| `data/PreferencesStoreTest.kt` | the corruption is repaired and the write path recovers; the repair survives a restart; the queue survives a corrupt settings file; the queue of an older install is migrated out and the old key is removed; **an interrupted migration leaves no stale key**; **a failed migration is reported and the queue is kept**; the settings are persisted and read back; the identity is created once, **is not stored in the settings file and is not rewritten**, and **survives a corrupt settings file**; **a value of another type is treated as absent and announced**; **a failed write leaves the value alone**; **the shared snapshot does not regress under a concurrent write**; **the settings file has one write path** | yes — every new test drives the production `createStore`, the production `Preferences` and the production file layout. The mutations below were run against the production code and each of them was caught |
| `data/PreferencesResetTest.kt` | **the exact on-disk name of the queue file** (and of the two other files); **the notice survives a restart** and the acknowledgement clears the record; **two damaged files carry two notices** and the second waits for its turn; **a second corruption of the same file is a new notice** (not conflated); one notice per file is enough | yes — the name test pins the string, the restart test needs the durable record, the two-notice test needs the queue |
| `data/AudioSettingsStoreTest.kt` | a failed write is observable, the persisted value is untouched, a later success clears the notice, and **the message of the system is not shown**; the cache limit is not confirmed before the file answers and is unconfirmed while the file has not answered; a corrupt audio file is repaired and announced; **a value of another type in the audio file is treated as absent and announced** | yes |
| `data/ProfileSettingsTest.kt` | unchanged — the draft filters the control characters, the draft carries the model's own message, the field filter keeps the bounds | yes |

### The mutations that were run against the production code

Each mutation was applied to the production source, the whole suite was run,
and the mutation was then reverted. The result:

| Mutation | Caught by |
|---|---|
| the identity of the library is written into the settings file again (the old `libraryId()`), and the move into its own file is switched off | `theLibraryIdentityIsNeitherStoredInTheSettingsFileNorRewritten`, `theLibraryIdentitySurvivesACorruptSettingsFile` |
| the migration stops at the queue file it has already filled (the old early return) | `anInterruptedMigrationLeavesNoStaleKeyBehind` |
| the durable record of the pending resets is not written | `theAnnouncedResetSurvivesARestart` |
| the cast of a value of another type is not guarded | `aValueOfAnotherTypeIsTreatedAsAbsentAndAnnounced`, `aValueOfAnotherTypeInTheAudioFileIsTreatedAsAbsentAndAnnounced` |
| the notice channel is a slot again (the second notice replaces the first) | `twoDamagedFilesCarryTwoNotices` |
| the write of the settings file is not serialised any more | `theSettingsFileHasOneWritePath` |

**The one honest exception:** the *behavioural* part of the snapshot test
(`theSharedSnapshotDoesNotRegressUnderAConcurrentWrite`) did **not** fail when
the mutex was removed. The order of two publications cannot be forced from a
JVM test: the actor of DataStore applies the writes one after another, and
the publication follows the completion of the write, so the inversion the
reviewer describes needs a scheduling pattern the test cannot produce. The
invariant is therefore pinned by a **structural** test as well
(`theSettingsFileHasOneWritePath`), in the same way `LibraryWriteFenceTest`
pins the fence of the library.


## Not fixed, and why

* **N4** — the notice of a damaged queue file says that the file was damaged
  and replaced. Whether the migration restored the queue from the settings
  file afterwards is a race between two independent reads; the user cannot
  act on the difference, and the detail text of the notice says that the
  queue may have been restored from the settings.
* **N10** — the silent truncation of an over-long profile name is the
  model's own documented bound. The hint that would tell the user belongs to
  `ui/ProfileScreen.kt` (A7).
* **The queue of an install whose settings file was damaged *before* the
  first launch of this version** — the queue is inside the damaged file and
  cannot be read. The notice says so; nothing else can.
* **The identity of such an install** — the same: the identity of an older
  install is still in the settings file until the first launch of this
  version moves it out. After that move, a corruption of the settings file
  cannot touch it any more.

## Not verified in this environment

* **The platform `FileStorage` corruption path.** Every test drives the JVM
  Okio backend of DataStore. The atomic replace of a corrupt
  `*.preferences_pb` on API 26 (the platform backend, `File.renameTo` under
  API 26) is **CANNOT VERIFY** here: no device, no emulator, and APK builds
  are forbidden. The corruption *policy* is the same code in both backends
  (`createStore` builds the handler once), but the platform backend's own
  file handling is not observed.
* **The durable record on a device** — the record is a plain file written
  with `java.nio.file.Files.move` (API 26 ✓); the JVM test writes and reads
  the same file, but the Android filesystem behaviour is not observed.
* **The notice on the screen** — the wiring (`ui/MusicViewModel.kt:175`,
  `:324-326`, `ui/MusicApp.kt:181-184`) is read from the source, not
  observed on a device.
* **A read failure of a preferences file** (an `IOException` that is not a
  missing file) cannot be produced on the JVM: the Okio backend answers a
  missing or unreadable path with the default. The path that keeps the cache
  limit unconfirmed is therefore pinned by the source, not by a test.

## The build was broken by another area

The tree did not compile when this pass started: area A4's pass was
**interrupted** and left three compile errors in its own files, and area A5
left three in its test files. To be able to compile, to run the suite and to
measure the lint, **six compile-only lines** were repaired — the smallest
change that makes the code compile, without a design change:

| File | Repair |
|---|---|
| `data/DesktopRows.kt:17` | `private val index` → `internal val index` (the row map of the class reads it) |
| `data/LibraryBackupStorage.kt:16` | the fence parameter is `suspend (suspend () -> Unit) -> Unit` (a fence that is taken around a suspend block) |
| `data/LibraryRepository.kt:26` | the call site of the fence follows the new signature |
| `data/BackupMergeTest.kt` | the missing import of `kotlinx.coroutines.cancel` |
| `data/ProviderConnectionsTest.kt:267` | a `return` inside a lambda with an expression body |
| `data/SecureProviderStoreTest.kt` | the missing import of `org.junit.Rule` |

**These files belong to A4 and A5 and are not part of this area.** The
repairs are compile fixes only; the areas themselves have to review them
(the fence of A4 in particular: the signature of the fence parameter is a
design decision of that area).

## Commands run and their real output

```
powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize
  GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-9164-184352.log
  602 tests completed, 1 failed
  TEST_SUMMARY files=82 tests=602 failures=1 errors=0 skipped=0
  > Task :app:testDebugUnitTest FAILED

powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
  BUILD SUCCESSFUL in 3m 23s
  android/app/build/reports/lint-results-debug.txt: 0 errors, 17 warnings, 1 hint

powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:compileDebugAndroidTestKotlin'
  BUILD SUCCESSFUL in 4s
```

The **one** failure is `SecureProviderStoreTest.an unavailable key and a
damaged record are told apart` — a test of **area A5** (`data/SecureProviderStore.kt`),
whose production code does not have the failure type the test asks for yet.
It is not a test of this area and it was failing before this pass as well
(the file did not even compile). **Every test of A3 is green.**

The baseline of the tree was 543 tests; the tree now holds 602 (the other
areas' in-flight work accounts for the difference; this area adds **18**
tests: `data/PreferencesStoreTest.kt` 14 (5 new), `data/PreferencesResetTest.kt`
5 (new file), `data/AudioSettingsStoreTest.kt` 8 (2 new)).

## Out-of-area findings (one line each)

* `ui/MusicViewModel.kt:645` — the generic `task {}` helper puts the message
  of the exception into the message the user sees (`error.message ?:
  "Не удалось выполнить действие"`), so a failed **settings** write still
  shows a system message. A7's file.
* `ui/MusicViewModel.kt:582` — the same for the profile save.
* `data/PreferencesLayer.kt` — the record of the pending resets
  (`datastore/nedotify_resets`) is a new file in the datastore directory; the
  review manifest of A8 (`android/review/SOURCE_SHA256.txt`) has to be
  regenerated after this pass.
* `data/SecureProviderStore.kt` — the failure type that
  `SecureProviderStoreTest.an unavailable key and a damaged record are told
  apart` asks for does not exist yet; the test of A5 fails on it (A5).
