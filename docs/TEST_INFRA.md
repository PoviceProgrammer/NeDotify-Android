# NeDotify Android — Comprehensive Test Infrastructure & 4-Tier Test Specifications

**Document Version**: 1.0.0  
**Author**: E2E Test Writer (`teamwork_preview_test_writer_e2e`)  
**Scope**: Mobile Native Android Port (Kotlin 2.0+ / Jetpack Compose / AndroidX Media3 / Room SQLite WAL)  
**Authoritative Sources**: `ORIGINAL_REQUEST.md`, `PROJECT.md`, Desktop Survey Reports (`survey_spec_report.md`, `survey_desktop_report.md`)  
**Target Matrix**: MinSdk 26 (Android 8.0), TargetSdk 35 (Android 15), CompileSdk 35  

---

## 1. Test Architecture & Infrastructure Framework

### 1.1. Testing Philosophy: Opaque-Box, Requirement-Driven, Multi-Tiered
The testing framework for NeDotify Android is designed around **opaque-box behavior verification** at public seams. In accordance with the project's Test-Driven Development (TDD) guidelines:
1. **Behavioral Seams Over Internals**: Tests verify inputs and outputs across pre-agreed public boundaries (API contracts, database DAOs, MediaSession commands, StateFlow emissions) without coupling to private implementation details or refactoring-sensitive private methods.
2. **Independent Expected Value Derivation**: Every expected assertion is derived directly from authoritative requirements (`ORIGINAL_REQUEST.md`, desktop algorithms, SQLite schemas, and RFC specifications) rather than mirroring production code logic.
3. **Hermetic, Isolated Execution**: Every test sets up its own isolated state (in-memory Room WAL database, ephemeral MockWebServer, synthetic coroutine dispatcher, isolated MediaSession) and cleans up resources on teardown. Tests are strictly independent and deterministic.
4. **No Facade Tests**: All tests execute real logic, real database queries, real JSON serialization/deserialization, real state machines, and real coroutine flows. No dummy stubs that pass unconditionally are permitted.

### 1.2. Toolchain & Test Stack
| Subsystem | Testing Library | Purpose |
|---|---|---|
| **JVM Unit & Integration Runner** | `Robolectric 4.13+` + `AndroidJUnit4` | High-fidelity Android OS simulation (API 26 to 35) directly on the JVM without requiring physical devices or slow emulators. |
| **Assertions & Matchers** | `Google Truth 1.4+` + `JUnit 4/5` | Fluent, readable assertions with descriptive failure messages. |
| **HTTP Simulation** | `OkHttp MockWebServer 4.12+` | Hermetic HTTP/HTTPS simulation for SoundCloud v2 API, YouTube InnerTube, LRCLIB, and CDN streaming endpoints. |
| **Persistence Simulation** | `Room In-Memory SQLite` | Real SQLite execution in WAL mode with foreign keys enabled (`inMemoryDatabaseBuilder`). |
| **Asynchronous & Coroutines** | `kotlinx-coroutines-test 1.9+` | `StandardTestDispatcher`, `TestScope`, `runTest`, and `advanceUntilIdle()` for deterministic time control. |
| **Media & Audio Engine** | `androidx.media3.test.utils` | `FakeExoPlayer`, synthetic `MediaSession`, `TestMediaSessionService`, and simulated audio focus/noisy broadcasts. |
| **UI & Design System** | `androidx.compose.ui.test.junit4` | `createComposeRule()`, semantics tree matching, node touch-target measurements, and UI interaction simulation. |

### 1.3. Code Layout & Test File Organization
Following `PROJECT.md § Code Layout`, all test classes are co-located in `android/app/src/test/java/com/nedotify/`:
```
android/app/src/test/java/com/nedotify/
├── testinfra/
│   ├── BaseUnitTest.kt                 # Base test with TestScope & Dispatcher setup
│   ├── MockServerRule.kt               # Reusable MockWebServer lifecycle manager
│   ├── InMemoryDatabaseRule.kt         # In-memory Room WAL database setup & teardown
│   └── TestTrackFixtures.kt            # Authoritative test tracks, JSON fixtures, LRC data
├── network/
│   ├── SoundCloudEngineTest.kt         # Tier 1 & Tier 2: Client ID scraping & HLS resolution
│   ├── YouTubeInnerTubeTest.kt         # Tier 1 & Tier 2: WEB_REMIX search & stream resolution
│   ├── CdnTokenValidatorTest.kt        # Tier 1 & Tier 2: Token expiration & Range probing
│   ├── SingleFlightMutexTest.kt        # Tier 1 & Tier 2: Concurrent deduplication & 403 recovery
│   └── LrcLyricsParserTest.kt          # Tier 1 & Tier 2: Synced LRC timestamps & LRCLIB client
├── data/
│   ├── RoomDatabaseTier1Test.kt        # Tier 1: All 8 entities CRUD, foreign keys, constraints
│   ├── RoomDatabaseTier2Test.kt        # Tier 2: FTS5 triggers, WAL stress, large batch transactions
│   ├── BackupV1ServiceTest.kt          # Tier 1 & Tier 2: NeDotifyBackupV1 import/export & sanitization
│   ├── DataStorePreferencesTest.kt     # Tier 1 & Tier 2: Settings reactivity & default values
│   └── DownloadManagerTest.kt          # Tier 1 & Tier 2: Concurrency <= 2, resume, MediaStore
├── service/
│   ├── MediaServiceCoreTest.kt         # Tier 1 & Tier 2: MediaSessionService & foreground notifications
│   ├── AudioFocusBecomingNoisyTest.kt  # Tier 1 & Tier 2: Ducking, pause on call, headphone disconnect
│   └── PlaybackQueueManagerTest.kt     # Tier 1 & Tier 2: Repeat, shuffle, history backstack
├── ui/
│   ├── MidnightThemeAccessibilityTest.kt# Tier 1 & Tier 2: Midnight palette, touch targets >= 48dp
│   ├── GlassmorphicBlurTest.kt         # Tier 1 & Tier 2: RenderEffect API 31+ vs fallback API 26-30
│   ├── MiniPlayerComponentTest.kt      # Tier 1 & Tier 2: 68dp capsule, progress, marquee, taps
│   └── KineticLyricsComponentTest.kt   # Tier 1 & Tier 2: Active line scale 1.08x, glow, tap-to-seek
└── e2e/
    ├── CrossFeatureTier3Test.kt        # Tier 3: Pairwise cross-subsystem interactions
    └── UserWorkflowsTier4Test.kt       # Tier 4: Real-world end-to-end user journeys
```

### 1.4. Seam Definitions & Boundary Contracts
| Seam | Public Boundary | What it Catches | What it Misses |
|---|---|---|---|
| **S1: Network Seam** | `OkHttpClient` interceptors & `MockWebServer` HTTP requests/responses | Network parsing errors, expired CDN tokens, HTTP 403/429/500 recovery, malformed payloads | Underlying cellular/Wi-Fi radio hardware firmware bugs |
| **S2: Database Seam** | `NeDotifyDatabase` Room DAOs & SQLite Transactions | Foreign key violations, UNIQUE constraint failures, WAL serialization bugs, FTS5 index sync | Underlying OS-level flash storage hardware corruption |
| **S3: Audio & Media Seam**| `MediaSession`, `MediaController`, `PlaybackQueueManager` | Audio focus ducking, becoming noisy pause, repeat/shuffle invariants, history navigation | Physical DSP audio DAC hardware distortion |
| **S4: Storage Seam** | `DownloadStore`, `File` operations, `ContentResolver` / `MediaStore` | Range resumption (.part), concurrency limits, Windows path sanitization, atomic replace | SD-card physical wear |
| **S5: UI Semantics Seam**| Compose `SemanticsNodeInteractionsProvider` | Layout hierarchy, >=48dp touch targets, state binding, kinetic lyrics auto-scroll | Display panel OLED hardware color calibration |

---

## 2. Tier 1 — Feature Coverage Test Specifications (>=5 per Feature)

Every core feature in `PROJECT.md § Feature Inventory` is mapped to at least 5 isolated, opaque-box test cases.

### Category 1: SoundCloud Streaming & Scraping Engine (Features 2, 3)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R1`, Desktop `survey_spec_report.md § 2 (Item 10)`, `services/soundcloud.py`

