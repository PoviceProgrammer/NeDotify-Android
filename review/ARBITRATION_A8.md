# ARBITRATION INPUT — A8

The two independent reviews of Area A8 (build system, test infrastructure, review manifests,
documentation) have been completed. Their **actionable** findings are consolidated here so the arbiter
can adjudicate them. Every remark must still be **verified against the code** before it is acted on.

Common rules: `android/review/REVIEW_BRIEFS.md` (the "Rules that apply to every area" section binds
every area). Build/test: `powershell -NoProfile -File android/tools/run-unit-tests.ps1 -Summarize`
(**redirect the output to a file; do not** pipe it through `Select-Object` — the Gradle daemon inherits
the stdout handle and the pipe hangs). Baseline: **604 tests, 0 failures, 0 errors, 0 skipped**;
lint 0 errors / 17 warnings / 1 hint. The desktop suite has **3 failing tests** in
`tests/test_android_source_gates.py` — see B4, that is this area's regression and it must be fixed.
Never run `assemble*`/`bundle*`/`package*`. Never open `~/.nedotify/`.

---

## Reviewer 2 (adversarial) — verdict ACCEPT WITH FIXES

* **B1 (P1) — the CI job cannot run: neither `android/` nor the workflow file is in version control.**
  `git ls-files android` → **0**; `git status --porcelain` → `?? android/` and
  `?? .github/workflows/android-source.yml`. A fresh `actions/checkout@v4` produces no `android/`
  directory, so the first step (`./android/review/VERIFY.ps1`), the catalog check
  (`./android/tools/check-catalog-lock.ps1`) and `./gradlew` all fail. The job has never run and as
  committed it cannot. This is the audit's own precondition
  (`RECOMMENDED_FIX_ORDER.md:37`: "**1.1** `git add android/` … It is also the pre-condition for the CI
  job") and REG P2-54.
  **Fix:** `git add android/ .github/workflows/android-source.yml` (respecting `android/.gitignore`).
  **Do not commit** — staging only; committing is the user's decision.
* **B2 (P2) — REG P2-77 is not fixed: the lint gate is still a function of the network.**
  `android/app/build.gradle:44` is `lint { abortOnError = true }`; `checkDependencies` is left at AGP's
  default (TRUE — the matrix correction says "a fix must not rely on those flags", so the default is
  the point), and **no separate non-blocking "newer version available" task exists** anywhere.
  15 of the 17 gate warnings (`NewerVersionAvailable` 8, `GradleDependency` 5,
  `AndroidGradlePluginVersion` 2) are exactly the network-dependent family the audit measured at
  15 / 13 / 4 warnings for the **same** source. The detector's cache lives in
  `app/build/intermediates/lint-cache/…/maven-versions/` — excluded from the snapshot and not in
  version control, so a clean checkout produces a different warning set.
  **Fix:** `lint { abortOnError = true; checkDependencies = false }` plus a **separate non-blocking
  task** that reports the newer versions without failing the gate; then state the new stable warning
  count in the documentation.
* **B3 (P2) — the published lint figures come from a run that predates the last source change, and
  the generator cannot see it.** The lint report on disk was written at 12:24; two main sources changed
  at 12:42/12:43. `android/tools/update-review.ps1:47-53` compares the newest source only with the
  **JUnit XML**; the lint report's own freshness is never checked.
  **Fix:** add the lint report to the freshness check and regenerate the summary and `VALIDATION.md`
  from a run newer than every source file.
* **B4 (P2) — the A8 rewrite of `SourceSnapshot.ps1` broke the repository's own regression test.**
  `tests/test_android_source_gates.py:26-28` expects the messages `Unlisted source`,
  `Source changed`, `Missing source`; the rewritten script prints `New (N):`, `Changed (N):`,
  `Removed (N):`, `No longer a source, but still on disk (N):`. Measured: **3 failed, 4 passed** in
  `tests/test_android_source_gates.py`. The *detection* still works; only the message strings changed —
  but the repository now ships 3 failing tests and neither the area report nor the tracker mentions it.
  **Fix:** update the three expected strings (and add the missing fourth case, `unlisted`) and re-run
  the desktop suite. The detection must keep failing the gate.
* **B5 (P2) — REG P2-39 is not fixed: the root documentation does not mention the Android product.**
  Measured over the root `AGENTS.md`, `STATUS.md` and `README.md` (case-insensitive): **0** matches
  for "android". `AGENTS.md:3-5` still says "Windows desktop music app … The sole UI is
  `ui/web_new_v2/`" — and `ui/**` now matches two trees.
  **Fix:** one paragraph in the root `README.md` (a second, native Android product in `android/`, its
  rules in `android/AGENTS.md`, its state in `android/PORTING_STATUS.md`) and a scope marker in
  `AGENTS.md` / `STATUS.md`.
* **N1 (P2) — REG P2-79 is not fixed: the SDK is still inside the temp dir and `local.properties` is
  still the primary SDK channel.** `android/local.properties:1` → `sdk.dir=C:/Temp/NeDotifyAndroidSdk`
  (51 717 files / 4.9 GiB); the SDK moved from one `C:\Temp` path to another. `local.properties` is
  still what a direct `gradlew` run needs and is still rewritten in place by `verify.ps1`.
* **N2 (P2) — REG P2-40 is not fixed: `android/AGENTS.md` still protects a host path and never names
  the device library.** `android/AGENTS.md:10` — "Never clear ~/.nedotify or alter the user's library";
  `~/.nedotify/` cannot exist on the device, and the device's own library (the Room DB, the offline
  packages, the Keystore store) is never named.
