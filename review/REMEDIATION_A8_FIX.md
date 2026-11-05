# A8 — Build system, test infrastructure, review manifests and documentation

Two passes: the fix engineer (2026-10-20) and the arbiter + repair engineer
(the same day, after the two independent reviews). Authority:
`docs/audits/independent_verification`. **No APK, `assemble`, `bundle` or
`package` task was run.** This is a fix log, not an independent review.

## What the gate measures now

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **604 tests, 0 failures, 0 errors, 0 skipped** (82 JUnit XML files) |
| `:app:lintDebug` | **0 errors, 2 warnings, 0 hints** (`ExportedService`, `UseKtx`) |
| `:app:compileDebugAndroidTestKotlin` | compiles (66 `@Test` methods, 17 files) |
| APK / device | **never**: no `assemble*`, no `bundle*`, no `package*`, no device, no emulator |
| CI job | **has never run** — until this pass neither `android/` nor the workflow file was in version control |

The lint number is now a function of the source alone: 2 warnings, without the
network. Before this pass the same source produced 15, 13 or 4 warnings
depending on the state of the detector's cache (REG P2-77).

---

## Part 1 — the first pass (fix engineer)

| # | Defect | Evidence |
|---|---|---|
| 1 | `:app:lintDebug` never reached the analysis: the dependency verification rejected the **lint toolchain** — 18 artifacts of 9 components were missing from `gradle/verification-metadata.xml` | `com.android.tools.lint:lint/lint-api/lint-checks/lint-gradle:32.1.1`, `com.android.tools.external.com-intellij:intellij-core`, `...:kotlin-compiler`, `com.android.tools.external.org-jetbrains:uast`, `com.android.tools:play-sdk-proto`, `org.codehaus.groovy:groovy` — "Dependency verification failed for configuration ':app:detachedConfiguration1'" |
| 2 | The one lint **error** was `PropertyEscape` on `android/local.properties` — a file that is gitignored and machine-local | `android/local.properties:1: Error: Windows file separators (\) and drive letter separators (':') must be escaped (\) in property files [PropertyEscape]` |
| 3 | The release build type was a **silent** release: `minifyEnabled=false`, `shrinkResources=false`, no `signingConfig`, and `debug.applicationIdSuffix=null`, so a debug build silently replaced the release build (both `ru.nedotify.app`) | audit REG P2-76 |
| 4 | The review artefacts quoted stale numbers (**217/17/55**) and the lint count was quoted as 15 in one place and 17 in another | `android/review/VALIDATION.md:5,8`, `review/TEST_SUMMARY.json`, `review/SNAPSHOT.json` |
| 5 | The CI job named a package that does not exist (`platforms;android-37`), and the verification metadata named only the **Windows** build of `aapt2` | `.github/workflows/android-source.yml:20`, `gradle/verification-metadata.xml` |

**What was done.** The lint toolchain and the Linux build of `aapt2` were
recorded by Gradle's own `--write-verification-metadata sha256` — dependency
verification stays **on** everywhere and is never switched off. The release
build type was wired (R8, the resource shrinker, `app/proguard-rules.pro`, an
environment-driven `signingConfig`, `debug.applicationIdSuffix = '.debug'`) and
verified **without an APK**, by dumping the effective AGP model
(`tools/dump-variant-model.gradle`, `:dumpVariantModel`) and by `:app:signingReport`.

**What stayed unverified (and stays that way):** that R8 really runs over the
release bytecode, that the shrinker produces a smaller resource table, that the
ProGuard rules keep every reflective class alive, and that the signed APK
installs. `:app:compileReleaseKotlin` compiles; it does not package.

---

## Part 2 — the second pass (arbiter + repair engineer)

The two independent reviews (an adversarial one and an independent
verification) produced 21 remarks. Every one of them was measured against the
code before it was acted on. The table is the adjudication; the details follow.