#### TC-T1-SC-01: Scraping active client_id from asset JavaScript bundle
* **Input**: HTML containing script tag `<script src="https://a-v2.sndcdn.com/assets/2-4f81c9a1.js"></script>` and JS bundle containing `client_id:"A2bK90xZpLmNqRtuvwxYz1234567890"`.
* **Execution**: Call `SoundCloudClientScraper.extractClientId()`.
* **Authoritative Expected Output**: Returns `Result.success("A2bK90xZpLmNqRtuvwxYz1234567890")`. Scraped ID is alphanumeric, exactly 32 chars, and matches regex `^[a-zA-Z0-9]{32}$`.

#### TC-T1-SC-02: Resolving progressive MP3/AAC audio stream URL
* **Input**: Track ID `182930411` with transcoding object:
  ```json
  {"protocol": "progressive", "mime_type": "audio/mpeg", "url": "https://api-v2.soundcloud.com/media/soundcloud:tracks:182930411/progressive"}
  ```
  Mock API returns `{"url": "https://cf-media.sndcdn.com/stream.mp3?Expires=1799999999&Signature=xyz"}`.
* **Execution**: Call `SoundCloudStreamResolver.resolve(trackId = "182930411")`.
* **Authoritative Expected Output**: `StreamInfo.url` equals the signed CDN link; `StreamInfo.isHls` is `false`; `StreamInfo.expiresAtSeconds` equals `1799999999L`.

#### TC-T1-SC-03: Resolving HLS `.m3u8` master and segment transcodings
* **Input**: Transcoding with protocol `"hls"` and mime_type `"audio/ogg; codecs=\"opus\""`. Mock server serves M3U8 master playlist pointing to audio chunk playlists.
* **Execution**: Call `SoundCloudStreamResolver.resolveHlsTranscoding(mediaUrl)`.
* **Authoritative Expected Output**: Returns `StreamInfo` with `isHls = true`, URL ending with `.m3u8`, and Media3 `MimeTypes.APPLICATION_M3U8` compatibility flag.

#### TC-T1-SC-04: Filtering preview snippets (duration < 30s) from full tracks
* **Input**: Track payload with `duration = 30000` (30 seconds) and `snipped = true` vs full track with `duration = 245000` and `snipped = false`.
* **Execution**: Pass tracks through `SoundCloudTrackMapper.filterPlayable()`.
* **Authoritative Expected Output**: Snippet track is flagged with `isSnippet = true`; full track has `isSnippet = false`; playable track list includes only full tracks.

#### TC-T1-SC-05: Track metadata mapping from SoundCloud JSON to domain TrackItem
* **Input**: SoundCloud v2 JSON:
  `{"id": 998877, "title": "Synth Breeze", "user": {"username": "RetroWave"}, "duration": 185000, "artwork_url": "https://i1.sndcdn.com/art-large.jpg"}`.
* **Execution**: Call `SoundCloudMapper.toTrackItem(json)`.
* **Authoritative Expected Output**: `TrackItem(source = "soundcloud", sourceId = "998877", title = "Synth Breeze", artist = "RetroWave", durationSeconds = 185.0, coverUrl = "https://i1.sndcdn.com/art-t500x500.jpg")`. (Validates automatic upgrade of artwork from `large` to `t500x500`).

---

### Category 2: YouTube InnerTube API & Fallback (Feature 4)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R1`, Desktop `survey_desktop_report.md § 4.3`, `services/youtube.py`

#### TC-T1-YT-01: WEB_REMIX client search query execution
* **Input**: Query `"The Weeknd Starboy"`, client context `WEB_REMIX` with user-agent and clientVersion `1.20241014.01.00`.
* **Execution**: Execute `YouTubeInnerTubeSearch.search("The Weeknd Starboy")`.
* **Authoritative Expected Output**: Returns non-empty list of search items where each item contains `source = "youtube"`, 11-character video ID matching `^[a-zA-Z0-9_-]{11}$`, non-empty title, and valid YouTube thumbnail URL.

#### TC-T1-YT-02: YouTube stream format filtering (audio/mp4 & webm/opus)
* **Input**: InnerTube player response containing formats: Video/MP4 1080p (itag 137), Audio/MP4 128kbps (itag 140), Audio/WebM 160kbps (itag 251).
* **Execution**: Call `YouTubeFormatSelector.selectBestAudio(formats)`.
* **Authoritative Expected Output**: Selects itag 251 (Opus 160kbps) as highest audio quality, extracts `contentLength` and `approxDurationMs`.

#### TC-T1-YT-03: Automatic fallback from SoundCloud failure to YouTube stream
* **Input**: Track request for `"Artist - Title"` where SoundCloud returns HTTP 404 or geo-restricted error.
* **Execution**: Call `StreamResolutionCoordinator.resolveWithFallback(track)`.
* **Authoritative Expected Output**: Coordinator logs SoundCloud failure, invokes `YouTubeInnerTubeSearch.search("Artist - Title")`, resolves YouTube stream URL, and returns valid `StreamInfo` with `source = "youtube"`.

#### TC-T1-YT-04: Duration tolerance check (within ±15% of target duration)
* **Input**: Target track duration = 200 seconds. Search candidates: Candidate A (duration = 195s, diff = 2.5%), Candidate B (duration = 600s, diff = 200%, full live concert).
* **Execution**: Call `YouTubeSearchMatcher.findBestMatch(candidates, targetDuration = 200.0)`.
* **Authoritative Expected Output**: Candidate A is selected; Candidate B is rejected because `abs(candidate.duration - target) / target > 0.15`.

#### TC-T1-YT-05: YouTube video title and artist normalization
* **Input**: Raw YouTube titles: `"The Weeknd - Starboy (Official Music Video) ft. Daft Punk"`, `"[OFFICIAL HD] Queen - Bohemian Rhapsody (Lyrics)"`.
* **Execution**: Call `TitleNormalizer.clean(rawTitle)`.
* **Authoritative Expected Output**: Titles cleaned of junk tags: `"The Weeknd - Starboy"`, `"Queen - Bohemian Rhapsody"`.

---

### Category 3: CDN Token Expiry & Range Probing (Features 5, 8)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R1`, Desktop `utils/stream_urls.py`, `survey_desktop_report.md § 2.3`

#### TC-T1-CDN-01: Parameter parsing for SoundCloud `Expires` timestamp
* **Input**: URL `https://cf-media.sndcdn.com/audio.mp3?Expires=1729290000&Signature=abc&Key-Pair-Id=K123`.
* **Execution**: Call `CdnTokenValidator.extractExpirationTimestamp(url)`.
* **Authoritative Expected Output**: Returns `1729290000L`.

#### TC-T1-CDN-02: Parameter parsing for YouTube `expire` timestamp
* **Input**: URL `https://rr2---sn-4g5ednks.googlevideo.com/videoplayback?expire=1729350000&ei=xyz&ip=1.2.3.4`.
* **Execution**: Call `CdnTokenValidator.extractExpirationTimestamp(url)`.
* **Authoritative Expected Output**: Returns `1729350000L`.

#### TC-T1-CDN-03: Pre-playback expiration validation with 60s safety margin
* **Input**: Current time = `1729200000L`. URL with `expire = 1729200045L` (45s remaining). Margin = `60L` seconds.
* **Execution**: Call `CdnTokenValidator.isExpired(url, currentTime = 1729200000L, marginSeconds = 60L)`.
* **Authoritative Expected Output**: Returns `true` (expired) because `1729200045 <= (1729200000 + 60)`. URL is refreshed before playback.

#### TC-T1-CDN-04: HTTP Range 0-1 byte probe returning HTTP 206
* **Input**: Stream URL on MockWebServer configured to respond to `Range: bytes=0-1` with HTTP 206, `Content-Range: bytes 0-1/5420310`, and 2 bytes payload `[0xFF, 0xFB]`.
* **Execution**: Call `StreamLivenessProber.probe(url)`.
* **Authoritative Expected Output**: Returns `LivenessResult.Alive(totalBytes = 5420310L, mimeType = "audio/mpeg")`.

#### TC-T1-CDN-05: Range probe detection of dead link (HTTP 404 / 410)
* **Input**: MockWebServer returning HTTP 410 Gone for stream URL.
* **Execution**: Call `StreamLivenessProber.probe(url)`.
* **Authoritative Expected Output**: Returns `LivenessResult.Dead(httpCode = 410)`.