* **N3 (P2) — the summary generator produces an **empty** lint breakdown on Windows.**
  `android/tools/update-review.ps1:66` uses `(?m)^[^:\r\n]+:\d+: …`, and `[^:\r\n]+` cannot cross the
  `:` of the drive letter in the absolute path the report actually contains — so
  `review/TEST_SUMMARY.json` publishes `"by_id": { }` while `review/VALIDATION.md` publishes a
  hand-derived table. The two artefacts of the same run disagree.
* **N4 (P2) — the snapshot cannot see files whose *type* is not in the source rules.**
  `android/tools/SourceSnapshot.ps1:25-36` covers `.kt .java .xml .gradle .properties .toml .ps1 .bat
  .pro .jar .py` + the schemas + the wrapper. Measured: **11 uncovered files**, including
  `android/AGENTS.md`, `android/README.md`, `android/PORTING_STATUS.md` (this area's own
  deliverables), `android/.gitignore`, `design/reference.png`, `android/settings-gradle.lockfile` and a
  stray `local.properties.*.bak`. The old manifest (187 files) did cover `.md`/`.json`/`.png`.
* **N5 (P2) — `android/tools/SourceTasks.ps1:6` allows a task that does not exist**
  (`:resolveLinuxAapt2`); the documented way to refresh the Linux checksum therefore fails with
  "Task not found".
* **N6 (P3) — REG P2-41 is not fixed:** `android/review/REVIEW_A.md` and `REVIEW_B.md` keep the old
  names and there is still no `android/review/README.md` index.
* **N7 (P3) — REG P2-42 is only half fixed:** `android/review/SNAPSHOT.json` still publishes
  `"files": 187, "unit_passed": 217, "instrumentation_compiled_only": 55` and, unlike the `PRE_*`
  files, its name does not mark it as history. The area report names this exact file as repaired.
