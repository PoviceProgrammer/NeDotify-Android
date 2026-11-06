# CORE-001 — new catalog import can replace a recovering saved import

- Area / owner: catalog import / C.
- Baseline: `0b10607ddb2b9cbce215430f0c48b4928f0abdb7`.
- Severity / priority: **S2 / P1**. The old partially imported playlist remains in
  Room, but its resumable pending list can be replaced. The workaround is to wait
  for recovery and resume/discard the saved request before starting another.
- State: `RESOLVED_TEST`; DEVICE remains `NOT_RUN`.
- Acceptance: D12–D14; W5 recovery contract.

## Trigger / expected result

Create a controller whose journal contains an unfinished import and call `start`
before its initialization coroutine has executed. The old checkpoint and playlist
identity must remain; a new request must be refused with the existing actionable
"continue or finish saved import" message. No new track may be committed.

## CODE

`data/CatalogImportController.kt:60`: asynchronous read assigns `checkpoint`.
`:71`: the `checkpoint != null` guard occurs synchronously in `start`.
`:112`: the queued run waits for initialization, but its selected lambda can then
create and persist a new checkpoint without rechecking the recovered one.

## TEST / DEVICE

TEST: **RED observed**. Actual-controller regression
`CatalogImportControllerTest.aStartBeforeJournalRecoveryCannotReplaceTheUnfinishedImport`
uses the existing StandardTestDispatcher and MemoryJournal seam: preseed journal,
construct, immediately start, runCurrent, assert original checkpoint and zero new
commits. The test failed with the original checkpoint replaced by `null` after the
replacement import completed: 1 test, 1 failure, 0 errors, 0 skipped.

Evidence: [red JUnit XML](evidence/CORE-001-red.xml),
[red Gradle log](evidence/CORE-001-red-gradle.log).
JUnit timestamp: `2026-11-05T13:17:22.798Z`.
Red production-file SHA-256:
`235e3b10a96f0904abbcce49aca0c93d4e59d9e3f6ae9ab2fb81b01972906c44`.
Regression-file SHA-256:
`6b4e0b2ed220d7f064af9c6223b2a4b8f3a2102e54b95647f327e646c40fa2d1`.
Root executed the focused JVM test; no Gradle invocation was launched by C.
Two preceding host native-memory failures produced no test result and are not
counted as red evidence.

Portable regression loop, from the Android project root:

```powershell
powershell -NoProfile -File .\tools\run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest' -InitScript docs/audit/CORE-001.gradle -Summarize
```

The portable init script runs the whole controller test class, including the new
regression and existing start/resume/cancellation cases. The captured red run used
the same task with `-InitScript C:\Temp\nedotify-core-001.gradle`, whose filter
selected only `ru.nedotify.app.data.CatalogImportControllerTest.aStartBeforeJournalRecoveryCannotReplaceTheUnfinishedImport`.
The temporary script also bounded test heap to 256 MiB, one fork, two active
processors and 64 MiB code cache; the portable loop retains these test-process
bounds. The ordinary source/CI gate runs without this diagnostic filter.

DEVICE: NOT_RUN. No actual user journal is read or modified by this test.

## Ranked hypotheses and predictions

1. Guard is before journal initialization: awaiting recovery before the guarded
   selection prevents replacement.
2. Serialized selection is missing a second checkpoint check: a guard inside the
   selection lock prevents replacement even for a queued start.
3. Existing test scheduler hides production ordering: a regression starting before
   runCurrent will expose it; if it does not, this candidate is reconsidered.

## Exit

Observe actual-controller regression fail against baseline; fix guarded selection;
rerun the same regression and whole CatalogImportControllerTest suite; preserve
existing file format, existing imported tracks, normal start and resume behavior.

## Fix

The new-start selection rechecks `checkpoint` after `initialized.await()`, while
holding enqueue's existing mutex, before constructing or writing a new request.
The existing immediate guard remains for already-recovered state. A recovered
request is retained and the caller receives the existing continue/finish message.
No Room schema, journal format or provider contract changes.

## Green verification

Root executed the whole `CatalogImportControllerTest` class after the fix:
**14 tests, 0 failures, 0 errors, 0 skipped**. This includes the original cold-start
trigger and existing normal start, resume, cancellation, limits and journal cases.
Evidence: [green JUnit XML](evidence/CORE-001-green.xml),
[green Gradle log](evidence/CORE-001-green-gradle.log).

Fixed production-file SHA-256:
`49b89b237533dde39f0cce634eb84d2f447e73d89d7f8c9cd7a82c60902e9182`.
The regression file is unchanged from the captured red run. This resolves the
demonstrated JVM/controller defect; it does not certify Android process death,
real account authorization, network playback or actual device storage behavior.
