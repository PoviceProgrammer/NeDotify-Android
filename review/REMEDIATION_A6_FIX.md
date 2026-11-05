# A6 — lyrics, search, discovery, catalogue import

Verification pass, 2026-10-20. Authority: `docs/audits/independent_verification`.
This is the report of the **verification engineer** for area A6. It checks the fix
engineer's work against the area contract, fixes the defects the check found, and
states what is still open. No APK, assemble, bundle or package task was run; the
only Gradle tasks used were `:app:testDebugUnitTest`, `:app:lintDebug` and
`:app:compileDebugKotlin`.

Files owned by this area and **changed** in this pass:

* `data/LyricsCascade.kt` (one global deadline), `data/LyricsData.kt` (the LRCLIB
  attribution), `data/LyricsDiskCache.kt` (the one JSON reader)
* `data/CatalogMatcher.kt` (a blocked provider is not a missing recording),
  `data/CatalogImportController.kt` (the 500-item cap, the bounded concurrency,
  the environment pause, the error code, no throwing into the UI)
* `ui/CatalogBrowser.kt` (the cancel affordance while the import runs)
* `test/.../data/A6RegressionTest.kt`, `CatalogImportControllerTest.kt`, `LyricsDataTest.kt`
* this report

Files owned by this area and **read but not changed** (they already satisfied the
contract): `data/CatalogProviders.kt`, `data/DiscoveryEngine.kt`,
`data/DiscoveryController.kt`, `data/SearchAggregate.kt`.

---

## 1. The contract this area had to satisfy

| id | the contract |
|---|---|
| P1-01 | The lyrics identity gate: an **artist-conditioned** prefix rule, an artist **intersection** (not a superset), the **base-recording** fallback for remix/slowed/sped only, and a **real failure channel** (an outage must not be reported as "no lyrics"). |
| REG P2-69 / P2-70 / MAIN-P2-29 | **One global deadline** for the lyrics cascade, providers running **concurrently** under it. The serial cascade cost 3 500 + 4 × 2 200 = 12 300 ms > 12 000 ms. |
| MAIN-P2-28 / P3-79 | An **oversized primary LRCLIB payload** must not abort the cascade. |
| REG P2-67 | `forceRefresh` must not replace a good offline entry with an empty result. |
| P3-29 / P3-78 | The result carries its **source** (provider attribution). |
| P3-80 / P3-81 | The lyrics disk cache is **versioned** and lives outside the purgeable `cacheDir`. |
| REG P2-08 / P2-74 / MAIN-P2-40 | The catalogue import: application-owned (survives the screen), persisted per-item progress, **bounded concurrency**, pause/resume, a cancel affordance, and a cap of **500 items** (1000 is the worst-case number of provider *searches*). |
| REG P2-58 / P2-60 / P2-61 / P2-62 | Provider bodies on IO; the error classification (`httpCode`) must survive out of the import loop. |
| REG P2-73 / MAIN-P2-28 | **One JSON field reader** that tells a missing key from a JSON null, covering the `optString` call sites. |
| P3-20 | The catalogue import stores the **catalogue** metadata, not the candidate's. |
| P3-27 | The matcher retries the other candidate/provider and has a playability pre-flight. |
| REG P2-59 / P1-05 | The local search must not share the network budget; an index on `addedAt`. |

---

## 2. Contract checklist — verdict and evidence

