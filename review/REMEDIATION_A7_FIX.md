# A7 — Compose, navigation, theming, accessibility, library UI

Two passes are recorded here.

* **Fix engineer pass, 2026-10-20.** Authority: `docs/audits/independent_verification`.
* **Verification & repair pass, 2026-10-20** (this file was rewritten by the
  verification engineer; the section “Verification pass” lists what was checked,
  what was wrong and what was changed).

No APK, assemble, bundle or package task was run in either pass. The
coordinator owns the serialized Gradle validation and the source-manifest
regeneration. This is a fix log, not an independent review.

## Navigation and saved state

* REG P2-37: `SaveableStateHolder` retains each route's query, sort, open
  dialogs and lazy-list scroll across the tab replacement
  (`ui/MusicApp.kt:44`, `ui/MusicApp.kt:83`). The saved back stack is bounded
  at 20 entries and keeps Home as its root (`ui/UiPolicies.kt:7`).
* MAIN-P2-37: Back pops the real opener of Settings/Profile, including nested
  Profile → Settings navigation (`ui/MusicApp.kt:56`, `ui/UiPolicies.kt:10`). A
  player sheet keeps its Material back path.
* MAIN-P2-38: cold and warm Spotify callbacks request Settings before exchanging
  the code, placing progress, state and retry controls at the visible
  destination (`ui/MusicViewModel.kt:468`, `ui/MusicApp.kt:46`).
* P3-02: music access has a rationale, a persisted already-requested flag and
  an Android settings action after a permanent denial
  (`MainActivity.kt:134`…`:148`). Granted access scans directly
  (`MainActivity.kt:150`).
* P3-01: avatar replacement/removal, local-file replacement, failed document
  import and library deletion release their now-unreferenced document grants
  (`ui/MusicViewModel.kt:releaseDocumentIfUnused`). Track URI/artwork and avatar
  references are checked first. Source files are never removed by grant cleanup.
  Pre-existing orphan grants are not bulk-revoked.

## Playback rendering

* MAIN-P2-41 / P3-46: only the timeline (`ui/PlayerScreen.kt:203`), the lyrics
  (`ui/PlayerScreen.kt:225`) and the mini player's progress bar
  (`ui/MiniPlayer.kt:33`) collect the position. The full player no longer
  reruns artwork, transport, queue and track lookup every poll. The
  current-track lookup uses a map remembered with the library
  (`ui/PlayerScreen.kt:37`).
* MAIN-P2-40: catalogue/progress, backup-preview and mini-player state are read
  by leaf hosts (`ui/MusicApp.kt:155`, `:173`). Track rows observe only whether
  their own id is active (`ui/MusicApp.kt:118`).
* REG P2-33: queue keys use track identity plus duplicate occurrence, retaining
  state when other tracks move, and disambiguating duplicate identities
  (`ui/PlayerScreen.kt:39`, `ui/UiPolicies.kt:46`).
* REG P2-35: queue rows render cover, artist and known duration
  (`ui/PlayerScreen.kt:70`…`:87`).
* P3-47: lyrics use a binary upper-bound lookup, handling duplicate timestamps,
  instead of scanning up to 20,000 rows on each position update
  (`ui/UiPolicies.kt:24`).
* P3-48: scrub and pending-seek state are saveable per track
  (`ui/PlayerScreen.kt:205`…`:207`).
* P3-49: a committed seek retains its requested thumb until acknowledgement
  (750 ms tolerance) or a bounded 2 s timeout (`ui/UiPolicies.kt:40`,
  `ui/PlayerScreen.kt:208`). A new track resets this state.
* P3-50: revisiting loaded/currently-loading lyrics retains them; the lyrics
  come from the in-memory LRU or the disk cache
  (`ui/MusicViewModel.kt:loadLyricsInternal`). Explicit retry still refreshes;
  empty, error and not-found outcomes may retry.
* P3-51: repeat cycles OFF → ALL → ONE → OFF, matching the desktop
  (`ui/UiPolicies.kt:33`).
* P3-52: queue clearing keeps the current item/position/play state by removing
  only the other items (`ui/MusicViewModel.kt:clearQueue`,
  `ui/PlayerScreen.kt:66`). The player error is visible as a polite live region
  (`ui/PlayerScreen.kt:91`).
