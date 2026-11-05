# A5 — providers, OAuth / auth, secure credential store

Final arbitration and repair pass, 2026-10-20. Authority:
`android/review/REVIEW_BRIEFS.md` → "A5", `android/review/ARBITRATION_A3_A4_A5.md`
→ "## A5", and `docs/audits/independent_verification/` (the corpus).

This is the report of the **final arbiter** of area A5. It adjudicates every
remark of the two independent reviews (reviewer 2 = adversarial, reviewer 3 =
independent verification), states what was changed, what was refused and why,
and what **cannot** be verified in this environment.

No APK, assemble, bundle or package task was run. The Gradle tasks used were
`:app:testDebugUnitTest`, `:app:lintDebug` and `:app:compileDebugKotlin`.

## 1. Scope and the files

Files owned by this area and **changed** in this pass:

| file | what changed |
|---|---|
| `data/SecureProviderStore.kt` | two **typed** failures (the key is unavailable / the record is corrupt), the crash-safe **file** layer (`SecretRecordFile`, `.bak` recovery, size caps) on `java.io.File` instead of the untestable `android.util.AtomicFile` |
| `data/ProviderConnections.kt` | the recovery policy (a record that cannot be read is never removed), the `busy` **counter**, the re-validation verdict, the durable entry point (`load()` + a published failure), the single-flight exchange, the unknown provider, the `lost` credential message |
| `data/ProviderAuth.kt` | `SecretKeyRef` (a redacted flight key), `YandexVerdict.RESTRICTED`, the "the credential was lost" message |
| `data/AuthPkce.kt` | the canonical Spotify client id, the percent-decoder of a URI query, the message of a token the **user** typed in, `PendingSpotify.expired`, `AuthPkce.isRedirect`, the app-wide JSON reader (`optText`) |
| `data/ProviderPayload.kt` | the **one** JSON reader of the area (the copy in `ProviderHttp` is gone) |
| `data/ProviderHttp.kt` | the dead second reader `ProviderHttp.json` removed — the class is the transport only |
| `ui/ConnectionsControls.kt` | the editor state survives a configuration change (`rememberSaveable`) |
| `MainActivity.kt` | the OAuth callback is delivered on **every** creation and marked in a ViewModel |
| `test/.../data/ProviderConnectionsTest.kt`, `ProviderAuthTest.kt`, `SecureProviderStoreTest.kt`, `AuthPkceTest.kt`, `test/.../ui/ConnectionsControlsTest.kt` | the regression tests of §5 |
| this report | — |

Files of other areas that were **read** and not changed: `data/CatalogProviders.kt`
(A6), `data/LibraryRepository.kt` (A4), `player/PlaybackService.kt` (A2),
`ui/MusicViewModel.kt` (A7). `data/JsonText.kt` (the app-wide JSON reader,
A6) is **used** by the files of this area — it is the one reader the corpus
asks for (REG P2-73) — and was not changed.

## 2. The contract of the area

| id | the contract |
|---|---|
| P1-03 | A 401 from one endpoint is **re-validated** against `account/status` before the credential is dropped; the order is re-validate → then remove; `yandexScope()` rotates only when the credential really is dropped. |
| REG P2-03 | The pending login is removed only **after** the token is stored, the exchange survives the Activity's teardown, and the callback is **re-deliverable**. |
| REG P2-05 | No mutex held across HTTP; a single-flight instead; a refresh token is spent exactly once. |
| P3-66 / MAIN-P2-42 | A second PKCE start must not invalidate the first pending login. |
| P3-67 | `cancelSpotifyLogin()` must have a caller. |
| P3-68 | The confirm button must not clear the field before the save completes. |
| P3-69 | A `liveRegion`. |
| P3-71 | The reachable `require` must not be swallowed. |
| P3-17 | The error must name the credential. |
| P3-18 | The cipher fully guarded, a broken alias replaced, no raw crypto exception to the UI. |
| P3-20 | The Spotify import must keep the catalogue metadata (A6/A7). |
| P3-21 | A `maxBytes` seam. |
| P3-70 | The `password()` semantics must be used. |
| REG P2-04 | The MediaSession trust predicate (already satisfied in `player/PlaybackService.kt`). |
| P3-19 | No `CertificatePinner` — a recorded decision, not to be implemented. |
| REG P2-73 | One JSON field reader that tells a **missing** key from a **JSON null** (the call sites of A5 are included). |

