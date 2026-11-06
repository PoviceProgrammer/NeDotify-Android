# State ownership contract

Status: implemented source ownership after PO selected variant B. MusicViewModel was deleted; first main Kotlin compilation passed. Full source-gate evidence belongs to review/VALIDATION.md at the matching source digest; Android/process-death acceptance remains NOT_RUN.

| State | Runtime owner | Persistence / recovery | UI owner |
|---|---|---|---|
| Queue, index, repeat/shuffle | PlaybackService / player | Existing queue DataStore, service restores paused | PlaybackConnection read-only projection |
| Position and playing/buffering | Service player | Queue checkpoints; reconnect to living service | Separate PlaybackConnection position flow; player leaf composables |
| Controller connection | Application-scoped PlaybackConnection | Recreated after process death; no stored controller | Connecting/connected/error state + bounded commands |
| Search request/results | SearchViewModel | SavedStateHandle query/source/category; rerun when search visible | SearchUiState; cancellation + stale-response rejection |
| Collection/favorites/playlists | Room / LibraryRepository | Existing database v4 and migrations | LibraryViewModel; only ID/filter/sort/scroll in saved state |
| Download execution | WorkManager | Existing durable work, reconciled on startup | DownloadsViewModel |
| Download catalogue | Room / Downloads coordinator | Existing download rows and packages | Read-only state plus commands |
| Settings/audio | Existing DataStore stores | Existing keys; service applies audio effects | SettingsViewModel |
| Lyrics | Lyrics repository + LyricsViewModel | Existing cache, request current track ID | LyricsUiState, panel and reading position |
| Listening history | Service ListeningHistory | Room, measured time only | ProfileViewModel derived reports |
| Auth/secrets | Provider repository / secure store | Existing Keystore/PKCE storage | Status only, never secrets |
| Catalogue imports | CatalogImportController | Existing import journal | Catalogue UI projection + explicit commands |
| Navigation/overlays | AppShell | Small typed destinations and selection parameters | Each tab retains its own stack |

## Recovery invariants

1. Activity recreation does not recreate the player or canonical queue.
2. Process recovery reconnects or restores the last durable queue **paused**.
3. Pause/seek checkpoints must be verified through the actual write path. Abrupt termination may lose the most recent 30–35 s; onDestroy is not guaranteed.
4. No Track lists, artwork bytes, stream URLs, or credentials in route arguments/SavedStateHandle. Group routes carry query/source/group key and refetch.
5. Missing saved IDs produce an empty/recovery screen; never crash or silently choose a different item.
6. Downloader reconciliation must not create duplicate work.
7. Only timeline/mini-player/current lyrics subscribe to frequent position updates.
8. No new all-purpose facade replacing MusicViewModel. Feature Content composables accept immutable UiState + actions; route containers own subscriptions.

## Migration order and exits

After W4: PlaybackConnection → SearchViewModel → library/downloads → settings/profile/lyrics/transfers → typed navigation → remove MusicViewModel and all references → recovery/resource regression. Storage formats and app identity stay compatible; a required storage change needs a separate tested migration.
