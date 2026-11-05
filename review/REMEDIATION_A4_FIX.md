# A4 repair pass — 2026-10-20

Scope: the remaining confirmed A4 defects, plus the durable identity residual of
REG P2-48.

This file has three parts:

1. **The fix engineer's report** (the table below) — what was changed and why.
   The table was **corrected by the verification engineer**; the corrections are
   marked and listed under "Corrections to the fix engineer's report".
2. **The verification engineer's report** (from "Verification pass" downwards) —
   an independent check of the whole area, the defects that were still there,
   the fixes that were made, and what could not be verified here.
3. **The arbiter's pass** (from "The arbiter's pass" downwards) — the adjudication
   of the two independent reviews of the area, the defects they found in the
   repaired code, the repair of those defects, and the residuals that are left.

The fix engineer did not run Gradle. The verification engineer did:
`:app:testDebugUnitTest` → **543 tests, 0 failures**; `:app:lintDebug` →
**BUILD SUCCESSFUL** (warnings only). The arbiter ran the suite again after the
repairs; the real numbers are in "Commands run and their real output" of the
arbiter's pass. No APK, assemble, package, device, emulator or SAF run was
performed by any of the three agents.

## Part 1 — the fix engineer's report

| Finding | Change / disposition | Evidence added |
|---|---|---|
| REG P2-14 | An unambiguous local recording with known title, artist and positive duration can reuse the destination row and its file grant. Title, artist, album and duration must all match after Unicode case/whitespace normalisation. Ambiguous and unknown metadata remain separate. Room stores `local_track_aliases`, so a later re-import still recognises a recording after the destination metadata changes. Playlist and history references follow the same remap. | `BackupMergeTest.anotherDevicesKnownRecordingReusesGrantAndRemapsEveryReference`, `ambiguousOrUnknownMetadataNeverClaimsAnotherDevicesFile` |
| REG P2-15 | Read and write errors name their action and the specific format limit. History is exported under a separate bounded policy rather than failing the entire export at 100,001 sessions. The 20,000-track / 2,000-playlist / 100,000-link / 16 MiB document limits remain explicit format limits; a metadata-only document that exceeds them is rejected with an export-specific message. | `BackupCodecTest.failure messages distinguish…`, `BackupExportTest` |
| REG P2-16 (promoted P1) | Kept the prior streaming codec; no JSON DOM is constructed. SAF reads spool to an app-private file and allocate the final byte array once, avoiding the growing byte stream plus `toByteArray` copy. Previews retain only a token and summary. Read/write codec boundaries convert an allocation failure into a user-facing failure. Fixed additional streaming regressions: missing child-object fields and escaped Unicode in smart-rule raw JSON. **Corrected by the arbiter: the guard was closed on the codec only** — the desktop-import and the restore boundaries caught `Exception` only, and an `OutOfMemoryError` escaped them. Both boundaries carry the guard now, and the peak of the import was cut (see the arbiter's pass). | `BackupCodecTest.missing object fields…`, `escaped Unicode smart rule strings…`, exact `encodedSize` test, **added**: `BackupCodecTest.the codec never builds a document model and converts an allocation failure` (the conversion of an allocation failure is now a behaviour, not a count of the phrase in the source) |
| REG P2-17 | A malformed desktop smart-rule row is skipped and counted in the notice; valid smart playlists, tracks and measured history still import. | `DesktopLibraryConverterTest.malformedSmartRowIsSkippedAndMissingColumnsAreDisclosed` |
| REG P2-18 | The actual available columns are passed into the converter. Missing metadata fields are named in the preview notice rather than silently defaulted. Missing identifier columns still reject an unusable schema. **Added by the verification engineer:** one unusable *date* or *history identifier* no longer aborts the whole import either — the value is lost and the loss is disclosed in the notice. **Added by the arbiter:** one unusable *playlist link* no longer aborts the whole import either. | same converter test, **added**: `anUnreadableDateDoesNotAbortTheWholeImportAndIsDisclosed`, **added by the arbiter**: `aMalformedPlaylistLinkDoesNotAbortTheWholeImportAndIsDisclosed` |
| REG P2-19 | `BackupExport.prepare` fits the newest listening sessions into both the row and combined byte budget; tracks, playlists and the entire Room history are retained. `BackupSummary.droppedSessions` discloses how many older sessions the portable document omits. Metadata alone exceeding the byte budget produces a specific error. | `BackupExportTest` |
| REG P2-20 | Current desktop copies carry the persistent UUID in `settings.portable_library_id` (desktop writer added by coordinator). The Android importer reads that setting and accepts both the raw UUID the desktop writes today and a JSON-quoted one. Renaming the user label, adding a first session and library growth do not change identity. Different PCs no longer share identity merely because their labels match. Legacy copies without a UUID use a full snapshot fingerprint and explicitly disclose the repeat-import limitation below. | `DesktopLibraryConverterTest.permanentPcIdentitySurvivesGrowthRenameAndFirstListeningSession`, `legacySameLabelAndMissingDatesCannotSilentlyAliasDifferentPcRows` |
| REG P2-21 | The SAF API accepts the selected `.db` plus its matching `.db-wal`, validates names and enforces the same per-file copy bound/cancellation policy. A real WAL validator checks the header, database page size, salt, frame structure and chained checksums before SQLite can silently ignore a damaged journal. **Added by the verification engineer:** the validator also refuses a journal that carries no completed transaction and a journal that commits a *smaller* database than the file it is paired with (a journal of another or older database). SQLite replays only the private copy. A WAL database selected without a journal remains importable with a conspicuous stale-copy warning. | `DesktopWalValidatorTest` over a newly created JDBC SQLite WAL, including truncation/checksum damage, **added**: `theJournalOfAnotherDatabaseIsRejected`, `aJournalWithoutACompletedTransactionIsRejected`, `aJournalThatCommitsLessThanTheFileHoldsIsRejected`, `aJournalThatCommitsTheSizeOfTheFileIsAccepted` |
| REG P2-22…24 | Catalogue-provider fixes are owned by A6, not changed here. The repository search/API coordination is described below. | A6 report |
| REG P2-25 (conditional) | The private copied database is opened read-write, checkpointed and converted to DELETE journal mode before its read-only inspection. The original SAF document is never opened for writing. This avoids relying on read-only WAL support on API 26/27; device behavior is still unverified. | Static path; JVM WAL-format test is not an API-26 runtime test |
| REG P2-26 | Desktop local identities include a digest of the native row's recording metadata and creation time. Reusing desktop row 1 for another recording therefore cannot silently reuse the old portable local row. Known aliases and legitimate same-origin restore retain destination grants and edited local metadata. | `DesktopLibraryConverterTest.reusedDesktopLocalRowIdWithDifferentRecordingCannotRetainOldRow`, existing legitimate re-import test retained |
| REG P2-31 / RC-3 | Added a transactional library deletion path, plus `LibraryRepository.deleteTrack`. It deletes that track's history and the track; foreign keys remove playlist links, offline rows and identity rows. Original audio is not deleted. UI confirmation and synchronous downloaded-cache cleanup are coordinated with A7/root. Queue staging now keeps unplayed queue entries in memory; the selected/heard track is persisted separately. Root owns the PlaybackService wiring. **Corrected by the arbiter:** the claim that "a write of a track row now shares the fence … so the two remaining unfenced `dao.remember(track)` call sites cannot resurrect a deleted row" was **false** — the DAO's own `addToPlaylist`/`importPlaylist` called the *unqualified* `remember(track)` outside the fence, and `refresh`/`favorite` re-created a deleted row. The fence now carries the fact of the delete (a tombstone) and every write of a track row asks it; see the arbiter's pass. | Real SQLite migration guard verifies identity/playlist FK cascades; UI/service changes belong to coordinator/A7. **Added:** `LibraryWriteFenceTest.aTrackWriteCannotInterleaveWithTheRemoval`, `everyTrackWriteGoesThroughTheFence`; **added by the arbiter**: `aWriteAfterTheDeleteDoesNotReCreateTheRow`, `aMediaStoreScanDoesNotReCreateADeletedTrack`, `everyTrackWriteOfTheDaoAsksTheTombstone`, `thePlaylistWritesDoNotCallTheUnqualifiedRemember` |
| REG P2-43 | Current and comparison windows now have exactly equal elapsed exposure and do not overlap, including at midnight, noon and across DST. The calendar-day chart remains unchanged. | `ListeningReportsTest.comparisonExposureIsEqualAtMidnightNoonAndAcrossDst` |
| REG P2-44 | Artist totals are grouped outside SQLite using Unicode lowercase and trimmed names, with sentinel artists excluded. SQLite's ASCII-only `LOWER` no longer splits Cyrillic case variants. | `ListeningReportsTest.artistRankingGroupsUnicodeCaseAndExcludesUnknownNames` |
| REG P2-45 | Imported play counts, last-played timestamps and desktop downloaded metadata are preserved in Room v4 and backup v3. Zero-length but measured desktop sessions remain played evidence; listening totals still exclude zero measured time. A6 combines the fields with Android sessions and applies the desktop smart-rule semantics. The imported downloaded flag never grants actual offline availability. | `DesktopLibraryConverterTest.zeroMeasuredSessionAndDesktopCountersPreservePlayedEvidence`, v3 codec roundtrip; A6 smart-rule/SQL tests |
| REG P2-48 durable residual | A successful matched fallback is stored in Room `provider_mappings`. Resolution reads the stored candidate before searching again and re-applies the same recording gate to current metadata. Failed, unverified or same-provider candidates cannot become a mapping. | `ProviderMappingTest`; actual fallback endpoint behavior remains network/device dependent |
| REG P2-53 (promoted P1) | Added a host-side migration guard that invokes the actual `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` through a strict SQLite adapter. It migrates every released schema 1/2/3, compares columns/defaults/PKs/FKs/indexes with generated Room schema 4 and verifies retained grants/favorites/history plus identity cascades. It uses real JDBC SQLite, not source-string assertions or mocked SQL execution. **Added by the verification engineer:** every table of the released schemas now carries a row across the migration, and a second check compares the exported schema with the entity declarations of `Models.kt`. **Added by the arbiter:** the instrumented `MigrationTestHelper` case for 3→4 (Room's own runtime validation) — it compiles, it has never been executed here. | `LibraryMigrationJvmTest.everyReleasedSchemaMigratesWithoutLosingLibraryData`, **added**: `theExportedSchemaDescribesTheEntitiesOfTheModule`; **added by the arbiter**: `androidTest/…/LibraryMigrationV4Test.kt` |
| REG P2-54 | Room version 4 and its explicit migration were added. Coordinator owns generation/retention of schema 4 and source manifests. Existing schemas 1/2/3 are retained and consumed by the JVM guard. | schema files + migration guard |
| MAIN-P2-35 | Export reads only the newest 100,000 sessions plus a count in one Room transaction. It fits the byte budget and reports omitted older sessions; the live database history is never automatically truncated or cleared. Restore loads only histories whose IDs are in the incoming document for conflict preflight. | `BackupExportTest`; DAO export snapshot/read queries |
| MAIN-P2-43 / P3-25 | Local search projection and matching include album. | Repository and DAO change; shared validation is run by coordinator |
| MAIN-P2-44 / P3-13 | Track/search paging, playlists, playlist items, recent tracks and downloads have deterministic identity tiebreakers. `tracks.addedAt` gets an index in migration 3→4. | migration schema guard checks index; DAO SQL |
| P3-15 | All selected desktop identifiers are fixed allowlisted column names and quoted, including `PRAGMA table_info`. No untrusted column name is interpolated as executable SQL. | Importer's fixed `DesktopColumns.FULL` projection |
| P3-16 | Restore preflight handles collisions in memory. Batch DAO calls replace per-session SELECT+INSERT+UPDATE, and every bounded batch checks cancellation inside the single transaction. This is not a claim that Room turns a list into one SQL statement; it still reuses prepared statements per entity. | DAO restore and preflight path; JVM migration tests do not establish Room transaction cancellation on-device |
| P3-71 | Reachable row-count errors are SourceFailure and survive the outer importer error boundary; bad smart rules are counted, not swallowed. **Added by the verification engineer:** the empty/oversized origin label now raises a typed `SourceFailure` instead of an `IllegalArgumentException` that the importer swallowed into a generic message. | converter count checks and tolerance test |
| P3-72 | MIME picker narrowing is A7's ownership. | A7 report |
| P3-73 | `BackupPreview` contains only token, summary and notice. Preview data lives in a validated private file, reread on restore and discarded after restore/dismiss. Abandoned files are bounded to four recent documents with 24-hour expiry during the next stage. | storage API, A7 ViewModel wiring |
| P3-74 | Invalid JSON, foreign format, unsupported version, write limits and read limits have distinct contextual messages; tests assert message semantics. | codec message test |
| P3-96 | Imported desktop play/download evidence now has Android fields; other desktop-only tag metadata remains a declared capability difference. No fabricated audio availability is inferred. | schema/codec/converter changes |
| P3-97 | YEAR/rolling-versus-calendar is a declared product difference, not silently changed. The confirmed unequal comparison exposure is repaired independently under P2-43. | report tests |
| P3-98 | Repeat remains a new measured session as deliberately tested by A1; no history identity change was made. | existing A1 tests |
| P3-99 | Refuted; not changed as a purported defect. Album search improves the separate confirmed issue. | independent verification FP-16 |
| P3-100 | Cache size/age/normalisation are declared product differences owned elsewhere; not changed here. | A3/A2 reports |
| P3-101 | Importer and converter now share `DesktopColumns.FULL` as the in-code column contract, with synthetic converter/SQLite WAL fixtures. No claim is made that this creates a universal desktop/Android exchange specification. | converter contract and JVM fixtures |
| P3-102 | The seven-state-holder observation is a design opinion. Only the materialised preview retention defect is changed. | `BackupPreview` API |

### Corrections to the fix engineer's report

* **REG P2-20** — the report said the importer "reads the JSON-encoded setting".
  The desktop writer stores the **raw** UUID
  (`core/database.py:501-504`: `INSERT OR IGNORE INTO settings … str(uuid.uuid4())`),
  not a JSON-quoted string. `DesktopLibraryImport.readSourceIdentity`
  (`data/DesktopLibraryImport.kt:174-180`) parses the value with `JSONTokener`,
  which returns a bare UUID string unchanged, so both forms are accepted — the
  wording of the report was wrong, the code was not.
* **REG P2-16** — "allocate the final byte array once" is true for the SAF path
  (`LibraryBackupStorage.copyBackup` writes a private file, `File.readBytes()`
  allocates the array once), but the *preview* path of `LibraryBackupStorage.stage`
  holds the encoded bytes **and** the private file at the same time. The peak is
  still bounded by the 16 MiB format ceiling, so the P1 requirement is met; the
  sentence overstated the constant factor.
* **REG P2-53** — the guard did compare the migration with the exported schema,
  but it did not check the exported schema against the **entities**, and it did
  not put a row into every table of every released schema. Both are now covered.
* The report's closing paragraph said "this agent did not run Gradle"; the
  verification pass below records the real numbers.
* **The arbiter corrected three further statements of this table** — REG P2-16
  (the guard was closed on the codec only), REG P2-31 (the fence was open at four
  call sites) and the count of the instrumented tests ("59" was wrong; the tree
  held 66, and holds 68 after the arbiter's pass). They are marked above and
  described in the arbiter's pass.

## Coordination outside A4-owned files

* A6 owns the smart-rule query semantics and catalogue changes. Added
  `observeDiscoveryChanges` / `allDiscoveryCandidates` DAO APIs so it can throttle
  cheap invalidations before performing heavy aggregate queries.
* The repository executes search on IO, validates the source and bounded query,
  exempts local search from a network timeout, forwards partial-source notices,
  matches album and uses the persistent files directory for lyrics cache.
* A7 owns staged-preview ViewModel handling, two-document desktop picking,
  deletion confirmation, partial-search notice display and export omission notices.
* Root owns PlaybackService queue staging/heard-track persistence, synchronous
  downloaded-cache removal, Room migration registration, the desktop UUID writer,
  build dependencies, regenerated schemas/manifests and shared checks.
* The arbiter changed **two lines** of `ui/MusicViewModel.kt` (the import and the
  restore boundary, which the area owns): the staging of a desktop import now
  speaks about the transfer of a PC library, and the boundary converts an
  allocation failure into an ordinary failure. No other file of another area was
  touched.

## Part 2 — verification pass (verification engineer)

### Overall verdict

**PASS WITH GAPS.** The area now satisfies the contract that can be checked
without a device. The gaps are the ones that cannot be closed here: the 66
device tests have still never run, the Room migration has never been executed by
Room itself, and the merge of two *ambiguous* records is reported rather than
performed.

### What the verification engineer changed

| File | Defect | Why it was wrong | Fix | Test that pins it |
|---|---|---|---|---|
| `data/DesktopLibraryConverter.kt:62-65` | An empty/oversized origin label raised `IllegalArgumentException`. `DesktopLibraryImport.inspect` catches `Exception` and replaces it with "Не удалось прочитать БД. Закройте настольный плеер…", so the user was told the *copy* was broken when the *label* was wrong. | a failure category that never reaches the user (P3-71/P3-74) | a typed `SourceFailure` with its own message | `DesktopLibraryConverterTest.invalidCopiesFailBeforeTheyCanBeMerged` (the expected type changed from `IllegalArgumentException` to `SourceFailure`; the intent of the test is unchanged and stronger) |
| `data/DesktopLibraryConverter.kt:89-90,109,124,138` | `lenientTimestamp` existed but was **never used**: one unusable `added_at` / `last_played` / `played_at` / `created_at` aborted the whole desktop import, and one unusable history identifier aborted it too. | the same "one unusable row kills the import" defect as REG P2-17, one class over | the value is lost, the row is skipped, and the loss is counted and named in the notice | `DesktopLibraryConverterTest.anUnreadableDateDoesNotAbortTheWholeImportAndIsDisclosed` |
| `data/DesktopLibraryConverter.kt:153-154,168-169` | Records with identical title/artist/album/duration were kept apart (correct) but **silently**: the user got two rows and no explanation. | the "ambiguous records are reported to the user" requirement | the count of ambiguous local groups is disclosed in the import notice | `DesktopLibraryConverterTest.ambiguousLocalRecordsAreDisclosedInsteadOfBeingMergedSilently` |
| `data/DesktopWalValidator.kt:9-84` | A `-wal` of a **different** database (or a stale `-wal` paired with a newer `.db`) passed every check. SQLite replays such a journal into the copy and truncates the copy to the journal's size — a silent, destructive outcome. | REG P2-21/P2-25 cover "the newest track and the entire history are missing", not "the copy is rewritten by a foreign journal" | the validator now also checks the SQLite header of the database, the number of committed frames and the committed database size against the size of the paired file | `DesktopWalValidatorTest.theJournalOfAnotherDatabaseIsRejected`, `aJournalWithoutACompletedTransactionIsRejected`, `aJournalThatCommitsLessThanTheFileHoldsIsRejected`, `aJournalThatCommitsTheSizeOfTheFileIsAccepted` |
| `data/LibraryWrites.kt:24-38`, `data/LibraryRepository.kt:23-25,200-202` | `dao.remember(track)` was still reachable **outside** the fence (the player, the downloader). A write that landed between the removal of a track and the end of the deletion brought the deleted row back — the exact defect the fence was built for. | the fence had a hole | `LibraryWrites` gained a fourth, fenced `keep` operation; `LibraryRepository.remember` routes through it | `LibraryWriteFenceTest.aTrackWriteCannotInterleaveWithTheRemoval`, `everyTrackWriteGoesThroughTheFence` |
| `downloads/Downloads.kt:51-53` | the same unfenced write in the downloader | the same hole | `repository.remember(track)` instead of `dao.remember(track)` | `LibraryWriteFenceTest.everyTrackWriteGoesThroughTheFence` |
| `ui/MusicViewModel.kt:254` | the same unfenced write in the player — **not this area's file**; it is reported below and was fixed by the parallel work while this pass was running | the same hole | (another area) `repository.remember(track)` | `LibraryWriteFenceTest.everyTrackWriteGoesThroughTheFence` asserts the repository and the downloader; the player is covered by the same repository method |
| `data/BackupMerge.kt:123-125` | `matchable` was private, so the converter could not apply the same "may this record be recognised by its metadata" rule | — | made `internal` and reused by the converter | `DesktopLibraryConverterTest.ambiguousLocalRecordsAreDisclosedInsteadOfBeingMergedSilently` |

The verification engineer also **strengthened the coverage** (no test was
weakened or deleted):

* `LibraryMigrationJvmTest` — a row is now put into `offline_downloads` (v≥2)
  and `smart_playlists` (v=3) and must survive the migration; the FK cascade of
  `offline_downloads` is asserted; and a new test compares the **exported schema
  4** with the **entity declarations of `Models.kt`** (a drift check).
* `BackupMergeTest` — new tests for a repeated import, the same file under a
  different PC label, the union of favourites/playlists/history when two rows
  merge, the ambiguous record that must not be merged, and the source-level
  facts that the restore never deletes a row, is one transaction, checks the
  cancellation between the batches and writes in batches.
* `BackupCodecTest` — a new test pins that the codec never builds a document
  model and that both boundaries convert an `OutOfMemoryError`.
* `DesktopWalValidatorTest` — the four WAL cases above.

### The seven verification scenarios

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Migrations v1/v2/v3 → v4 preserve the data | **PASS (JVM)** | `data/Models.kt:269-297` (three explicit `Migration` objects), `data/Models.kt:265` (`version=4`). `LibraryMigrationJvmTest.everyReleasedSchemaMigratesWithoutLosingLibraryData` runs the *production* SQL against real JDBC SQLite, compares columns/defaults/PKs/FKs/indices with the exported schema 4 and asserts that the grant, the favourite, the playlist entry, the history row, the offline row and the smart playlist survive. Drift: `theExportedSchemaDescribesTheEntitiesOfTheModule` compares the exported schema with the entity declarations; additionally the schema files were **byte-identical** before and after a forced KSP re-run (SHA-256 `566763E8…` for `4.json`). Not verified: Room's own runtime validation on a device. |
| 2 | A repeated import creates no duplicates | **PASS** | `BackupMergeTest.theSameFileImportedTwiceCreatesNoDuplicate` (same file twice → 1 track, 1 link, 1 session; the second plan adds no link and changes no id) and `theSameFileFromAnotherPcLabelMergesIntoTheSameRow` (the same recording under another PC's library id merges into the device's own row and keeps its file grant). |
| 3 | The merge carries favourites, playlists and history | **PASS** | `BackupMergeTest.aMergedRowKeepsTheUnionOfFavouritesPlaylistsAndHistory`: after the merge the survivor carries the union of the favourites, of the playlist memberships (`p`+`q`) and of the history rows (`s1`+`s2`), and no playlist is lost. `theRestoreNeverDeletesARow` pins the source-level fact that the restore uses only `@Insert(IGNORE)` and `@Upsert` — no `DELETE` — so no row can be lost. **Both facts are behavioural now — the arbiter replaced the source-string assertions; see the arbiter's pass.** `ambiguousRecordsAreNeverMergedAndNoRowIsLost` pins that an ambiguous record is not merged and that both existing rows survive. |
| 4 | A corrupt or unsuitable WAL | **PASS** | `DesktopWalValidatorTest`: truncation and checksum damage are rejected; a non-WAL file is rejected; a journal of another database is rejected with "Журнал WAL не принадлежит выбранной БД…"; a journal without a completed transaction is rejected; a journal that commits less than the paired file holds is rejected. A **missing** journal is not an error but a conspicuous warning (`data/DesktopLibraryImport.kt:127-136`). The original SAF document is never opened for writing: both files are copied into the app-private cache first (`data/DesktopLibraryImport.kt:44-72`). |
| 5 | Cancelling an import leaves no partially applied restore | **PASS (by construction; not device-verified)** | `data/Models.kt:133-145` — the whole restore is one `@Transaction`; `LibraryBackupStorage.restore` calls it as one statement (`data/LibraryBackupStorage.kt:70-76`). Room 2.8.5 ends the transaction with `success=false` when the body throws, and a `CancellationException` from `ensureActive()` is such a throw, so SQLite either has all of the restore or none of it. Pinned by `BackupMergeTest.aCancelledRestoreCannotLeaveAHalfAppliedLibrary`. **The arbiter split this: the cancellation between the batches is a behaviour now, and the atomicity of the transaction is a static check that says that the rollback needs a device.** |
| 6 | Deleting a track removes its dependencies | **PASS (SQL level); the cache removal is another area's** | `data/Models.kt:105-107` — `deleteTrack` removes the history and the track in one transaction; the foreign keys (`playlist_tracks`, `offline_downloads`, `provider_mappings`, `local_track_aliases` → `tracks`, `ON DELETE CASCADE`) remove the rest; asserted in `LibraryMigrationJvmTest` against real SQLite. The downloaded cache is removed by `Downloads.removeNow` → `DownloadStorage.remove` (`downloads/DownloadStorage.kt:167`), called from `ui/MusicViewModel.kt:286` before the row is deleted. A late history write cannot resurrect the row: the checkpoint is fenced (`data/LibraryWrites.kt`), and the fence now also covers a write of the track row. |
| 7 | The new history-write guard | **PASS** | `data/LibraryWrites.kt:24-38` — one `Mutex` around the checkpoint, the track write and the removal; `data/Models.kt:92` (`rememberSession`) and `data/Models.kt:107` (`deleteTrack`) are single `@Transaction`s. `LibraryWriteFenceTest` pins all four interleavings, including the one **without** the fence that resurrects the row, and `LibraryWritesTest` pins the same for the repository's fence. |

### The two honest limitations

1. **An old desktop copy without the persistent UUID cannot prove which PC it
   came from.** `DesktopLibraryConverter.convert`
   (`data/DesktopLibraryConverter.kt:66-71`) falls back to a SHA-256 fingerprint
   of the snapshot's own content, combined with the user label. That identifies
   *a snapshot*, not *a machine*. The importer says so in the notice:
   "В старой копии нет постоянного идентификатора ПК. Использован отпечаток …" —
   an altered copy of the same PC is treated as a separate source, and repeated
   playlists/history are possible. The desktop writer
   (`core/database.py:501-504`) gives new copies a stable `portable_library_id`,
   and from then on the identity is machine-specific. Nothing in the code claims
   more than that.
2. **Records with ambiguous metadata cannot be merged automatically.** Two
   desktop rows with the same title, artist, album and duration produce two
   portable identities; the merge refuses to pick one
   (`data/BackupMerge.kt:41,58-60`: a candidate must be *unique* and must not
   already be claimed) and the second record is imported as a separate row.
   The user is told: "Записи с одинаковым названием, исполнителем, альбомом и
   длительностью не объединяются автоматически: N. Они перенесены отдельно;
   лишние удалите в библиотеке." The *destination* side of the ambiguity (two
   rows of the device itself with the same metadata) is detected by the merge
   but is not yet surfaced in the restore message — the notice channel of the
   merge itself does not exist yet.

### Defects still present

* **Device verification is still missing.** The 66 instrumentation tests under
  `android/app/src/androidTest/**` have never run — APK builds are forbidden
  here. The Room migration, the SAF import, the API-26 read-only WAL behaviour
  and the delete path are therefore verified only at the SQL/JVM level.
* **The destination-side ambiguity is not reported** (see limitation 2). The
  restore message carries only the counts of tracks and playlists.
* **`most_played` / `least_played` sort by listened time, not by play count.**
  The import preserves the desktop's counters (`importedPlayCount`), and
  `DISCOVERY_CANDIDATES_SQL` (`data/DiscoveryEngine.kt:13-20`) folds them into
  `playCount`, but `SmartPlaylistEngine` (`data/SmartRules.kt:114-116`) orders
  by `listenedMs`. An imported track with desktop play counts and no Android
  session has `listenedMs = 0` and therefore sorts last instead of by its play
  count. Both files belong to A6; the change needed there is to order
  `MOST_PLAYED`/`LEAST_PLAYED` by the candidate's `playCount` (or to add an
  imported listening estimate), and to keep `importedPlayCount` in the same
  unit.
* **A desktop track whose metadata changed is imported as a new row.** The
  desktop identity is a digest of the native row id *and* its metadata
  (`data/DesktopLibraryConverter.kt:86`), so a renamed track gets a new
  portable identity, a new row, and the old row keeps the file grant. This is
  the deliberate trade-off of REG P2-26 (a reused desktop row id must not
  silently take over an old portable row); the user can remove the leftover
  through the new delete path.
* **The export still holds three copies of the document.** `BackupCodec.encode`
  builds the portable model (a copy of the model), writes into a
  `ByteArrayOutputStream` that is pre-sized to the exact length, and returns
  `out.toByteArray()` — a second copy of the bytes. The peak is therefore
  bounded (the format ceiling is 16 MiB and every count is capped), which is
  what REG P2-16 asks for, but a 16 MiB export still needs roughly three times
  that in live heap. No device measurement of the peak exists; the constant
  factor was left alone deliberately, because the byte-exact size test
  (`BackupCodecTest.size measurement is exact…`) is the only guard on that path.

### Out-of-area findings (one line each)

* `ui/MusicViewModel.kt:254` — **reported by this pass and already fixed by the parallel
  work**: the player wrote the track row outside the fence
  (`repository.dao.remember(track)`); it now calls `repository.remember(track)`,
  which routes through `LibraryWrites`. The line moved from 233 to 254 while the
  parallel edits landed.
* `data/SmartRules.kt:114-116` — `MOST_PLAYED`/`LEAST_PLAYED` order by
  `listenedMs`; the desktop orders by play count (A6).
* `data/DiscoveryEngine.kt:15-16` — the imported play count is folded into
  `playCount` but not into `listenedMs`, so the two orderings disagree (A6).
* `ui/MusicViewModel.kt:286-288` — the downloaded cache is removed *before* the
  library row; if the cache removal fails, the row is kept and the user is
  told to retry (correct, but the order is worth knowing).
* `data/BackupMerge.kt:78-82` — for a track that is *not* same-origin the
  merged row takes the imported title/artist/album/duration but keeps its own
  `sourceUrl`/`coverUrl`; an older cover therefore survives an import that
  brings a new one.

### Commands run and their real output

```
powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize
  GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-18980-154544.log
  BUILD SUCCESSFUL
  TEST_SUMMARY files=80 tests=543 failures=0 errors=0 skipped=0

powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
  GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-24260-154803.log
  > Task :app:lintDebug
  BUILD SUCCESSFUL in 3m 50s
```

The module baseline before this pass was **511 tests, 0 failures**; the module now has
**543 tests, 0 failures**. This pass added **16** tests to the area (the rest of
the growth comes from the parallel work of the other areas, which also changed
`ui/MusicViewModel.kt` and briefly broke the compile at 15:41 — it was repaired
by that area at 15:43 and the suite was green again from then on). The lint
report contains warnings only (dependency versions, `ExportedService`, one
`UseKtx` hint in `LibraryRepository.kt:194` that predates this pass); there are
no lint errors and none of the warnings come from the changes of this pass.

The schema drift was checked by touching `Models.kt`, forcing KSP to re-run and
comparing the SHA-256 of the four exported schemas before and after:

```
BDFE4C3C1C62061F82F68193C821F298C2CA1E3F1F3ED33A0D60F0D0755EA34A  1.json
D21FE803EB984F3155D485D779E0072B81EF690E72B6A55C13B9A70A88D09BCF  2.json
6504C79190D8AAB89E69F2DF6ACA218FB8380A091327803E842F06A7CA6AB8A7  3.json
566763E8340BC5C4B31844645A7AB8617748929BD71416624F0045E7EE9015BE  4.json
```

— identical before and after, i.e. the exported schema matches the entities.

### Test-quality assessment of the area's tests

* `BackupCodecTest` — pins the round trip, the limits, the strict JSON grammar,
  the type/bound errors, the message semantics and the exact byte size. It
  would fail on a revert of the streaming writer (the `encodedSize` equality)
  and on a revert of the error prefixes (the message test). The new DOM/OOM
  test would fail on a revert of the streaming codec.
* `BackupExportTest` — pins the bounded history export: the row budget, the byte
  budget, the disclosure of the omitted sessions and the fact that the live
  snapshot is not mutated. It would fail on a revert of `BackupExport.prepare`.
* `BackupMergeTest` — pins the identity model (same origin, another device,
  aliases, ambiguity), the conflict errors and, with the new tests, the
  idempotence, the union of favourites/playlists/history and the "never delete"
  contract. It is a pure-function test, so it fails on any behavioural revert of
  `BackupMerge.plan`.
* `DesktopLibraryConverterTest` — pins the metadata mapping, the measured-time
  policy, the smart-rule translation and skipping, the column disclosure, the
  permanent identity, the legacy fingerprint and, with the new tests, the
  lenient dates and the ambiguity disclosure. It would fail on a revert of the
  converter.
* `DesktopWalValidatorTest` — pins the real WAL format (a JDBC SQLite WAL),
  the truncation and checksum damage and, with the new tests, the foreign
  journal, the journal without a commit and the committed-size rule. It is a
  real-format test, not a string assertion.
* `LibraryMigrationJvmTest` — the strongest test of the area: it runs the
  *production* migration SQL against real SQLite and compares the result with
  Room's own exported schema, then checks that the data survived and that the
  cascades fire. It would fail on any incomplete migration step.
* `LibraryWriteFenceTest` / `LibraryWritesTest` — pin the four interleavings of
  the checkpoint, the write and the removal, including the interleaving that
  resurrects a row **without** the fence, so the test cannot pass by accident.
* `BackupCodecTest`/`LibraryWriteFenceTest` use source-level assertions for the
  facts that cannot be observed from the JVM (the absence of a JSON DOM, the
  `@Transaction` boundaries, the insert-only restore). These are brittle by
  nature; they are pinned to phrases of the production source and are meant to
  fail loudly when the source changes shape.

### Remaining limits, without a runtime claim

* A legacy desktop `.db` without a persistent UUID cannot prove which physical PC
  produced it. The importer identifies that exact snapshot, warns about possible
  repeated playlists/history when an altered legacy copy is imported, and never
  silently conflates two differing snapshots by their label. Current desktop
  copies gain a stable UUID through the coordinated desktop change. Existing
  historical duplicate rows are not destructively guessed or merged.
* Local recording matching uses strong metadata, not a cryptographic audio
  fingerprint. Ambiguous/unknown records are intentionally retained separately
  and are now disclosed to the user; the user can attach a file or remove a
  duplicate through the new UI path.
* Document limits remain finite. A library's entire measured history stays in
  Room; the portable export discloses when it includes only a recent portion.
* No APK, assemble, package, device, emulator, audio playback, SAF provider or
  Android SQLite recovery was exercised by this agent. JDBC migration/WAL tests
  do not substitute for minimum-API Room/SAF instrumentation.

## The arbiter's pass

The two independent reviews of the area (`android/review/ARBITRATION_A3_A4_A5.md`,
section "A4") were adjudicated remark by remark against the source. Every remark
was re-derived from the code before it was acted on; the remarks that turned out
to be false positives are listed as such. The result of the pass:

* the **write fence was open at four call sites** (the report above claimed it
  was closed) and is now closed by a tombstone that every write of a track row
  asks;
* the **P1 "an OOM kills the process" was closed on the codec only** and is now
  closed on the desktop-import and the restore boundaries as well, and the peak
  of the import was cut;
* **restoring the device's own backup rolled back the current metadata of the
  device's own remote tracks**; the library row wins now;
* the **v3→v4 migration had no device-side guard**; the `MigrationTestHelper`
  case exists now (it compiles, it has never been executed here);
* the twelve non-blocking remarks were addressed one by one;
* the **source-string assertions that stood in for behaviour** were replaced by
  behaviour, and the two properties that cannot be observed without a device are
  now labelled as static checks instead of being presented as verified.

### The fence, closed

The defect (reviewer 2, B1; reviewer 3, R2): the DAO's own `addToPlaylist` and
`importPlaylist` called the **unqualified** `remember(track)` — the write of the
track row happened behind the back of `LibraryWrites` — and `refresh` and
`favorite` re-created a row the user had deleted. The delete takes the history,
the playlist entries, the offline row and the identity rows of a track away (the
foreign keys cascade), so a write that lands afterwards leaves a track with none
of them: the promise of the delete dialog ("Трек удалён из библиотеки") is
broken, and a later scan of the media store re-created **every** deleted local
track.

The repair has two halves.

1. **The tombstone.** `DeletedTracks` (`data/LibraryWrites.kt`) remembers the
   tracks the user deleted. It is a process-wide registry, because the writers
   live in two places: the DAO (the scan, the favourite toggle, the playlist
   writes) and the repository (the player, the downloader). It is not a second
   lock: `LibraryWrites` still serialises the delete against the writes, and the
   registry only carries the fact of the delete. A DAO method never takes the
   fence's mutex — it already holds the database write lock when it runs, and
   the two locks are always taken in the order "fence, then database".
   * `LibraryWrites.delete` marks the id **before** the removal, so a writer
     that checks the tombstone and then writes cannot undo the delete.
   * `LibraryWrites.remember` and `LibraryWrites.refresh` (the scan and the
     file import) do not write a row the user deleted.
   * Every write of a track row of the DAO asks the tombstone: `putTrack`
     (the one write of a track row), `remember`, `rememberSession`, `refresh`,
     `favorite`, `addToPlaylist` and `importPlaylist`.
2. **The unqualified call is gone.** `addToPlaylist` and `importPlaylist` write
   the track row through `putTrack(track)`, which asks the tombstone; the
   unqualified `remember(track)` is only left in `rememberSession`, the fenced
   checkpoint. The playlist writes keep their own transaction, so the track row
   and the playlist entry still land together.

**What is still open, and is stated as such:** the tombstone lives in the
process, not in the database. A tombstone that survives a restart needs a new
Room version, which is the coordinator's decision (the area did not change the
schema version under the feet of the other areas). Until then, a media-store
scan *after a restart of the app* can bring a deleted local file back; the
delete is remembered for the session. The row of a track that the user deleted
and that the **restore** brings back is the restore's own semantics: the
restore is an explicit merge of the user's own copy and it never deletes.