## 3. The one model that carries the area

| rule | what it replaces |
|---|---|
| **A credential is only destroyed on proof.** A 401 is re-validated before the credential is dropped; a pending login is consumed only after its token has been stored; and a stored record is removed **only** when it is provably damaged. | One 401 from any Yandex call destroyed the user's credential (P1-03); the pending login was destroyed *before* the token POST (REG P2-03); an unreachable Keystore deleted the record (B1). |
| **One flight at a time.** A refresh token is spent by exactly one request; one callback is exchanged once, however often it is delivered. | Two concurrent refreshes could spend the same refresh token; a repeated delivery of the callback could spend the same code. |
| **The state is guarded, the network is not.** The mutex protects the in-memory credentials and is never held across an HTTP call; the `busy` flag is a counter that is changed under that mutex. | `action{}` and `spotifyAccess()` held the lock across the token POST, the account call and the refresh (REG P2-05); `busy` was a boolean read-modify-written outside the lock (N7/D4). |

## 4. The adjudication, remark by remark

### Reviewer 2 (adversarial) — verdict ACCEPT WITH FIXES

| remark | verdict | evidence |
|---|---|---|
| **B1 (High)** an unreachable Keystore destroys the stored credential | **confirmed & fixed** | `data/ProviderConnections.kt:94-160` — the recovery no longer removes a record it could not read. `data/SecureProviderStore.kt:27-38` — `ProviderCredentialUnavailableException` ("the key cannot be used **now**") and `ProviderCredentialCorruptException` ("the record is damaged") are two different types. `recover()` returns a `Recovery` with the state `VALUE / ABSENT / UNREADABLE / UNUSABLE / CORRUPT`; only `CORRUPT` removes the record, and the removal of the token, of the pending logins and of the Yandex record is now guarded by that state (`:137`, `:156`). Pinned by `ProviderConnectionsTest.an unreachable keystore never destroys the stored credential` (the record survives, **no** record is removed, the user is told) and `ProviderConnectionsTest.a damaged record is removed from the store` (the counterpart). The class's own KDoc promise (`data/SecureProviderStore.kt:54-63`) is now true. |
| **B2 (Medium)** a 403 from the re-validation is treated as proof that the credential is dead | **confirmed & fixed** | `data/ProviderConnections.kt:458-467` — `401 → DEAD`, `403 → RESTRICTED`, everything else → `UNKNOWN`; `data/ProviderAuth.kt:66-84` — `RESTRICTED` maps to `KEEP`. The app's own HTTP layer words 403 as "the service **restricted** access for this account or app" (`data/ProviderHttp.kt:44`) — the service recognised the credential. Pinned by `ProviderConnectionsTest.a 403 from the revalidation does not destroy the credential` and `ProviderAuthTest.a restricted re-validation never drops the credential`. |
| **B3 (Medium)** `exchangeSpotifyRedirect()` can never find the pending login | **confirmed & fixed** | `data/ProviderConnections.kt:250-254` — `exchangeRedirect()` calls `load()` before it matches the callback, so the durable entry point finds the pending logins of the store. Pinned by `ProviderConnectionsTest.the durable entry point finds the pending login of a fresh instance` (a fresh instance, a pending login written by another instance, the token is stored). |
| **B4 (Medium)** a failed durable exchange is completely silent | **confirmed & fixed** | `data/ProviderConnections.kt:241-249` — the `Job` publishes the failure into `_status.error` before it rethrows it. Pinned by `ProviderConnectionsTest.a failed durable exchange tells the user` and `…a durable exchange of a foreign callback tells the user`. |
| **B5 (Medium)** re-entering the same Spotify client id in a different case destroys the token and every pending login | **confirmed & fixed** | `data/AuthPkce.kt:121` — `validateClientId()` canonicalises the id once (lower case). The model (`data/ProviderConnections.kt:194-206`) and the panel (`ui/ConnectionsControls.kt:35`) now compare the same spelling. Pinned by `ProviderConnectionsTest.the same client id in another case keeps the token and the started login` and `AuthPkceTest.the Spotify application has one spelling`. |
| **N1 (Medium)** no coverage for the inconclusive re-validation | **confirmed & fixed** | `ProviderConnectionsTest.an inconclusive revalidation keeps the credential` — a 500 from `account/status` keeps the credential, keeps the connection and does not rotate the scope. |
| **N2 (Low)** the bound of the pending-login list is not pinned by a test | **confirmed & fixed** | `AuthPkceTest.the list of pending logins is bounded` — `PendingLogins.MAX` (4) is the bound, the newest login is kept and the oldest one is dropped. |
| **N3 (Low)** the single-flight holds the refresh token as its map key without redaction | **confirmed & fixed** | `data/ProviderAuth.kt:15-21` — `SecretKeyRef`, a carrier that redacts itself; the refresh key (`data/ProviderConnections.kt:324`) and the re-validation key (`:458`) use it. Pinned by `ProviderAuthTest.the key of a flight never carries the secret into a diagnostic string`. |
| **N4 (Low, CANNOT VERIFY)** the OAuth redirect scheme is claimable by any other app | **confirmed & recorded, not fixed** | `AndroidManifest.xml:22-27` — the filter has no `autoVerify`, so another app can claim `nedotify://oauth/spotify`. PKCE bounds the damage (the code cannot be stolen without the verifier), but the login can be **denied**. A dedicated HTTPS App Link would need a domain the project does not own. Recorded as a design note (§7), not a regression. |
| **N5 (Low)** `URLDecoder` is the wrong decoder for a URI query | **confirmed & fixed** | `data/AuthPkce.kt:188-208` — the query is percent-decoded by the area's own decoder; a `+` is a literal plus. Pinned by `AuthPkceTest.a plus in the callback is a literal plus and not a space`. |
| **N6 (Low)** the wrong speaker in "the service returned invalid data" for a user-pasted token | **confirmed & fixed** | `data/AuthPkce.kt:133-138` — `AuthPkce.userSecret()` is used by `saveYandexToken` (`data/ProviderConnections.kt:298`) and answers "Введённый токен выглядит некорректно"; the service's own secrets keep the service's wording. Pinned by `AuthPkceTest.a token the user typed in is the user's mistake and not the service's`. |
| **N7 (Low)** `busy` is a boolean, not a counter | **confirmed & fixed** | `data/ProviderConnections.kt:173-179` — `busyCount` under the mutex; `publish()` derives `busy = busyCount>0`. An overlapping action no longer clears the flag of the running one, and the message of a failed action is only cleared when the **first** action starts (`busyCount==1`). Pinned by `ProviderConnectionsTest.overlapping actions keep the panel busy until the last one ends`. |
| **N8 (Low)** an expired pending login is not removed when the callback is refused by age | **confirmed & fixed** | `data/ProviderConnections.kt:264-272` — the refusal by age removes the pending login (`PendingSpotify.expired`, `data/AuthPkce.kt:40`). |
| **N9 (Low)** the editor state is lost on a configuration change | **confirmed & fixed** | `ui/ConnectionsControls.kt:43-53` — the editor, the saving flag, the revision and the reveal flag are `rememberSaveable`; **the token itself stays in memory only** and never enters a saved-state Bundle. |
| **N10 (Low)** a stale line citation in the area log | **confirmed & fixed** | `docs/REMEDIATION_TRACKER.md` — the trust predicate is at `player/PlaybackService.kt:158-162` (`onConnect`), not `:138-142`. |
| **N11 (Low)** the area has no `REMEDIATION_A5_FIX.md` | **confirmed & fixed** | this file. |