---

### Category 4: Single-Flight Mutex & 403 Renewal (Features 6, 7)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R1`, `PROJECT.md § Interface Contracts`, Desktop `core/playback_recovery.py`

#### TC-T1-SF-01: Deduplicating 5 concurrent requests for identical track ID
* **Input**: Track ID `"sc_12345"`. 5 concurrent coroutines calling `SingleFlightMutex.executeOrJoin("sc_12345") { fetchStreamNetwork("sc_12345") }`.
* **Execution**: Launch all 5 coroutines simultaneously in `runTest`.
* **Authoritative Expected Output**: The network fetch lambda is executed **exactly once** (`fetchCount == 1`). All 5 coroutines complete and receive the exact same `StreamInfo`.

#### TC-T1-SF-02: Independent execution for distinct track IDs in Single-Flight Mutex
* **Input**: Coroutine 1 requests `"sc_101"`, Coroutine 2 requests `"sc_102"`.
* **Execution**: Launch both coroutines simultaneously.
* **Authoritative Expected Output**: Both network fetches execute independently (`fetchCount == 2`). Neither blocks the other.

#### TC-T1-SF-03: Intercepting HTTP 403 in ExoPlayer error listener
* **Input**: ExoPlayer playback triggers `PlaybackException` with `errorCode = ERROR_CODE_IO_BAD_HTTP_STATUS` and cause contains HTTP 403.
* **Execution**: Player error listener passes exception to `PlaybackRecoveryHandler.handleError(exception)`.
* **Authoritative Expected Output**: Handler identifies error as `RecoveryAction.RENEW_STREAM`, suppresses fatal crash, and requests fresh stream URL.

#### TC-T1-SF-04: Invalidation of expired stream in `stream_cache`
* **Input**: Database `stream_cache` table contains row for `("soundcloud", "12345")` with expired URL.
* **Execution**: Call `StreamCacheDao.invalidate("soundcloud", "12345")`.
* **Authoritative Expected Output**: Row is deleted from `stream_cache`; subsequent lookup returns `null`.

#### TC-T1-SF-05: Transparent stream renewal and position resumption
* **Input**: Track playing at `positionMs = 74000L` encounters 403. Renewal returns fresh URL.
* **Execution**: Call `NeDotifyMediaService.renewAndSeek(freshUrl, positionMs = 74000L)`.
* **Authoritative Expected Output**: ExoPlayer is prepared with fresh `MediaItem`, seeks to `74000L`, and resumes playing without resetting user playback progress.

---

### Category 5: LRCLIB Synced Lyrics Client & Parser (Features 9, 34)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R1`, Desktop `lyrics_service.py:361-430`, `survey_spec_report.md § 4.7`

#### TC-T1-LRC-01: Standard centisecond timestamp parsing `[mm:ss.xx]`
* **Input**: Line `"[01:23.45]Just a young gun with a quick gun"`.
* **Execution**: Call `LrcParser.parseLine(line)`.
* **Authoritative Expected Output**: Emits `LyricLine(timeMs = 83450L, text = "Just a young gun with a quick gun")`.
  * *Formula derivation*: `(1 * 60 + 23) * 1000 + 45 * 10 = 83000 + 450 = 83450ms`.

#### TC-T1-LRC-02: Millisecond timestamp parsing `[mm:ss.xxx]`
* **Input**: Line `"[02:05.123]Thunder feel the thunder"`.
* **Execution**: Call `LrcParser.parseLine(line)`.
* **Authoritative Expected Output**: Emits `LyricLine(timeMs = 125123L, text = "Thunder feel the thunder")`.
  * *Formula derivation*: `(2 * 60 + 5) * 1000 + 123 = 125000 + 123 = 125123ms`.

#### TC-T1-LRC-03: Multi-timestamp single line parsing
* **Input**: Line `"[00:15.00][00:45.00]Lightning and the thunder"`.
* **Execution**: Call `LrcParser.parse(lrcText)`.
* **Authoritative Expected Output**: Emits two discrete `LyricLine` objects:
  1. `LyricLine(timeMs = 15000L, text = "Lightning and the thunder")`
  2. `LyricLine(timeMs = 45000L, text = "Lightning and the thunder")`
  Sorted chronologically.

#### TC-T1-LRC-04: Header tag parsing and offset adjustment `[offset: +/-ms]`
* **Input**:
  ```text
  [ti:Radioactive]
  [offset:500]
  [00:10.00]I'm waking up
  ```
* **Execution**: Call `LrcParser.parse(lrcText)`.
* **Authoritative Expected Output**: Header `[ti:Radioactive]` is excluded from lyric lines. Offset `+500` is applied: line timestamp becomes `10000 + 500 = 10500L`.

#### TC-T1-LRC-05: LRCLIB API client lookup by artist, title, album, and duration
* **Input**: `artist = "Imagine Dragons"`, `trackName = "Radioactive"`, `albumName = "Night Visions"`, `duration = 186.0`. MockWebServer responds with LRCLIB JSON payload.
* **Execution**: Call `LrclibClient.fetchLyrics(artist, trackName, albumName, duration)`.
* **Authoritative Expected Output**: Returns parsed `LyricsData(syncedLyrics = List<LyricLine>, plainLyrics = "...", isSynced = true)`.

---

### Category 6: Room SQLite WAL Database & All 8 Entities (Features 10, 11-18)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R2`, `PROJECT.md § Code Layout`, `survey_desktop_report.md § 1`

#### TC-T1-DB-01: SQLite WAL mode verification and foreign keys PRAGMA
* **Input**: Initialized Room Database instance.
* **Execution**: Query `PRAGMA journal_mode;` and `PRAGMA foreign_keys;`.
* **Authoritative Expected Output**: `journal_mode` returns `"wal"` (or `"memory"` for in-memory test DB); `foreign_keys` returns `1`.

#### TC-T1-DB-02: `tracks` CRUD and uniqueness on `file_path`
* **Input**: Insert Track A with `file_path = "/sdcard/music/song1.mp3"`. Attempt to insert Track B with identical `file_path`.
* **Execution**: Call `TrackDao.insert(trackA)` followed by `TrackDao.insert(trackB)`.
* **Authoritative Expected Output**: First insert succeeds returning generated `id = 1L`. Second insert throws `SQLiteConstraintException` due to `UNIQUE(file_path)` constraint.

#### TC-T1-DB-03: `playlists` and `playlist_tracks` cascading deletion
* **Input**: Playlist `id = 10` with 3 linked entries in `playlist_tracks` (`playlist_id = 10`).
* **Execution**: Call `PlaylistDao.deleteById(10)`.
* **Authoritative Expected Output**: Playlist 10 is deleted; `SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = 10` returns `0` due to `ON DELETE CASCADE`.

#### TC-T1-DB-04: `history` recorded plays and `listening_measured` session tracking
* **Input**: Track `id = 1`. Record listening session with `duration_listened = 180.0`, `media_listened = 180.0`, `completed = 1`, `listening_measured = 1`.
* **Execution**: Call `HistoryDao.insert(historyItem)` and query recent history.
* **Authoritative Expected Output**: History record persisted with timestamp; `completed` is `1`; `media_listened` is `180.0`.

#### TC-T1-DB-05: `stream_cache` TTL purge and `download_queue` track uniqueness
* **Input**:
  - `stream_cache`: Row with `expires_at = datetime('now', '-1 hour')` and `cached_file_path = NULL`.
  - `download_queue`: Row with `track_id = 42`. Attempt to insert second row with `track_id = 42`.
* **Execution**: Call `StreamCacheDao.purgeExpired()` and `DownloadQueueDao.insert(queueItem)`.
* **Authoritative Expected Output**: Expired cache row is deleted; duplicate `download_queue` insert throws `SQLiteConstraintException` (honoring `ux_download_queue_track`).

#### TC-T1-DB-06: `smart_playlists` rule JSON parsing and SQL generation
* **Input**: Smart playlist rule: `{"added_within_days": 30, "favorite": true, "sort": "newest"}`.
* **Execution**: Call `SmartPlaylistEvaluator.generateQuery(rule)`.
* **Authoritative Expected Output**: Generates query containing `is_favorite = 1 AND added_at >= datetime('now', '-30 days') ORDER BY added_at DESC`.