The test offered as evidence before (a grep for the qualified literal
`dao.remember(track)` in three files) could not see the defect: it never looked
at `Models.kt` and never looked at the unqualified call. The test now inspects
`Models.kt`, and it inspects the *unqualified* call:
`LibraryWriteFenceTest.thePlaylistWritesDoNotCallTheUnqualifiedRemember` and
`LibraryWriteFenceTest.everyTrackWriteOfTheDaoAsksTheTombstone`. The behaviour
is pinned as well: `aWriteAfterTheDeleteDoesNotReCreateTheRow` and
`aMediaStoreScanDoesNotReCreateADeletedTrack` would fail on a revert of the
tombstone.

### The allocation failure, closed everywhere

`OutOfMemoryError` is an `Error`, not an `Exception`. The only two catches of
it were in `BackupCodec`; the desktop-import boundary and the restore boundary
caught `Exception` only, so a copy that did not fit the heap of the device
killed the process. `allocationSafe` (`data/AllocationGuard.kt`) is the one
place that converts an allocation failure into an ordinary, user facing
failure, and it is used by the codec, by the desktop import, by the restore and
by the ViewModel's backup boundary. The conversion is now a **behaviour** that a
test pins (`BackupCodecTest.the codec never builds a document model and converts
an allocation failure`), not a count of the phrase in the source.

