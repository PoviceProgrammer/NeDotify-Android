# Standalone validation evidence

Owner: **V**. Integration owner: **L**. PO authorized debug APK packaging for
manual testing on 2026-11-06. Local package source/hash are in `APK.json`;
device execution and acceptance remain **NOT_RUN**.

## What is executable now

From the Android repository root:

```powershell
./tools/generate-design-tokens.ps1 -Check
./tools/test-source-infrastructure.ps1
./tools/check-catalog-lock.ps1
./tools/run-source-build.ps1 -RerunTasks
```

The source build runs explicit debug/release main Kotlin compilation, JVM tests,
lint and instrumentation **compilation**. Its output is
`app/build/reports/source-gate/run.json`, including input-tree digest, exact task
names, start/finish timestamps and exit status. Source edits during the run reject
the result. No packaging/install/device task is allowed.

After all writers stop and that build succeeds, L refreshes the report and source
manifest with `./tools/update-review.ps1 -RefreshSnapshot`, then runs
`./review/VERIFY.ps1`. The report generator rejects missing, failed, mismatched or
stale build evidence. Historical reports elsewhere in `review/` are not current
verification merely because they exist.

## CI gate

`.github/workflows/android-source.yml` runs from this standalone root.
`android/source` requires policy, source build and cold-cache planning to succeed.
The mandatory build job also assembles the authorized debug APK and uploads its
hash and binary as a separate artifact. Source-only reports describe the five-task
gate; packaging evidence and device outcomes are separate records.
The cold compile may be skipped only when the successful planning job explicitly
determines that dependency/build-policy inputs did not change. Dependency changes,
introduction or modification of this workflow/source-build policy, the initial
push and the final manual cold-cache run require a successful cold compile.
Cancelled/skipped/failed mandatory jobs fail the aggregate check.

The fixture checks execute token generation/drift detection, source hashing,
allow-list rejection, aggregate-job policy and device-report rejection against
new isolated temporary directories. They never use a real device library,
provider credentials, a host desktop profile or an original imported database.

## Device gate

Instrumentation is configured with test-only `IsolatedAndroidTestRunner`. It boots
a standard Android Application instead of the production `NeDotifyApp`, so app
startup cannot reconcile downloads against the installed user profile. Its target
context routes files, DataStore, databases and external fixture paths into a fresh
UUID directory in the instrumentation APK's cache. SharedPreferences are in memory;
external storage migrations and escaped file names are rejected. Scratch evidence
is retained rather than deleting any installed profile. `RunnerIsolationTest`
must pass on the device before any instrumentation acceptance claim.

UI navigation tests use the real stateless navigation controls with saved fixture
state. They do not launch MainActivity or change production preference files.
Production Application startup, a live service/controller and audible playback
still require the separate D01/D03/D08 acceptance scenarios; the fixture runner is
not evidence that those production paths ran.

`scenarios.json` is the versioned D01–D22 specification: owner, scenario, expected
result and initial `NOT_RUN`. `device-results.json` is an execution record and is
deliberately excluded from the source digest to avoid circular evidence.

Every execution row needs status, nonblank `actualResult`, evidence paths and linked defects.
The report also needs source commit SHA, APK SHA-256, model/API and execution time.
`./tools/check-device-acceptance.ps1 -SourceSha <sha> -ApkSha256 <hash> -EvidenceRoot <directory>` rejects
unrun scenarios, blank actual results, missing/empty evidence files, another build
and open S0/S1 defects. Evidence references must be portable relative local paths
inside that explicitly approved directory. Traversal and symbolic links/junctions
are rejected, including paths that look internal but resolve outside. It only
validates existing evidence; it cannot enable packaging or authorize a device run.

No UI prototype interaction, `isPlaying` flag, compiled test or host HTTP probe
proves audible Android playback. Sound checks need actual listening evidence.