### Reviewer 3 (independent verification) — verdict PASS WITH GAPS

| remark | verdict | evidence |
|---|---|---|
| **D1 (Medium)** the OAuth callback is still not re-deliverable | **confirmed & fixed** | `MainActivity.kt:52-66` — `readOAuth(intent)` runs on **every** `onCreate` (the old `if(savedInstanceState==null)` is gone) and the intent data is **not** nulled any more. The delivery is marked in `OAuthDeliveryViewModel` (`MainActivity.kt:35-47`), a ViewModel: it survives a configuration change (the same callback is not delivered twice) and it does **not** survive a process death (the callback is delivered again — the case the reviewer reported). The model side is safe as well: a callback that belongs to no login consumes nothing, and a callback that belongs to a consumed login is refused. Pinned by `ConnectionsControlsTest.the OAuth callback is delivered on every creation and only once per process` (a source-level guard — an Activity lifecycle is not observable from a JVM test) and, on the model side, by the unchanged `ProviderConnectionsTest.OAuth callback survives process recreation and is consumed only once`. |
| **D2 (Low)** a Spotify refresh that answers 400 keeps the generic message | **confirmed & fixed** | `data/ProviderConnections.kt:340-345` — a 400/401 of the refresh **drops the credential** and the message names it (`data/ProviderAuth.kt:108-119`, the `lost` flag). Pinned by `ProviderConnectionsTest.a rejected refresh token names the credential it lost`. |
| **D3 (Low)** the repair created a second JSON reader and left `ProviderHttp.json` as dead production code | **confirmed & fixed** | `data/ProviderHttp.kt` — the copy is **deleted**; the class is the transport (`text`) only. `data/ProviderPayload.kt` is the one reader of the area (the depth bound, the balanced document, the single object at the root). The three test call sites of the deleted reader now use the one reader. Pinned by `ProviderConnectionsTest.the JSON reader rejects excessive nesting trailing payload and nonobject root` and `ProviderAuthTest.the payload reader keeps the bounds of the provider path`. |
| **D4 (Low)** the `busy` flag is a non-atomic read-modify-write outside the lock | **confirmed & fixed** | as N7 — `data/ProviderConnections.kt:173-179`; the error of a failed action is published under the same lock (`:179`). |
| **D5 (Low)** the durable entry point swallows every failure | **confirmed & fixed** | as B4. |
| **D6 (info)** the *file* layer of `SecureProviderStore` has no JVM test | **confirmed & fixed** | `data/SecureProviderStore.kt:169-201` — the record file is now a `File`-based layer (`SecretRecordFile`) with the same names on the disk (`.bin`, `.bin.bak`), the same recovery (a record whose base file was lost is restored from the backup) and the same size caps. It replaces `android.util.AtomicFile` for one reason: `AtomicFile` is a platform class that a JVM test cannot run, and the recovery of a half-written record is exactly what has to be guarded. Pinned by `SecureProviderStoreTest.a secret survives a round trip through the file layer`, `…a record whose base file was lost is restored from the backup`, `…an oversized record is refused without a cryptographic error`, `…a value that is too large is refused`, `…a removed record leaves no remnant on the disk`, `…a failed write keeps the previous record`. |
| **D7 (info)** the expired-pending filter is pinned only on the `beginSpotifyLogin` path | **confirmed & fixed** | `data/ProviderConnections.kt:125-135` — the filter also runs on the `load()` path. Pinned by `ProviderConnectionsTest.an expired pending login is dropped when the app starts`. |
| **D8 (info)** a pending login of a different Spotify application is silently dropped at load | **confirmed & recorded, deliberately kept** | `data/ProviderConnections.kt:127-135` — a pending login of another Spotify application **cannot** be finished: the client id of the login is bound to the application that started it (`data/AuthPkce.kt:36-47`). The record is rewritten without it. It is silent on purpose: the user has nothing to do about a login of an application that is not configured any more, and the "Начать заново" affordance is the way forward. The panel's `spotifyPending` state reflects the real state. |
| **R6 (info)** `get(provider, …)` treats every provider name other than `"spotify"` as Yandex | **confirmed & fixed** | `data/ProviderConnections.kt:396-401` — an unknown name is refused ("Неизвестное подключение") and **no** request is sent. Pinned by `ProviderConnectionsTest.an unknown provider is refused instead of being taken for Yandex`. |