#### TC-T1-DB-07: `listening_stats` millisecond chunk telemetry aggregation
* **Input**: Insert chunks: `15000ms`, `15000ms`, `30000ms`.
* **Execution**: Call `ListeningStatsDao.getTotalListeningTimeMs()`.
* **Authoritative Expected Output**: Returns exactly `60000L` (60 seconds).

---

### Category 7: Versioned Backup V1 Service & Sanitization (Feature 19)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R2`, `survey_desktop_report.md § 2`, `NeDotifyBackupV1.json`

#### TC-T1-BK-01: Complete JSON export conforming to `NeDotifyBackupV1` schema
* **Input**: Database containing 2 tracks, 1 playlist, 1 smart playlist.
* **Execution**: Call `BackupV1Service.exportBackup()`.
* **Authoritative Expected Output**: JSON string containing `"version": "NeDotifyBackupV1"`, `"exported_by": "android"`, valid ISO-8601 `"exported_at"`, and matching `library.tracks`, `library.playlists`, and `settings` objects.

#### TC-T1-BK-02: Stripping Windows drive paths (`C:\...`) from `file_path`
* **Input**: Desktop track record with `file_path = "C:\\Users\\Артур\\Music\\track.mp3"`.
* **Execution**: Call `BackupV1Service.exportTrack(track)`.
* **Authoritative Expected Output**: The exported JSON track object has `"file_path": null` and `"is_downloaded": false`. Windows drive paths are completely stripped.

#### TC-T1-BK-03: Stripping ephemeral CDN tokens from `source_url`
* **Input**: Track with `source_url = "https://cf-media.sndcdn.com/stream.mp3?Expires=1729290000&Signature=abc"`.
* **Execution**: Call `BackupV1Service.sanitizeTrackForExport(track)`.
* **Authoritative Expected Output**: Ephemeral CDN link is sanitized; `source_url` is converted to canonical permalink or `null`.

#### TC-T1-BK-04: Import of backup into empty database with path sanitization
* **Input**: Valid `NeDotifyBackupV1` JSON file containing 10 tracks with mixed Windows paths and canonical URLs.
* **Execution**: Call `BackupV1Service.importBackup(jsonString)`.
* **Authoritative Expected Output**: Database contains 10 tracks; all 10 tracks have `file_path = NULL` and `is_downloaded = 0`; metadata, play counts, and favorites are preserved.

#### TC-T1-BK-05: Re-linking existing tracks without duplicating rows
* **Input**: Database already contains track `(source = "youtube", source_id = "34Na4j8AVgA")`. Import JSON contains same track with updated `play_count = 50`.
* **Execution**: Call `BackupV1Service.importBackup(jsonString)`.
* **Authoritative Expected Output**: Track count remains 1; `play_count` is updated to 50; no duplicate row created.

---

### Category 8: DataStore Preferences (Feature 20)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R2`, `PROJECT.md § Feature Inventory`

#### TC-T1-DS-01: Storing and observing theme preference (`midnight`, `dark`, `amoled`)
* **Input**: Preference key `THEME_MODE`, set to `"midnight"`.
* **Execution**: Write `"midnight"` via `PreferencesRepository.setThemeMode("midnight")` and read Flow.
* **Authoritative Expected Output**: Flow immediately emits `"midnight"`.

#### TC-T1-DS-02: Storing audio normalization & target LUFS (-14.0)
* **Input**: Preference keys `NORMALIZATION_ENABLED = true`, `TARGET_LUFS = -14.0f`.
* **Execution**: Save settings and retrieve `AudioSettings` object.
* **Authoritative Expected Output**: `audioSettings.normalizationEnabled` is `true`; `audioSettings.targetLufs` is `-14.0f`.

#### TC-T1-DS-03: Storing crossfade enabled and duration (3s)
* **Input**: `crossfadeEnabled = true`, `crossfadeDurationSeconds = 3`.
* **Execution**: Save settings via repository.
* **Authoritative Expected Output**: Read returns `crossfadeEnabled == true` and `crossfadeDuration == 3`.

#### TC-T1-DS-04: Storing auto-download favorites flag
* **Input**: `autoDownloadFavorites = true`.
* **Execution**: Save preference via repository.
* **Authoritative Expected Output**: Flow emits `true`; toggle is persistent.

#### TC-T1-DS-05: Default settings values on clean install
* **Input**: Fresh DataStore instance with empty storage.
* **Execution**: Read initial `AppSettings` Flow.
* **Authoritative Expected Output**: Default values: `theme = "midnight"`, `normalization = false`, `targetLufs = -14.0f`, `crossfade = false`, `crossfadeDuration = 3`, `autoDownloadFavorites = false`.

---

### Category 9: Resumable Download Manager & MediaStore Export (Features 21, 22)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R2`, `survey_desktop_report.md § 3`, `core/downloader.py`

#### TC-T1-DL-01: Enqueue download task with `pending` status
* **Input**: Track ID `501`, remote stream URL.
* **Execution**: Call `DownloadManager.enqueue(trackId = 501)`.
* **Authoritative Expected Output**: `download_queue` record created with `status = "pending"`, `downloaded_bytes = 0`, `total_bytes = 0`.

#### TC-T1-DL-02: Strict concurrency capping at 2 active downloads
* **Input**: Enqueue 5 tracks simultaneously: `[101, 102, 103, 104, 105]`.
* **Execution**: Start download queue processor.
* **Authoritative Expected Output**: Exactly **2 tracks** transition to `status = "downloading"`; remaining 3 tracks remain in `status = "pending"`. Concurrency is strictly bounded by `Semaphore(2)`.

#### TC-T1-DL-03: Resumption of partial file via HTTP Range request
* **Input**: Existing `.part` file of 1,000,000 bytes on disk. Total file size = 5,000,000 bytes. MockWebServer configured to accept `Range: bytes=1000000-`.
* **Execution**: Call `DownloadManager.resumeDownload(trackId)`.
* **Authoritative Expected Output**: Outbound request header contains `Range: bytes=1000000-`; MockWebServer returns HTTP 206; downloaded bytes are appended to the `.part` file starting at byte 1,000,000.

#### TC-T1-DL-04: Audio file validation (min 4KB, audio extension) before completion
* **Input**: Completed download file with size = 2,048,000 bytes and extension `.m4a`.
* **Execution**: Run `DownloadVerifier.verifyFile(file)`.
* **Authoritative Expected Output**: Returns `VerificationResult.Valid`. (File < 4096 bytes or invalid extension returns `Invalid`).

#### TC-T1-DL-05: Export completed track to MediaStore `Music/NeDotify`
* **Input**: Downloaded local file `/data/user/0/com.nedotify/files/downloads/track_501.m4a` with metadata Title = "Starboy", Artist = "The Weeknd".
* **Execution**: Call `MediaStoreExporter.exportToPublicMusic(context, track)`.
* **Authoritative Expected Output**: New row inserted into `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = "Music/NeDotify"`, `TITLE = "Starboy"`, `ARTIST = "The Weeknd"`, and file content streamed successfully.

---

### Category 10: NeDotifyMediaService & Notifications (Features 23, 24)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R3`, `survey_spec_report.md § 5`, AndroidX Media3 specifications

#### TC-T1-MS-01: Foreground service initialization with `mediaPlayback` type
* **Input**: Service started via `startForegroundService(intent)`.
* **Execution**: Service `onCreate()` and `onStartCommand()`.
* **Authoritative Expected Output**: Calls `startForeground()` with notification ID, `Notification`, and `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`.

#### TC-T1-MS-02: Media notification posting with metadata (title, artist, artwork)
* **Input**: Active track: Title = "Blinding Lights", Artist = "The Weeknd", CoverUri = "https://...".
* **Execution**: Trigger `MediaNotificationProvider.createNotification()`.
* **Authoritative Expected Output**: Returns `Notification` with `style = MediaStyle`, content title = "Blinding Lights", content text = "The Weeknd", action buttons for Previous, Play/Pause, Next.

#### TC-T1-MS-03: MediaSession playback command handling (play, pause, skip)
* **Input**: Remote `MediaController` sends `session.player.pause()` and `session.player.seekToNextMediaItem()`.
* **Execution**: Service receives commands through MediaSession callback.
* **Authoritative Expected Output**: Underlying ExoPlayer is paused; queue advances to next item.

