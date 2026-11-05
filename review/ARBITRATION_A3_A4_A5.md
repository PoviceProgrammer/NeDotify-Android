# ARBITRATION INPUT — A3, A4, A5

The two independent reviews of each area below have been completed. Their **actionable** findings
are consolidated here so the arbiter can adjudicate them. Every remark must still be **verified
against the code** before it is acted on — a reviewer claim is not a fact.

Common rules: `android/review/REVIEW_BRIEFS.md` (the "Rules that apply to every area" section binds
every area). Build/test: `powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize`
(redirect the output to a file; **do not** pipe it through `Select-Object`). Baseline:
**543 tests, 0 failures, 0 errors, 0 skipped**; lint 0 errors / 17 warnings / 1 hint;
`:app:compileDebugAndroidTestKotlin` green. Never run `assemble*`/`bundle*`/`package*`. Never open
`~/.nedotify/`.

---

## A3 — Settings, preferences and profile state

**Reviewer 2 (adversarial) — verdict ACCEPT WITH FIXES**

* **B1 (High) — the announced reset is not durable.** `data/PreferencesLayer.kt:47-57` keeps the
  pending reset in an **in-memory** `MutableStateFlow`. The corruption handler
  (`data/PreferencesLayer.kt:163-167`) *repairs* the file at the moment it announces, so the evidence
  is destroyed in the same operation. If the process dies before the snackbar is shown, the next
  launch finds a valid file, announces nothing — and the user's appearance, profile, search history
  and library identity are gone with no word. **Fix:** persist the marker in the replacement file
  itself (or in the queue file, which is not replaced) and clear it on acknowledgement.
* **B2 (Medium-High) — the reset channel has one slot.** `announce()` overwrites
  (`data/PreferencesLayer.kt:53`). The layer owns three files; two corrupt files → the second
  announcement replaces the first and the larger loss is never mentioned. `MutableStateFlow` also
  **conflates equal values**, so a second corruption of the *same* file after the acknowledgement
  produces no emission at all. **Fix:** a queue (`List`/`Channel`), not a slot.
* **B3 (Medium-High) — the library identity is still destroyed by a settings corruption.**
  `data/Preferences.kt:303` keeps `LIBRARY_ID` in the **settings** file, which the handler replaces
  with an empty one; `libraryId()` (`:189-192`) then mints a **new UUID silently**.
  Consequence (cross-area, real): `data/BackupMerge.kt:38` recomputes the local rows' portable ids
  with the local library id, so after a regeneration the restore is downgraded from "the same device"
  to "another device's copy". `data/LibraryBackupStorage.kt:22,59,74` call `libraryId()` on every
  export and restore. **Fix:** move the library identity out of the replaceable file, or re-use the
  previous id and state the change in the reset notice. Add the missing test.
* **N1 (Medium) — `libraryId()` rewrites the settings file on every call** (`data/Preferences.kt:190`
  → `write { … }` always performs an `edit`). A read-only export then depends on a successful write.
  **Fix:** read first, write only when the key is absent.
* **N2 (Medium) — the shared snapshot can regress.** `data/Preferences.kt:200-204` and `:250-251` are
  **two** writers of the settings file inside one class, and the publish is not ordered against the
  edit that produced it. The area log's claim "the shared snapshot is exact only because the class is
  the only writer" is falsified by the class itself. **Fix:** one publisher, or a monotonic sequence.
* **N3 (Low) — the queue migration is not idempotent after a kill between its two writes**
  (`data/Preferences.kt:244-251`): the early return at `:239` skips the cleanup, so the stale
  `queue_v2` stays in the settings file forever.
* **N4 (Low) — an announced QUEUE reset contradicts the migration** (the migration copies the queue
  back into the freshly replaced queue file).
* **N5 (Medium) — the `confirmed` cache-limit flag has no production consumer.**
  `data/AudioSettingsStore.kt:44,72-75,97`; nothing in `app/src/main` reads it;
  `NeDotifyApp.kt:39-49` still hardcodes 256 MB. P3-59's "silent 256 MB fallback" is described, not
  fixed. (The `runBlocking` half is gone.)