The peak of a desktop import was cut as well:

* the rows of the tables are no longer a `LinkedHashMap` each: a row is a list
  of values under a shared list of column names (`data/DesktopRows.kt`), and the
  row is still a map, so the converter and its tests are unchanged;
* the fingerprint of a legacy copy no longer builds a sorted copy of every row
  (`row.toSortedMap()` copied the whole library a second time); it reads the
  keys of the row and sorts them, and the value of the fingerprint is unchanged;
* the export holds **two** copies of the document instead of three: the writer
  writes into a buffer that is pre-sized to the exact length and returns that
  buffer instead of `out.toByteArray()`.

No device measurement of the peak exists; the byte-exact size test
(`BackupCodecTest.size measurement is exact…`) is the guard on the writer.

### The merge of the device's own remote tracks

`BackupMerge.plan` put every incoming **remote** track into the remap and
returned before `sameOrigin` was computed, so `sameOrigin` only ever held local
tracks. A remote track that the device already had therefore took the branch
"another device's copy" and the imported (older) title, artist, album and
duration overwrote the current ones — while the policy the code states for
itself is "the library row wins".

The identity of a remote track is its own id (`youtube:<id>`), so a row the
device already has is the device's own row. The merge now says so, and
`BackupMergeTest.restoringAnOlderCopyOfTheDevicesOwnRemoteTrackKeepsTheCurrentMetadata`
pins it: the current title, artist, album, duration and the moment the track
entered the library survive the restore of an older copy, and the import may
still add a favourite.