#### TC-T1-MS-04: Background playback survival when app is minimized
* **Input**: Activity enters `onStop()` / `onDestroy()`.
* **Execution**: Check `NeDotifyMediaService` status.
* **Authoritative Expected Output**: Service remains running in foreground; audio continues playing without interruption; wake lock `C.WAKE_MODE_NETWORK` held.

#### TC-T1-MS-05: Lock screen and Bluetooth AVRCP transport control integration
* **Input**: Bluetooth AVRCP key event `KEYCODE_MEDIA_NEXT`.
* **Execution**: Send media key event to `MediaSession`.
* **Authoritative Expected Output**: `MediaSession` dispatches skip action; next track begins playing.

---

### Category 11: Audio Focus & Becoming Noisy (Features 25, 26)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R3`, `survey_spec_report.md § 5.3, 5.4`

#### TC-T1-AF-01: Transient focus loss with ducking: volume reduces to 0.20f
* **Input**: Playing track at volume `1.0f`. `AudioManager` emits `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`.
* **Execution**: Call `AudioFocusHelper.onAudioFocusChange(AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)`.
* **Authoritative Expected Output**: Player volume is smoothly lowered to `0.20f`; player continues playing.

#### TC-T1-AF-02: Regaining focus: volume smoothly restores to 1.0f
* **Input**: Player currently ducked at volume `0.20f`. `AudioManager` emits `AUDIOFOCUS_GAIN`.
* **Execution**: Call `AudioFocusHelper.onAudioFocusChange(AUDIOFOCUS_GAIN)`.
* **Authoritative Expected Output**: Player volume is restored to `1.0f`.

#### TC-T1-AF-03: Transient focus loss without ducking (incoming phone call): immediate pause
* **Input**: Playing track. `AudioManager` emits `AUDIOFOCUS_LOSS_TRANSIENT`.
* **Execution**: Call `AudioFocusHelper.onAudioFocusChange(AUDIOFOCUS_LOSS_TRANSIENT)`.
* **Authoritative Expected Output**: Player is paused immediately; `isPlaying` becomes `false`.

#### TC-T1-AF-04: Regaining focus after phone call ends: resume playback
* **Input**: Player paused due to `AUDIOFOCUS_LOSS_TRANSIENT`. `AudioManager` emits `AUDIOFOCUS_GAIN`.
* **Execution**: Call `AudioFocusHelper.onAudioFocusChange(AUDIOFOCUS_GAIN)`.
* **Authoritative Expected Output**: Player automatically resumes playback; `isPlaying` becomes `true`.

#### TC-T1-AF-05: `ACTION_AUDIO_BECOMING_NOISY` broadcast triggers immediate pause
* **Input**: Playing music through wired or Bluetooth headphones. Broadcast intent `AudioManager.ACTION_AUDIO_BECOMING_NOISY` received.
* **Execution**: `BecomingNoisyReceiver.onReceive(context, intent)`.
* **Authoritative Expected Output**: Player is immediately paused; speaker audio output is prevented.

---

### Category 12: PlaybackQueueManager Engine (Feature 27)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R3`, Desktop `audio/queue.py:11-469`, `survey_spec_report.md § 5.6`

#### TC-T1-QM-01: Basic queue loading and sequential navigation (next / previous)
* **Input**: Queue with 3 tracks: `[A, B, C]`. Start at index 0 (Track A).
* **Execution**: Call `queueManager.nextTrack()`, then `queueManager.previousTrack()`.
* **Authoritative Expected Output**: After `nextTrack()`, `currentIndex == 1` (Track B). After `previousTrack()`, `currentIndex == 0` (Track A).

#### TC-T1-QM-02: Repeat mode cycling: OFF -> ALL -> ONE -> OFF
* **Input**: Current repeat mode = `RepeatMode.OFF`.
* **Execution**: Call `queueManager.cycleRepeatMode()` 3 times.
* **Authoritative Expected Output**:
  1. Transition 1: `RepeatMode.ALL`.
  2. Transition 2: `RepeatMode.ONE`.
  3. Transition 3: `RepeatMode.OFF`.

#### TC-T1-QM-03: Shuffle mode enabling: pins current track to index 0, shuffles rest
* **Input**: 5 tracks `[T0, T1, T2, T3, T4]`. Current track is `T2` (`currentIndex = 2`).
* **Execution**: Call `queueManager.toggleShuffle()`.
* **Authoritative Expected Output**: `isShuffleEnabled` becomes `true`; `currentIndex` becomes `0`; track at index 0 is `T2`; remaining tracks `[T0, T1, T3, T4]` are shuffled in indices 1..4; all 5 original tracks are present.

#### TC-T1-QM-04: Shuffle mode disabling: restores exact `_original_order`
* **Input**: Shuffled queue from TC-T1-QM-03. Current playing track is `T2`.
* **Execution**: Call `queueManager.toggleShuffle()`.
* **Authoritative Expected Output**: `isShuffleEnabled` becomes `false`; tracks list is restored to original `[T0, T1, T2, T3, T4]`; `currentIndex` is repositioned to `2` (matching `T2`).

#### TC-T1-QM-05: History backstack navigation (up to 100 entries) in shuffle mode
* **Input**: Shuffled playback sequence: Played T2 -> T4 -> T1. Current track is T1.
* **Execution**: Call `queueManager.previousTrack()`.
* **Authoritative Expected Output**: Traverses `historyStack` in LIFO order; navigates to T4 (the track heard immediately prior), rather than numerical decrement in the shuffled list.

---

### Category 13: Jetpack Compose UI & Design System (Features 29-38)
* **Authoritative Source**: `ORIGINAL_REQUEST.md § R4`, `survey_spec_report.md § 4`, Compose testing specs

#### TC-T1-UI-01: Midnight theme `#0A0E17` color tokens applied to screen root
* **Input**: Compose screen rendered within `NeDotifyTheme(themeMode = "midnight")`.
* **Execution**: Inspect surface background color token in Compose semantics tree.
* **Authoritative Expected Output**: Root background matches `NeDotifyColors.BackgroundBase` (`Color(0xFF0A0E17)`).

#### TC-T1-UI-02: Adaptive blur: RenderEffect on API 31+ vs translucent fallback on API 26-30
* **Input**: Surface with `Modifier.glassmorphism()` tested under API 33 (Tiramisu) vs API 28 (Pie).
* **Execution**: Measure modifier chain output.
* **Authoritative Expected Output**:
  - API 33: Modifier includes `RenderEffect.createBlurEffect()`.
  - API 28: Modifier falls back to solid `#F0101622` background with 1dp `#263B82F6` border; no `RenderEffect` called.

#### TC-T1-UI-03: Touch target accessibility: interactive components >= 48dp x 48dp
* **Input**: Mini-player buttons (Play/Pause, Favorite, Next) and navigation items.
* **Execution**: Measure touch bounds using `composeTestRule.onNodeWithContentDescription("Play").getBoundsInRoot()`.
* **Authoritative Expected Output**: Width >= 48.dp and Height >= 48.dp on all interactive elements.

#### TC-T1-UI-04: Mini-player docked above bottom navigation with 68dp capsule
* **Input**: Active track playing. Main screen with bottom navigation.
* **Execution**: Assert mini-player presence and height.
* **Authoritative Expected Output**: Mini-player capsule node exists, has height = 68.dp, displays title, artist, and play/pause button.

#### TC-T1-UI-05: Kinetic lyrics active line styling: 1.08x scale and purple glow
* **Input**: `KineticLyricsView` displaying lyrics. Playback position matches Line 3 (`timeMs = 30000L`).
* **Execution**: Query Line 3 semantics.
* **Authoritative Expected Output**: Line 3 is marked active, rendered with scale `1.08f`, font weight `ExtraBold (800)`, and purple accent highlight.

---

## 3. Tier 2 — Boundary & Corner Case Test Specifications (>=5 per Feature)

Tier 2 targets severe edge cases, corrupted data, boundary timestamps, network anomalies, and race conditions.

### Category 1: SoundCloud & Network Edge Cases
* **TC-T2-SC-01**: SoundCloud asset JS bundle obfuscated or client_id pattern shifted. Test regex fallback patterns; verify error handling returns `Result.failure(ClientIdExtractionException)`.
* **TC-T2-SC-02**: SoundCloud stream URL resolution returns HTTP 429 Too Many Requests with `Retry-After: 5`. Test that client backs off for 5 seconds rather than crashing.
* **TC-T2-SC-03**: M3U8 master playlist contains zero audio bandwidth streams. Parser emits `EmptyPlaylistException` and triggers YouTube fallback.
* **TC-T2-SC-04**: Track duration is 0.0 seconds or negative. System clamps duration to 0 and disables progress slider scrubbing.
* **TC-T2-SC-05**: Asset HTML script tag has malformed URL or returns 500 Internal Server Error. Scraper iterates to backup script tag in DOM.