* **N6 (Low) — a raw system exception message is shown to the user**
  (`data/AudioSettingsStore.kt:117-118` → `ui/AudioSettingsControls.kt:42-44`).
* **N7 (Low) — the area log is inaccurate in four places**: the reset snackbar **is** wired
  (`ui/MusicViewModel.kt:175,324-326`, `ui/MusicApp.kt:181-184`); the profile dialog and the avatar
  remove **are** wired (`ui/ProfileScreen.kt:52,151-160`); there is **no** `runBlocking` in
  `NeDotifyApp.kt` any more; and the test counts in the log are stale (the tree is 543/0/0/0).
* **N8 (Low) — the on-disk name of the queue file is not pinned by any test**
  (`data/PreferencesStoreTest.kt:239-249` only asserts that the queue's name differs).
* **N9 (Low) — the layer's warm-up read is discarded** (`data/PreferencesLayer.kt:107-111`): the
  first consumer still performs its own `first()`.
* **N10 (Low) — the profile name bound is enforced by silent truncation**
  (`data/ProfileSettings.kt:43-44`) — the model's "от 1 до 40 символов" message is unreachable for an
  over-long name.
* **N11 (Low) — the store cache is keyed by the file *name* only** (`data/PreferencesLayer.kt:80`).

**Reviewer 3 (independent verification) — verdict PASS WITH GAPS**

* **R1 (Medium) — the queue of an older install is destroyed when the settings file is corrupt at the
  upgrade.** The migration reads the settings file to copy `queue_v2` out, but the corruption handler
  replaces the file *during that read* (`data/Preferences.kt:236-258`; the layer's background read at
  `data/PreferencesLayer.kt:107-111` widens the window). Reproduced: `queue() == ""`. The loss is
  announced, but as a SETTINGS reset. **Fix:** extract the queue from the raw bytes before the handler
  can run, or report it as a QUEUE reset.
* **R2 (Medium) — the "upgrade-time type mismatch" trigger is not covered.** A **valid** proto with a
  wrong-typed value parses, so the corruption handler never fires; the typed read throws a
  **`ClassCastException`** (an unchecked cast in `MutablePreferences.get`), which is **not** an
  `IOException`, so the read-side fallback does not catch it. The write path is not wedged (good), but
  the typed read fails and nothing is announced. Reproduced. **Fix:** catch the per-key cast failure
  and treat the key as absent, and announce a reset.
* **R3 (Low/Medium) — the reset announcement is not durable** (same as B1).
* **R4 (Low) — only the last reset is announced, and the same reset is rendered twice** (the snackbar
  **and** the settings panel) (same as B2).
* **R5 (Low) — an interrupted migration leaves a stale `queue_v2` in the settings file forever**
  (same as N3).
* **R6 (Low) — a transient read error is now sticky**: the `emptyPreferences()` fallback is published
  into the shared snapshot and the app shows the defaults until the next write.
* **R7 (Low) — the shared snapshot can in principle go backwards** (same as N2; not observed in 150
  stress rounds).
* **R8 (Low) — `libraryId()` writes on every call** (same as N1).
* **R9 (Low) — the `confirmed` cache-limit flag is dead code** (same as N5).
* **R10 (documentation) — four statements in the area log contradict the current source** (same as N7).
* **Harness limitation found:** the migration's `catch { Log.e(...) }` (`data/Preferences.kt:256`)
  throws `Method e in android.util.Log not mocked` in a JVM test, so **a JVM test cannot exercise the
  migration's failure path**. Consider routing the diagnostic through an injectable sink, as area A2
  did for the media policy.
* **Coverage gaps:** the queue file's exact path; the migration failure path; the interrupted
  migration; a wrong-typed key; the reset across a restart; a write failure of `Preferences`; the
  library identity surviving a settings corruption; `libraryId()` not rewriting the file; the snapshot
  not regressing under a concurrent write.

---

## A4 — Library, Room, backup codec, desktop import/export

**Reviewer 2 (adversarial) — verdict ACCEPT WITH FIXES**

* **B1 (High) — the write fence is still incomplete.** `data/Models.kt:163-165` (`addToPlaylist`) and
  `:167-177` (`importPlaylist`) call the DAO's **unqualified** `remember(track)` directly, outside
  `LibraryWrites` (`data/LibraryWrites.kt:26-37`). A delete concurrent with
  `dao.addToPlaylist` (`ui/MusicViewModel.kt:443`) or `dao.importPlaylist`
  (`data/CatalogImportController.kt:217`) **resurrects the deleted row** while its playlist entries,
  offline row, mapping and alias stay cascaded away. The area report claims the fence is closed
  (`android/review/REMEDIATION_A4_FIX.md:112-114`), and the test offered as evidence
  (`data/LibraryWriteFenceTest.kt:187-203`) greps for the **qualified** literal `dao.remember(track)`
  in three files and never looks at `Models.kt`. **Fix:** route the DAO-internal writes through the
  fence and extend the test to inspect `Models.kt` and the *unqualified* call.
* **B2 (High) — the P1 "an OOM kills the process" is closed only on the codec.** The only two
  `OutOfMemoryError` catches in `main` are `data/BackupCodec.kt:185` and `:269`. The desktop-import
  boundary (`data/DesktopLibraryImport.kt:63-67`) and the ViewModel boundary
  (`ui/MusicViewModel.kt:433`) catch `Exception` only — an `Error` escapes. The importer materialises
  `List<Map<String,Any?>>` for every row of every table (`data/DesktopLibraryImport.kt:80-106`, caps
  at `:120-125` → up to 220 000 maps) and `machineFingerprint` builds a second full materialisation
  (`data/DesktopLibraryConverter.kt:199-208`). The restore holds the incoming model, the device
  snapshot and the plan simultaneously (`data/Models.kt:133-145`). **Fix:** add the OOM guard at
  those boundaries and cut the peak.
* **B3 (Medium-High) — restoring the device's own backup rolls back the current metadata of the
  device's own remote tracks.** `data/BackupMerge.kt:46-50` puts a non-local incoming track into
  `remap` and returns **before** `sameOrigin` is computed, so `sameOrigin` only ever holds local
  tracks; the branch at `:78-82` then overwrites title/artist/album/duration. The policy the code
  states for itself at `:69-70` is "the library row wins". A remote track id *is* the identity
  (`data/BackupCodec.kt:377-379`). **Fix:** treat an existing row with the same id as same-origin for
  remote tracks too; add a test that restores an older copy of the device's own remote track.
* **N1 (Low) — the "foreign WAL" protection is one-directional** (`data/DesktopWalValidator.kt:75`
  rejects only a journal that commits **less** than the paired file). The KDoc and the report overstate
  the guarantee.
* **N2 (Low) — the reader materialises the smart-playlist `rules` value before the 4 KiB limit**
  (`data/BackupCodec.kt:757-761` vs `data/SmartRules.kt:54`).
* **N3 (Low) — a malformed desktop playlist link still aborts the whole import**
  (`data/DesktopLibraryConverter.kt:112,116` — `Map.id()` throws), unlike the dates and the history
  ids which were made lenient.
* **N4 (Low) — the smart-rules failure escapes both error prefixes** (REG P2-15 survives on that
  path): `data/BackupCodec.kt:398` throws a plain `SourceFailure`; `:267-268` re-throws it unchanged.
* **N5 (Low) — the desktop-import UI still tells the user the *label* decides the identity**
  (`ui/DesktopImportControls.kt:19`) — the identity is now the desktop's persistent UUID.
* **N6 (Low) — the export's byte-budget search re-measures the whole document ~17 times**
  (`data/BackupExport.kt:17-23`).
* **N7 (Low) — the restore path does not take the `LibraryWrites` fence** and holds one write
  transaction for the whole import (`data/LibraryBackupStorage.kt:74`, `data/Models.kt:133-145`).
* **N8 (Low) — the WAL-mode detection uses a single unchecked `read()`** (`data/DesktopLibraryImport.kt:53`).
* **N9 (Low) — the `offline_downloads` insert can surface a raw SQLite constraint error**
  (`downloads/Downloads.kt:53-55`).
* **N10 (Low) — `MOST_PLAYED`/`LEAST_PLAYED` still order by listened time, not play count**
  (`data/SmartRules.kt:115-116`, `data/DiscoveryEngine.kt:15-16`) — A6-owned.
* **N11 (Low) — the two sources' `importedPlayCount` are combined with `maxOf`, not summed**
  (`data/BackupMerge.kt:72,80`) — make the choice deliberate and documented.
* **N12 (Low) — the export holds three copies of the document** (`data/BackupCodec.kt:195,257`).
* **N13 (Low) — the report's instrumented-test count is wrong** ("59" → **66**).
* **Data-loss items the reviewer confirmed and that are NOT defects:** the restore never deletes; the
  delete path removes the history and the track in one transaction; the cached audio is removed before
  the row; the live history is never truncated; the importer only writes into a private copy.
* **Test-quality criticism:** `data/BackupMergeTest.kt:203-228` (`theRestoreNeverDeletesARow`,
  `aCancelledRestoreCannotLeaveAHalfAppliedLibrary`) are **source-string** assertions, not behaviour —
  the report's "cancel leaves no partially applied restore" scenario is therefore **not** verified by
  any executable test. `data/BackupCodecTest.kt:288-295` counts the occurrences of `OutOfMemoryError`
  — a weak proxy that says nothing about the desktop-import path.

**Reviewer 3 (independent verification) — verdict PASS WITH GAPS**

* **R1 (P2) — the new v3→v4 migration has no device-side guard.** `MIGRATION_3_4` appears nowhere in
  `android/app/src/androidTest/**`; `SmartPlaylistMigrationTest.kt:22,38` and
  `DownloadMigrationTest.kt:19` cover only 1→2 and 1→3. The JVM guard substitutes for the *SQL* but
  not for Room's own `MigrationTestHelper`/`TableInfo` validation. **Fix:** add the instrumented case
  (it cannot run here — the limitation stays recorded).
* **R2 (P2) — four writers still resurrect a deleted track outside the fence:**
  `dao.refresh` (`data/Models.kt:94-97`; called by `data/LibraryRepository.kt:180,195,197` — a
  media-store re-scan **re-creates every deleted local track**), `dao.favorite`
  (`data/Models.kt:98`; `ui/MusicViewModel.kt:342`), `dao.addToPlaylist` and `dao.importPlaylist`
  (see B1). The delete dialog promises the track is removed from the library, playlists, history and
  queue (`ui/MusicApp.kt:144`) — a later scan breaks that promise, and there is no "hidden" state.
* **R3 (P2) — the desktop merge loses the carry-over payload of a group of three or more duplicates**
  (`core/database.py:842-846`, `:863-869`, `:894-913` — the survivor is a stale plan-time dict
  snapshot). Reproduced: `play_count` 1/2/4 → the survivor gets **5** instead of 7, and `last_played`
  **regresses** to an earlier timestamp. The A9 brief requires "play_count must be **summed**"; the
  repo's own test only covers a **pair**. **This is area A9's file — report it to the A9 arbiter.**
* **R4 (P3) — the codec's *write* prefix leaks into the desktop-import context**
  (`ui/MusicViewModel.kt:404-407` → `data/LibraryBackupStorage.kt:34`): the user sees "Не удалось
  **сохранить копию**" while importing a PC library.
* **R5 (P3) — `BackupExport.prepare` re-encodes the whole document O(log n) times**
  (`data/BackupExport.kt:13,15,20`).
* **Reproduction facts the reviewer established:** `core/database.py:219` declares
  `file_path TEXT UNIQUE`, so the desktop merge is only reachable for rows whose `file_path` is NULL;
  SQLite's `LOWER()` is **ASCII-only** — `'Песня'` vs `'песня'` stay two rows (the app's own
  identity, deliberately not "fixed").