### The twelve non-blocking remarks

| Remark | Disposition |
|---|---|
| the "foreign WAL" protection is one-directional | **confirmed and fixed**: the KDoc of `DesktopWalValidator` now states the guarantee as it is — "a journal may only grow the database it belongs to"; a journal of another database of the same or of a larger size is not detected by the size comparison, and the salt and the checksum of the frames are what cover that case. |
| the reader materialises the smart-playlist `rules` value before the 4 KiB limit | **confirmed and fixed**: the raw value is bounded (`MAX_RULES_BYTES`) while it is scanned, so a copy with a huge rules field never costs its own size in the heap; the limit of the rules themselves (4 KiB) is still enforced by `SmartRules.decode`. |
| a malformed desktop playlist link still aborts the whole import | **confirmed and fixed**: the playlist and the track id of a link are read leniently, the entry is skipped and the loss is disclosed in the notice ("Записи плейлистов с неверным идентификатором пропущены: N"). |
| the smart-rules failure escapes both error prefixes | **confirmed and fixed**: the rules of a smart playlist are read through one helper that raises the failure of the boundary ("Не удалось прочитать копию: …" / "Не удалось сохранить копию: …"), on the read path and on the write path. |
| the desktop-import UI still tells the user the *label* decides the identity | **confirmed and fixed**: the text says that a repeated transfer is recognised by the permanent identifier inside the copy and that the name of the source is only a label. |
| the export's byte-budget search re-measures the whole document ~17 times | **confirmed and fixed**: the document is measured once, and the history — the only part that grows — is measured in one pass into the sizes of its prefixes; the binary search reads them. `BackupCodecTest.the measured sizes of the history agree with the size of the document` pins the agreement with the size of the whole document. |
| the restore path does not take the `LibraryWrites` fence | **confirmed and fixed**: the restore runs inside the fence of the library, so a restore and a deletion of a track cannot interleave. The lock order is "fence, then database", so the fence cannot deadlock against the write lock of Room. |
| the WAL-mode detection uses a single unchecked `read()` | **confirmed and fixed**: the header of the copy is read completely; a half-read header no longer looks like a database without the log. |
| the `offline_downloads` insert can surface a raw SQLite constraint error | **confirmed and fixed**: the insert of the download row converts a constraint violation into an ordinary failure ("Трек удалён из библиотеки"). |
| the two sources' `importedPlayCount` are combined with `maxOf`, not summed | **confirmed and documented**: the choice is deliberate and now written down — the same copy may be imported more than once, and a sum would count every session of the desktop twice. `BackupMergeTest.theImportedCountersAreNeitherLoweredNorSummed` pins it. |
| the export holds three copies of the document | **confirmed and fixed**: the writer returns its own buffer, so the export holds the model and the document, not three copies. |
| the report's instrumented-test count is wrong ("59") | **confirmed and fixed**: the tree held **66** instrumented `@Test` methods before this pass and holds **68** now (the two new cases of the v3→4 migration). The report above says 66 for the state of the area and 68 for the state after the arbiter's pass. |