| Remark | Verdict | What was done |
|---|---|---|
| B1 / D1 — the CI job cannot run: neither `android/` nor the workflow file is in version control | **confirmed** | `git add android/ .github/workflows/android-source.yml`. **Staged, not committed** — the commit is the owner's decision |
| B2 / D3 — REG P2-77 is not fixed: the lint gate is still a function of the network | **confirmed, and the prescribed fix was incomplete** | `lint { checkDependencies = false }` **and** the three network-dependent detectors switched off by name in `lint.xml`, **and** a separate non-blocking task `:reportNewerVersions` |
| B3 — the published lint figures come from a run that predates the last source change, and the generator cannot see it | **confirmed** | the generator now compares the lint report **and** the JUnit XML with the newest source file of the tree and refuses to publish when either is older |
| B4 — the rewrite of `SourceSnapshot.ps1` broke `tests/test_android_source_gates.py` (3 failed, 4 passed) | **confirmed** | the three expected messages now match the four drift kinds the script reports; the fourth case (`unlisted`) was added; the test also asserts that the report names the file that drifted. **The detection still fails the gate in every case** |
| B5 / D4 — REG P2-39 is not fixed: the root documentation does not mention the Android product | **confirmed** | one paragraph in the root `README.md`, a scope marker in the root `AGENTS.md`, a scope marker in `STATUS.md` |
| D2 — the dependency-verification metadata is incomplete: a cold Gradle cache fails at **configuration** time | **confirmed** | the metadata was re-recorded by Gradle on an **empty** `GRADLE_USER_HOME`; the CI job gained a step that resolves the build with an empty Gradle home |
| N1 — REG P2-79 is not fixed: the SDK is in the temp dir, `local.properties` is the primary channel | **confirmed** | the SDK moved to `%LOCALAPPDATA%\Android\Sdk`; the environment is the primary channel, `local.properties` the fallback |
| N2 — REG P2-40 is not fixed: `android/AGENTS.md` protects a host path and never names the device library | **confirmed** | the rule now names the device library and says that `~/.nedotify/` is the desktop profile |
| N3 / D5 — the lint breakdown is empty on Windows | **confirmed** | the finding line is matched with a **greedy** path (the old pattern could not cross the `:` of the drive letter), and the generator **refuses** to publish an empty breakdown next to a non-empty finding list |
| N4 — the snapshot cannot see 11 files whose type is not in the rules | **confirmed** | the rule set now covers `.md .json .png .yml .yaml .txt .lockfile` and `.gitignore` |
| N5 — `:resolveLinuxAapt2` is allow-listed but does not exist | **confirmed** | removed: a task that does not exist must not be offered as a recovery command |
| N6 — REG P2-41 is not fixed: the briefs are not renamed, no `review/README.md` | **confirmed** | `BRIEF_A.md` / `BRIEF_B.md` + `review/README.md` |
| N7 / D6 — `review/SNAPSHOT.json` still publishes 217/55/187 | **confirmed** | the file is generated by the same run as the other two artefacts |
| N8 — the `PropertyEscape` suppression is global while the docs claim it is file-scoped | **confirmed** | the suppression is now `<ignore path="local.properties"/>` — by file **name**, so it works under the ASCII junction and on a CI checkout; `android/gradle.properties` is checked as usual |
| N10 — the tracker misattributes finding ids | **confirmed** | the A8 row and log were rewritten against `VERIFICATION_MATRIX.csv` |
| N11 — the README overstates the lock state | **confirmed** | `settings-gradle.lockfile` holds one line, and the README says so |
| N12 — `check-catalog-lock.ps1` compares presence, not resolution; skips the BOM entries; is not part of `verify.ps1` | **confirmed** | the check compares the version of every library, checks the six BOM-managed libraries by name, reports a module locked at several versions, prints the real counts, and now runs inside `verify.ps1` |
| N13 — the CI's snapshot step is a trip-wire and is not documented | **confirmed** | the workflow says so in a comment, and the README explains the rule |
| N14 — every Gradle log opens with a rendered "NativeCommandError" | **confirmed** | `JAVA_TOOL_OPTIONS` is no longer used: the ASCII temp directory reaches the JVM through `TEMP`/`TMP` (on Windows the JVM takes `java.io.tmpdir` from them) |
| D7 — `verify.ps1` refuses to run after the SDK moves, while `run-unit-tests.ps1` re-points the alias | **confirmed** | both scripts re-point the alias |
| D8 — the root-level plugin classpath is not locked | **confirmed** | the plugin classpath is locked in the root project (`buildscript-gradle.lockfile`); the catalog check now cross-checks the plugin aliases |