### Reported by A6 to this area and fixed here

A6's report (§10) listed two AOSP-only defects **in the files of A5**
(the call sites of the code of that moment: `data/AuthPkce.kt:20,23,95` and
`data/ProviderConnections.kt:343,344`) — on AOSP
`JSONObject.optString(name)` answers the **text** `"null"` for a JSON null, so a
missing `refresh_token` could be read as the credential `"null"` and a missing
`uid` as the account id `"null"`.

* Fixed: the app-wide reader `data/JsonText.kt` (`optText`) is used in
  `data/AuthPkce.kt:18-31,40-45,98-102` and `data/ProviderConnections.kt:473-476`.
  A JSON null and a missing key are now the same thing on every platform.
* Pinned by `AuthPkceTest.a JSON null is not a value` and
  `ProviderConnectionsTest.a JSON null in the account is not shown as the text null`.

### Remarks that are **false positives** or that are refused

| remark | verdict |
|---|---|
| "The three changed existing assertions were judged CORRECT … the test **name** 'cancelled token exchange releases mutex busy flag…' is now stale." | The **name** was stale (the test does not exercise a mutex any more): it is now `a cancelled token exchange releases the busy flag and preserves the previous account`. The **assertions** are unchanged. |
| "The reviewers showed the seam is tested but the caller that destroys the record is not." | Confirmed — the seam (`SecureProviderStoreTest.an unreachable keystore never changes the alias`) tested the *cipher*, not the caller. The caller is now pinned (`ProviderConnectionsTest.an unreachable keystore never destroys the stored credential`). |
| "`ProviderHttp.json` … only the test that compares the two" | Confirmed, the reader is deleted. |