### Category 2: YouTube & Fallback Edge Cases
* **TC-T2-YT-01**: YouTube video requires age verification / login (`LOGIN_REQUIRED` status). Format selector catches failure, logs warning, and rejects candidate.
* **TC-T2-YT-02**: YouTube search query containing extreme special characters (`!@#$%^&*()_+={}|[]:;"'<>?,./~```). Validates URL encoding without crashing.
* **TC-T2-YT-03**: Network socket times out after 10,000ms during stream signature deciphering. Verifies timeout cancellation and retry trigger.
* **TC-T2-YT-04**: YouTube candidate audio stream is completely silent (bitrate = 0 or fileSize < 1000 bytes). Validates rejection in format picker.
* **TC-T2-YT-05**: Both SoundCloud and YouTube return zero results for obscure title. System emits `TrackUnavailableException` with user-friendly message.

### Category 3: CDN Token Expiry & Range Boundary Cases
* **TC-T2-CDN-01**: CDN URL contains timestamp `expire = 0` or negative number. Validator immediately flags URL as expired.
* **TC-T2-CDN-02**: CDN URL contains non-numeric string `expire = abc123xyz`. Validator catches `NumberFormatException` safely and treats URL as expired.
* **TC-T2-CDN-03**: Current time exactly equals expiration timestamp (`now == expire`). Margin 60s correctly classifies token as expired.
* **TC-T2-CDN-04**: Server responds to `Range: bytes=0-1` with HTTP 200 OK instead of HTTP 206 Partial Content (ignoring Range header). Prober accepts stream as alive if content length > 0.
* **TC-T2-CDN-05**: Range request returns HTTP 416 Range Not Satisfiable. Prober flags stream URL as dead, triggering re-resolution.

### Category 4: Single-Flight Mutex & 403 Renewal Edge Cases
* **TC-T2-SF-01**: In-flight request in SingleFlightMutex throws network `IOException`. Verifies that exception propagates to all joined callers and the mutex cleanly unlocks without deadlock.
* **TC-T2-SF-02**: 10 rapid 403 Forbidden errors within 2 seconds (infinite 403 renewal loop). Recovery circuit breaker halts after 3 consecutive failures to protect network and battery.
* **TC-T2-SF-03**: 403 occurs during track seek to position beyond track end (`seekMs > durationMs`). System clamps position to valid range before renewing.
* **TC-T2-SF-04**: Coroutine cancellation while awaiting SingleFlightMutex result. Awaiting caller cancels cleanly without aborting the shared in-flight fetch for other callers.
* **TC-T2-SF-05**: Stream renewal succeeds but returns the exact same expired URL. Validator rejects refreshed URL and forces upstream cache bypass.

### Category 5: LRC Parsing Boundary & Anomaly Cases
* **TC-T2-LRC-01**: Malformed timestamp brackets: `[01:23.45`, `01:23.45]`, `[1:2:3:4]`. Parser ignores invalid syntax and continues parsing subsequent lines.
* **TC-T2-LRC-02**: Negative offset header: `[offset: -50000]`. All line timestamps offset backwards, strictly clamped to minimum `0ms` (no negative timestamps).
* **TC-T2-LRC-03**: Empty line text or line with only whitespace: `"[00:15.00]   "`. Text is replaced with musical symbol `♪` to preserve karaoke cadence without collapsing layout.
* **TC-T2-LRC-04**: Out-of-order timestamps in LRC file (`[00:50.00]` appears before `[00:10.00]`). Parser automatically sorts emitted lines by `timeMs ASC`.
* **TC-T2-LRC-05**: Extreme multi-byte Unicode and Cyrillic / CJK characters in lyrics. Verifies correct character preservation without mojibake or truncation.

### Category 6: Room Database Boundary & Stress Cases
* **TC-T2-DB-01**: Batch insert of 5,000 tracks in a single transaction. Verifies execution completes in < 1,500ms without memory exhaustion.
* **TC-T2-DB-02**: FTS5 search query with SQLite meta-characters (`*`, `"`, `OR`, `AND`, `NEAR`, `NOT`). Search query sanitizer prevents FTS syntax errors.
* **TC-T2-DB-03**: Inserting track with title length > 1,000 characters and empty artist. Verified that default `'Unknown Artist'` is applied.
* **TC-T2-DB-04**: Concurrent read on UI thread while large WAL write transaction is executing. Verifies zero UI thread blocking or SQLite database locked errors.
* **TC-T2-DB-05**: Cascading deletion of artist with 500 tracks and 2,000 history entries. Verifies atomic transaction integrity and complete cleanup.

### Category 7: Backup V1 Sanitization Boundary Cases
* **TC-T2-BK-01**: Windows UNC network paths: `\\\\server\\share\\music\\song.mp3`. Path sanitizer strips UNC path cleanly to `null`.
* **TC-T2-BK-02**: Windows path with forward slashes: `C:/Users/Music/song.mp3`. Regex `^[a-zA-Z]:[/\\\\]` catches and strips path.
* **TC-T2-BK-03**: Linux/Android local paths: `/storage/emulated/0/Music/song.mp3`. Export resets path to `null` to prevent broken references across devices.
* **TC-T2-BK-04**: Backup JSON file is empty (0 bytes) or corrupted syntax. Importer returns `Result.failure(MalformedBackupException)` without corrupting existing database.
* **TC-T2-BK-05**: Backup JSON has future version tag `"version": "NeDotifyBackupV99"`. Importer detects unsupported version and presents upgrade prompt.

### Category 8: DataStore Preferences Boundary Cases
* **TC-T2-DS-01**: Corrupted preferences file on disk. DataStore catches corruption, resets to default values, and logs warning without crashing app.
* **TC-T2-DS-02**: Rapid concurrent preference writes (100 writes in 100ms). Verifies atomic consistency and final state matches last write.
* **TC-T2-DS-03**: Setting target LUFS to extreme values (`+20.0f` or `-100.0f`). Sanitizer clamps values to valid audio range `[-30.0f, -6.0f]`.
* **TC-T2-DS-04**: Setting crossfade duration to negative value (`-5s`). Clamped to `0s` (disabled).
* **TC-T2-DS-05**: Setting unknown theme string `"neon_cyber"`. Falls back cleanly to `"midnight"`.

### Category 9: Download Manager & MediaStore Boundary Cases
* **TC-T2-DL-01**: Device storage full (`ENOSPC` / zero bytes free) during active download. Task halts, deletes partial `.part` file, and marks status as `failed` with error "No storage space".
* **TC-T2-DL-02**: Server ignores Range request and sends entire file again (HTTP 200). Downloader discards stale `.part` file, resets offset to 0, and writes cleanly.
* **TC-T2-DL-03**: Network drops mid-transfer at byte 2,500,000 of 5,000,000. Transfer pauses; `.part` file retains exact 2,500,000 bytes.
* **TC-T2-DL-04**: Rapid user toggling: Enqueue -> Cancel -> Resume -> Cancel within 500ms. Verifies cooperative cancellation generation (`_cancel_epoch`) prevents orphaned worker threads.
* **TC-T2-DL-05**: MediaStore insert fails due to permission denial on Android 13+ without `READ_MEDIA_AUDIO`. Exporter surfaces clear permission request callback.

### Category 10: Media3 Service & Audio Focus Boundary Cases
* **TC-T2-AF-01**: Audio focus flutters rapidly: Duck -> Regain -> Duck -> Loss within 200ms. Verifies volume animator smoothly reconciles without audio popping.
* **TC-T2-AF-02**: `ACTION_AUDIO_BECOMING_NOISY` broadcast arrives when player is already paused. Receiver performs safe no-op.
* **TC-T2-AF-03**: Incoming phone call during track transition (between tracks). Service pauses immediately and holds queue at the new track start.
* **TC-T2-AF-04**: Media notification dismissed by user swiping it away while playing. Ongoing notification cannot be dismissed while actively playing; dismiss only allowed when paused.
* **TC-T2-AF-05**: OS terminates service under memory pressure (`onTrimMemory`). Service saves playback position and queue state to DataStore before exit.