### The measurement that changed the prescription (B2 / D3)

The prescribed fix was `lint { checkDependencies = false }`. **Measured on
AGP 9.1.1, that flag does not remove the version detectors.** The effective
value was read out of the model (`PROBE lint.checkDependencies=false`), the
lint model was regenerated (`generateDebugLintReportModel` executed), and the
run still reported **17 warnings** — `NewerVersionAvailable` 8,
`GradleDependency` 5, `AndroidGradlePluginVersion` 2 — exactly the
network-dependent family the audit measured at 15 / 13 / 4 warnings for the
same source.

So the gate now does three things:

1. `lint { checkDependencies = false }` — the dependency analysis of the
   project graph is off;
2. `android/lint.xml` switches the three network-dependent detectors **off by
   name** (`NewerVersionAvailable`, `GradleDependency`,
   `AndroidGradlePluginVersion`) — they read a repository over the network and
   cache the answer in
   `app/build/intermediates/lint-cache/lintAnalyzeDebug/maven-versions/`;
3. the information is produced by a **separate, non-blocking task**:
   `:reportNewerVersions` prints the newer versions of the catalog and the
   plugins and never fails the build. It resolves nothing, so it cannot change
   what is built.

Result: **0 errors, 2 warnings, 0 hints** — `ExportedService` (the exported
`PlaybackService` without a permission, A1/A7) and one `UseKtx`.

The cost of this is stated plainly: the gate no longer *reports* an outdated
dependency. The report exists, it is not a gate, and the README, `lint.xml`
and the tracker all say so.

### The cold cache (D2)

The dependency verification runs when an artifact is **downloaded**. An
artifact that is already in the local Gradle cache is not hashed again, so a
warm cache hides an incomplete `verification-metadata.xml` — and the warm
cache of this machine was exactly what hid it: the plugin classpath named
`guava-parent` only at `26.0-android` and `32.0.1-jre`, and `junit-bom` only
as a `.pom` without the `.module` files.

The metadata was therefore re-recorded by Gradle itself, on an **empty**
`GRADLE_USER_HOME`:

    GRADLE_USER_HOME=<empty> gradlew --refresh-dependencies \
        --write-verification-metadata sha256 \
        :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin

and the CI job gained a step that resolves the build with an empty Gradle
home, so a fresh runner is proven to work and a warm cache can no longer hide
a gap.

### D8 — the plugin classpath is not locked

`settings-gradle.lockfile` held one line (`empty=incomingCatalogForLibs0`):
the classpath of the plugins was not locked by version, and
`dependencyLocking` was declared only in `android/app/build.gradle` (project
scope). The plugin aliases of the catalog were never cross-checked at all.

The plugins are applied in the **root project** (`plugins { alias(...) apply
false }`), so their classpath is locked **there** and not in the settings:

```groovy
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}
```

Measured on the way: the settings script has **no** `dependencyLocking()`
method in Gradle 9.3.1 — "Could not find method dependencyLocking() for
arguments [...] on settings 'project' of type
org.gradle.initialization.DefaultSettings" — and a `buildscript {}` block must
precede the `plugins {}` block.

The state is now `android/buildscript-gradle.lockfile` (123 lines, including
the plugin markers such as
`com.android.application:com.android.application.gradle.plugin:9.1.1`), and
`tools/check-catalog-lock.ps1` cross-checks the three plugin aliases of the
catalog against it.

---

## The files this area owns