### The test-quality criticism

The two properties of `BackupMergeTest` that stood in for behaviour were
replaced:

* `theRestoreNeverDeletesARow` is now a **behavioural** test: after the merge
  and the apply step, every track, playlist, playlist entry, listening session
  and smart playlist of the library is still there. The write strategy of the
  DAO (an upsert, an insert that ignores a conflict, no `DELETE`) is a **static**
  check now, in a test that says so (`theDaoWritesTheRestoreWithoutDeleting`).
* `aCancelledRestoreCannotLeaveAHalfAppliedLibrary` was split: the batches of
  the restore are a **behavioural** test (`theBatchesOfTheRestoreStopAtTheCancellation`
  — the batch writer is now a function of its own, `writeBatches`), and the
  atomicity of the transaction is a **static** check that says plainly that the
  rollback of a transaction is Room's own behaviour and needs a device
  (`theRestoreIsOneRoomTransaction`).
* `BackupCodecTest.the codec never builds a document model and converts an
  allocation failure` no longer counts the phrase `OutOfMemoryError` in the
  source: the conversion of an allocation failure is a behaviour now.

Two static checks are left in the merge test, and both are labelled as such.
The other source checks of the area are the facts that cannot be observed from
a JVM test at all — the transaction boundary of the DAO's checkpoint and
removal, the absence of a document model in the codec, and the wiring of the
tombstone into the writers of the DAO — and each of them says what it checks
and what it cannot show.

