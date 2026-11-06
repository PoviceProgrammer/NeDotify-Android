# W1 / G1 — design evaluation

Status: **B SELECTED BY PO**. The user explicitly chose B (collection and speed).
Reported clarity/identity ratings: A = 3/5, B = 4/5, C = 4/5. In the two-criterion
question this means clarity A=3, B=4, C=4; identity A=5, B=5, C=5. No task timing,
accessibility score or weighted total is inferred from them. U implements B; L
retains the remaining measurement obligations.

Question: which of A/B/C best combines quick music access, understandable first
screen, NeDotify identity, native feasibility and accessibility?

## Repeatable tasks

Start from the populated home, dark theme, 412 dp, normal text, paused player.
Reload between comparison rounds to restore identical fixture data. Rotate task
order for subsequent rounds to reduce learning effects.

| Task | Start | Expected completion | Limit |
|---|---|---|---|
| T1 | Home | Play a recent track | At most two product taps |
| T2 | Home | Find and play “Тихий берег” | Search is directly discoverable; record time/taps |
| T3 | Home | Open the downloaded playlist “Всегда с собой” and play it | Library/downloads are directly discoverable |
| T4 | Full player | Open queue and move the second entry down | Action is visible and does not start the wrong track |
| T5 | Full player | Open lyrics, select a line, return to playback | Selected panel and return path are clear |

The timer measures task start → Play command. Report it as **UI selection time**.
The prototype cannot measure audible playback startup, MediaSession, HLS decoding,
background lifetime or native accessibility.

## Score

Each criterion is rated 1–5. Weighted score = sum(rating / 5 × weight).

| Criterion | Weight | 1 | 3 | 5 |
|---|---:|---|---|---|
| Playback access | 25 | Task fails or requires explanation | Task completed with detour | All target paths direct; T1 within two taps |
| First-screen clarity | 25 | Search/collection/current track unclear | Discoverable after inspection | All three found without explanation |
| NeDotify identity | 20 | User sees a generic/unrelated player | Some intended character | User recognizes the intended palette, artwork and musical atmosphere |
| Implementation cost | 15 | Requires >1.75× baseline UI budget | ≤1.5× baseline | Fits baseline native UI budget |
| Accessibility | 15 | Core action blocked | Issues have simple corrections | Reachable focus, readable contrast, 48 dp targets, large-text paths usable |

Native feasibility must cover API 26 and actual available data. Source, user
library and runtime functionality are identical between designs. No view is allowed
to rely on a new paid service or an unimplemented product capability to look good.

Hard gates: T1 within two taps; visible search/library; core actions reachable at
large text; native API 26 feasibility; coverage of the existing feature map. A
failed hard gate is corrected before a design can win.

## Measurements — pending measured task evaluation

| Variant | T1 time/taps | T2 time/taps | T3 time/taps | Clarity 1–5 | Identity 1–5 | Cost 1–5 | Accessibility 1–5 | Weighted total |
|---|---|---|---|---|---|---|---|---|
| A | PENDING | PENDING | PENDING | 3 | 5 | PENDING | PENDING | PENDING |
| B | PENDING | PENDING | PENDING | 4 | 5 | PENDING | PENDING | PENDING |
| C | PENDING | PENDING | PENDING | 4 | 5 | PENDING | PENDING | PENDING |

Decision rule: highest admitted weighted score. If difference is no more than five
points, choose the lower implementation cost; then the faster UI task time. A is
an initial hypothesis, not an automatic default winner. A one-person walkthrough
is formative feedback, not a statistically validated usability study.

## Decision record

Browser reference captures are listed in [design/baselines](../../design/baselines/README.md).
They remain browser evidence; the native comparison is a separate device gate.

- Selected variant: B — explicit user choice, reported by L on 2026-11-05
- Accepted direction: collection-first B, compact player with next-up preview, shared token palette.
- Source token SHA-256: d1267ec308dc737b31b5adceaecede46033627ceb29c89a666aa355d1af06a10
- Native implementation may start: YES — explicit PO selection. Timed/accessibility observations remain unmeasured; device gate remains separate.
- Browser walkthrough evidence: design/baselines/README.md (synthetic fixtures; no Android result)
- Native/device evidence: NOT_RUN (APK restriction)

After selection, freeze screenshots, dimensions, fixture data, theme and font;
record the source SHA. Preserve the experiment in a throwaway branch and migrate
the selected design deliberately to native Compose.
