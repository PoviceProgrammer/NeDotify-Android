# Execution gates — 2026-11-06

Source tree digest: a45c8d94c420e2f110693d0f16a25bd8dc3cea0ee3e6d89992e2fdc3658faaaa
Source manifest SHA-256: aae2386f334652763894b8842421e0b8375b48bb50d099855b1ec03e590f6273; 325 files.

| Gate | Evidence / remaining work |
|---|---|
| G0 | Public standalone repository; protected main requires android/source. Current PR cloud result is authoritative. |
| G1 | PO selected B; clarity 3/4/4 and identity 5/5/5. Browser baselines/tokens frozen; full quantitative comparison and native parity remain pending. |
| G2 | PASS_SOURCE_ONLY: aggregate VM removed; nine feature owners and shared controller; behavior regressions passed. Android lifecycle/process-death proof NOT_RUN. |
| G3 | PASS_LOCAL: five explicit tasks, 633 JVM tests, zero failures/errors/skips; lint 0 errors/2 existing warnings; 79 instrumentation definitions compiled, not executed. |
| APK | PACKAGED: user authorized manual debug testing on 2026-11-06; signature, minSdk 26, targetSdk 37 and new feature-VM classes verified. Source/hash in APK.json. |
| G4 | NOT_RUN: manual device/audio/native screenshot/TalkBack and D01–D22 results still required. |
| G5 | BLOCKED by remaining device/design evidence; no release-ready claim. |

Local five-task run: 11/06/2026 07:05:31 – 11/06/2026 07:10:30; source unchanged. Session-local Gradle heap 2 GiB, in-process Kotlin, one worker.

Delivered local APK: output/NeDotify-B-37642cb-debug.apk; source 37642cbd7544d8fbdae87ee83c1bb19e515bedfb; SHA-256 65d47f1ce2c8a723bc938f324c4421d7b7a85105a6fd15cf2c520e11c3d8844b. No device installation/execution was performed.

Cloud attempts: [42174831681](https://github.com/PoviceProgrammer/NeDotify-Android/actions/runs/42174831681) exposed obsolete SDK tools/default Unix dotfile behavior; fixed. [42176708053](https://github.com/PoviceProgrammer/NeDotify-Android/actions/runs/42176708053) passed the full source build and strict cold-cache compile, but the policy harness leaked an expected subprocess failure exit. The harness now explicitly succeeds only after all assertions, and still returns nonzero on a real assertion failure. The next required aggregate check must pass; neither failed attempt counts as aggregate success.

Source reports have scope source-gate-only. Separate debug packaging evidence is APK.json; the mandatory CI build also assembles a debug APK. Device evidence remains a separate gate. Older source-only reports that say APK NOT_RUN describe their five-task invocation, not later manual packaging.

Current local lint warnings: ExportedService (MediaSessionService) and UseKtx (existing MediaStore URI). No lint checks were disabled to pass the new UI. Legacy Compose test-rule API deprecation warnings remain; changing that test scheduler is outside this integration.