## 5. The tests of the area

| file | what it pins | fails on a revert? |
|---|---|---|
| `data/ProviderConnectionsTest.kt` | the store-level data loss (an unavailable store in front of `load()`), the removal of a damaged record, the inconclusive re-validation (500), the 403 verdict, the durable entry point on a fresh instance, the failed durable exchange, the client id in another case, the busy counter, the expired pending login on the `load()` path, the old single-object pending format end-to-end, the unknown provider, the JSON reader, the Spotify 400 message, the JSON null | yes — every one of them fails when the corresponding production change is reverted |
| `data/ProviderAuthTest.kt` | the credential decision table (including the restricted verdict), the "the credential was lost" message, the payload limit, the bounds of the JSON reader, one flight at a time, the cancelled waiter, the redacted flight key | yes |
| `data/SecureProviderStoreTest.kt` | the key policy, the round trip and the binding to its own name, the broken alias, the missing alias, the unreachable keystore, the raw crypto exception, the two typed failures, and the **file layer** (round trip, `.bak` recovery, the size caps, the removal, the failed write) | yes |
| `data/AuthPkceTest.kt` | the PKCE vector, the callback matching, the refusal as a typed outcome, the **bound** of the pending list, the percent-decoding of a query, the canonical client id, the message of a user-typed token, the JSON null, `isRedirect` | yes |
| `ui/ConnectionsControlsTest.kt` | the value is only dropped when the service accepted it, and the **delivery** of the OAuth callback (a source-level guard) | yes for the production part; the delivery guard is a source assertion and says so |

**No test was weakened or deleted.** The three assertions that the previous pass
had changed were kept as they are. The two call sites of the deleted
`ProviderHttp.json` were moved to the one reader, and the JSON reader test keeps
the same documents and the same assertions.

## 6. What was **not** fixed here, and why

| finding | why |
|---|---|
| **N4** the OAuth redirect scheme is claimable by another app (`AndroidManifest.xml:22-27`) | A dedicated HTTPS App Link needs a domain the project does not own, and `autoVerify` needs the association file on the domain. PKCE bounds the damage: the code cannot be exchanged without the verifier, which never leaves the app. Recorded as a design limitation (§7). |
| **D8** a pending login of another Spotify application is dropped silently at load | Deliberate: such a login cannot be finished, and the user has nothing to act on. The panel shows the real state and offers "Начать заново". |
| **P3-19** no `CertificatePinner` | A recorded decision (the three token-bearing origins rotate their chains). Not re-opened. |
| **P3-20** the Spotify import stores the candidate's metadata | A6/A7's files (`data/CatalogMatcher.kt`, `ui/MusicViewModel.kt`); reported again in §8. |
| **REG P2-04** the MediaSession trust predicate | Already satisfied (`player/PlaybackService.kt:158-162`). The citation in the area log was wrong and is corrected. |
| The `AtomicFile` of the platform is no longer used by this store | Not a finding but a decision, recorded in §7: the platform class cannot be exercised by a JVM test, and the crash-safe write is the behaviour that had to be guarded. The file names on the disk (`.bin`, `.bin.bak`) are unchanged, so an existing install keeps its records. |

## 7. Blockers and what is **not** verifiable in this environment

* **No device, no emulator, no APK** (forbidden by `android/AGENTS.md`). The
  **real Android Keystore** (a broken alias, the key rotation, the
  `KeyPermanentlyInvalidatedException` after a lock-screen change), the **real
  OAuth round trip** (the browser, the Spotify authorisation page), the **real
  provider endpoints** (`account/status`, the token endpoint) and the file layer
  **on a device** are **CANNOT VERIFY**. The Keystore is covered by the seam
  (`ProviderKeyStore`), the file layer by real files in a temporary folder, and
  the providers by `okhttp3.mockwebserver`.