* P3-103 / P3-104: `NowPlaying` carries an error dimension
  (`ui/MusicViewModel.kt:34`), `update()` copies it
  (`ui/MusicViewModel.kt:211`) and the player screen renders it
  (`ui/PlayerScreen.kt:91`).
* REG P2-65 (the UI side, handed over by A1's remark N2/R1): a transport or
  queue command no longer drops the pending preparation; only a new selection
  (or an explicit invalidation) does. See the verification pass.

## Library, backup and discovery integration

* REG P2-31: a confirmed action removes a library track, its dependencies,
  history and the queue entries (`ui/MusicViewModel.kt:327`…`:339`); the
  confirmation names the affected data and preserves the source audio
  (`ui/MusicApp.kt:143`). Search-only rows do not offer removal. Queue
  preparation stages the result list instead of permanently inserting every
  result.
* REG P2-32: a new search keeps the previous results visible while loading or
  failing, so the list does not collapse on every keystroke
  (`ui/MusicViewModel.kt:search`, `ui/SearchScreen.kt:85`).
* P3-53 / MAIN-P2-34: only smart rows tagged with the selected playlist id
  render (`ui/LibraryScreen.kt:39`).
* P3-55 / REG P2-63: the completed-track count uses the completed bytes; the
  other partial download rows get a separate count/bytes label
  (`ui/LibraryScreen.kt:74`, `:75`).
* P3-63: Profile observes Room's favourite-count aggregate instead of reading
  every Track to calculate one integer (`ui/MusicViewModel.kt:68`,
  `ui/ProfileScreen.kt:33`). The unused listened flow was removed (P3-38).
* P3-73: the pending backup state retains the `BackupPreview` counts and a
  staged-file capability, never the whole model during the preview
  (`ui/MusicViewModel.kt:116`). Dismiss/replacement/ViewModel clearing
  schedules the private-preview disposal (`ui/MusicViewModel.kt:discardPendingBackup`).
* MAIN-P2-35 integration: a successful export reports the omitted older
  sessions when the export budget applies and explicitly keeps the device
  history untouched (`ui/MusicViewModel.kt:exportBackup`).
* REG P2-21 integration: the desktop picker accepts the DB and the optional
  matching WAL together (`MainActivity.kt:76`).
* P3-72: the JSON picker no longer explicitly admits arbitrary `text/plain`;
  the octet-stream compatibility MIME remains (`MainActivity.kt:162`).
* P3-75: `remove_failed` offers a retry of the removal instead of a
  re-download (`ui/DownloadsScreen.kt:40`).
* REG P2-74 / P3-54/57: the ViewModel uses A6's application-owned, journalled
  import controller. Dismissing/changing the catalogue leaves it running;
  offline and logout pause it. The catalogue and Settings both expose
  pause/resume/finish, so process-restored work remains reachable. A second
  import explains the outstanding request. Single-track preparation uses busy
  rather than falsely presenting background-import controls. Search completion
  sets `hasSearched` (`ui/MusicViewModel.kt:503`, `:521`).

## Theme, accessibility, profile and settings

* REG P2-36: all ordinary Material roles have deliberate primary/secondary/
  tertiary containers, matching text, surface variants/container levels, errors
  and inverses (`ui/Design.kt:neDotifyColorScheme`). Dialogs/chips/sliders no
  longer inherit the baseline purple roles.
* P3-40: artwork admits HTTPS and Android content only; filesystem, cleartext,
  executable/data and malformed URI schemes are rejected
  (`ui/UiPolicies.kt:13`, `ui/Design.kt:116`). Offline never sends remote
  artwork. The explicit error painter keeps the fallback icon visible.
* P3-35/64: the profile avatar removal is reachable and uses grant cleanup. A
  failed avatar uses an explicit transparent error painter above the Person
  fallback (`ui/ProfileScreen.kt:47`…`:52`).
* P3-39: the nested playlist-icon glass became an ordinary themed container
  (`ui/LibraryScreen.kt:167`); the glass blur radius is 14 dp
  (`ui/Design.kt:73`). The actual device render cost remains unmeasured.
* P3-41: the greeting updates every minute while STARTED, and immediately on
  resume, in a leaf composition (`ui/HomeScreen.kt:73`).
* P3-42: ArrowBack/ArrowForward use the auto-mirrored variants
  (`ui/BrowseComponents.kt:43`, `ui/SearchScreen.kt:117`). `Sort` stays
  unchanged because the verification refuted the claim that it is directional.
* P3-43: the tab icons are decorative beside the spoken text label
  (`ui/MusicApp.kt:75`).
* P3-44 integration: the initial recommendation loading uses A6's idempotent
  method; an explicit refresh alone invokes `refreshRecommendations`
  (`ui/HomeScreen.kt:23`).
* P3-45: the launch background has day/night resources
  (`res/values/colors.xml`, `res/values-night/colors.xml`). The manual
  persisted theme before the first Compose frame remains a device
  visual-acceptance check.
* P3-58: the displayed EQ gain and the sliders clamp to the audio device's
  band range; the presets save within that range. The hardware interpolation
  stays in the service (`ui/AudioSettingsControls.kt:67`…`:85` — another area's
  file, verified only).
* P3-61: `ProfileDraft` filters the controls, the model/write errors stay
  inside the dialog, and only a successful persistence closes it. Busy
  dismissal/repeated submit are disabled; a failed write retains the text
  (`ui/ProfileScreen.kt:146`…`:161`).
* A3/P1-02 integration: the ViewModel forwards the durable reset state; a
  separate snackbar effect acknowledges it after the display. Other messages
  cannot replace it (`ui/MusicApp.kt:181`).
* P3-60/62: A3's optimistic-draft rollback and the leaf download subscription
  remain. Settings also qualifies Android's evictable lyrics cache (P3-81
  integration).
