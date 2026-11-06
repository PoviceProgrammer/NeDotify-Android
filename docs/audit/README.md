# Backend audit — 2026-11-05

Owner: C (Codex Core). Baseline: `0b10607ddb2b9cbce215430f0c48b4928f0abdb7`.
This is an authored static audit and targeted regression work, not independent review.

## Evidence rules

`CODE` means the cited implementation was inspected at the baseline. `TEST` requires
an executed result for the relevant source version. `DEVICE` requires an actual
Android run and environment/APK/source identity. A test merely present in the tree
is `PRESENT_NOT_RUN`; compiling instrumentation is not a device result. No user
library, preferences, offline media, credentials or desktop profile are test inputs.

No S0 defect has been demonstrated in this initial pass. During W5 integration,
CORE-003 was confirmed as S1 and corrected with an observed red/green regression.
All device and live-provider behavior below is `NOT_RUN`.
The triage register records hypotheses without promoting them to proven failures.

## Area inventory

| Area / acceptance IDs | CODE evidence and observed contract | TEST evidence | DEVICE |
|---|---|---|---|
| Playback ownership / D03,D08 | `PlaybackService.kt:30`: MediaSessionService owns DualDeckPlayer; `NeDotifyApp.kt:54`: queue writes use application IO scope | Existing playback/gate tests present; current execution pending root gate | NOT_RUN |
| Queue, restore, position / D04,D08 | `PlaybackService.kt:243` at baseline: reads persisted snapshot, remaps missing entries, restores paused; `QueueCheckpoint.kt:25`: identity immediate, position throttled | CORE-002 actual callback regression RED 8 tests/2 failures → GREEN 8/0, preserving cadence | NOT_RUN |
| Focus/crossfade/effects / D05,D06,D07 | `DualDeckPlayer.kt`: shared deck owner, interruption hooks; actual sound and OEM AudioEffect support require a device | Focus/crossfade policy tests present; execution pending | NOT_RUN |
| History / D18 | `ListeningHistory.kt:21`: monotonic listening clock; `LibraryWrites.kt`: record and deletion share a fence | ListeningHistory/ListeningClock/library-write tests present; execution pending | NOT_RUN |
| Download execution / D11 | `Downloads.kt:68`: generation changes before cancellation; `Downloads.kt:95`: reconciles Room and WorkManager; worker updates are generation guarded | Download gate/policy/cancellation tests present; execution pending | NOT_RUN |
| Offline completeness / D11 | `TrackDownloadWorker.kt:146`: progressive declared size/HLS manifest completeness precedes `complete`; separate offline storage | Completion and storage tests present; storage instrumentation unexecuted | NOT_RUN |
| Schema and library / D01,D02 | `Models.kt:295`: **Room v4**, migrations 1→2→3→4; `NeDotifyApp.kt:62`: explicit migration registration | LibraryMigrationV4 and Room tests present; no device execution | NOT_RUN |
| Backup/desktop transfer / D12 | `LibraryBackupStorage.kt:90`: fence; `Models.kt:144`: single Room transaction; BackupMerge preflight validates identity conflicts | BackupMerge/transaction/desktop tests present; execution pending | NOT_RUN |
| Catalog import / D12,D13,D14 | `CatalogImportController.kt:60,71,112` at baseline: async journal recovery and new-start race is CORE-001 | CORE-001 red reproduced, minimal fix verified: whole controller class 14 tests passed | NOT_RUN |
| Secrets/accounts / D13,D14,D15 | `SecureProviderStore.kt:115`: AES-GCM with record AAD; `ProviderConnections.kt:385`: generation checks reject stale account replies | Store/auth/generation tests present; hardware Keystore and accounts require device | NOT_RUN |
| Search/source cancellation / D16 | `SearchAggregate.kt:16`: bounded independent requests retain partial results; `SourceDeadline.kt`: caller cancellation preserved | Aggregate/deadline tests present; actual provider availability untested | NOT_RUN |
| Lyrics / D17 | Existing multi-source identity/caching implementation; display will be reworked after design selection | Cascade/LRC/cache tests present; playback synchronization requires device | NOT_RUN |

Source filenames above are under `app/src/main/java/ru/nedotify/app/` in their
respective `player`, `downloads` or `data` package, except NeDotifyApp. Line numbers
refer to the baseline and are retained as historical evidence if fixes move them.

## Triage

| ID | Severity / priority | State | Owner | Blocked work |
|---|---|---|---|---|
| CORE-001 | S2 / P1 | RESOLVED_TEST; DEVICE NOT_RUN | C | Source regression resolved; Android catalog acceptance remains pending |
| CORE-002 | S2 / P2 | RESOLVED_TEST; DEVICE NOT_RUN | C | Source callback gap resolved; real-player/device wiring remains pending |
| CORE-003 | S1 / P0 | RESOLVED_SOURCE_TEST; DEVICE NOT_RUN | C + L | Source retry regression green; full source gate and actual Android reconnection proof remain required |
| DOC-001 | S3 / P3 | RESOLVED_DOC | L | Current docs identify v4 and mark older v3 reports as historical |

S0: data loss/corruption or secret exposure; S1: core playback/queue/lifecycle
unusable or unsafe; S2: recoverable secondary failure with workaround; S3:
cosmetic/documentation. Severity is independent of evidence confidence.

S0 is triaged immediately and stops affected changes/release; S1 is triaged the
same working day and stops affected integration/release. S2 is reviewed daily;
S3 at milestone completion. No open S0/S1 may ship. A postponed S2 needs owner,
workaround, target milestone and product-owner exception. DEVICE `NOT_RUN` still
blocks final device/release gates even when source checks pass.

## Refuted suspicion

`PlaybackService.onDestroy()` calls `checkpointQueue(true)` then cancels its own
scope. The write is submitted to `NeDotifyApp.queueWrites` and consumed in
`NeDotifyApp.ioScope`. Service cancellation therefore does not, by itself, cancel
that writer. Abrupt process termination can still lose asynchronous pending writes;
this requires a controlled device test and is not certified by source inspection.

## Exit criteria

Initial audit: inventory complete, every finding has severity/owner/evidence and
all unsupported claims are explicitly pending. Source fix: failing regression
observed before change, targeted regression green afterward, affected source gate
green at the same source identity. Device fix: original scenario repeated on the
device and evidence attached. Final acceptance remains blocked while APK/device
execution is forbidden.