* **Test-quality criticism:** three source-string proxies stand in for behavioural properties
  (`data/BackupCodecTest.kt:288`, `data/BackupMergeTest.kt:203,221`,
  `data/LibraryWriteFenceTest.kt:158,187`); two of them assert less than their name claims.
* **Open items the reviewer confirmed:** the destination-side ambiguity is not surfaced in the restore
  message; `MOST_PLAYED`/`LEAST_PLAYED` ordering (A6); the export's three copies; the merge
  materialises the whole library in memory.

---

## A5 — Providers, OAuth / auth, secure credential store

**Reviewer 2 (adversarial) — verdict ACCEPT WITH FIXES**

* **B1 (High) — an unreachable Keystore *destroys* the stored credential.** `data/ProviderConnections.kt:68-72`
  (`recover`) removes the stored record on **any** exception. `data/SecureProviderStore.kt:100-116`
  refuses a read when the key is `Unavailable` (a Keystore service failure, a transient
  `KeyStoreException`) — a `SourceFailure` that `recover` catches → `store.remove("yandex_token")` →
  `AtomicFile.delete()`. The class's own KDoc (`data/SecureProviderStore.kt:37-43`) states the
  opposite policy: "a keystore that cannot be reached is never touched — the credential is left
  alone". It is not. The message is wrong too ("Сохранённый вход повреждён" for an unreachable key).
  **Fix:** a distinct type for "the key is unavailable"; remove the record only for a genuine
  corruption.