### Category 11: PlaybackQueueManager Boundary Cases
* **TC-T2-QM-01**: Queue contains exactly 1 track. Shuffle toggle enables without crashing; track remains at index 0. Next and Previous loop or stay on track depending on repeat mode.
* **TC-T2-QM-02**: Queue is completely empty (0 tracks). Next, Previous, Play, Shuffle all handle gracefully without `IndexOutOfBoundsException`.
* **TC-T2-QM-03**: Removing currently playing track from middle of queue. Next track starts playing immediately; queue shrinks by 1.
* **TC-T2-QM-04**: Calling `jumpTo(9999)` with out-of-bounds index. Clamped or rejected safely without state corruption.
* **TC-T2-QM-05**: User clears entire queue while playing. Playback stops, player resets, UI shows empty state.

### Category 12: Compose UI & Accessibility Boundary Cases
* **TC-T2-UI-01**: Android system font scaling set to maximum `200%`. Track titles, mini-player buttons, and navigation labels do not clip or overlap.
* **TC-T2-UI-02**: Device width is compact 320dp (narrow phone). Mini-player controls remain accessible; text uses marquee scroll.
* **TC-T2-UI-03**: Track title is 300 characters long. Title composable activates marquee scrolling after 1.5s delay.
* **TC-T2-UI-04**: Missing or 404 album cover art image. Composable displays default glassmorphic music note vector placeholder.
* **TC-T2-UI-05**: Rapid bottom navigation tab switching (10 taps in 1 second). NavHost handles backstack cleanly without duplicate destinations.

---

## 4. Tier 3 — Cross-Feature Integration Test Specifications (Pairwise Subsystems)

Tier 3 verifies integration contracts between intersecting subsystems.

### TC-T3-01: Downloading track while actively playing it from stream
* **Subsystems**: `NeDotifyMediaService` (ExoPlayer) ↔ `DownloadManager` (OkHttp).
* **Test Scenario**: Track ID 101 is streaming and currently playing at `positionMs = 30000L`. User taps "Download" button.
* **Expected Invariants**:
  - `DownloadManager` initiates background transfer into `.part` file.
  - ExoPlayer streaming playback is completely uninterrupted (no audio stutter or buffer underrun).
  - When download completes, `tracks.is_downloaded` becomes `1` and `file_path` points to local file.
  - ExoPlayer seamlessly transitions next play of Track 101 to the local file without streaming.

### TC-T3-02: Exporting backup while 2 background downloads are actively in flight
* **Subsystems**: `BackupV1Service` ↔ `DownloadManager` ↔ `NeDotifyDatabase`.
* **Test Scenario**: 2 downloads are in progress with `.part` files on disk. User triggers "Export Backup".
* **Expected Invariants**:
  - Exported JSON captures a consistent snapshot of `tracks` and `playlists`.
  - The 2 downloading tracks are exported with `file_path: null` and `is_downloaded: false` (since their transfers are incomplete).
  - `download_queue` and `stream_cache` tables are excluded from the exported JSON.
  - Background downloads continue without IO deadlock or database lock error.

### TC-T3-03: Queue shuffle while device is offline
* **Subsystems**: `PlaybackQueueManager` ↔ `RoomDatabase` ↔ `ConnectivityManager`.
* **Test Scenario**: Device has 10 tracks in queue (5 downloaded offline, 5 stream-only). Internet is disconnected. User enables Shuffle and taps Next.
* **Expected Invariants**:
  - Queue shuffles all tracks.
  - When navigating, player automatically skips unplayable stream-only tracks or flags them with offline badge.
  - Downloaded tracks play seamlessly with offline audio indicator.

### TC-T3-04: Kinetic lyrics synchronization during rapid user seek & pause
* **Subsystems**: `KineticLyricsView` ↔ `ExoPlayer` ↔ `LrcParser`.
* **Test Scenario**: User drags player scrubber from `00:10.00` to `02:30.00` while paused, then hits Play.
* **Expected Invariants**:
  - Lyrics viewport immediately snaps to the active line corresponding to `02:30.00`.
  - No out-of-order scrolling animations or jitter.
  - Active line expands to `1.08x` with purple glow; playback resumes synchronously.

### TC-T3-05: Incoming phone call (audio focus loss) during background download
* **Subsystems**: `AudioFocusHelper` ↔ `NeDotifyMediaService` ↔ `DownloadManager`.
* **Test Scenario**: User is streaming music while a batch download of 10 tracks is running. Incoming phone call triggers `AUDIOFOCUS_LOSS_TRANSIENT`.
* **Expected Invariants**:
  - Media playback immediately pauses (`player.pause()`).
  - Active background downloads continue downloading at full speed without interruption.
  - Call disconnects (`AUDIOFOCUS_GAIN`); media playback automatically resumes.

### TC-T3-06: Headphone disconnect (`BECOMING_NOISY`) in lock-screen state
* **Subsystems**: `BecomingNoisyReceiver` ↔ `MediaSessionService` ↔ `MediaNotificationProvider`.
* **Test Scenario**: Screen is locked. Music is playing through Bluetooth. Bluetooth headset powers off.
* **Expected Invariants**:
  - `ACTION_AUDIO_BECOMING_NOISY` is received.
  - Player pauses immediately.
  - Lock-screen media widget updates from "Playing" to "Paused" with Play icon.
  - Foreground notification remains present with Play button.

### TC-T3-07: Smart playlist dynamic evaluation after new track download and favorite toggle
* **Subsystems**: `SmartPlaylistEvaluator` ↔ `DownloadManager` ↔ `TrackDao`.
* **Test Scenario**: Smart playlist defined as `{"downloaded": true, "favorite": true}`. User favorites Track 50 and downloads it.
* **Expected Invariants**:
  - Room Flow for the smart playlist automatically re-evaluates and emits updated list containing Track 50.
  - No manual refresh or app restart required.

### TC-T3-08: MediaStore export of batch-downloaded playlist with unicode characters
* **Subsystems**: `MediaStoreExporter` ↔ `DownloadManager` ↔ `TagWriter`.
* **Test Scenario**: Batch download of playlist containing Cyrillic, Japanese, and emoji titles: `"Мой трек 🎵"`, `"夜に駆ける"`. User exports to public Music folder.
* **Expected Invariants**:
  - Files are written to `Music/NeDotify/` with valid UTF-8 metadata tags.
  - `MediaStore.Audio.Media` scanner indexes songs with matching titles and artists.

### TC-T3-09: Stream renewal (403 interceptor) while queue is set to Repeat One
* **Subsystems**: `PlaybackRecoveryHandler` ↔ `PlaybackQueueManager` ↔ `ExoPlayer`.
* **Test Scenario**: Queue mode is `RepeatMode.ONE`. Track loops and encounters 403 Forbidden on the second loop.
* **Expected Invariants**:
  - 403 interceptor renews stream URL.
  - Player resumes playback from beginning of the track.
  - Queue remains locked on the current track with `repeatMode == ONE`.

### TC-T3-10: Restoring backup JSON while media service is actively playing
* **Subsystems**: `BackupV1Service` ↔ `NeDotifyMediaService` ↔ `NeDotifyDatabase`.
* **Test Scenario**: User is listening to a local track. In Settings, user imports `NeDotifyBackupV1.json`.
* **Expected Invariants**:
  - Database tables are updated within atomic transaction.
  - Active playback of current track is NOT interrupted or crashed.
  - Library UI reactively updates to reflect restored playlists and favorites.

---

## 5. Tier 4 — Real-World Application Scenario Test Specifications

Tier 4 verifies complete, realistic multi-step user workflows end-to-end.

### Scenario 1 (SC-T4-01): Complete First-Time User Onboarding to Background Playback
* **Workflow Steps**:
  1. User launches NeDotify on clean install. Initial database and DataStore initialize with Midnight theme.
  2. Home screen displays dynamic greeting and empty-state recommendations.
  3. User taps "Поиск" (Search) in bottom navigation.
  4. User enters `"Starboy"` into the search capsule.
  5. Debounced query fires; MockWebServer returns search results.
  6. User taps track row for "The Weeknd - Starboy".
  7. Stream URL is resolved via SingleFlightMutex; docked mini-player appears with cover art and title.
  8. User taps mini-player; full-screen player slides up.
  9. Synced lyrics load from LRCLIB; active line highlights and scrolls.
  10. User minimizes application (home button).
  11. System media notification appears in status bar; audio plays continuously in background.