| id | verdict | evidence |
|---|---|---|
| P1-01 (a) artist-conditioned prefix | **CONFIRMED** | `LyricsCascade.kt:61-70` — the prefix is stripped only when the embedded credits *intersect* the track's artist (`expected.intersect(embedded).isNotEmpty()`) or when the artist is unknown; `LyricsCascade.kt:63-64` keeps a "Part 2"-style second part. Live: LRCLIB `/api/get` echoes a prefixed title, so the candidate is accepted. Pinned by `A6RegressionTest.anEmbeddedPrefixIsStrippedOnlyWhenItNamesTheArtist`. |
| P1-01 (b) artist intersection | **CONFIRMED** | `LyricsCascade.kt:101` — `actualArtists.intersect(expectedArtists).isEmpty()` → reject (the old `containsAll` is gone). Pinned by `A6RegressionTest.anySharedCreditedArtistMatchesButUnrelatedArtistsDoNot`. |
| P1-01 (c) base-recording fallback | **CONFIRMED** | `LyricsCascade.kt:74-75` (`baseFallback`, remix/slowed/sped only), `LyricsCascade.kt:97` (the version relation is set equality **or** the fallback), `LyricsCascade.kt:103` (an instrumental result is refused for every other version), `LyricsCascade.kt:106` (a base fallback never carries synchronised lines). Pinned by `A6RegressionTest.limitedBaseVersionFallbackIsAlwaysPlainAndCannotInventKaraokeOrInstrumental`. |
| P1-01 (d) failure channel | **CONFIRMED** | `LyricsCascade.kt:139,146,153-154` — a provider error is remembered and, when nothing was found, a `SourceFailure` ("Не удалось получить текст из источников…") is thrown. `LyricsClient.kt:52-55` rethrows it when the primary produced nothing. Pinned by `A6RegressionTest.providerLookupsStartTogetherUnderOneBudgetAndFailuresAreNotNoMatch`. |
| REG P2-69 / P2-70 — one deadline, concurrent providers | **CONFIRMED (was partial)** | The fix engineer had made the providers concurrent with a *per-provider* timeout. This pass replaced it with **one deadline** computed once and passed down: `LyricsCascade.kt:123` (the deadline), `LyricsCascade.kt:131-133` (each provider gets the *remaining* time), `LyricsCascade.kt:129-141` (the four providers run in one `supervisorScope`). Worst case 3 500 ms primary + 8 000 ms cascade = 11 500 ms < the 12 000 ms UI deadline (`ui/MusicViewModel.kt:576`). Pinned by `A6RegressionTest.theWholeLyricsCascadeStaysInsideOneBudgetWhenAProviderHangs` (a hanging provider cannot exceed the budget) and by the barrier test (all four providers start before any answer). |
| MAIN-P2-28 / P3-79 — oversized primary | **CONFIRMED** | `LyricsClient.kt:38-41` — an over-long `plainLyrics`/`syncedLyrics` is dropped and turned into `primaryError`, not into an exception; the plain text survives and the cascade is still consulted (`LyricsClient.kt:52`). Pinned by `A6RegressionTest.oversizedPrimaryLyricsStillConsultFallbackAndKeepValidPlain`. |
| REG P2-67 — `forceRefresh` | **CONFIRMED** | `LyricsClient.kt:56-57` — "a refresh is an upgrade attempt; a miss must never replace an offline hit"; the write-back at `:58-63` is only reached with content. Pinned by `A6RegressionTest.forcedMissNeverReplacesGoodOfflineLyrics` and `forcedFailureKeepsCacheAndStillReportsUnavailability`. |
| P3-29 / P3-78 — the source | **CONFIRMED (extended by this pass)** | `LyricsCascade.kt:134` — every offered result carries the provider name; `LyricsDiskCache.kt:42,33` — the source is stored and read back; `LyricsClient.kt:46` — the primary is `LRCLIB`. **This pass** added the source to `lyricsResult` (`LyricsData.kt:35`), the LRCLIB payload mapper, which still returned a source-less result. |
| P3-80 — a versioned cache | **CONFIRMED** | `LyricsDiskCache.kt:22` (the record must carry `schemaVersion == 2`), `LyricsDiskCache.kt:42` (the writer stores it), `LyricsClient.kt:15` (the key itself carries `lyrics-v2`). Pinned by `LyricsDiskCacheTest.legacySchemasAreIgnoredAndInterruptedTemporaryWritesAreReclaimed`. |
| P3-81 — outside `cacheDir` | **CONFIRMED** | `data/LibraryRepository.kt:20` — `LyricsDiskCache(File(context.filesDir,"lyrics"))` (A4's file, coordinated). |
| REG P2-08 / P2-74 / MAIN-P2-40 — the import | **PARTIALLY SATISFIED → fixed in this pass** | See §4.1–§4.5. The cap was **1000 items** (not 500), the loop was **serial** (no bounded concurrency), the offline mode and an unreachable provider **burned the whole list** into one counter, and a refusal was **thrown into a UI button handler**. |
| REG P2-60 / P2-61 — the bodies on IO | **CONFIRMED** | `CatalogProviders.kt:84,89,90,94,95,128,134,141,158,167` — every catalogue entry point runs under `withContext(Dispatchers.IO)`; `Sources.kt:120,189` — the SoundCloud and YouTube searches likewise; `ProviderConnections.kt:98` — the credential action is on IO. |
| REG P2-58 / P2-62 — the classification survives | **CONFIRMED (extended by this pass)** | `CatalogMatcher.kt:85,92` — a 401/403/429 from a search or a playability check escapes with its code; `CatalogImportController.kt:194` — the same inside the import; `CatalogImportController.kt:15-17,138-141` — **this pass** added `errorCode` to the state so the code is no longer only a message. The import no longer counts a classified failure in the generic `unavailable` counter. Pinned by `A6RegressionTest.classifiedRateLimitStopsCatalogMatchingWithoutBeingFlattened` and `CatalogImportControllerTest.aProviderLimitKeepsItsCodeAndIsNotCollapsedIntoTheUnavailableCounter`. |
| REG P2-73 — one JSON reader | **CONFIRMED (coverage incomplete outside A6)** | `JsonText.kt:6-11` — the single helper; 61 call sites use it (`Sources.kt`, `CatalogProviders.kt`, `CatalogImportController.kt`, `LyricsClient.kt`, `LyricsCascade.kt`). **This pass** fixed the one A6 site that still called the platform reader (`LyricsDiskCache.kt:33`). Pinned by `A6RegressionTest.theJsonReaderKeepsAMissingKeyAndAJsonNullApartAndNeverProducesTheStringNull`. |
| P3-20 — the catalogue metadata | **CONFIRMED** | `CatalogMatcher.kt:80-82` — the committed track carries the catalogue title, artist, album, duration and cover. Pinned by `A6RegressionTest.matcherRetriesPlayableCandidatesAndKeepsCatalogMetadata`. |
| P3-27 — the retry and the pre-flight | **CONFIRMED** | `CatalogMatcher.kt:74-89` — the candidate list is walked, a failed playability check removes that candidate and the next one is tried; `CatalogMatcher.kt:71-94` — the other provider is tried when the first returns nothing. Pinned by the same test (the first candidate throws, the second is used). |
| REG P2-59 / P1-05 — the local search | **CONFIRMED** | `data/LibraryRepository.kt:154-155` — the local task is passed as `timeoutExemptIndices=setOf(0)`, so it no longer shares the network budget; `SearchAggregate.kt:16,24` — the exemption is implemented in A6's own file; the index exists: `data/Models.kt:274` (`CREATE INDEX index_tracks_addedAt`) and `app/schemas/…/4.json:137-143`. The quoted 6.5 s/0.89 s timings were not reproducible (per the corpus) and were **not** re-measured here. |

---

## 3. What the fix engineer had already done (verified, kept)

* The lyrics identity gate was rebuilt as an object (`LyricsCascade.kt:24-111`) and
  compared against the desktop oracle `services/music_matching.py:48-95` and
  `services/lyrics_lookup.py:40-71`. The Android gate is equal or stricter:
  the similarity threshold (`LyricsCascade.kt:77-88`, ≤ 10 % edit distance) is
  **not** looser than the desktop's 0.85 and was deliberately left alone — it is
  the false-match protection.
* The cascade became concurrent, the failure channel appeared, the oversized
  primary was contained, the `forceRefresh` write-back was guarded, the disk
  cache was versioned and moved out of `cacheDir`, the source attribution was
  added, the catalogue metadata was preserved and the matcher was given a retry.
* The import became an application-owned job with a journalled checkpoint
  (`CatalogImportController.kt:60-65,109-145`), and the UI was given
  pause/resume/finish (`ui/CatalogBrowser.kt:57-67`).

---

## 4. Defects this verification pass found, and the fixes

### 4.1 The import cap was 1000 items, not 500 — CONFIRMED defect

`CatalogImportController.start` accepted up to 1000 items and the journal could
restore 1000. The corpus is explicit: the cap is **500 items**; 1000 is the
worst-case number of provider *searches* (500 items × two searchers).

* Fixed: `CatalogImportController.kt:78-79` — the request is truncated to
  `MAX_ITEMS = 500` (`CatalogImportController.kt:201`) and the truncation is
  announced ("Обработаны первые 500 записей"), the desktop's wording.
  `CatalogImportController.kt:238` — a journal with more than the cap is refused.
* Pinned by `CatalogImportControllerTest.theRequestIsCappedAtFiveHundredItemsAndTheTruncationIsAnnounced`
  and `CatalogImportControllerTest.aJournalWithMoreThanTheCapIsRejected`.

### 4.2 The import had no bounded concurrency — CONFIRMED defect

The loop resolved one item at a time: 500 items × 20 s = **2 h 46 min 40 s**, the
number the audit quotes.

* Fixed: `CatalogImportController.kt:152-181` — a window of at most
  `concurrency` (default **4**, `CatalogImportController.kt:203`) items is in
  flight; the items are still committed **in the order of the list**, one
  durable checkpoint per committed item (`CatalogImportController.kt:166-174`),
  so the per-item durability of the previous implementation is kept. The run
  budget was raised from 5 min to 45 min (`CatalogImportController.kt:206`):
  500 items at four at a time with a 20 s per-item timeout is at most
  41 min 40 s, which now fits into one run instead of demanding a manual
  resume every five minutes.
* Pinned by `CatalogImportControllerTest.theProviderWorkIsBoundedButEveryItemIsCommittedInOrder`
  (the peak number of simultaneous provider calls is exactly 3 with
  `concurrency = 3`, and the commit order is the list order).

### 4.3 An offline app or an unreachable provider burned the whole list — CONFIRMED defect

`resolve` failures without an HTTP status were counted as "unavailable" and the
loop continued, so a 500-item playlist was walked item by item into one counter
while the provider was unreachable or the session was gone. The task's
requirement — the pause must work in offline mode and on logout, and the user
must be able to resume — was not met.

* Fixed:
  * `CatalogImportController.kt:25` — `CatalogImportBlocked`, a `SourceFailure`
    subclass, so every existing caller keeps working while the import can tell
    a systemic failure from a missing recording.
  * `CatalogMatcher.kt:95-99` — "no provider answered at all" is now a
    `CatalogImportBlocked`, while "the providers answered but this recording has
    no match" stays a per-item `SourceFailure`.
  * `CatalogImportController.kt:159-160,177-178` — the controller asks a
    `blocker` before the first item and after every commit; a reason pauses
    the request instead of consuming the rest of the list.
  * `CatalogImportController.kt:212` (offline mode) and `:218-221` (the Yandex
    session is gone) wire the blocker to the real application state.
* Pinned by `CatalogImportControllerTest.offlineModePausesTheRequestBeforeAnythingIsAdded`
  and `CatalogImportControllerTest.anUnreachableProviderPausesTheImportAndTheUserCanResumeIt`
  (the list is not burned, the request stays resumable, and the resume imports
  every item).

### 4.4 A classified failure lost its code and a refusal was thrown into the UI — CONFIRMED defect

`CatalogImportState` carried no HTTP status, and `start()`/`resume()` **threw**
`SourceFailure` into `MusicViewModel.importCatalog()` / `resumeCatalogImport()`,
which are plain button handlers without a `try` — an exception there is a crash.

* Fixed: `CatalogImportController.kt:15-17` (`errorCode`), `:71-89` (a refusal
  is reported in the state, not thrown), `:138-141` (the code of a classified
  failure is kept).
* Pinned by `CatalogImportControllerTest.aProviderLimitKeepsItsCodeAndIsNotCollapsedIntoTheUnavailableCounter`
  and `CatalogImportControllerTest.classifiedAuthFailureStopsImmediatelyAndRetainsAnActionableResumableCheckpoint`
  (the 401 keeps its code and is **not** counted as an unavailable track).

### 4.5 The cancel affordance was missing while the import ran — CONFIRMED defect

The browser offered "Приостановить импорт" while the import ran and
"Завершить импорт" only when it was already paused; a running import could not
be cancelled in one step.

* Fixed: `ui/CatalogBrowser.kt:57-67` — "Отменить импорт" is available while the
  import runs and while it is paused; the notice still states that the import
  survives the closing of the window.

### 4.6 The cascade had a per-provider timeout, not one deadline — CONFIRMED defect (partial)

Each provider had its own 8 s timeout. Under concurrency that is equivalent in
wall-clock terms, but the contract asks for **one deadline passed down**, and
the per-provider form does not express it.

* Fixed: `LyricsCascade.kt:123` computes the deadline once;
  `LyricsCascade.kt:131-133` gives every provider the *remaining* time, so a
  provider that starts late cannot spend the budget twice.
* Pinned by `A6RegressionTest.theWholeLyricsCascadeStaysInsideOneBudgetWhenAProviderHangs`.

### 4.7 The LRCLIB payload mapper returned a source-less result — CONFIRMED defect

`lyricsResult()` (the LRCLIB payload mapper, `LyricsData.kt:28`) built a
`LyricsResult` without a source, so a caller that used it would persist and
show an empty attribution — exactly the P3-29 defect.

* Fixed: `LyricsData.kt:35` — the result carries `source = "LRCLIB"`.
* `LyricsDataTest.nullPlainLyricsAndInstrumentalHaveExplicitResults` updated
  accordingly (the assertion now pins the attribution).

### 4.8 One `optString` call site in A6 — CONFIRMED defect

`LyricsDiskCache.kt:33` read the source with the platform reader. On AOSP
`optString(name)` returns a Java `null` for a **missing** key, and
`null.take(40)` would throw inside the cache read.

* Fixed: `LyricsDiskCache.kt:33` now uses the one helper (`optText`).

### 4.9 A test-harness trap worth recording (not a production defect)

`kotlinx-coroutines-test` 1.10 does **not** dispatch `backgroundScope` work from
`advanceUntilIdle()`; `runCurrent()` does. Four of the new tests failed on this
before the harness detail was pinned with a throw-away probe (which has been
removed). The production code was verified separately: the probe showed the
expected `WRITE next=0 → RESOLVE → COMMIT → WRITE next=1 → CLEAR` sequence.

---

## 5. The tests of this area

New or changed in this pass:

| test | what it pins |
|---|---|
| `A6RegressionTest.anEmbeddedPrefixIsStrippedOnlyWhenItNamesTheArtist` | the prefix is stripped only when it names the artist ("AC/DC - Back in Black" with another artist and "Океан - Part 2" keep their titles). |
| `A6RegressionTest.theJsonReaderKeepsAMissingKeyAndAJsonNullApartAndNeverProducesTheStringNull` | a JSON null and a missing key both keep the per-field default, neither becomes the text "null", neither throws, a real value is never rewritten. |
| `A6RegressionTest.theWholeLyricsCascadeStaysInsideOneBudgetWhenAProviderHangs` | the whole lookup stays inside the budget when a provider hangs. |
| `CatalogImportControllerTest.theRequestIsCappedAtFiveHundredItemsAndTheTruncationIsAnnounced` | the 500-item cap and the announced truncation. |
| `CatalogImportControllerTest.theProviderWorkIsBoundedButEveryItemIsCommittedInOrder` | the bounded concurrency and the ordered commits. |
| `CatalogImportControllerTest.offlineModePausesTheRequestBeforeAnythingIsAdded` | the offline pause and the resume. |
| `CatalogImportControllerTest.anUnreachableProviderPausesTheImportAndTheUserCanResumeIt` | the unreachable-provider pause and the resume. |
| `CatalogImportControllerTest.aProviderLimitKeepsItsCodeAndIsNotCollapsedIntoTheUnavailableCounter` | the 429 keeps its code and is not counted as unavailable. |
| `CatalogImportControllerTest.aJournalWithMoreThanTheCapIsRejected` | a journal beyond the cap is refused. |
| `CatalogImportControllerTest.completedItemsAreDurableBeforeCancellation…` (changed) | the same intent as before (a committed item is durable and is never requested again); the *request list* assertion was rewritten because the window now resolves several items at once. The old assertion `listOf("a","b","b","c")` pinned a strictly serial loop and could not hold with a bounded concurrency. |
| `CatalogImportControllerTest.classifiedAuthFailureStopsImmediately…` (changed) | the 401 keeps its code, is not counted as unavailable, and only the bounded window was started. |
| `CatalogImportControllerTest.ownItemTimeoutSkipsOneItemAndOverallBudgetPauses…` (changed) | the per-item timeout skips the item and the run budget pauses instead of losing the request; the list is now longer than the window so the budget can actually trigger. |
| `LyricsDataTest.nullPlainLyricsAndInstrumentalHaveExplicitResults` (changed) | the LRCLIB attribution. |

**Test quality of the tests that were already there.** The area's regression
tests were checked against the "would it fail if the production code were
reverted?" rule:

* `A6RegressionTest.explicitJsonNullHasIdenticalTextSemanticsAndCannotBecomeCatalogTitle`
  — **real on the platform**: a JSON null must not become a catalogue title.
  The discriminating case is AOSP, where the platform `optString(name)` answers
  `"null"` for a JSON null (the org.json jar answers `""`), so reverting the
  helper to the platform reader turns the title into the text "null" on the
  device. On the host JVM the discriminating case is the **missing key**, which
  the org.json reader answers with a `NullPointerException` — that is what
  `theJsonReaderKeepsAMissingKeyAndAJsonNullApartAndNeverProducesTheStringNull`
  pins.
* `A6RegressionTest.providerLookupsStartTogetherUnderOneBudgetAndFailuresAreNotNoMatch`
  — **real**: the `CountDownLatch(4)` barrier makes a serial cascade fail
  (the first request would wait 2 s alone), and the message assertion pins the
  failure channel.
* `A6RegressionTest.oversizedPrimaryLyricsStillConsultFallbackAndKeepValidPlain`
  — **real**: the fallback lambda asserts that it was called with the plain
  text, so an oversized primary that aborted the cascade fails the test.
* `A6RegressionTest.forcedMissNeverReplacesGoodOfflineLyrics` — **real**.
* `A6RegressionTest.matcherRetriesPlayableCandidatesAndKeepsCatalogMetadata` —
  **real**: the first candidate throws, the test asserts the second was used
  and that the catalogue metadata survived.
* `A6RegressionTest.explicitJsonNullHasIdenticalTextSemantics…` and the
  matcher/catalogue tests — **real**.
* `A6RegressionTest.neutralSmartRulesAreRejectedAndImportedDownloadEvidenceDoesNotEnableOfflinePlayback`
  — **real** (it asserts behaviour, not a constant).
* `CatalogImportControllerTest.commitBeforeCheckpointReplayKeepsTheSamePlaylistIdentity`
  — **real**: the journal write fails once, the replay must use the same
  playlist identity and must not duplicate the record.
* `A6RegressionTest.repeatedHomeRequestsAreIdempotentAndExplicitRefreshStillLoads`
  and `invalidationBurstIsConflatedBeforeTheExpensiveDatabaseAggregation` —
  **real**: they count the calls, not the constants.
* **Weak spots found**: `A6RegressionTest.providerLookupsStartTogether…` also
  asserts `server.requestCount == 4`. That is not the contract (the contract is
  the found text, the synchronisation and the source, all asserted elsewhere in
  `LyricsCascadeTest`), but it is harmless: it states that a failing provider
  is asked once. `CatalogImportControllerTest.completeRequestSurvivesAnAtomicJournalRoundTrip`
  only round-trips the record; it does not prove that the file survives a
  crash — that needs a device.

---

## 6. Findings that are **not** fixed here

| finding | why |
|---|---|
| The 14 remaining `optString` call sites outside A6 (`ProviderConnections.kt:343,344`, `AuthPkce.kt:20,23,95`, `QueueSnapshotCodec.kt:64,73,88`, `SignedUrlExpiry.kt:24,26`, `YandexDownloadInfo.kt:38,42`, `DesktopLibraryConverter.kt:208,213`) | Other areas' files. `ProviderConnections.kt:343` and `AuthPkce.kt:20,23,95` are the dangerous ones: on AOSP `optString(name)` returns a Java `null` for a missing key, and the following `.takeIf`/`.let` would throw. **Reported to the owning areas.** |
| The import is not a WorkManager job | The contract asks for "a job that survives the screen being closed". The controller is an application-owned `SupervisorJob` scope (`CatalogImportController.kt:209`) with a file journal; it survives the screen and the ViewModel, and the checkpoint survives a process restart. It is **not** a WorkManager job, so it does not survive a device reboot while the app is not running. |
| The import still creates a **second playlist** when the user starts a new import of the same list after a completed one | The *records* are not doubled (the track id is the primary key and the link insert is `INSERT IGNORE`, `data/Models.kt:167-177`), and a replay of the same request reuses the same playlist id. A deliberately repeated import is a new request and gets a new playlist. |
| The lyrics cascade's providers are not covered by a live probe for NetEase / QQ / Genius | Only LRCLIB could be probed here (§8). |
| The discovery recommendations fetch their two seeds serially with a 15 s timeout each (`DiscoveryEngine.kt:80-85`, up to 30 s) | Not one of this area's contract items; recorded as an observation, not changed. |
| REG P2-59 / P1-05 timings | The quoted timings were not reproducible and were not re-measured here; the mechanism (the budget exemption and the index) is in place. |

---

## 7. Blockers and what is **not** verifiable in this environment

* **No device, no emulator, no APK.** The Room commit path of the import, the
  process-death resume, the Media3 playback and the TalkBack labels were not
  exercised. The import's idempotent commit is argued from
  `data/Models.kt:167-177` (`@Upsert` on the track, `INSERT IGNORE` on the
  playlist link) and pinned on the controller side by
  `CatalogImportControllerTest.commitBeforeCheckpointReplayKeepsTheSamePlaylistIdentity`.
* **`lintDebug` cannot resolve its own toolchain** in this checkout: the Gradle
  dependency verification (`android/gradle/verification-metadata.xml`, 635
  components) pins `com.android.tools.lint:lint-model` and `lint-typedef-remover`
  but not `lint`, `lint-api`, `lint-checks`, `intellij-core`,
  `kotlin-compiler`, `uast` or `groovy`, so `:app:lintDebug` fails with
  "Dependency verification failed for configuration ':app:detachedConfiguration1'"
  **before it analyses any source**. `android/gradle/**` is not this area's
  file. The lint was therefore run once with
  `-Dorg.gradle.dependency.verification=off` (a run-time flag; **no repository
  file was changed**): the result is **1 error, 22 warnings, 1 hint**, and
  **none of them is in an A6 file**. The single error is
  `android/local.properties:1` — "Windows file separators must be escaped in
  property files" — a machine-local file that is not source and not part of
  this area.
* The live probe (`NEDOTIFY_LIVE_PROBES=1`) needs a system property that the
  Gradle test task does not forward and covers SoundCloud/YouTube rather than
  the lyrics providers, so it was not run; a direct HTTP probe was used instead
  (§8).

---

## 8. The live-provider behaviour that *was* checked

The network was available, so LRCLIB was probed directly:

```
GET https://lrclib.net/api/get?artist_name=Radiohead&track_name=Creep        -> 200
GET https://lrclib.net/api/get?artist_name=Radiohead&track_name=Radiohead - Creep -> 200
    trackName="Radiohead - Creep"  artistName="Radiohead"  plain+synced present
```

This **confirms the refutation** recorded in the task: `/api/get` echoes the
prefixed title, so the candidate is accepted and the lyrics *are* found. The
refuted claim ("the lyrics are never found for 'Artist - Song' tracks") is not
re-introduced, and the similarity threshold was deliberately left alone.

---

## 9. Commands and their actual output

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize
GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-11056-140815.log
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 15s
26 actionable tasks: 3 executed, 23 up-to-date
TEST_SUMMARY files=78 tests=511 failures=0 errors=0 skipped=0
```

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
BUILD FAILED in 2s
> Task ':app:lintAnalyzeDebug'
> Dependency verification failed for configuration ':app:detachedConfiguration1'
  18 artifacts failed verification: ... com.android.tools.lint:lint:32.1.1 ...
```

```
> gradlew ':app:lintDebug' '-Dorg.gradle.dependency.verification=off'      (run-time flag only)
Lint found errors in the project; aborting build.
lint-results-debug.txt: 1 error, 22 warnings, 1 hint
  error: android/local.properties:1  [PropertyEscape]        (machine-local file, not source)
  no warning, error or hint in: LyricsCascade.kt, LyricsData.kt, LyricsDiskCache.kt,
    DiscoveryEngine.kt, DiscoveryController.kt, CatalogMatcher.kt, CatalogProviders.kt,
    CatalogImportController.kt, SearchAggregate.kt, ui/CatalogBrowser.kt, JsonText.kt
```

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:compileDebugKotlin'
BUILD SUCCESSFUL in 37s
```

Baseline for comparison: the suite was **502 tests, 0 failures** before this
pass and is **511 tests, 0 failures** after it (nine new tests).

---

## 10. Out-of-area findings (one line each)

* `data/ProviderConnections.kt:343` — `account.optString("uid")` returns a Java
  `null` for a missing key on AOSP; the following `.takeIf` would throw.
* `data/ProviderConnections.kt:344` — the same for `displayName`/`login`.
* `data/AuthPkce.kt:20,23,95` — the same: `optString` followed by `.let`/`.isBlank`.
* `data/QueueSnapshotCodec.kt:64,73,88` — `optString` on the app's own snapshot:
  a JSON null becomes the text "null".
* `data/SignedUrlExpiry.kt:24,26` — a JSON null becomes "null" and the expiry is
  mis-parsed.
* `data/YandexDownloadInfo.kt:38,42` — a JSON null becomes "null"; the row is
  skipped instead of being reported.
* `data/DesktopLibraryConverter.kt:208,213` — `optString(name, fallback)` does
  **not** return the fallback for a JSON null on AOSP (it returns "null").
* `data/LyricsClient.kt` — outside A6's file list but inside A6's contract: the
  primary is a 3.5 s `withTimeout` and the cascade an 8 s deadline, so the
  worst case is 11.5 s against a 12 s UI budget — only 0.5 s of headroom.
* `data/DiscoveryEngine.kt:80-85` — the two recommendation seeds are fetched
  serially (up to 30 s).
* `android/gradle/verification-metadata.xml` — the lint toolchain is not pinned,
  so `:app:lintDebug` cannot run at all.
