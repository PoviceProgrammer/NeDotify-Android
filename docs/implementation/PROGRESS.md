# Android implementation — 2026-11-05–06

## Baseline and authorization

- Standalone repository: https://github.com/PoviceProgrammer/NeDotify-Android
- Baseline: `0b10607ddb2b9cbce215430f0c48b4928f0abdb7`.
- User selected Riff + Android, UI + backend audit, browser prototype first.
- APK packaging and device tasks remain forbidden. No application data is touched.
- User explicitly authorized public visibility after the publication audit. Main now requires `android/source`, strict up-to-date checks, administrator enforcement, and rejects force pushes/deletions.
- Baseline source run: compileDebugKotlin, compileReleaseKotlin, testDebugUnitTest, lintDebug, compileDebugAndroidTestKotlin; 49 tasks executed, 604 tests, 0 failures/errors/skips. Instrumentation compiled only. Historical reports are not evidence for subsequent changes.
- Actual Room schema is **v4**, with 1→2→3→4 migrations. Older v3 descriptions are historical.

## Work and gates

| Package | Owner | Status | Dependency / exit |
|---|---|---|---|
| W0 Git/baseline/CI | L + V | IN_PROGRESS | Remote created/protected; current branch must pass cloud CI |
| W1 prototype | U | IMPLEMENTED | A/B/C share fixtures; preview baselines in design/baselines |
| W2 audit | C | INITIAL_PASS_DONE | CODE/TEST/DEVICE inventory and severity triage in docs/audit; runtime checks remain pending |
| W3 validation | V | IMPLEMENTED | Token generator, source gate, isolated fixtures/runner, D01–D22; PS5/PS7 infrastructure checks passed |
| W4 design/contracts | L + PO | SELECTED | PO chose B; clarity/identity A 3/5, B 4/5, C 4/5. Measured timings and full weighted score remain pending |
| W5 state separation | C | IMPLEMENTED_SOURCE | Nine narrow feature owners; MusicViewModel removed; connection recovery and full gate tracked in audit/review |
| W6 Compose foundation | U | IMPLEMENTED_SOURCE | Generated palettes, type roles and dimensions consumed by native theme |
| W7 navigation/vertical slice | L | IMPLEMENTED_SOURCE | Typed independent stacks/adaptive shell wired; initial main Kotlin compilation passed |
| W8 screens | U | IMPLEMENTED_SOURCE | Variant B screens use real feature state; actual Android rendering remains NOT_RUN |
| W9 confirmed fixes | C | SEE_AUDIT | Defects require observed red/green regression, then matching final source gate |
| W10 regression | V + L | SEE_REVIEW | Exact source identity, task results and counts belong to review/VALIDATION.md |
| W11 device | V | BLOCKED | APK authorization and device; actual audio evidence |

G0/G3 decisions must use the matching local source report and required GitHub `android/source` check, not infer success from this implementation inventory. PO selected B and authorized native implementation; full quantitative G1 evaluation remains incomplete and is not represented as a measured win. G2 source ownership is implemented; Android restoration/resource proof remains pending. Current execution gates are recorded separately in review/implementation/GATES.md. G4/G5 are blocked by APK prohibition and unexecuted device acceptance.

Engineering estimate remains 158–254 h plus 20% reserve (190–305 h). Parallel critical path: W0 → W1 → W4 → W5/W6 → W7/W8 → W10 → W11. External waiting is excluded.

Owners: L main integrator; U prototype/UI; C core and regression fixes; V validation code/CI; PO user design decisions and required device/authorization actions. Authored reports are not independent reviews.