* P3-66…70 / MAIN-P2-42 were already repaired in A5. Its password semantics,
  visibility toggle, live-region errors, retained failed draft and explicit
  pending-login cancellation were inspected and retained.

## Findings whose repair belongs to another area

* MAIN-P2-39 / P3-37 are design/refactor recommendations, not contract defects;
  a wholesale visual/track-row rewrite was not represented as a bug fix.
* P3-07/08 and MAIN-P2-45 are service scheduling/queue refresh/history-write
  findings; the root area reports the event-driven idle polling and the
  guarded history writes. The UI side of P3-08 is bounded: the download rows
  observe a de-duplicated state map (`ui/MusicViewModel.kt:60`), not the byte
  progress.
* P3-11 is conditional and lacks the proposed contract; the wrap-to-head
  behaviour was not changed without such a contract.
* P3-13 / MAIN-P2-44 (SQL order), P3-14 (identity), P3-25 / MAIN-P2-43
  (search album) are A4 data changes.
* P3-19 certificate pinning is A5's recorded hardening recommendation.
  P3-20 catalogue metadata preservation belongs to A6's `CatalogMatcher`.
* P3-54/57 and REG P2-74 (cap, empty wording, import lifetime) belong to A6's
  catalogue controller/browser and are integrated by A7.

---

# Verification pass (2026-10-20)

The verification engineer re-read the whole area (not only the new files),
checked the contract ids one by one, ran the JVM suite, the lint task and the
instrumented-test compile, and repaired what did not hold.

## Commands and their real output

| Command | Result |
|---|---|
| `powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize` | `TEST_SUMMARY files=80 tests=543 failures=0 errors=0 skipped=0` — **BUILD SUCCESSFUL** |
| `… -Tasks ':app:compileDebugAndroidTestKotlin'` | **BUILD SUCCESSFUL** (only the pre-existing `createComposeRule` v1 deprecation warnings) |
| `… -Tasks ':app:lintDebug'` | **BUILD SUCCESSFUL**, 18 issues (17 warnings, 1 hint), **none** in `ui/**` or `MainActivity.kt` |

The baseline before this area was 511 tests / 0 failures; the suite is now
543 / 0 / 0. The dependency-verification metadata that used to break
`:app:lintDebug` before lint ran has been repaired by another area — lint
really ran here, no `--dependency-verification=off` was needed.

## What was repaired in this pass

