# NeDotify Android UI prototype — W1

Status: **B selected by the user; preserved for comparison**. This is throwaway
browser code. It is not included in the Android app and must not be promoted to a
WebView implementation.

## Open

Double-click `index.html`. No server, install step, network, account or API key is
required. Add `?variant=A`, `?variant=B` or `?variant=C` to compare shareable states.
The selected variant survives a reload through that URL parameter only. All other
state is held in memory and resets on reload.

External lab controls change the fixture scenario, simulated window width,
light/dark theme and large text. The floating variant bar is a prototype control
outside the product surface. Left/right arrows switch variants except while an
input/select is focused. Escape closes the current sheet, player or nested view.

## The design question

Which information hierarchy makes NeDotify easiest to understand and quickest to
play, while keeping its Riff-inspired atmosphere?

| Variant | Home | Player |
|---|---|---|
| A — Artwork and mood | Large atmosphere card, mood playlists, recent tracks | Artwork-led player, dedicated queue and lyrics panels |
| B — Collection and speed | Collection command, local/download/favorite shortcuts, dense recent list | Smaller artwork, next tracks visible in the player |
| C — Quick choice | Prominent search, compact quick-pick tiles, immediate tracks | Record-like artwork and centered playback information |

All three render the same in-memory fixture collection. Switching variants does
not silently replace the data or choose a winner.

## Connected demonstration flows

- Home → track → mini player → full player → queue/lyrics.
- Search → query/source/category → track or grouped artist/album results.
- Library → playlist → play, track menu, favorites and add to playlist.
- Downloads → pause/resume → manually complete a simulated download.
- Profile → period selection → history/library → settings.
- Settings → theme/effects toggles, audio panel, service connection states,
  library-transfer preview and privacy panel.
- New playlist form creates a new in-memory playlist.
- Queue supports selection, move up/down, removal and clear-to-current.

Playback position is a timer. No audio is played. Network, file selection, OAuth,
database import, audio effects and actual downloads are explanatory mock actions.
They do not access or modify the user's library. Some secondary actions use a
clearly worded demonstration message rather than a complete editor; see the
coverage note below. This prototype is evidence about design, not Android runtime.

## Scenario controls

`populated`, `empty`, `loading`, `error`, `partial`, `offline`, and `long` provide
repeatable demonstration states. Empty collection is applied on the home/library;
offline limits search to local/completed fixture tracks. Source-error and partial
results display a retry action. The loader has a manual completion action to make
the state easy to inspect.

The viewport choices are 320, 360, 412 and 840 dp represented as CSS pixels. The
840 mode shows a parallel player pane. It is a layout preview, not proof of native
window sizing, text shaping, system insets or TalkBack support.

## Compare

Use the same fixture scenario and task for each variant. Start the external task
timer immediately before the task. The next Play command records elapsed UI time
and number of product actions. This does not measure network/audio startup.

The evaluation sheet, scoring anchors and pending decisions are in
`docs/ui/EVALUATION.md`. No score is pre-filled on behalf of the user.

## Tokens

`design/design-tokens.json` is the editable token source. `tokens.css` is generated
by `tools/generate-design-tokens.ps1`; use its `-Check` mode in source CI. No remote
font is loaded: the current browser system font is used in this experiment. Native
font/render comparisons remain pending until an Android build is permitted.

## Coverage limits before G1

The dominant screen layouts and journeys are represented. OAuth entry, file
pickers, EQ processing, backup writes, playlist rename/delete and profile editing
are explanatory demonstration actions. Their native implementations and acceptance
tests remain in the implementation plan; these placeholder interactions must not
be counted as production feature completion.

There are no automated tests for this throwaway UI. Root performs the manual
browser walkthrough and records findings separately. Prototype capture on a
throwaway branch is performed after the user chooses a design; no choice or
production migration is implied by the existence of these files.
