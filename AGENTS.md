# Android port rules

- Latest user instruction permits subagents specifically to WRITE code in parallel,
  not merely to review the main agent's changes. Keep file ownership separate.
  Do not simulate independent review or report it as completed without real reports.
- On 2026-11-06 the user authorized a debug APK for manual testing. Build
  `:app:assembleDebug` and deliver the APK with source identity and SHA-256.
  Source-only verification remains separate. Device installation/execution and
  release publishing require their own authorization; the user will test this APK.
- Native Kotlin / Compose / Media3. No desktop Python runtime or WebView shell.
- Keep changes inside android/ unless a desktop export or other shared change is
  explicitly needed and described.
- **The library of this product lives on the device, not in `~/.nedotify/`.**
  `~/.nedotify/` is the profile of the WINDOWS desktop application and cannot
  exist on an Android device; protecting it here protected nothing. The
  device's own library is:
  - the Room database `nedotify-android.db` (tracks, history, playlists);
  - the DataStore files in `filesDir/datastore/`: `nedotify.preferences_pb`
    (settings), `nedotify_queue.preferences_pb` (the queue),
    `nedotify_audio.preferences_pb` (the audio settings) and
    `nedotify_resets.preferences_pb` (the pending reset notices);
  - the library identity `filesDir/nedotify_library_id`;
  - the offline packages `filesDir/offline-media/` and the catalogue import
    journal `filesDir/catalog-import-v1.json`;
  - the provider secrets `noBackupFilesDir/provider-secrets/`, encrypted with a
    key that never leaves the Android Keystore (alias
    `nedotify.provider-secrets.v1`).
  None of them may be deleted, cleared or rewritten by a test or a tool. The
  desktop profile `~/.nedotify/` of the host must not be touched either, but
  it is not the library of this product.
- Run verify.ps1 or Gradle for Android checks. The root venv requirement applies
  to Python commands, not to Gradle, Java, adb or PowerShell.
- Player ownership stays in MediaSessionService, not in a composable or Activity.
  Do not fetch stream URLs for the entire queue before starting playback.
- Preserve minimum API 26: SQLite SQL and resource attributes must work there.
- Use actual listening time; seeking or a track's metadata duration is not evidence
  of listening. Never treat partial streaming cache as completed offline downloads.
- Record unimplemented features and unavailable device checks in PORTING_STATUS.md.
  A build or host HTTP probe does not establish audio playback on Android.
- When sources change after a review snapshot, regenerate review/SOURCE_SHA256.txt.