1. **The transport commands still dropped the pending preparation (REG P2-65,
   UI side).** A1 closed the service side; the UI side was untouched: all eight
   transport/queue commands began with `cancelPendingPlayback()`, so a tap on a
   catalogue track followed by a transport press silently discarded the tapped
   track and resumed the previous one. Now one place decides:
   `ui/PlaybackCommands.kt:35` dispatches every command by kind, and only
   `SELECT` and `INVALIDATE` drop the preparation (`ui/MusicViewModel.kt:281`,
   `:294`…`:312`). The semantic choice is the one the register prescribes:
   the pending selection wins; a transport command issued during a preparation
   acts on the queue that is running and the prepared queue is then installed.
   Pinned by `ui/PlaybackCommandsTest.kt` (5 tests) — a reverted policy fails
   them.
2. **The mini player collected the position at the top of its composition**
   (`ui/MiniPlayer.kt:20` before the change). MAIN-P2-41 cites that line, and
   the fix report's claim that “only timeline and lyrics leaves collect
   position” was therefore overstated. The bar no longer collects anything;
   only its progress bar does (`ui/MiniPlayer.kt:33`, `:63`).
3. **The refusal of the interruption gate was invisible on the player screen.**
   A refused play is a *command* failure: it never becomes a media error, so
   `now.error` stayed empty and the only carrier was the snackbar — which lives
   in a separate `ModalBottomSheet` window. The refusal is now player state:
   `ui/PlayerErrorState.kt` merges the media error and the refused command,
   `ui/MusicViewModel.kt:149` records the refusal and `ui/PlayerScreen.kt:91`
   renders it as a polite live region. The snackbar host inside the sheet
   (`ui/MusicApp.kt:98`) was already there and is kept.
4. **The library write of the preparation was unfenced.** `ui/MusicViewModel.kt`
   called `repository.dao.remember(track)` directly, so the row of a track the
   user had just deleted could be written back. The call site now uses the
   repository API, which A4 has since fenced
   (`data/LibraryRepository.kt:201` → `data/LibraryWrites.kt:36`), and the
   delete waits for the in-flight preparation to release the library before it
   removes the row (`ui/MusicViewModel.kt:332`,
   `ui/PlaybackPreparation.kt:cancelAndAwait`). Pinned by
   `ui/PlaybackPreparationTest.kt` (“the cancelled preparation releases the
   library before the delete runs”).
5. **The theme test did not pin REG P2-36.** The contrast check passes for the
   Material baseline as well, so the test would have stayed green with the
   baseline purple chips and dialogs. The new test compares the container roles
   with `lightColorScheme()` / `darkColorScheme()` from the library itself
   (`ui/UiPoliciesTest.kt`, “the container roles are chosen and not the material
   baseline”).
6. **The greeting test pinned almost nothing** (it only compared two equal
   hours). It now pins the three windows and their boundaries.
7. **Lint noise in the area's own files**: the back stack was a
   `MutableState<ArrayList<String>>` (lint `MutableCollectionMutableState`) and
   `MainActivity` used `Uri.parse`/`edit()` instead of the KTX extensions. Both
   are clean now (`ui/MusicApp.kt:42`, `MainActivity.kt:48`, `:142`).

## What the verification confirmed (no change needed)

* MAIN-P2-37 / REG P2-37 — back stack and `SaveableStateHolder`
  (`ui/MusicApp.kt:42`…`:56`, `:83`).
* MAIN-P2-40 — the catalogue flow is read by a leaf host
  (`ui/MusicApp.kt:173`).
* MAIN-P2-41 / P3-46 — position and current-track lookup
  (`ui/PlayerScreen.kt:37`, `:203`, `:225`).
* REG P2-34 / P2-35 — the reused queue rows and the richer queue row
  (`ui/MusicViewModel.kt:queueRows`, `ui/NowPlayingQueue.kt:9`,
  `ui/PlayerScreen.kt:70`…`:87`).
* P3-47…P3-53 — the lyric lookup, the scrub, the seek, the lyrics retention,
  the repeat order, the queue clearing and the smart rows
  (`ui/UiPolicies.kt:24`, `:33`, `:40`; `ui/PlayerScreen.kt:66`, `:91`,
  `:205`…`:213`; `ui/LibraryScreen.kt:39`).