* **N8 (P3) — the `PropertyEscape` suppression is global, not scoped to the machine-local file.**
  `android/lint.xml:29` is `<issue id="PropertyEscape" severity="ignore" />` — project-wide — while
  the report and `android/README.md` claim it is "silenced for the one file that is not in the
  repository". It is also off for `android/gradle.properties`, which **is** in the repository.
  Lint's XML format does support a per-path suppression (`<issue id="PropertyEscape"><ignore
  path="local.properties"/></issue>`). In fairness: the lint report carries the junction path, so a
  path-scoped rule is fragile under the alias — but then the documentation must say "globally, because
  the file name is machine-local and the path is rewritten per run", and `android/gradle.properties`
  must be checked by hand.
* **N10 (P3) — the tracker misattributes finding ids.** `docs/REMEDIATION_TRACKER.md:1024` and `:1027`
  and `:1028` label ids that mean something else (P3-85 = "the APK path does not exist", P3-88 = "the
  APK ban is recorded inconsistently", P3-89 = "verify.ps1 has no header block"; the stale-numbers
  ids are P3-83/P3-92; P2-72 = "verify.ps1 executed zero tasks", P2-76 = the release build type,
  P2-77 = the lint/network gate, P2-79 = the SDK location). Rewrite the A8 row against the matrix.
* **N11 (P3) — `android/README.md:48` overstates the lock state:** it lists
  `settings-gradle.lockfile` as "the locked versions of **all** configurations", but that file holds
  exactly one line (`empty=incomingCatalogForLibs0`) and is not in the snapshot manifest.
* **N12 (P3) — `check-catalog-lock.ps1` is weaker than its message.** It compares *presence*, not
  resolution: a coordinate that appears **anywhere** in the lock state passes (measured:
  `org.xerial:sqlite-jdbc` is locked at two versions). It also silently skips the BOM-managed entries
  while reporting "36 libraries checked" (30 are compared), and it is **not** part of
  `android/verify.ps1` or `run-unit-tests.ps1`.
* **N13 (P3) — the CI's snapshot step is a trip-wire for every ordinary change** and is not
  documented in the workflow file.
* **N14 (P3) — every Gradle log starts with a rendered "NativeCommandError" block** because
  `JAVA_TOOL_OPTIONS` makes the JVM print to stderr. Harmless, but a reader sees an error at the top
  of every log. Inject the temp dir through `GRADLE_OPTS` / `org.gradle.jvmargs` instead.

## Reviewer 3 (independent verification) — verdict PASS WITH GAPS

Reproduced independently: the catalog, the lock check, the four-way drift detection, the APK/device
ban (7 task spellings rejected before Gradle starts), the log capture, the summary generator
(543/0/0/0, 80 files, 66/17, lint 0/17/1 — identical to the published artefacts), REG P2-72 (a
captured `verify.ps1` run executed 30 real Gradle tasks and wrote a log) and REG P2-76 (the effective
release model: `minifyEnabled=true`, `shrinkResources=true`, `proguardFiles=[…, app/proguard-rules.pro]`,
`signingConfig=release`, `debug.applicationId=ru.nedotify.app.debug`, `minifyReleaseWithR8=true`;
`signingReport` shows the release store as null).

* **D1 (HIGH) — the CI job cannot execute: the tree and the workflow are not committed.** (Same as B1.)
* **D2 (HIGH) — the dependency-verification metadata is incomplete: a cold Gradle cache fails the
  build.** Measured twice — with `--refresh-dependencies` on the warm cache and with an **empty**
  `caches/modules-2` (exactly what a fresh CI runner has):
  `Dependency verification failed for configuration 'classpath'` — `guava-parent-33.3.1-jre.pom`,
  `junit-bom-5.10.2.module`, `junit-bom-5.11.0-M2.module`. In the metadata,
  `org.junit:junit-bom:5.10.2` and `5.11.0-M2` record **only** the `.pom` (no `.module`), and
  `com.google.guava:guava-parent` exists only at `26.0-android` and `32.0.1-jre` — `33.3.1-jre` is
  absent entirely. The build therefore dies at **configuration** time, before any task runs, so the CI
  job dies on its first Gradle command. This is the same class of defect the area report says it fixed
  for the lint toolchain, only for the *plugin* classpath. The warm local cache masked it.
  **Fix:** re-record the metadata on an empty cache
  (`gradlew --refresh-dependencies --write-verification-metadata sha256 …` with a fresh
  `GRADLE_USER_HOME`) and add a CI step that exercises a cold cache. Also note: verification is
  enforced **at download time only** — a tampered checksum is not re-checked for an artifact already
  in the local cache — so "verification is on" ≠ "every build re-hashes every artifact".
* **D3 (MEDIUM) — REG P2-77 is only half fixed.** (Same as B2.)
* **D4 (MEDIUM) — four of the area's own registered defects are reported "fixed" but are not:**
  REG P2-39 (root docs), REG P2-40 (`android/AGENTS.md` protects `~/.nedotify`), REG P2-41 (the review
  briefs are not renamed; no `review/README.md`), REG P2-42 (`git ls-files android` → 0).
* **D5 (LOW/MEDIUM) — the summary generator's lint breakdown is silently empty on Windows.**
  (Same as N3.) The fix must also **assert** that `by_id` is non-empty when warnings + errors + hints > 0,
  so a future regex failure cannot be mistaken for "no findings".
* **D6 (LOW) — `review/SNAPSHOT.json` still quotes 217/55/187.** (Same as N7.)
* **D7 (LOW) — `verify.ps1` refuses to run after the SDK moves**, while `run-unit-tests.ps1` re-points
  the alias (`verify.ps1:50-53` throws "Existing alias points elsewhere"; `run-unit-tests.ps1:103-114`
  deletes and re-creates it). The **documented** entry point (`android/README.md:23`) is the less
  portable of the two.
* **D8 (LOW) — the root-level plugin classpath is not locked.** `android/settings-gradle.lockfile`
  holds one line; `dependencyLocking { lockAllConfigurations() }` is declared only in
  `android/app/build.gradle:90` (project scope); `check-catalog-lock.ps1:42` parses the `[libraries]`
  section only, so the three plugin aliases are never cross-checked. The mitigation is that the
  verification metadata covers the plugin jars' checksums, so an unexpected plugin version fails
  loudly — but the "lock state" half of the contract is absent for the classpath configuration.

## What the arbiter must also do

* **Regenerate the snapshot last.** The manifest is regenerated *after* the last area stops editing
  (`android/tools/update-review.ps1 -RefreshSnapshot`, then `android/review/VERIFY.ps1`). The rule is
  in `android/AGENTS.md`: "When sources change after a review snapshot, regenerate
  `review/SOURCE_SHA256.txt`." Coordinate with the coordinator if another area is still editing.
* **Regenerate the published numbers** from a run that is newer than every source file, and make the
  generator refuse a stale lint report (B3).
* **Keep the honest positions** the reviewers confirmed: the 66 instrumented `@Test` methods compile
  but have never run; no APK was ever built; the CI job has never run; `PORTING_STATUS.md` is honest
  about all of it.