* The **file layer** is exercised on the host JVM with real files; the Android
  filesystem (the `noBackupFilesDir`) and the `fsync` behaviour of a device are
  not.
* A **real** Yandex 401 is not known to be transient in practice; the
  re-validation makes that question harmless, but it does not answer it.
* The **delivery** of the OAuth callback is pinned on the source of the Activity
  (an Activity lifecycle cannot be observed from a JVM test). The model side of
  the same contract is pinned behaviourally.
* The A6 report lists `data/ProviderConnections.kt:343,344` and
  `data/AuthPkce.kt:20,23,95` (the call sites of the code of that moment) as
  AOSP-only `optString` defects; they are fixed
  here, but the *device* behaviour of the AOSP `org.json` is still not verified
  (the host's `org.json` is used by the JVM tests).

## 9. Commands and their actual output

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize
GRADLE_LOG=C:\Temp\nedotify-B3544D227D\gradle-…-….log
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 12s
TEST_SUMMARY files=82 tests=602 failures=0 errors=0 skipped=0
```

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:lintDebug'
> Task :app:lintDebug
BUILD SUCCESSFUL in 2m 26s
lint-results-debug.xml: 0 errors, 17 warnings, 0 hints
  (the 17 warnings are the build configuration — the Gradle/AGP versions, the
   dependency versions, the "newer version available" detector — the
   `ExportedService` of the manifest and one `UseKtx` in `LibraryRepository.kt`;
   **none** of them is in a file of A5)
```

```
> powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Tasks ':app:compileDebugKotlin'
BUILD SUCCESSFUL in 30s
```

The area's own tests: **88 / 0 / 0 / 0** — `ProviderConnectionsTest` 46,
`ProviderAuthTest` 10, `SecureProviderStoreTest` 14, `AuthPkceTest` 14,
`ConnectionsControlsTest` 4 (57 before this pass → 88 now: **31 new tests**).
The whole tree: **602 tests / 0 failures / 0 errors / 0 skipped** (the baseline
of the brief was 543; the growth is the parallel work of the other areas).

**A note on the runs.** The tree is shared with the parallel areas, and during
this pass the module did not compile several times because A3 and A4 were
mid-edit in their own files (`data/Preferences*.kt`, `data/DesktopRows.kt`,
`data/LibraryBackupStorage.kt`). The runs above are the ones made after the
tree compiled again; **no test of another area was changed, skipped or
disabled** to reach a green run.

## 10. Out-of-area findings (one line each)

* `data/CatalogMatcher.kt:66-76` (A6) + `ui/MusicViewModel.kt` (A7) — **P3-20**:
  the Spotify catalogue import stores the *candidate's* metadata and discards the
  parsed catalogue metadata; the Yandex path (`data/CatalogProviders.kt:102`) is
  the reference.
* `data/DesktopLibraryConverter.kt:17-18` and `data/DesktopLibraryImport.kt:35`
  (A4) — the other half of **P3-71**: the `origin` trigger is unreachable from
  the UI and the reachable `require` is swallowed by the catch-all.
* `data/MediaHttp.kt:154` (A2) — the redirect interceptor compares only the
  **host**, so a hop to another **port** of the same host keeps the
  `Authorization` header (OkHttp's rule is the whole origin).
* `ui/MusicViewModel.kt:469,471-474` (A7) — `handleSpotifyRedirect` refuses the
  callback in the offline mode (`requireOnline()`), so a login that arrives
  while the app is offline is spent without a word. The model keeps the pending
  login, so the user can start over, but the *browser tab* is lost.
* `android/PORTING_STATUS.md:89` (A8) — the text still says "AtomicFile в
  noBackupFilesDir"; the secure provider store now writes its records with its
  own `File`-based layer (`data/SecureProviderStore.kt:169-201`). The other
  `AtomicFile` use (`data/LocalAudioMetadata.kt:36-37`) is unchanged.
* `data/Sources.kt:21` (A2) — `SourceFailure` is the base of the two new typed
  failures of the area (`ProviderCredentialUnavailableException`,
  `ProviderCredentialCorruptException`); the file was not changed.
