# CORE-002 — pause/seek events do not force a queue-position checkpoint

- Area / owner: playback persistence / C.
- Baseline: `0b10607ddb2b9cbce215430f0c48b4928f0abdb7`.
- Severity / priority: **S2 / P2**; bounded position rewind, no queue/data deletion.
- State: `RESOLVED_TEST`; real-player wiring instrumentation remains `NOT_RUN`.
- Acceptance: D04,D08; W5 explicit pause/seek checkpoint contract.

CODE: `player/PlaybackService.kt:203` sends position-discontinuity/is-playing events
to `checkpointQueue()` without force. `player/QueueCheckpoint.kt:25` suppresses
unchanged queue identity with movement below 30 seconds. Destruction forces a write
at `PlaybackService.kt:465`; periodic playback checks happen every five seconds.

Expected new contract: a deliberate pause or seek schedules its current position
even below the periodic interval. Normal playback retains the throttled cadence.
An abrupt process kill is not equivalent to a graceful service destruction.

## Regression seam

The original generic `onEvents` checkpoint dispatch has been extracted without a
behavior change to `QueueCheckpointListener`, registered by PlaybackService.
Its event set and `force=false` behavior match the old service. The service's other
listener retains polling/history/error work. This creates an actual Media3 callback
seam that can be exercised on the JVM, instead of asserting on source text.

`QueueCheckpointListenerTest` drives the registered listener type with real
`QueueCheckpoint` policy and a captured queue-snapshot writer. User pause at
1,200 ms and user seek to 2,500 ms must schedule those positions despite the
30-second periodic interval; focus interruption/play must not force periodic
writes. Existing cadence tests run in the same diagnostic invocation.

```powershell
powershell -NoProfile -File .\tools\run-unit-tests.ps1 -Tasks ':app:testDebugUnitTest' -InitScript docs/audit/CORE-002.gradle -Summarize
```

TEST: **RED observed, then GREEN verified**. Before the fix the diagnostic run had
8 tests, 2 failures, 0 errors and 0 skipped: explicit user pause and seek did not
submit their sub-interval positions. After the fix the same 8 tests passed.

Evidence: [red Gradle log](evidence/CORE-002-red-gradle.log),
[red listener XML](evidence/CORE-002-red-TEST-ru.nedotify.app.player.QueueCheckpointListenerTest.xml),
[green Gradle log](evidence/CORE-002-green-gradle.log),
[green listener XML](evidence/CORE-002-green-TEST-ru.nedotify.app.player.QueueCheckpointListenerTest.xml).
Existing QueueCheckpoint cadence XML is stored alongside these files.

Red listener-file SHA-256:
`6182ec4dec8d1d83eb2b8d09c44b0f8fe351a94a1729caddec78279c17a34074`.
Fixed listener-file SHA-256:
`72632d6ecfe74245274c13ef36901916662f81703cdb6b0c9d6912aa58b1e35d`.
Unchanged regression-file SHA-256:
`7ddeadee7f1838ad36181a167b79d8c8daa12f602b944687800153ac60bb68c3`.

The fix overrides the registered listener's explicit user-pause and
seek/seek-adjustment callbacks to request `force=true`; periodic generic events
retain `force=false`. Native `QueueCheckpointListenerWiringTest` covers real
ExoPlayer/delegate → stable bridge → listener dispatch without preparing media,
network access or reading/writing the app library. It still requires device
execution. DEVICE: **NOT_RUN**. The passing JVM test establishes checkpoint
submission, not hardware playback or durable Android DataStore writes.

## Hypotheses and exit

1. Generic event handling omits explicit user-action force: forced submission on
   pause/seek fixes the two regression cases while periodic tests stay green.
2. The checkpoint policy suppresses even forced writes: existing explicit-force
   tests would fail as well. They already define the expected policy.
3. Android dispatch differs from direct listener callbacks: a real-player wiring
   test and device execution are required before claiming lifecycle acceptance.

Exit: observe red callback regression, fix explicit user-action force, run the
same tests and unaffected cadence checks green. Compile a real-player wiring
test, then verify pause/seek and restoration on an isolated Android profile after
packaging/device permission. No exact crash-position durability is promised;
the documented abrupt-kill tolerance remains about 30–35 seconds.