* **B2 (Medium) — a 403 from the re-validation is treated as proof that the credential is dead
  (logic inversion).** `data/ProviderConnections.kt:332-336` maps `401 || 403` to `DEAD`. The app's own
  HTTP layer words 403 as "the service **restricted** access for this account or app"
  (`data/ProviderHttp.kt:44`) — 403 means the service *recognised* the credential. An entitlement or
  WAF 403 on `account/status` therefore destroys a token that works everywhere else.
  **Fix:** `DEAD` = **401 only**; give 403 its own verdict that maps to KEEP.
* **B3 (Medium) — the advertised durable entry point `exchangeSpotifyRedirect()` can never find the
  pending login.** `data/ProviderConnections.kt:153` never calls `load()`; `pending` is
  `PendingLogins.EMPTY` until `load()` runs (`:48`, `:81`), so the match at `:157` fails against an
  empty list and the user gets "Проверка безопасности входа не пройдена". It works today only by
  accident. **Fix:** `exchangeRedirect` must call `load()`.
* **B4 (Medium) — a failed durable exchange is completely silent.** `:153` returns a `Job` whose
  `Deferred` exception nobody observes, on a `SupervisorJob()` scope. **Fix:** publish the failure into
  `_status.error`.
* **B5 (Medium) — re-entering the same Spotify client id in a different case silently destroys the
  token and every pending login.** `data/AuthPkce.kt:107` accepts `[a-fA-F0-9]{32}` and returns the
  value unchanged; the comparison at `data/ProviderConnections.kt:114` is case-**sensitive**; the UI
  compares case-**insensitively** (`ui/ConnectionsControls.kt:35`). **Fix:** canonicalise once.