* P3-36 / P3-38 — the unused `listened` flow is gone; the saveable states are
  saveable (`ui/LibraryScreen.kt:40`…`:46`, `ui/SearchScreen.kt:31`…`:34`).
* P3-41 / P3-45 — the greeting ticker and the night resources
  (`ui/HomeScreen.kt:73`, `res/values-night/colors.xml`).
* P3-42 / P3-43 — exactly two auto-mirrored icons, decorative tab icons
  (`ui/BrowseComponents.kt:43`, `ui/SearchScreen.kt:117`, `ui/MusicApp.kt:75`).
* P3-39 / P3-37 — the nested glass is gone and the blur radius is 14 dp; the
  “18 surfaces” figure and the “4 row components” figure are **not**
  reproducible and were not “fixed”
  (`ui/Design.kt:73`, `ui/LibraryScreen.kt:167`).
* P3-01 / P3-02 — the grant release and the permission rationale
  (`ui/MusicViewModel.kt:releaseDocumentIfUnused`, `MainActivity.kt:134`…`:148`).
* P3-57 — the `hasSearched` flag exists and the ViewModel sets it
  (`ui/MusicViewModel.kt:503`, `:521`; the rendering is A6's
  `ui/CatalogBrowser.kt:81`).
* P3-13 / MAIN-P2-44 and P3-25 / MAIN-P2-43 — the SQL and the search
  projection are A4's; the UI calls `repository.search(...)` and nothing else.
  The library/import API the UI uses is the current one
  (`repository.remember`, `repository.deleteTrack`, `repository.backups.*`,
  `repository.desktopImport.inspectDocuments`) and the area compiles against
  it.

## Still open (reported, not claimed as fixed)

* **The lyrics tab flashes “Текст не найден” for one frame** when the user
  opens the tab for a track whose lyrics were never fetched: the reset state
  and the genuine “not found” outcome have the same shape
  (`ui/MusicViewModel.kt:195`, `ui/PlayerScreen.kt:164`). The contract's
  artefact — the spinner flash for lyrics that *are* available — is gone. The
  remaining flash needs a new “no outcome yet” dimension in `LyricsState`,
  which would change the constructor the instrumented tests use; it was not
  done blind.
* **REG P2-11 / the queue commands**: the queue commands act on the queue that
  is running while a preparation is pending, so a queue edit made during a
  preparation is overwritten when the prepared queue is installed. That is the
  register's prescribed semantic, not an accident.

## Still requiring a device

No device, no emulator and no APK: the instrumented tests compile but were not
run. The following are therefore **not** verified here and must not be read as
verified:

* the actual MediaSession commands and the binder seek timing;
* the SAF grant lifecycle and the provider persistence;
* the OAuth destination and the pre-first-frame theme;
* TalkBack, the large font, RTL and the system file pickers;
* the real frame cost of the queue and of the glass surfaces;
* `androidTest/.../ui/NavigationTest.kt` (typed-query preservation across the
  Settings Back and the tab replacement).

## Test quality of this area

| File | What it pins |
|---|---|
| `ui/UiPoliciesTest.kt` | the back stack, the artwork admission, the binary lyric lookup (with a read counter at 20 000 lines), the repeat order, the seek acknowledgement, the queue identity, the greeting windows, the play/pause label, the theme roles against the Material baseline and the contrast of the app's own surfaces |
| `ui/PlaybackCommandsTest.kt` | the command dispatch: a transport or queue command does not drop the pending selection, a new selection and an invalidation do |
| `ui/PlayerErrorStateTest.kt` | the refused command is shown on the player screen, the media error wins, the refusal leaves with its track and with the retry |
| `ui/PlaybackPreparationTest.kt` | the cancelled preparation releases the library before the delete runs; the previous cancellation invariants |
| `ui/NowPlayingQueueTest.kt` | the queue rows keep their instance, so the mirror comparison stays cheap |
| `ui/PlaybackErrorsTest.kt` | the failure text survives the wrapping; a session error without a message never clears the channel |
| `ui/MediaControllerConnectorTest.kt` | a disconnected controller is rebuilt |
| `ui/ConnectionsControlsTest.kt` | A5's component (not this area's) |

Every new test fails when its production code is reverted; none of them reads
the Kotlin source or asserts a bare string constant.