### The residuals of the area

1. **The tombstone of a delete lives in the process.** A media-store scan after
   a restart of the app can bring a deleted local file back. A durable
   tombstone needs a new Room version; the coordinator owns the schema.
2. **The destination-side ambiguity is not reported.** The merge detects that
   the device itself has two rows with the same metadata and that the incoming
   record matches both; it refuses to merge them and imports the record
   separately — but the restore message carries only the counts of the tracks
   and the playlists. The notice channel of the merge does not exist yet, and
   adding it would change the signature of the DAO's restore and the message of
   another area's screen.
3. **`most_played` / `least_played` order by the listened time, not by the play
   count** (`data/SmartRules.kt:114-116`, `data/DiscoveryEngine.kt:15-16`).
   A6 owns both files.
4. **The device tests have never run.** 68 instrumented `@Test` methods
   compile; APK builds are forbidden in this environment, so Room's own runtime
   validation of the migrations, the SAF import, the read-only WAL behaviour of
   API 26 and the cascades **on the device** are **CANNOT VERIFY**.
5. **A legacy desktop copy without the permanent UUID identifies a snapshot,
   not a machine** — unchanged, and disclosed to the user by the importer.
6. **The merge still holds the whole library in memory.** `BackupMerge.plan`
   works on the snapshot of the device, on the incoming document and on the
   plan at the same time. The format's caps bound it (20,000 tracks, 2,000
   playlists, 100,000 sessions, a 16 MiB document), and the allocation failure
   of the restore boundary is now an ordinary failure, but no measurement of the
   peak of a restore exists.