| File | What changed in the second pass |
|---|---|
| `android/build.gradle` | the lint configuration (`checkDependencies = false`, `abortOnError = true`) and the task `:reportNewerVersions` |
| `android/lint.xml` | `PropertyEscape` scoped to the file name; the three network-dependent detectors switched off by name |
| `android/tools/SourceSnapshot.ps1` | the source rule set now covers the documentation, the images and the lock files; `Get-ReviewNewestSource` added |
| `android/tools/update-review.ps1` | the freshness check covers the lint report; the lint parse is greedy and self-checking; `TEST_SUMMARY.json`, `VALIDATION.md` and `SNAPSHOT.json` are written from one parse |
| `android/tools/SourceTasks.ps1` | `:resolveLinuxAapt2` removed; `:reportNewerVersions` added |
| `android/tools/check-catalog-lock.ps1` | the BOM-managed libraries are checked by name; the **plugin aliases** are checked against the lock state of the plugin classpath; a module locked at several versions is reported; the counts are real; the check is part of `verify.ps1` |
| `android/tools/run-unit-tests.ps1` | the environment is the primary SDK channel; `JAVA_TOOL_OPTIONS` dropped; `-RerunTasks` added |
| `android/tools/check-dependency-updates.py` | **deleted** — it was a second, Python-only implementation of the "newer versions" report with no caller and no reference anywhere in the repository. The one implementation is the Gradle task `:reportNewerVersions`, which needs no Python |
| `android/verify.ps1` | the alias is re-pointed instead of refused; the catalog check runs first; `JAVA_TOOL_OPTIONS` dropped |
| `android/AGENTS.md` | the rule protects the **device** library and names it |
| `android/README.md` | the lock state, the lint gate, the SDK location, the snapshot rules and the CI steps |
| `android/PORTING_STATUS.md` | the second pass, with the honest positions |
| `android/review/` | `BRIEF_A.md`/`BRIEF_B.md` (renamed), `README.md` (new), the regenerated artefacts, this file |
| `.github/workflows/android-source.yml` | the trip-wire comment, the cold-cache step, the non-blocking version report |
| `docs/REMEDIATION_TRACKER.md` | the A8 row and log, against the matrix |
| `tests/test_android_source_gates.py` | the drift messages and the fourth case |

## The one code fix outside the area

`downloads/Downloads.kt` wrote the track row with `dao.remember(track)`,
outside the library write fence, so a row the user had deleted could come
back. The library area added the fenced path in the same pass
(`data/LibraryWrites.kt` — a fourth `keep` lambda and
`suspend fun remember(track)`, `data/LibraryRepository.kt:201` —
`suspend fun remember(track) = libraryWrites.remember(track)`), and the
download path now goes through it (`downloads/Downloads.kt:53` —
`repository.remember(track)`). `data/**` was not touched by this area.

## Out of area (reported, not fixed here)

* `android/app/src/main/AndroidManifest.xml:29` — the exported `PlaybackService`
  without a permission (lint `ExportedService`, A1/A7). It is a **warning**,
  not an error; the lint gate stays green with it.
* REG P2-57 — five instrumented test files use the deprecated Compose test
  API. A deprecation warning with no build impact; the files are under
  `app/src/androidTest/**`, which is outside the file list of this pass.

Two defects that this area reported were repaired by their owners in the same
pass and are no longer open:

* `ui/MusicViewModel.kt:254` wrote the track row with
  `repository.dao.remember(track)`, outside the fence. It now uses the fenced
  `repository.remember(track)` (A7).
* `app/src/test/java/ru/nedotify/app/data/LibraryWriteFenceTest.kt` and
  `LibraryWritesTest.kt` construct `LibraryWrites`, which gained the fourth
  `keep` lambda; both test files pass it now (A4).

## Not verified here

* No device, no emulator, no APK (forbidden by `android/AGENTS.md`).
* The instrumented tests are **compiled**, never executed: 66 `@Test` methods
  in 17 files under `app/src/androidTest`.
* **The CI job has never run.** It could not have: until this pass neither
  `android/` nor the workflow file was in version control. The job is staged;
  its first real run is still ahead.
* The live providers were not contacted: the network probe is opt-in
  (`-PliveProviderProbes=true`), and the MockWebServer tests do not prove that
  Spotify, Яндекс or YouTube are reachable.
* The old review artefacts of 2026-10-19 describe a tree of 187 files; the
  current tree is larger. They are kept as history, not as evidence
  (`android/review/README.md` says which file is which).
* The SDK was moved on **this** machine. A fresh clone has no SDK: the
  scripts take it from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, and
  `local.properties` (gitignored) is the fallback.