* **N1 (Medium) — the re-validation has no integration coverage for the inconclusive case** (a 500
  from `account/status` → `UNKNOWN` → KEEP).
* **N2 (Low) — the bound of the pending-login list is not pinned by a test**
  (`data/AuthPkceTest.kt:66-85` adds only two logins).
* **N3 (Low) — the single-flight holds the refresh token as its map key without redaction**
  (`data/ProviderAuth.kt:21`; every other secret carrier redacts itself).
* **N4 (Low, CANNOT VERIFY) — the OAuth redirect scheme is claimable by any other app on the device**
  (`data/AuthPkce.kt:102`, the manifest filter has no `autoVerify`). PKCE bounds the damage (the
  token cannot be stolen) but the login can be *denied*. Record it.
* **N5 (Low) — `URLDecoder` is the wrong decoder for a URI query** (`data/AuthPkce.kt:142-144` maps
  `+` to a space; RFC 3986 says `+` is a literal plus).
* **N6 (Low) — the wrong speaker in the "the service returned invalid data" message** for a
  user-pasted token (`data/AuthPkce.kt:112-116`).
* **N7 (Low) — `busy` is a boolean, not a counter** (`data/ProviderConnections.kt:99,109`): two
  overlapping actions clear each other's flag and the first failure's message.
