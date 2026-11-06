# W5 state ownership

The former `MusicViewModel` has been removed. Feature classes live in
`ru.nedotify.app.ui.state`; existing queue/lyrics/report value types remain in
`ru.nedotify.app.ui.UiModels` (package `ui`) for existing helper compatibility.
All Android feature ViewModels have the public `(Application, SavedStateHandle)`
constructor and use the Activity's saved-state-aware default factory.

## Runtime owners

| State | Owner / persistence | UI projection |
|---|---|---|
| Decoder, queue, active occurrence, repeat/shuffle, actual position | MediaSessionService / existing queue DataStore checkpoint | Application-owned PlaybackConnection read-only playback + independent position StateFlows |
| Controller connection and pending selection | One lazy NeDotifyApp.playbackConnection, Main.immediate scope | Connection status and explicit transport/queue/select commands |
| Query, source, category and selected result group | SearchViewModel/SearchRequestController / SavedStateHandle small values | SearchState; results remain memory-only and rerun on active restored route |
| Tracks, playlists and favorites | Room / LibraryViewModel projections | Tracks/playlists/counts; selected ID/filter/sort in SavedStateHandle |
| Home recommendations and recent collection | HomeViewModel + DiscoveryController / Room and repository | Read-only data flows; playback explicitly passed separately |
| Download execution | Existing WorkManager/Downloads and Room | DownloadsViewModel list/status projections and commands |
| Appearance and audio configuration | Existing separate DataStores | SettingsViewModel; it creates no player/controller |
| Measured sessions | Service ListeningHistory and Room | ProfileViewModel report projection and saved report period |
| Lyrics requests | LyricsViewModel; existing disk cache | LyricsState tied to current player ID; job cancelled on track change |
| Credentials and account generations | Provider repository + protected store | ConnectionsViewModel status/catalog; no secrets in saved state |
| Durable catalog import | Application-scoped CatalogImportController + existing journal | ConnectionsViewModel import progress and commands |
| Backup preview/import and local-file attachment | TransfersViewModel + repository private staging | Counts/capability in memory; process death safely requires reselecting preview |
| Platform prompts, notices, requested route | UiEvents | Notification/file/avatar/browser events; no domain state |
| Back stacks and overlays | Root AppNavigation/AppShell | IDs, filters, bounded saveable state; no track lists or URLs |

## Recovery and cancellation

- Activity/feature ViewModel recreation does not close or recreate the shared
  controller. Only the connection's process/test teardown closes its scope.
- Process recreation constructs a new connection; the service restores its
  persisted queue paused. Controller state never becomes a second canonical queue.
- Search construction does not issue a restored network query. Route
  `onActive(true)` runs it; `onActive(false)` cancels it. Generation checks and
  coroutine activity reject a late, noncooperative provider response.
- Partial provider success is retained with `SearchState.warning`, independently
  of a fatal result error. Offline switches the route to local search.
- Position polling runs only while its flow is subscribed and the player is
  playing. Screens that render collection data do not subscribe to position.
- Catalog playback uses the same preparation generation as ordinary selections.
  Closing a catalog cancels only the selection owned by that catalog; it cannot
  cancel a later selection from another screen.
- Library deletion waits for pending playback preparation before removing queue
  occurrences, download and library data. File grants are released only after
  durable profile/track references are checked.
- Feature jobs use viewModelScope; durable imports and download execution keep
  their existing application/WorkManager ownership.

## Saved-state keys

Search: `query` (up to 200 characters), `source`, `category`, `search_group`.
Library: `playlist`, `library_filter`, `library_sort`, `smart_playlist`.
Profile: `report_period`.
Catalog: `catalog_provider`, `catalog_query` (up to 200 characters).
No token, full result collection, image, audio bytes or temporary signed URL is
stored in these handles. Deleted saved playlist IDs are distinguishable from an
initial empty projection via `LibraryViewModel.playlistsLoaded`.

This document describes the implemented source design. Source compilation,
behavioral tests and device evidence are recorded separately by the integration
gate; the existence of these classes is not an Android runtime claim.