### Out-of-area findings of the arbiter (one line each)

* `core/database.py:842-846`, `:863-869`, `:894-913` — **the desktop merge loses the
  carry-over payload of a group of three or more duplicates**: the survivor is a
  stale plan-time snapshot, so `play_count` is not summed (1/2/4 becomes 5
  instead of 7) and `last_played` can regress. The file belongs to area A9; it
  is reported here and **not** changed.
* `core/database.py:219` — `file_path TEXT UNIQUE`, so the desktop merge is only
  reachable for the rows whose `file_path` is NULL.
* `data/DiscoveryEngine.kt:15-16` — the imported play count is folded into
  `playCount` but not into `listenedMs`, so the two orderings disagree (A6).
* `ui/MusicViewModel.kt:286-288` — the downloaded cache is removed before the
  library row; if the removal fails, the row is kept and the user is told to
  retry (correct, but the order is worth knowing).
* `data/BackupMerge.kt` — the merged row of a track that is not same-origin keeps
  its own `sourceUrl`/`coverUrl`; an older cover therefore survives an import
  that brings a new one.

### Commands run and their real output (the arbiter's pass)

```
powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize
  GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-28008-182835.log
  BUILD SUCCESSFUL
  TEST_SUMMARY files=82 tests=604 failures=0 errors=0 skipped=0

powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
  BUILD SUCCESSFUL
  lint-results-debug.xml: 17 warnings, 0 errors, 0 hints
  (no lint issue in any A4-owned file)

powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:compileDebugAndroidTestKotlin'
  BUILD SUCCESSFUL
```

The tree holds **604** JVM tests now; the baseline of the brief was 543, and the
difference is the parallel work of the other areas (A3, A5, A6, A7). The area
itself holds **111** JVM tests, all green:

| Test class | Tests |
|---|---|
| `BackupCodecTest` | 29 |
| `BackupMergeTest` | 20 |
| `LibraryWriteFenceTest` | 13 |
| `DesktopLibraryConverterTest` | 17 |
| `ListeningReportsTest` | 12 |
| `DesktopWalValidatorTest` | 6 |
| `LocalImportBatchTest` / `LocalImportTitleTest` | 4 / 3 |
| `BackupExportTest` | 2 |
| `DesktopRowsTest` (new) | 2 |
| `LibraryMigrationJvmTest` | 2 |
| `LibraryWritesTest` | 1 |

The lint gate is unchanged: 0 errors, 17 warnings, 0 hints (the one hint of the
baseline, a `UseKtx` in `LibraryRepository.kt`, is gone; the warnings are the
dependency versions, `ExportedService` and the deprecations of the other areas).
The instrumented suite holds **68** `@Test` methods (66 before this pass, plus
the two new cases of the v3→4 migration). They compile; they have never been
executed here, because an APK build is forbidden in this environment.
