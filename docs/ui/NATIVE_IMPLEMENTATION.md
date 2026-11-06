# Native UI — selected direction B

The user selected B (collection and speed). The recorded clarity/identity ratings
were A 3/5, B 4/5 and C 4/5: clarity 3/4/4, identity 5/5/5. They are not substituted
for task timings or native accessibility evidence. Browser prototype code is kept
separate from the Android implementation.

## Implemented source changes

- `NeDotifyTheme` uses generated `DesignTokens` for both neutral palettes, default
  accent, error colors and typography roles. Explicit existing accent preferences
  and Dynamic Color remain available.
- Surfaces default to opaque, quiet B-style cards. Frosted rendering is opt-in at
  the mini-player and observes API/power-save/user preferences. The global
  background is a subtle static accent wash rather than animated decoration.
- Home leads with the real collection count and direct collection action, then
  favorites/download/local shortcuts, recent tracks, personal Flow and playlists.
  Empty collection offers search and import. No charts or subscriptions are added.
- Track rows have a pure `TrackRowContent(state, actions)` interface. The narrow
  route adapter observes library, download and playback state. Existing favorite,
  playlist, offline download, file relink and delete actions remain reachable.
- Search uses its feature ViewModel's saved query/source/category/group and
  visibility lifecycle. Partial-source warnings are nonblocking alongside results.
- Library uses saved filter/playlist/sort state and typed shell callbacks. Shortcut
  tiles stack on narrow/large-text layouts. A removed selected playlist has explicit
  recovery, after the first playlist DAO emission confirms that it is missing.
- Full player follows B: compact artwork, live track heading and timeline, next-up
  rows in the main player, separate queue/lyrics tabs and pinned transport. Queue
  actions operate on occurrence indices and preserve accessible move actions.
- Mini-player and timeline observe position at the leaf; artwork and entire screens
  do not collect the 500 ms position stream. Buffering remains pausable.
- Profile, downloads, settings, connections, catalog import and transfer controls
  use narrow feature owners. Existing actual-data statistics and recovery flows
  remain reachable.

## State interfaces

Routes receive only their relevant feature ViewModels plus `PlaybackConnection`.
No renderer refers to `MusicViewModel` or reads the repository directly. The
application shell owns typed routes and adaptive navigation; the media service
owns the runtime queue and decoding. Detailed state lifecycle belongs to Core's
architecture documentation and tests.

## Evidence limits

These are source changes, not Android visual or audio acceptance. Compile/lint/unit
results are recorded by the root validation run. The browser screenshot inspection
does not establish Compose dimensions, native text layout, TalkBack, blur or audio.
All native screenshot comparisons and device rows remain NOT_RUN until an APK is
permitted and the actual Android checks are executed.

## Candidate native adaptations to review on device

These are explicit implementation differences, not a passed visual comparison:

| Area | Browser B | Native source | Reason / remaining check |
|---|---|---|---|
| Player artwork | 190 CSS px, 175 on short player | Maximum 190 dp; reduced using available content height, 148 dp beside heading in wide landscape | Keep transport reachable on short/large-text windows; measure D19/D21 |
| Player navigation | Tabs below scrollable content | Tabs above content, transport pinned below | Stable access while browsing long queue/text; evaluate native gestures/focus |
| Queue controls | Visible move buttons plus remove | Menu with accessible move up/down/remove actions | Avoid squeezing title at 320 dp and 200% text; verify TalkBack ordering |
| Main text | Browser system sans-serif | Android sans-serif and generated role sizes | Renderer/font differences need native screenshots with fixed font/scale |
| Decorations | Original SVG landscape fixtures | Real source artwork; neutral musical-note fallback | No fabricated artwork assigned to real tracks; inspect missing-cover states |
| Large window | CSS container preview | Shell rail/persistent pane by actual window constraints | Verify actual system insets and split-screen window sizing |

The U owner proposes these adaptations. They remain candidates until D19–D21
are executed; choosing B does not certify unmeasured pixel or accessibility parity.
