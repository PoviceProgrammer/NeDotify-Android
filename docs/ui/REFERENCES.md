# NeDotify UI references

Recorded on 2026-11-05 (Europe/Moscow). References supply design ideas; the
prototype's geometric landscape artwork is original inline SVG. No remote covers,
fonts, scripts, trackers, private profile data or source code from these apps is
loaded by the prototype.

| Source | Revision / snapshot | Observed design idea | NeDotify use |
|---|---|---|---|
| [Riff](https://github.com/rootscripts/riff) | `9e28e0c0e095e83dddfc7b71dca977c77851e29d` | Artwork-led mood, album grids, visual emphasis on music | A hero, atmosphere and artwork-first player |
| [Auxio](https://github.com/OxygenCobalt/Auxio) | `f243bceab0b55fdae7462723a3d14e7eec21a432` | Legible collection categories, large transport, queue access | B collection hierarchy and player next-up area |
| [InnerTune](https://github.com/z-huang/InnerTune) | `bfba5ecb16795cfc93ecce73d94ca583bcec917c` | Quick picks, rows plus artwork, grouped search | C home, shared search and compact playback |
| [Muriel mobile](https://github.com/cloverscripts/riff-muriel/tree/main/mobile) | `2df82f518627171523cec84ee56d76e04c886aea` | Mini-player → full player and mobile-safe layout | Shared mini/full-player relationship; Android implementation stays native |
| [Spotify adaptive tablet announcement](https://newsroom.spotify.com/2026-04-16/new-tablet-app-experience/) | Page snapshot date 2026-11-05; publication 2026-04-16 | Parallel browsing and now-playing surface | Wide layout with an adjacent current-player pane |
| [Material 3 Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) | Documentation accessed in planning, reference date 2026-11-05 | Semantic colors, typography and component states | Shared token model and native implementation target |
| Existing NeDotify `design/reference.png` | Preserved existing file, unchanged | Navy base, blue accent, NeDotify identity | All variants |

Repository revision IDs were retrieved from each primary GitHub repository's
commit endpoint. They pin the inspected reference, not a dependency. The browser
prototype is a local experiment and does not execute code from these repositories.

## Token and rendering contract

`design/design-tokens.json` is the single editable source. Generated CSS and Kotlin
must match it. The prototype uses semantic color variables and shared dimensions.
Decorative cover colors are artwork, not alternate product theme tokens.

At this stage browser typography uses the available system sans-serif. A native
font/render match has not been measured. The handover requires the same text,
fixture data, window width, theme and selected font; solid colors match exactly,
key geometry tolerance is 2 dp, and intentional renderer differences are recorded.

Native screenshot capture and TalkBack evidence remain NOT_RUN while APK building
is prohibited. A rendered browser view must never be recorded as Android evidence.
