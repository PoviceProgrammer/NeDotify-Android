# CORE-003 — failed initial application controller has no explicit recovery

- Area / owner: W5 playback connection / C.
- State: `RESOLVED_SOURCE_TEST` (2026-11-06); final integrated source gate is recorded in review/VALIDATION.md. DEVICE: NOT_RUN.
- Severity / priority: **S1 / P0**. A failed initial session connection leaves core
  playback unavailable for the life of the application-owned connection.
- Scope: new W5 connection ownership; no Room or data-format change.
- Acceptance: D03,D08; blocks final source integration and release while open.

## Evidence and trigger

`ui/state/PlaybackConnection.kt` catches the initial failed controller future and
marks DISCONNECTED, but the one-shot future remains in ControllerConnector. The
connector's `connect()` returns while that future is non-null. New selections throw
"player connecting" and toggle has no controller to act on. The application-owned
connection survives feature/Activity teardown, so constructing a new feature
ViewModel no longer supplies a fresh connection attempt. Configuration recreation
was never a guarantee of a new ViewModel; this report does not claim otherwise.

Expected: after an initial failure, a deliberate new play/retry may create one new
connection. A pending or successful future is retained. No timer or automatic
infinite retry is introduced.

## Test loop

`MediaControllerConnectorTest` creates real SettableFuture instances, fails the
first, explicitly calls connect, and asserts a new attempt/release count. A second
connect while the replacement is pending must not create a third attempt.

```powershell
powershell -NoProfile -File .\tools\run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest' -InitScript docs/audit/CORE-003.gradle -Summarize
```

TEST: red run observed 5 tests / 1 assertion failure / 0 errors / 0 skips in
`evidence/CORE-003-red.xml` and `CORE-003-red-gradle.log`. After the fix, all
7 connector tests passed (0 failures/errors/skips); two additional cases cover
cancelled attempts and synchronous build exceptions. Green evidence:
`evidence/CORE-003-green.xml` and `CORE-003-green-gradle.log`.

The connector releases a failed/cancelled completed future only on an explicit
connect; a pending or live future is retained. PlaybackConnection requests retry
from play preparation/toggle when disconnected and catches synchronous creation
errors. No periodic retry or automatic play is introduced. The user may need to
repeat Play after the connection completes.

Fixed source SHA-256:
- `ui/MediaControllerConnector.kt`: `902d4e7d695979e65168d422c4750c1d9f4b89d0aefcc4a77451ef7d5d76134d`
- `ui/state/PlaybackConnection.kt`: `86d17681f32fec3d30e98db102436bd41b366e50ebab198f2a35603de111c42f`
- `ui/MediaControllerConnectorTest.kt` (test source): `1bdf433b4e0f92a1a5d30f9dee455f8dc55d8af70ad79c1eecd82a6f5636001f`

DEVICE: NOT_RUN. The unit seam establishes future ownership and explicit retry
ordering, not an actual Android session binding or audible playback.

## Ranked hypotheses and exit

1. Failed future is retained: allowing a completed failed/cancelled future to be
   released on explicit connect lets the next attempt proceed.
2. Playback entry points never request a retry: route deliberate play/toggle retry
   to the connection on its Main scope when no controller exists.
3. Failure is synchronous instead of a failed future: report DISCONNECTED without
   crashing the application scope; the next explicit retry still remains possible.

Exit: demonstrate failing future regression, apply bounded explicit retry, pass
all connector tests and full source gate. Real session/device recovery remains in
D08 with DEVICE NOT_RUN until Android execution is permitted.