* **Authoritative Assertions**:
  - Service is running as `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
  - Database records 1 play in `history` with context `'search'`.
  - Notification reflects active playback with Play/Pause and Next controls.

### Scenario 2 (SC-T4-02): Commute Scenario with Intermittent Offline & Headphone Disconnect
* **Workflow Steps**:
  1. User creates custom playlist "Commute Mix" with 5 tracks.
  2. User taps "Скачать всё" (Batch download); DownloadManager enqueues all 5 tracks.
  3. Downloads process with max 2 concurrent slots; all 5 complete and are marked `is_downloaded = 1`.
  4. Device enters subway (offline mode activated).
  5. User opens Library -> Offline tracks; taps "Воспроизвести все" with Shuffle enabled.
  6. Music plays directly from local storage without network access.
  7. User accidentally pulls out wired headphones.
  8. `ACTION_AUDIO_BECOMING_NOISY` broadcast fires; playback pauses instantly.
  9. User plugs in Bluetooth headset and taps Play on headset.
  10. Audio resumes; user listens past 80% mark of Track 1.
* **Authoritative Assertions**:
  - Track 1 play is recorded as `completed = 1` in `history`.
  - Zero network requests occurred while in offline mode.
  - No speaker audio leaked during disconnect.

### Scenario 3 (SC-T4-03): Multi-Source Music Discovery & Smart Playlist Curation
* **Workflow Steps**:
  1. User searches for an indie electronic track available exclusively on SoundCloud.
  2. User streams the SoundCloud track; audio plays via HLS stream resolver.
  3. User taps Favorite heart icon on full-screen player (`is_favorite = 1`).
  4. In Settings, user enables `auto_download_favorites = true`.
  5. System detects favorite toggle with auto-download enabled; enqueues track for download.
  6. Download completes; audio tags and cover art are written.
  7. User navigates to Library -> Smart Playlists -> "Любимые треки".
  8. The newly downloaded SoundCloud track is dynamically listed.
  9. User navigates to Settings -> "Экспорт резервной копии"; generates `NeDotifyBackupV1.json`.
* **Authoritative Assertions**:
  - Smart playlist SQL query accurately includes the track.
  - Exported backup contains track entry with `source: "soundcloud"`, stripped local paths, and clean metadata.

### Scenario 4 (SC-T4-04): Desktop-to-Mobile Migration & MediaStore Synchronization
* **Workflow Steps**:
  1. User transfers desktop backup `NeDotifyBackupV1.json` from PC containing Windows paths (`D:\Music\Rock\...`) and expired CDN links.
  2. User opens Settings -> "Импорт резервной копии" and selects file.
  3. Sanitizer wipes all Windows drive letters and expired CDN tokens; imports 200 tracks and 5 playlists into Room.
  4. User opens Playlist "Classic Rock"; selects 10 tracks and taps "Скачать".
  5. Tracks are downloaded into app internal storage.
  6. User navigates to Settings -> "Экспорт загруженной музыки в папку Music".
  7. MediaStore exporter writes files to public `/storage/emulated/0/Music/NeDotify/`.
* **Authoritative Assertions**:
  - Zero database rows contain Windows backslashes (`\`) or drive letters.
  - Android `MediaStore` contains 10 newly registered audio entries under `Music/NeDotify`.
  - External music players can detect and play the exported files.

### Scenario 5 (SC-T4-05): Interrupted Long Listening Session & Analytics Telemetry
* **Workflow Steps**:
  1. User starts listening to an ambient album (40 minutes duration).
  2. Heartbeat listener ticks every 5 seconds, accumulating milliseconds in `listening_stats`.
  3. At minute 15, incoming phone call interrupts playback (`AUDIOFOCUS_LOSS_TRANSIENT`).
  4. Playback pauses; user takes 5-minute call.
  5. Call ends; playback resumes automatically (`AUDIOFOCUS_GAIN`).
  6. At minute 25, GPS navigation prompt speaks: "Turn right in 200 meters" (`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`).
  7. Audio ducks to volume 0.20 for 4 seconds, then smoothly returns to 1.0.
  8. User listens until album track completes (reaching > 80% duration).
  9. User navigates to Home -> "Ваша статистика" (Wrapped).
* **Authoritative Assertions**:
  - `listening_stats` reflects exact audible time (~40 minutes, ignoring the 5-minute phone call pause).
  - Track in `history` has `completed = 1`, `listening_measured = 1`.
  - Home screen stats display aggregated minutes listened and top artist accurately.

---

## 6. Authoritative Output Derivation Formulas & Invariants

To eliminate arbitrary or tautological test expectations, all test assertions are bound to authoritative mathematical formulas and invariants:

1. **LRC Timestamp Milliseconds Calculation**:
   $$\text{timeMs} = (\text{minutes} \times 60 + \text{seconds}) \times 1000 + \text{millis} + \text{offsetMs}$$
   - Centiseconds `[mm:ss.xx]`: $\text{millis} = xx \times 10$
   - Milliseconds `[mm:ss.xxx]`: $\text{millis} = xxx$
   - Minimum clamp: $\text{timeMs} = \max(0, \text{timeMs})$

2. **CDN Expiration Calculation**:
   $$\text{isExpired} = (\text{expireTimestamp} \le \text{currentTimeSeconds} + \text{marginSeconds})$$
   - Default safety margin: $60\text{ seconds}$

3. **Measured Listening Completion Invariant**:
   $$\text{isCompleted} = (\text{mediaSeconds} \ge \text{durationSeconds} \times 0.80)$$
   - Only audible media playback scaled by playback rate counts towards `mediaSeconds`. Direct seeking does not increment `mediaSeconds`.

4. **Shuffle Mechanics Invariants**:
   - Let queue be $Q = [t_0, t_1, \dots, t_{n-1}]$ with current index $k$.
   - Shuffled queue $Q' = [t_k, \text{permute}(Q \setminus \{t_k\})]$.
   - Restoring shuffle restores $Q$ with current index set to index of $t_k$ in $Q$.
   - Invariant: $|Q'| = |Q|$ and $\text{set}(Q') = \text{set}(Q)$.

5. **Download Concurrency Invariant**:
   $$\text{Count}(\text{tasks with status } \texttt{'downloading'}) \le 2$$

6. **Windows Path Sanitization Invariant**:
   $$\forall p \in \text{tracks.filePath}: \quad p \text{ does not match } \texttt{\textasciicircum}[a-zA-Z]:[/\backslash] \quad \land \quad \backslash \notin p$$

---

## 7. Execution Commands & Continuous Verification

### 7.1. Running the Test Suite
* **Execute All Unit & Integration Tests (Tiers 1-3)**:
  ```powershell
  cd c:\Users\Артур\NeDotify\android
  ./gradlew testDebugUnitTest --continue
  ```
* **Execute Specific Feature Test**:
  ```powershell
  ./gradlew testDebugUnitTest --tests "com.nedotify.network.SoundCloudEngineTest"
  ./gradlew testDebugUnitTest --tests "com.nedotify.service.PlaybackQueueManagerTest"
  ./gradlew testDebugUnitTest --tests "com.nedotify.data.RoomDatabaseTier1Test"
  ```
* **Execute E2E Scenarios (Tier 4)**:
  ```powershell
  ./gradlew testDebugUnitTest --tests "com.nedotify.e2e.UserWorkflowsTier4Test"
  ```
* **Execute Lint Verification**:
  ```powershell
  ./gradlew lintDebug
  ```
* **Build Debug APK**:
  ```powershell
  ./gradlew assembleDebug
  ```

### 7.2. Two-Subagent Independent Review Logging Protocol
In accordance with `ORIGINAL_REQUEST.md § R5`, all verification outcomes must be independently reviewed by two subagents and logged in `android/PORTING_STATUS.md`:
- **Subagent A (Architecture & Functionality)**: Validates Media3 lifecycle, Room WAL integrity, background survivability, and download concurrency.
- **Subagent B (UI/UX & Accessibility)**: Validates Midnight `#0A0E17` palette, touch targets >= 48dp, hardware blur fallback on API 26-30, and kinetic lyrics rendering.