* **N8 (Low) — an expired pending login is not removed when the callback is refused by age**
  (`data/ProviderConnections.kt:159-166`).
* **N9 (Low) — the editor state is lost on a configuration change** (`ui/ConnectionsControls.kt:43-45`
  uses `remember`, not `rememberSaveable`).
* **N10 (Low) — a stale line citation in the area log** (the trust predicate is at
  `player/PlaybackService.kt:158-162`, not `:138-142`).
* **N11 (Low) — the area has no `android/review/REMEDIATION_A5_FIX.md`** (A4, A6, A7 and A8 each have
  one).
* **The three changed existing assertions were judged CORRECT** (each strengthens or re-targets a
  contract); the test **name** "cancelled token exchange releases mutex busy flag…" is now stale.

**Reviewer 3 (independent verification) — verdict PASS WITH GAPS**

* **D1 (Medium) — REG P2-03 loss (a): the OAuth callback is still not re-deliverable.**
  `MainActivity.kt:36-40` still nulls `intent.data`, and `:44` still reads the callback only
  `if (savedInstanceState == null)`. After a process death/restore the callback intent is re-delivered
  but never read → the browser tab is spent and the login is lost. The pending login survives and the
  "Начать заново" affordance exists, so the user is **not stuck** — but a correct authorisation is
  thrown away. **Fix:** read the callback on every `onCreate`/restore; the pending-login list now makes
  a double delivery harmless.
* **D2 (Low) — P3-17 partial: a Spotify refresh that answers 400 keeps the generic message.**
  `data/ProviderConnections.kt:227-231` drops the token on 400/401, but `data/ProviderAuth.kt:87`
  names the service only for 401 → the user sees "Сервис ответил HTTP 400" while the app has just
  disconnected their Spotify account. `invalid_grant` (400) is the **normal** Spotify answer for an
  expired refresh token.
* **D3 (Low) — the repair created a second JSON reader and left `ProviderHttp.json` as dead
  production code.** `data/ProviderHttp.kt:57-77` has **no** production caller (only the test that
  compares the two); `data/ProviderPayload.kt:17-49` is a near-verbatim copy. **Fix:** one reader.
* **D4 (Low) — the `busy` flag is a non-atomic read-modify-write outside the lock**
  (`data/ProviderConnections.kt:99,109`).
* **D5 (Low) — the durable entry point swallows every failure** (same as B4).
* **D6 (info) — the *file* layer of `SecureProviderStore` (AtomicFile, the `.bak` recovery, the size
  caps) has no JVM test**; only the instrumented one, which has never run.
* **D7 (info) — the expired-pending filter is pinned only on the `beginSpotifyLogin` path**, not on
  the `load()` path.
* **D8 (info) — a pending login of a different Spotify application is now silently dropped at load**
  (`data/ProviderConnections.kt:79`) instead of producing the previous explanation.
* **R6 (info) — `get(provider, …)` treats every provider name other than `"spotify"` as Yandex**
  (`data/ProviderConnections.kt:282`).
* **The three changed existing assertions were judged CORRECT and determinstic** (5 call sites read the
  new list format; the old single-object format is still read — pinned at `data/AuthPkceTest.kt:82`).
* **Harness:** 84 checks, 1 real failure (S10 = D2).
* **Tooling note:** `run-unit-tests.ps1 … | Tee-Object` **hangs** after a successful build (the Gradle
  daemon inherits the stdout handle) — redirect to a file instead.
